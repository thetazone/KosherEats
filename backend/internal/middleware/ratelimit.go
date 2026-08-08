package middleware

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"sync"
	"time"

	"github.com/koshereats/backend/internal/ctxkeys"
	"github.com/redis/go-redis/v9"
	"golang.org/x/time/rate"
)

// RateLimiter is a per-key rate limiter. Key is typically the client IP for
// anonymous endpoints or the authenticated user id for logged-in ones.
//
// By default it is a purely in-memory token-bucket: each key gets its own
// golang.org/x/time/rate limiter, and buckets for inactive clients are
// garbage-collected so memory doesn't grow unbounded.
//
// When constructed with NewRedisRateLimiter it additionally consults Redis
// using a shared fixed-window (INCR + EXPIRE) counter, so limits survive
// restarts and are enforced across every API instance. Redis is strictly an
// upgrade: any Redis error (unreachable, timeout, misconfig) falls back to the
// in-memory token bucket for that request — we never block a request because
// Redis is down (fail-open to in-memory).
type RateLimiter struct {
	visitors map[string]*visitor
	mu       sync.Mutex
	rate     rate.Limit
	burst    int
	ttl      time.Duration

	// redis is nil for the default in-memory limiter. When non-nil, allow()
	// tries Redis first and falls back to the in-memory bucket on any error.
	redis *redis.Client
	// window/max define the fixed window used for the Redis counter, derived
	// from rate/burst so the Redis path approximates the token bucket: at most
	// `max` requests per `window`.
	window time.Duration
	max    int
	// redisPrefix namespaces the Redis counter per limiter instance so
	// limiters with different max/window never share a counter. Empty for
	// the in-memory-only limiter (set in NewRedisRateLimiter).
	redisPrefix string
}

type visitor struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// NewRateLimiter creates an in-memory limiter that allows `r` requests per
// second with a burst of `b`. Visitors idle for longer than `ttl` are GC'd.
func NewRateLimiter(r rate.Limit, b int, ttl time.Duration) *RateLimiter {
	rl := &RateLimiter{
		visitors: map[string]*visitor{},
		rate:     r,
		burst:    b,
		ttl:      ttl,
	}
	go rl.cleanupLoop()
	return rl
}

// NewRedisRateLimiter creates a limiter with the same per-second/burst/ttl
// semantics as NewRateLimiter, but backed by a shared Redis fixed-window
// counter so limits are enforced across instances and survive restarts.
//
// If client is nil it is exactly equivalent to NewRateLimiter — callers can
// pass the (possibly nil) shared client unconditionally. The in-memory bucket
// is always built too and is used as the fail-open fallback whenever a Redis
// command errors.
func NewRedisRateLimiter(client *redis.Client, r rate.Limit, b int, ttl time.Duration) *RateLimiter {
	rl := NewRateLimiter(r, b, ttl)
	if client == nil {
		return rl
	}
	rl.redis = client
	// Map the token bucket (r req/s, burst b) onto a fixed window: allow `b`
	// requests per `b/r` seconds. This matches the steady-state throughput of
	// the bucket (r req/s) while permitting the configured burst at the start
	// of each window. Guard against r<=0 and b<1.
	rl.max = b
	if rl.max < 1 {
		rl.max = 1
	}
	if r > 0 {
		secs := float64(rl.max) / float64(r)
		rl.window = time.Duration(math.Ceil(secs)) * time.Second
	}
	if rl.window <= 0 {
		rl.window = time.Second
	}
	rl.redisPrefix = fmt.Sprintf("rl:%d:%d:", rl.max, int(rl.window.Seconds()))
	return rl
}

func (rl *RateLimiter) cleanupLoop() {
	t := time.NewTicker(5 * time.Minute)
	defer t.Stop()
	for range t.C {
		rl.mu.Lock()
		now := time.Now()
		for k, v := range rl.visitors {
			if now.Sub(v.lastSeen) > rl.ttl {
				delete(rl.visitors, k)
			}
		}
		rl.mu.Unlock()
	}
}

// allow decides whether a request keyed by `key` may proceed. When Redis is
// configured it is consulted first; on any Redis error we fail open to the
// in-memory token bucket so a Redis outage can never reject traffic.
func (rl *RateLimiter) allow(key string) bool {
	if rl.redis != nil {
		if ok, err := rl.allowRedis(key); err == nil {
			return ok
		}
		// Redis errored — fall through to the in-memory bucket (fail-open).
	}
	return rl.allowLocal(key)
}

// allowLocal is the original in-memory per-key token bucket.
func (rl *RateLimiter) allowLocal(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	v, ok := rl.visitors[key]
	if !ok {
		v = &visitor{limiter: rate.NewLimiter(rl.rate, rl.burst)}
		rl.visitors[key] = v
	}
	v.lastSeen = time.Now()
	return v.limiter.Allow()
}

// allowRedis enforces a shared fixed-window counter: INCR the per-key counter
// and, on the first request of a window, set EXPIRE to the window length. The
// request is allowed while the counter is within `max`. Returns an error
// (rather than a decision) on any Redis failure so the caller can fall back.
func (rl *RateLimiter) allowRedis(key string) (bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	rkey := rl.redisPrefix + key
	count, err := rl.redis.Incr(ctx, rkey).Result()
	if err != nil {
		return false, err
	}
	// Arm the TTL whenever the key has none (ExpireNX = EXPIRE ... NX). This
	// covers the first hit of a window AND self-heals a key orphaned by a
	// prior failed EXPIRE, so a TTL-less counter can never climb forever and
	// permanently 429 the key. A key that already has a TTL is left untouched.
	// A failed EXPIRE is treated as a Redis error so the caller fails open.
	if err := rl.redis.ExpireNX(ctx, rkey, rl.window).Err(); err != nil {
		return false, err
	}
	return count <= int64(rl.max), nil
}

// PerIP returns a handler middleware that limits requests by client IP.
// Used on /auth/* endpoints to slow down credential stuffing.
func (rl *RateLimiter) PerIP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !rl.allow(clientIP(r)) {
			http.Error(w, `{"error":"rate limit exceeded"}`, http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// PerUser returns a handler middleware that limits by authenticated user id,
// falling back to IP if unauthenticated. Used on general authenticated
// endpoints to catch runaway clients.
func (rl *RateLimiter) PerUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := clientIP(r)
		if v := r.Context().Value(ctxkeys.UserKey); v != nil {
			if m, ok := v.(map[string]string); ok && m["user_id"] != "" {
				key = "u:" + m["user_id"]
			}
		}
		if !rl.allow(key) {
			http.Error(w, `{"error":"rate limit exceeded"}`, http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}
