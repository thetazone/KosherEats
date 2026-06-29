package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

// insertSignupCode writes an email_otp 'signup' row whose code is `code` (bcrypt
// hashed, like production), so a test can drive the real /auth/email/verify
// endpoint with a code it actually knows (the stub mailer never reveals the
// random one).
func insertSignupCode(t *testing.T, email, code string) {
	t.Helper()
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.DefaultCost)
	if err != nil {
		t.Fatalf("hash code: %v", err)
	}
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`INSERT INTO email_otp (email, purpose, code_hash, expires_at, attempts, verified_at)
		 VALUES ($1, 'signup', $2, NOW() + interval '15 minutes', 0, NULL)
		 ON CONFLICT (email, purpose) DO UPDATE
		   SET code_hash = $2, expires_at = NOW() + interval '15 minutes', attempts = 0, verified_at = NULL`,
		email, string(hash)); err != nil {
		t.Fatalf("insert signup code: %v", err)
	}
}

// Register must refuse a consumer who hasn't proven the email via OTP.
func TestIntegration_RegisterRequiresEmailVerification(t *testing.T) {
	harness.resetVolatile(t)

	rec := harness.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email":      uniqueEmail("unverified"),
		"password":   "password123",
		"first_name": "Nope",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("register without email OTP: status %d (want 400), body %s", rec.Code, rec.Body.String())
	}
}

// The full email-signup OTP path: start (stub), verify with the known code, then
// register succeeds and the account comes back email-verified but not yet
// phone-verified.
func TestIntegration_EmailSignupOtpThenRegister(t *testing.T) {
	harness.resetVolatile(t)
	email := uniqueEmail("otp-signup")

	// Seed a known code (equivalent to /auth/email/start having sent it).
	insertSignupCode(t, email, "654321")

	// Wrong code is rejected.
	if rec := harness.do(http.MethodPost, "/api/v1/auth/email/verify", "", map[string]any{
		"email": email, "code": "000000",
	}); rec.Code != http.StatusBadRequest {
		t.Fatalf("verify wrong code: status %d (want 400), body %s", rec.Code, rec.Body.String())
	}

	// Correct code verifies.
	if rec := harness.do(http.MethodPost, "/api/v1/auth/email/verify", "", map[string]any{
		"email": email, "code": "654321",
	}); rec.Code != http.StatusOK {
		t.Fatalf("verify correct code: status %d (want 200), body %s", rec.Code, rec.Body.String())
	}

	// Register now succeeds and reflects the verification state.
	rec := harness.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": email, "password": "password123", "first_name": "Otp",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register after verify: status %d (want 201), body %s", rec.Code, rec.Body.String())
	}
	var resp AuthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("register decode: %v", err)
	}
	if !resp.User.EmailVerified {
		t.Fatalf("expected email_verified=true after OTP register")
	}
	if resp.User.PhoneVerified {
		t.Fatalf("expected phone_verified=false on a fresh email signup")
	}
}

// A consumer who is email-verified but not phone-verified is blocked at the
// transaction gate, and clears it once the phone is verified too.
func TestIntegration_TransactionGateBlocksUntilFullyVerified(t *testing.T) {
	harness.resetVolatile(t)
	email := uniqueEmail("gate")
	harness.verifySignupEmail(t, email)

	rec := harness.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": email, "password": "password123", "first_name": "Gate",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register: status %d, body %s", rec.Code, rec.Body.String())
	}
	var resp AuthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("register decode: %v", err)
	}
	token := resp.Token

	// phone_verified is still false → order creation is gated.
	rec = harness.do(http.MethodPost, "/api/v1/orders", token, map[string]any{})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("create order while unverified: status %d (want 403), body %s", rec.Code, rec.Body.String())
	}
	var gate map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &gate); err != nil {
		t.Fatalf("gate body decode: %v", err)
	}
	if gate["error"] != "verification_required" {
		t.Fatalf("gate error = %v, want verification_required (body %s)", gate["error"], rec.Body.String())
	}

	// Same gate guards the money path.
	if rec := harness.do(http.MethodPost, "/api/v1/payments/intent", token, map[string]any{}); rec.Code != http.StatusForbidden {
		t.Fatalf("create payment intent while unverified: status %d (want 403), body %s", rec.Code, rec.Body.String())
	}

	// Verify the phone (the real flow is /user/phone/change; stamp it directly).
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE users SET phone_verified = true WHERE id = $1`, resp.User.ID); err != nil {
		t.Fatalf("stamp phone verified: %v", err)
	}

	// Now the gate lets the request through (it fails later for an empty body,
	// but crucially NOT with 403 verification_required).
	rec = harness.do(http.MethodPost, "/api/v1/orders", token, map[string]any{})
	if rec.Code == http.StatusForbidden {
		t.Fatalf("create order after full verification still gated: %s", rec.Body.String())
	}
}

// Verifying an add-email onto one account rejects an address already owned by
// another account in the same (role, vertical).
func TestIntegration_AddEmailRejectsDuplicate(t *testing.T) {
	harness.resetVolatile(t)

	// Account A owns emailA.
	_, _ = harness.registerUser(t, "owner-a")
	var emailA string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT email FROM users WHERE role = 'consumer' ORDER BY created_at DESC LIMIT 1`).Scan(&emailA); err != nil {
		t.Fatalf("lookup A email: %v", err)
	}

	// Account B tries to add emailA to itself.
	tokenB, _ := harness.registerUser(t, "owner-b")

	// Seed an add_email code for emailA (as if B requested /user/email/start).
	hash, err := bcrypt.GenerateFromPassword([]byte("111222"), bcrypt.DefaultCost)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`INSERT INTO email_otp (email, purpose, code_hash, expires_at)
		 VALUES ($1, 'add_email', $2, NOW() + interval '15 minutes')
		 ON CONFLICT (email, purpose) DO UPDATE SET code_hash = $2, expires_at = NOW() + interval '15 minutes', attempts = 0, verified_at = NULL`,
		emailA, string(hash)); err != nil {
		t.Fatalf("seed add_email code: %v", err)
	}

	rec := harness.do(http.MethodPost, "/api/v1/user/email/verify", tokenB, map[string]any{
		"email": emailA, "code": "111222",
	})
	if rec.Code != http.StatusConflict {
		t.Fatalf("add duplicate email: status %d (want 409), body %s", rec.Code, rec.Body.String())
	}
}
