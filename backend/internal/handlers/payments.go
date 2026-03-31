package handlers

import (
	"net/http"
)

type PaymentIntentRequest struct {
	Amount int `json:"amount"` // cents
}

func (h *Handler) CreatePaymentIntent(w http.ResponseWriter, r *http.Request) {
	// TODO: integrate Stripe
	// stripe.Key = h.cfg.StripeSecretKey
	// params := &stripe.PaymentIntentParams{
	//     Amount:   stripe.Int64(int64(req.Amount)),
	//     Currency: stripe.String("usd"),
	// }
	// pi, err := paymentintent.New(params)

	writeJSON(w, http.StatusOK, map[string]string{
		"client_secret": "placeholder_implement_stripe",
		"status":        "requires_payment_method",
	})
}

func (h *Handler) ConfirmPayment(w http.ResponseWriter, r *http.Request) {
	// TODO: confirm payment with Stripe
	writeJSON(w, http.StatusOK, map[string]string{"status": "confirmed"})
}

func (h *Handler) StripeWebhook(w http.ResponseWriter, r *http.Request) {
	// TODO: handle Stripe webhooks
	// - payment_intent.succeeded -> update order status
	// - payment_intent.payment_failed -> notify user
	w.WriteHeader(http.StatusOK)
}
