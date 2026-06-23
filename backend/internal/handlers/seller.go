package handlers

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
)

// normalizeKosherCertification maps any case variant (e.g. "ou", "OU", "Ou")
// to the canonical form the consumer apps' Codable enums expect. iOS uses
// strict raw-value matching ("OU", "Star-K", "Kof-K", "cRc"…), so a row
// stored as "ou" breaks decoding for the entire restaurants list. Unknown
// values pass through unchanged so future certifications don't silently
// disappear before someone adds them to the canonical map.
func normalizeKosherCertification(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "ou":
		return "OU"
	case "ok":
		return "OK"
	case "kof-k":
		return "Kof-K"
	case "star-k":
		return "Star-K"
	case "crc":
		return "cRc"
	case "badatz":
		return "Badatz"
	case "chof-k":
		return "Chof-K"
	default:
		return v
	}
}

// UpdateRestaurantRequest is a partial update — every field is optional.
// Nil means "leave this column alone"; non-nil means "write this value."
// Using pointers here lets the seller UI save a single section without
// clobbering fields it doesn't render (e.g. lat/lng, is_active).
type UpdateRestaurantRequest struct {
	Name        *string  `json:"name"`
	Description *string  `json:"description"`
	Phone       *string  `json:"phone"`
	Email       *string  `json:"email"`
	Street      *string  `json:"street"`
	City        *string  `json:"city"`
	State       *string  `json:"state"`
	ZipCode     *string  `json:"zip_code"`
	CuisineType []string `json:"cuisine_type"`
	// Money fields are cents to match the DB (delivery_fee / min_order are INTEGER).
	DeliveryFee         *int    `json:"delivery_fee"`
	MinOrder            *int    `json:"min_order"`
	EstDeliveryMin      *int    `json:"est_delivery_min"`
	EstDeliveryMax      *int    `json:"est_delivery_max"`
	IsOpen              *bool   `json:"is_open"`
	KosherCertification *string `json:"kosher_certification"`
	CertifyingAgency    *string `json:"certifying_agency"`
	IsCholovYisroel     *bool   `json:"is_cholov_yisroel"`
	IsPasYisroel        *bool   `json:"is_pas_yisroel"`
	IsGlattKosher       *bool   `json:"is_glatt_kosher"`
	KosherCertificateURL *string `json:"kosher_certificate_url"`
	DeliveryMode        *string `json:"delivery_mode"`
	ImageURL            *string `json:"image_url"`
	LogoURL             *string `json:"logo_url"`
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

// CreateRestaurantRequest is the form a seller fills on first launch (or when
// adding a second restaurant). Fields are required-or-defaulted at the handler
// rather than at the DB layer so we can return a useful 400 instead of a
// generic "null value violates NOT NULL constraint" error.
type CreateRestaurantRequest struct {
	Name                 string   `json:"name"`
	Description          string   `json:"description"`
	ImageURL             string   `json:"image_url"`
	LogoURL              string   `json:"logo_url"`
	Phone                string   `json:"phone"`
	Email                string   `json:"email"`
	Street               string   `json:"street"`
	City                 string   `json:"city"`
	State                string   `json:"state"`
	ZipCode              string   `json:"zip_code"`
	KosherCertification  string   `json:"kosher_certification"`
	CertifyingAgency     string   `json:"certifying_agency"`
	KosherCertificateURL string   `json:"kosher_certificate_url"`
	CuisineType          []string `json:"cuisine_type"`
	IsCholovYisroel      bool     `json:"is_cholov_yisroel"`
	IsPasYisroel         bool     `json:"is_pas_yisroel"`
	IsGlattKosher        bool     `json:"is_glatt_kosher"`
	// FromImport: the seller is importing their menu from UberEats, so the
	// address/phone/picture get filled by the import worker afterward — relax
	// those required-field checks (name/email/cert stay required up front).
	FromImport           bool     `json:"from_import"`
}

// CreateRestaurant inserts a new restaurant owned by the calling seller.
// Used by the seller iOS app's "create your first restaurant" onboarding flow
// and (eventually) a multi-restaurant "add another" path. Defaults are picked
// to land the restaurant in a usable but offline state — is_open=false so
// orders don't immediately route to a kitchen that isn't ready, sensible
// delivery-fee/time defaults the seller can tune in Settings.
//
// lat/lng default to NYC-center for now (no geocoding wired up). The seller
// can fix these in Settings, or we add geocoding in a follow-up.
func (h *Handler) CreateRestaurant(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req CreateRestaurantRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// Required-field validation. Description is allowed empty so first-time
	// sellers aren't blocked on writing copy.
	if req.Name == "" {
		writeError(w, http.StatusBadRequest, "name is required")
		return
	}
	// Address, phone, and the restaurant picture are filled by the UberEats
	// import worker when from_import is set, so don't block onboarding on them.
	if !req.FromImport {
		if req.Street == "" || req.City == "" || req.State == "" || req.ZipCode == "" {
			writeError(w, http.StatusBadRequest, "full address (street, city, state, zip_code) is required")
			return
		}
		if req.Phone == "" {
			writeError(w, http.StatusBadRequest, "phone is required")
			return
		}
		if req.ImageURL == "" {
			writeError(w, http.StatusBadRequest, "image_url (restaurant picture) is required")
			return
		}
	}
	if req.Email == "" {
		writeError(w, http.StatusBadRequest, "email is required")
		return
	}
	if req.KosherCertification == "" {
		writeError(w, http.StatusBadRequest, "kosher_certification is required")
		return
	}
	if req.KosherCertificateURL == "" {
		writeError(w, http.StatusBadRequest, "kosher_certificate_url is required")
		return
	}
	// Canonicalize to the form iOS's strict Codable enum expects (e.g. "ou" → "OU").
	// The seller app sometimes lower-cases user input; without this normalization
	// the consumer apps fail to decode the entire restaurants list.
	req.KosherCertification = normalizeKosherCertification(req.KosherCertification)
	if len(req.Name) > 200 {
		writeError(w, http.StatusBadRequest, "name too long (max 200)")
		return
	}
	if len(req.Description) > 2000 {
		writeError(w, http.StatusBadRequest, "description too long (max 2000)")
		return
	}

	cuisine := req.CuisineType
	if cuisine == nil {
		cuisine = []string{}
	}

	// NYC default — geocoding is a follow-up. The seller can correct this in
	// Settings; the consumer map view will show the restaurant at this point
	// until then.
	const defaultLat, defaultLng = 40.7128, -74.0060
	const defaultDeliveryFee = 399 // $3.99 cents
	const defaultMinOrder = 0
	const defaultEstMin = 25
	const defaultEstMax = 45

	// New restaurants land pending — the platform admin reviews them via
	// the emailed magic links before they become visible to consumers.
	// is_active is gated on the approval decision, not the seller's input.
	approvalToken := generateApprovalToken()

	// Inherit the seller's vertical so KosherEats sellers create kosher
	// restaurants and GreenEats sellers create vegan restaurants. Defaults
	// to 'kosher' for legacy clients/tokens that don't carry the claim.
	sellerVertical := user["vertical"]
	if sellerVertical == "" {
		sellerVertical = "kosher"
	}

	var rest models.Restaurant
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO restaurants (
			owner_id, name, description, image_url, cover_image_url, logo_url,
			phone, email, street, city, state, zip_code, lat, lng,
			kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
			is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
			est_delivery_min, est_delivery_max, is_open, is_active,
			approval_status, approval_token, vertical
		)
		VALUES ($1, $2, $3, $4, '', $5,
			$6, $7, $8, $9, $10, $11, $12, $13,
			$14, $15, $16, $17,
			$18, $19, $20, 0, 0, $21, $22,
			$23, $24, false, false,
			'pending', $25, $26)
		RETURNING id, owner_id, name, description, image_url, cover_image_url, logo_url,
			phone, email, street, city, state, zip_code, lat, lng,
			kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
			is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
			est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at`,
		user["user_id"], req.Name, req.Description, req.ImageURL, req.LogoURL,
		req.Phone, req.Email, req.Street, req.City, req.State, req.ZipCode, defaultLat, defaultLng,
		req.KosherCertification, req.CertifyingAgency, req.IsCholovYisroel, req.IsPasYisroel,
		req.IsGlattKosher, req.KosherCertificateURL, cuisine, defaultDeliveryFee, defaultMinOrder,
		defaultEstMin, defaultEstMax, approvalToken, sellerVertical,
	).Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL, &rest.LogoURL,
		&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
		&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
		&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.KosherCertificateURL, &rest.CuisineType,
		&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
		&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive, &rest.ApprovalStatus,
		&rest.DeliveryMode, &rest.CreatedAt, &rest.UpdatedAt)

	if err != nil {
		slog.Error("CreateRestaurant insert failed",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to create restaurant")
		return
	}

	// Fire off the admin notification asynchronously — never block the
	// seller's response on a flaky mail server.
	go h.sendNewSubmissionAdminEmail(rest.ID, approvalToken)

	writeJSON(w, http.StatusCreated, rest)
}

// ListSellerRestaurants returns every restaurant the current seller owns.
// Used by the iOS restaurant picker to populate its list.
func (h *Handler) ListSellerRestaurants(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		 FROM restaurants WHERE owner_id = $1 ORDER BY name`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list restaurants")
		return
	}
	defer rows.Close()

	restaurants, err := scanRestaurants(rows)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list restaurants")
		return
	}
	writeJSON(w, http.StatusOK, restaurants)
}

func (h *Handler) GetSellerRestaurant(w http.ResponseWriter, r *http.Request) {
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

	var rest models.Restaurant
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		FROM restaurants WHERE id = $1`, restID,
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

	writeJSON(w, http.StatusOK, rest)
}

func (h *Handler) UpdateRestaurant(w http.ResponseWriter, r *http.Request) {
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

	var req UpdateRestaurantRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Name != nil && len(*req.Name) > 200 {
		writeError(w, http.StatusBadRequest, "name too long (max 200)")
		return
	}
	if req.Description != nil && len(*req.Description) > 2000 {
		writeError(w, http.StatusBadRequest, "description too long (max 2000)")
		return
	}
	if req.DeliveryMode != nil {
		switch *req.DeliveryMode {
		case "platform", "external", "restaurant":
		default:
			writeError(w, http.StatusBadRequest, "delivery_mode must be platform, external, or restaurant")
			return
		}
	}
	// Canonicalize so iOS's strict Codable enum on KosherCertification keeps
	// decoding [Restaurant] regardless of how the seller app capitalised it.
	if req.KosherCertification != nil {
		normalized := normalizeKosherCertification(*req.KosherCertification)
		req.KosherCertification = &normalized
	}

	// Guard: a seller can't open a restaurant for orders before the platform
	// admin has approved it. We let them toggle the flag in their UI for
	// convenience while editing, but the actual marketplace gate is the
	// is_active + approval_status filter on the consumer endpoints. Reject
	// here so the seller doesn't think they're live when they're not.
	if req.IsOpen != nil && *req.IsOpen {
		var currentStatus string
		err := h.db.Pool.QueryRow(r.Context(),
			`SELECT approval_status FROM restaurants WHERE id = $1`, restID,
		).Scan(&currentStatus)
		if err == nil && currentStatus != "approved" {
			writeError(w, http.StatusForbidden,
				"restaurant must be approved before it can be opened for orders")
			return
		}
	}

	// Use COALESCE so each column only gets overwritten when the client
	// supplied a non-nil pointer. This means partial updates (e.g. only
	// flipping is_open) don't blank out the rest of the row.
	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET
			name                   = COALESCE($1,  name),
			description            = COALESCE($2,  description),
			phone                  = COALESCE($3,  phone),
			email                  = COALESCE($4,  email),
			street                 = COALESCE($5,  street),
			city                   = COALESCE($6,  city),
			state                  = COALESCE($7,  state),
			zip_code               = COALESCE($8,  zip_code),
			cuisine_type           = COALESCE($9,  cuisine_type),
			delivery_fee           = COALESCE($10, delivery_fee),
			min_order              = COALESCE($11, min_order),
			est_delivery_min       = COALESCE($12, est_delivery_min),
			est_delivery_max       = COALESCE($13, est_delivery_max),
			is_open                = COALESCE($14, is_open),
			kosher_certification   = COALESCE($15, kosher_certification),
			certifying_agency      = COALESCE($16, certifying_agency),
			is_cholov_yisroel      = COALESCE($17, is_cholov_yisroel),
			is_pas_yisroel         = COALESCE($18, is_pas_yisroel),
			is_glatt_kosher        = COALESCE($19, is_glatt_kosher),
			delivery_mode          = COALESCE($20, delivery_mode),
			kosher_certificate_url = COALESCE($22, kosher_certificate_url),
			image_url              = COALESCE($23, image_url),
			logo_url               = COALESCE($24, logo_url),
			updated_at             = NOW()
		 WHERE id = $21`,
		req.Name, req.Description, req.Phone, req.Email,
		req.Street, req.City, req.State, req.ZipCode,
		req.CuisineType,
		req.DeliveryFee, req.MinOrder, req.EstDeliveryMin, req.EstDeliveryMax,
		req.IsOpen, req.KosherCertification, req.CertifyingAgency,
		req.IsCholovYisroel, req.IsPasYisroel, req.IsGlattKosher,
		req.DeliveryMode,
		restID, req.KosherCertificateURL, req.ImageURL, req.LogoURL)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update restaurant")
		return
	}

	// Return the full, post-update Restaurant so the iOS client can swap
	// its in-memory model in one call (avoids a second GET round-trip and
	// keeps the seller UI and DB in lockstep).
	var rest models.Restaurant
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		phone, email, street, city, state, zip_code, lat, lng,
		kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		FROM restaurants WHERE id = $1`, restID,
	).Scan(&rest.ID, &rest.OwnerID, &rest.Name, &rest.Description, &rest.ImageURL, &rest.CoverImageURL, &rest.LogoURL,
		&rest.Phone, &rest.Email, &rest.Street, &rest.City, &rest.State, &rest.ZipCode,
		&rest.Lat, &rest.Lng, &rest.KosherCertification, &rest.CertifyingAgency,
		&rest.IsCholovYisroel, &rest.IsPasYisroel, &rest.IsGlattKosher, &rest.KosherCertificateURL, &rest.CuisineType,
		&rest.Rating, &rest.ReviewCount, &rest.DeliveryFee, &rest.MinOrder,
		&rest.EstDeliveryMin, &rest.EstDeliveryMax, &rest.IsOpen, &rest.IsActive, &rest.ApprovalStatus,
		&rest.DeliveryMode, &rest.CreatedAt, &rest.UpdatedAt)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "updated but failed to reload restaurant")
		return
	}

	writeJSON(w, http.StatusOK, rest)
}

func (h *Handler) GetSellerMenu(w http.ResponseWriter, r *http.Request) {
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
			 FROM menu_items WHERE category_id = $1 AND restaurant_id = $2
			 ORDER BY sort_order`, cat.ID, restID)
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req CreateMenuItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	if req.Price < 0 {
		writeError(w, http.StatusBadRequest, "price must be non-negative")
		return
	}

	var item models.MenuItem
	err = h.db.Pool.QueryRow(r.Context(),
		// Only allow inserts through a category owned by this seller's restaurant.
		// This closes the cross-tenant hole where one seller could inject items
		// into another restaurant's public menu by reusing that category UUID.
		`INSERT INTO menu_items (restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available)
		 SELECT $1, mc.id, $3, $4, $5, $6, $7, $8, $9, true
		 FROM menu_categories mc
		 WHERE mc.id = $2 AND mc.restaurant_id = $1
		 RETURNING id, restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		restID, req.CategoryID, req.Name, req.Description, req.ImageURL, req.Price,
		req.IsMeat, req.IsDairy, req.IsPareve,
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.ImageURL, &item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)

	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusBadRequest, "category not found for restaurant")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to create menu item")
		return
	}

	writeJSON(w, http.StatusCreated, item)
}

func (h *Handler) UpdateMenuItem(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	itemID := chi.URLParam(r, "id")

	var req CreateMenuItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Price < 0 {
		writeError(w, http.StatusBadRequest, "price must be non-negative")
		return
	}

	var item models.MenuItem
	err = h.db.Pool.QueryRow(r.Context(),
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	itemID := chi.URLParam(r, "id")

	var req struct {
		IsAvailable bool `json:"is_available"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var item models.MenuItem
	err = h.db.Pool.QueryRow(r.Context(),
		`UPDATE menu_items SET is_available = $1, updated_at = NOW()
		 WHERE id = $2 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $3)
		 RETURNING id, restaurant_id, category_id, name, description, image_url, price,
		 is_meat, is_dairy, is_pareve, is_available, sort_order`,
		req.IsAvailable, itemID, user["user_id"],
	).Scan(&item.ID, &item.RestaurantID, &item.CategoryID, &item.Name, &item.Description,
		&item.ImageURL, &item.Price, &item.IsMeat, &item.IsDairy, &item.IsPareve, &item.IsAvailable, &item.SortOrder)
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req struct {
		IsOpen bool `json:"is_open"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	// Don't let a seller flip "open for orders" until the platform admin
	// has approved the restaurant. Closing (false) is always allowed.
	if req.IsOpen {
		var status string
		if err := h.db.Pool.QueryRow(r.Context(),
			`SELECT approval_status FROM restaurants WHERE id = $1`, restID,
		).Scan(&status); err == nil && status != "approved" {
			writeError(w, http.StatusForbidden,
				"restaurant must be approved before it can be opened for orders")
			return
		}
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req struct {
		Name string `json:"name"`
	}
	if err := readJSON(r, &req); err != nil || req.Name == "" {
		writeError(w, http.StatusBadRequest, "category name required")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
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

// --- Menu import (self-serve UberEats import) --------------------------------

// menuImportSelectCols is shared by the import read/return queries so the scan
// order in scanMenuImport stays in lockstep. error is coalesced to '' so the
// destination *string never sees a NULL.
const menuImportSelectCols = `id, restaurant_id, source, source_url, status, items_total, items_created, COALESCE(error, ''), created_at, updated_at`

// scanMenuImport reads one menu_imports row in menuImportSelectCols order.
// Works for both pgx.Row (QueryRow) and pgx.Rows (Query) — both satisfy Scan.
func scanMenuImport(row interface{ Scan(dest ...any) error }, job *models.MenuImport) error {
	return row.Scan(&job.ID, &job.RestaurantID, &job.Source, &job.SourceURL,
		&job.Status, &job.ItemsTotal, &job.ItemsCreated, &job.Error,
		&job.CreatedAt, &job.UpdatedAt)
}

// CreateMenuImport enqueues an async menu import for the seller's restaurant
// from an external store URL (UberEats today). The scrape+import runs
// out-of-process on a residential browser node (datacenter IPs get blocked by
// UberEats); this handler only records the job. Returns 202 with the new row.
func (h *Handler) CreateMenuImport(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req struct {
		Source    string `json:"source"`
		SourceURL string `json:"source_url"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Source == "" {
		req.Source = "ubereats"
	}
	if req.Source != "ubereats" {
		writeError(w, http.StatusBadRequest, "unsupported import source")
		return
	}
	if !isUberEatsURL(req.SourceURL) {
		writeError(w, http.StatusBadRequest, "a valid ubereats.com store URL is required")
		return
	}

	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	var job models.MenuImport
	err = scanMenuImport(h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO menu_imports (restaurant_id, source, source_url)
		 VALUES ($1, $2, $3)
		 RETURNING `+menuImportSelectCols,
		restID, req.Source, strings.TrimSpace(req.SourceURL),
	), &job)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create import job")
		return
	}
	writeJSON(w, http.StatusAccepted, job)
}

// GetMenuImport returns one import job, scoped to the caller's restaurants so a
// seller can't read another seller's job.
func (h *Handler) GetMenuImport(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	jobID := chi.URLParam(r, "id")

	var job models.MenuImport
	err = scanMenuImport(h.db.Pool.QueryRow(r.Context(),
		`SELECT `+menuImportSelectCols+`
		 FROM menu_imports
		 WHERE id = $1 AND restaurant_id IN (SELECT id FROM restaurants WHERE owner_id = $2)`,
		jobID, user["user_id"],
	), &job)
	if err != nil {
		writeError(w, http.StatusNotFound, "import not found")
		return
	}
	writeJSON(w, http.StatusOK, job)
}

// ListMenuImports returns recent import jobs for the seller's resolved
// restaurant, newest first — used by the Menu screen to show import progress.
func (h *Handler) ListMenuImports(w http.ResponseWriter, r *http.Request) {
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
		`SELECT `+menuImportSelectCols+`
		 FROM menu_imports WHERE restaurant_id = $1
		 ORDER BY created_at DESC LIMIT 20`,
		restID,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list imports")
		return
	}
	defer rows.Close()

	jobs := []models.MenuImport{}
	for rows.Next() {
		var job models.MenuImport
		if err := scanMenuImport(rows, &job); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to read imports")
			return
		}
		jobs = append(jobs, job)
	}
	writeJSON(w, http.StatusOK, jobs)
}

// isUberEatsURL accepts links with or without a scheme and requires an
// ubereats.com host. Mirrors the iOS client-side check so both reject the same
// inputs. The scrape worker is responsible for the URL actually resolving.
func isUberEatsURL(raw string) bool {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return false
	}
	lower := strings.ToLower(raw)
	if !strings.HasPrefix(lower, "http://") && !strings.HasPrefix(lower, "https://") {
		raw = "https://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	return host == "ubereats.com" || strings.HasSuffix(host, ".ubereats.com")
}

// DeleteCategory removes a category (cascades to items via FK ON DELETE CASCADE).
func (h *Handler) DeleteCategory(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
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

	var stats models.DashboardStats
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT
		    COUNT(*) FILTER (WHERE o.created_at::date = CURRENT_DATE
		                      AND o.status NOT IN ('cancelled','rejected'))   AS today_orders,
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
	if err != nil {
		slog.Error("GetDashboardStats query failed", "error", err, "restaurant_id", restID)
		writeError(w, http.StatusInternalServerError, "failed to load dashboard stats")
		return
	}

	writeJSON(w, http.StatusOK, stats)
}

func (h *Handler) DeleteMenuItem(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
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
	Name          string                  `json:"name"`
	Description   string                  `json:"description"`
	IsRequired    bool                    `json:"is_required"`
	MinSelections int                     `json:"min_selections"`
	MaxSelections int                     `json:"max_selections"`
	SortOrder     int                     `json:"sort_order"`
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	itemID := chi.URLParam(r, "itemId")

	var req ModifierGroupRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	owned, err := h.ownsMenuItem(r, itemID, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to verify menu item ownership")
		return
	}
	if !owned {
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
		if m.PriceDelta < 0 {
			writeError(w, http.StatusBadRequest, "price_delta must be non-negative")
			return
		}
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := chi.URLParam(r, "groupId")

	var req ModifierGroupRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	ownedGroup, errGroup := h.ownsModifierGroup(r, groupID, user["user_id"])
	if errGroup != nil {
		writeError(w, http.StatusInternalServerError, "failed to verify modifier group ownership")
		return
	}
	if !ownedGroup {
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
		if m.PriceDelta < 0 {
			writeError(w, http.StatusBadRequest, "price_delta must be non-negative")
			return
		}
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	groupID := chi.URLParam(r, "groupId")

	ownedDel, errDel := h.ownsModifierGroup(r, groupID, user["user_id"])
	if errDel != nil {
		writeError(w, http.StatusInternalServerError, "failed to verify modifier group ownership")
		return
	}
	if !ownedDel {
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

func (h *Handler) ownsMenuItem(r *http.Request, itemID string, ownerID interface{}) (bool, error) {
	var exists bool
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS (
		    SELECT 1 FROM menu_items mi
		    JOIN restaurants rest ON rest.id = mi.restaurant_id
		    WHERE mi.id = $1 AND rest.owner_id = $2
		 )`, itemID, ownerID).Scan(&exists)
	return exists, err
}

func (h *Handler) ownsModifierGroup(r *http.Request, groupID string, ownerID interface{}) (bool, error) {
	var exists bool
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS (
		    SELECT 1 FROM menu_item_modifier_groups g
		    JOIN menu_items mi ON mi.id = g.menu_item_id
		    JOIN restaurants rest ON rest.id = mi.restaurant_id
		    WHERE g.id = $1 AND rest.owner_id = $2
		 )`, groupID, ownerID).Scan(&exists)
	return exists, err
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
