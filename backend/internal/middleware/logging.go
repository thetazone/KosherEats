// Package middleware hosts the hardening middlewares wired into main.go:
// structured request logging, per-IP + per-user rate limiting.
package middleware

import (
	"net"
	"net/http"
	"time"

	"log/slog"
)

// statusRecorder wraps ResponseWriter to capture the status code for logging.
type statusRecorder struct {
	http.ResponseWriter
	status int
	size   int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	n, err := s.ResponseWriter.Write(b)
	s.size += n
	return n, err
}

// RequestLogger is a structured logger middleware. Logs one line per request
// with method, path, status, duration, and (if JWT middleware has run) the
// authenticated user id. Uses slog so ops tooling can parse it as JSON in
// prod and pretty-print it in dev.
func RequestLogger(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w}
			next.ServeHTTP(rec, r)

			// Pull user id from context if auth middleware set it. We don't
			// import the handlers package (would create a cycle), so we
			// read the context key directly by string.
			userID := ""
			if v := r.Context().Value(contextKey("user")); v != nil {
				if m, ok := v.(map[string]string); ok {
					userID = m["user_id"]
				}
			}

			logger.Info("request",
				slog.String("method", r.Method),
				slog.String("path", r.URL.Path),
				slog.Int("status", rec.status),
				slog.Int("bytes", rec.size),
				slog.Duration("duration", time.Since(start)),
				slog.String("ip", clientIP(r)),
				slog.String("user_id", userID),
			)
		})
	}
}

// contextKey mirrors the private type in handlers.AuthMiddleware so we can
// read the user map from context without importing the handlers package
// (would create an import cycle).
type contextKey string

// clientIP extracts the best-guess client IP from X-Forwarded-For (when
// behind a proxy) or falls back to RemoteAddr with the port stripped.
// The port strip is critical — without it, rate limiter keys would be
// unique per connection (since each new TCP connection has a new
// ephemeral port), completely defeating the limiter.
func clientIP(r *http.Request) string {
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		// X-Forwarded-For can be a comma-separated list; take the first.
		for i := 0; i < len(fwd); i++ {
			if fwd[i] == ',' {
				return fwd[:i]
			}
		}
		return fwd
	}
	// r.RemoteAddr is "ip:port" (IPv4) or "[::1]:port" (IPv6).
	// net.SplitHostPort handles both forms correctly.
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr // no port, use as-is
	}
	return host
}
