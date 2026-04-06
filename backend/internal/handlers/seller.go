package handlers

import (
	"fmt"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type UpdateRestaurantRequest struct {
	Name            string   `json:"name"`
	Description     string   `json:"description"`
	Phone           string   `json:"phone"`
	CuisineType     []string `json:"cuisine_type"`
	DeliveryFee     int      `json:"delivery_fee"`
	MinOrder        int      `json:"min_order"`
	EstDeliveryMin  int      `json:"est_delivery_min"`
	EstDeliveryMax  int      `json:"est_delivery_max"`
	IsOpen          bool     `json:"is_open"`
}

type CreateMenuItemRequest struct {
	CategoryID  string `json:"category_id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	ImageURL    string `json:"image_url"`
	Price       int    `json:"price"`
	IsMeat      bool   `json:"is_meat"`
	IsDairy     bool   `json:"is_dairy"`
	IsPareve    bool   `json:"is_pareve"`
}

// resolveSellerRestaurant returns the restaurant the seller wants to act on.
// If the client supplies ?restaurant_id=… it must be one the seller owns.
// Otherwise we fall back to the seller's first owned restaurant (alphabetical
// by name) so existing single-restaurant callers keep working without changes.
// Returns an error if the seller owns zero restaurants or the supplied id
// doesn't belong to them.
func (h *Handler) resolveSellerRestaurant(r *http.Request, userID string) (string, error) {
	requested := r.URL.Query().Get("restaurant_id")
	if requested != "" {
		var ok bool
		err := h.db.Pool.QueryRow(r.Context(),
			`SELECT EXISTS(SELECT 1 FROM restaurants WHERE id = $1 AND owner_id = $2)`,
			requested, userID,
		).Scan(&ok)
		if err != nil || !ok {
			return "", fmt.Errorf("restaurant not owned by seller")
		}
		return requested, nil
	}

	var restID string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM restaurants WHERE owner_id = $1 ORDER BY name LIMIT 1`, userID,
	).Scan(&restID)
	if err != nil {
		return "", fmt.Errorf("no restaurants owned by seller")
	}
	return restID, nil
}

// ListSellerRestaurants returns every restaurant the current seller owns.
// Used by the iOS restaurant picker to populate its list.
func (h *Handler) ListSellerRestaurants(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		 FROM restaurants WHERE owner_id = $1 ORDER BY name`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list restaurants")
		return
	}
	defer rows.Close()

	writeJSON(w, http.StatusOK, scanRestaurants(rows))
}

func (h *Handler) GetSellerRestaurant(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var rest models.Restaurant
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		FROM restaurants WHERE id = $1`, restID,
	).Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL,
		&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
		&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
		&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.CuisineType,
		&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
		&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive,
		&rest.CreatedAt, &rest.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	writeJSON(w, http.StatusOK, rest)
}

func (h *Handler) UpdateRestaurant(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var req UpdateRestaurantRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET name = $1, description = $2, phone = $3,
		 cuisine_type = $4, delivery_fee = $5, min_order = $6,
		 est_delivery_min = $7, est_delivery_max = $8, is_open = $9, updated_at = NOW()
		 WHERE id = $10`,
		req.Name, req.Description, req.Phone, req.CuisineType,
		req.DeliveryFee, req.MinOrder, req.EstDeliveryMin, req.EstDeliveryMax,
		req.IsOpen, restID)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "failed to update restaurant")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) GetSellerMenu(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	// Reuse the public menu handler logic
	categories := []models.MenuCategory{}

	catRows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, restaurant_id, name, sort_order FROM menu_categories
		 WHERE restaurant_id = $1 ORDER BY sort_order`, restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch menu")
		return
	}
	defer catRows.Close()

	for catRows.Next() {
		var cat models.MenuCategory
		if err := catRows.Scan(&cat.ID, &cat.RestaurantID, &cat.Name, &cat.SortOrder); err != nil {
			continue
		}

		itemRows, err := h.db.Pool.Query(r.Context(),
			`SELECT id, restaurant_id, category_id, name, description, image_url,
			 price, is_meat, is_dairy, is_pareve, is_available, sort_order
			 FROM menu_items WHERE category_id = $1 ORDER BY sort_order`, cat.ID)
		if err != nil {
			continue
		}
		defer itemRows.Close()

		for itemRows.Next() {
			var item models.MenuItem
			if err := itemRows.Scan(&item.ID, &item.RestaurantID, &item.CategoryID,
				&item.Name, &item.Description, &item.ImageURL, &item.Price,
				&item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable,
				&item.SortOrder); err != nil {
				continue
			}
			cat.Items = append(cat.Items, item)
		}

		categories = append(categories, cat)
	}

	// Raw array — iOS seller client expects `[MenuCategory]`, not a wrapper.
	writeJSON(w, http.StatusOK, categories)
}

func (h *Handler) CreateMenuItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req CreateMenuItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var item models.MenuItem
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO menu_items (restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true)
		 RETURNING id, restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		restID, req.CategoryID, req.Name, req.Description, req.ImageURL, req.Price,
		req.IsMeat, req.IsDairy, req.IsPareve,
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.ImageURL, &item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create menu item")
		return
	}

	writeJSON(w, http.StatusCreated, item)
}

func (h *Handler) UpdateMenuItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	itemID := chi.URLParam(r, "id")

	var req CreateMenuItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var item models.MenuItem
	err := h.db.Pool.QueryRow(r.Context(),
		`UPDATE menu_items SET name = $1, description = $2, image_url = $3, price = $4,
		 is_meat = $5, is_dairy = $6, is_pareve = $7, updated_at = NOW()
		 WHERE id = $8 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $9)
		 RETURNING id, restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		req.Name, req.Description, req.ImageURL, req.Price, req.IsMeat, req.IsDairy, req.IsPareve,
		itemID, user["user_id"],
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.ImageURL, &item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)

	if err != nil {
		writeError(w, http.StatusBadRequest, "failed to update menu item")
		return
	}

	// Return the full updated item — iOS seller client decodes as MenuItem.
	writeJSON(w, http.StatusOK, item)
}

// ToggleItemAvailability flips the is_available flag on a menu item.
// Called from the seller menu management screen's per-item toggle.
func (h *Handler) ToggleItemAvailability(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	itemID := chi.URLParam(r, "id")

	var req struct {
		IsAvailable bool `json:"is_available"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var item models.MenuItem
	err := h.db.Pool.QueryRow(r.Context(),
		`UPDATE menu_items SET is_available = $1, updated_at = NOW()
		 WHERE id = $2 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $3)
		 RETURNING id, restaurant_id, category_id, name, description, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		req.IsAvailable, itemID, user["user_id"],
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)
	if err != nil {
		writeError(w, http.StatusNotFound, "menu item not found")
		return
	}

	writeJSON(w, http.StatusOK, item)
}

// ToggleRestaurantStatus flips is_open on the seller's restaurant. This is
// the big orange/green toggle on the dashboard that lets a seller go
// "closed" when they want to stop receiving orders.
func (h *Handler) ToggleRestaurantStatus(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req struct {
		IsOpen bool `json:"is_open"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET is_open = $1, updated_at = NOW() WHERE id = $2`,
		req.IsOpen, restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update status")
		return
	}

	// Return the fresh restaurant record so the client has up-to-date state.
	h.GetSellerRestaurant(w, r)
}

// CreateCategory adds a menu category for the seller's restaurant.
func (h *Handler) CreateCategory(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req struct {
		Name string `json:"name"`
	}
	if err := readJSON(r, &req); err != nil || req.Name == "" {
		writeError(w, http.StatusBadRequest, "category name required")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var cat models.MenuCategory
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO menu_categories (restaurant_id, name, sort_order)
		 VALUES ($1, $2, COALESCE((SELECT MAX(sort_order) + 1 FROM menu_categories WHERE restaurant_id = $1), 0))
		 RETURNING id, restaurant_id, name, sort_order`,
		restID, req.Name,
	).Scan(&cat.ID, &cat.RestaurantID, &cat.Name, &cat.SortOrder)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create category")
		return
	}
	writeJSON(w, http.StatusCreated, cat)
}

// DeleteCategory removes a category (cascades to items via FK ON DELETE CASCADE).
func (h *Handler) DeleteCategory(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	catID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM menu_categories
		 WHERE id = $1 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $2)`,
		catID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "category not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

// GetDashboardStats aggregates the numbers the seller sees at the top of
// their home screen. Scoped to a single restaurant via resolveSellerRestaurant
// so a multi-restaurant seller gets accurate per-restaurant numbers.
func (h *Handler) GetDashboardStats(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var stats models.DashboardStats
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT
		    COUNT(*) FILTER (WHERE o.created_at::date = CURRENT_DATE)         AS today_orders,
		    COALESCE(SUM(o.total) FILTER (WHERE o.created_at::date = CURRENT_DATE), 0) AS today_revenue_cents,
		    COUNT(*) FILTER (WHERE o.status IN ('pending','accepted','preparing','ready'))
		                                                                       AS active_orders,
		    COALESCE(AVG(EXTRACT(EPOCH FROM (o.updated_at - o.created_at)) / 60)
		             FILTER (WHERE o.status = 'delivered'
		                     AND o.created_at::date = CURRENT_DATE), 0)       AS avg_prep_time_min
		   FROM orders o
		  WHERE o.restaurant_id = $1`, restID,
	).Scan(&stats.TodayOrders, &stats.TodayRevenueCents, &stats.ActiveOrders, &stats.AvgPrepTime)

	writeJSON(w, http.StatusOK, stats)
}

func (h *Handler) DeleteMenuItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	itemID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM menu_items
		 WHERE id = $1 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $2)`,
		itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "menu item not found")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}
