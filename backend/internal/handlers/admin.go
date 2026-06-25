package handlers

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

// AdminMiddleware gates /api/v1/admin/* routes to users with role=admin.
// Consumers + sellers + couriers can never reach these endpoints.
func (h *Handler) AdminMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userData, ok := r.Context().Value(userContextKey).(map[string]string)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if userData["role"] != string(models.RoleAdmin) {
			writeError(w, http.StatusForbidden, "admin access required")
			return
		}
		next.ServeHTTP(w, r.WithContext(r.Context()))
	})
}

// AdminListRestaurants returns every restaurant in the system (not just
// ones the caller owns). Used by the web admin to manage the whole fleet.
func (h *Handler) AdminListRestaurants(w http.ResponseWriter, r *http.Request) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, owner_id, name, description, image_url, cover_image_url, logo_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, kosher_certificate_url, cuisine_type, rating, review_count, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, approval_status, delivery_mode, created_at, updated_at
		 FROM restaurants ORDER BY name`)
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

// AdminCreateRestaurant onboards a new restaurant from the admin UI.
// Takes full Restaurant fields in the body and inserts them. The owner_id
// must be an existing seller user — admins create the seller account first.
type AdminCreateRestaurantRequest struct {
	OwnerID             string   `json:"owner_id"`
	Name                string   `json:"name"`
	Description         string   `json:"description"`
	ImageURL            string   `json:"image_url"`
	CoverImageURL       string   `json:"cover_image_url"`
	Phone               string   `json:"phone"`
	Email               string   `json:"email"`
	Street              string   `json:"street"`
	City                string   `json:"city"`
	State               string   `json:"state"`
	ZipCode             string   `json:"zip_code"`
	Lat                 float64  `json:"lat"`
	Lng                 float64  `json:"lng"`
	KosherCertification string   `json:"kosher_certification"`
	CertifyingAgency    string   `json:"certifying_agency"`
	IsCholovYisroel     bool     `json:"is_cholov_yisroel"`
	IsPasYisroel        bool     `json:"is_pas_yisroel"`
	IsGlattKosher       bool     `json:"is_glatt_kosher"`
	CuisineType         []string `json:"cuisine_type"`
	DeliveryFee         int      `json:"delivery_fee"`
	MinOrder            int      `json:"min_order"`
	EstDeliveryMin      int      `json:"est_delivery_min"`
	EstDeliveryMax      int      `json:"est_delivery_max"`
	DeliveryMode        string   `json:"delivery_mode"`
	Vertical            string   `json:"vertical"`
}

func (h *Handler) AdminCreateRestaurant(w http.ResponseWriter, r *http.Request) {
	var req AdminCreateRestaurantRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.OwnerID == "" || req.Name == "" {
		writeError(w, http.StatusBadRequest, "owner_id and name required")
		return
	}
	if len(req.Name) > 200 {
		writeError(w, http.StatusBadRequest, "name too long (max 200 characters)")
		return
	}
	if len(req.Description) > 2000 {
		writeError(w, http.StatusBadRequest, "description too long (max 2000 characters)")
		return
	}
	if req.Lat < -90 || req.Lat > 90 || req.Lng < -180 || req.Lng > 180 {
		writeError(w, http.StatusBadRequest, "lat must be [-90,90] and lng must be [-180,180]")
		return
	}
	// Verify owner exists and is a seller.
	var ownerRole string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT role FROM users WHERE id = $1`, req.OwnerID,
	).Scan(&ownerRole); err != nil {
		writeError(w, http.StatusBadRequest, "owner_id does not match an existing user")
		return
	}
	if ownerRole != "seller" {
		writeError(w, http.StatusBadRequest, "owner must have the seller role")
		return
	}

	var id string
	err := h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO restaurants (owner_id, name, description, image_url, cover_image_url,
		 phone, email, street, city, state, zip_code, lat, lng,
		 kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel,
		 is_glatt_kosher, kosher_certificate_url, cuisine_type, delivery_fee, min_order,
		 est_delivery_min, est_delivery_max, is_open, is_active, delivery_mode,
		 vertical, approval_status, reviewed_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15,
		         $16, $17, $18, '', $19, $20, $21, $22, $23, true, true, COALESCE(NULLIF($24,''), 'platform'),
		         COALESCE(NULLIF($25,''), 'kosher'), 'approved', NOW())
		 RETURNING id`,
		req.OwnerID, req.Name, req.Description, req.ImageURL, req.CoverImageURL,
		req.Phone, req.Email, req.Street, req.City, req.State, req.ZipCode,
		req.Lat, req.Lng, req.KosherCertification, req.CertifyingAgency,
		req.IsCholovYisroel, req.IsPasYisroel, req.IsGlattKosher, req.CuisineType,
		req.DeliveryFee, req.MinOrder, req.EstDeliveryMin, req.EstDeliveryMax,
		req.DeliveryMode, req.Vertical,
	).Scan(&id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create restaurant")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": id})
}

// AdminSetRestaurantApproval lets an admin override the approval state of any
// restaurant — including reversing a previously-recorded decision. The magic-
// link flow in restaurant_approval.go is intentionally one-shot and refuses to
// re-decide once a decision is made; this endpoint is the escape hatch (for
// example, when a kosher cert was misjudged on first review).
//
// status must be one of: approved, rejected, pending. Setting "pending" resets
// the row so the original admin email's magic link works again. Setting
// approved/rejected emails the seller — but only when the status actually
// changes, so re-saving the same status doesn't spam them.
type AdminSetRestaurantApprovalRequest struct {
	Status string `json:"status"`
	Notes  string `json:"notes"`
}

func (h *Handler) AdminSetRestaurantApproval(w http.ResponseWriter, r *http.Request) {
	restID := chi.URLParam(r, "id")
	var req AdminSetRestaurantApprovalRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	switch req.Status {
	case "approved", "rejected", "pending":
	default:
		writeError(w, http.StatusBadRequest, "status must be approved, rejected, or pending")
		return
	}

	var prevStatus string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT approval_status FROM restaurants WHERE id = $1`, restID,
	).Scan(&prevStatus); err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	isActive := req.Status == "approved"
	var reviewedAt any = time.Now()
	if req.Status == "pending" {
		reviewedAt = nil
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants
		    SET approval_status = $1,
		        approval_notes  = $2,
		        is_active       = $3,
		        reviewed_at     = $4,
		        updated_at      = NOW()
		  WHERE id = $5`,
		req.Status, req.Notes, isActive, reviewedAt, restID,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update approval")
		return
	}

	if req.Status != prevStatus && (req.Status == "approved" || req.Status == "rejected") {
		h.sendDecisionEmail(restID, req.Status, req.Notes)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": req.Status})
}

// AdminListCouriers returns every courier with their profile info. The
// approval queue is the subset where onboarding_status != 'approved'.
func (h *Handler) AdminListCouriers(w http.ResponseWriter, r *http.Request) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.created_at,
		        cp.onboarding_status, cp.vehicle_type, cp.vehicle_make, cp.vehicle_model,
		        cp.license_plate, cp.drivers_license_number, cp.background_check_status,
		        cp.total_deliveries, cp.rating, cp.is_online, cp.payout_ready
		   FROM users u
		   JOIN courier_profiles cp ON cp.user_id = u.id
		  WHERE u.role = 'courier'
		  ORDER BY
		    CASE cp.onboarding_status
		      WHEN 'pending_background' THEN 0
		      WHEN 'pending_documents'  THEN 1
		      WHEN 'pending_info'       THEN 2
		      WHEN 'approved'           THEN 3
		      ELSE 4
		    END,
		    u.created_at DESC`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list couriers")
		return
	}
	defer rows.Close()

	type CourierAdminRow struct {
		ID                    string  `json:"id"`
		Email                 string  `json:"email"`
		FirstName             string  `json:"first_name"`
		LastName              string  `json:"last_name"`
		Phone                 string  `json:"phone"`
		CreatedAt             string  `json:"created_at"`
		OnboardingStatus      string  `json:"onboarding_status"`
		VehicleType           string  `json:"vehicle_type"`
		VehicleMake           string  `json:"vehicle_make"`
		VehicleModel          string  `json:"vehicle_model"`
		LicensePlate          string  `json:"license_plate"`
		DriversLicenseNumber  string  `json:"drivers_license_number"`
		BackgroundCheckStatus string  `json:"background_check_status"`
		TotalDeliveries       int     `json:"total_deliveries"`
		Rating                float64 `json:"rating"`
		IsOnline              bool    `json:"is_online"`
		PayoutReady           bool    `json:"payout_ready"`
	}

	var out []CourierAdminRow
	for rows.Next() {
		var c CourierAdminRow
		// Scan TIMESTAMPTZ into time.Time, not string. Scanning into *string
		// errors silently because pgx has no string codec for TIMESTAMPTZ.
		var createdAt time.Time
		if err := rows.Scan(&c.ID, &c.Email, &c.FirstName, &c.LastName, &c.Phone, &createdAt,
			&c.OnboardingStatus, &c.VehicleType, &c.VehicleMake, &c.VehicleModel,
			&c.LicensePlate, &c.DriversLicenseNumber, &c.BackgroundCheckStatus,
			&c.TotalDeliveries, &c.Rating, &c.IsOnline, &c.PayoutReady); err != nil {
			slog.Error("AdminListCouriers: scan error", slog.String("error", err.Error()))
			continue
		}
		c.CreatedAt = createdAt.Format(time.RFC3339)
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		slog.Error("AdminListCouriers: row iteration failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list couriers")
		return
	}
	if out == nil {
		out = []CourierAdminRow{}
	}
	writeJSON(w, http.StatusOK, out)
}

// AdminCourierDetail returns a single courier's full profile — vehicle info,
// all four document URLs, license number, background-check state, everything
// the admin dashboard needs to render the review modal and make an informed
// approve/reject call. List view omits these fields to keep payloads light.
func (h *Handler) AdminCourierDetail(w http.ResponseWriter, r *http.Request) {
	courierID := chi.URLParam(r, "id")

	type CourierDetailResponse struct {
		ID                     string  `json:"id"`
		Email                  string  `json:"email"`
		FirstName              string  `json:"first_name"`
		LastName               string  `json:"last_name"`
		Phone                  string  `json:"phone"`
		CreatedAt              string  `json:"created_at"`
		OnboardingStatus       string  `json:"onboarding_status"`
		PhoneVerified          bool    `json:"phone_verified"`
		VehicleType            string  `json:"vehicle_type"`
		VehicleMake            string  `json:"vehicle_make"`
		VehicleModel           string  `json:"vehicle_model"`
		VehicleYear            int     `json:"vehicle_year"`
		VehicleColor           string  `json:"vehicle_color"`
		LicensePlate           string  `json:"license_plate"`
		DriversLicenseURL      string  `json:"drivers_license_url"`
		DriversLicenseNumber   string  `json:"drivers_license_number"`
		InsuranceURL           string  `json:"insurance_url"`
		VehicleRegistrationURL string  `json:"vehicle_registration_url"`
		ProfilePhotoURL        string  `json:"profile_photo_url"`
		BackgroundCheckStatus  string  `json:"background_check_status"`
		BackgroundCheckRef     string  `json:"background_check_ref"`
		PayoutReady            bool    `json:"payout_ready"`
		IsOnline               bool    `json:"is_online"`
		TotalDeliveries        int     `json:"total_deliveries"`
		Rating                 float64 `json:"rating"`
	}

	var c CourierDetailResponse
	var createdAt time.Time
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.created_at,
		        cp.onboarding_status, cp.phone_verified, cp.vehicle_type, cp.vehicle_make,
		        cp.vehicle_model, cp.vehicle_year, cp.vehicle_color, cp.license_plate,
		        cp.drivers_license_url, cp.drivers_license_number, cp.insurance_url,
		        cp.vehicle_registration_url, cp.profile_photo_url,
		        cp.background_check_status, cp.background_check_ref, cp.payout_ready,
		        cp.is_online, cp.total_deliveries, cp.rating
		   FROM users u
		   JOIN courier_profiles cp ON cp.user_id = u.id
		  WHERE u.id = $1 AND u.role = 'courier'`, courierID,
	).Scan(&c.ID, &c.Email, &c.FirstName, &c.LastName, &c.Phone, &createdAt,
		&c.OnboardingStatus, &c.PhoneVerified, &c.VehicleType, &c.VehicleMake,
		&c.VehicleModel, &c.VehicleYear, &c.VehicleColor, &c.LicensePlate,
		&c.DriversLicenseURL, &c.DriversLicenseNumber, &c.InsuranceURL,
		&c.VehicleRegistrationURL, &c.ProfilePhotoURL,
		&c.BackgroundCheckStatus, &c.BackgroundCheckRef, &c.PayoutReady,
		&c.IsOnline, &c.TotalDeliveries, &c.Rating)
	if err != nil {
		writeError(w, http.StatusNotFound, "courier not found")
		return
	}
	c.CreatedAt = createdAt.Format(time.RFC3339)
	writeJSON(w, http.StatusOK, c)
}

// AdminApproveCourier flips a courier to onboarding_status=approved and
// marks their background check as passed. Used when the admin reviews a
// pending_background courier manually (e.g. when Checkr stub auto-passes
// in dev or when an edge case needs a human decision in prod).
func (h *Handler) AdminApproveCourier(w http.ResponseWriter, r *http.Request) {
	courierID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET onboarding_status = 'approved',
		       background_check_status = 'passed',
		       updated_at = NOW()
		 WHERE user_id = $1`, courierID)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "courier not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "approved"})
}

// AdminRejectCourier sets the courier to rejected state. The courier can
// still log in but cannot go online or claim deliveries.
func (h *Handler) AdminRejectCourier(w http.ResponseWriter, r *http.Request) {
	courierID := chi.URLParam(r, "id")
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET onboarding_status = 'rejected', updated_at = NOW()
		 WHERE user_id = $1`, courierID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to reject courier")
		return
	}
	if result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "courier not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "rejected"})
}

// AdminListOrders returns recent orders across the whole platform for the
// admin overview table. Paginated lightly (100 most recent) so the default
// view is fast; filters can be added later.
func (h *Handler) AdminListOrders(w http.ResponseWriter, r *http.Request) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
		        o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
		        o.courier_tip, o.delivery_address, o.created_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  ORDER BY o.created_at DESC LIMIT 100`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list orders")
		return
	}
	defer rows.Close()

	type OrderRow struct {
		ID              string `json:"id"`
		UserID          string `json:"user_id"`
		RestaurantID    string `json:"restaurant_id"`
		RestaurantName  string `json:"restaurant_name"`
		Status          string `json:"status"`
		Subtotal        int    `json:"subtotal"`
		DeliveryFee     int    `json:"delivery_fee"`
		ServiceFee      int    `json:"service_fee"`
		Tax             int    `json:"tax"`
		Total           int    `json:"total"`
		CourierTip      int    `json:"courier_tip"`
		DeliveryAddress string `json:"delivery_address"`
		CreatedAt       string `json:"created_at"`
	}

	var out []OrderRow
	for rows.Next() {
		var o OrderRow
		var createdAt time.Time
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.CourierTip, &o.DeliveryAddress, &createdAt); err != nil {
			slog.Error("AdminListOrders: scan error", slog.String("error", err.Error()))
			continue
		}
		o.CreatedAt = createdAt.Format(time.RFC3339)
		out = append(out, o)
	}
	if err := rows.Err(); err != nil {
		slog.Error("AdminListOrders: row iteration failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to list orders")
		return
	}
	if out == nil {
		out = []OrderRow{}
	}
	writeJSON(w, http.StatusOK, out)
}

// AdminStats rolls up platform-wide numbers for the admin dashboard hero.
func (h *Handler) AdminStats(w http.ResponseWriter, r *http.Request) {
	type Stats struct {
		TotalRestaurants int `json:"total_restaurants"`
		ActiveRestaurants int `json:"active_restaurants"`
		TotalCouriers    int `json:"total_couriers"`
		ApprovedCouriers int `json:"approved_couriers"`
		PendingCouriers  int `json:"pending_couriers"`
		TodayOrders      int `json:"today_orders"`
		TodayRevenue     int `json:"today_revenue"` // cents
		LifetimeOrders   int `json:"lifetime_orders"`
	}
	var s Stats
	if err := h.db.Pool.QueryRow(r.Context(), `
		SELECT
		  (SELECT COUNT(*) FROM restaurants),
		  (SELECT COUNT(*) FROM restaurants WHERE is_active = true),
		  (SELECT COUNT(*) FROM users WHERE role = 'courier'),
		  (SELECT COUNT(*) FROM courier_profiles WHERE onboarding_status = 'approved'),
		  (SELECT COUNT(*) FROM courier_profiles WHERE onboarding_status != 'approved' AND onboarding_status != 'rejected'),
		  (SELECT COUNT(*) FROM orders
		     WHERE (created_at AT TIME ZONE 'America/New_York')::date = (NOW() AT TIME ZONE 'America/New_York')::date
		       AND status NOT IN ('cancelled', 'rejected')),
		  (SELECT COALESCE(SUM(total), 0) FROM orders
		     WHERE (created_at AT TIME ZONE 'America/New_York')::date = (NOW() AT TIME ZONE 'America/New_York')::date
		       AND status NOT IN ('cancelled', 'rejected')),
		  (SELECT COUNT(*) FROM orders)
	`).Scan(&s.TotalRestaurants, &s.ActiveRestaurants, &s.TotalCouriers,
		&s.ApprovedCouriers, &s.PendingCouriers, &s.TodayOrders, &s.TodayRevenue, &s.LifetimeOrders); err != nil {
		slog.Error("admin stats query failed", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to load stats")
		return
	}

	writeJSON(w, http.StatusOK, s)
}

// AdminCreateSellerRequest is used by the admin to onboard a brand-new
// seller account. The admin sets a temporary password that the seller can
// reset on first login.
type AdminCreateSellerRequest struct {
	Email     string `json:"email"`
	Password  string `json:"password"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Phone     string `json:"phone"`
	Vertical  string `json:"vertical"`
}

// AdminCreateSeller creates a user with role=seller and returns the user id
// so the admin can then POST /admin/restaurants with it as owner_id.
func (h *Handler) AdminCreateSeller(w http.ResponseWriter, r *http.Request) {
	var req AdminCreateSellerRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	// Normalize email to lowercase to match Register / the lower(email) lookups
	// so case variants can't create duplicate seller accounts.
	req.Email = strings.ToLower(strings.TrimSpace(req.Email))
	if req.Email == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "email and password required")
		return
	}
	if len(req.Password) < 8 || len(req.Password) > 72 {
		writeError(w, http.StatusBadRequest, "password must be between 8 and 72 characters")
		return
	}

	// Reuse the Register path for consistency — same bcrypt cost, same JWT
	// generation, but forcing role=seller.
	hashed, err := bcryptHash(req.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	var id string
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, $2, $3, $4, $5, 'seller', COALESCE(NULLIF($6,''), 'kosher'))
		 RETURNING id`,
		req.Email, hashed, req.FirstName, req.LastName, req.Phone, req.Vertical,
	).Scan(&id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create seller")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": id})
}
