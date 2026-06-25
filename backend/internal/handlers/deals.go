package handlers

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
)

// ── Request types ────────────────────────────────────────────

type CreateDealRequest struct {
	Title          string              `json:"title"`
	Description    string              `json:"description"`
	ImageURL       string              `json:"image_url"`
	MenuItemID     *string             `json:"menu_item_id,omitempty"`
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
		if req.DiscountValue > 10000 {
			writeError(w, http.StatusBadRequest, "fixed discount cannot exceed $100")
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

	minOrderAmount := 0
	if req.MinOrderAmount != nil {
		minOrderAmount = *req.MinOrderAmount
	}

	// Validate and optionally auto-populate image from linked menu item.
	if req.MenuItemID != nil && *req.MenuItemID != "" {
		var itemRestID, itemImageURL string
		err := h.db.Pool.QueryRow(r.Context(),
			`SELECT restaurant_id, COALESCE(image_url, '') FROM menu_items WHERE id = $1`,
			*req.MenuItemID,
		).Scan(&itemRestID, &itemImageURL)
		if err != nil {
			writeError(w, http.StatusBadRequest, "menu item not found")
			return
		}
		if itemRestID != restID {
			writeError(w, http.StatusForbidden, "menu item does not belong to your restaurant")
			return
		}
		if req.ImageURL == "" && itemImageURL != "" {
			req.ImageURL = itemImageURL
		}
	}

	var deal models.Deal
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO deals (restaurant_id, title, description, image_url, menu_item_id,
		    discount_type, discount_value, min_order_amount, starts_at, expires_at, is_active)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, true)
		 RETURNING id, restaurant_id, title, description, image_url, menu_item_id,
		    discount_type, discount_value, min_order_amount, starts_at, expires_at,
		    is_active, created_at, updated_at`,
		restID, req.Title, req.Description, req.ImageURL, req.MenuItemID,
		req.DiscountType, req.DiscountValue, minOrderAmount, startsAt, req.ExpiresAt,
	).Scan(&deal.ID, &deal.RestaurantID, &deal.Title, &deal.Description,
		&deal.ImageURL, &deal.MenuItemID, &deal.DiscountType, &deal.DiscountValue,
		&deal.MinOrderAmount, &deal.StartsAt, &deal.ExpiresAt, &deal.IsActive,
		&deal.CreatedAt, &deal.UpdatedAt)

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
		`SELECT id, restaurant_id, title, description, image_url, menu_item_id,
		    discount_type, discount_value, min_order_amount, starts_at, expires_at,
		    is_active, created_at, updated_at
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
			&d.ImageURL, &d.MenuItemID, &d.DiscountType, &d.DiscountValue,
			&d.MinOrderAmount, &d.StartsAt, &d.ExpiresAt, &d.IsActive,
			&d.CreatedAt, &d.UpdatedAt); err != nil {
			continue
		}
		deals = append(deals, d)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
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

// resolveDealDiscount looks up the deal by id, validates it (active, not
// expired, belongs to the cart's restaurant, subtotal meets the minimum)
// and returns the discount in cents. Returns (0, nil) when dealID is empty
// — callers pass the empty string when no deal is being applied. Returns a
// non-nil error when the deal exists but cannot be applied — callers
// should surface it to the user as a 400.
//
// Discount semantics:
//   - percentage: subtotal * value / 100, capped at subtotal
//   - fixed:      min(value, subtotal)
//   - bogo:       price of the cheapest individual unit in the cart, when
//                 the cart has at least 2 units total. Otherwise 0.
//                 Crude but matches the "buy one get one free" promise
//                 without item-targeting logic.
func (h *Handler) resolveDealDiscount(ctx context.Context, dealID, restaurantID, userID string, subtotal int, items []models.OrderItem) (int, error) {
	if dealID == "" {
		return 0, nil
	}

	var (
		dealRestID    string
		discountType  string
		discountValue int
		minOrder      int
		isActive      bool
		expiresAt     time.Time
	)
	err := h.db.Pool.QueryRow(ctx,
		`SELECT restaurant_id, discount_type, discount_value, COALESCE(min_order_amount, 0),
		        is_active, expires_at
		 FROM deals WHERE id = $1`, dealID,
	).Scan(&dealRestID, &discountType, &discountValue, &minOrder, &isActive, &expiresAt)
	if err != nil {
		if err == pgx.ErrNoRows {
			return 0, fmt.Errorf("deal not found")
		}
		return 0, fmt.Errorf("failed to look up deal: %w", err)
	}

	if !isActive {
		return 0, fmt.Errorf("deal is no longer active")
	}
	if !expiresAt.After(time.Now()) {
		return 0, fmt.Errorf("deal has expired")
	}
	if dealRestID != restaurantID {
		return 0, fmt.Errorf("deal does not apply to this restaurant")
	}
	if subtotal < minOrder {
		return 0, fmt.Errorf("order subtotal of %d is below the deal minimum of %d", subtotal, minOrder)
	}

	// Limit deal to one use per customer.
	var used bool
	err = h.db.Pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM orders WHERE user_id = $1 AND applied_deal_id = $2 AND status NOT IN ('rejected','cancelled'))`,
		userID, dealID).Scan(&used)
	if err != nil {
		return 0, fmt.Errorf("failed to check deal usage: %w", err)
	}
	if used {
		return 0, fmt.Errorf("deal already used")
	}

	switch discountType {
	case "percentage":
		d := subtotal * discountValue / 100
		if d > subtotal {
			d = subtotal
		}
		return d, nil
	case "fixed":
		if discountValue > subtotal {
			return subtotal, nil
		}
		return discountValue, nil
	case "bogo":
		// Need at least 2 units total for a "buy one get one" to make sense.
		var totalQty int
		cheapestUnit := -1
		for _, it := range items {
			totalQty += it.Quantity
			if cheapestUnit < 0 || it.Price < cheapestUnit {
				cheapestUnit = it.Price
			}
		}
		if totalQty < 2 || cheapestUnit < 0 {
			return 0, fmt.Errorf("BOGO requires at least 2 items")
		}
		return cheapestUnit, nil
	default:
		return 0, fmt.Errorf("unknown discount type: %s", discountType)
	}
}

// ListNearbyDeals returns active, non-expired deals with restaurant and
// optional menu-item info. Results are interleaved so the same restaurant
// doesn't appear back-to-back.
// GET /deals/nearby
func (h *Handler) ListNearbyDeals(w http.ResponseWriter, r *http.Request) {
	vertical := verticalFromRequest(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT d.id, d.restaurant_id, d.title, d.description, d.image_url,
		    d.menu_item_id, d.discount_type, d.discount_value, d.min_order_amount,
		    d.starts_at, d.expires_at, d.is_active, d.created_at, d.updated_at,
		    r.name, r.image_url,
		    mi.name, mi.price, mi.image_url
		 FROM deals d
		 JOIN restaurants r ON r.id = d.restaurant_id
		 LEFT JOIN menu_items mi ON mi.id = d.menu_item_id
		 WHERE d.is_active = true
		   AND d.starts_at <= NOW()
		   AND d.expires_at > NOW()
		   AND r.is_active = true
		   AND r.approval_status = 'approved'
		   AND r.vertical = $1
		 ORDER BY d.expires_at ASC`, vertical)
	if err != nil {
		slog.Error("ListNearbyDeals query failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}
	defer rows.Close()

	deals := []models.DealWithItem{}
	for rows.Next() {
		var d models.DealWithItem
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.Title, &d.Description,
			&d.ImageURL, &d.MenuItemID, &d.DiscountType, &d.DiscountValue,
			&d.MinOrderAmount, &d.StartsAt, &d.ExpiresAt, &d.IsActive,
			&d.CreatedAt, &d.UpdatedAt,
			&d.RestaurantName, &d.RestaurantImageURL,
			&d.MenuItemName, &d.MenuItemPrice, &d.MenuItemImageURL); err != nil {
			continue
		}
		deals = append(deals, d)
	}
	if err := rows.Err(); err != nil {
		slog.Error("ListNearbyDeals row iteration failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}

	writeJSON(w, http.StatusOK, interleaveDeals(deals))
}

// ListRestaurantDeals returns active deals for a specific restaurant.
// GET /restaurants/{id}/deals
func (h *Handler) ListRestaurantDeals(w http.ResponseWriter, r *http.Request) {
	restaurantID := chi.URLParam(r, "id")
	vertical := verticalFromRequest(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT d.id, d.restaurant_id, d.title, d.description, d.image_url,
		    d.menu_item_id, d.discount_type, d.discount_value, d.min_order_amount,
		    d.starts_at, d.expires_at, d.is_active, d.created_at, d.updated_at,
		    r.name, r.image_url,
		    mi.name, mi.price, mi.image_url
		 FROM deals d
		 JOIN restaurants r ON r.id = d.restaurant_id
		 LEFT JOIN menu_items mi ON mi.id = d.menu_item_id
		 WHERE d.restaurant_id = $1
		   AND d.is_active = true
		   AND d.starts_at <= NOW()
		   AND d.expires_at > NOW()
		   AND r.is_active = true
		   AND r.approval_status = 'approved'
		   AND r.vertical = $2
		 ORDER BY d.expires_at ASC`, restaurantID, vertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}
	defer rows.Close()

	deals := []models.DealWithItem{}
	for rows.Next() {
		var d models.DealWithItem
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.Title, &d.Description,
			&d.ImageURL, &d.MenuItemID, &d.DiscountType, &d.DiscountValue,
			&d.MinOrderAmount, &d.StartsAt, &d.ExpiresAt, &d.IsActive,
			&d.CreatedAt, &d.UpdatedAt,
			&d.RestaurantName, &d.RestaurantImageURL,
			&d.MenuItemName, &d.MenuItemPrice, &d.MenuItemImageURL); err != nil {
			continue
		}
		deals = append(deals, d)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list deals")
		return
	}

	writeJSON(w, http.StatusOK, deals)
}

// interleaveDeals reorders deals so the same restaurant doesn't appear
// back-to-back. Greedy pick: always prefer a deal from a different
// restaurant than the previous one.
func interleaveDeals(deals []models.DealWithItem) []models.DealWithItem {
	if len(deals) <= 2 {
		return deals
	}
	result := make([]models.DealWithItem, 0, len(deals))
	remaining := make([]models.DealWithItem, len(deals))
	copy(remaining, deals)

	lastRestID := ""
	for len(remaining) > 0 {
		picked := -1
		for i, d := range remaining {
			if d.RestaurantID != lastRestID {
				picked = i
				break
			}
		}
		if picked == -1 {
			picked = 0
		}
		result = append(result, remaining[picked])
		lastRestID = remaining[picked].RestaurantID
		remaining = append(remaining[:picked], remaining[picked+1:]...)
	}
	return result
}
