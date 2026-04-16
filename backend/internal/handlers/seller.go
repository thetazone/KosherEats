package handlers

import (
	"fmt"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

// UpdateRestaurantRequest is a partial update — every field is optional.
// Nil means "leave this column alone"; non-nil means "write this value."
// Using pointers here lets the seller UI save a single section without
// clobbering fields it doesn't render (e.g. lat/lng, is_active).
type UpdateRestaurantRequest struct {
	Name                *string  `json:"name"`
	Description         *string  `json:"description"`
	Phone               *string  `json:"phone"`
	Email               *string  `json:"email"`
	Street              *string  `json:"street"`
	City                *string  `json:"city"`
	State               *string  `json:"state"`
	ZipCode             *string  `json:"zip_code"`
	CuisineType         []string `json:"cuisine_type"`
	// Money fields are cents to match the DB (delivery_fee / min_order are INTEGER).
	DeliveryFee         *int     `json:"delivery_fee"`
	MinOrder            *int     `json:"min_order"`
	EstDeliveryMin      *int     `json:"est_delivery_min"`
	EstDeliveryMax      *int     `json:"est_delivery_max"`
	IsOpen              *bool    `json:"is_open"`
	KosherCertification *string  `json:"kosher_certification"`
	CertifyingAgency    *string  `json:"certifying_agency"`
	IsCholovYisroel     *bool    `json:"is_cholov_yisroel"`
	IsPasYisroel        *bool    `json:"is_pas_yisroel"`
	IsGlattKosher       *bool    `json:"is_glatt_kosher"`
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

	// Use COALESCE so each column only gets overwritten when the client
	// supplied a non-nil pointer. This means partial updates (e.g. only
	// flipping is_open) don't blank out the rest of the row.
	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET
			name                 = COALESCE($1,  name),
			description          = COALESCE($2,  description),
			phone                = COALESCE($3,  phone),
			email                = COALESCE($4,  email),
			street               = COALESCE($5,  street),
			city                 = COALESCE($6,  city),
			state                = COALESCE($7,  state),
			zip_code             = COALESCE($8,  zip_code),
			cuisine_type         = COALESCE($9,  cuisine_type),
			delivery_fee         = COALESCE($10, delivery_fee),
			min_order            = COALESCE($11, min_order),
			est_delivery_min     = COALESCE($12, est_delivery_min),
			est_delivery_max     = COALESCE($13, est_delivery_max),
			is_open              = COALESCE($14, is_open),
			kosher_certification = COALESCE($15, kosher_certification),
			certifying_agency    = COALESCE($16, certifying_agency),
			is_cholov_yisroel    = COALESCE($17, is_cholov_yisroel),
			is_pas_yisroel       = COALESCE($18, is_pas_yisroel),
			is_glatt_kosher      = COALESCE($19, is_glatt_kosher),
			updated_at           = NOW()
		 WHERE id = $20`,
		req.Name, req.Description, req.Phone, req.Email,
		req.Street, req.City, req.State, req.ZipCode,
		req.CuisineType,
		req.DeliveryFee, req.MinOrder, req.EstDeliveryMin, req.EstDeliveryMax,
		req.IsOpen, req.KosherCertification, req.CertifyingAgency,
		req.IsCholovYisroel, req.IsPasYisroel, req.IsGlattKosher,
		restID)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update restaurant")
		return
	}

	// Return the full, post-update Restaurant so the iOS client can swap
	// its in-memory model in one call (avoids a second GET round-trip and
	// keeps the seller UI and DB in lockstep).
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
		writeError(w, http.StatusInternalServerError, "updated but failed to reload restaurant")
		return
	}

	writeJSON(w, http.StatusOK, rest)
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
	itemByID := map[string]*models.MenuItem{}
	var allItemIDs []string

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

		for itemRows.Next() {
			var item models.MenuItem
			if err := itemRows.Scan(&item.ID, &item.RestaurantID, &item.CategoryID,
				&item.Name, &item.Description, &item.ImageURL, &item.Price,
				&item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable,
				&item.SortOrder); err != nil {
				continue
			}
			cat.Items = append(cat.Items, item)
			allItemIDs = append(allItemIDs, item.ID)
		}
		itemRows.Close()

		categories = append(categories, cat)
	}

	// Build pointer map AFTER categories is fully populated — the slice
	// backing cat.Items gets its final allocation once we stop appending.
	for i := range categories {
		for j := range categories[i].Items {
			itemByID[categories[i].Items[j].ID] = &categories[i].Items[j]
		}
	}

	// Seller view wants every modifier (including unavailable) so they can
	// re-enable paused ones from the editor — don't use the consumer-side
	// attachModifierGroups which filters by is_available.
	if len(allItemIDs) > 0 {
		h.attachSellerModifierGroups(r, allItemIDs, itemByID)
	}

	// Raw array — iOS seller client expects `[MenuCategory]`, not a wrapper.
	writeJSON(w, http.StatusOK, categories)
}

// Mirror of attachModifierGroups in restaurants.go but includes unavailable
// modifiers so the seller editor can surface them for toggling.
func (h *Handler) attachSellerModifierGroups(r *http.Request, itemIDs []string, itemByID map[string]*models.MenuItem) {
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

	modRows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, group_id, name, price_delta, is_default, is_available, sort_order
		   FROM menu_item_modifiers
		  WHERE group_id = ANY($1)
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

	for _, g := range groups {
		g.Modifiers = modsByGroup[g.ID]
		if item, ok := itemByID[g.MenuItemID]; ok {
			item.ModifierGroups = append(item.ModifierGroups, g)
		}
	}
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
		    COALESCE(SUM(o.total) FILTER (
		        WHERE o.created_at::date = CURRENT_DATE
		          AND o.status NOT IN ('cancelled','rejected')
		    ), 0)                                                              AS today_revenue_cents,
		    -- Active includes picked_up: the seller should still see an order
		    -- in flight after the courier grabs it, until it's marked delivered.
		    -- Matches OrderStatus.isActive on the iOS side.
		    COUNT(*) FILTER (WHERE o.status IN ('pending','accepted','preparing','ready','picked_up'))
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

// --- Menu item modifier groups -------------------------------------------------
//
// Ownership check on every write: the menu item must live in a restaurant the
// caller owns, otherwise one seller could edit another's modifiers by guessing
// a UUID. Each handler threads this through a JOIN against `restaurants.owner_id`.

type ModifierGroupRequest struct {
	Name          string                 `json:"name"`
	Description   string                 `json:"description"`
	IsRequired    bool                   `json:"is_required"`
	MinSelections int                    `json:"min_selections"`
	MaxSelections int                    `json:"max_selections"`
	SortOrder     int                    `json:"sort_order"`
	Modifiers     []ModifierOptionRequest `json:"modifiers"`
}

type ModifierOptionRequest struct {
	ID          string `json:"id,omitempty"` // present on updates, absent on inserts
	Name        string `json:"name"`
	PriceDelta  int    `json:"price_delta"`
	IsDefault   bool   `json:"is_default"`
	IsAvailable bool   `json:"is_available"`
	SortOrder   int    `json:"sort_order"`
}

// CreateModifierGroup adds a new modifier group to a menu item along with its
// options in a single call. Options with empty ids are treated as inserts.
// We do this in a tx so a partially-saved group doesn't leave the seller with
// a group visible in their editor but no options to pick from.
func (h *Handler) CreateModifierGroup(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	itemID := chi.URLParam(r, "itemId")

	var req ModifierGroupRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if !h.ownsMenuItem(r, itemID, user["user_id"]) {
		writeError(w, http.StatusNotFound, "menu item not found")
		return
	}

	if !isValidModifierGroup(req) {
		writeError(w, http.StatusBadRequest, "invalid modifier group: name required, max >= min, required groups need min >= 1")
		return
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin tx")
		return
	}
	defer tx.Rollback(r.Context())

	var group models.ModifierGroup
	err = tx.QueryRow(r.Context(),
		`INSERT INTO menu_item_modifier_groups
		 (menu_item_id, name, description, is_required, min_selections, max_selections, sort_order)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)
		 RETURNING id, menu_item_id, name, description, is_required,
		           min_selections, max_selections, sort_order`,
		itemID, req.Name, req.Description, req.IsRequired,
		req.MinSelections, req.MaxSelections, req.SortOrder,
	).Scan(&group.ID, &group.MenuItemID, &group.Name, &group.Description,
		&group.IsRequired, &group.MinSelections, &group.MaxSelections, &group.SortOrder)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create modifier group")
		return
	}

	for _, m := range req.Modifiers {
		var mod models.Modifier
		err := tx.QueryRow(r.Context(),
			`INSERT INTO menu_item_modifiers
			 (group_id, name, price_delta, is_default, is_available, sort_order)
			 VALUES ($1, $2, $3, $4, $5, $6)
			 RETURNING id, group_id, name, price_delta, is_default, is_available, sort_order`,
			group.ID, m.Name, m.PriceDelta, m.IsDefault, m.IsAvailable, m.SortOrder,
		).Scan(&mod.ID, &mod.GroupID, &mod.Name, &mod.PriceDelta,
			&mod.IsDefault, &mod.IsAvailable, &mod.SortOrder)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create modifier")
			return
		}
		group.Modifiers = append(group.Modifiers, mod)
	}

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit")
		return
	}

	writeJSON(w, http.StatusCreated, group)
}

// UpdateModifierGroup replaces a group's metadata and rewrites its option list:
// any option with an id is updated in place, new options are inserted, and any
// option id not present in the incoming list is deleted. This "full replace"
// shape matches how the seller editor submits the form — simpler than tracking
// a per-option action type on the client.
func (h *Handler) UpdateModifierGroup(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	groupID := chi.URLParam(r, "groupId")

	var req ModifierGroupRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if !h.ownsModifierGroup(r, groupID, user["user_id"]) {
		writeError(w, http.StatusNotFound, "modifier group not found")
		return
	}

	if !isValidModifierGroup(req) {
		writeError(w, http.StatusBadRequest, "invalid modifier group: name required, max >= min, required groups need min >= 1")
		return
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin tx")
		return
	}
	defer tx.Rollback(r.Context())

	var group models.ModifierGroup
	err = tx.QueryRow(r.Context(),
		`UPDATE menu_item_modifier_groups
		    SET name = $1, description = $2, is_required = $3,
		        min_selections = $4, max_selections = $5, sort_order = $6,
		        updated_at = NOW()
		  WHERE id = $7
		  RETURNING id, menu_item_id, name, description, is_required,
		            min_selections, max_selections, sort_order`,
		req.Name, req.Description, req.IsRequired,
		req.MinSelections, req.MaxSelections, req.SortOrder, groupID,
	).Scan(&group.ID, &group.MenuItemID, &group.Name, &group.Description,
		&group.IsRequired, &group.MinSelections, &group.MaxSelections, &group.SortOrder)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update modifier group")
		return
	}

	// Collect kept-option ids so we can delete anything not in the new list.
	keptIDs := make([]string, 0, len(req.Modifiers))
	for _, m := range req.Modifiers {
		if m.ID != "" {
			keptIDs = append(keptIDs, m.ID)
		}
	}

	// Delete options removed by the seller. If keptIDs is empty (they removed
	// all existing options) we wipe the whole group — the trailing ANY() does
	// the right thing with an empty array.
	if len(keptIDs) == 0 {
		if _, err := tx.Exec(r.Context(),
			`DELETE FROM menu_item_modifiers WHERE group_id = $1`, groupID); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to clear options")
			return
		}
	} else {
		if _, err := tx.Exec(r.Context(),
			`DELETE FROM menu_item_modifiers
			  WHERE group_id = $1 AND NOT (id = ANY($2))`, groupID, keptIDs); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to prune options")
			return
		}
	}

	// Upsert each option: update if it has an id, insert if not.
	for _, m := range req.Modifiers {
		var mod models.Modifier
		if m.ID != "" {
			err = tx.QueryRow(r.Context(),
				`UPDATE menu_item_modifiers
				    SET name = $1, price_delta = $2, is_default = $3,
				        is_available = $4, sort_order = $5
				  WHERE id = $6 AND group_id = $7
				  RETURNING id, group_id, name, price_delta, is_default, is_available, sort_order`,
				m.Name, m.PriceDelta, m.IsDefault, m.IsAvailable, m.SortOrder,
				m.ID, groupID,
			).Scan(&mod.ID, &mod.GroupID, &mod.Name, &mod.PriceDelta,
				&mod.IsDefault, &mod.IsAvailable, &mod.SortOrder)
		} else {
			err = tx.QueryRow(r.Context(),
				`INSERT INTO menu_item_modifiers
				 (group_id, name, price_delta, is_default, is_available, sort_order)
				 VALUES ($1, $2, $3, $4, $5, $6)
				 RETURNING id, group_id, name, price_delta, is_default, is_available, sort_order`,
				groupID, m.Name, m.PriceDelta, m.IsDefault, m.IsAvailable, m.SortOrder,
			).Scan(&mod.ID, &mod.GroupID, &mod.Name, &mod.PriceDelta,
				&mod.IsDefault, &mod.IsAvailable, &mod.SortOrder)
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to save option")
			return
		}
		group.Modifiers = append(group.Modifiers, mod)
	}

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit")
		return
	}

	writeJSON(w, http.StatusOK, group)
}

func (h *Handler) DeleteModifierGroup(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	groupID := chi.URLParam(r, "groupId")

	if !h.ownsModifierGroup(r, groupID, user["user_id"]) {
		writeError(w, http.StatusNotFound, "modifier group not found")
		return
	}

	// CASCADE on menu_item_modifiers.group_id cleans up options automatically.
	if _, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM menu_item_modifier_groups WHERE id = $1`, groupID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete modifier group")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

func (h *Handler) ownsMenuItem(r *http.Request, itemID string, ownerID interface{}) bool {
	var exists bool
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS (
		    SELECT 1 FROM menu_items mi
		    JOIN restaurants rest ON rest.id = mi.restaurant_id
		    WHERE mi.id = $1 AND rest.owner_id = $2
		 )`, itemID, ownerID).Scan(&exists)
	return exists
}

func (h *Handler) ownsModifierGroup(r *http.Request, groupID string, ownerID interface{}) bool {
	var exists bool
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS (
		    SELECT 1 FROM menu_item_modifier_groups g
		    JOIN menu_items mi ON mi.id = g.menu_item_id
		    JOIN restaurants rest ON rest.id = mi.restaurant_id
		    WHERE g.id = $1 AND rest.owner_id = $2
		 )`, groupID, ownerID).Scan(&exists)
	return exists
}

func isValidModifierGroup(req ModifierGroupRequest) bool {
	if req.Name == "" {
		return false
	}
	if req.MaxSelections < req.MinSelections {
		return false
	}
	if req.IsRequired && req.MinSelections < 1 {
		return false
	}
	return true
}
