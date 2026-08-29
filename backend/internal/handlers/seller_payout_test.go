package handlers

// The self-delivery payout: what a restaurant that drives its own order is
// credited when it marks the order delivered. SellerDeliverOrder derives it by
// SUBTRACTING the KosherEats marketplace markup back out of the delivery_fee
// the consumer was charged, so the split is only correct if the markup it uses
// is the one the order was PRICED with — which is why checkout freezes it on
// the row (orders.delivery_markup_cents) instead of leaving delivery to
// recompute it from live config.
//
// SAFETY: DB only. No provider client is configured, so nothing dials out.

import (
	"context"
	"net/http"
	"testing"
)

// sellerEarnings reads what the deliver transition credited the restaurant.
func sellerEarnings(t *testing.T, orderID string) int {
	t.Helper()
	var cents int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COALESCE(seller_delivery_earnings, 0) FROM orders WHERE id = $1`, orderID).Scan(&cents); err != nil {
		t.Fatalf("read seller earnings: %v", err)
	}
	return cents
}

// selfDeliveryOrder seeds a picked_up self-delivery order with a known
// delivery_fee, subtotal and tip. markup is what CreateOrder freezes on the row
// at checkout; a nil markup seeds a pre-058 row that carries no stamp.
func (s *sellerEnv) selfDeliveryOrder(t *testing.T, subtotal, deliveryFee, tip int, markup *int) string {
	t.Helper()
	var id string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, courier_tip, fulfillment_type, delivery_mode, delivery_markup_cents)
		 VALUES ($1, $2, 'picked_up', $3, $4, 0, 0, $5, '2 Oak St', $6, $7, 'delivery', 'restaurant', $8)
		 RETURNING id`,
		s.consumer, s.restID, subtotal, deliveryFee, subtotal+deliveryFee+tip,
		uniqueEmail("pi_payout"), tip, markup,
	).Scan(&id); err != nil {
		t.Fatalf("seed self-delivery order: %v", err)
	}
	return id
}

// pricedAt is the markup stamp CreateOrder would have written for this basket
// under the currently configured tiers.
func pricedAt(h *Handler, subtotal int) *int {
	m := h.deliveryMarkupCents(subtotal)
	return &m
}

// The happy path: the restaurant keeps its own delivery fee (what the consumer
// paid minus the marketplace markup KE keeps) plus 100% of the tip.
func TestIntegration_SelfDeliveryPayoutSplitsFeeAndKeepsTip(t *testing.T) {
	s := newSellerEnv(t)
	h := quoteHandlerWithProviders(t) // markup tiers: 100 / 200 / 300 at 4000 / 8000

	cases := []struct {
		name                             string
		subtotal, deliveryFee, tip, want int
	}{
		{"small basket pays the $1 tier", 2000, 699, 500, 599 + 500},
		{"large basket pays the $2 tier", 5000, 799, 300, 599 + 300},
		{"highest basket pays the $3 tier", 9000, 899, 0, 599},
		// A fee smaller than the markup must clamp at zero, never go negative:
		// a negative credit would silently claw money back from the seller.
		{"fee below the markup clamps to zero", 2000, 50, 250, 0 + 250},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := s.selfDeliveryOrder(t, tc.subtotal, tc.deliveryFee, tc.tip, pricedAt(h, tc.subtotal))
			resp := s.patch(t, id, "deliver", s.token)
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("deliver: status %d", resp.StatusCode)
			}
			if got := sellerEarnings(t, id); got != tc.want {
				t.Errorf("seller_delivery_earnings = %d, want %d (fee %d - markup %d + tip %d)",
					got, tc.want, tc.deliveryFee, h.deliveryMarkupCents(tc.subtotal), tc.tip)
			}
		})
	}
}

// An order escalated to a courier or an external provider was NOT delivered by
// the restaurant, so it must credit nothing even though delivery_mode still
// says 'restaurant'. The CASE guard keys off who actually carried it.
func TestIntegration_SelfDeliveryPayoutSkippedWhenSomeoneElseDelivered(t *testing.T) {
	s := newSellerEnv(t)
	h := quoteHandlerWithProviders(t)

	id := s.selfDeliveryOrder(t, 2000, 699, 500, pricedAt(h, 2000))
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE orders SET external_provider = 'uber_direct', external_delivery_id = 'd1' WHERE id = $1`,
		id); err != nil {
		t.Fatalf("mark escalated: %v", err)
	}

	resp := s.patch(t, id, "deliver", s.token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("deliver: status %d", resp.StatusCode)
	}
	if got := sellerEarnings(t, id); got != 0 {
		t.Errorf("seller_delivery_earnings = %d, want 0 — a provider delivered this order, "+
			"paying the seller a delivery fee too would pay for the same delivery twice", got)
	}
}

// Replaying the deliver transition must not credit the seller a second time.
func TestIntegration_SelfDeliveryPayoutIsNotDoubleCredited(t *testing.T) {
	s := newSellerEnv(t)
	h := quoteHandlerWithProviders(t)

	id := s.selfDeliveryOrder(t, 2000, 699, 500, pricedAt(h, 2000))
	if resp := s.patch(t, id, "deliver", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("first deliver: status %d", resp.StatusCode)
	}
	first := sellerEarnings(t, id)
	// The second tap finds status already 'delivered' and must be rejected.
	if resp := s.patch(t, id, "deliver", s.token); resp.StatusCode == http.StatusOK {
		t.Error("a replayed deliver was accepted on an already-delivered order")
	}
	if got := sellerEarnings(t, id); got != first {
		t.Errorf("seller_delivery_earnings moved from %d to %d on a replay", first, got)
	}
}

// The payout must settle against the markup the order was PRICED with, not the
// one configured at delivery time.
//
// The consumer was charged delivery_fee = restaurant_fee + markup_at_quote_time,
// and that number is frozen on the order row. If SellerDeliverOrder recovered
// the restaurant's share by subtracting h.deliveryMarkupCents(subtotal) — read
// from live config at DELIVERY time — then changing the markup (a config value,
// so a secret edit plus a restart) would re-split every order already placed
// and charged under the old markup: KE keeps the difference on each in-flight
// self-delivery order and the seller is short by exactly the delta. Checkout
// therefore stamps orders.delivery_markup_cents, and the payout reads that.
func TestIntegration_SelfDeliveryPayoutUsesTheMarkupTheOrderWasPricedWith(t *testing.T) {
	s := newSellerEnv(t)
	h := quoteHandlerWithProviders(t)

	// Charged under the $1 markup: the consumer paid 599 restaurant fee + 100.
	const subtotal, deliveryFee = 2000, 699
	chargedMarkup := h.deliveryMarkupCents(subtotal)
	id := s.selfDeliveryOrder(t, subtotal, deliveryFee, 0, pricedAt(h, subtotal))

	// KE raises the small-basket markup to $2 while the order is out for delivery.
	raised := *h.cfg
	raised.DeliveryMarkupCents = 200
	h.cfg = &raised

	if resp := s.patch(t, id, "deliver", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("deliver: status %d", resp.StatusCode)
	}

	want := deliveryFee - chargedMarkup
	if got := sellerEarnings(t, id); got != want {
		t.Errorf("seller_delivery_earnings = %d, want %d — the consumer was charged a %d-cent "+
			"markup, so the restaurant is owed the rest of the fee. The payout re-derived the "+
			"split from live config instead of the markup stamped on the order at checkout, so a "+
			"markup change silently re-split an order that was already charged (seller short by %d).",
			got, want, chargedMarkup, want-got)
	}
}

// Orders placed before migration 058 carry no markup stamp. There is nothing to
// recover the historical value from, so those rows can only fall back to the
// live tiers — the pre-fix behavior, kept so a NULL stamp doesn't panic or pay
// out the whole delivery fee.
func TestIntegration_SelfDeliveryPayoutFallsBackWhenUnstamped(t *testing.T) {
	s := newSellerEnv(t)
	h := quoteHandlerWithProviders(t)

	const subtotal, deliveryFee = 2000, 699
	id := s.selfDeliveryOrder(t, subtotal, deliveryFee, 0, nil)

	if resp := s.patch(t, id, "deliver", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("deliver: status %d", resp.StatusCode)
	}

	want := deliveryFee - h.deliveryMarkupCents(subtotal)
	if got := sellerEarnings(t, id); got != want {
		t.Errorf("seller_delivery_earnings = %d, want %d (live-tier fallback for an unstamped row)", got, want)
	}
}
