package handlers

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/config"
)

func TestVerifyGoogleTokenAcceptsMatchingAudience(t *testing.T) {
	restoreURL := swapGoogleTokenInfoURLForTest("https://tokeninfo.test")
	defer restoreURL()

	restoreClient := swapGoogleHTTPClientForTest(newGoogleTokenInfoClient(t, http.StatusOK, googleTokenInfo{
		Email:         "user@example.com",
		EmailVerified: "true",
		GivenName:     "Test",
		FamilyName:    "User",
		Picture:       "https://example.com/avatar.png",
		Sub:           "google-user-123",
		Aud:           "web-client-id",
	}))
	defer restoreClient()

	handler := &Handler{
		cfg: &config.Config{GoogleClientID: "web-client-id"},
	}

	email, firstName, lastName, avatarURL, providerID, err := handler.verifyGoogleToken("good-token")
	if err != nil {
		t.Fatalf("verify google token: %v", err)
	}

	if email != "user@example.com" {
		t.Fatalf("unexpected email: %s", email)
	}
	if firstName != "Test" || lastName != "User" {
		t.Fatalf("unexpected names: %s %s", firstName, lastName)
	}
	if avatarURL != "https://example.com/avatar.png" {
		t.Fatalf("unexpected avatar url: %s", avatarURL)
	}
	if providerID != "google-user-123" {
		t.Fatalf("unexpected provider id: %s", providerID)
	}
}

func TestVerifyGoogleTokenRejectsMismatchedAudience(t *testing.T) {
	restoreURL := swapGoogleTokenInfoURLForTest("https://tokeninfo.test")
	defer restoreURL()

	restoreClient := swapGoogleHTTPClientForTest(newGoogleTokenInfoClient(t, http.StatusOK, googleTokenInfo{
		Email:         "user@example.com",
		EmailVerified: "true",
		Sub:           "google-user-123",
		Aud:           "different-client-id",
	}))
	defer restoreClient()

	handler := &Handler{
		cfg: &config.Config{GoogleClientID: "web-client-id"},
	}

	if _, _, _, _, _, err := handler.verifyGoogleToken("wrong-audience-token"); err == nil {
		t.Fatal("expected mismatched google audience to be rejected")
	}
}

func TestVerifyGoogleTokenRejectsWhenGoogleSignInUnconfigured(t *testing.T) {
	handler := &Handler{
		cfg: &config.Config{},
	}

	if _, _, _, _, _, err := handler.verifyGoogleToken("token"); err == nil {
		t.Fatal("expected unconfigured google sign-in to be rejected")
	}
}

func TestVerifyGoogleTokenRejectsUnverifiedEmail(t *testing.T) {
	restoreURL := swapGoogleTokenInfoURLForTest("https://tokeninfo.test")
	defer restoreURL()

	restoreClient := swapGoogleHTTPClientForTest(newGoogleTokenInfoClient(t, http.StatusOK, googleTokenInfo{
		Email:         "user@example.com",
		EmailVerified: "false",
		Sub:           "google-user-123",
		Aud:           "web-client-id",
	}))
	defer restoreClient()

	handler := &Handler{
		cfg: &config.Config{GoogleClientID: "web-client-id"},
	}

	if _, _, _, _, _, err := handler.verifyGoogleToken("unverified-email-token"); err == nil {
		t.Fatal("expected unverified google email to be rejected")
	}
}

func TestVerifyAppleTokenAcceptsValidSignedToken(t *testing.T) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate rsa key: %v", err)
	}

	restore := swapAppleJWKCacheForTest(newStaticAppleJWKCache("test-kid", &privateKey.PublicKey))
	defer restore()

	handler := &Handler{
		cfg: &config.Config{AppleClientID: "com.koshereats.ios"},
	}

	// Replay-attack mitigation: handler hashes rawNonce (SHA256 → hex) and
	// expects it to match the JWT's nonce claim. We bake the hashed form into
	// the signed token so the verification round-trips successfully.
	const rawNonce = "test-raw-nonce"
	hashedNonce := sha256.Sum256([]byte(rawNonce))
	hexNonce := hex.EncodeToString(hashedNonce[:])

	token := signedAppleTokenWithNonce(t, privateKey, "test-kid", handler.cfg.AppleClientID, true, hexNonce)

	// First/last name come from the iOS client (Apple only returns them on
	// the very first sign-in), so verifyAppleToken returns whatever the
	// client supplied as-is.
	email, firstName, lastName, providerID, err := handler.verifyAppleToken(token, "Apple", "User", rawNonce)
	if err != nil {
		t.Fatalf("verify apple token: %v", err)
	}

	if email != "user@example.com" {
		t.Fatalf("unexpected email: %s", email)
	}
	if firstName != "Apple" || lastName != "User" {
		t.Fatalf("unexpected default names: %s %s", firstName, lastName)
	}
	if providerID != "apple-user-123" {
		t.Fatalf("unexpected provider id: %s", providerID)
	}
}

func TestVerifyAppleTokenRejectsAlgNone(t *testing.T) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate rsa key: %v", err)
	}

	restore := swapAppleJWKCacheForTest(newStaticAppleJWKCache("test-kid", &privateKey.PublicKey))
	defer restore()

	handler := &Handler{
		cfg: &config.Config{AppleClientID: "com.koshereats.ios"},
	}

	now := time.Now()
	unsignedToken := jwt.NewWithClaims(jwt.SigningMethodNone, appleTokenClaims{
		Email:         "user@example.com",
		EmailVerified: mustMarshalRawMessage(t, true),
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "https://appleid.apple.com",
			Subject:   "apple-user-123",
			Audience:  jwt.ClaimStrings{handler.cfg.AppleClientID},
			ExpiresAt: jwt.NewNumericDate(now.Add(time.Hour)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	})
	unsignedToken.Header["kid"] = "test-kid"

	tokenString, err := unsignedToken.SignedString(jwt.UnsafeAllowNoneSignatureType)
	if err != nil {
		t.Fatalf("sign none token: %v", err)
	}

	if _, _, _, _, err := handler.verifyAppleToken(tokenString, "", "", ""); err == nil {
		t.Fatal("expected alg=none token to be rejected")
	}
}

func TestVerifyAppleTokenRejectsUnverifiedEmail(t *testing.T) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate rsa key: %v", err)
	}

	restore := swapAppleJWKCacheForTest(newStaticAppleJWKCache("test-kid", &privateKey.PublicKey))
	defer restore()

	handler := &Handler{
		cfg: &config.Config{AppleClientID: "com.koshereats.ios"},
	}

	token := signedAppleToken(t, privateKey, "test-kid", handler.cfg.AppleClientID, false)

	if _, _, _, _, err := handler.verifyAppleToken(token, "", "", ""); err == nil {
		t.Fatal("expected unverified apple email to be rejected")
	}
}

func signedAppleToken(t *testing.T, privateKey *rsa.PrivateKey, kid, audience string, emailVerified bool) string {
	t.Helper()
	return signedAppleTokenWithNonce(t, privateKey, kid, audience, emailVerified, "")
}

func signedAppleTokenWithNonce(t *testing.T, privateKey *rsa.PrivateKey, kid, audience string, emailVerified bool, hashedNonce string) string {
	t.Helper()

	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, appleTokenClaims{
		Email:         "user@example.com",
		EmailVerified: mustMarshalRawMessage(t, emailVerified),
		Nonce:         hashedNonce,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "https://appleid.apple.com",
			Subject:   "apple-user-123",
			Audience:  jwt.ClaimStrings{audience},
			ExpiresAt: jwt.NewNumericDate(now.Add(time.Hour)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	})
	token.Header["kid"] = kid

	tokenString, err := token.SignedString(privateKey)
	if err != nil {
		t.Fatalf("sign token: %v", err)
	}

	return tokenString
}

func mustMarshalRawMessage(t *testing.T, value interface{}) json.RawMessage {
	t.Helper()

	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal raw message: %v", err)
	}

	return encoded
}

func newStaticAppleJWKCache(kid string, key *rsa.PublicKey) *appleJWKCache {
	return &appleJWKCache{
		keys: map[string]*rsa.PublicKey{
			kid: key,
		},
		expiresAt: time.Now().Add(time.Hour),
		now:       time.Now,
	}
}

func swapAppleJWKCacheForTest(cache *appleJWKCache) func() {
	previous := appleJWKs
	appleJWKs = cache
	return func() {
		appleJWKs = previous
	}
}

func newGoogleTokenInfoClient(t *testing.T, statusCode int, payload googleTokenInfo) *http.Client {
	t.Helper()

	return &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			if got := r.URL.Query().Get("id_token"); got == "" {
				t.Errorf("expected id_token query param to be set")
			}
			if got := r.URL.Scheme + "://" + r.URL.Host + r.URL.Path; got != "https://tokeninfo.test" {
				t.Errorf("unexpected tokeninfo url: %s", got)
			}

			body, err := json.Marshal(payload)
			if err != nil {
				t.Fatalf("marshal tokeninfo payload: %v", err)
			}

			return &http.Response{
				StatusCode: statusCode,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(string(body))),
			}, nil
		}),
	}
}

func swapGoogleHTTPClientForTest(client *http.Client) func() {
	previous := googleHTTPClient
	googleHTTPClient = client
	return func() {
		googleHTTPClient = previous
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func swapGoogleTokenInfoURLForTest(tokenInfoURL string) func() {
	previous := googleTokenInfoURL
	googleTokenInfoURL = tokenInfoURL
	return func() {
		googleTokenInfoURL = previous
	}
}
