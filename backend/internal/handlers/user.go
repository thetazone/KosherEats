package handlers

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/koshereats/backend/internal/models"
)

type UpdateProfileRequest struct {
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Phone     string `json:"phone"`
	// Email is optional. When non-empty it replaces the current email —
	// used by the Apple completion sheet to let users override the
	// @privaterelay.appleid.com address Apple supplies on first sign-in.
	Email string `json:"email,omitempty"`
}

type AddAddressRequest struct {
	Label   string  `json:"label"`
	Street  string  `json:"street"`
	Apt     string  `json:"apt"`
	City    string  `json:"city"`
	State   string  `json:"state"`
	ZipCode string  `json:"zip_code"`
	Lat     float64 `json:"lat"`
	Lng     float64 `json:"lng"`
}

func (h *Handler) GetProfile(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var u models.User
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, email, first_name, last_name, phone, role, vertical, avatar_url, email_verified, phone_verified, created_at, updated_at
		 FROM users WHERE id = $1`, user["user_id"],
	).Scan(&u.ID, &u.Email, &u.FirstName, &u.LastName, &u.Phone, &u.Role, &u.Vertical, &u.AvatarURL,
		&u.EmailVerified, &u.PhoneVerified, &u.CreatedAt, &u.UpdatedAt); err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}

	writeJSON(w, http.StatusOK, u)
}

func (h *Handler) UpdateProfile(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)

	var req UpdateProfileRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// Normalize email before persistence so uniqueness checks aren't
	// defeated by casing. Empty => "don't touch email".
	email := strings.TrimSpace(strings.ToLower(req.Email))
	ctx := r.Context()

	// SECURITY: profile edit no longer writes `phone`. Phone is a login factor
	// (phone-OTP login keys off it), and an unverified write let a user squat a
	// number / set up a hijack. Phone changes go through the OTP-verified flow
	// (StartPhoneChange + VerifyPhoneChange); req.Phone is ignored here.
	if email != "" {
		// Changing the address invalidates whatever proof the old one had
		// (OTP or provider-asserted), so email_verified only survives when
		// the email is unchanged — the comparison reads the OLD row value. A
		// consumer who swaps emails here re-verifies via the OTP flow before
		// they can transact again (sellers/couriers aren't gated). The ::text
		// casts keep $3's deduced type consistent across both sites (42P08).
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE users SET first_name = $1, last_name = $2, email = $3::text,
			   email_verified = (email = $3::text AND email_verified),
			   updated_at = NOW()
			 WHERE id = $4`,
			req.FirstName, req.LastName, email, user["user_id"])
		if err != nil {
			var pgErr *pgconn.PgError
			if errors.As(err, &pgErr) && pgErr.Code == "23505" {
				writeError(w, http.StatusConflict, "that email is already in use")
				return
			}
			writeError(w, http.StatusBadRequest, "failed to update profile")
			return
		}
	} else {
		if _, err := h.db.Pool.Exec(ctx,
			`UPDATE users SET first_name = $1, last_name = $2, updated_at = NOW()
			 WHERE id = $3`,
			req.FirstName, req.LastName, user["user_id"]); err != nil {
			writeError(w, http.StatusBadRequest, "failed to update profile")
			return
		}
	}

	var u models.User
	err := h.db.Pool.QueryRow(ctx,
		`SELECT id, email, first_name, last_name, phone, role, vertical, avatar_url, email_verified, phone_verified, created_at, updated_at
		 FROM users WHERE id = $1`, user["user_id"],
	).Scan(&u.ID, &u.Email, &u.FirstName, &u.LastName, &u.Phone, &u.Role, &u.Vertical, &u.AvatarURL,
		&u.EmailVerified, &u.PhoneVerified, &u.CreatedAt, &u.UpdatedAt)
	if err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}

	writeJSON(w, http.StatusOK, u)
}

func (h *Handler) ListAddresses(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, user_id, label, street, apt, city, state, zip_code, lat, lng, is_default
		 FROM addresses WHERE user_id = $1 ORDER BY is_default DESC, label`,
		user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch addresses")
		return
	}
	defer rows.Close()

	var addresses []models.Address
	for rows.Next() {
		var a models.Address
		if err := rows.Scan(&a.ID, &a.UserID, &a.Label, &a.Street, &a.Apt,
			&a.City, &a.State, &a.ZipCode, &a.Lat, &a.Lng, &a.IsDefault); err != nil {
			continue
		}
		addresses = append(addresses, a)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch addresses")
		return
	}

	if addresses == nil {
		addresses = []models.Address{}
	}

	writeJSON(w, http.StatusOK, addresses)
}

func (h *Handler) AddAddress(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)

	var req AddAddressRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Lat < -90 || req.Lat > 90 || req.Lng < -180 || req.Lng > 180 {
		writeError(w, http.StatusBadRequest, "lat must be [-90,90] and lng must be [-180,180]")
		return
	}

	var addr models.Address
	err := h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO addresses (user_id, label, street, apt, city, state, zip_code, lat, lng)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		 RETURNING id, user_id, label, street, apt, city, state, zip_code, lat, lng, is_default`,
		user["user_id"], req.Label, req.Street, req.Apt, req.City, req.State, req.ZipCode, req.Lat, req.Lng,
	).Scan(&addr.ID, &addr.UserID, &addr.Label, &addr.Street, &addr.Apt,
		&addr.City, &addr.State, &addr.ZipCode, &addr.Lat, &addr.Lng, &addr.IsDefault)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to add address")
		return
	}

	writeJSON(w, http.StatusCreated, addr)
}

func (h *Handler) DeleteAddress(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)
	addrID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM addresses WHERE id = $1 AND user_id = $2`,
		addrID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "address not found")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

// SetDefaultAddress flips is_default=true on the target address and clears it
// on every other address the user owns. Wrapped in a tx so we never end up
// with zero or two "default" rows if the second UPDATE fails.
func (h *Handler) SetDefaultAddress(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)
	addrID := chi.URLParam(r, "id")
	ctx := r.Context()

	tx, err := h.db.Pool.Begin(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to start transaction")
		return
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`UPDATE addresses SET is_default = false WHERE user_id = $1`, user["user_id"]); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to clear defaults")
		return
	}

	result, err := tx.Exec(ctx,
		`UPDATE addresses SET is_default = true WHERE id = $1 AND user_id = $2`,
		addrID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "address not found")
		return
	}

	if err := tx.Commit(ctx); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update default")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) DeleteAccount(w http.ResponseWriter, r *http.Request) {
	user, _ := getUserFromContext(r)
	uid := user["user_id"]
	ctx := r.Context()

	tx, err := h.db.Pool.Begin(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to start transaction")
		return
	}
	defer tx.Rollback(ctx)

	// Refuse the delete while the account still has a live order. The orders step
	// below anonymizes with `SET user_id = NULL` and has no status filter, so a
	// paid, non-terminal order (pending/accepted/preparing/ready/picked_up, plus a
	// scheduled one that hasn't been promoted yet) would be cut loose from its
	// customer while its PaymentIntent stays captured: the stale-rejection sweep
	// could no longer auto-reject + refund it, and auto-dispatch / mark-ready /
	// seller escalation all resolve the consumer through orders.user_id, so a
	// cooked order would become undeliverable. Money is on the line either way, so
	// the order has to reach a terminal state (delivered/completed/cancelled/
	// rejected — cancelling refunds) before the account can go.
	var liveOrders int
	if err := tx.QueryRow(ctx, `
		SELECT COUNT(*) FROM orders
		 WHERE user_id = $1
		   AND status NOT IN ('delivered', 'completed', 'cancelled', 'rejected')`,
		uid).Scan(&liveOrders); err != nil {
		slog.Error("DeleteAccount step failed",
			slog.String("user_id", uid), slog.String("step", "check live orders"),
			slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}
	if liveOrders > 0 {
		writeError(w, http.StatusConflict,
			"you have an order still in progress — cancel it or wait for it to be delivered before deleting your account")
		return
	}

	// Freeze any courier payouts still owed to this account before the user row
	// goes away. The FK is ON DELETE SET NULL (migration 060), so the queue rows
	// themselves survive the delete — same anonymize-don't-delete rule the orders
	// step below applies. But an unpaid row must not stay claimable by the payout
	// sweep once the courier and their Stripe Connect account are gone, so park
	// pending/processing rows in failed_permanent, where the admin failed-payout
	// view surfaces them for manual reconciliation.
	frozen, err := tx.Exec(ctx, `
		UPDATE courier_payout_queue
		   SET status = 'failed_permanent',
		       last_error = 'courier deleted their account with this payout outstanding; reconcile manually',
		       updated_at = NOW()
		 WHERE courier_id = $1
		   AND status IN ('pending', 'processing')`, uid)
	if err != nil {
		slog.Error("DeleteAccount step failed",
			slog.String("user_id", uid), slog.String("step", "freeze courier payouts"),
			slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}
	outstandingPayouts := frozen.RowsAffected()

	// Delete related data in dependency order. Each step is checked so a failure
	// short-circuits with the real root-cause logged, rather than only surfacing
	// at the final DELETE/Commit with the underlying error lost.
	steps := []struct {
		desc string
		sql  string
	}{
		{"delete device_tokens", `DELETE FROM device_tokens WHERE user_id = $1`},
		{"delete cart_items", `DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id = $1)`},
		{"delete carts", `DELETE FROM carts WHERE user_id = $1`},
		{"delete addresses", `DELETE FROM addresses WHERE user_id = $1`},
		// Anonymize orders (keep for accounting) rather than deleting.
		{"anonymize orders.user_id", `UPDATE orders SET user_id = NULL WHERE user_id = $1`},
		// Deactivate restaurants owned by this seller and unlink owner.
		{"deactivate restaurants", `UPDATE restaurants SET is_active = false, owner_id = NULL WHERE owner_id = $1`},
		// Anonymize courier assignments on orders.
		{"anonymize orders.courier_id", `UPDATE orders SET courier_id = NULL WHERE courier_id = $1`},
		{"delete courier_locations", `DELETE FROM courier_locations WHERE courier_id = $1`},
		{"delete courier_profiles", `DELETE FROM courier_profiles WHERE user_id = $1`},
		{"delete chat_messages", `DELETE FROM chat_messages WHERE sender_user_id = $1`},
		{"delete courier_ratings", `DELETE FROM courier_ratings WHERE consumer_id = $1`},
		// Delete the user.
		{"delete users", `DELETE FROM users WHERE id = $1`},
	}
	for _, step := range steps {
		if _, err := tx.Exec(ctx, step.sql, uid); err != nil {
			slog.Error("DeleteAccount step failed",
				slog.String("user_id", uid), slog.String("step", step.desc),
				slog.String("error", err.Error()))
			writeError(w, http.StatusInternalServerError, "failed to delete account")
			return
		}
	}

	if err := tx.Commit(ctx); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}

	// Money still owed to a courier who just walked away needs a human, not
	// only a row in a table nobody is watching.
	if outstandingPayouts > 0 {
		slog.Warn("DeleteAccount: courier deleted their account with outstanding payouts",
			slog.String("user_id", uid), slog.Int64("payout_rows", outstandingPayouts))
		h.alertAdmin("Courier deleted their account with outstanding payouts",
			fmt.Sprintf("Courier %s deleted their account while %d payout queue row(s) were still unpaid.\n\n"+
				"Those rows were moved to failed_permanent so the sweep stops retrying them; the courier's\n"+
				"user row (and any Stripe Connect account link) is gone, so settle them off-platform.\n\n"+
				"Query: SELECT * FROM courier_payout_queue WHERE courier_id IS NULL AND status = 'failed_permanent';\n",
				uid, outstandingPayouts))
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}
