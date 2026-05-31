package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/koshereats/backend/internal/ctxkeys"
	"golang.org/x/time/rate"
)

// RateLimiter is a per-key token-bucket rate limiter. Key is typically the
// client IP for anonymous endpoints or the authenticated user id for
// logged-in ones.
//
// Buckets for inactive clients are garbage-collected every `gcInterval` so
// memory doesn't grow unbounded.
type RateLimiter struct {
	visitors map[string]*visitor
	mu       sync.Mutex
	rate     rate.Limit
	burst    int
	ttl      time.Duration
}

type visitor struct {
	limiter *rate.Limiter
	lastSeen time.Time
}

// NewRateLimiter creates a limiter that allows `r` requests per second with
// a burst of `b`. Visitors idle for longer than `ttl` are GC'd.
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

func (rl *RateLimiter) allow(key string) bool {
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
