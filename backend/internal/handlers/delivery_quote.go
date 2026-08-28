package handlers

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/uberdirect"
)

const (
	// Fallback delivery fee for the rare case where no external provider is
	// configured or all quotes fail — we can't compute "provider + markup"
	// without a quote, so we fall back to a flat fee rather than block delivery.
	deliveryFeeFallbackCents = 599 // $5.99
)

type DeliveryQuoteRequest struct {
	RestaurantID    string  `json:"restaurant_id"`
	DeliveryLat     float64 `json:"delivery_lat"`
	DeliveryLng     float64 `json:"delivery_lng"`
	DeliveryAddress string  `json:"delivery_address"`
}

type DeliveryQuoteResponse struct {
	DeliveryFeeCents int    `json:"delivery_fee"`
	EstMinutes       int    `json:"est_minutes"`
	Provider         string `json:"provider"`
	ProviderFeeCents int    `json:"provider_fee"`
}

// DeliveryQuote returns the dynamic delivery fee for a given restaurant →
// customer route. Called by the checkout screen before payment.
func (h *Handler) DeliveryQuote(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req DeliveryQuoteRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request")
		return
	}
	if req.RestaurantID == "" || req.DeliveryAddress == "" {
		writeError(w, http.StatusBadRequest, "restaurant_id and delivery_address are required")
		return
	}

	var restName, restAddress, restPhone, restDeliveryMode string
	var restLat, restLng float64
	var restDeliveryFee int
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT name,
		        COALESCE(street || ', ' || city || ', ' || state || ' ' || zip_code, ''),
		        COALESCE(phone, ''), lat, lng,
		        COALESCE(delivery_mode, 'external'), delivery_fee
		   FROM restaurants WHERE id = $1`, req.RestaurantID,
	).Scan(&restName, &restAddress, &restPhone, &restLat, &restLng, &restDeliveryMode, &restDeliveryFee)
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	// Dropoff contact, matching what dispatch will later send. Best-effort: a
	// missing name or phone must not fail the quote (the provider clients
	// substitute a placeholder name), but sending them keeps this quote and the
	// dispatch quote on identical payloads.
	var customerName, customerPhone string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(first_name || ' ' || last_name, ''), COALESCE(phone, '')
		   FROM users WHERE id = $1`, user["user_id"],
	).Scan(&customerName, &customerPhone); err != nil {
		slog.Warn("delivery-quote: customer lookup failed, quoting without contact",
			slog.String("error", err.Error()))
	}

	// Item subtotal of the user's cart decides the markup tier ($1 vs $2 vs $3
	// over the large-order thresholds), so the preview matches what checkout
	// charges. A swallowed error here left subtotal=0, silently quoting the
	// cheapest tier on a big cart — the customer then sees a lower fee than
	// checkout independently computes, recreating the quote-vs-charge mismatch.
	// Fail the quote rather than under-quote the marketplace fee.
	var subtotal int
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(SUM(ci.unit_price * ci.quantity), 0)
		   FROM cart_items ci JOIN carts c ON ci.cart_id = c.id
		  WHERE c.user_id = $1`, user["user_id"]).Scan(&subtotal); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to compute delivery quote")
		return
	}

	quote := h.quoteDeliveryFee(r.Context(), quoteParams{
		pickupAddress:   restAddress,
		dropoffAddress:  req.DeliveryAddress,
		restaurantName:  restName,
		restaurantPhone: restPhone,
		customerName:    customerName,
		customerPhone:   customerPhone,
		subtotalCents:   subtotal,
		deliveryMode:    restDeliveryMode,
		restaurantFee:   restDeliveryFee,
	})

	writeJSON(w, http.StatusOK, DeliveryQuoteResponse{
		DeliveryFeeCents: quote.consumerFee,
		EstMinutes:       quote.estMinutes,
		Provider:         quote.provider,
		ProviderFeeCents: quote.providerFee,
	})
}

type deliveryQuoteResult struct {
	consumerFee int
	providerFee int // non-KE delivery cost: provider quote (external) or the restaurant's own fee (self-delivery)
	estMinutes  int
	provider    string
}

// selfDeliveryEstMinutes is the rough ETA shown for restaurant self-delivery,
// where we have no external courier ETA to quote.
const selfDeliveryEstMinutes = 35

// deliveryMarkupCents is the flat KosherEats marketplace fee added on top of the
// delivery cost, tiered by item subtotal (excl. delivery) and charged to the
// consumer on every delivery (kept by KE): the small fee up to the large
// threshold, the large fee up to the highest threshold, the highest fee above.
func (h *Handler) deliveryMarkupCents(subtotalCents int) int {
	switch {
	case subtotalCents > h.cfg.DeliveryHighestOrderCents:
		return h.cfg.DeliveryMarkupHighestCents
	case subtotalCents > h.cfg.DeliveryLargeOrderCents:
		return h.cfg.DeliveryMarkupLargeCents
	default:
		return h.cfg.DeliveryMarkupCents
	}
}

// quoteParams is everything a provider needs to price a route. Checkout and
// dispatch fill it from the same columns so the two quotes are directly
// comparable.
//
// They used to diverge: checkout sent only the two addresses, while dispatch
// also sent the pickup business name/phone and the dropoff contact name/phone.
// DoorDash rejects the shorter payload (400 on pickup_phone_number and
// "Customer first_name contains no letters"), so the provider silently dropped
// out of the checkout auction and reappeared at dispatch — quoting a price the
// consumer was never shown, after the card had already been charged.
type quoteParams struct {
	pickupAddress   string
	dropoffAddress  string
	restaurantName  string
	restaurantPhone string
	customerName    string
	customerPhone   string
	subtotalCents   int // item subtotal, excl. delivery — sets the markup tier
	deliveryMode    string
	restaurantFee   int // the restaurant's own fee, used for self-delivery
}

// quoteDeliveryFee gets quotes from available external providers, picks the
// cheapest, and returns the consumer-facing fee: the real provider cost plus a
// flat markup we keep ($1 normally, $2 once the item subtotal clears the
// large-order threshold). No floor/ceiling — the fee always tracks the actual
// quote. Falls back to a flat fee only if no provider is configured or all
// quotes fail.
func (h *Handler) quoteDeliveryFee(ctx context.Context, p quoteParams) deliveryQuoteResult {
	// Self-delivery: the restaurant fulfills with its own driver. The consumer
	// pays the restaurant's configured fee plus the KosherEats marketplace fee;
	// the restaurant keeps its fee in full, KE keeps the marketplace fee. No
	// external provider is contacted.
	if p.deliveryMode == "restaurant" {
		return deliveryQuoteResult{
			consumerFee: p.restaurantFee + h.deliveryMarkupCents(p.subtotalCents),
			providerFee: p.restaurantFee,
			estMinutes:  selfDeliveryEstMinutes,
			provider:    "self_delivery",
		}
	}

	type providerQuote struct {
		provider   string
		feeCents   int
		estMinutes int
	}

	var quotes []providerQuote

	if h.uber != nil && h.uber.Enabled() {
		pickup := uberdirect.Address{Street: []string{p.pickupAddress}, Country: "US"}
		dropoff := uberdirect.Address{Street: []string{p.dropoffAddress}, Country: "US"}
		q, err := h.uber.GetQuote(ctx, pickup, dropoff)
		if err != nil {
			slog.Warn("delivery-quote: uber quote failed", slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, providerQuote{
				provider: "uber_direct", feeCents: q.Fee, estMinutes: q.DurationMinutes,
			})
		}
	}

	if h.doordash != nil && h.doordash.Enabled() {
		// Same field set dispatch sends (dispatch/external.go) — anything less
		// and DoorDash 400s here but succeeds there, hiding the provider from
		// the price the consumer actually agrees to.
		q, err := h.doordash.GetQuote(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: "quote_check",
			PickupAddress:      p.pickupAddress,
			PickupBusinessName: p.restaurantName,
			PickupPhone:        p.restaurantPhone,
			DropoffAddress:     p.dropoffAddress,
			DropoffContactName: p.customerName,
			DropoffPhone:       p.customerPhone,
			OrderValue:         p.subtotalCents,
		})
		if err != nil {
			slog.Warn("delivery-quote: doordash quote failed", slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, providerQuote{
				provider: "doordash_drive", feeCents: q.Fee, estMinutes: 30,
			})
		}
	}

	if h.shipday != nil && h.shipday.Enabled() {
		q, err := h.shipday.GetQuote(ctx, p.pickupAddress, p.dropoffAddress)
		if err != nil {
			slog.Warn("delivery-quote: shipday quote failed", slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, providerQuote{
				provider: "shipday", feeCents: q.FeeCents, estMinutes: q.EstMinutes,
			})
		}
	}

	if len(quotes) == 0 {
		return deliveryQuoteResult{
			consumerFee: deliveryFeeFallbackCents,
			providerFee: 0,
			estMinutes:  30,
			provider:    "flat_rate",
		}
	}

	best := quotes[0]
	for _, q := range quotes[1:] {
		if q.feeCents < best.feeCents {
			best = q
		}
	}

	// Provider cost + our tiered marketplace markup. No clamping: the consumer
	// pays exactly the courier cost plus the markup.
	consumerFee := best.feeCents + h.deliveryMarkupCents(p.subtotalCents)

	return deliveryQuoteResult{
		consumerFee: consumerFee,
		providerFee: best.feeCents,
		estMinutes:  best.estMinutes,
		provider:    best.provider,
	}
}
