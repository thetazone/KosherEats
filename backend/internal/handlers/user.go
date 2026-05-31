package handlers

import (
	"errors"
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
		`SELECT id, email, first_name, last_name, phone, role, vertical, avatar_url, created_at, updated_at
		 FROM users WHERE id = $1`, user["user_id"],
	).Scan(&u.ID, &u.Email, &u.FirstName, &u.LastName, &u.Phone, &u.Role, &u.Vertical, &u.AvatarURL,
		&u.CreatedAt, &u.UpdatedAt); err != nil {
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

	if email != "" {
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE users SET first_name = $1, last_name = $2, phone = $3, email = $4, updated_at = NOW()
			 WHERE id = $5`,
			req.FirstName, req.LastName, req.Phone, email, user["user_id"])
		if err != nil {
			var pgErr *pgconn.PgError
			if errors.As(err, &pgErr) && pgErr.Code == "23505" {
				if strings.Contains(pgErr.ConstraintName, "phone") {
					writeError(w, http.StatusConflict, "that phone number is already linked to another account")
				} else {
					writeError(w, http.StatusConflict, "that email is already in use")
				}
				return
			}
			writeError(w, http.StatusBadRequest, "failed to update profile")
			return
		}
	} else {
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE users SET first_name = $1, last_name = $2, phone = $3, updated_at = NOW()
			 WHERE id = $4`,
			req.FirstName, req.LastName, req.Phone, user["user_id"])
		if err != nil {
			var pgErr *pgconn.PgError
			if errors.As(err, &pgErr) && pgErr.Code == "23505" {
				writeError(w, http.StatusConflict, "that phone number is already linked to another account")
				return
			}
			writeError(w, http.StatusBadRequest, "failed to update profile")
			return
		}
	}

	var u models.User
	err := h.db.Pool.QueryRow(ctx,
		`SELECT id, email, first_name, last_name, phone, role, vertical, avatar_url, created_at, updated_at
		 FROM users WHERE id = $1`, user["user_id"],
	).Scan(&u.ID, &u.Email, &u.FirstName, &u.LastName, &u.Phone, &u.Role, &u.Vertical, &u.AvatarURL,
		&u.CreatedAt, &u.UpdatedAt)
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

	// Delete related data in dependency order
	tx.Exec(ctx, `DELETE FROM device_tokens WHERE user_id = $1`, uid)
	tx.Exec(ctx, `DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id = $1)`, uid)
	tx.Exec(ctx, `DELETE FROM carts WHERE user_id = $1`, uid)
	tx.Exec(ctx, `DELETE FROM addresses WHERE user_id = $1`, uid)
	// Anonymize orders (keep for accounting) rather than deleting
	tx.Exec(ctx, `UPDATE orders SET user_id = NULL WHERE user_id = $1`, uid)
	// Deactivate restaurants owned by this seller and unlink owner
	tx.Exec(ctx, `UPDATE restaurants SET is_active = false, owner_id = NULL WHERE owner_id = $1`, uid)
	// Anonymize courier assignments on orders
	tx.Exec(ctx, `UPDATE orders SET courier_id = NULL WHERE courier_id = $1`, uid)
	// Clean up courier locations
	tx.Exec(ctx, `DELETE FROM courier_locations WHERE courier_id = $1`, uid)
	// Delete courier profile if exists
	tx.Exec(ctx, `DELETE FROM courier_profiles WHERE user_id = $1`, uid)
	// Delete chat messages sent by this user
	tx.Exec(ctx, `DELETE FROM chat_messages WHERE sender_user_id = $1`, uid)
	// Delete courier ratings left by this consumer
	tx.Exec(ctx, `DELETE FROM courier_ratings WHERE consumer_id = $1`, uid)
	// Delete the user
	_, err = tx.Exec(ctx, `DELETE FROM users WHERE id = $1`, uid)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}

	if err := tx.Commit(ctx); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}
