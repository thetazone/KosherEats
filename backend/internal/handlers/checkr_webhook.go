package handlers

import (
	"net/http"

	"github.com/koshereats/backend/internal/background"
)

// CheckrWebhook receives report.completed events from Checkr. In prod you'd
// verify a signature header; for now we accept anything (dev + behind a
// reverse proxy URL that only Checkr knows). Flips the matching courier's
// onboarding_status based on the report result.
func (h *Handler) CheckrWebhook(w http.ResponseWriter, r *http.Request) {
	var payload background.WebhookPayload
	if err := readJSON(r, &payload); err != nil {
		w.WriteHeader(http.StatusOK) // never fail open to Checkr
		return
	}
	_ = h.checkr.HandleWebhook(r.Context(), payload)
	w.WriteHeader(http.StatusOK)
}
