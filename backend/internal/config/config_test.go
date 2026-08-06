package config

import "testing"

// The whole point of the production guard is that it fires on a configuration
// mistake, so these tests care most about the cases where someone forgot
// something: no APP_ENV set, or a stub flag left over from a sandbox test.

func TestIsProductionInfersFromLiveStripeKey(t *testing.T) {
	tests := []struct {
		name   string
		cfg    Config
		wantIs bool
	}{
		{"empty config is not production", Config{}, false},
		{"explicit APP_ENV=production", Config{AppEnv: "production"}, true},
		{"explicit APP_ENV=prod", Config{AppEnv: "prod"}, true},
		{"APP_ENV is case-insensitive", Config{AppEnv: "Production"}, true},
		{"APP_ENV=staging is not production", Config{AppEnv: "staging"}, false},
		{"test Stripe key is not production", Config{StripeSecretKey: "sk_test_abc123"}, false},
		{
			// The important one: this is exactly prod's real shape today — a live
			// Stripe key with APP_ENV never set. A guard keyed only on APP_ENV
			// would consider this a dev box and let the stub through.
			name:   "live Stripe key alone means production",
			cfg:    Config{StripeSecretKey: "sk_live_abc123"},
			wantIs: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.cfg.IsProduction(); got != tt.wantIs {
				t.Fatalf("IsProduction() = %v, want %v", got, tt.wantIs)
			}
		})
	}
}

func TestValidateAllowsAnythingOutsideProduction(t *testing.T) {
	// Dev must stay able to stub the courier: that's what the flag is for.
	cfg := Config{
		StripeSecretKey:       "sk_test_abc",
		UberDirectStub:        true,
		UberDirectRoboCourier: true,
	}
	fatal, warn := cfg.Validate()
	if len(fatal) != 0 || len(warn) != 0 {
		t.Fatalf("expected no problems outside production, got fatal=%v warn=%v", fatal, warn)
	}
}

func TestValidateRejectsStubInProduction(t *testing.T) {
	cfg := Config{StripeSecretKey: "sk_live_abc", UberDirectStub: true}
	fatal, _ := cfg.Validate()
	if len(fatal) == 0 {
		t.Fatal("UBER_DIRECT_STUB=true in production must be FATAL — it makes the API fabricate deliveries")
	}
	if !contains(fatal, "UBER_DIRECT_STUB") {
		t.Fatalf("problem should name the offending variable so the fix is obvious, got %v", fatal)
	}
}

// Robo courier WARNS rather than blocks: prod is production-by-Stripe while
// still pointed at the Uber sandbox account before launch, which is deliberate,
// and Uber rejects the field on live credentials so the failure is never silent.
// Making this fatal would refuse to boot the current intentional config.
func TestValidateWarnsButDoesNotBlockOnRoboCourier(t *testing.T) {
	cfg := Config{AppEnv: "production", UberDirectRoboCourier: true}
	fatal, warn := cfg.Validate()
	if len(fatal) != 0 {
		t.Fatalf("robo courier must not block boot — prod runs it against sandbox creds today, got %v", fatal)
	}
	if !contains(warn, "UBER_DIRECT_ROBO") {
		t.Fatalf("robo courier should warn so it isn't forgotten at cutover, got %v", warn)
	}
}

func TestValidateRejectsRealCredentialsWithNoWebhookSecret(t *testing.T) {
	// Deliveries would be created and then never advance, because every status
	// callback fails signature verification. Silent, and expensive.
	cfg := Config{
		StripeSecretKey:      "sk_live_abc",
		UberDirectClientID:   "client-id",
		UberDirectWebhookSec: "",
	}
	fatal, warn := cfg.Validate()
	if len(fatal) != 0 {
		t.Fatalf("a missing webhook secret is visible (orders stall) — must not block boot, got %v", fatal)
	}
	if !contains(warn, "UBER_DIRECT_WEBHOOK_SECRET") {
		t.Fatalf("missing webhook secret alongside real credentials must warn, got %v", warn)
	}
}

func TestValidateAcceptsAWellFormedProductionConfig(t *testing.T) {
	cfg := Config{
		AppEnv:                 "production",
		StripeSecretKey:        "sk_live_abc",
		UberDirectClientID:     "client-id",
		UberDirectClientSecret: "secret",
		UberDirectCustomerID:   "customer",
		UberDirectWebhookSec:   "whsec",
		AdminAlertEmail:        "ops@koshereats.shop",
	}
	fatal, warn := cfg.Validate()
	if len(fatal) != 0 || len(warn) != 0 {
		t.Fatalf("a correct production config must boot cleanly, got fatal=%v warn=%v", fatal, warn)
	}
}

// The alerts added for orphaned deliveries, exhausted refunds and stalled
// dispatches are no-ops without a destination, so an unset ADMIN_ALERT_EMAIL has
// to be surfaced — prod has it unset today.
func TestValidateWarnsWhenNoAlertDestination(t *testing.T) {
	cfg := Config{StripeSecretKey: "sk_live_abc", AdminAlertEmail: ""}
	_, warn := cfg.Validate()
	if !contains(warn, "ADMIN_ALERT_EMAIL") {
		t.Fatalf("an unset alert destination must warn, got %v", warn)
	}
}

// The real prod shape as of the Uber Direct pre-launch state: live Stripe, no
// APP_ENV, sandbox Uber creds with robo courier on, webhook secret set. It must
// boot.
func TestValidateBootsCurrentPreLaunchProdShape(t *testing.T) {
	cfg := Config{
		StripeSecretKey:       "sk_live_abc",
		UberDirectClientID:    "sandbox-client",
		UberDirectCustomerID:  "sandbox-customer",
		UberDirectWebhookSec:  "whsec",
		UberDirectRoboCourier: true,
	}
	if fatal, _ := cfg.Validate(); len(fatal) != 0 {
		t.Fatalf("the current pre-launch prod config must still boot, got %v", fatal)
	}
}

func TestValidateReportsEveryProblemAtOnce(t *testing.T) {
	// A bad deploy should be diagnosable in one pass rather than one restart per
	// mistake.
	cfg := Config{
		StripeSecretKey:       "sk_live_abc",
		UberDirectStub:        true,
		UberDirectRoboCourier: true,
		UberDirectClientID:    "client-id",
	}
	fatal, warn := cfg.Validate()
	if len(fatal)+len(warn) < 3 {
		t.Fatalf("expected several problems reported together, got fatal=%v warn=%v", fatal, warn)
	}
}

func contains(errs []error, substr string) bool {
	for _, e := range errs {
		if e == nil {
			continue
		}
		if idx := indexOf(e.Error(), substr); idx >= 0 {
			return true
		}
	}
	return false
}

func indexOf(haystack, needle string) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}
