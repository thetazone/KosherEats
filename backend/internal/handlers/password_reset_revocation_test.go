package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

// mintTokensWithoutEpochClaim reproduces the exact claim set generateTokens
// produced before migration 059, so the suite can exercise tokens that are
// already in users' keychains at deploy time.
func mintTokensWithoutEpochClaim(t *testing.T, h *Handler, userID string) (access, refresh string) {
	t.Helper()
	sign := func(extra map[string]any) string {
		claims := jwt.MapClaims{
			"sub":      userID,
			"role":     "consumer",
			"vertical": "kosher",
			"exp":      time.Now().Add(15 * time.Minute).Unix(),
			"iat":      time.Now().Unix(),
		}
		for k, v := range extra {
			claims[k] = v
		}
		s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(h.cfg.JWTSecret))
		if err != nil {
			t.Fatalf("sign legacy token: %v", err)
		}
		return s
	}
	return sign(nil), sign(map[string]any{"typ": "refresh", "exp": time.Now().Add(7 * 24 * time.Hour).Unix()})
}

// TestIntegration_PasswordResetRevokesExistingSessions pins the fix for the
// self-renewing refresh token.
//
// Before migration 059 there was no session store, jti or denylist anywhere in
// the backend, and ResetPassword rewrote password_hash only. Refresh tokens are
// stateless 7-day HS256 JWTs and /auth/refresh mints a BRAND-NEW 7-day one on
// every call, so an attacker holding a leaked refresh token could renew it
// forever — straight through the victim's password reset, which is the exact
// remediation users are told to perform. This asserts the reset now strands
// both the stolen refresh token and any access token minted alongside it, while
// the victim's own re-login still works.
func TestIntegration_PasswordResetRevokesExistingSessions(t *testing.T) {
	harness.resetVolatile(t)
	ctx := context.Background()
	pool := harness.h.db.Pool

	// The victim's live session — treat both halves as compromised.
	email := uniqueEmail("revoke-victim")
	harness.verifySignupEmail(t, email)
	regRec := harness.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": email, "password": "password123", "first_name": "Victim",
	})
	if regRec.Code != http.StatusCreated {
		t.Fatalf("register: %d %s", regRec.Code, regRec.Body.String())
	}
	var reg AuthResponse
	if err := json.Unmarshal(regRec.Body.Bytes(), &reg); err != nil {
		t.Fatalf("register decode: %v", err)
	}
	stolenAccess, stolenRefresh, userID := reg.Token, reg.RefreshToken, reg.User.ID
	if stolenRefresh == "" {
		t.Fatal("register returned no refresh token")
	}

	// Baseline: the stolen pair works before the reset, otherwise the
	// post-reset assertions below would pass for the wrong reason.
	if rec := harness.do(http.MethodGet, "/api/v1/cart", stolenAccess, nil); rec.Code != http.StatusOK {
		t.Fatalf("pre-reset cart with access token: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{
		"refresh_token": stolenRefresh,
	}); rec.Code != http.StatusOK {
		t.Fatalf("pre-reset refresh: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}

	// Victim performs the standard remediation. Plant a known code the way
	// ForgotPassword does (the real one is only ever emailed).
	codeHash, err := bcrypt.GenerateFromPassword([]byte("123456"), bcrypt.MinCost)
	if err != nil {
		t.Fatalf("hash reset code: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`UPDATE users SET reset_code_hash = $1, reset_code_expires_at = NOW() + interval '15 minutes',
		   reset_code_attempts = 0 WHERE id = $2`, string(codeHash), userID,
	); err != nil {
		t.Fatalf("plant reset code: %v", err)
	}
	resetRec := harness.do(http.MethodPost, "/api/v1/auth/password/reset", "", map[string]any{
		"email": email, "code": "123456", "new_password": "brand-new-password",
		"role": "consumer", "vertical": "kosher",
	})
	if resetRec.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", resetRec.Code, resetRec.Body.String())
	}

	// The whole point: the attacker can no longer renew, and cannot ride out
	// the remaining life of the access token either.
	if rec := harness.do(http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{
		"refresh_token": stolenRefresh,
	}); rec.Code != http.StatusUnauthorized {
		t.Fatalf("stolen refresh token still renews after password reset: got %d, want 401 (%s)",
			rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodGet, "/api/v1/cart", stolenAccess, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("stolen access token still authenticates after password reset: got %d, want 401 (%s)",
			rec.Code, rec.Body.String())
	}

	// ...and the victim's own new session is fully functional, including a
	// refresh, so the epoch bump revokes rather than bricks the account.
	loginRec := harness.do(http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": email, "password": "brand-new-password", "role": "consumer", "vertical": "kosher",
	})
	if loginRec.Code != http.StatusOK {
		t.Fatalf("login with new password: %d %s", loginRec.Code, loginRec.Body.String())
	}
	var fresh AuthResponse
	if err := json.Unmarshal(loginRec.Body.Bytes(), &fresh); err != nil {
		t.Fatalf("login decode: %v", err)
	}
	if rec := harness.do(http.MethodGet, "/api/v1/cart", fresh.Token, nil); rec.Code != http.StatusOK {
		t.Fatalf("post-reset cart with fresh access token: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{
		"refresh_token": fresh.RefreshToken,
	}); rec.Code != http.StatusOK {
		t.Fatalf("post-reset refresh with fresh refresh token: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
}

// TestIntegration_LegacyTokenWithoutEpochClaimStillWorks guards the deploy
// itself: every token already in the wild predates the "epoch" claim, and
// rejecting them would sign out the entire user base on release. A missing
// claim reads as 0, which is what the new column defaults to, so those sessions
// stay valid right up until their account actually resets its password.
func TestIntegration_LegacyTokenWithoutEpochClaimStillWorks(t *testing.T) {
	harness.resetVolatile(t)
	ctx := context.Background()
	pool := harness.h.db.Pool

	_, userID := harness.registerUser(t, "legacy-epoch")

	// Strip the claim the way a pre-059 build would have minted it.
	legacyAccess, legacyRefresh := mintTokensWithoutEpochClaim(t, harness.h, userID)

	if rec := harness.do(http.MethodGet, "/api/v1/cart", legacyAccess, nil); rec.Code != http.StatusOK {
		t.Fatalf("legacy access token rejected: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{
		"refresh_token": legacyRefresh,
	}); rec.Code != http.StatusOK {
		t.Fatalf("legacy refresh token rejected: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}

	// Once the account's epoch moves, the legacy token dies like any other.
	if _, err := pool.Exec(ctx, `UPDATE users SET token_epoch = token_epoch + 1 WHERE id = $1`, userID); err != nil {
		t.Fatalf("bump epoch: %v", err)
	}
	if rec := harness.do(http.MethodGet, "/api/v1/cart", legacyAccess, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("legacy access token survived an epoch bump: got %d, want 401 (%s)", rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{
		"refresh_token": legacyRefresh,
	}); rec.Code != http.StatusUnauthorized {
		t.Fatalf("legacy refresh token survived an epoch bump: got %d, want 401 (%s)", rec.Code, rec.Body.String())
	}
}
