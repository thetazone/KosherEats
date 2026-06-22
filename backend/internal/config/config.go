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

	// ReviewerSecret gates the App Store reviewer backdoor endpoint.
	// Empty → endpoint disabled entirely.
	ReviewerSecret string

	// Uber Direct (fallback courier dispatch). Empty → stub mode.
	UberDirectClientID     string
	UberDirectClientSecret string
	UberDirectCustomerID   string
	UberDirectWebhookSec   string

	// DoorDash Drive (second fallback courier). Empty → stub mode.
	DoorDashDeveloperID string
	DoorDashKeyID       string
	DoorDashSigningKey  string
	DoorDashWebhookSec  string

	// Tax rate as a whole-number percentage (e.g. 9 = 9%). Defaults to 9
	// if TAX_RATE_PERCENT is not set.
	TaxRatePercent int

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
}

func Load() *Config {
	return &Config{
		Port:             getEnv("PORT", "8080"),
		DatabaseURL:      getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/koshereats?sslmode=disable"),
		RedisURL:         getEnv("REDIS_URL", "redis://localhost:6379"),
		JWTSecret:        getEnv("JWT_SECRET", ""),
		StripeSecretKey:      getEnv("STRIPE_SECRET_KEY", ""),
		StripePublishableKey: getEnv("STRIPE_PUBLISHABLE_KEY", ""),
		StripeWebhookSec:     getEnv("STRIPE_WEBHOOK_SECRET", ""),
		WebURL:           getEnv("WEB_URL", "http://localhost:3000"),

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

		ReviewerSecret: getEnv("REVIEWER_SECRET", ""),

		UberDirectClientID:     getEnv("UBER_DIRECT_CLIENT_ID", ""),
		UberDirectClientSecret: getEnv("UBER_DIRECT_CLIENT_SECRET", ""),
		UberDirectCustomerID:   getEnv("UBER_DIRECT_CUSTOMER_ID", ""),
		UberDirectWebhookSec:   getEnv("UBER_DIRECT_WEBHOOK_SECRET", ""),

		DoorDashDeveloperID: getEnv("DOORDASH_DEVELOPER_ID", ""),
		DoorDashKeyID:       getEnv("DOORDASH_KEY_ID", ""),
		DoorDashSigningKey:  getEnv("DOORDASH_SIGNING_KEY", ""),
		DoorDashWebhookSec:  getEnv("DOORDASH_WEBHOOK_SECRET", ""),

		TaxRatePercent: getEnvInt("TAX_RATE_PERCENT", 9),

		Temporal: TemporalConfig{
			HostPort:  getEnv("TEMPORAL_HOSTPORT", ""),
			Namespace: getEnv("TEMPORAL_NAMESPACE", "default"),
			TaskQueue: getEnv("TEMPORAL_TASK_QUEUE", "payout-task-queue"),
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
