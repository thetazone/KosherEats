package config

import "os"

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
	FacebookAppID      string
	FacebookAppSecret  string

	// APNs (push notifications)
	APNsKeyID      string // Apple Developer key id (e.g. "ABC123DEFG")
	APNsTeamID     string // Apple team id
	APNsP8Key      string // PEM-encoded .p8 private key contents
	APNsBundlePfx  string // Bundle id prefix, e.g. "com.koshereats"
	APNsProduction bool   // false -> sandbox

	// Storage (S3 for uploads: courier documents, restaurant photos)
	S3Bucket    string
	S3Region    string
	S3PublicURL string // optional CDN prefix (e.g. cloudfront)

	// Checkr (courier background checks)
	CheckrAPIKey  string
	CheckrPackage string // e.g. "driver_pro". Package slug couriers get invited to.

	// FCM (Android push notifications)
	// Service account JSON as a single string (the full file contents). The
	// FCM client parses this at startup to get the private key + client_email
	// for OAuth2 token minting. Leave empty in dev to run in stub mode (pushes
	// logged, not sent) — same pattern as APNs.
	FCMServiceAccountJSON string
	FCMProjectID          string // e.g. "koshereats-prod"; passed to the /v1/projects/{id}/messages:send URL
}

func Load() *Config {
	return &Config{
		Port:             getEnv("PORT", "8080"),
		DatabaseURL:      getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/koshereats?sslmode=disable"),
		RedisURL:         getEnv("REDIS_URL", "redis://localhost:6379"),
		JWTSecret:        getEnv("JWT_SECRET", "change-me-in-production"),
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
		FacebookAppID:      getEnv("FACEBOOK_APP_ID", ""),
		FacebookAppSecret:  getEnv("FACEBOOK_APP_SECRET", ""),

		APNsKeyID:      getEnv("APNS_KEY_ID", ""),
		APNsTeamID:     getEnv("APNS_TEAM_ID", ""),
		APNsP8Key:      getEnv("APNS_P8_KEY", ""),
		APNsBundlePfx:  getEnv("APNS_BUNDLE_PREFIX", "com.koshereats"),
		APNsProduction: getEnv("APNS_PRODUCTION", "") == "true",

		S3Bucket:    getEnv("S3_BUCKET", ""),
		S3Region:    getEnv("S3_REGION", "us-east-1"),
		S3PublicURL: getEnv("S3_PUBLIC_URL", ""),

		CheckrAPIKey:  getEnv("CHECKR_API_KEY", ""),
		CheckrPackage: getEnv("CHECKR_PACKAGE", "driver_pro"),

		FCMServiceAccountJSON: getEnv("FCM_SERVICE_ACCOUNT_JSON", ""),
		FCMProjectID:          getEnv("FCM_PROJECT_ID", ""),
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
