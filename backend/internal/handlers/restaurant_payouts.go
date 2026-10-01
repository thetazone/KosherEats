package handlers

import (
	"context"
	"encoding/base64"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/restaurantpayout"
)

// Restaurant (seller) payouts over Stripe Connect, plus the per-order payout /
// sales-tax pass-through statement.
//
// Onboarding mirrors the courier flow (courier_payouts.go) but is keyed on the
// RESTAURANT, not the user: each restaurant gets its own Express account
// (restaurants.stripe_connect_id / payout_ready). The seller's restaurant is
// resolved exactly like every other /seller endpoint (resolveSellerRestaurant:
// ?restaurant_id= the seller owns, else their first restaurant).
//
// Money movement itself lives in internal/restaurantpayout: every order that
// reaches a successful terminal state gets one ledger line, and the scheduler
// sweep transfers the net once the account is ready and
// RESTAURANT_PAYOUTS_ENABLED is on.

// RestaurantPayoutStatusResponse is POST /seller/payouts/account and
// GET /seller/payouts/status.
type RestaurantPayoutStatusResponse struct {
	PayoutReady      bool   `json:"payout_ready"`
	ConnectID        string `json:"connect_id,omitempty"`
	DetailsSubmitted bool   `json:"details_submitted"`
}

// RestaurantPayoutLine is one row of GET /seller/payouts. The orders table has
// no human-facing order number, so order_number is omitted.
type RestaurantPayoutLine struct {
	ID                 string     `json:"id"`
	OrderID            string     `json:"order_id"`
	CompletedAt        time.Time  `json:"completed_at"`
	Fulfillment        string     `json:"fulfillment"`
	FoodSubtotalCents  int        `json:"food_subtotal_cents"`
	SalesTaxCents      int        `json:"sales_tax_cents"`
	DeliveryFeeCents   int        `json:"delivery_fee_cents"`
	TipCents           int        `json:"tip_cents"`
	KEFeeCents         int        `json:"ke_fee_cents"`
	ProcessingFeeCents int        `json:"processing_fee_cents"`
	NetCents           int        `json:"net_cents"`
	Status             string     `json:"status"`
	TransferID         *string    `json:"transfer_id"`
	PaidAt             *time.Time `json:"paid_at"`
}

// RestaurantPayoutListResponse is GET /seller/payouts.
type RestaurantPayoutListResponse struct {
	Lines      []RestaurantPayoutLine `json:"lines"`
	NextCursor string                 `json:"next_cursor,omitempty"`
}

// RestaurantPayoutSummaryResponse is GET /seller/payouts/summary.
type RestaurantPayoutSummaryResponse struct {
	From               string `json:"from"`
	To                 string `json:"to"`
	Orders             int    `json:"orders"`
	FoodSubtotalCents  int    `json:"food_subtotal_cents"`
	SalesTaxCents      int    `json:"sales_tax_cents"`
	DeliveryFeeCents   int    `json:"delivery_fee_cents"`
	TipCents           int    `json:"tip_cents"`
	KEFeeCents         int    `json:"ke_fee_cents"`
	ProcessingFeeCents int    `json:"processing_fee_cents"`
	NetCents           int    `json:"net_cents"`
	PaidCents          int    `json:"paid_cents"`
	PendingCents       int    `json:"pending_cents"`
}

const (
	defaultPayoutPageSize = 50
	maxPayoutPageSize     = 200
	// maxSummaryDays bounds one summary query (a year and a leap day).
	maxSummaryDays = 366
)

// statementTZ is the zone statement dates are interpreted in.
const statementTZ = "America/New_York"

// recordRestaurantPayoutLineTx records the order's restaurant payout ledger
// line inside the terminal-state transaction (under a savepoint, so a ledger
// failure never aborts the delivery itself — the sweep backfills it).
func (h *Handler) recordRestaurantPayoutLineTx(ctx context.Context, tx pgx.Tx, site, orderID string) {
	_, err := restaurantpayout.RecordLineInTx(ctx, tx, orderID, h.deliveryMarkupCents)
	restaurantpayout.LogRecordError(site, orderID, err)
}

// recordRestaurantPayoutLine is the no-transaction variant (the terminal
// UPDATE already committed on its own).
func (h *Handler) recordRestaurantPayoutLine(ctx context.Context, site, orderID string) {
	_, err := restaurantpayout.RecordLine(ctx, h.db.Pool, orderID, h.deliveryMarkupCents)
	restaurantpayout.LogRecordError(site, orderID, err)
}

// SellerCreatePayoutAccount — POST /seller/payouts/account. Creates the
// restaurant's Stripe Express account if it has none, then reports status.
// Idempotent: an existing account is reused, and the Stripe create carries an
// idempotency key keyed on the restaurant so concurrent taps share one account.
func (h *Handler) SellerCreatePayoutAccount(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	var existingID, name, restEmail, ownerEmail string
	if err := h.db.Pool.QueryRow(r.Context(), `
		SELECT COALESCE(r.stripe_connect_id, ''), r.name, COALESCE(r.email, ''), COALESCE(u.email, '')
		  FROM restaurants r LEFT JOIN users u ON u.id = r.owner_id
		 WHERE r.id = $1`, restID).Scan(&existingID, &name, &restEmail, &ownerEmail); err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	if existingID != "" {
		h.writeRestaurantPayoutStatus(w, r, restID, existingID)
		return
	}

	email := restEmail
	if email == "" {
		email = ownerEmail
	}
	acctID, err := h.stripe.CreateRestaurantExpressAccount(restID, email, name)
	if err != nil {
		slog.Error("SellerCreatePayoutAccount: Stripe account create failed",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to create Stripe account")
		return
	}
	// Only fill an empty slot: if a concurrent request already stored an id,
	// keep that one (with the idempotency key it is normally the same account).
	if _, err := h.db.Pool.Exec(r.Context(), `
		UPDATE restaurants SET stripe_connect_id = $1, updated_at = NOW()
		 WHERE id = $2 AND COALESCE(stripe_connect_id, '') = ''`, acctID, restID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save connect id")
		return
	}
	var stored string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(stripe_connect_id, '') FROM restaurants WHERE id = $1`, restID).Scan(&stored); err != nil || stored == "" {
		writeError(w, http.StatusInternalServerError, "failed to save connect id")
		return
	}
	h.writeRestaurantPayoutStatus(w, r, restID, stored)
}

// SellerGetPayoutLink — GET /seller/payouts/link. A fresh Stripe-hosted
// onboarding link (they expire quickly, so clients fetch one per open).
func (h *Handler) SellerGetPayoutLink(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	var acctID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(stripe_connect_id, '') FROM restaurants WHERE id = $1`, restID).Scan(&acctID); err != nil || acctID == "" {
		writeError(w, http.StatusBadRequest, "no Stripe account — call /seller/payouts/account first")
		return
	}
	base := strings.TrimRight(h.cfg.WebURL, "/")
	url, err := h.stripe.CreateAccountLink(acctID, base+"/seller/payouts/return", base+"/seller/payouts/refresh")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create account link")
		return
	}
	writeJSON(w, http.StatusOK, PayoutLinkResponse{URL: url})
}

// SellerGetPayoutStatus — GET /seller/payouts/status. Re-reads the account from
// Stripe and refreshes restaurants.payout_ready.
func (h *Handler) SellerGetPayoutStatus(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	var acctID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(stripe_connect_id, '') FROM restaurants WHERE id = $1`, restID).Scan(&acctID); err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	if acctID == "" {
		writeJSON(w, http.StatusOK, RestaurantPayoutStatusResponse{})
		return
	}
	h.writeRestaurantPayoutStatus(w, r, restID, acctID)
}

func (h *Handler) writeRestaurantPayoutStatus(w http.ResponseWriter, r *http.Request, restID, acctID string) {
	status, err := h.stripe.GetAccountStatus(acctID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch Stripe status")
		return
	}
	ready := status.PayoutsEnabled && status.DetailsSubmitted
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET payout_ready = $1, updated_at = NOW() WHERE id = $2`, ready, restID); err != nil {
		slog.Error("writeRestaurantPayoutStatus: update payout_ready failed",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
	} else if err := restaurantpayout.SyncRestaurantLines(r.Context(), h.db.Pool, restID, ready); err != nil {
		slog.Error("writeRestaurantPayoutStatus: sync ledger lines failed",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
	}
	writeJSON(w, http.StatusOK, RestaurantPayoutStatusResponse{
		PayoutReady:      ready,
		ConnectID:        acctID,
		DetailsSubmitted: status.DetailsSubmitted,
	})
}

// encodePayoutCursor / decodePayoutCursor: an opaque keyset cursor over the
// (completed_at DESC, id DESC) ordering.
func encodePayoutCursor(t time.Time, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(t.UTC().Format(time.RFC3339Nano) + "|" + id))
}

func decodePayoutCursor(s string) (time.Time, string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return time.Time{}, "", err
	}
	ts, id, ok := strings.Cut(string(raw), "|")
	if !ok || id == "" {
		return time.Time{}, "", errors.New("malformed cursor")
	}
	t, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		return time.Time{}, "", err
	}
	return t, id, nil
}

// SellerListPayouts — GET /seller/payouts?limit=50&cursor=… newest first.
func (h *Handler) SellerListPayouts(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	limit := defaultPayoutPageSize
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeError(w, http.StatusBadRequest, "limit must be a positive integer")
			return
		}
		if n > maxPayoutPageSize {
			n = maxPayoutPageSize
		}
		limit = n
	}
	var cursorAt *time.Time
	var cursorID *string
	if c := r.URL.Query().Get("cursor"); c != "" {
		t, id, err := decodePayoutCursor(c)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid cursor")
			return
		}
		cursorAt, cursorID = &t, &id
	}

	rows, err := h.db.Pool.Query(r.Context(), `
		SELECT id, order_id, completed_at, fulfillment, food_subtotal_cents, sales_tax_cents,
		       delivery_fee_cents, tip_cents, ke_fee_cents, processing_fee_cents, net_cents,
		       status, transfer_id, paid_at
		  FROM restaurant_payout_lines
		 WHERE restaurant_id = $1
		   AND ($2::timestamptz IS NULL OR (completed_at, id) < ($2::timestamptz, $3::uuid))
		 ORDER BY completed_at DESC, id DESC
		 LIMIT $4`, restID, cursorAt, cursorID, limit+1)
	if err != nil {
		slog.Error("SellerListPayouts query failed", slog.String("restaurant_id", restID), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list payouts")
		return
	}
	defer rows.Close()

	resp := RestaurantPayoutListResponse{Lines: []RestaurantPayoutLine{}}
	for rows.Next() {
		var l RestaurantPayoutLine
		if err := rows.Scan(&l.ID, &l.OrderID, &l.CompletedAt, &l.Fulfillment, &l.FoodSubtotalCents,
			&l.SalesTaxCents, &l.DeliveryFeeCents, &l.TipCents, &l.KEFeeCents, &l.ProcessingFeeCents,
			&l.NetCents, &l.Status, &l.TransferID, &l.PaidAt); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to list payouts")
			return
		}
		l.Status = restaurantpayout.PublicStatus(l.Status)
		resp.Lines = append(resp.Lines, l)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list payouts")
		return
	}
	if len(resp.Lines) > limit {
		resp.Lines = resp.Lines[:limit]
		last := resp.Lines[limit-1]
		resp.NextCursor = encodePayoutCursor(last.CompletedAt, last.ID)
	}
	writeJSON(w, http.StatusOK, resp)
}

// SellerPayoutSummary — GET /seller/payouts/summary?from=YYYY-MM-DD&to=YYYY-MM-DD
// (inclusive, America/New_York calendar dates on the order's completion time).
//
// The statement totals (orders, food, tax, delivery, tip, fees, net) cover
// lines that still stand: fully refunded orders (void / reversed lines) are not
// sales and are excluded. A partially refunded order is included at its
// recorded amounts; the clawback shows up in paid_cents / pending_cents, which
// are net of reversed_cents. paid_cents = what the restaurant has received and
// kept; pending_cents = what is still owed (awaiting account, pending, or in
// flight). 'failed' lines count in the totals but in neither bucket.
func (h *Handler) SellerPayoutSummary(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	fromStr, toStr := r.URL.Query().Get("from"), r.URL.Query().Get("to")
	from, ferr := time.Parse("2006-01-02", fromStr)
	to, terr := time.Parse("2006-01-02", toStr)
	if ferr != nil || terr != nil {
		writeError(w, http.StatusBadRequest, "from and to are required as YYYY-MM-DD")
		return
	}
	if to.Before(from) {
		writeError(w, http.StatusBadRequest, "to must not be before from")
		return
	}
	if to.Sub(from) > maxSummaryDays*24*time.Hour {
		writeError(w, http.StatusBadRequest, "date range too long (max 366 days)")
		return
	}

	resp := RestaurantPayoutSummaryResponse{From: fromStr, To: toStr}
	err = h.db.Pool.QueryRow(r.Context(), `
		SELECT COUNT(*) FILTER (WHERE status NOT IN ('void', 'reversed')),
		       COALESCE(SUM(food_subtotal_cents)  FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(sales_tax_cents)      FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(delivery_fee_cents)   FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(tip_cents)            FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(ke_fee_cents)         FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(processing_fee_cents) FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(net_cents)            FILTER (WHERE status NOT IN ('void', 'reversed')), 0),
		       COALESCE(SUM(net_cents - reversed_cents) FILTER (WHERE status = 'paid'), 0),
		       COALESCE(SUM(net_cents - reversed_cents)
		                FILTER (WHERE status IN ('awaiting_account', 'pending', 'processing')), 0)
		  FROM restaurant_payout_lines
		 WHERE restaurant_id = $1
		   AND completed_at >= ($2::date::timestamp AT TIME ZONE '`+statementTZ+`')
		   AND completed_at <  (($3::date + 1)::timestamp AT TIME ZONE '`+statementTZ+`')`,
		restID, fromStr, toStr,
	).Scan(&resp.Orders, &resp.FoodSubtotalCents, &resp.SalesTaxCents, &resp.DeliveryFeeCents,
		&resp.TipCents, &resp.KEFeeCents, &resp.ProcessingFeeCents, &resp.NetCents,
		&resp.PaidCents, &resp.PendingCents)
	if err != nil {
		slog.Error("SellerPayoutSummary query failed", slog.String("restaurant_id", restID), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to summarize payouts")
		return
	}
	writeJSON(w, http.StatusOK, resp)
}
