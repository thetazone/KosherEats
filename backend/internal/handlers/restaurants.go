package handlers

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
)

// Returns a plain JSON array: `[{...}, {...}]`. iOS + Android clients both
// expect unwrapped array responses for list endpoints.
func (h *Handler) ListRestaurants(w http.ResponseWriter, r *http.Request) {
	lat := r.URL.Query().Get("lat")
	lng := r.URL.Query().Get("lng")

	const baseQuery = `SELECT id, owner_id, name, description, image_url, cover_image_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		FROM restaurants WHERE is_active = true`

	var rows pgx.Rows
	var err error

	if lat != "" && lng != "" {
		rows, err = h.db.Pool.Query(r.Context(),
			baseQuery+` ORDER BY point($1, $2) <-> point(lng, lat) LIMIT 50`,
			lng, lat)
	} else {
		rows, err = h.db.Pool.Query(r.Context(), baseQuery+` ORDER BY rating DESC LIMIT 50`)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch restaurants")
		return
	}
	defer rows.Close()

	restaurants := scanRestaurants(rows)
	writeJSON(w, http.StatusOK, restaurants)
}

// scanRestaurants drains a pgx.Rows into a slice of models.Restaurant.
// Shared by ListRestaurants and SearchRestaurants to avoid duplication.
func scanRestaurants(rows pgx.Rows) []models.Restaurant {
	out := []models.Restaurant{}
	for rows.Next() {
		var rest models.Restaurant
		if err := rows.Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL,
			&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
			&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
			&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.CuisineType,
			&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
			&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive,
			&rest.CreatedAt, &rest.UpdatedAt); err != nil {
			continue
		}
		out = append(out, rest)
	}
	return out
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

	// Collect all items first, then fetch modifier groups/modifiers in two
	// batched queries by restaurant. Avoids N+1 queries as the menu grows.
	var allItemIDs []string
	itemByID := map[string]*models.MenuItem{}

	for i, cat := range categories {
		itemRows, err := h.db.Pool.Query(r.Context(),
			`SELECT id, restaurant_id, category_id, name, description, image_url,
			 price, is_meat, is_dairy, is_pareve, is_available, sort_order
			 FROM menu_items WHERE category_id = $1 AND is_available = true
			 ORDER BY sort_order`, cat.ID)
		if err != nil {
			continue
		}

		for itemRows.Next() {
			var item models.MenuItem
			if err := itemRows.Scan(&item.ID, &item.RestaurantID, &item.CategoryID,
				&item.Name, &item.Description, &item.ImageURL, &item.Price,
				&item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable,
				&item.SortOrder); err != nil {
				continue
			}
			categories[i].Items = append(categories[i].Items, item)
			allItemIDs = append(allItemIDs, item.ID)
		}
		itemRows.Close()

		// Build pointer map into the slice so we can attach modifier groups after.
		for j := range categories[i].Items {
			itemByID[categories[i].Items[j].ID] = &categories[i].Items[j]
		}
	}

	// Attach modifier groups + modifiers to each item.
	if len(allItemIDs) > 0 {
		h.attachModifierGroups(r, allItemIDs, itemByID)
	}

	if categories == nil {
		categories = []models.MenuCategory{}
	}

	writeJSON(w, http.StatusOK, categories)
}

// attachModifierGroups loads every modifier group + modifier for a batch of
// menu items in two queries, then attaches them to the items via the pointer
// map. Keeps GetMenu fast even as menus grow.
func (h *Handler) attachModifierGroups(r *http.Request, itemIDs []string, itemByID map[string]*models.MenuItem) {
	// Step 1: collect all groups into a flat slice. Don't attach to items yet —
	// appending to item.ModifierGroups inside the loop would invalidate any
	// pointers we tried to store alongside (classic slice-reallocation footgun).
	groupRows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, menu_item_id, name, description, is_required,
		        min_selections, max_selections, sort_order
		   FROM menu_item_modifier_groups
		  WHERE menu_item_id = ANY($1)
		  ORDER BY menu_item_id, sort_order`, itemIDs)
	if err != nil {
		return
	}

	var groups []models.ModifierGroup
	var groupIDs []string
	for groupRows.Next() {
		var g models.ModifierGroup
		if err := groupRows.Scan(&g.ID, &g.MenuItemID, &g.Name, &g.Description,
			&g.IsRequired, &g.MinSelections, &g.MaxSelections, &g.SortOrder); err != nil {
			continue
		}
		groups = append(groups, g)
		groupIDs = append(groupIDs, g.ID)
	}
	groupRows.Close()

	if len(groupIDs) == 0 {
		return
	}

	// Step 2: fetch all modifiers in one query, bucket by group id.
	modRows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, group_id, name, price_delta, is_default, is_available, sort_order
		   FROM menu_item_modifiers
		  WHERE group_id = ANY($1) AND is_available = true
		  ORDER BY group_id, sort_order`, groupIDs)
	if err != nil {
		return
	}
	defer modRows.Close()

	modsByGroup := map[string][]models.Modifier{}
	for modRows.Next() {
		var m models.Modifier
		if err := modRows.Scan(&m.ID, &m.GroupID, &m.Name, &m.PriceDelta,
			&m.IsDefault, &m.IsAvailable, &m.SortOrder); err != nil {
			continue
		}
		modsByGroup[m.GroupID] = append(modsByGroup[m.GroupID], m)
	}

	// Step 3: attach modifiers to each group by value, then append each
	// fully-built group onto its item. Everything here is append-only from
	// complete values — no dangling pointers.
	for _, g := range groups {
		g.Modifiers = modsByGroup[g.ID]
		if item, ok := itemByID[g.MenuItemID]; ok {
			item.ModifierGroups = append(item.ModifierGroups, g)
		}
	}
}

// SearchRestaurants does a simple case-insensitive substring match on name
// and cuisine_type. Full-text search / trigram indexes would be the prod move;
// this is plenty for the MVP.
func (h *Handler) SearchRestaurants(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if q == "" {
		writeError(w, http.StatusBadRequest, "search query required")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, created_at, updated_at
		 FROM restaurants
		 WHERE is_active = true
		   AND (name ILIKE $1 OR EXISTS (
		       SELECT 1 FROM unnest(cuisine_type) ct WHERE ct ILIKE $1
		   ))
		 ORDER BY rating DESC LIMIT 50`,
		"%"+q+"%")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "search failed")
		return
	}
	defer rows.Close()

	writeJSON(w, http.StatusOK, scanRestaurants(rows))
}
