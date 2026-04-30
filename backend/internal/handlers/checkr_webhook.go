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
	_ = h.checkr.HandleWebhook(r.Context(), payload)
	w.WriteHeader(http.StatusOK)
}
