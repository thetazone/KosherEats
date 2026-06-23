package middleware

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"golang.org/x/time/rate"
)

// okHandler is a trivial 200 handler used as the "next" in middleware tests.
var okHandler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
})

// TestRedisNilFallsBackToInMemory verifies that passing a nil client makes
// NewRedisRateLimiter behave exactly like the in-memory limiter: it allows up
// to `burst` requests then 429s.
func TestRedisNilFallsBackToInMemory(t *testing.T) {
	rl := NewRedisRateLimiter(nil, rate.Limit(0.0001), 3, time.Minute)
	h := rl.PerIP(okHandler)

	allowed := 0
	for i := 0; i < 10; i++ {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = "1.2.3.4:5555"
		h.ServeHTTP(rec, req)
		if rec.Code == http.StatusOK {
			allowed++
		}
	}
	if allowed != 3 {
		t.Fatalf("nil-client limiter: expected burst=3 allowed, got %d", allowed)
	}
}

// TestUnreachableRedisFailsOpenToInMemory points the limiter at a dead Redis
// address. Every allow() call should error on Redis and fall back to the
// in-memory bucket, so the limiter still enforces the burst (never rejects
// everything, never allows everything).
func TestUnreachableRedisFailsOpenToInMemory(t *testing.T) {
	dead := redis.NewClient(&redis.Options{Addr: "127.0.0.1:1", DialTimeout: 50 * time.Millisecond})
	defer dead.Close()

	rl := NewRedisRateLimiter(dead, rate.Limit(0.0001), 3, time.Minute)
	h := rl.PerIP(okHandler)

	allowed := 0
	for i := 0; i < 10; i++ {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = "5.6.7.8:9999"
		h.ServeHTTP(rec, req)
		if rec.Code == http.StatusOK {
			allowed++
		}
	}
	if allowed != 3 {
		t.Fatalf("unreachable-redis limiter: expected fail-open in-memory burst=3, got %d", allowed)
	}
}

// TestRedisBackedEnforcesWindow runs against a real Redis if one is reachable
// at localhost:6379 (skipped otherwise). It proves the shared-counter path
// actually limits, and survives a "restart" (a fresh limiter sharing the same
// Redis still sees the count).
func TestRedisBackedEnforcesWindow(t *testing.T) {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379", DialTimeout: 200 * time.Millisecond})
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		t.Skip("redis not reachable at localhost:6379; skipping live test")
	}

	// Long window + a unique key so the test is deterministic and isolated.
	key := "rl:1.1.1.1"
	client.Del(context.Background(), key)
	defer client.Del(context.Background(), key)

	// rate.Limit chosen so window = ceil(max/rate) is large (won't roll over
	// mid-test): max=3, rate=0.01 -> window=300s.
	rl := NewRedisRateLimiter(client, rate.Limit(0.01), 3, time.Minute)
	h := rl.PerIP(okHandler)

	hit := func(l *RateLimiter) int {
		allowed := 0
		hh := l.PerIP(okHandler)
		for i := 0; i < 5; i++ {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.RemoteAddr = "1.1.1.1:1234"
			hh.ServeHTTP(rec, req)
			if rec.Code == http.StatusOK {
				allowed++
			}
		}
		return allowed
	}
	_ = h

	first := hit(rl)
	if first != 3 {
		t.Fatalf("redis limiter: expected 3 allowed in window, got %d", first)
	}

	// Simulate a process restart: a brand-new limiter sharing the same Redis
	// must see the existing counter (already at max) and allow nothing more.
	rl2 := NewRedisRateLimiter(client, rate.Limit(0.01), 3, time.Minute)
	if again := hit(rl2); again != 0 {
		t.Fatalf("redis limiter across restart: expected 0 additional allowed, got %d", again)
	}
}
