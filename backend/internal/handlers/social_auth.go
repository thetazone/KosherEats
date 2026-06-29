package handlers

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
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
	Provider  string          `json:"provider"`        // "google", "apple", "facebook"
	Token     string          `json:"token"`           // ID token or access token from provider
	FirstName string          `json:"first_name"`      // optional, from provider
	LastName  string          `json:"last_name"`       // optional, from provider
	Role      models.UserRole `json:"role"`            // consumer or seller
	// Vertical scopes the account to a branded app ('kosher' | 'vegan').
	// Empty defaults to 'kosher' for older clients.
	Vertical string `json:"vertical,omitempty"`
	// Nonce is the raw nonce the iOS client generated before requesting the
	// Apple ID token. Apple bakes SHA256(nonce) into the token's `nonce`
	// claim; we re-hash and compare here to block token replay attacks.
	// Only set for Apple sign-in.
	Nonce string `json:"nonce,omitempty"`
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
	jwt.RegisteredClaims
	Email         string          `json:"email"`
	EmailVerified json.RawMessage `json:"email_verified"`
	Nonce         string          `json:"nonce"`
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
		email, firstName, lastName, providerID, err = h.verifyAppleToken(req.Token, req.FirstName, req.LastName, req.Nonce)
		avatarURL = ""
	default:
		writeError(w, http.StatusBadRequest, "unsupported provider: "+req.Provider)
		return
	}

	if err != nil {
		slog.Warn("social auth token verification failed",
			slog.String("provider", req.Provider),
			slog.String("error", err.Error()))
		writeError(w, http.StatusUnauthorized, "failed to verify token")
		return
	}

	if email == "" {
		writeError(w, http.StatusBadRequest, "email not available from provider")
		return
	}

	// All lookups are scoped by (role, vertical). The same Apple ID or Google
	// ID can map to many separate user rows — one per (role × vertical) pair
	// — and the calling app's role + vertical disambiguates which row this
	// sign-in is for.
	// SECURITY: clamp the requested role — never let OAuth sign-up of a fresh
	// identity self-assign admin (see allowedSignupRole). The role also scopes
	// every lookup below, so a rejected value never reaches a query or INSERT.
	role, ok := allowedSignupRole(req.Role)
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid role")
		return
	}
	vertical := normalizeVertical(req.Vertical)

	// Match the provider identity via the user_auth_providers junction table
	// (authoritative after migration 022), then fall back to email on the
	// users table. Email-only lookup catches the case where the user updates
	// their email in ProfileCompletionSheet but Apple keeps returning the
	// original @privaterelay.appleid.com on every sign-in.
	var user models.User
	err = nil
	if providerID != "" {
		err = h.db.Pool.QueryRow(r.Context(),
			`SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.role, u.vertical, u.avatar_url, u.email_verified, u.phone_verified, u.created_at, u.updated_at
			 FROM users u
			 JOIN user_auth_providers uap ON u.id = uap.user_id
			 WHERE uap.provider = $1 AND uap.provider_id = $2 AND u.role = $3 AND u.vertical = $4`,
			req.Provider, providerID, role, vertical,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone,
			&user.Role, &user.Vertical, &user.AvatarURL, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)
	}
	if err != nil || providerID == "" {
		err = h.db.Pool.QueryRow(r.Context(),
			`SELECT id, email, first_name, last_name, phone, role, vertical, avatar_url, email_verified, phone_verified, created_at, updated_at
			 FROM users WHERE email = $1 AND role = $2 AND vertical = $3`, email, role, vertical,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone,
			&user.Role, &user.Vertical, &user.AvatarURL, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)
	}

	isNewUser := false
	if err != nil {
		// No account exists for this (provider/email, role) — create one.
		// A different role's account on the same provider/email is a
		// separate row and does not conflict.
		isNewUser = true
		// SECURITY: synthetic password over crypto-random bytes, not a function
		// of the provider id ("oauth-"+providerID was derivable). The Login guard
		// blocks password login for auth_provider != email anyway, but never
		// store a guessable secret. OAuth users authenticate via the social path.
		randPwd := make([]byte, 32)
		if _, rerr := rand.Read(randPwd); rerr != nil {
			writeError(w, http.StatusInternalServerError, "failed to create user")
			return
		}
		dummyHash, _ := bcrypt.GenerateFromPassword(randPwd, bcrypt.DefaultCost)

		// Verification state at creation. Google asserts a verified email and the
		// backend already requires email_verified=="true" on the token, so we
		// trust it. Apple is NOT trusted here: it commonly returns a
		// @privaterelay forwarder, so the consumer app forces a real-email OTP
		// regardless. Either way a new consumer must still verify a phone, so
		// phone_verified starts false. Seller/courier social signups are exempt
		// from this requirement (and from the consumer-transaction gate).
		emailVerified := req.Provider == "google" || role != models.RoleConsumer
		phoneVerified := role != models.RoleConsumer

		err = h.db.Pool.QueryRow(r.Context(),
			`INSERT INTO users (email, password_hash, first_name, last_name, role, vertical, avatar_url, auth_provider, auth_provider_id, email_verified, phone_verified)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
			 RETURNING id, email, first_name, last_name, phone, role, vertical, avatar_url, email_verified, phone_verified, created_at, updated_at`,
			email, string(dummyHash), firstName, lastName, role, vertical, avatarURL, req.Provider, providerID, emailVerified, phoneVerified,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName, &user.Phone,
			&user.Role, &user.Vertical, &user.AvatarURL, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)

		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create user")
			return
		}
	} else {
		// Update avatar and provider info if needed
		if _, err := h.db.Pool.Exec(r.Context(),
			`UPDATE users SET avatar_url = COALESCE(NULLIF($1, ''), avatar_url),
			 auth_provider = $2, auth_provider_id = $3, updated_at = NOW()
			 WHERE id = $4`,
			avatarURL, req.Provider, providerID, user.ID); err != nil {
			slog.Warn("failed to update user avatar/auth_provider on social login",
				slog.String("user_id", user.ID), slog.String("error", err.Error()))
		}
	}

	// Ensure user_auth_providers has this provider link. Covers new users,
	// email-fallback matches that didn't have a junction row yet, and
	// pre-migration accounts not captured by the 022 seed.
	if providerID != "" {
		if _, err := h.db.Pool.Exec(r.Context(),
			`INSERT INTO user_auth_providers (user_id, provider, provider_id)
			 VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, provider) DO UPDATE SET provider_id = $3`,
			user.ID, req.Provider, providerID,
		); err != nil {
			slog.Warn("failed to upsert user_auth_providers on social login",
				slog.String("user_id", user.ID), slog.String("error", err.Error()))
		}
	}
	_ = isNewUser

	// Couriers need a courier_profiles row or GetCourierProfile 404s and the
	// iOS courier app spins forever after sign-in. The email signup path
	// (CourierRegister) creates this in the same tx; the social path never
	// did, so Apple/Google signups were dead-ends. Idempotent via the
	// user_id UNIQUE constraint.
	//
	// Starts at pending_info so the reviewer walks through the full
	// onboarding flow (phone → vehicle → docs → background check).
	// Dev mode auto-approves the background check in ~2s; no Checkr
	// key needed for App Review.
	if user.Role == models.RoleCourier {
		if _, err := h.db.Pool.Exec(r.Context(),
			`INSERT INTO courier_profiles (user_id, onboarding_status, phone_verified)
			 VALUES ($1, 'pending_info', false)
			 ON CONFLICT (user_id) DO NOTHING`,
			user.ID); err != nil {
			slog.Error("failed to create courier_profiles row on social login",
				slog.String("user_id", user.ID), slog.String("error", err.Error()))
		}
	}

	token, refreshToken, err := h.generateTokens(user.ID, string(user.Role), user.Vertical)
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

func (h *Handler) verifyAppleToken(idToken string, firstName, lastName, rawNonce string) (email, fName, lName, providerID string, err error) {
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
		jwt.WithExpirationRequired(),
	)
	if err != nil {
		return "", "", "", "", fmt.Errorf("invalid apple token: %w", err)
	}

	// Accept any of the configured bundle IDs (comma-separated). Consumer,
	// seller, and courier each ship with a different Bundle ID, and Apple
	// puts that Bundle ID in the JWT's aud claim — so the backend must
	// recognize all three.
	audienceMatched := false
	for _, allowed := range strings.Split(h.cfg.AppleClientID, ",") {
		allowed = strings.TrimSpace(allowed)
		if allowed == "" {
			continue
		}
		for _, aud := range claims.Audience {
			if aud == allowed {
				audienceMatched = true
				break
			}
		}
		if audienceMatched {
			break
		}
	}
	if !audienceMatched {
		return "", "", "", "", fmt.Errorf("apple token audience not allowed: %v", claims.Audience)
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

	// Replay-attack mitigation. iOS clients that generate a nonce send the
	// raw value alongside the ID token; we re-hash and compare to the JWT's
	// nonce claim. If the JWT carries no nonce claim (legacy clients that
	// haven't rolled out nonce generation yet), skip the check — Apple
	// still validates the token's signature/audience/expiration above, so
	// the degraded mode isn't a wide-open bypass.
	if claims.Nonce != "" || rawNonce != "" {
		if rawNonce == "" {
			return "", "", "", "", fmt.Errorf("nonce is required for Apple sign-in")
		}
		sum := sha256.Sum256([]byte(rawNonce))
		expected := hex.EncodeToString(sum[:])
		if subtle.ConstantTimeCompare([]byte(claims.Nonce), []byte(expected)) != 1 {
			return "", "", "", "", fmt.Errorf("nonce mismatch in Apple token")
		}
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
