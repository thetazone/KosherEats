package handlers

import (
	"crypto/subtle"
	"errors"
	"log/slog"
	"net/http"

	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

// ReviewerSellerLogin hands the App Store review team a prebuilt seller
// account so they can exercise the dashboard without going through the real
// seller-approval flow (which requires a human operator on our side). The
// account is provisioned lazily — first call creates the user + a demo
// restaurant; later calls just issue fresh tokens for the same row — and
// keyed off a fixed email so repeated taps don't pile up duplicates.
//
// Temporary. Remove this handler and its route once we ship a self-serve
// demo path that reviewers can hit without a backdoor.
const (
	reviewerSellerEmail    = "appreview-seller@koshereats.local"
	reviewerRestaurantName = "App Review Demo Kitchen"
	// A real E.164 number is required: Uber Direct's CreateDelivery rejects a
	// dispatch with an empty pickup_phone_number ("This field is required"),
	// which would otherwise strand every delivery order from this restaurant in
	// 'ready' forever (the quote succeeds, the create 400s, the sweep loops).
	reviewerRestaurantPhone = "+17185550123"
)

func (h *Handler) ReviewerSellerLogin(w http.ResponseWriter, r *http.Request) {
	secret := h.cfg.ReviewerSecret
	provided := r.Header.Get("X-Reviewer-Secret")
	if secret == "" || subtle.ConstantTimeCompare([]byte(provided), []byte(secret)) != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	ctx := r.Context()

	var user models.User
	err := h.db.Pool.QueryRow(ctx,
		`SELECT id, email, first_name, last_name, phone, role, created_at, updated_at
		   FROM users WHERE email = $1`, reviewerSellerEmail,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName,
		&user.Phone, &user.Role, &user.CreatedAt, &user.UpdatedAt)

	switch {
	case errors.Is(err, pgx.ErrNoRows):
		// users.password_hash is NOT NULL; the reviewer never logs in via
		// password so a synthetic bcrypt is fine.
		hash, hashErr := bcrypt.GenerateFromPassword([]byte("reviewer-"+reviewerSellerEmail), bcrypt.DefaultCost)
		if hashErr != nil {
			writeError(w, http.StatusInternalServerError, "failed to prepare reviewer account")
			return
		}
		err = h.db.Pool.QueryRow(ctx,
			`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, auth_provider)
			 VALUES ($1, $2, 'App Store', 'Reviewer', '', $3, 'reviewer')
			 RETURNING id, email, first_name, last_name, phone, role, created_at, updated_at`,
			reviewerSellerEmail, string(hash), models.RoleSeller,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName,
			&user.Phone, &user.Role, &user.CreatedAt, &user.UpdatedAt)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create reviewer account")
			return
		}
	case err != nil:
		writeError(w, http.StatusInternalServerError, "reviewer lookup failed")
		return
	default:
		if user.Role != models.RoleSeller {
			writeError(w, http.StatusForbidden, "reviewer account disabled")
			return
		}
	}

	// Seed a demo restaurant the first time through so the dashboard has
	// content. SellerMiddleware doesn't require a restaurant to pass, but
	// GetSellerRestaurant would otherwise 404 on first launch.
	if _, err := h.db.Pool.Exec(ctx,
		`INSERT INTO restaurants (
		     owner_id, name, description, phone,
		     street, city, state, zip_code, lat, lng,
		     kosher_certification, is_open, is_active
		 )
		 SELECT $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'OU', true, true
		 WHERE NOT EXISTS (SELECT 1 FROM restaurants WHERE owner_id = $1)`,
		user.ID, reviewerRestaurantName,
		"Preloaded demo restaurant for the App Store review team.",
		reviewerRestaurantPhone,
		"123 Demo Ave", "Brooklyn", "NY", "11201",
		40.6892, -74.0445,
	); err != nil {
		slog.Warn("failed to seed reviewer demo restaurant",
			slog.String("user_id", user.ID), slog.String("error", err.Error()))
	}

	// Reviewer accounts are always on the original (kosher) vertical.
	token, refresh, err := h.generateTokens(user.ID, string(user.Role), "kosher")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}
	writeJSON(w, http.StatusOK, AuthResponse{
		Token:        token,
		RefreshToken: refresh,
		User:         user,
	})
}
