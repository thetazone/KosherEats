package handlers

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"

	"github.com/koshereats/backend/internal/models"
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
	discount, err := h.resolveDealDiscount(r.Context(), req.AppliedDealID, cartRestID, subtotal, dealItems)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	discountedSubtotal := subtotal - discount

	isPickup := req.FulfillmentType == "pickup"

	deliveryFee := 0
	if !isPickup {
		if req.RestaurantID != "" && req.DeliveryAddress != "" {
			var restAddress string
			err := h.db.Pool.QueryRow(r.Context(),
				`SELECT COALESCE(street || ', ' || city || ', ' || state || ' ' || zip_code, '')
				   FROM restaurants WHERE id = $1`, req.RestaurantID,
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
	tax := discountedSubtotal * h.cfg.TaxRatePercent / 100
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

	bundle, err := h.stripe.CreatePaymentSheet(r.Context(), h.db.Pool, total, user["user_id"], email, firstName+" "+lastName)
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

	event, err := webhook.ConstructEvent(payload, r.Header.Get("Stripe-Signature"), h.cfg.StripeWebhookSec)
	if err != nil {
		slog.Warn("stripe webhook signature verification failed", slog.String("error", err.Error()))
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
		if _, err := h.db.Pool.Exec(r.Context(),
			`UPDATE courier_profiles SET payout_ready = $1, updated_at = NOW()
			 WHERE stripe_connect_id = $2`, ready, account.ID); err != nil {
			slog.Error("StripeWebhook: failed to update payout_ready",
				slog.String("connect_id", account.ID), slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
}
