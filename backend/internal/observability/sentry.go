// Package observability wires optional error reporting (Sentry). Everything
// here is a no-op unless a DSN is configured, so dev and test runs never reach
// out to the network.
package observability

import (
	"net/http"
	"time"

	"github.com/getsentry/sentry-go"
)

// InitSentry initializes the global Sentry hub when dsn is non-empty and
// returns a flush function to defer in main. When dsn is empty it returns a
// no-op flush and reports enabled=false — Sentry is completely inert.
//
// env is the deployment environment (APP_ENV), tagged on every event so
// production noise is separable from staging/dev.
func InitSentry(dsn, env string) (flush func(), enabled bool) {
	if dsn == "" {
		return func() {}, false
	}
	err := sentry.Init(sentry.ClientOptions{
		Dsn:         dsn,
		Environment: env,
		// Keep tracing cheap: sample a small fraction of transactions. Error
		// capture is unaffected by this — it controls performance traces only.
		TracesSampleRate: 0.05,
	})
	if err != nil {
		// Failing to init Sentry must never take the API down; degrade to
		// "no reporting" rather than crashing.
		return func() {}, false
	}
	return func() { sentry.Flush(2 * time.Second) }, true
}

// SentryRecover is an HTTP middleware that captures any panic from a downstream
// handler to Sentry and then re-panics so the existing chi Recoverer still
// turns it into a 500. When Sentry is not initialized, CaptureException is a
// cheap no-op, so this is safe to install unconditionally.
//
// Install it INSIDE chi's Recoverer (i.e. add it after r.Use(Recoverer)) so the
// panic is reported here and the Recoverer one frame out writes the response.
func SentryRecover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				hub := sentry.GetHubFromContext(r.Context())
				if hub == nil {
					hub = sentry.CurrentHub().Clone()
				}
				hub.RecoverWithContext(r.Context(), rec)
				// Re-panic so chi's Recoverer produces the 500 response and
				// logs as it does today — behavior-preserving for callers.
				panic(rec)
			}
		}()
		next.ServeHTTP(w, r)
	})
}
