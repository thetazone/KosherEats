package handlers

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/ctxkeys"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

const userContextKey = ctxkeys.UserKey

type RegisterRequest struct {
	Email     string          `json:"email"`
	Password  string          `json:"password"`
	FirstName string          `json:"first_name"`
	LastName  string          `json:"last_name"`
	Phone     string          `json:"phone"`
	// Role is the role being signed up for. Empty defaults to consumer so
	// older consumer-app builds without the role field keep working.
	Role models.UserRole `json:"role,omitempty"`
	// Vertical is the branded app the request originated from. Empty
	// defaults to 'kosher' so older clients without the field keep working.
	Vertical string `json:"vertical,omitempty"`
}

type LoginRequest struct {
	Email    string          `json:"email"`
	Password string          `json:"password"`
	// Role scopes the lookup since (email, role) is now the unique key.
	// Empty defaults to consumer for backward compatibility.
	Role models.UserRole `json:"role,omitempty"`
	// Vertical scopes the account to a branded app. Empty defaults to 'kosher'.
	Vertical string `json:"vertical,omitempty"`
}

// normalizeVertical returns 'kosher' (the default) when input is empty or
// unrecognized. Keeps older clients working and shields us from typos.
func normalizeVertical(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "vegan":
		return "vegan"
	default:
		return "kosher"
	}
}

// verticalFromRequest resolves which vertical (kosher/vegan) a request belongs
// to. Resolution order:
//
//  1. JWT claim (if request is authenticated)
//  2. `?vertical=` query string (anonymous browsing)
//  3. 'kosher' (default — keeps original KosherEats clients working)
//
// Public restaurant / deals endpoints call this to scope catalog queries so
// KosherEats clients never see vegan listings and vice versa.
func verticalFromRequest(r *http.Request) string {
	if user, err := getUserFromContext(r); err == nil {
		if v := user["vertical"]; v != "" {
			return normalizeVertical(v)
		}
	}
	return normalizeVertical(r.URL.Query().Get("vertical"))
}

type AuthResponse struct {
	Token        string      `json:"token"`
	RefreshToken string      `json:"refresh_token"`
	User         models.User `json:"user"`
}

// allowedSignupRole clamps a self-service signup role to the set a user may
// create for themselves. SECURITY: admin accounts must NEVER be creatable
// through any public signup path (Register / SocialLogin / phone OTP) — they're
// seeded or created by an existing admin. AdminMiddleware authorizes purely on
// the JWT role claim, so an attacker who could self-assign role=admin here would
// mint an admin token and own the whole /admin surface. Returns (role, ok);
// ok=false means the requested value is not self-serviceable → reject with 400.
func allowedSignupRole(role models.UserRole) (models.UserRole, bool) {
	switch role {
	case "":
		return models.RoleConsumer, true
	case models.RoleConsumer, models.RoleSeller, models.RoleCourier:
		return role, true
	default:
		return "", false
	}
}

func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req RegisterRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	req.Email = strings.TrimSpace(strings.ToLower(req.Email))

	if req.Email == "" || req.Password == "" || req.FirstName == "" {
		writeError(w, http.StatusBadRequest, "email, password, and first_name are required")
		return
	}
	if len(req.Password) < 8 || len(req.Password) > 72 {
		writeError(w, http.StatusBadRequest, "password must be between 8 and 72 characters")
		return
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	role, ok := allowedSignupRole(req.Role)
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid role")
		return
	}
	vertical := normalizeVertical(req.Vertical)

	var user models.User
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)
		 RETURNING id, email, first_name, last_name, phone, role, vertical, created_at, updated_at`,
		req.Email, string(hashedPassword), req.FirstName, req.LastName, req.Phone, role, vertical,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone, &user.Role, &user.Vertical, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			writeError(w, http.StatusConflict, "an account with this email already exists for this app")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to create user")
		return
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role), user.Vertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate tokens")
		return
	}

	writeJSON(w, http.StatusCreated, AuthResponse{
		Token:        token,
		RefreshToken: refreshToken,
		User:         user,
	})
}

// CheckEmail tells the unified email-entry UI whether an account already
// exists for this (email, role) pair — used to route the user to "sign in"
// vs "create account" without forcing a dummy login attempt first.
//
// Scoped by role so the seller app's "do I have a seller account?" check
// doesn't get false positives from a consumer-side account that happens to
// share the email.
func (h *Handler) CheckEmail(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string          `json:"email"`
		Role     models.UserRole `json:"role,omitempty"`
		Vertical string          `json:"vertical,omitempty"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	email := strings.TrimSpace(strings.ToLower(req.Email))
	if email == "" || !strings.Contains(email, "@") {
		writeError(w, http.StatusBadRequest, "email is required")
		return
	}

	role := req.Role
	if role == "" {
		role = models.RoleConsumer
	}
	vertical := normalizeVertical(req.Vertical)

	var found string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT role FROM users WHERE email = $1 AND role = $2 AND vertical = $3`, email, role, vertical,
	).Scan(&found)

	if err != nil {
		// Any error — including pgx.ErrNoRows — is treated as "doesn't
		// exist". Callers should branch on `exists` rather than the role.
		writeJSON(w, http.StatusOK, map[string]any{
			"exists": false,
			"role":   "",
		})
		return
	}

	// Don't expose the user's role. Note the boolean `exists` itself is an
	// account-enumeration signal that /login does not provide (/login returns an
	// identical 401 for unknown-email and wrong-password); the role would only
	// make it worse. This endpoint is guarded by its own dedicated, stricter
	// per-IP limiter (emailCheckLimiter in cmd/api/main.go), not the shared
	// authLimiter, so enumeration can't ride the looser /login burst budget.
	writeJSON(w, http.StatusOK, map[string]any{
		"exists": true,
		"role":   "",
	})
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req LoginRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	req.Email = strings.TrimSpace(strings.ToLower(req.Email))

	role := req.Role
	if role == "" {
		role = models.RoleConsumer
	}
	vertical := normalizeVertical(req.Vertical)

	var user models.User
	err := h.db.Pool.QueryRow(r.Context(),
		// SECURITY: only real password accounts (auth_provider NULL/'email') may
		// authenticate here. Phone-OTP and OAuth accounts are provisioned with a
		// synthetic password and MUST NOT be reachable via /login — their
		// synthetic secret was historically derivable from public data
		// (phone-OTP: "phone-"+phone), so matching them here was a deterministic
		// account-takeover bypass of the OTP/OAuth flow. They sign in through
		// their own provider path instead.
		`SELECT id, email, password_hash, first_name, last_name, phone, role, vertical, created_at, updated_at
		 FROM users WHERE email = $1 AND role = $2 AND vertical = $3
		   AND (auth_provider IS NULL OR auth_provider = 'email')`,
		req.Email, role, vertical,
	).Scan(&user.ID, &user.Email, &user.PasswordHash, &user.FirstName, &user.LastName,
		&user.Phone, &user.Role, &user.Vertical, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role), user.Vertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate tokens")
		return
	}

	user.PasswordHash = ""

	writeJSON(w, http.StatusOK, AuthResponse{
		Token:        token,
		RefreshToken: refreshToken,
		User:         user,
	})
}

func (h *Handler) RefreshToken(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	claims := &jwt.MapClaims{}
	token, err := jwt.ParseWithClaims(req.RefreshToken, claims, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected alg: %v", token.Header["alg"])
		}
		return []byte(h.cfg.JWTSecret), nil
	})

	if err != nil || !token.Valid {
		writeError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}

	if typ, _ := (*claims)["typ"].(string); typ != "refresh" {
		writeError(w, http.StatusUnauthorized, "invalid refresh token: wrong token type")
		return
	}

	userID, ok := (*claims)["sub"].(string)
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid refresh token: missing sub claim")
		return
	}

	// Re-fetch the current role + vertical from DB so demotions/bans take
	// effect immediately on the next refresh, rather than living in the
	// token claim until it expires.
	var currentRole, currentVertical string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT role, vertical FROM users WHERE id = $1`, userID,
	).Scan(&currentRole, &currentVertical); err != nil {
		writeError(w, http.StatusUnauthorized, "user not found")
		return
	}

	newToken, newRefresh, err := h.generateTokens(userID, currentRole, currentVertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate tokens")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"token":         newToken,
		"refresh_token": newRefresh,
	})
}

func (h *Handler) AuthMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			writeError(w, http.StatusUnauthorized, "missing authorization header")
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			writeError(w, http.StatusUnauthorized, "invalid authorization header")
			return
		}

		claims := &jwt.MapClaims{}
		token, err := jwt.ParseWithClaims(parts[1], claims, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected alg: %v", token.Header["alg"])
			}
			return []byte(h.cfg.JWTSecret), nil
		})

		if err != nil || !token.Valid {
			writeError(w, http.StatusUnauthorized, "invalid token")
			return
		}

		if typ, _ := (*claims)["typ"].(string); typ == "refresh" {
			writeError(w, http.StatusUnauthorized, "cannot use refresh token as access token")
			return
		}

		userID, ok := (*claims)["sub"].(string)
		if !ok {
			writeError(w, http.StatusUnauthorized, "invalid token: missing sub claim")
			return
		}
		role, ok := (*claims)["role"].(string)
		if !ok {
			writeError(w, http.StatusUnauthorized, "invalid token: missing role claim")
			return
		}
		// Vertical may be absent on legacy tokens issued before multi-tenancy.
		// Fall back to 'kosher' so existing sessions keep working.
		vertical, _ := (*claims)["vertical"].(string)
		if vertical == "" {
			vertical = "kosher"
		}

		ctx := context.WithValue(r.Context(), userContextKey, map[string]string{
			"user_id":  userID,
			"role":     role,
			"vertical": vertical,
		})
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// OptionalAuthMiddleware is like AuthMiddleware but does NOT reject
// unauthenticated requests. If a valid Bearer token is present it populates
// the user context; otherwise the request proceeds with no user context.
// Handlers behind this middleware call getUserFromContext and treat errors as
// "guest / logged-out" rather than aborting.
func (h *Handler) OptionalAuthMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			next.ServeHTTP(w, r)
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			next.ServeHTTP(w, r)
			return
		}

		claims := &jwt.MapClaims{}
		token, err := jwt.ParseWithClaims(parts[1], claims, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected alg: %v", token.Header["alg"])
			}
			return []byte(h.cfg.JWTSecret), nil
		})
		if err != nil || !token.Valid {
			next.ServeHTTP(w, r)
			return
		}
		if typ, _ := (*claims)["typ"].(string); typ == "refresh" {
			next.ServeHTTP(w, r)
			return
		}

		userID, ok := (*claims)["sub"].(string)
		if !ok {
			next.ServeHTTP(w, r)
			return
		}
		role, _ := (*claims)["role"].(string)
		vertical, _ := (*claims)["vertical"].(string)
		if vertical == "" {
			vertical = "kosher"
		}

		ctx := context.WithValue(r.Context(), userContextKey, map[string]string{
			"user_id":  userID,
			"role":     role,
			"vertical": vertical,
		})
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (h *Handler) SellerMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userData, ok := r.Context().Value(userContextKey).(map[string]string)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if userData["role"] != string(models.RoleSeller) && userData["role"] != string(models.RoleAdmin) {
			writeError(w, http.StatusForbidden, "seller access required")
			return
		}
		next.ServeHTTP(w, r.WithContext(r.Context()))
	})
}

func getUserFromContext(r *http.Request) (map[string]string, error) {
	userData, ok := r.Context().Value(userContextKey).(map[string]string)
	if !ok || userData["user_id"] == "" {
		return nil, errors.New("unauthorized")
	}
	return userData, nil
}

func (h *Handler) generateTokens(userID, role, vertical string) (string, string, error) {
	if vertical == "" {
		vertical = "kosher"
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":      userID,
		"role":     role,
		"vertical": vertical,
		"exp":      time.Now().Add(15 * time.Minute).Unix(),
		"iat":      time.Now().Unix(),
	})

	tokenString, err := token.SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", err
	}

	refresh := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":      userID,
		"role":     role,
		"vertical": vertical,
		"typ":      "refresh",
		"exp":      time.Now().Add(7 * 24 * time.Hour).Unix(),
		"iat":      time.Now().Unix(),
	})

	refreshString, err := refresh.SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", err
	}

	return tokenString, refreshString, nil
}
