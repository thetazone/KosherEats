// Package redisclient builds a shared *redis.Client from a REDIS_URL.
//
// It is intentionally tiny: a single constructor that parses the URL and
// returns a client, plus a reachability ping. Everything that wants Redis
// (currently the distributed rate limiter) shares one client built in main.
//
// Degradation is the caller's job: New never blocks startup, and callers are
// expected to treat a nil client (or any later command error) as "Redis is
// unavailable, fall back to local behavior". We never want a flaky Redis to
// take the API down.
package redisclient

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

// New parses a redis:// (or rediss://) URL and returns a client. An empty url
// returns (nil, nil): Redis is simply not configured, which callers treat as
// "use the in-memory fallback". A malformed URL returns an error so misconfig
// is visible in logs, but callers should still degrade rather than crash.
func New(url string) (*redis.Client, error) {
	if url == "" {
		return nil, nil
	}
	opt, err := redis.ParseURL(url)
	if err != nil {
		return nil, err
	}
	// Fail fast on outages so a down Redis degrades to in-memory quickly
	// instead of adding retry latency to every rate-limited request. The
	// limiter already wraps each call in a short context timeout; these keep
	// the underlying dial/read budget small and disable client-side retries.
	if opt.DialTimeout == 0 {
		opt.DialTimeout = 200 * time.Millisecond
	}
	if opt.ReadTimeout == 0 {
		opt.ReadTimeout = 200 * time.Millisecond
	}
	if opt.WriteTimeout == 0 {
		opt.WriteTimeout = 200 * time.Millisecond
	}
	opt.MaxRetries = -1 // -1 disables retries (0 would mean "use default")
	return redis.NewClient(opt), nil
}

// Ping reports whether the client can reach Redis within a short timeout. It
// is used once at startup to decide whether to log "rate limiter: redis" vs
// "rate limiter: in-memory fallback"; a false result is not fatal because the
// limiter degrades per-request anyway. A nil client is reported unreachable.
func Ping(ctx context.Context, c *redis.Client) bool {
	if c == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	return c.Ping(ctx).Err() == nil
}
