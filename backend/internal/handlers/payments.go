package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/jackc/pgx/v5"
	"github.com/koshereats/backend/internal/models"
	"github.com/koshereats/backend/internal/notify"
	"github.com/stripe/stripe-go/v78/webhook"
)

// alertAdmin emails the configured admin alert address about an operational
// anomaly (charge dispute, refund, etc.). When cfg.AdminAlertEmail is unset it
// is a logged no-op (see notify.Alerter). Money-critical paths call this for
// side-channel visibility; a send failure never breaks the caller.
func (h *Handler) alertAdmin(subject, body string) {
	notify.NewAlerter(h.cfg.AdminAlertEmail, h.email).Alert(subject, body)
}

// taxForOrder computes the tax on a (discounted) subtotal in cents.
//
// Today this is a documented stub: it returns the same flat-rate result the
// inline computation always used, so enabling cfg.StripeTaxEnabled changes
// nothing about the charged amount yet — it only routes tax through this one
// integration point.
//
// TODO: integrate Stripe Tax (needs the connected Stripe account's Tax feature
// enabled). When wired, this should call Stripe's tax calculation for the
// order's jurisdiction instead of the flat TaxRatePercent. Until then the
// flat rate is authoritative and StripeTaxEnabled is effectively a feature
// flag guarding an inert seam.
func (h *Handler) taxForOrder(discountedSubtotal int) int {
	// TODO: integrate Stripe Tax (needs the Stripe account's Tax enabled).
	return discountedSubtotal * h.cfg.TaxRatePercent / 100
}

// CreatePaymentIntent computes the authoritative total server-side (cart +
// fees + tip) and returns a PaymentSheet bundle that iOS StripePaymentSheet
// can initialize from directly.
//
// We never trust a client-supplied total — the client only sends the tip
// amount. Everything else (subtotal, tax, service fee, delivery fee) is
// derived from the cart.
type CreatePaymentIntentRequest struct {
	Tip int `json:"tip"` // cents, optional
	// FulfillmentType matches CreateOrderRequest. Pickup orders skip both
	// the delivery fee and the courier tip so the Stripe charge total
	// agrees with what CreateOrder will record. Defaults to delivery.
	FulfillmentType string `json:"fulfillment_type,omitempty"`
	// Delivery address fields for dynamic fee quoting. When provided, the
	// server gets real-time quotes from courier providers instead of using
	// the static formula.
	RestaurantID    string `json:"restaurant_id,omitempty"`
	DeliveryAddress string `json:"delivery_address,omitempty"`
	// AppliedDealID, when set, applies the deal's discount to the subtotal
	// before computing tax and the Stripe charge total. CreateOrder must
	// receive the same id so the recorded total matches the charge.
	AppliedDealID string `json:"applied_deal_id,omitempty"`
}

func (h *Handler) CreatePaymentIntent(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req CreatePaymentIntentRequest
	_ = readJSON(r, &req) // body optional

	// Pull the current cart and its items straight from the DB — the one
	// source of truth. Uses ci.unit_price (modifier-adjusted snapshot) not
	// mi.price so selected modifiers are already baked in. We fetch
	// per-item rows (not just SUM) because BOGO discount logic needs the
	// cheapest single-unit price.
	var cartRestID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT restaurant_id FROM carts WHERE user_id = $1`, user["user_id"],
	).Scan(&cartRestID); err != nil {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT ci.unit_price, ci.quantity
		   FROM cart_items ci
		   JOIN carts c ON ci.cart_id = c.id
		  WHERE c.user_id = $1`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to read cart")
		return
	}
	var subtotal int
	var dealItems []models.OrderItem
	for rows.Next() {
		var unit, qty int
		if err := rows.Scan(&unit, &qty); err != nil {
			rows.Close()
			writeError(w, http.StatusInternalServerError, "failed to scan cart item")
			return
		}
		subtotal += unit * qty
		dealItems = append(dealItems, models.OrderItem{Price: unit, Quantity: qty})
	}
	rows.Close()
	if subtotal == 0 {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Apply deal discount before tax so the user pays tax on the discounted
	// subtotal. CreateOrder applies the same logic — keep them in sync.
	discount, err := h.resolveDealDiscount(r.Context(), req.AppliedDealID, cartRestID, user["user_id"], subtotal, dealItems)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	discountedSubtotal := subtotal - discount

	isPickup := req.FulfillmentType == "pickup"

	deliveryFee := 0
	if !isPickup {
		// Quote against the cart's restaurant (authoritative — CreateOrder uses
		// the same cart.RestaurantID), so the client only has to supply the
		// delivery address. Without a delivery address we can't quote, so fall
		// back to the flat rate. The fee computed here is stamped onto the
		// PaymentIntent and reused verbatim by CreateOrder, so the two never
		// disagree even though quoteDeliveryFee is a live, drifting quote.
		if req.DeliveryAddress != "" {
			var restAddress string
			err := h.db.Pool.QueryRow(r.Context(),
				`SELECT COALESCE(street || ', ' || city || ', ' || state || ' ' || zip_code, '')
				   FROM restaurants WHERE id = $1`, cartRestID,
			).Scan(&restAddress)
			if err == nil && restAddress != "" {
				quote := h.quoteDeliveryFee(r.Context(), restAddress, req.DeliveryAddress)
				deliveryFee = quote.consumerFee
			} else {
				deliveryFee = deliveryFeeFallbackCents
			}
		} else {
			deliveryFee = deliveryFeeFallbackCents
		}
	}
	serviceFee := 0
	// Default: flat TaxRatePercent (unchanged). When StripeTaxEnabled is set we
	// route through taxForOrder, the Stripe Tax integration seam — which today
	// returns the same flat-rate value, so the charged total is identical until
	// that stub is wired to real Stripe Tax.
	var tax int
	if h.cfg.StripeTaxEnabled {
		tax = h.taxForOrder(discountedSubtotal)
	} else {
		tax = discountedSubtotal * h.cfg.TaxRatePercent / 100
	}
	tip := req.Tip
	if tip < 0 {
		tip = 0
	}
	if isPickup {
		tip = 0
	}
	if tip > subtotal {
		writeError(w, http.StatusBadRequest, "tip cannot exceed subtotal")
		return
	}
	total := discountedSubtotal + deliveryFee + serviceFee + tax + tip

	// Fetch the user's email + name for the Stripe Customer record.
	var email, firstName, lastName string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT email, first_name, last_name FROM users WHERE id = $1`, user["user_id"],
	).Scan(&email, &firstName, &lastName); err != nil {
		slog.Warn("CreatePaymentIntent: failed to fetch user info for Stripe",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
	}

	bundle, err := h.stripe.CreatePaymentSheet(r.Context(), h.db.Pool, total, deliveryFee, user["user_id"], email, firstName+" "+lastName)
	if err != nil {
		// Surface the real Stripe error to the logs so future "failed to
		// create payment" reports take seconds, not an hour, to diagnose.
		// Past examples: stale stripe_customer_id from key rotation, missing
		// publishable key, account in restricted state.
		slog.Error("CreatePaymentIntent failed",
			slog.String("user_id", user["user_id"]),
			slog.Int("amount_cents", total),
			slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to create payment")
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
		"discount":              discount,
		"applied_deal_id":       req.AppliedDealID,
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

// GetPaymentCustomer returns everything iOS's STPCustomerSheet needs to list,
// add, and delete the user's saved payment methods from the profile screen.
// Unlike CreatePaymentIntent this does not create a PaymentIntent — the
// CustomerSheet uses SetupIntents (see CreateSetupIntent) for adding cards.
func (h *Handler) GetPaymentCustomer(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var email, firstName, lastName string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT email, first_name, last_name FROM users WHERE id = $1`, user["user_id"],
	).Scan(&email, &firstName, &lastName); err != nil {
		slog.Warn("GetPaymentCustomer: failed to fetch user info for Stripe",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
	}

	bundle, err := h.stripe.CreateCustomerBundle(r.Context(), h.db.Pool, user["user_id"], email, firstName+" "+lastName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create customer session")
		return
	}
	writeJSON(w, http.StatusOK, bundle)
}

// CreateSetupIntent issues a SetupIntent client_secret scoped to the user's
// persistent Stripe Customer. The iOS STPCustomerSheet needs a fresh
// SetupIntent for each "add a new card" flow.
func (h *Handler) CreateSetupIntent(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var email, firstName, lastName string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT email, first_name, last_name FROM users WHERE id = $1`, user["user_id"],
	).Scan(&email, &firstName, &lastName); err != nil {
		slog.Warn("CreateSetupIntent: failed to fetch user info for Stripe",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
	}

	clientSecret, err := h.stripe.CreateSetupIntent(r.Context(), h.db.Pool, user["user_id"], email, firstName+" "+lastName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create setup intent")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"client_secret": clientSecret})
}

func (h *Handler) StripeWebhook(w http.ResponseWriter, r *http.Request) {
	// Verify the Stripe signature before trusting any webhook payload. This
	// handler updates courier payout readiness from Stripe Connect's
	// account.updated events, so it must fail closed when misconfigured.
	if h.cfg.StripeWebhookSec == "" {
		slog.Error("stripe webhook signing secret is not configured")
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	payload, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	// IgnoreAPIVersionMismatch: the webhook endpoint is pinned to the account's
	// current Stripe API version, which is newer than the one stripe-go v78 is
	// built against. The SIGNATURE is still fully verified — we only relax the
	// version assertion, which would otherwise reject every live event with a
	// 400. The fields this handler reads (event.Type, PaymentIntent id/status,
	// charge dispute, account payout flags) are stable across these versions.
	event, err := webhook.ConstructEventWithOptions(
		payload, r.Header.Get("Stripe-Signature"), h.cfg.StripeWebhookSec,
		webhook.ConstructEventOptions{IgnoreAPIVersionMismatch: true},
	)
	if err != nil {
		slog.Warn("stripe webhook signature verification failed", slog.String("error", err.Error()))
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	// Idempotency: Stripe delivers at-least-once, so the same event.ID can
	// arrive multiple times (retries, network dups). Record the id once; if the
	// INSERT affects no rows we've already processed this event — ACK 200 and
	// run no side effects again. We do this AFTER signature verification so an
	// unsigned/forged payload can never poison the ledger.
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		slog.Error("StripeWebhook: failed to begin tx",
			slog.String("event_id", event.ID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	ct, err := tx.Exec(r.Context(),
		`INSERT INTO stripe_webhook_events (event_id, type) VALUES ($1, $2)
		 ON CONFLICT (event_id) DO NOTHING`, event.ID, string(event.Type))
	if err != nil {
		slog.Error("StripeWebhook: failed to record event for idempotency",
			slog.String("event_id", event.ID), slog.String("error", err.Error()))
		// Fail closed so Stripe retries — better a duplicate delivery (which the
		// dedupe will then catch) than silently dropping a dispute/refund event.
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if ct.RowsAffected() == 0 {
		slog.Info("StripeWebhook: duplicate event ignored",
			slog.String("event_id", event.ID), slog.String("type", string(event.Type)))
		w.WriteHeader(http.StatusOK)
		return
	}

	// Admin alerts are side effects that must escape the tx exactly-once.
	// Capture them here and only send after Commit succeeds, so a commit
	// failure (which rolls back the dedupe row and triggers a Stripe retry)
	// cannot re-send the same email.
	var alertSubject, alertBody string

	switch event.Type {
	case "account.updated":
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
		if _, err := tx.Exec(r.Context(),
			`UPDATE courier_profiles SET payout_ready = $1, updated_at = NOW()
			 WHERE stripe_connect_id = $2`, ready, account.ID); err != nil {
			slog.Error("StripeWebhook: failed to update payout_ready",
				slog.String("connect_id", account.ID), slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

	case "charge.dispute.created":
		// A customer (or their bank) is disputing a charge. This is money at
		// risk — log loudly and alert the admin so they can submit evidence
		// before Stripe's response deadline.
		var dispute struct {
			ID            string `json:"id"`
			Charge        string `json:"charge"`
			PaymentIntent string `json:"payment_intent"`
			Amount        int    `json:"amount"`
			Currency      string `json:"currency"`
			Reason        string `json:"reason"`
			Status        string `json:"status"`
		}
		if err := json.Unmarshal(event.Data.Raw, &dispute); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		orderID := h.lookupOrderByPaymentIntent(r.Context(), dispute.PaymentIntent)
		slog.Warn("StripeWebhook: charge dispute created",
			slog.String("dispute_id", dispute.ID),
			slog.String("charge", dispute.Charge),
			slog.String("payment_intent", dispute.PaymentIntent),
			slog.String("order_id", orderID),
			slog.Int("amount_cents", dispute.Amount),
			slog.String("reason", dispute.Reason),
			slog.String("status", dispute.Status))
		alertSubject = "Stripe dispute opened"
		alertBody = disputeAlertBody(dispute.ID, dispute.Charge, dispute.PaymentIntent, orderID, dispute.Amount, dispute.Reason)

	case "charge.refunded":
		// A charge was refunded (manually in the dashboard, by our auto-refund
		// sweeps, or by Stripe). Log + alert so refunds are never silent.
		var charge struct {
			ID             string `json:"id"`
			PaymentIntent  string `json:"payment_intent"`
			AmountRefunded int    `json:"amount_refunded"`
			Amount         int    `json:"amount"`
		}
		if err := json.Unmarshal(event.Data.Raw, &charge); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		orderID := h.lookupOrderByPaymentIntent(r.Context(), charge.PaymentIntent)
		slog.Info("StripeWebhook: charge refunded",
			slog.String("charge", charge.ID),
			slog.String("payment_intent", charge.PaymentIntent),
			slog.String("order_id", orderID),
			slog.Int("amount_refunded_cents", charge.AmountRefunded))
		alertSubject = "Stripe charge refunded"
		alertBody = refundAlertBody(charge.ID, charge.PaymentIntent, orderID, charge.AmountRefunded)
	}

	if err := tx.Commit(r.Context()); err != nil {
		slog.Error("StripeWebhook: failed to commit event",
			slog.String("event_id", event.ID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if alertSubject != "" {
		h.alertAdmin(alertSubject, alertBody)
	}

	w.WriteHeader(http.StatusOK)
}

// lookupOrderByPaymentIntent resolves the order id whose stripe_payment_id
// matches the given PaymentIntent, for enriching dispute/refund alerts. Empty
// PI or no match returns "" — alerts are best-effort context, never a hard
// dependency, so a lookup miss must not fail the webhook.
func (h *Handler) lookupOrderByPaymentIntent(ctx context.Context, paymentIntent string) string {
	if paymentIntent == "" {
		return ""
	}
	var orderID string
	err := h.db.Pool.QueryRow(ctx,
		`SELECT id FROM orders WHERE stripe_payment_id = $1`, paymentIntent,
	).Scan(&orderID)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Warn("StripeWebhook: order lookup by payment_intent failed",
				slog.String("payment_intent", paymentIntent), slog.String("error", err.Error()))
		}
		return ""
	}
	return orderID
}

func disputeAlertBody(disputeID, charge, paymentIntent, orderID string, amountCents int, reason string) string {
	body := "A Stripe charge dispute was opened.\n\n" +
		"Dispute: " + disputeID + "\n" +
		"Charge: " + charge + "\n" +
		"PaymentIntent: " + paymentIntent + "\n" +
		"Amount (cents): " + strconv.Itoa(amountCents) + "\n" +
		"Reason: " + reason + "\n"
	if orderID != "" {
		body += "Order: " + orderID + "\n"
	}
	body += "\nSubmit evidence in the Stripe dashboard before the response deadline."
	return body
}

func refundAlertBody(charge, paymentIntent, orderID string, amountRefundedCents int) string {
	body := "A Stripe charge was refunded.\n\n" +
		"Charge: " + charge + "\n" +
		"PaymentIntent: " + paymentIntent + "\n" +
		"Amount refunded (cents): " + strconv.Itoa(amountRefundedCents) + "\n"
	if orderID != "" {
		body += "Order: " + orderID + "\n"
	}
	return body
}
