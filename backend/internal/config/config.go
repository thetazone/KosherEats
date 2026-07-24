package config

import (
	"os"
	"strconv"
)

type Config struct {
	Port                 string
	DatabaseURL          string
	RedisURL             string
	JWTSecret            string
	StripeSecretKey      string
	StripePublishableKey string // sent to iOS clients; safe to expose
	StripeWebhookSec     string
	WebURL               string

	// OAuth
	GoogleClientID     string
	GoogleClientSecret string
	AppleClientID      string
	AppleTeamID        string
	AppleKeyID         string
	ApplePrivateKey    string

	// APNs (push notifications)
	APNsKeyID      string // Apple Developer key id (e.g. "ABC123DEFG")
	APNsTeamID     string // Apple team id
	APNsP8Key      string // PEM-encoded .p8 private key contents
	APNsBundlePfx  string // Bundle id prefix, e.g. "com.koshereats"
	APNsProduction bool   // false -> sandbox

	// Storage (S3 / S3-compatible for uploads: courier documents, restaurant
	// photos, kosher certificates). On Fly we use Tigris, which auto-injects
	// AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION / BUCKET_NAME /
	// AWS_ENDPOINT_URL_S3 when you run `fly storage create`. The SDK picks
	// up the access keys + region from env automatically; we read the bucket
	// + endpoint here.
	S3Bucket    string
	S3Region    string
	S3Endpoint  string // empty for AWS, set to https://fly.storage.tigris.dev for Tigris
	S3PublicURL string // optional CDN prefix (e.g. cloudfront). Computed from endpoint+bucket if empty.

	// Checkr (courier background checks)
	CheckrAPIKey     string
	CheckrPackage    string // e.g. "driver_pro". Package slug couriers get invited to.
	CheckrWebhookSec string

	// FCM (Android push notifications)
	// Service account JSON as a single string (the full file contents). The
	// FCM client parses this at startup to get the private key + client_email
	// for OAuth2 token minting. Leave empty in dev to run in stub mode (pushes
	// logged, not sent) — same pattern as APNs.
	FCMServiceAccountJSON string
	FCMProjectID          string // e.g. "koshereats-prod"; passed to the /v1/projects/{id}/messages:send URL

	// Twilio Verify (phone-number OTP login). Empty → dev stub mode
	// (accepts a fixed code, see internal/sms/twilio.go).
	TwilioAccountSID       string
	TwilioAuthToken        string
	TwilioVerifyServiceSID string

	// VerificationEnforced turns ON the hard side of consumer phone+email
	// verification: requiring an email-OTP proof at register and blocking
	// order/payment for unverified consumers. Default OFF so the backend can be
	// deployed (new endpoints available, apps can adopt the verification UI)
	// without breaking the old app builds still in users' hands. Flip to true
	// (a single `fly secrets set VERIFICATION_ENFORCED=true`) once the updated
	// apps are widely adopted — no code redeploy needed.
	VerificationEnforced bool

	// Uber Direct (fallback courier dispatch). Empty → stub mode.
	UberDirectClientID     string
	UberDirectClientSecret string
	UberDirectCustomerID   string
	UberDirectWebhookSec   string
	// Dev-only: force the Uber client to report Enabled() AND return canned stub
	// responses (no network call, no charge), so the dispatch happy-path can be
	// exercised locally without real credentials. NEVER set in production.
	UberDirectStub bool
	// Test-only: with TEST credentials, drive each Uber delivery via a simulated
	// auto-advancing courier (real API + real webhooks, no courier, no charge).
	// Lets the full lifecycle be tested against the live API. NEVER set in prod.
	UberDirectRoboCourier bool

	// DoorDash Drive (second fallback courier). Empty → stub mode.
	DoorDashDeveloperID string
	DoorDashKeyID       string
	DoorDashSigningKey  string
	DoorDashWebhookSec  string

	// Tax rate as a whole-number percentage (e.g. 9 = 9%). Defaults to 9
	// if TAX_RATE_PERCENT is not set.
	TaxRatePercent int

	// Delivery pricing: the consumer always pays the cheapest external courier
	// (Uber Direct / DoorDash) quote PLUS a flat markup we keep — no minimum, no
	// free delivery, no floor/ceiling. The fee always tracks the real provider
	// cost, tiered by item subtotal (excl. delivery): DeliveryMarkupCents up to
	// DeliveryLargeOrderCents, DeliveryMarkupLargeCents up to
	// DeliveryHighestOrderCents, DeliveryMarkupHighestCents above that.
	DeliveryMarkupCents        int
	DeliveryMarkupLargeCents   int
	DeliveryMarkupHighestCents int
	DeliveryLargeOrderCents    int
	DeliveryHighestOrderCents  int

	// StripeTaxEnabled flips order-tax computation from the flat TaxRatePercent
	// to the (currently stubbed) Stripe Tax integration point. Default false:
	// the flat-rate path is unchanged unless STRIPE_TAX_ENABLED=true. See
	// handlers.taxForOrder — wiring Stripe Tax also needs the connected Stripe
	// account to have Tax enabled, so this stays off until that's provisioned.
	StripeTaxEnabled bool

	// AdminAlertEmail receives anomaly alerts (charge disputes, refunds,
	// auto-refunds, permanently failed payouts). Empty (the default) makes
	// alertAdmin a logged no-op so dev/test never tries to send mail.
	AdminAlertEmail string

	// SentryDSN enables error reporting to Sentry. Empty (the default) makes
	// Sentry a complete no-op: no init, no network calls. Set SENTRY_DSN in
	// prod to capture handler panics + errors.
	SentryDSN string

	// Temporal (durable courier payout sweep). DISABLED unless HostPort is
	// non-empty: when empty we never dial a Temporal client, inject a nil
	// *payout.Starter, and the legacy direct-transfer sweep runs unchanged.
	Temporal TemporalConfig
}

// TemporalConfig holds the connection settings for the payout workflow worker.
// An empty HostPort means Temporal is disabled (the default).
type TemporalConfig struct {
	HostPort  string // TEMPORAL_HOSTPORT, e.g. "localhost:7233". Empty → disabled.
	Namespace string // TEMPORAL_NAMESPACE, default "default"
	TaskQueue string // TEMPORAL_TASK_QUEUE, default "payout-task-queue"
	// Cloud auth (leave all empty for a local/insecure dev server):
	APIKey  string // TEMPORAL_API_KEY — Temporal Cloud API key (enables TLS). Preferred.
	TLSCert string // TEMPORAL_TLS_CERT — path to client cert for mTLS (alternative to API key)
	TLSKey  string // TEMPORAL_TLS_KEY — path to client key for mTLS
}

func Load() *Config {
	return &Config{
		Port:                 getEnv("PORT", "8080"),
		DatabaseURL:          getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/koshereats?sslmode=disable"),
		RedisURL:             getEnv("REDIS_URL", "redis://localhost:6379"),
		JWTSecret:            getEnv("JWT_SECRET", ""),
		StripeSecretKey:      getEnv("STRIPE_SECRET_KEY", ""),
		StripePublishableKey: getEnv("STRIPE_PUBLISHABLE_KEY", ""),
		StripeWebhookSec:     getEnv("STRIPE_WEBHOOK_SECRET", ""),
		WebURL:               getEnv("WEB_URL", "http://localhost:3000"),

		GoogleClientID:     getEnv("GOOGLE_CLIENT_ID", ""),
		GoogleClientSecret: getEnv("GOOGLE_CLIENT_SECRET", ""),
		AppleClientID:      getEnv("APPLE_CLIENT_ID", ""),
		AppleTeamID:        getEnv("APPLE_TEAM_ID", ""),
		AppleKeyID:         getEnv("APPLE_KEY_ID", ""),
		ApplePrivateKey:    getEnv("APPLE_PRIVATE_KEY", ""),

		APNsKeyID:      getEnv("APNS_KEY_ID", ""),
		APNsTeamID:     getEnv("APNS_TEAM_ID", ""),
		APNsP8Key:      getEnv("APNS_P8_KEY", ""),
		APNsBundlePfx:  getEnv("APNS_BUNDLE_PREFIX", "com.koshereats"),
		APNsProduction: getEnv("APNS_PRODUCTION", "") == "true",

		// Prefer Fly's auto-injected names (BUCKET_NAME, AWS_REGION,
		// AWS_ENDPOINT_URL_S3 from `fly storage create`) and fall back to
		// our legacy S3_* names so existing AWS-direct configs keep working.
		S3Bucket:    firstNonEmpty(getEnv("BUCKET_NAME", ""), getEnv("S3_BUCKET", "")),
		S3Region:    firstNonEmpty(getEnv("AWS_REGION", ""), getEnv("S3_REGION", "us-east-1")),
		S3Endpoint:  firstNonEmpty(getEnv("AWS_ENDPOINT_URL_S3", ""), getEnv("S3_ENDPOINT", "")),
		S3PublicURL: getEnv("S3_PUBLIC_URL", ""),

		CheckrAPIKey:     getEnv("CHECKR_API_KEY", ""),
		CheckrPackage:    getEnv("CHECKR_PACKAGE", "driver_pro"),
		CheckrWebhookSec: getEnv("CHECKR_WEBHOOK_SECRET", ""),

		FCMServiceAccountJSON: getEnv("FCM_SERVICE_ACCOUNT_JSON", ""),
		FCMProjectID:          getEnv("FCM_PROJECT_ID", ""),

		TwilioAccountSID:       getEnv("TWILIO_ACCOUNT_SID", ""),
		TwilioAuthToken:        getEnv("TWILIO_AUTH_TOKEN", ""),
		TwilioVerifyServiceSID: getEnv("TWILIO_VERIFY_SERVICE_SID", ""),

		VerificationEnforced: getEnvBool("VERIFICATION_ENFORCED", false),

		UberDirectClientID:     getEnv("UBER_DIRECT_CLIENT_ID", ""),
		UberDirectClientSecret: getEnv("UBER_DIRECT_CLIENT_SECRET", ""),
		UberDirectCustomerID:   getEnv("UBER_DIRECT_CUSTOMER_ID", ""),
		UberDirectWebhookSec:   getEnv("UBER_DIRECT_WEBHOOK_SECRET", ""),
		UberDirectStub:         getEnv("UBER_DIRECT_STUB", "") == "true",
		UberDirectRoboCourier:  getEnv("UBER_DIRECT_ROBO", "") == "true",

		DoorDashDeveloperID: getEnv("DOORDASH_DEVELOPER_ID", ""),
		DoorDashKeyID:       getEnv("DOORDASH_KEY_ID", ""),
		DoorDashSigningKey:  getEnv("DOORDASH_SIGNING_KEY", ""),
		DoorDashWebhookSec:  getEnv("DOORDASH_WEBHOOK_SECRET", ""),

		TaxRatePercent:   getEnvInt("TAX_RATE_PERCENT", 9),

		DeliveryMarkupCents:        getEnvInt("DELIVERY_MARKUP_CENTS", 100),
		DeliveryMarkupLargeCents:   getEnvInt("DELIVERY_MARKUP_LARGE_CENTS", 200),
		DeliveryMarkupHighestCents: getEnvInt("DELIVERY_MARKUP_HIGHEST_CENTS", 300),
		DeliveryLargeOrderCents:    getEnvInt("DELIVERY_LARGE_ORDER_CENTS", 4000),
		DeliveryHighestOrderCents:  getEnvInt("DELIVERY_HIGHEST_ORDER_CENTS", 8000),
		StripeTaxEnabled: getEnv("STRIPE_TAX_ENABLED", "") == "true",
		AdminAlertEmail:  getEnv("ADMIN_ALERT_EMAIL", ""),

		SentryDSN: getEnv("SENTRY_DSN", ""),

		Temporal: TemporalConfig{
			HostPort:  getEnv("TEMPORAL_HOSTPORT", ""),
			Namespace: getEnv("TEMPORAL_NAMESPACE", "default"),
			TaskQueue: getEnv("TEMPORAL_TASK_QUEUE", "payout-task-queue"),
			APIKey:    getEnv("TEMPORAL_API_KEY", ""),
			TLSCert:   getEnv("TEMPORAL_TLS_CERT", ""),
			TLSKey:    getEnv("TEMPORAL_TLS_KEY", ""),
		},
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func getEnvInt(key string, fallback int) int {
	if val := os.Getenv(key); val != "" {
		if n, err := strconv.Atoi(val); err == nil {
			return n
		}
	}
	return fallback
}

func getEnvBool(key string, fallback bool) bool {
	if val := os.Getenv(key); val != "" {
		if b, err := strconv.ParseBool(val); err == nil {
			return b
		}
	}
	return fallback
}
