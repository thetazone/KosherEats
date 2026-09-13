package handlers

// The deal discount is STAMPED on the PaymentIntent and reused by CreateOrder,
// the same way the delivery fee is.
//
// CreatePaymentIntent validates the deal and charges the discounted total; the
// customer then spends however long the PaymentSheet takes. CreateOrder used to
// re-run resolveDealDiscount from scratch, re-checking is_active and expires_at
// against NOW() — so a promo that lapsed (end-of-day expiry, or the seller
// tapping Deactivate) in that window answered 400 "deal has expired" on a card
// that had ALREADY been charged the discounted amount: charged-but-no-order,
// held until the 20-minute orphan sweep refunded it. The stamp is what the card
// paid, so CreateOrder honors it.
//
// SAFETY: the loopback fake Stripe from stripe_money_guard_test.go; nothing
// leaves the machine.

import (
	"context"
	"fmt"
	"net/http"
	"testing"
	"time"
)

// seedDeal creates a fixed-amount deal on the approved restaurant whose window
// is described by the caller (expired = expires_at already in the past).
func seedDeal(t *testing.T, discountCents int, active bool, expired bool) string {
	t.Helper()
	expires := time.Now().Add(24 * time.Hour)
	if expired {
		expires = time.Now().Add(-time.Minute)
	}
	var id string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`INSERT INTO deals (restaurant_id, title, discount_type, discount_value, is_active,
		   starts_at, expires_at)
		 VALUES ($1, 'Stamp Test Deal', 'fixed', $2, $3, NOW() - INTERVAL '1 hour', $4)
		 RETURNING id`,
		harness.approvedRestID, discountCents, active, expires).Scan(&id); err != nil {
		t.Fatalf("seed deal: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(context.Background(), `DELETE FROM deals WHERE id = $1`, id)
	})
	return id
}

func readOrderDeal(t *testing.T, pi string) (discount int, dealID string) {
	t.Helper()
	var deal *string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT discount_cents, applied_deal_id FROM orders WHERE stripe_payment_id = $1`, pi,
	).Scan(&discount, &deal); err != nil {
		t.Fatalf("read order deal: %v", err)
	}
	if deal != nil {
		dealID = *deal
	}
	return discount, dealID
}

// The reported scenario: a deal that was valid when the intent was minted and
// charged, and has expired (or been deactivated) by the time the order lands.
func TestIntegration_CreateOrderHonorsTheStampedDealAfterItLapses(t *testing.T) {
	for _, tc := range []struct {
		name            string
		active, expired bool
	}{
		{name: "expired", active: true, expired: true},
		{name: "deactivated by the seller", active: false, expired: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fake := installFakeStripe(t)
			e := newMoneyGuardEnv(t, "pi-deal")
			const discount = 300
			dealID := seedDeal(t, discount, tc.active, tc.expired)

			d := discount
			fee := 0
			fake.intents[e.piID] = fakePI{
				// Charged the discounted total: tax on the discounted subtotal.
				amount:      seededItemPrice - discount + (seededItemPrice-discount)*testTaxPercent/100,
				userID:      e.userID,
				fulfillment: "pickup",
				deliveryFee: &fee,
				discount:    &d,
				deal:        dealID,
			}

			rec := e.createOrder(t, map[string]any{
				"fulfillment_type": "pickup",
				"applied_deal_id":  dealID,
			})
			if rec.Code != http.StatusCreated {
				t.Fatalf("status %d, want 201 — the card was already charged the discounted total (body %s)",
					rec.Code, rec.Body.String())
			}
			if fake.refundCount() != 0 {
				t.Errorf("%d refund(s) issued on a successful order", fake.refundCount())
			}
			gotDiscount, gotDeal := readOrderDeal(t, e.piID)
			if gotDiscount != discount || gotDeal != dealID {
				t.Errorf("order recorded discount=%d deal=%q, want the stamped %d/%q",
					gotDiscount, gotDeal, discount, dealID)
			}
		})
	}
}

// The stamp is authoritative over what the client names now: a PI charged with
// NO discount must not gain one from an applied_deal_id supplied only to
// CreateOrder. (The total would disagree with the charge either way; this pins
// that the recorded order carries no deal rather than tripping on the guard.)
func TestIntegration_CreateOrderIgnoresAClientDealTheIntentWasNotChargedWith(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-nodeal")
	dealID := seedDeal(t, 300, true, false)

	zero := 0
	fee := 0
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice + moneyGuardTax,
		userID:      e.userID,
		fulfillment: "pickup",
		deliveryFee: &fee,
		discount:    &zero, // stamped: no deal
	}
	rec := e.createOrder(t, map[string]any{
		"fulfillment_type": "pickup",
		"applied_deal_id":  dealID,
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status %d, want 201 (body %s)", rec.Code, rec.Body.String())
	}
	gotDiscount, gotDeal := readOrderDeal(t, e.piID)
	if gotDiscount != 0 || gotDeal != "" {
		t.Errorf("order recorded discount=%d deal=%q, want none — the charge carried no deal", gotDiscount, gotDeal)
	}
}

// A legacy intent with no discount stamp keeps the old behavior: the deal is
// re-resolved, and a lapsed one is refused before an order exists.
func TestIntegration_CreateOrderStillReResolvesAnUnstampedDeal(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-legacy-deal")
	dealID := seedDeal(t, 300, true, true) // expired

	fee := 0
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice - 300 + (seededItemPrice-300)*testTaxPercent/100,
		userID:      e.userID,
		fulfillment: "pickup",
		deliveryFee: &fee,
		// no discount stamp
	}
	rec := e.createOrder(t, map[string]any{
		"fulfillment_type": "pickup",
		"applied_deal_id":  dealID,
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 for an unstamped, expired deal (body %s)", rec.Code, rec.Body.String())
	}
	if n := orderCountForPI(t, e.piID); n != 0 {
		t.Errorf("%d order(s) created past a refused deal", n)
	}
}

// The once-per-user rule survives the stamp: the DB index rejects a second
// redemption, and the second charge is refunded.
func TestIntegration_StampedDealStillLimitedToOnePerUser(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-deal-twice")
	const discount = 300
	dealID := seedDeal(t, discount, true, false)
	d := discount
	fee := 0
	charged := fakePI{
		amount:      seededItemPrice - discount + (seededItemPrice-discount)*testTaxPercent/100,
		userID:      e.userID,
		fulfillment: "pickup",
		deliveryFee: &fee,
		discount:    &d,
		deal:        dealID,
	}
	fake.intents[e.piID] = charged
	if rec := e.createOrder(t, map[string]any{"fulfillment_type": "pickup"}); rec.Code != http.StatusCreated {
		t.Fatalf("first redemption: status %d (body %s)", rec.Code, rec.Body.String())
	}

	// Same customer, fresh cart, fresh (already charged) intent stamped with the
	// same deal.
	harness.addToCart(t, e.token, harness.approvedRestID, harness.menuItemID)
	second := fmt.Sprintf("pi_guard_second_%d", time.Now().UnixNano())
	fake.intents[second] = charged
	rec := e.createOrder(t, map[string]any{"fulfillment_type": "pickup", "payment_intent_id": second})
	if rec.Code != http.StatusConflict {
		t.Fatalf("second redemption: status %d, want 409 (body %s)", rec.Code, rec.Body.String())
	}
	if n := orderCountForPI(t, second); n != 0 {
		t.Errorf("%d order(s) created for the second redemption", n)
	}
	if fake.refundCount() != 1 {
		t.Errorf("%d refund(s), want exactly 1 — the second charge must be returned", fake.refundCount())
	}
}
