package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/broker"
	"github.com/koshereats/backend/internal/models"
	// Aliased because DeliverOrder has a local int named `payout`.
	payoutwf "github.com/koshereats/backend/internal/payout"
)

// Courier-facing order endpoints: the marketplace loop.
//
// Flow:
//   1. Courier goes online (SetOnline)
//   2. Courier polls ListAvailableDeliveries — orders in 'ready' status with no courier
//   3. Courier ClaimOrder — sets courier_id and claimed_at (no status change; still 'ready')
//   4. Courier drives to restaurant, hits PickupOrder — status 'ready' -> 'picked_up'
//   5. Courier drives to customer, hits DeliverOrder — status 'picked_up' -> 'delivered'
//
// Every 5-10s while on an active delivery the app posts to UpdateLocation
// so the consumer's live map tracks the courier.

type SetOnlineRequest struct {
	Online bool    `json:"online"`
	Lat    float64 `json:"lat"`
	Lng    float64 `json:"lng"`
}

// SetOnline toggles courier availability. Blocks non-approved couriers.
func (h *Handler) SetCourierOnline(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req SetOnlineRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET is_online = $1, last_lat = $2, last_lng = $3, last_location_at = NOW(),
		       updated_at = NOW()
		 WHERE user_id = $4 AND onboarding_status = 'approved'`,
		req.Online, req.Lat, req.Lng, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusForbidden, "courier not approved to go online")
		return
	}

	writeJSON(w, http.StatusOK, map[string]bool{"is_online": req.Online})
}

// ListAvailableDeliveries returns orders in 'ready' status with no courier claimed.
// Accepts optional ?lat=&lng= to sort by distance. In prod we'd also filter by a radius.
func (h *Handler) ListAvailableDeliveries(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var status models.CourierOnboardingStatus
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT onboarding_status FROM courier_profiles WHERE user_id = $1`, user["user_id"],
	).Scan(&status)
	if err != nil || status != models.OnboardingApproved {
		writeError(w, http.StatusForbidden, "courier not approved")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		// PRIVACY: this feed is visible to EVERY approved courier before anyone
		// claims, so it must not expose a customer's exact home. Withhold the
		// street/unit string and coarsen the dropoff coords to ~block level
		// (3 decimals ≈ 110m) — enough for a courier to judge distance/area and
		// decide whether to claim. The full address + precise coords are revealed
		// only to the assigned courier after claim (loadOrderWithCourier).
		`SELECT o.id, o.restaurant_id, rest.name, o.status, o.subtotal, o.delivery_fee,
		        o.service_fee, o.tax, o.total, o.courier_tip, '' AS delivery_address,
		        ROUND(o.delivery_lat::numeric, 3)::double precision AS delivery_lat,
		        ROUND(o.delivery_lng::numeric, 3)::double precision AS delivery_lng,
		        rest.lat, rest.lng, o.est_delivery_time, o.created_at, o.updated_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.status = 'ready' AND o.courier_id IS NULL
		    AND o.fulfillment_type = 'delivery'
		    -- Only KE-fleet ('platform') orders are claimable here. Self-delivery
		    -- ('restaurant') orders are the seller's; external-mode or already-
		    -- escalated orders go to Uber/DoorDash (external_provider set). Showing
		    -- or letting a courier claim those = double delivery + wrong payout.
		    AND o.external_provider IS NULL
		    AND COALESCE(rest.delivery_mode, 'platform') = 'platform'
		  ORDER BY o.created_at ASC
		  LIMIT 50`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch available deliveries")
		return
	}
	defer rows.Close()

	type AvailableDelivery struct {
		models.Order
		RestaurantLat float64 `json:"restaurant_lat"`
		RestaurantLng float64 `json:"restaurant_lng"`
	}

	var list []AvailableDelivery
	for rows.Next() {
		var d AvailableDelivery
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.RestaurantName, &d.Status,
			&d.Subtotal, &d.DeliveryFee, &d.ServiceFee, &d.Tax, &d.Total,
			&d.CourierTip, &d.DeliveryAddress, &d.DeliveryLat, &d.DeliveryLng,
			&d.RestaurantLat, &d.RestaurantLng,
			&d.EstDeliveryTime, &d.CreatedAt, &d.UpdatedAt); err != nil {
			continue
		}
		list = append(list, d)
	}

	if list == nil {
		list = []AvailableDelivery{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"deliveries": list})
}

// ListUpcomingDeliveries returns orders in 'accepted' or 'preparing' status
// with no courier yet — orders that aren't claimable yet but will be soon.
// Lets approved couriers position themselves near the restaurant ahead of
// the status flip to 'ready', cutting the dwell time between a kitchen
// finishing the order and a courier arriving for pickup.
//
// Same payload shape as ListAvailableDeliveries; clients differentiate by
// the order's `status` field. Customer/dropoff details are intentionally
// omitted from this list since the courier hasn't claimed yet — only
// restaurant info is exposed.
func (h *Handler) ListUpcomingDeliveries(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var status models.CourierOnboardingStatus
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT onboarding_status FROM courier_profiles WHERE user_id = $1`, user["user_id"],
	).Scan(&status)
	if err != nil || status != models.OnboardingApproved {
		writeError(w, http.StatusForbidden, "courier not approved")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.restaurant_id, rest.name, o.status, o.subtotal, o.delivery_fee,
		        o.service_fee, o.tax, o.total, o.courier_tip,
		        '' AS delivery_address, 0::float AS delivery_lat, 0::float AS delivery_lng,
		        rest.lat, rest.lng, o.est_delivery_time, o.created_at, o.updated_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.status IN ('accepted', 'preparing')
		    AND o.courier_id IS NULL
		    AND o.fulfillment_type = 'delivery'
		    -- KE-fleet orders only — don't surface self-delivery or external-mode
		    -- restaurants' orders for couriers to pre-position on (parity with
		    -- ListAvailableDeliveries).
		    AND o.external_provider IS NULL
		    AND COALESCE(rest.delivery_mode, 'platform') = 'platform'
		  ORDER BY o.created_at ASC
		  LIMIT 50`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch upcoming deliveries")
		return
	}
	defer rows.Close()

	type AvailableDelivery struct {
		models.Order
		RestaurantLat float64 `json:"restaurant_lat"`
		RestaurantLng float64 `json:"restaurant_lng"`
	}

	var list []AvailableDelivery
	for rows.Next() {
		var d AvailableDelivery
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.RestaurantName, &d.Status,
			&d.Subtotal, &d.DeliveryFee, &d.ServiceFee, &d.Tax, &d.Total,
			&d.CourierTip, &d.DeliveryAddress, &d.DeliveryLat, &d.DeliveryLng,
			&d.RestaurantLat, &d.RestaurantLng,
			&d.EstDeliveryTime, &d.CreatedAt, &d.UpdatedAt); err != nil {
			continue
		}
		list = append(list, d)
	}

	if list == nil {
		list = []AvailableDelivery{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"deliveries": list})
}

// ClaimOrder atomically assigns the order to this courier iff no one else has.
func (h *Handler) ClaimOrder(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	orderID := chi.URLParam(r, "id")

	// Only approved couriers can claim
	var status models.CourierOnboardingStatus
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT onboarding_status FROM courier_profiles WHERE user_id = $1`, user["user_id"],
	).Scan(&status)
	if err != nil || status != models.OnboardingApproved {
		writeError(w, http.StatusForbidden, "courier not approved")
		return
	}

	// Atomic claim: only succeeds if courier_id is still NULL AND the order isn't
	// mid/post external dispatch (external_provider set to 'dispatching' or a real
	// provider) — a KE courier must not claim an order already going to Uber.
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders
		   SET courier_id = $1, claimed_at = NOW(), updated_at = NOW()
		  FROM restaurants rest
		 WHERE orders.id = $2 AND orders.restaurant_id = rest.id
		   AND orders.status = 'ready' AND orders.courier_id IS NULL
		   AND orders.external_provider IS NULL
		   -- Couriers only deliver — a pickup order is collected by the customer,
		   -- never claimed/paid out to a courier (the feed already hides them, but
		   -- guard the direct claim call too).
		   AND orders.fulfillment_type = 'delivery'
		   -- A self-delivery ('restaurant') order has external_provider NULL, so the
		   -- guard above doesn't stop a KE courier from poaching it — exclude
		   -- non-platform delivery modes explicitly.
		   AND COALESCE(rest.delivery_mode, 'platform') = 'platform'`,
		user["user_id"], orderID)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusConflict, "order no longer available")
		return
	}

	// Notify consumer + seller that a courier has been assigned.
	var consumerID, restaurantID, courierFirst string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT o.user_id, o.restaurant_id, u.first_name
		   FROM orders o JOIN users u ON u.id = o.courier_id
		  WHERE o.id = $1`, orderID,
	).Scan(&consumerID, &restaurantID, &courierFirst); err != nil {
		slog.Warn("failed to fetch notification data after order claim",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
	}
	if consumerID != "" {
		go h.notify.OrderClaimed(context.Background(), orderID, consumerID, restaurantID, courierFirst)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "claimed"})
}

// PickupOrder: courier has arrived at restaurant and collected the food.
// Transitions 'ready' -> 'picked_up'.
// requireApprovedCourier re-asserts the caller is a CURRENTLY-approved courier.
// Approval at claim time isn't enough: an admin can suspend a courier
// mid-delivery, and a suspended courier must not keep progressing orders (taking
// the food, getting paid). Writes a 403 and returns false when not approved.
func (h *Handler) requireApprovedCourier(w http.ResponseWriter, r *http.Request, userID string) bool {
	var status models.CourierOnboardingStatus
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT onboarding_status FROM courier_profiles WHERE user_id = $1`, userID).Scan(&status)
	if err != nil || status != models.OnboardingApproved {
		writeError(w, http.StatusForbidden, "courier not approved")
		return false
	}
	return true
}

func (h *Handler) PickupOrder(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if !h.requireApprovedCourier(w, r, user["user_id"]) {
		return
	}
	orderID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders
		   SET status = 'picked_up', picked_up_at = NOW(), updated_at = NOW()
		 WHERE id = $1 AND courier_id = $2 AND status = 'ready'`,
		orderID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot mark this order picked up")
		return
	}

	var consumerID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID); err != nil {
		slog.Warn("failed to fetch consumer for pickup notification",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
	}
	if consumerID != "" {
		go h.notify.OrderPickedUp(context.Background(), orderID, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "picked_up"})
}

// DeliverOrder: courier has handed the order to the customer.
// Transitions 'picked_up' -> 'delivered' and bumps total_deliveries.
func (h *Handler) DeliverOrder(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if !h.requireApprovedCourier(w, r, user["user_id"]) {
		return
	}
	orderID := chi.URLParam(r, "id")

	var req struct {
		ProofURL string `json:"proof_url"`
	}
	// Body is optional — courier app sends proof URL when a photo was taken.
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.ProofURL != "" {
		if len(req.ProofURL) > 2048 || !strings.HasPrefix(req.ProofURL, "https://") {
			writeError(w, http.StatusBadRequest, "invalid proof_url")
			return
		}
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin tx")
		return
	}
	defer tx.Rollback(r.Context())

	var deliveryFee, tip, subtotal int
	if err := tx.QueryRow(r.Context(),
		`SELECT delivery_fee, courier_tip, subtotal FROM orders WHERE id = $1`, orderID,
	).Scan(&deliveryFee, &tip, &subtotal); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load order payout fields")
		return
	}
	// Courier earns the delivery fee (their labor) + 100% of the tip. The old
	// "+2.5% of subtotal" platform bump was funded by a service fee that is now
	// hard-coded 0, so it was paid out of nothing — a structural loss on every
	// in-house delivery. Removed. (Courier payout only applies to platform-mode
	// in-house deliveries; external-mode orders are delivered + billed by the
	// provider, and orders.provider_fee_cents records that cost for accounting.)
	_ = subtotal
	payout := deliveryFee + tip

	result, err := tx.Exec(r.Context(),
		`UPDATE orders
		   SET status = 'delivered', delivered_at = NOW(),
		       courier_payout = $1, delivery_proof_url = COALESCE(NULLIF($4, ''), delivery_proof_url),
		       updated_at = NOW()
		 WHERE id = $2 AND courier_id = $3 AND status = 'picked_up'`,
		payout, orderID, user["user_id"], req.ProofURL)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot mark this order delivered")
		return
	}

	if _, err := tx.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET total_deliveries = total_deliveries + 1, updated_at = NOW()
		 WHERE user_id = $1`, user["user_id"]); err != nil {
		slog.Error("failed to increment courier delivery count",
			slog.String("courier_id", user["user_id"]), slog.String("error", err.Error()))
	}

	// Enqueue the courier payout inside the same transaction so it cannot
	// be silently dropped if the process dies between commit and insert.
	var connectID string
	if err := tx.QueryRow(r.Context(),
		`SELECT stripe_connect_id FROM courier_profiles WHERE user_id = $1`,
		user["user_id"]).Scan(&connectID); err != nil && !errors.Is(err, pgx.ErrNoRows) {
		slog.Warn("failed to fetch courier stripe_connect_id for payout enqueue",
			slog.String("courier_id", user["user_id"]), slog.String("error", err.Error()))
	}
	if connectID != "" && payout > 0 {
		if _, err := tx.Exec(r.Context(), `
			INSERT INTO courier_payout_queue
			    (order_id, courier_id, stripe_connect_id, amount_cents)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (order_id) DO NOTHING`,
			orderID, user["user_id"], connectID, payout); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to enqueue payout")
			return
		}
	}

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit")
		return
	}

	// Post-commit, GATED Temporal payout start. Only fires when the same
	// enqueue condition held above (connectID != "" && payout > 0) AND a
	// Temporal starter is wired (Enabled() is false on a nil *payout.Starter,
	// so when Temporal is disabled this branch is skipped and behavior is
	// byte-for-byte identical to before). Best-effort in the background on a
	// fresh context.Background() — the request context is done once the
	// handler returns. A lost start is covered by the reconcile sweep, so the
	// error is intentionally ignored.
	if h.payoutStarter.Enabled() && connectID != "" && payout > 0 {
		courierID := user["user_id"]
		payoutAmount := payout
		go func() {
			_ = h.payoutStarter.Start(context.Background(), payoutwf.PayoutInput{
				OrderID:         orderID,
				CourierID:       courierID,
				StripeConnectID: connectID,
				AmountCents:     payoutAmount,
			})
		}()
	}

	var consumerID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID); err != nil {
		slog.Warn("failed to fetch consumer for delivery notification",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
	}
	if consumerID != "" {
		go h.notify.OrderDelivered(context.Background(), orderID, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "delivered"})
}

// UpdateCourierLocation is the GPS heartbeat. Writes to courier_profiles.last_*
// (hot, for nearby-courier queries) AND appends to courier_locations (cold trail).
func (h *Handler) UpdateCourierLocation(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req models.CourierLocationPing
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Lat < -90 || req.Lat > 90 || req.Lng < -180 || req.Lng > 180 {
		writeError(w, http.StatusBadRequest, "lat must be [-90,90] and lng must be [-180,180]")
		return
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET last_lat = $1, last_lng = $2, last_location_at = NOW(), updated_at = NOW()
		 WHERE user_id = $3`,
		req.Lat, req.Lng, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update location")
		return
	}

	// Attach to active order if any, for the delivery trail.
	var activeOrderID *string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM orders
		  WHERE courier_id = $1 AND status IN ('ready', 'picked_up')
		  ORDER BY claimed_at DESC LIMIT 1`, user["user_id"],
	).Scan(&activeOrderID); err != nil && !errors.Is(err, pgx.ErrNoRows) {
		slog.Warn("failed to look up active order for location trail",
			slog.String("courier_id", user["user_id"]), slog.String("error", err.Error()))
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO courier_locations (courier_id, order_id, lat, lng, heading, speed)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		user["user_id"], activeOrderID, req.Lat, req.Lng, req.Heading, req.Speed); err != nil {
		slog.Warn("failed to record courier location trail",
			slog.String("courier_id", user["user_id"]), slog.String("error", err.Error()))
	}

	// Fan out to any consumer SSE streams watching this order.
	if activeOrderID != nil && *activeOrderID != "" {
		h.location.Publish(broker.LocationEvent{
			OrderID: *activeOrderID,
			Lat:     req.Lat,
			Lng:     req.Lng,
			Heading: req.Heading,
			Speed:   req.Speed,
			At:      time.Now().UTC(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ListCourierActiveOrders returns the currently-claimed order(s) for this courier.
func (h *Handler) ListCourierActiveOrders(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
		        o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
		        o.delivery_address, o.delivery_lat, o.delivery_lng,
		        rest.lat, rest.lng, rest.phone,
		        o.claimed_at, o.picked_up_at, o.created_at, o.updated_at,
		        consumer.first_name, consumer.phone
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		   LEFT JOIN users consumer ON o.user_id = consumer.id
		  WHERE o.courier_id = $1 AND o.status IN ('ready', 'picked_up')
		  ORDER BY o.claimed_at DESC`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch active orders")
		return
	}
	defer rows.Close()

	var list []models.Order
	for rows.Next() {
		var o models.Order
		var restPhone, consumerFirst, consumerPhone *string
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
			&o.RestaurantLat, &o.RestaurantLng, &restPhone,
			&o.ClaimedAt, &o.PickedUpAt, &o.CreatedAt, &o.UpdatedAt,
			&consumerFirst, &consumerPhone); err != nil {
			continue
		}
		o.CustomerName = strOr(consumerFirst, "")
		o.CustomerPhone = strOr(consumerPhone, "")
		list = append(list, o)
	}
	// A mid-iteration error stops Next() like end-of-rows; without this the
	// courier's in-flight delivery would silently vanish from their active list
	// (looks delivered/cancelled), stranding the handoff. Fail instead.
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load active orders")
		return
	}
	if list == nil {
		list = []models.Order{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"orders": list})
}

// ListCourierHistory returns completed deliveries (for earnings screen).
func (h *Handler) ListCourierHistory(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.restaurant_id, rest.name, o.status, o.total,
		        o.delivery_fee, o.courier_tip, o.courier_payout,
		        o.delivered_at, o.created_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.courier_id = $1 AND o.status = 'delivered'
		  ORDER BY o.delivered_at DESC
		  LIMIT 100`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch history")
		return
	}
	defer rows.Close()

	// JSON response shape. Timestamps are returned as RFC3339 strings so the
	// iOS client can decode them with the standard ISO8601 formatter.
	type HistoryRow struct {
		ID             string  `json:"id"`
		RestaurantID   string  `json:"restaurant_id"`
		RestaurantName string  `json:"restaurant_name"`
		Status         string  `json:"status"`
		Total          int     `json:"total"`
		DeliveryFee    int     `json:"delivery_fee"`
		CourierTip     int     `json:"courier_tip"`
		CourierPayout  int     `json:"courier_payout"`
		DeliveredAt    *string `json:"delivered_at,omitempty"`
		CreatedAt      string  `json:"created_at"`
	}

	var list []HistoryRow
	for rows.Next() {
		var h HistoryRow
		// Scan TIMESTAMPTZ columns into time.Time (pgx drivers) — scanning
		// into *string silently fails because there's no registered codec
		// for that conversion, which is why this endpoint was returning an
		// empty list even when deliveries existed.
		var deliveredAt *time.Time
		var createdAt time.Time
		if err := rows.Scan(&h.ID, &h.RestaurantID, &h.RestaurantName, &h.Status,
			&h.Total, &h.DeliveryFee, &h.CourierTip, &h.CourierPayout,
			&deliveredAt, &createdAt); err != nil {
			continue
		}
		if deliveredAt != nil {
			s := deliveredAt.Format(time.RFC3339)
			h.DeliveredAt = &s
		}
		h.CreatedAt = createdAt.Format(time.RFC3339)
		list = append(list, h)
	}
	if list == nil {
		list = []HistoryRow{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"orders": list})
}
