package handlers

import (
	"log/slog"
	"net/http"
)

// Stripe Connect payout onboarding for couriers.
//
// Flow (mirrors UberEats / DoorDash direct deposit setup):
//   1. Courier taps "Set up payouts" in the iOS app.
//   2. POST /courier/payouts/account — we create a Stripe Express account and
//      store stripe_connect_id on their courier_profile (if not already set).
//   3. GET /courier/payouts/link — we return a Stripe-hosted onboarding URL.
//   4. iOS opens that URL in SFSafariViewController. Courier fills out KYC,
//      bank info, tax info on Stripe's domain.
//   5. Courier returns to the app. App calls GET /courier/payouts/status to
//      refresh our local payout_ready flag.
//   6. Stripe also fires account.updated webhooks — StripeWebhook catches
//      those and updates payout_ready out-of-band.

type PayoutLinkResponse struct {
	URL string `json:"url"`
}

type PayoutStatusResponse struct {
	PayoutReady      bool   `json:"payout_ready"`
	ConnectID        string `json:"connect_id,omitempty"`
	OnboardingURL    string `json:"onboarding_url,omitempty"`
	DetailsSubmitted bool   `json:"details_submitted"`
}

// CreatePayoutAccount creates a Stripe Express account for the courier if
// they don't have one yet, then returns status. Idempotent.
func (h *Handler) CreatePayoutAccount(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var existingID, email, firstName, lastName, phone string
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT cp.stripe_connect_id, u.email, u.first_name, u.last_name, u.phone
		   FROM courier_profiles cp JOIN users u ON u.id = cp.user_id
		  WHERE cp.user_id = $1`, user["user_id"],
	).Scan(&existingID, &email, &firstName, &lastName, &phone)
	if err != nil {
		writeError(w, http.StatusNotFound, "courier profile not found")
		return
	}

	if existingID != "" {
		// Already has an account — just return the status.
		h.writePayoutStatus(w, r, user["user_id"], existingID)
		return
	}

	acctID, err := h.stripe.CreateExpressAccount(email, firstName, lastName, phone)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create Stripe account")
		return
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles SET stripe_connect_id = $1, updated_at = NOW() WHERE user_id = $2`,
		acctID, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save connect id")
		return
	}

	h.writePayoutStatus(w, r, user["user_id"], acctID)
}

// GetPayoutLink generates a fresh Stripe hosted onboarding URL. Links expire
// quickly, so the iOS app fetches a new one each time it opens the sheet.
func (h *Handler) GetPayoutLink(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var acctID string
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT stripe_connect_id FROM courier_profiles WHERE user_id = $1`,
		user["user_id"],
	).Scan(&acctID)
	if err != nil || acctID == "" {
		writeError(w, http.StatusBadRequest, "no Stripe account — call /payouts/account first")
		return
	}

	// These URLs are deep links the Stripe flow will redirect to.
	// For now we just use generic https URLs; the iOS app intercepts via
	// SFSafariViewController's completion and doesn't actually care about
	// the return URL content. Production would use a universal link.
	returnURL := h.cfg.WebURL + "/courier/payouts/return"
	refreshURL := h.cfg.WebURL + "/courier/payouts/refresh"

	url, err := h.stripe.CreateAccountLink(acctID, returnURL, refreshURL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create account link")
		return
	}

	writeJSON(w, http.StatusOK, PayoutLinkResponse{URL: url})
}

// GetPayoutStatus re-fetches the courier's account state from Stripe and
// updates the local payout_ready flag. Called after the user returns from
// the hosted onboarding UI.
func (h *Handler) GetPayoutStatus(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var acctID string
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT stripe_connect_id FROM courier_profiles WHERE user_id = $1`,
		user["user_id"],
	).Scan(&acctID)
	if err != nil || acctID == "" {
		writeJSON(w, http.StatusOK, PayoutStatusResponse{PayoutReady: false})
		return
	}
	h.writePayoutStatus(w, r, user["user_id"], acctID)
}

// writePayoutStatus is shared by CreatePayoutAccount and GetPayoutStatus.
// It calls Stripe, updates payout_ready in the DB, and returns the current
// status to the client.
func (h *Handler) writePayoutStatus(w http.ResponseWriter, r *http.Request, userID, acctID string) {
	status, err := h.stripe.GetAccountStatus(acctID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch Stripe status")
		return
	}

	payoutReady := status.PayoutsEnabled && status.DetailsSubmitted
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles SET payout_ready = $1, updated_at = NOW() WHERE user_id = $2`,
		payoutReady, userID); err != nil {
		slog.Error("writePayoutStatus: failed to update payout_ready",
			slog.String("user_id", userID), slog.String("error", err.Error()))
	}

	writeJSON(w, http.StatusOK, PayoutStatusResponse{
		PayoutReady:      payoutReady,
		ConnectID:        acctID,
		DetailsSubmitted: status.DetailsSubmitted,
	})
}
