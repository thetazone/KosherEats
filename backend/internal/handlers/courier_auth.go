package handlers

import (
	"log/slog"
	"net/http"
	"strings"

	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

// Courier signup is a separate flow from consumer/seller registration.
// It creates a user with role=courier AND a courier_profile row in
// onboarding_status = 'pending_info'. The courier cannot claim deliveries
// until they reach onboarding_status = 'approved'.

type CourierRegisterRequest struct {
	Email     string `json:"email"`
	Password  string `json:"password"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Phone     string `json:"phone"`
}

func (h *Handler) CourierRegister(w http.ResponseWriter, r *http.Request) {
	var req CourierRegisterRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Email == "" || req.Password == "" || req.FirstName == "" || req.Phone == "" {
		writeError(w, http.StatusBadRequest, "email, password, first_name, and phone are required")
		return
	}

	hashed, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin tx")
		return
	}
	defer tx.Rollback(r.Context())

	var user models.User
	err = tx.QueryRow(r.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role)
		 VALUES ($1, $2, $3, $4, $5, 'courier')
		 RETURNING id, email, first_name, last_name, phone, role, created_at, updated_at`,
		req.Email, string(hashed), req.FirstName, req.LastName, req.Phone,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone, &user.Role, &user.CreatedAt, &user.UpdatedAt)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			writeError(w, http.StatusConflict, "email already registered")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to create user")
		return
	}

	_, err = tx.Exec(r.Context(),
		`INSERT INTO courier_profiles (user_id, onboarding_status)
		 VALUES ($1, 'pending_info')`, user.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create courier profile")
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit")
		return
	}

	token, refresh, err := h.generateTokens(user.ID, string(user.Role))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate tokens")
		return
	}

	writeJSON(w, http.StatusCreated, AuthResponse{
		Token:        token,
		RefreshToken: refresh,
		User:         user,
	})
}

// CourierMiddleware enforces role=courier on /api/v1/courier/* routes.
// It does NOT enforce onboarding_status; gating on approval happens inside
// the individual endpoints (e.g. GoOnline and ClaimOrder require approved).
func (h *Handler) CourierMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userData, ok := r.Context().Value(userContextKey).(map[string]string)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if userData["role"] != string(models.RoleCourier) && userData["role"] != string(models.RoleAdmin) {
			writeError(w, http.StatusForbidden, "courier access required")
			return
		}
		next.ServeHTTP(w, r.WithContext(r.Context()))
	})
}

// GetCourierProfile returns the full profile for the authenticated courier,
// including onboarding state so the iOS app knows which step to show.
func (h *Handler) GetCourierProfile(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	p, err := h.loadCourierProfile(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "courier profile not found")
		return
	}
	writeJSON(w, http.StatusOK, p)
}

type UpdateCourierVehicleRequest struct {
	VehicleType  models.VehicleType `json:"vehicle_type"`
	VehicleMake  string             `json:"vehicle_make"`
	VehicleModel string             `json:"vehicle_model"`
	VehicleYear  int                `json:"vehicle_year"`
	VehicleColor string             `json:"vehicle_color"`
	LicensePlate string             `json:"license_plate"`
}

// Step 1 of onboarding: vehicle info. Advances pending_info -> pending_documents.
func (h *Handler) UpdateCourierVehicle(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req UpdateCourierVehicleRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.VehicleType == "" {
		writeError(w, http.StatusBadRequest, "vehicle_type is required")
		return
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET vehicle_type = $1, vehicle_make = $2, vehicle_model = $3,
		       vehicle_year = $4, vehicle_color = $5, license_plate = $6,
		       onboarding_status = CASE
		           WHEN onboarding_status = 'pending_info' THEN 'pending_documents'
		           ELSE onboarding_status
		       END,
		       updated_at = NOW()
		 WHERE user_id = $7`,
		req.VehicleType, req.VehicleMake, req.VehicleModel,
		req.VehicleYear, req.VehicleColor, req.LicensePlate, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update vehicle")
		return
	}

	p, _ := h.loadCourierProfile(r, user["user_id"])
	writeJSON(w, http.StatusOK, p)
}

type UpdateCourierDocumentsRequest struct {
	DriversLicenseURL      string `json:"drivers_license_url"`
	DriversLicenseNumber   string `json:"drivers_license_number"`
	InsuranceURL           string `json:"insurance_url"`
	VehicleRegistrationURL string `json:"vehicle_registration_url"`
	ProfilePhotoURL        string `json:"profile_photo_url"`
}

// Step 2 of onboarding: document uploads. Advances pending_documents -> pending_background
// and kicks off a (stubbed) background check that auto-approves in dev.
func (h *Handler) UpdateCourierDocuments(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req UpdateCourierDocumentsRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	// We always require the ID *photo* (driver's license for car/motorcycle
	// couriers, government ID for bike/scooter/walk couriers — same DB column,
	// different UI label). The driver's license *number* is requested only for
	// vehicle types that legally need one and may be empty for ID-only flows.
	if req.DriversLicenseURL == "" {
		writeError(w, http.StatusBadRequest, "ID photo is required")
		return
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET drivers_license_url = $1, drivers_license_number = $2,
		       insurance_url = $3, vehicle_registration_url = $4, profile_photo_url = $5,
		       onboarding_status = 'pending_background',
		       background_check_status = 'in_progress',
		       updated_at = NOW()
		 WHERE user_id = $6 AND onboarding_status IN ('pending_documents', 'pending_background')`,
		req.DriversLicenseURL, req.DriversLicenseNumber, req.InsuranceURL,
		req.VehicleRegistrationURL, req.ProfilePhotoURL, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update documents")
		return
	}

	// Kick off the real background check via Checkr (or the dev stub that
	// auto-approves after 2s). Returns immediately so the courier sees the
	// "background check running" screen; the flip to approved happens
	// asynchronously.
	var email, firstName, lastName, phone string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT email, first_name, last_name, phone FROM users WHERE id = $1`,
		user["user_id"],
	).Scan(&email, &firstName, &lastName, &phone); err != nil {
		slog.Error("UpdateCourierDocuments: failed to fetch user info for background check",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
	}
	if err := h.checkr.InitiateCheck(r.Context(), user["user_id"], email, firstName, lastName, phone); err != nil {
		slog.Error("UpdateCourierDocuments: background check initiation failed",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
	}

	p, _ := h.loadCourierProfile(r, user["user_id"])
	writeJSON(w, http.StatusOK, p)
}

// VerifyCourierPhone stub: in prod this would check an SMS code. Dev auto-passes.
func (h *Handler) VerifyCourierPhone(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles SET phone_verified = true, updated_at = NOW() WHERE user_id = $1`,
		user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to verify phone")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"phone_verified": true})
}

// loadCourierProfile fetches a single courier's profile by user_id.
// Used by onboarding handlers and the profile GET.
func (h *Handler) loadCourierProfile(r *http.Request, userID string) (*models.CourierProfile, error) {
	var p models.CourierProfile
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, user_id, onboarding_status, phone_verified, vehicle_type,
		        vehicle_make, vehicle_model, vehicle_year, vehicle_color, license_plate,
		        drivers_license_url, drivers_license_number, insurance_url,
		        vehicle_registration_url, profile_photo_url, background_check_status,
		        payout_ready, is_online, last_lat, last_lng, last_location_at,
		        total_deliveries, rating, created_at, updated_at
		   FROM courier_profiles WHERE user_id = $1`, userID,
	).Scan(&p.ID, &p.UserID, &p.OnboardingStatus, &p.PhoneVerified, &p.VehicleType,
		&p.VehicleMake, &p.VehicleModel, &p.VehicleYear, &p.VehicleColor, &p.LicensePlate,
		&p.DriversLicenseURL, &p.DriversLicenseNumber, &p.InsuranceURL,
		&p.VehicleRegistrationURL, &p.ProfilePhotoURL, &p.BackgroundCheckStatus,
		&p.PayoutReady, &p.IsOnline, &p.LastLat, &p.LastLng, &p.LastLocationAt,
		&p.TotalDeliveries, &p.Rating, &p.CreatedAt, &p.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &p, nil
}
