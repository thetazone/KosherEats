package handlers

import (
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
)

// Returns a plain JSON array: `[{...}, {...}]`. iOS + Android clients both
// expect unwrapped array responses for list endpoints.
func (h *Handler) ListRestaurants(w http.ResponseWriter, r *http.Request) {
	lat := r.URL.Query().Get("lat")
	lng := r.URL.Query().Get("lng")
	vertical := verticalFromRequest(r)

	const baseQuery = `SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		FROM restaurants WHERE is_active = true AND approval_status = 'approved' AND vertical = $1`

	var rows pgx.Rows
	var err error

	latF, errLat := strconv.ParseFloat(lat, 64)
	lngF, errLng := strconv.ParseFloat(lng, 64)
	useDistance := lat != "" && lng != "" &&
		errLat == nil && errLng == nil &&
		latF >= -90 && latF <= 90 && lngF >= -180 && lngF <= 180

	if useDistance {
		rows, err = h.db.Pool.Query(r.Context(),
			baseQuery+` ORDER BY point($2, $3) <-> point(lng, lat) LIMIT 50`,
			vertical, lngF, latF)
	} else {
		rows, err = h.db.Pool.Query(r.Context(), baseQuery+` ORDER BY rating DESC LIMIT 50`, vertical)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch restaurants")
		return
	}
	defer rows.Close()

	restaurants, err := scanRestaurants(rows)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch restaurants")
		return
	}
	writeJSON(w, http.StatusOK, redactPublicRestaurants(restaurants))
}

// scanRestaurants drains a pgx.Rows into a slice of models.Restaurant.
// Shared by ListRestaurants and SearchRestaurants to avoid duplication.
func scanRestaurants(rows pgx.Rows) ([]models.Restaurant, error) {
	out := []models.Restaurant{}
	for rows.Next() {
		var rest models.Restaurant
		if err := rows.Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL, &rest.LogoURL,
			&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
			&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
			&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.KosherCertificateURL, &rest.CuisineType,
			&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
			&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive, &rest.ApprovalStatus,
			&rest.DeliveryMode, &rest.CreatedAt, &rest.UpdatedAt); err != nil {
			continue
		}
		out = append(out, rest)
	}
	return out, rows.Err()
}

// redactPublicRestaurants clears fields that consumer / anonymous callers must
// not see. owner_id is the seller's internal users.id — opaque (non-enumerable)
// but PII-adjacent and unused by any consumer client: iOS decodes the key but
// never reads the value, Android ignores it entirely. We blank the value rather
// than drop the JSON key, because already-shipped consumer iOS apps do a
// *required* decode of `owner_id` (Models.swift: try decode(String.self)) and
// would fail to parse the whole restaurant if the key disappeared.
//
// scanRestaurants is shared with the seller (ListSellerRestaurants) and admin
// (AdminListRestaurants) views, which legitimately need owner_id — those paths
// intentionally do NOT call this, so only the consumer-facing handlers redact.
func redactPublicRestaurants(rs []models.Restaurant) []models.Restaurant {
	for i := range rs {
		rs[i].OwnerID = ""
	}
	return rs
}

func (h *Handler) GetRestaurant(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	vertical := verticalFromRequest(r)

	var rest models.Restaurant
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		FROM restaurants WHERE id = $1 AND vertical = $2 AND is_active = true AND approval_status = 'approved'`, id, vertical,
	).Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL, &rest.LogoURL,
		&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
		&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
		&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.KosherCertificateURL, &rest.CuisineType,
		&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
		&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive, &rest.ApprovalStatus,
		&rest.DeliveryMode, &rest.CreatedAt, &rest.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	rest.OwnerID = "" // see redactPublicRestaurants — consumer endpoint, don't leak the seller's user id
	writeJSON(w, http.StatusOK, rest)
}

func (h *Handler) GetMenu(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	// Gate the menu on the same visibility rules as GetRestaurant: anonymous or
	// cross-vertical callers, and pending/rejected/deactivated restaurants, must
	// not be able to read a menu by guessing the restaurant ID.
	vertical := verticalFromRequest(r)
	var visible bool
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS(SELECT 1 FROM restaurants
		   WHERE id = $1 AND vertical = $2 AND is_active = true AND approval_status = 'approved')`,
		id, vertical,
	).Scan(&visible); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch menu")
		return
	}
	if !visible {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

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
	if err := catRows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch menu")
		return
	}

	// Collect all items first, then fetch modifier groups/modifiers in two
	// batched queries by restaurant. Avoids N+1 queries as the menu grows.
	var allItemIDs []string
	itemByID := map[string]*models.MenuItem{}

	for i, cat := range categories {
		itemRows, err := h.db.Pool.Query(r.Context(),
			`SELECT id, restaurant_id, category_id, name, description, image_url,
			 price, is_meat, is_dairy, is_pareve, is_available, sort_order
			 FROM menu_items
			 WHERE category_id = $1 AND restaurant_id = $2 AND is_available = true
			 ORDER BY sort_order`, cat.ID, id)
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
		if err := itemRows.Err(); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to fetch menu")
			return
		}

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
	if groupRows.Err() != nil {
		return
	}

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
	if modRows.Err() != nil {
		return
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

	esc := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	q = esc.Replace(q)

	vertical := verticalFromRequest(r)
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		 FROM restaurants
		 WHERE is_active = true AND approval_status = 'approved' AND vertical = $2
		   AND (name ILIKE $1 OR EXISTS (
		       SELECT 1 FROM unnest(cuisine_type) ct WHERE ct ILIKE $1
		   ))
		 ORDER BY rating DESC LIMIT 50`,
		"%"+q+"%", vertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "search failed")
		return
	}
	defer rows.Close()

	restaurants, err := scanRestaurants(rows)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "search failed")
		return
	}
	writeJSON(w, http.StatusOK, redactPublicRestaurants(restaurants))
}

// SuggestedRestaurants returns a personalised alternating list of restaurants:
// unfamiliar (never/rarely ordered), familiar (frequently ordered), unfamiliar,
// familiar, ... For unauthenticated users or users with no order history it
// falls back to popular restaurants sorted by rating.
//
// Uses OptionalAuthMiddleware so both logged-in and guest users get results.
func (h *Handler) SuggestedRestaurants(w http.ResponseWriter, r *http.Request) {
	limit := 10
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l <= 50 {
		limit = l
	}

	user, _ := getUserFromContext(r)
	userID := ""
	if user != nil {
		userID = user["user_id"]
	}
	vertical := verticalFromRequest(r)

	ctx := r.Context()

	// If we have an authenticated user, look up their order history grouped by
	// restaurant: count of delivered/completed orders and most recent order
	// date. This powers the familiar vs unfamiliar split.
	type orderStat struct {
		RestaurantID string
		OrderCount   int
	}
	var familiar []orderStat

	if userID != "" {
		statRows, err := h.db.Pool.Query(ctx,
			`SELECT restaurant_id, COUNT(*) AS order_count
			   FROM orders
			  WHERE user_id = $1
			    AND status IN ('delivered', 'completed')
			  GROUP BY restaurant_id
			  ORDER BY order_count DESC, MAX(created_at) DESC`, userID)
		if err != nil {
			slog.Warn("suggested: failed to load order stats",
				slog.String("user_id", userID), slog.String("error", err.Error()))
		} else {
			for statRows.Next() {
				var s orderStat
				if err := statRows.Scan(&s.RestaurantID, &s.OrderCount); err == nil {
					familiar = append(familiar, s)
				}
			}
			statRows.Close()
		}
	}

	// Build the two buckets — familiar restaurants (ordered 2+ times) and
	// unfamiliar restaurants (everything else, preferring high-rated ones the
	// user hasn't tried).
	familiarIDs := make([]string, 0, len(familiar))
	for _, s := range familiar {
		if s.OrderCount >= 2 {
			familiarIDs = append(familiarIDs, s.RestaurantID)
		}
	}

	// Familiar bucket — full restaurant objects in frequency order.
	var familiarRestaurants []models.Restaurant
	if len(familiarIDs) > 0 {
		famRows, err := h.db.Pool.Query(ctx,
			`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
			        phone, email, street, city, state, zip_code, lat, lng,
			        kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
			        is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
			        est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
			   FROM restaurants
			  WHERE id = ANY($1) AND vertical = $2 AND is_active = true AND approval_status = 'approved'`, familiarIDs, vertical)
		if err == nil {
			scanned, _ := scanRestaurants(famRows)
			famRows.Close()
			// Re-order scanned results by the original frequency order.
			byID := map[string]models.Restaurant{}
			for _, r := range scanned {
				byID[r.ID] = r
			}
			for _, id := range familiarIDs {
				if r, ok := byID[id]; ok {
					familiarRestaurants = append(familiarRestaurants, r)
				}
			}
		}
	}

	// Unfamiliar bucket — all active restaurants the user has never or rarely
	// ordered from, sorted by rating descending so the best ones float up.
	// For guests (no order history) this is effectively "top rated".
	var allOrderedIDs []string
	for _, s := range familiar {
		allOrderedIDs = append(allOrderedIDs, s.RestaurantID)
	}

	var unfamiliarRestaurants []models.Restaurant
	if len(allOrderedIDs) > 0 {
		unfamRows, err := h.db.Pool.Query(ctx,
			`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
			        phone, email, street, city, state, zip_code, lat, lng,
			        kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
			        is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
			        est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
			   FROM restaurants
			  WHERE is_active = true AND approval_status = 'approved' AND vertical = $3 AND id != ALL($1)
			  ORDER BY rating DESC
			  LIMIT $2`, allOrderedIDs, limit, vertical)
		if err == nil {
			unfamiliarRestaurants, _ = scanRestaurants(unfamRows)
			unfamRows.Close()
		}
	} else {
		// Guest user or no order history — just serve top-rated restaurants.
		unfamRows, err := h.db.Pool.Query(ctx,
			`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
			        phone, email, street, city, state, zip_code, lat, lng,
			        kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
			        is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
			        est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
			   FROM restaurants
			  WHERE is_active = true AND approval_status = 'approved' AND vertical = $2
			  ORDER BY rating DESC
			  LIMIT $1`, limit, vertical)
		if err == nil {
			unfamiliarRestaurants, _ = scanRestaurants(unfamRows)
			unfamRows.Close()
		}
	}

	// Interleave: unfamiliar, familiar, unfamiliar, familiar, ...
	// If one bucket runs out, append the remainder of the other.
	result := make([]models.Restaurant, 0, limit)
	ui, fi := 0, 0
	for len(result) < limit && (ui < len(unfamiliarRestaurants) || fi < len(familiarRestaurants)) {
		if ui < len(unfamiliarRestaurants) {
			result = append(result, unfamiliarRestaurants[ui])
			ui++
		}
		if len(result) >= limit {
			break
		}
		if fi < len(familiarRestaurants) {
			result = append(result, familiarRestaurants[fi])
			fi++
		}
	}

	if result == nil {
		result = []models.Restaurant{}
	}

	writeJSON(w, http.StatusOK, redactPublicRestaurants(result))
}

// --- Favorites ---

func (h *Handler) AddFavorite(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	rid := chi.URLParam(r, "restaurant_id")
	_, err = h.db.Pool.Exec(r.Context(),
		`INSERT INTO restaurant_favorites (user_id, restaurant_id) VALUES ($1, $2)
		 ON CONFLICT DO NOTHING`,
		user["user_id"], rid)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to favorite")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "favorited"})
}

func (h *Handler) RemoveFavorite(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	rid := chi.URLParam(r, "restaurant_id")
	if _, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM restaurant_favorites WHERE user_id = $1 AND restaurant_id = $2`,
		user["user_id"], rid); err != nil {
		slog.Warn("failed to remove favorite",
			slog.String("user_id", user["user_id"]), slog.String("restaurant_id", rid),
			slog.String("error", err.Error()))
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "removed"})
}

func (h *Handler) ListFavorites(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT r.id, r.owner_id, r.name, r.description, r.image_url, r.cover_image_url, r.logo_url,
		        r.phone, r.email, r.street, r.city, r.state, r.zip_code, r.lat, r.lng,
		        r.kosher_certification, r.certifying_agency, r.is_cholov_yisroel, r.is_pas_yisroel,
		        r.is_glatt_kosher, r.kosher_certificate_url, r.cuisine_type, r.rating, r.review_count, r.delivery_fee, r.min_order,
		        r.est_delivery_min, r.est_delivery_max, r.is_open, r.is_active, r.approval_status, r.delivery_mode, r.created_at, r.updated_at
		   FROM restaurant_favorites f
		   JOIN restaurants r ON f.restaurant_id = r.id
		  WHERE f.user_id = $1 AND r.vertical = $2
		  ORDER BY f.created_at DESC`, user["user_id"], verticalFromRequest(r))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list favorites")
		return
	}
	defer rows.Close()
	restaurants, err := scanRestaurants(rows)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list favorites")
		return
	}
	writeJSON(w, http.StatusOK, redactPublicRestaurants(restaurants))
}

func (h *Handler) ListFavoriteIDs(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT f.restaurant_id FROM restaurant_favorites f
		   JOIN restaurants r ON f.restaurant_id = r.id
		  WHERE f.user_id = $1 AND r.vertical = $2`,
		user["user_id"], verticalFromRequest(r))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load favorites")
		return
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err == nil {
			ids = append(ids, id)
		}
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load favorites")
		return
	}
	if ids == nil {
		ids = []string{}
	}
	writeJSON(w, http.StatusOK, ids)
}
