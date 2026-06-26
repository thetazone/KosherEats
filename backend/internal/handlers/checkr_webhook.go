package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"

	"github.com/koshereats/backend/internal/background"
)

func (h *Handler) CheckrWebhook(w http.ResponseWriter, r *http.Request) {
	if h.cfg.CheckrWebhookSec == "" {
		slog.Error("checkr webhook signing secret is not configured")
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	{
		sig := r.Header.Get("X-Checkr-Signature")
		mac := hmac.New(sha256.New, []byte(h.cfg.CheckrWebhookSec))
		mac.Write(body)
		expected := hex.EncodeToString(mac.Sum(nil))
		if !hmac.Equal([]byte(sig), []byte(expected)) {
			slog.Warn("checkr webhook signature verification failed")
			writeError(w, http.StatusBadRequest, "invalid signature")
			return
		}
	}

	var payload background.WebhookPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	ctx := r.Context()
	eventID := webhookEventID(body)

	// Idempotency: skip an already-processed event. HandleWebhook runs on its own
	// connection (not a tx we control), so we record AFTER it succeeds rather than
	// claiming up front — a failed attempt leaves no ledger row, so Checkr's retry
	// reprocesses instead of being permanently dropped. A ledger-read failure
	// falls through to processing (HandleWebhook is idempotent) rather than block.
	var seen bool
	if err := h.db.Pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM external_webhook_events WHERE provider = 'checkr' AND event_id = $1)`,
		eventID).Scan(&seen); err != nil {
		slog.Warn("checkr webhook: idempotency check failed, processing anyway",
			slog.String("error", err.Error()))
	}
	if seen {
		w.WriteHeader(http.StatusOK)
		return
	}

	// Background checks gate who is allowed to drive — a dropped 'report.completed'
	// leaves a courier's clearance state wrong. Surface processing failures as 5xx
	// so Checkr retries instead of silently giving up (it swallowed the error and
	// always 200'd before).
	if err := h.checkr.HandleWebhook(ctx, payload); err != nil {
		slog.Error("checkr webhook: processing failed — returning 500 so Checkr retries",
			slog.String("type", payload.Type), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if _, err := h.db.Pool.Exec(ctx,
		`INSERT INTO external_webhook_events (provider, event_id, type)
		 VALUES ('checkr', $1, $2) ON CONFLICT (provider, event_id) DO NOTHING`,
		eventID, payload.Type); err != nil {
		// Non-fatal: the event was processed; a missing ledger row only risks one
		// idempotent reprocess on a retry. Log so it's visible if systematic.
		slog.Warn("checkr webhook: failed to record event for idempotency",
			slog.String("error", err.Error()))
	}
	w.WriteHeader(http.StatusOK)
}
