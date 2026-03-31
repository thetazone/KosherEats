package handlers

import (
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
	Price       int    `json:"price"`
	IsMeat      bool   `json:"is_meat"`
	IsDairy     bool   `json:"is_dairy"`
	IsPareve    bool   `json:"is_pareve"`
}

func (h *Handler) GetSellerRestaurant(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var rest models.Restaurant
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		FROM restaurants WHERE owner_id = $1`, user["user_id"],
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

	var req UpdateRestaurantRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET name = $1, description = $2, phone = $3,
		 cuisine_type = $4, delivery_fee = $5, min_order = $6,
		 est_delivery_min = $7, est_delivery_max = $8, is_open = $9, updated_at = NOW()
		 WHERE owner_id = $10`,
		req.Name, req.Description, req.Phone, req.CuisineType,
		req.DeliveryFee, req.MinOrder, req.EstDeliveryMin, req.EstDeliveryMax,
		req.IsOpen, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "failed to update restaurant")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) GetSellerMenu(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	// Get restaurant ID
	var restID string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM restaurants WHERE owner_id = $1`, user["user_id"],
	).Scan(&restID)
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
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

	writeJSON(w, http.StatusOK, map[string]interface{}{"categories": categories})
}

func (h *Handler) CreateMenuItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req CreateMenuItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var restID string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM restaurants WHERE owner_id = $1`, user["user_id"],
	).Scan(&restID)
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	var item models.MenuItem
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO menu_items (restaurant_id, category_id, name, description, price,
		 is_meat, is_dairy, is_pareve, is_available)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, true)
		 RETURNING id, restaurant_id, category_id, name, description, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		restID, req.CategoryID, req.Name, req.Description, req.Price,
		req.IsMeat, req.IsDairy, req.IsPareve,
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)

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

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE menu_items SET name = $1, description = $2, price = $3,
		 is_meat = $4, is_dairy = $5, is_pareve = $6
		 WHERE id = $7 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $8)`,
		req.Name, req.Description, req.Price, req.IsMeat, req.IsDairy, req.IsPareve,
		itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "failed to update menu item")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
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
