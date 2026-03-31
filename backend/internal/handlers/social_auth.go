package handlers

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

type SocialLoginRequest struct {
	Provider    string          `json:"provider"`    // "google", "apple", "facebook"
	Token       string          `json:"token"`       // ID token or access token from provider
	FirstName   string          `json:"first_name"`  // optional, from provider
	LastName    string          `json:"last_name"`   // optional, from provider
	Role        models.UserRole `json:"role"`        // consumer or seller
}

type googleTokenInfo struct {
	Email         string `json:"email"`
	EmailVerified string `json:"email_verified"`
	Name          string `json:"name"`
	GivenName     string `json:"given_name"`
	FamilyName    string `json:"family_name"`
	Picture       string `json:"picture"`
	Sub           string `json:"sub"`
}

type facebookUser struct {
	ID        string `json:"id"`
	Email     string `json:"email"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Picture   struct {
		Data struct {
			URL string `json:"url"`
		} `json:"data"`
	} `json:"picture"`
}

type appleTokenClaims struct {
	Email string `json:"email"`
	Sub   string `json:"sub"`
}

func (h *Handler) SocialLogin(w http.ResponseWriter, r *http.Request) {
	var req SocialLoginRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Token == "" || req.Provider == "" {
		writeError(w, http.StatusBadRequest, "provider and token are required")
		return
	}

	if req.Role == "" {
		req.Role = models.RoleConsumer
	}

	var email, firstName, lastName, avatarURL, providerID string
	var err error

	switch req.Provider {
	case "google":
		email, firstName, lastName, avatarURL, providerID, err = h.verifyGoogleToken(req.Token)
	case "apple":
		email, firstName, lastName, providerID, err = h.verifyAppleToken(req.Token, req.FirstName, req.LastName)
		avatarURL = ""
	case "facebook":
		email, firstName, lastName, avatarURL, providerID, err = h.verifyFacebookToken(req.Token)
	default:
		writeError(w, http.StatusBadRequest, "unsupported provider: "+req.Provider)
		return
	}

	if err != nil {
		writeError(w, http.StatusUnauthorized, "failed to verify token: "+err.Error())
		return
	}

	if email == "" {
		writeError(w, http.StatusBadRequest, "email not available from provider")
		return
	}

	// Check if user exists by email
	var user models.User
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT id, email, first_name, last_name, phone, role, avatar_url, created_at, updated_at
		 FROM users WHERE email = $1`, email,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone,
		&user.Role, &user.AvatarURL, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		// User doesn't exist — create new account
		dummyHash, _ := bcrypt.GenerateFromPassword([]byte("oauth-"+providerID), bcrypt.DefaultCost)

		err = h.db.Pool.QueryRow(r.Context(),
			`INSERT INTO users (email, password_hash, first_name, last_name, role, avatar_url, auth_provider, auth_provider_id)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
			 RETURNING id, email, first_name, last_name, phone, role, avatar_url, created_at, updated_at`,
			email, string(dummyHash), firstName, lastName, req.Role, avatarURL, req.Provider, providerID,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone,
			&user.Role, &user.AvatarURL, &user.CreatedAt, &user.UpdatedAt)

		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create user")
			return
		}
	} else {
		// Update avatar and provider info if needed
		h.db.Pool.Exec(r.Context(),
			`UPDATE users SET avatar_url = COALESCE(NULLIF($1, ''), avatar_url),
			 auth_provider = $2, auth_provider_id = $3, updated_at = NOW()
			 WHERE id = $4`,
			avatarURL, req.Provider, providerID, user.ID)
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate tokens")
		return
	}

	writeJSON(w, http.StatusOK, AuthResponse{
		Token:        token,
		RefreshToken: refreshToken,
		User:         user,
	})
}

func (h *Handler) verifyGoogleToken(idToken string) (email, firstName, lastName, avatarURL, providerID string, err error) {
	resp, err := http.Get("https://oauth2.googleapis.com/tokeninfo?id_token=" + url.QueryEscape(idToken))
	if err != nil {
		return "", "", "", "", "", fmt.Errorf("failed to verify google token")
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", "", "", "", "", fmt.Errorf("invalid google token")
	}

	var info googleTokenInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return "", "", "", "", "", fmt.Errorf("failed to decode google response")
	}

	return info.Email, info.GivenName, info.FamilyName, info.Picture, info.Sub, nil
}

func (h *Handler) verifyAppleToken(idToken string, firstName, lastName string) (email, fName, lName, providerID string, err error) {
	// Decode the JWT payload (Apple ID tokens are JWTs)
	parts := strings.Split(idToken, ".")
	if len(parts) != 3 {
		return "", "", "", "", fmt.Errorf("invalid apple token format")
	}

	// Decode payload
	payload := parts[1]
	// Add padding if needed
	switch len(payload) % 4 {
	case 2:
		payload += "=="
	case 3:
		payload += "="
	}

	decoded, err := io.ReadAll(strings.NewReader(payload))
	if err != nil {
		return "", "", "", "", fmt.Errorf("failed to decode apple token")
	}

	var claims appleTokenClaims
	// In production: verify signature against Apple's public keys at https://appleid.apple.com/auth/keys
	if err := json.Unmarshal(decoded, &claims); err != nil {
		return "", "", "", "", fmt.Errorf("failed to parse apple token claims")
	}

	// Apple only sends name on first login — use what the client provides
	if firstName == "" {
		firstName = "Apple"
	}
	if lastName == "" {
		lastName = "User"
	}

	return claims.Email, firstName, lastName, claims.Sub, nil
}

func (h *Handler) verifyFacebookToken(accessToken string) (email, firstName, lastName, avatarURL, providerID string, err error) {
	resp, err := http.Get("https://graph.facebook.com/me?fields=id,email,first_name,last_name,picture.type(large)&access_token=" + url.QueryEscape(accessToken))
	if err != nil {
		return "", "", "", "", "", fmt.Errorf("failed to verify facebook token")
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", "", "", "", "", fmt.Errorf("invalid facebook token")
	}

	var fbUser facebookUser
	if err := json.NewDecoder(resp.Body).Decode(&fbUser); err != nil {
		return "", "", "", "", "", fmt.Errorf("failed to decode facebook response")
	}

	return fbUser.Email, fbUser.FirstName, fbUser.LastName, fbUser.Picture.Data.URL, fbUser.ID, nil
}
