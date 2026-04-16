package handlers

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

type contextKey string

const userContextKey contextKey = "user"

type RegisterRequest struct {
	Email     string `json:"email"`
	Password  string `json:"password"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Phone     string `json:"phone"`
}

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type AuthResponse struct {
	Token        string      `json:"token"`
	RefreshToken string      `json:"refresh_token"`
	User         models.User `json:"user"`
}

func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req RegisterRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Email == "" || req.Password == "" || req.FirstName == "" {
		writeError(w, http.StatusBadRequest, "email, password, and first_name are required")
		return
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	var user models.User
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role)
		 VALUES ($1, $2, $3, $4, $5, $6)
		 RETURNING id, email, first_name, last_name, phone, role, created_at, updated_at`,
		req.Email, string(hashedPassword), req.FirstName, req.LastName, req.Phone, models.RoleConsumer,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone, &user.Role, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			writeError(w, http.StatusConflict, "email already registered")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to create user")
		return
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role))
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

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req LoginRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var user models.User
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, email, password_hash, first_name, last_name, phone, role, created_at, updated_at
		 FROM users WHERE email = $1`,
		req.Email,
	).Scan(&user.ID, &user.Email, &user.PasswordHash, &user.FirstName, &user.LastName,
		&user.Phone, &user.Role, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role))
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
		return []byte(h.cfg.JWTSecret), nil
	})

	if err != nil || !token.Valid {
		writeError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}

	userID := (*claims)["sub"].(string)
	role := (*claims)["role"].(string)

	newToken, newRefresh, err := h.generateTokens(userID, role)
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
			return []byte(h.cfg.JWTSecret), nil
		})

		if err != nil || !token.Valid {
			writeError(w, http.StatusUnauthorized, "invalid token")
			return
		}

		userID := (*claims)["sub"].(string)
		role := (*claims)["role"].(string)

		ctx := context.WithValue(r.Context(), userContextKey, map[string]string{
			"user_id": userID,
			"role":    role,
		})
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (h *Handler) SellerMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userData := r.Context().Value(userContextKey).(map[string]string)
		if userData["role"] != string(models.RoleSeller) && userData["role"] != string(models.RoleAdmin) {
			writeError(w, http.StatusForbidden, "seller access required")
			return
		}
		next.ServeHTTP(w, r.WithContext(r.Context()))
	})
}

func getUserFromContext(r *http.Request) map[string]string {
	return r.Context().Value(userContextKey).(map[string]string)
}

func (h *Handler) generateTokens(userID, role string) (string, string, error) {
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":  userID,
		"role": role,
		"exp":  time.Now().Add(15 * time.Minute).Unix(),
		"iat":  time.Now().Unix(),
	})

	tokenString, err := token.SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", err
	}

	refresh := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":  userID,
		"role": role,
		"exp":  time.Now().Add(7 * 24 * time.Hour).Unix(),
		"iat":  time.Now().Unix(),
	})

	refreshString, err := refresh.SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", err
	}

	return tokenString, refreshString, nil
}
