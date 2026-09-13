package handlers

// Phone-OTP login: role-dependent account creation.
//
// Consumers (and couriers) onboard by phone — a verified OTP for an unknown
// number mints a fresh account. Sellers do NOT: a seller account is created
// through the seller app's email/Apple/Google registration, and the phone OTP
// only signs an existing seller in. Before this guard, any stranger's phone
// minted a brand-new seller row and dropped them into restaurant onboarding.
//
// Runs against the shared Postgres harness from integration_test.go (SMS in
// dev stub mode, so the OTP code is "1234").

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/koshereats/backend/internal/models"
)

// phoneUserCount returns how many users rows carry (phone, role).
func phoneUserCount(t *testing.T, phone string, role models.UserRole) int {
	t.Helper()
	var n int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT count(*) FROM users WHERE phone = $1 AND role = $2`, phone, role).Scan(&n); err != nil {
		t.Fatalf("count users for %s/%s: %v", phone, role, err)
	}
	return n
}

// otpStartExists reports whether a phone_otp_starts row is still pending for
// the phone (i.e. the OTP has NOT been consumed).
func otpStartExists(t *testing.T, phone string) bool {
	t.Helper()
	var n int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT count(*) FROM phone_otp_starts WHERE phone = $1`, phone).Scan(&n); err != nil {
		t.Fatalf("count phone_otp_starts for %s: %v", phone, err)
	}
	return n > 0
}

func startPhoneOTP(t *testing.T, phone string) {
	t.Helper()
	rec := harness.do(http.MethodPost, "/api/v1/auth/phone/start", "", map[string]any{"phone": phone})
	if rec.Code != http.StatusOK {
		t.Fatalf("phone/start: %d %s", rec.Code, rec.Body.String())
	}
}

// (a) seller + unknown phone: the OTP is consumed, but no account is minted and
// the client gets a 404 with an in-app-displayable message.
func TestPhoneVerify_SellerUnknownPhoneIsRejectedNotCreated(t *testing.T) {
	harness.resetVolatile(t)
	const phone = "+13475550201"
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(context.Background(), `DELETE FROM users WHERE phone = $1`, phone)
	})

	if n := phoneUserCount(t, phone, models.RoleSeller); n != 0 {
		t.Fatalf("precondition: expected no seller for %s, found %d", phone, n)
	}

	startPhoneOTP(t, phone)
	rec := harness.do(http.MethodPost, "/api/v1/auth/phone/verify", "", map[string]any{
		"phone": phone, "code": "1234", "role": "seller",
	})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("seller verify for unknown phone: want 404, got %d %s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode error body: %v (%s)", err, rec.Body.String())
	}
	if body["error"] != phoneNoSellerAccountMsg {
		t.Fatalf("error message: want %q, got %q", phoneNoSellerAccountMsg, body["error"])
	}
	if !strings.Contains(body["error"], "No seller account") {
		t.Fatalf("error message should be user-facing, got %q", body["error"])
	}

	// No row for ANY role — the seller path must not fall through to creation.
	for _, role := range []models.UserRole{models.RoleSeller, models.RoleConsumer, models.RoleCourier} {
		if n := phoneUserCount(t, phone, role); n != 0 {
			t.Fatalf("seller verify must not create a user; found %d %s row(s)", n, role)
		}
	}

	// The OTP was validated and consumed BEFORE the account lookup, so the row
	// is gone — a second attempt with the same code cannot reuse it.
	if otpStartExists(t, phone) {
		t.Fatal("phone_otp_starts row should be consumed by a successful code check")
	}
	again := harness.do(http.MethodPost, "/api/v1/auth/phone/verify", "", map[string]any{
		"phone": phone, "code": "1234", "role": "seller",
	})
	if again.Code != http.StatusUnauthorized {
		t.Fatalf("replaying a consumed OTP: want 401, got %d %s", again.Code, again.Body.String())
	}
}

// The account-existence answer must sit BEHIND the code check: a wrong code for
// an unregistered seller phone is a plain 401, never the 404 — otherwise the
// endpoint would leak which numbers have seller accounts without proving
// control of the phone.
func TestPhoneVerify_SellerWrongCodeDoesNotLeakAccountExistence(t *testing.T) {
	harness.resetVolatile(t)
	const phone = "+13475550202"

	startPhoneOTP(t, phone)
	rec := harness.do(http.MethodPost, "/api/v1/auth/phone/verify", "", map[string]any{
		"phone": phone, "code": "0000", "role": "seller",
	})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("wrong code: want 401, got %d %s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "seller account") {
		t.Fatalf("wrong-code response must not mention account existence: %s", rec.Body.String())
	}
	if n := phoneUserCount(t, phone, models.RoleSeller); n != 0 {
		t.Fatalf("wrong code must not create a user; found %d", n)
	}
	// Lockout bookkeeping still ran: the start row survives with a failed attempt.
	var failed int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT failed_attempts FROM phone_otp_starts WHERE phone = $1`, phone).Scan(&failed); err != nil {
		t.Fatalf("phone_otp_starts row should survive a wrong code: %v", err)
	}
	if failed != 1 {
		t.Fatalf("failed_attempts: want 1, got %d", failed)
	}
}

// (b) consumer + unknown phone: still auto-creates (intended onboarding).
func TestPhoneVerify_ConsumerUnknownPhoneStillCreated(t *testing.T) {
	harness.resetVolatile(t)
	const phone = "+13475550203"
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(context.Background(), `DELETE FROM users WHERE phone = $1`, phone)
	})

	startPhoneOTP(t, phone)
	rec := harness.do(http.MethodPost, "/api/v1/auth/phone/verify", "", map[string]any{
		"phone": phone, "code": "1234", "role": "consumer", "first_name": "Phoney",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("consumer verify for unknown phone: want 200, got %d %s", rec.Code, rec.Body.String())
	}
	var resp AuthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode auth response: %v", err)
	}
	if resp.Token == "" || resp.User.Role != models.RoleConsumer || resp.User.Phone != phone {
		t.Fatalf("unexpected auth response: token=%q role=%s phone=%s", resp.Token, resp.User.Role, resp.User.Phone)
	}
	if n := phoneUserCount(t, phone, models.RoleConsumer); n != 1 {
		t.Fatalf("consumer verify should create exactly one user; found %d", n)
	}
}

// (c) seller + existing phone: signs in the existing seller.
func TestPhoneVerify_SellerExistingPhoneSignsIn(t *testing.T) {
	harness.resetVolatile(t)
	const phone = "+13475550204"
	ctx := context.Background()

	var sellerID string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Existing', 'Seller', $2, 'seller', 'kosher') RETURNING id`,
		uniqueEmail("phone-seller"), phone).Scan(&sellerID); err != nil {
		t.Fatalf("insert seller fixture: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, sellerID)
	})

	startPhoneOTP(t, phone)
	rec := harness.do(http.MethodPost, "/api/v1/auth/phone/verify", "", map[string]any{
		"phone": phone, "code": "1234", "role": "seller",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("seller verify for existing phone: want 200, got %d %s", rec.Code, rec.Body.String())
	}
	var resp AuthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode auth response: %v", err)
	}
	if resp.User.ID != sellerID {
		t.Fatalf("signed in as %s, want existing seller %s", resp.User.ID, sellerID)
	}
	if resp.User.Role != models.RoleSeller || resp.Token == "" || resp.RefreshToken == "" {
		t.Fatalf("unexpected auth response: role=%s token=%q refresh=%q", resp.User.Role, resp.Token, resp.RefreshToken)
	}
	if !resp.User.PhoneVerified {
		t.Fatal("phone login should stamp phone_verified on the existing seller")
	}
	if n := phoneUserCount(t, phone, models.RoleSeller); n != 1 {
		t.Fatalf("existing-seller sign-in must not create a second row; found %d", n)
	}
}
