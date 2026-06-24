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
	RestaurantID   string  `json:"restaurant_id"`
	DeliveryLat    float64 `json:"delivery_lat"`
	DeliveryLng    float64 `json:"delivery_lng"`
	DeliveryAddress string `json:"delivery_address"`
}

type DeliveryQuoteResponse struct {
	DeliveryFeeCents   int    `json:"delivery_fee"`
	EstMinutes         int    `json:"est_minutes"`
	Provider           string `json:"provider"`
	ProviderFeeCents   int    `json:"provider_fee"`
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

	var restAddress, restPhone string
	var restLat, restLng float64
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(street || ', ' || city || ', ' || state || ' ' || zip_code, ''),
		        COALESCE(phone, ''), lat, lng
		   FROM restaurants WHERE id = $1`, req.RestaurantID,
	).Scan(&restAddress, &restPhone, &restLat, &restLng)
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	// Item subtotal of the user's cart decides the markup tier ($1 vs $2 over
	// the large-order threshold), so the preview matches what checkout charges.
	var subtotal int
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(SUM(ci.unit_price * ci.quantity), 0)
		   FROM cart_items ci JOIN carts c ON ci.cart_id = c.id
		  WHERE c.user_id = $1`, user["user_id"]).Scan(&subtotal)

	quote := h.quoteDeliveryFee(r.Context(), restAddress, req.DeliveryAddress, subtotal)

	writeJSON(w, http.StatusOK, DeliveryQuoteResponse{
		DeliveryFeeCents: quote.consumerFee,
		EstMinutes:       quote.estMinutes,
		Provider:         quote.provider,
		ProviderFeeCents: quote.providerFee,
	})
}

type deliveryQuoteResult struct {
	consumerFee int
	providerFee int
	estMinutes  int
	provider    string
}

// quoteDeliveryFee gets quotes from available external providers, picks the
// cheapest, and returns the consumer-facing fee: the real provider cost plus a
// flat markup we keep ($1 normally, $2 once the item subtotal clears the
// large-order threshold). No floor/ceiling — the fee always tracks the actual
// quote. Falls back to a flat fee only if no provider is configured or all
// quotes fail. subtotalCents is the item subtotal (excl. delivery).
func (h *Handler) quoteDeliveryFee(ctx context.Context, pickupAddress, dropoffAddress string, subtotalCents int) deliveryQuoteResult {
	type providerQuote struct {
		provider   string
		feeCents   int
		estMinutes int
	}

	var quotes []providerQuote

	if h.uber != nil && h.uber.Enabled() {
		pickup := uberdirect.Address{Street: []string{pickupAddress}, Country: "US"}
		dropoff := uberdirect.Address{Street: []string{dropoffAddress}, Country: "US"}
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
		q, err := h.doordash.GetQuote(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: "quote_check",
			PickupAddress:      pickupAddress,
			DropoffAddress:     dropoffAddress,
		})
		if err != nil {
			slog.Warn("delivery-quote: doordash quote failed", slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, providerQuote{
				provider: "doordash_drive", feeCents: q.Fee, estMinutes: 30,
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

	// Provider cost + our flat markup. $2 once the order clears the large-order
	// threshold (bigger basket → we keep a bit more), $1 otherwise. No clamping:
	// the consumer pays exactly the courier cost plus the markup.
	markup := h.cfg.DeliveryMarkupCents
	if subtotalCents > h.cfg.DeliveryLargeOrderCents {
		markup = h.cfg.DeliveryMarkupLargeCents
	}
	consumerFee := best.feeCents + markup

	return deliveryQuoteResult{
		consumerFee: consumerFee,
		providerFee: best.feeCents,
		estMinutes:  best.estMinutes,
		provider:    best.provider,
	}
}
