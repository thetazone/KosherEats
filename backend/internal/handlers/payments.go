package handlers

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/stripe/stripe-go/v78/webhook"
)

// CreatePaymentIntent computes the authoritative total server-side (cart +
// fees + tip) and returns a PaymentSheet bundle that iOS StripePaymentSheet
// can initialize from directly.
//
// We never trust a client-supplied total — the client only sends the tip
// amount. Everything else (subtotal, tax, service fee, delivery fee) is
// derived from the cart.
type CreatePaymentIntentRequest struct {
	Tip int `json:"tip"` // cents, optional
}

func (h *Handler) CreatePaymentIntent(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req CreatePaymentIntentRequest
	_ = readJSON(r, &req) // body optional

	// Pull the current cart subtotal straight from the DB — the one source of
	// truth. Uses ci.unit_price (modifier-adjusted snapshot) not mi.price so
	// selected modifiers are already baked in.
	var subtotal int
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(SUM(ci.unit_price * ci.quantity), 0)
		   FROM cart_items ci
		   JOIN carts c ON ci.cart_id = c.id
		  WHERE c.user_id = $1`, user["user_id"],
	).Scan(&subtotal)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to read cart")
		return
	}
	if subtotal == 0 {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Fee calculation mirrors CreateOrder exactly so the client sees the
	// same amount it will actually be charged.
	deliveryFee := 399
	serviceFee := subtotal * 15 / 100
	tax := subtotal * 9 / 100
	tip := req.Tip
	if tip < 0 {
		tip = 0
	}
	total := subtotal + deliveryFee + serviceFee + tax + tip

	// Fetch the user's email + name for the Stripe Customer record.
	var email, firstName, lastName string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT email, first_name, last_name FROM users WHERE id = $1`, user["user_id"],
	).Scan(&email, &firstName, &lastName)

	bundle, err := h.stripe.CreatePaymentSheet(total, user["user_id"], email, firstName+" "+lastName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create payment: "+err.Error())
		return
	}

	// Include the computed breakdown so the client can display it without
	// recomputing (or disagreeing with) the server.
	writeJSON(w, http.StatusOK, map[string]any{
		"payment_intent_secret": bundle.PaymentIntentSecret,
		"ephemeral_key_secret":  bundle.EphemeralKeySecret,
		"customer_id":           bundle.CustomerID,
		"publishable_key":       bundle.PublishableKey,
		"subtotal":              subtotal,
		"delivery_fee":          deliveryFee,
		"service_fee":           serviceFee,
		"tax":                   tax,
		"tip":                   tip,
		"total":                 total,
	})
}

func (h *Handler) ConfirmPayment(w http.ResponseWriter, r *http.Request) {
	// Confirmation happens client-side via StripePaymentSheet. This endpoint
	// is retained for compatibility but is a no-op — the real confirmation
	// signal is `payment_intent.succeeded` via StripeWebhook, which we can
	// use later to mark orders paid server-side.
	writeJSON(w, http.StatusOK, map[string]string{"status": "confirmed"})
}

func (h *Handler) StripeWebhook(w http.ResponseWriter, r *http.Request) {
	// Verify the Stripe signature before trusting any webhook payload. This
	// handler updates courier payout readiness from Stripe Connect's
	// account.updated events, so it must fail closed when misconfigured.
	if h.cfg.StripeWebhookSec == "" {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	payload, err := io.ReadAll(r.Body)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	event, err := webhook.ConstructEvent(payload, r.Header.Get("Stripe-Signature"), h.cfg.StripeWebhookSec)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	if event.Type == "account.updated" {
		var account struct {
			ID               string `json:"id"`
			PayoutsEnabled   bool   `json:"payouts_enabled"`
			DetailsSubmitted bool   `json:"details_submitted"`
		}
		if err := json.Unmarshal(event.Data.Raw, &account); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		ready := account.PayoutsEnabled && account.DetailsSubmitted
		_, _ = h.db.Pool.Exec(r.Context(),
			`UPDATE courier_profiles SET payout_ready = $1, updated_at = NOW()
			 WHERE stripe_connect_id = $2`, ready, account.ID)
	}

	w.WriteHeader(http.StatusOK)
}
