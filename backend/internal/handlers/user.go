package handlers

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type UpdateProfileRequest struct {
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Phone     string `json:"phone"`
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
	user := getUserFromContext(r)

	var u models.User
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, email, first_name, last_name, phone, role, avatar_url, created_at, updated_at
		 FROM users WHERE id = $1`, user["user_id"],
	).Scan(&u.ID, &u.Email, &u.FirstName, &u.LastName, &u.Phone, &u.Role, &u.AvatarURL,
		&u.CreatedAt, &u.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}

	writeJSON(w, http.StatusOK, u)
}

func (h *Handler) UpdateProfile(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req UpdateProfileRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET first_name = $1, last_name = $2, phone = $3, updated_at = NOW()
		 WHERE id = $4`,
		req.FirstName, req.LastName, req.Phone, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "failed to update profile")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) ListAddresses(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

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
	user := getUserFromContext(r)

	var req AddAddressRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
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
	user := getUserFromContext(r)
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
