package handlers

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/config"
)

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

	token := signedAppleToken(t, privateKey, "test-kid", handler.cfg.AppleClientID, true)

	email, firstName, lastName, providerID, err := handler.verifyAppleToken(token, "", "")
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

	if _, _, _, _, err := handler.verifyAppleToken(tokenString, "", ""); err == nil {
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

	if _, _, _, _, err := handler.verifyAppleToken(token, "", ""); err == nil {
		t.Fatal("expected unverified apple email to be rejected")
	}
}

func signedAppleToken(t *testing.T, privateKey *rsa.PrivateKey, kid, audience string, emailVerified bool) string {
	t.Helper()

	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, appleTokenClaims{
		Email:         "user@example.com",
		EmailVerified: mustMarshalRawMessage(t, emailVerified),
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
