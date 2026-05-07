package handlers

import (
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

// ── Request types ────────────────────────────────────────────

type CreateDealRequest struct {
	Title          string              `json:"title"`
	Description    string              `json:"description"`
	DiscountType   models.DiscountType `json:"discount_type"`
	DiscountValue  int                 `json:"discount_value"`
	MinOrderAmount *int                `json:"min_order_amount,omitempty"`
	StartsAt       *time.Time          `json:"starts_at,omitempty"`
	ExpiresAt      time.Time           `json:"expires_at"`
}

// ── Seller endpoints ─────────────────────────────────────────

// CreateDeal lets a seller post a new limited-time deal for their restaurant.
// POST /seller/deals
func (h *Handler) CreateDeal(w http.ResponseWriter, r *http.Request) {
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

	var req CreateDealRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// Validation
	if req.Title == "" {
		writeError(w, http.StatusBadRequest, "title is required")
		return
	}
	if len(req.Title) > 200 {
		writeError(w, http.StatusBadRequest, "title too long (max 200)")
		return
	}
	if len(req.Description) > 2000 {
		writeError(w, http.StatusBadRequest, "description too long (max 2000)")
		return
	}

	switch req.DiscountType {
	case models.DiscountPercentage:
		if req.DiscountValue < 1 || req.DiscountValue > 100 {
			writeError(w, http.StatusBadRequest, "percentage discount must be 1-100")
			return
		}
	case models.DiscountFixed:
		if req.DiscountValue < 1 {
			writeError(w, http.StatusBadRequest, "fixed discount must be at least 1 cent")
			return
		}
	case models.DiscountBOGO:
		// No value needed
	default:
		writeError(w, http.StatusBadRequest, "discount_type must be percentage, fixed, or bogo")
		return
	}

	if req.ExpiresAt.IsZero() {
		writeError(w, http.StatusBadRequest, "expires_at is required")
		return
	}
	if req.ExpiresAt.Before(time.Now()) {
		writeError(w, http.StatusBadRequest, "expires_at must be in the future")
		return
	}

	startsAt := time.Now()
	if req.StartsAt != nil {
		startsAt = *req.StartsAt
	}

	var deal models.Deal
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO deals (restaurant_id, title, description, discount_type, discount_value,
		    min_order_amount, starts_at, expires_at, is_active)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, true)
		 RETURNING id, restaurant_id, title, description, discount_type, discount_value,
		    min_order_amount, starts_at, expires_at, is_active, created_at, updated_at`,
		restID, req.Title, req.Description, req.DiscountType, req.DiscountValue,
		req.MinOrderAmount, startsAt, req.ExpiresAt,
	).Scan(&deal.ID, &deal.RestaurantID, &deal.Title, &deal.Description,
		&deal.DiscountType, &deal.DiscountValue, &deal.MinOrderAmount,
		&deal.StartsAt, &deal.ExpiresAt, &deal.IsActive, &deal.CreatedAt, &deal.UpdatedAt)

	if err != nil {
		slog.Error("CreateDeal insert failed",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to create deal")
		return
	}

	writeJSON(w, http.StatusCreated, deal)
}

// ListSellerDeals returns all deals for the seller's restaurant (active and inactive).
// GET /seller/deals
func (h *Handler) ListSellerDeals(w http.ResponseWriter, r *http.Request) {
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

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, restaurant_id, title, description, discount_type, discount_value,
		    min_order_amount, starts_at, expires_at, is_active, created_at, updated_at
		 FROM deals
		 WHERE restaurant_id = $1
		 ORDER BY created_at DESC`, restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}
	defer rows.Close()

	deals := []models.Deal{}
	for rows.Next() {
		var d models.Deal
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.Title, &d.Description,
			&d.DiscountType, &d.DiscountValue, &d.MinOrderAmount,
			&d.StartsAt, &d.ExpiresAt, &d.IsActive, &d.CreatedAt, &d.UpdatedAt); err != nil {
			continue
		}
		deals = append(deals, d)
	}

	writeJSON(w, http.StatusOK, deals)
}

// DeactivateDeal soft-deletes a deal by setting is_active = false.
// The seller must own the restaurant the deal belongs to.
// DELETE /seller/deals/{dealId}
func (h *Handler) DeactivateDeal(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	dealID := chi.URLParam(r, "dealId")

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE deals SET is_active = false, updated_at = NOW()
		 WHERE id = $1
		   AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $2)`,
		dealID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "deal not found")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deactivated"})
}

// ── Consumer endpoint ────────────────────────────────────────

// ListNearbyDeals returns active, non-expired deals. For now returns all
// active deals ordered by expiry (soonest first); location filtering is a
// follow-up once we wire up PostGIS or a radius query on lat/lng.
// GET /deals/nearby
func (h *Handler) ListNearbyDeals(w http.ResponseWriter, r *http.Request) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT d.id, d.restaurant_id, d.title, d.description, d.discount_type,
		    d.discount_value, d.min_order_amount, d.starts_at, d.expires_at,
		    d.is_active, d.created_at, d.updated_at,
		    r.name, r.image_url
		 FROM deals d
		 JOIN restaurants r ON r.id = d.restaurant_id
		 WHERE d.is_active = true
		   AND d.starts_at <= NOW()
		   AND d.expires_at > NOW()
		   AND r.is_active = true
		 ORDER BY d.expires_at ASC`)
	if err != nil {
		slog.Error("ListNearbyDeals query failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}
	defer rows.Close()

	deals := []models.DealWithRestaurant{}
	for rows.Next() {
		var d models.DealWithRestaurant
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.Title, &d.Description,
			&d.DiscountType, &d.DiscountValue, &d.MinOrderAmount,
			&d.StartsAt, &d.ExpiresAt, &d.IsActive, &d.CreatedAt, &d.UpdatedAt,
			&d.RestaurantName, &d.RestaurantImageURL); err != nil {
			continue
		}
		deals = append(deals, d)
	}

	writeJSON(w, http.StatusOK, deals)
}
