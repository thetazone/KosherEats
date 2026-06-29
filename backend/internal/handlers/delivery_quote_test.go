package handlers

import (
	"context"
	"testing"

	"github.com/koshereats/backend/internal/config"
)

func testQuoteHandler() *Handler {
	return &Handler{cfg: &config.Config{
		DeliveryMarkupCents:        100, // $1
		DeliveryMarkupLargeCents:   200, // $2
		DeliveryMarkupHighestCents: 300, // $3
		DeliveryLargeOrderCents:    4000, // $40
		DeliveryHighestOrderCents:  8000, // $80
	}}
}

// The KosherEats marketplace fee is tiered by item subtotal: $1 ≤ $40,
// $2 up to $80, $3 above. Boundaries are exclusive on the lower threshold.
func TestDeliveryMarkupCents_Tiers(t *testing.T) {
	h := testQuoteHandler()
	cases := []struct {
		subtotal, want int
	}{
		{0, 100},
		{3999, 100},
		{4000, 100},  // exactly at large threshold → still tier 1
		{4001, 200},  // just over → tier 2
		{8000, 200},  // exactly at highest threshold → still tier 2
		{8001, 300},  // just over → tier 3
		{25000, 300}, // large basket → tier 3
	}
	for _, c := range cases {
		if got := h.deliveryMarkupCents(c.subtotal); got != c.want {
			t.Errorf("deliveryMarkupCents(%d) = %d, want %d", c.subtotal, got, c.want)
		}
	}
}

// Self-delivery quotes the restaurant's own fee + the marketplace fee, and
// returns early without contacting any external provider (nil clients are fine).
// providerFee carries the restaurant's portion so the seller's payout can be
// derived as consumerFee - markup.
func TestQuoteDeliveryFee_SelfDelivery(t *testing.T) {
	h := testQuoteHandler()

	// $3.99 restaurant fee + $1 marketplace fee (small order) = $4.99.
	q := h.quoteDeliveryFee(context.Background(), "pickup", "dropoff", 2000, "restaurant", 399)
	if q.provider != "self_delivery" {
		t.Errorf("provider = %q, want self_delivery", q.provider)
	}
	if q.consumerFee != 499 {
		t.Errorf("consumerFee = %d, want 499", q.consumerFee)
	}
	if q.providerFee != 399 {
		t.Errorf("providerFee = %d, want 399 (restaurant's own fee)", q.providerFee)
	}
	// Restaurant's kept fee = consumerFee - markup must equal its set fee.
	if kept := q.consumerFee - h.deliveryMarkupCents(2000); kept != 399 {
		t.Errorf("restaurant kept = %d, want 399", kept)
	}

	// Highest-tier basket → $3 marketplace fee: 399 + 300 = 699.
	big := h.quoteDeliveryFee(context.Background(), "p", "d", 9000, "restaurant", 399)
	if big.consumerFee != 699 {
		t.Errorf("highest-tier consumerFee = %d, want 699", big.consumerFee)
	}
}
