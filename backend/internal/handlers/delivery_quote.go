package handlers

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/uberdirect"
)

const (
	// Markup added on top of the cheapest external courier quote. Covers
	// payment processing on the delivery fee portion and gives a small buffer.
	deliveryFeeMarkupCents = 100 // $1.00

	// Floor and ceiling for the consumer-facing delivery fee regardless of
	// what the external provider quotes. Keeps pricing predictable.
	deliveryFeeFloorCents   = 499 // $4.99
	deliveryFeeCeilingCents = 1199 // $11.99

	// Fallback delivery fee when no external provider is configured or all
	// quotes fail. Matches the old formula's ballpark for a ~$68 order.
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
	_, err := getUserFromContext(r)
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

	quote := h.quoteDeliveryFee(r.Context(), restAddress, req.DeliveryAddress)

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
// cheapest, and returns the consumer-facing fee (provider cost + markup,
// clamped to floor/ceiling). Falls back to a flat fee if no providers are
// configured or all quotes fail.
func (h *Handler) quoteDeliveryFee(ctx context.Context, pickupAddress, dropoffAddress string) deliveryQuoteResult {
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

	consumerFee := best.feeCents + deliveryFeeMarkupCents
	if consumerFee < deliveryFeeFloorCents {
		consumerFee = deliveryFeeFloorCents
	}
	if consumerFee > deliveryFeeCeilingCents {
		consumerFee = deliveryFeeCeilingCents
	}

	return deliveryQuoteResult{
		consumerFee: consumerFee,
		providerFee: best.feeCents,
		estMinutes:  best.estMinutes,
		provider:    best.provider,
	}
}
