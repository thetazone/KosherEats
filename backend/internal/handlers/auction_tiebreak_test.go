package handlers

// The checkout auction and the dispatch auction must resolve a fee TIE the same
// way.
//
// The consumer is charged the checkout winner's fee plus the markup, and the
// card is captured before dispatch runs. Dispatch then re-runs the same auction
// and buys from ITS winner. Both walk their providers in the order uber →
// doordash → shipday and keep the incumbent on a tie (strict `<`), so the same
// three quotes produce the same winner on both sides. If either side flipped to
// `<=`, or reordered its providers, a tie would silently hand the delivery to a
// provider the consumer was never quoted — and any later divergence between the
// two fee schedules comes straight out of KosherEats.
//
// The dispatch half of this invariant is pinned by
// TestDispatch_CheapestQuoteWins/"tie keeps the first provider".
//
// SAFETY: installFakeProviders redirects every provider host to a loopback
// httptest server and refuses anything else.

import (
	"context"
	"fmt"
	"testing"
)

func TestIntegration_CheckoutAuctionTieKeepsTheFirstProvider(t *testing.T) {
	cases := []struct {
		name         string
		uber, dd     int
		shipday      float64
		wantProvider string
		wantFee      int
	}{
		{"all three tie", 800, 800, 8.00, "uber_direct", 800},
		{"uber ties doordash", 800, 800, 12.00, "uber_direct", 800},
		{"doordash ties shipday, both under uber", 1200, 800, 8.00, "doordash_drive", 800},
		// One cent decides it — no rounding slop in either direction.
		{"a single cent breaks the tie", 800, 799, 8.00, "doordash_drive", 799},
		{"a single cent the other way", 799, 800, 8.00, "uber_direct", 799},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			net := installFakeProviders(t, true, true, true)
			net.on(uberQuotePath, 200, fmt.Sprintf(`{"id":"q","fee":%d,"duration":25}`, tc.uber))
			net.on(ddQuotePath, 200, fmt.Sprintf(`{"external_delivery_id":"q","fee":%d}`, tc.dd))
			net.on(shipdayQuotePath, 200, fmt.Sprintf(
				`[{"id":"e","name":"DoorDash","fee":%v,"pickupDuration":10,"deliveryDuration":15}]`, tc.shipday))

			h := quoteHandlerWithProviders(t)
			q := h.quoteDeliveryFee(context.Background(), quoteParams{
				pickupAddress: "1 Main St", dropoffAddress: "2 Oak St",
				restaurantName: "Deli", restaurantPhone: "+17185551212",
				customerName: "Con Sumer", customerPhone: "+13156645801",
				subtotalCents: 2000, deliveryMode: "external",
			})
			if q.provider != tc.wantProvider {
				t.Errorf("checkout picked %q, want %q — dispatch breaks the same tie toward %q, so a "+
					"disagreement charges the consumer for a provider that will not deliver",
					q.provider, tc.wantProvider, tc.wantProvider)
			}
			if q.providerFee != tc.wantFee {
				t.Errorf("providerFee = %d, want %d", q.providerFee, tc.wantFee)
			}
		})
	}
}
