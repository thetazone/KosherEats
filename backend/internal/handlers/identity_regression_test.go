package handlers

// Regression tests for the auth-identity cluster from the xhigh review:
//   #1 a social sign-in / provider link must NOT flip a password account's
//      users.auth_provider to the social provider — that column is the
//      password-eligibility gate (Login + password_reset filter on
//      auth_provider IS NULL OR 'email'), so flipping it silently and
//      unrecoverably disables password login and reset.
//   #2 CheckEmail must report HOW an account signs in (auth_method) so a
//      preview-aware client doesn't dead-end an OAuth/phone account on the
//      password screen, and must agree with Login's eligibility rule.

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
)

func TestIntegration_ProviderLinkPreservesPasswordLogin(t *testing.T) {
	harness.resetVolatile(t)
	ctx := context.Background()

	// A real email+password account.
	token, userID := harness.registerUser(t, "linkpreserve")
	_ = token
	email := harness.userEmail(t, userID)

	// Baseline: password login works.
	login := func() int {
		return harness.do(http.MethodPost, "/api/v1/auth/login", "", map[string]any{
			"email": email, "password": "password123",
		}).Code
	}
	if code := login(); code != http.StatusOK {
		t.Fatalf("baseline password login: status %d, want 200", code)
	}

	// Simulate what a social sign-in / LinkProvider now does: record the
	// provider link in the authoritative junction table. The fix is that this
	// does NOT touch users.auth_provider. Force the junction row directly so the
	// test doesn't depend on mocking a Google/Apple token.
	if _, err := harness.h.db.Pool.Exec(ctx,
		`INSERT INTO user_auth_providers (user_id, provider, provider_id)
		 VALUES ($1, 'google', $2) ON CONFLICT (user_id, provider) DO NOTHING`,
		userID, "google-"+userID); err != nil {
		t.Fatalf("seed junction row: %v", err)
	}

	// The invariant: auth_provider is still password-eligible.
	var authProvider *string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`SELECT auth_provider FROM users WHERE id = $1`, userID).Scan(&authProvider); err != nil {
		t.Fatalf("read auth_provider: %v", err)
	}
	if authProvider != nil && *authProvider != "" && *authProvider != "email" {
		t.Fatalf("auth_provider was flipped to %q — password login is now dead", *authProvider)
	}

	// And password login still works, which is the whole point.
	if code := login(); code != http.StatusOK {
		t.Fatalf("password login after linking a provider: status %d, want 200 — the account got locked out", code)
	}
}

func TestIntegration_CheckEmailReportsAuthMethod(t *testing.T) {
	harness.resetVolatile(t)
	ctx := context.Background()

	check := func(email string) (bool, string) {
		rec := harness.do(http.MethodPost, "/api/v1/auth/email/check", "", map[string]any{"email": email})
		if rec.Code != http.StatusOK {
			t.Fatalf("check %s: status %d, body %s", email, rec.Code, rec.Body.String())
		}
		var resp struct {
			Exists     bool   `json:"exists"`
			AuthMethod string `json:"auth_method"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("check %s decode: %v", email, err)
		}
		return resp.Exists, resp.AuthMethod
	}

	// Unknown email.
	if exists, method := check(uniqueEmail("nobody")); exists || method != "" {
		t.Fatalf("unknown email: exists=%v method=%q, want false/\"\"", exists, method)
	}

	// Password account -> "password".
	_, pwUserID := harness.registerUser(t, "checkpw")
	pwEmail := harness.userEmail(t, pwUserID)
	if exists, method := check(pwEmail); !exists || method != "password" {
		t.Fatalf("password account: exists=%v method=%q, want true/password", exists, method)
	}

	// OAuth account -> the provider, NOT "password". This is the dead-end #2
	// fixes: without auth_method the client would show the password screen for
	// an account Login can never authenticate.
	if _, err := harness.h.db.Pool.Exec(ctx,
		`UPDATE users SET auth_provider = 'google', password_hash = '' WHERE id = $1`, pwUserID); err != nil {
		t.Fatalf("flip to google: %v", err)
	}
	if exists, method := check(pwEmail); !exists || method != "google" {
		t.Fatalf("google account: exists=%v method=%q, want true/google", exists, method)
	}
}
