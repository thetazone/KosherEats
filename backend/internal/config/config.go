package config

import "os"

type Config struct {
	Port             string
	DatabaseURL      string
	RedisURL         string
	JWTSecret        string
	StripeSecretKey  string
	StripeWebhookSec string
	WebURL           string
}

func Load() *Config {
	return &Config{
		Port:             getEnv("PORT", "8080"),
		DatabaseURL:      getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/koshereats?sslmode=disable"),
		RedisURL:         getEnv("REDIS_URL", "redis://localhost:6379"),
		JWTSecret:        getEnv("JWT_SECRET", "change-me-in-production"),
		StripeSecretKey:  getEnv("STRIPE_SECRET_KEY", ""),
		StripeWebhookSec: getEnv("STRIPE_WEBHOOK_SECRET", ""),
		WebURL:           getEnv("WEB_URL", "http://localhost:3000"),
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
