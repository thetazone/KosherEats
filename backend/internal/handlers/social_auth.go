package handlers

import (
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
	"golang.org/x/sync/singleflight"
)

type SocialLoginRequest struct {
	Provider  string `json:"provider"`   // "google", "apple"
	Token     string `json:"token"`      // ID token from provider
	FirstName string `json:"first_name"` // optional, from provider
	LastName  string `json:"last_name"`  // optional, from provider
}

type googleTokenInfo struct {
	Email         string `json:"email"`
	EmailVerified string `json:"email_verified"`
	Name          string `json:"name"`
	GivenName     string `json:"given_name"`
	FamilyName    string `json:"family_name"`
	Picture       string `json:"picture"`
	Sub           string `json:"sub"`
	Aud           string `json:"aud"`
}

type appleTokenClaims struct {
	Email         string          `json:"email"`
	EmailVerified json.RawMessage `json:"email_verified"`
	jwt.RegisteredClaims
}

const (
	appleJWKCacheTTL         = time.Hour
	appleJWKFetchTimeout     = 5 * time.Second
	appleTokenIssuedAtLeeway = 5 * time.Minute
)

var appleJWKs = newAppleJWKCache()

var (
	googleTokenInfoURL = "https://oauth2.googleapis.com/tokeninfo"
	googleHTTPClient   = &http.Client{Timeout: 5 * time.Second}
)

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

	var email, firstName, lastName, avatarURL, providerID string
	var err error

	switch req.Provider {
	case "google":
		email, firstName, lastName, avatarURL, providerID, err = h.verifyGoogleToken(req.Token)
	case "apple":
		email, firstName, lastName, providerID, err = h.verifyAppleToken(req.Token, req.FirstName, req.LastName)
		avatarURL = ""
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
			email, string(dummyHash), firstName, lastName, models.RoleConsumer, avatarURL, req.Provider, providerID,
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
	if h.cfg == nil || h.cfg.GoogleClientID == "" {
		return "", "", "", "", "", fmt.Errorf("google sign-in is not configured")
	}

	resp, err := googleHTTPClient.Get(googleTokenInfoURL + "?id_token=" + url.QueryEscape(idToken))
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

	audienceMatched := false
	for _, audience := range strings.Split(h.cfg.GoogleClientID, ",") {
		if info.Aud == strings.TrimSpace(audience) {
			audienceMatched = true
			break
		}
	}
	if !audienceMatched {
		return "", "", "", "", "", fmt.Errorf("invalid google token")
	}

	if info.EmailVerified != "true" || info.Email == "" {
		return "", "", "", "", "", fmt.Errorf("invalid google token")
	}

	return info.Email, info.GivenName, info.FamilyName, info.Picture, info.Sub, nil
}

func (h *Handler) verifyAppleToken(idToken string, firstName, lastName string) (email, fName, lName, providerID string, err error) {
	if h.cfg == nil || h.cfg.AppleClientID == "" {
		return "", "", "", "", fmt.Errorf("apple sign-in is not configured")
	}

	var claims appleTokenClaims
	_, err = jwt.ParseWithClaims(
		idToken,
		&claims,
		appleJWKs.resolveKey,
		jwt.WithValidMethods([]string{jwt.SigningMethodRS256.Alg()}),
		jwt.WithIssuer("https://appleid.apple.com"),
		jwt.WithAudience(h.cfg.AppleClientID),
		jwt.WithExpirationRequired(),
	)
	if err != nil {
		return "", "", "", "", fmt.Errorf("invalid apple token")
	}

	if claims.Subject == "" {
		return "", "", "", "", fmt.Errorf("apple token missing subject")
	}

	if claims.Email == "" {
		return "", "", "", "", fmt.Errorf("apple token missing verified email")
	}

	if !claims.emailVerified() {
		return "", "", "", "", fmt.Errorf("apple email is not verified")
	}

	if claims.IssuedAt != nil && claims.IssuedAt.Time.After(time.Now().Add(appleTokenIssuedAtLeeway)) {
		return "", "", "", "", fmt.Errorf("apple token issued-at is invalid")
	}

	// Apple only sends name on first login — use what the client provides
	if firstName == "" {
		firstName = "Apple"
	}
	if lastName == "" {
		lastName = "User"
	}

	return claims.Email, firstName, lastName, claims.Subject, nil
}

func (c appleTokenClaims) emailVerified() bool {
	var verifiedBool bool
	if err := json.Unmarshal(c.EmailVerified, &verifiedBool); err == nil {
		return verifiedBool
	}

	var verifiedString string
	if err := json.Unmarshal(c.EmailVerified, &verifiedString); err == nil {
		return strings.EqualFold(verifiedString, "true")
	}

	return false
}

type appleJWKResponse struct {
	Keys []appleJWK `json:"keys"`
}

type appleJWK struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"`
	Alg string `json:"alg"`
	Use string `json:"use"`
	N   string `json:"n"`
	E   string `json:"e"`
}

type appleJWKCache struct {
	mu        sync.RWMutex
	keys      map[string]*rsa.PublicKey
	expiresAt time.Time
	client    *http.Client
	group     singleflight.Group
	jwksURL   string
	now       func() time.Time
}

func newAppleJWKCache() *appleJWKCache {
	return &appleJWKCache{
		keys: make(map[string]*rsa.PublicKey),
		client: &http.Client{
			Timeout: appleJWKFetchTimeout,
		},
		jwksURL: "https://appleid.apple.com/auth/keys",
		now:     time.Now,
	}
}

func (c *appleJWKCache) resolveKey(token *jwt.Token) (interface{}, error) {
	if token.Method == nil || token.Method.Alg() != jwt.SigningMethodRS256.Alg() {
		return nil, fmt.Errorf("unexpected apple signing method")
	}

	kid, _ := token.Header["kid"].(string)
	if kid == "" {
		return nil, fmt.Errorf("apple token missing key id")
	}

	if key, ok := c.cachedKey(kid); ok {
		return key, nil
	}

	keys, err := c.refresh()
	if err != nil {
		return nil, fmt.Errorf("failed to load apple signing keys")
	}

	key := keys[kid]
	if key == nil {
		return nil, fmt.Errorf("apple signing key not found")
	}

	return key, nil
}

func (c *appleJWKCache) cachedKey(kid string) (*rsa.PublicKey, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.now().After(c.expiresAt) {
		return nil, false
	}

	key := c.keys[kid]
	return key, key != nil
}

func (c *appleJWKCache) refresh() (map[string]*rsa.PublicKey, error) {
	result, err, _ := c.group.Do("apple-jwks-refresh", func() (interface{}, error) {
		keys, err := c.fetch()
		if err != nil {
			return nil, err
		}

		c.mu.Lock()
		c.keys = keys
		c.expiresAt = c.now().Add(appleJWKCacheTTL)
		c.mu.Unlock()

		return keys, nil
	})
	if err != nil {
		return nil, err
	}

	return result.(map[string]*rsa.PublicKey), nil
}

func (c *appleJWKCache) fetch() (map[string]*rsa.PublicKey, error) {
	resp, err := c.client.Get(c.jwksURL)
	if err != nil {
		return nil, fmt.Errorf("fetch apple jwks: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("apple jwks returned status %d", resp.StatusCode)
	}

	var jwkResponse appleJWKResponse
	if err := json.NewDecoder(resp.Body).Decode(&jwkResponse); err != nil {
		return nil, fmt.Errorf("decode apple jwks: %w", err)
	}

	keys := make(map[string]*rsa.PublicKey, len(jwkResponse.Keys))
	for _, jwk := range jwkResponse.Keys {
		if jwk.Kid == "" || jwk.Kty != "RSA" {
			continue
		}
		if jwk.Use != "" && jwk.Use != "sig" {
			continue
		}
		if jwk.Alg != "" && jwk.Alg != jwt.SigningMethodRS256.Alg() {
			continue
		}

		key, err := parseAppleRSAPublicKey(jwk)
		if err != nil {
			return nil, err
		}
		keys[jwk.Kid] = key
	}

	if len(keys) == 0 {
		return nil, fmt.Errorf("apple jwks contained no usable keys")
	}

	return keys, nil
}

func parseAppleRSAPublicKey(jwk appleJWK) (*rsa.PublicKey, error) {
	modulusBytes, err := base64.RawURLEncoding.DecodeString(jwk.N)
	if err != nil {
		return nil, fmt.Errorf("decode apple jwk modulus: %w", err)
	}

	exponentBytes, err := base64.RawURLEncoding.DecodeString(jwk.E)
	if err != nil {
		return nil, fmt.Errorf("decode apple jwk exponent: %w", err)
	}

	exponent := 0
	for _, b := range exponentBytes {
		exponent = (exponent << 8) | int(b)
	}
	if exponent <= 0 {
		return nil, fmt.Errorf("apple jwk exponent is invalid")
	}

	modulus := new(big.Int).SetBytes(modulusBytes)
	if modulus.Sign() <= 0 {
		return nil, fmt.Errorf("apple jwk modulus is invalid")
	}

	return &rsa.PublicKey{
		N: modulus,
		E: exponent,
	}, nil
}
