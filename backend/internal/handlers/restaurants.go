package handlers

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

func (h *Handler) ListRestaurants(w http.ResponseWriter, r *http.Request) {
	lat := r.URL.Query().Get("lat")
	lng := r.URL.Query().Get("lng")

	var restaurants []models.Restaurant

	query := `SELECT id, owner_id, name, description, image_url, cover_image_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		FROM restaurants WHERE is_active = true`

	var rows interface{ Close() }
	var err error

	if lat != "" && lng != "" {
		query += ` ORDER BY point($1, $2) <-> point(lng, lat) LIMIT 50`
		r, err2 := h.db.Pool.Query(r.Context(), query, lng, lat)
		rows = r
		err = err2
		_ = rows
	} else {
		query += ` ORDER BY rating DESC LIMIT 50`
		r, err2 := h.db.Pool.Query(r.Context(), query)
		rows = r
		err = err2
		_ = rows
	}

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch restaurants")
		return
	}

	// TODO: scan rows into restaurants slice
	if restaurants == nil {
		restaurants = []models.Restaurant{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"restaurants": restaurants,
	})
}

func (h *Handler) GetRestaurant(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var rest models.Restaurant
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		FROM restaurants WHERE id = $1 AND is_active = true`, id,
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

func (h *Handler) GetMenu(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var categories []models.MenuCategory
	catRows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, restaurant_id, name, sort_order FROM menu_categories
		 WHERE restaurant_id = $1 ORDER BY sort_order`, id)
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
		categories = append(categories, cat)
	}

	for i, cat := range categories {
		itemRows, err := h.db.Pool.Query(r.Context(),
			`SELECT id, restaurant_id, category_id, name, description, image_url,
			 price, is_meat, is_dairy, is_pareve, is_available, sort_order
			 FROM menu_items WHERE category_id = $1 AND is_available = true
			 ORDER BY sort_order`, cat.ID)
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
			categories[i].Items = append(categories[i].Items, item)
		}
	}

	if categories == nil {
		categories = []models.MenuCategory{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"categories": categories,
	})
}

func (h *Handler) SearchRestaurants(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if q == "" {
		writeError(w, http.StatusBadRequest, "search query required")
		return
	}

	// TODO: implement full-text search
	restaurants := []models.Restaurant{}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"restaurants": restaurants,
		"query":       q,
	})
}
