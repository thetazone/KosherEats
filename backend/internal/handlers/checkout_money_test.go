package handlers

// Money-path tests for the charge-then-order flow: CreatePaymentIntent's
// server-side total, the checkout refusal when no courier can be had, and
// CreateOrder's arithmetic, validation and idempotency.
//
// SAFETY: Stripe runs in dev stub mode (the harness leaves STRIPE_SECRET_KEY
// unset), so no PaymentIntent is ever created and VerifyPaymentSucceeded always
// passes. The provider clients are left unconfigured unless a test explicitly
// installs a loopback fake, so no courier API is contacted either.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// ---- CreatePaymentIntent -------------------------------------------------

// intentResponse is the breakdown the client renders at checkout. It has to
// agree with what CreateOrder later records, or the amount-match guard rejects
// the order AFTER the card is charged.
type intentResponse struct {
	Subtotal       int    `json:"subtotal"`
	Discount       int    `json:"discount"`
	DeliveryFee    int    `json:"delivery_fee"`
	DeliveryMethod string `json:"delivery_method"`
	ServiceFee     int    `json:"service_fee"`
	Tax            int    `json:"tax"`
	Tip            int    `json:"tip"`
	Total          int    `json:"total"`
}

func createIntent(t *testing.T, token string, body map[string]any) (*httptest.ResponseRecorder, intentResponse) {
	t.Helper()
	rec := harness.do(http.MethodPost, "/api/v1/payments/intent", token, body)
	var out intentResponse
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode intent: %v (body %s)", err, rec.Body.String())
		}
	}
	return rec, out
}

// The seeded menu item is $15.00 and the harness tax rate is 9%.
const (
	seededItemPrice = 1500
	testTaxPercent  = 9
)

// A pickup order carries no delivery fee and no courier tip: there is no
// courier. Charging either would be money the platform collects for a service
// nobody performs, and CreateOrder (which forces the same zeroes) would then
// disagree with the charge.
func TestIntegration_PickupIntentHasNoDeliveryFeeOrTip(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "pickup-intent")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec, got := createIntent(t, token, map[string]any{
		"fulfillment_type": "pickup",
		"tip":              500, // must be ignored
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}
	if got.DeliveryFee != 0 {
		t.Errorf("delivery_fee = %d, want 0 for pickup", got.DeliveryFee)
	}
	if got.Tip != 0 {
		t.Errorf("tip = %d, want 0 for pickup (no courier to tip)", got.Tip)
	}
	wantTax := seededItemPrice * testTaxPercent / 100
	if got.Tax != wantTax {
		t.Errorf("tax = %d, want %d", got.Tax, wantTax)
	}
	if got.Total != seededItemPrice+wantTax {
		t.Errorf("total = %d, want %d", got.Total, seededItemPrice+wantTax)
	}
	// The total must be exactly the sum of the parts the client is shown.
	if sum := got.Subtotal - got.Discount + got.DeliveryFee + got.ServiceFee + got.Tax + got.Tip; sum != got.Total {
		t.Errorf("breakdown sums to %d but total = %d — the client would display a different number than the charge", sum, got.Total)
	}
}

// The tip is the ONLY money the client supplies, so it is the only input an
// attacker controls. Negative tips must clamp (a negative tip is a discount on
// money that isn't theirs) and a tip larger than the food must be refused.
//
// The bound is only reachable on a delivery order — pickup zeroes the tip
// first — so a loopback fake provider stands in for the courier auction.
func TestIntegration_IntentTipBounds(t *testing.T) {
	cases := []struct {
		name       string
		tip        int
		wantStatus int
		wantTip    int
	}{
		{"negative tip clamps to zero", -5000, http.StatusOK, 0},
		{"zero tip", 0, http.StatusOK, 0},
		{"ordinary tip is charged verbatim", 300, http.StatusOK, 300},
		{"tip equal to subtotal is allowed", seededItemPrice, http.StatusOK, seededItemPrice},
		{"tip above subtotal is refused", seededItemPrice + 1, http.StatusBadRequest, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			net := installFakeProviders(t, true, false, false)
			net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)

			token, _ := harness.registerUser(t, "tip-bounds")
			harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

			rec, got := createIntent(t, token, map[string]any{
				"fulfillment_type": "delivery",
				"restaurant_id":    harness.approvedRestID,
				"delivery_address": "2 Oak St, Brooklyn, NY 11218",
				"tip":              tc.tip,
			})
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if tc.wantStatus != http.StatusOK {
				return
			}
			if got.Tip != tc.wantTip {
				t.Errorf("tip = %d, want %d", got.Tip, tc.wantTip)
			}
			// The tip must be added on top, never folded into another line.
			if sum := got.Subtotal - got.Discount + got.DeliveryFee + got.ServiceFee + got.Tax + got.Tip; sum != got.Total {
				t.Errorf("breakdown sums to %d but total = %d", sum, got.Total)
			}
		})
	}
}

// A working courier auction must produce a real provider name and a fee of
// exactly "cheapest provider cost + marketplace markup" — the number the
// consumer agrees to and CreateOrder later reuses verbatim from the PI stamp.
func TestIntegration_DeliveryIntentChargesQuotePlusMarkup(t *testing.T) {
	harness.resetVolatile(t)
	net := installFakeProviders(t, true, false, true)
	net.on(uberQuotePath, 200, `{"id":"q","fee":1199,"duration":25}`)
	net.on(shipdayQuotePath, 200, `[{"id":"e","name":"DoorDash","fee":6.49,"regulatoryFee":1.99,"pickupDuration":8,"deliveryDuration":12}]`)
	h := quoteHandlerWithProviders(t)

	token, _ := harness.registerUser(t, "delivery-intent")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec, got := createIntent(t, token, map[string]any{
		"fulfillment_type": "delivery",
		"restaurant_id":    harness.approvedRestID,
		"delivery_address": "2 Oak St, Brooklyn, NY 11218",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}
	// Shipday at 6.49+1.99 = 848 beats Uber at 1199.
	if got.DeliveryMethod != "shipday" {
		t.Errorf("delivery_method = %q, want shipday (the cheapest quote)", got.DeliveryMethod)
	}
	wantFee := 848 + h.deliveryMarkupCents(seededItemPrice)
	if got.DeliveryFee != wantFee {
		t.Errorf("delivery_fee = %d, want %d (cheapest cost + markup)", got.DeliveryFee, wantFee)
	}
	if got.DeliveryMethod == "flat_rate" {
		t.Error("a successful auction must never report the flat_rate sentinel")
	}
	if sum := got.Subtotal - got.Discount + got.DeliveryFee + got.ServiceFee + got.Tax + got.Tip; sum != got.Total {
		t.Errorf("breakdown sums to %d but total = %d", sum, got.Total)
	}
}

// An empty cart must never mint a PaymentIntent — a $0 (or fee-only) charge
// with nothing to deliver.
func TestIntegration_IntentRefusesEmptyCart(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "empty-cart")
	rec, _ := createIntent(t, token, map[string]any{"fulfillment_type": "pickup"})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 for an empty cart (body %s)", rec.Code, rec.Body.String())
	}
}

// THE regression from 2026-08-11 (orders 356a73e9 and d2bee10e): when no
// provider can quote, quoteDeliveryFee returns the "flat_rate" fallback. Taking
// that charge produces a paid order no courier can perform — it strands in
// 'ready' with no automatic refund. Checkout must refuse instead.
//
// Enabled() is credential-presence only, so a suspended account still reports
// enabled; the quote OUTCOME is the only honest signal. With no provider
// configured at all the outcome is the same, which is what this exercises.
func TestIntegration_DeliveryIntentRefusedWhenNoProviderCanQuote(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "no-courier")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec, _ := createIntent(t, token, map[string]any{
		"fulfillment_type": "delivery",
		"restaurant_id":    harness.approvedRestID,
		"delivery_address": "2 Oak St, Brooklyn, NY 11218",
	})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503 — charging the flat-rate fallback for a delivery "+
			"nobody can perform is exactly how orders get charged and stranded (body %s)",
			rec.Code, rec.Body.String())
	}

	// And no order/charge may have been created as a side effect.
	var count int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM orders`).Scan(&count); err != nil {
		t.Fatalf("count orders: %v", err)
	}
	if count != 0 {
		t.Errorf("%d orders exist after a refused checkout, want 0", count)
	}
}

// The same refusal must apply when the client supplies no delivery address at
// all: without one there is nothing to quote against, so the flat-rate fallback
// would again charge for an undeliverable order.
func TestIntegration_DeliveryIntentRefusedWithoutAddress(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "no-address")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec, _ := createIntent(t, token, map[string]any{"fulfillment_type": "delivery"})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503 (body %s)", rec.Code, rec.Body.String())
	}
}

// A restaurant that is not accepting orders must stop BEFORE Stripe. Minting a
// PaymentIntent that CreateOrder will then refuse is the charged-but-no-order
// failure mode.
func TestIntegration_IntentRefusesNonOrderableRestaurant(t *testing.T) {
	harness.resetVolatile(t)
	token, userID := harness.registerUser(t, "pending-rest")

	// Seed the cart directly: AddToCart guards on orderability too, so this
	// reproduces a cart that outlived its restaurant's approval.
	var itemID string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT id FROM menu_items WHERE restaurant_id = $1 LIMIT 1`, harness.pendingRestID).Scan(&itemID); err != nil {
		t.Fatalf("find pending item: %v", err)
	}
	var cartID string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`INSERT INTO carts (user_id, restaurant_id) VALUES ($1, $2) RETURNING id`,
		userID, harness.pendingRestID).Scan(&cartID); err != nil {
		t.Fatalf("seed cart: %v", err)
	}
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`INSERT INTO cart_items (cart_id, menu_item_id, quantity, unit_price)
		 VALUES ($1, $2, 1, 1500)`, cartID, itemID); err != nil {
		t.Fatalf("seed cart item: %v", err)
	}

	rec, _ := createIntent(t, token, map[string]any{"fulfillment_type": "pickup"})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status %d, want 403 before any Stripe call (body %s)", rec.Code, rec.Body.String())
	}
}

// ---- CreateOrder ---------------------------------------------------------

// The recorded order must be internally consistent: total = subtotal - discount
// + fees + tax + tip, items persisted, cart emptied. A mismatch here is what
// the Stripe amount-match guard rejects after the card is already charged.
func TestIntegration_CreateOrderRecordsConsistentTotals(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "totals")
	pi := fmt.Sprintf("pi_totals_%d", time.Now().UnixNano())

	// Two of the seeded item so the subtotal isn't trivially the unit price.
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec := harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
		"restaurant_id":     harness.approvedRestID,
		"payment_intent_id": pi,
		"fulfillment_type":  "pickup",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create order: status %d, body %s", rec.Code, rec.Body.String())
	}

	var o struct {
		ID          string `json:"id"`
		Subtotal    int    `json:"subtotal"`
		Discount    int    `json:"discount"`
		DeliveryFee int    `json:"delivery_fee"`
		ServiceFee  int    `json:"service_fee"`
		Tax         int    `json:"tax"`
		Total       int    `json:"total"`
		CourierTip  int    `json:"courier_tip"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &o); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if want := o.Subtotal - o.Discount + o.DeliveryFee + o.ServiceFee + o.Tax + o.CourierTip; want != o.Total {
		t.Errorf("total = %d but the parts sum to %d", o.Total, want)
	}
	if o.Subtotal != 2*seededItemPrice {
		t.Errorf("subtotal = %d, want %d (two items)", o.Subtotal, 2*seededItemPrice)
	}
	if o.Tax != o.Subtotal*testTaxPercent/100 {
		t.Errorf("tax = %d, want %d%% of the discounted subtotal", o.Tax, testTaxPercent)
	}

	// Line items must be persisted — an order with no items can't be cooked.
	var itemCount, itemTotal int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*), COALESCE(SUM(price * quantity), 0) FROM order_items WHERE order_id = $1`,
		o.ID).Scan(&itemCount, &itemTotal); err != nil {
		t.Fatalf("count items: %v", err)
	}
	if itemCount == 0 {
		t.Fatal("no order_items persisted")
	}
	if itemTotal != o.Subtotal {
		t.Errorf("order_items sum to %d but the order subtotal is %d — the customer is charged for a different basket than the kitchen sees", itemTotal, o.Subtotal)
	}

	// The cart must be gone, or the next checkout re-charges the same food.
	var carts int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM carts`).Scan(&carts); err != nil {
		t.Fatalf("count carts: %v", err)
	}
	if carts != 0 {
		t.Errorf("%d carts survived order creation, want 0", carts)
	}
}

// fulfillment_type is trusted for the delivery-fee and tip lanes, so it must be
// validated rather than defaulted from an arbitrary string.
func TestIntegration_CreateOrderValidatesFulfillmentType(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "fulfillment")

	cases := []struct {
		name       string
		payload    map[string]any
		wantStatus int
	}{
		{"unknown type", map[string]any{"fulfillment_type": "teleport"}, http.StatusBadRequest},
		{"delivery with no address", map[string]any{"fulfillment_type": "delivery"}, http.StatusBadRequest},
		{"empty defaults to delivery, so it also needs an address", map[string]any{}, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
			payload := map[string]any{
				"restaurant_id":     harness.approvedRestID,
				"payment_intent_id": fmt.Sprintf("pi_ff_%d", time.Now().UnixNano()),
			}
			for k, v := range tc.payload {
				payload[k] = v
			}
			rec := harness.do(http.MethodPost, "/api/v1/orders/", token, payload)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			var count int
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT COUNT(*) FROM orders`).Scan(&count); err != nil {
				t.Fatalf("count: %v", err)
			}
			if count != 0 {
				t.Errorf("%d orders created despite a rejected request", count)
			}
		})
	}
}

// The card is charged BEFORE CreateOrder runs, so a double-submit (a retried
// tap, a flaky network) must converge on ONE order rather than two orders for
// one charge — or a 4xx that makes a successful charge look failed.
//
// The unique index on stripe_payment_id plus the ON CONFLICT DO NOTHING is what
// makes this hold even when both requests are in flight at once.
func TestIntegration_ConcurrentCreateOrderYieldsExactlyOneOrder(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "double-tap")
	pi := fmt.Sprintf("pi_race_%d", time.Now().UnixNano())
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	payload := map[string]any{
		"restaurant_id":     harness.approvedRestID,
		"payment_intent_id": pi,
		"fulfillment_type":  "pickup",
	}

	const racers = 5
	var wg sync.WaitGroup
	codes := make([]int, racers)
	ids := make([]string, racers)
	for i := 0; i < racers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			rec := harness.do(http.MethodPost, "/api/v1/orders/", token, payload)
			codes[i] = rec.Code
			var o struct {
				ID string `json:"id"`
			}
			_ = json.Unmarshal(rec.Body.Bytes(), &o)
			ids[i] = o.ID
		}(i)
	}
	wg.Wait()

	var count int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM orders WHERE stripe_payment_id = $1`, pi).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Fatalf("%d orders exist for one PaymentIntent, want 1 — the customer paid once", count)
	}

	// Every racer that got a 2xx must name the SAME order; a 2xx naming nothing
	// (or a different order) would leave the client tracking a phantom.
	var winner string
	for i, code := range codes {
		if code != http.StatusCreated && code != http.StatusOK {
			continue // a losing racer may legitimately 4xx on "cart is empty"
		}
		if ids[i] == "" {
			t.Errorf("racer %d returned %d with no order id", i, code)
			continue
		}
		if winner == "" {
			winner = ids[i]
		} else if ids[i] != winner {
			t.Errorf("racers converged on different orders: %s vs %s", winner, ids[i])
		}
	}
	if winner == "" {
		t.Fatal("no racer succeeded, but the card was already charged")
	}
}

// An order for a restaurant that stopped accepting orders between add-to-cart
// and checkout must be refused — a preview listing must never take an order.
func TestIntegration_CreateOrderRechecksOrderabilityAtOrderTime(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "deapproved")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	// The restaurant loses approval while the cart sits.
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE restaurants SET approval_status = 'pending' WHERE id = $1`, harness.approvedRestID); err != nil {
		t.Fatalf("de-approve: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(context.Background(),
			`UPDATE restaurants SET approval_status = 'approved' WHERE id = $1`, harness.approvedRestID)
	})

	rec := harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
		"restaurant_id":     harness.approvedRestID,
		"payment_intent_id": fmt.Sprintf("pi_deapp_%d", time.Now().UnixNano()),
		"fulfillment_type":  "pickup",
	})
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status %d, want 403 (body %s)", rec.Code, rec.Body.String())
	}
}

// A scheduled order more than 30 minutes out starts in 'scheduled', not
// 'pending': firing the seller's "new order — tap to accept" push now would be
// noise they cannot act on.
func TestIntegration_FarFutureOrderStartsScheduled(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "scheduler")

	for _, tc := range []struct {
		name string
		when time.Time
		want string
	}{
		{"two hours out is scheduled", time.Now().Add(2 * time.Hour), "scheduled"},
		{"ten minutes out is pending", time.Now().Add(10 * time.Minute), "pending"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
			rec := harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
				"restaurant_id":     harness.approvedRestID,
				"payment_intent_id": fmt.Sprintf("pi_sched_%d", time.Now().UnixNano()),
				"fulfillment_type":  "pickup",
				"scheduled_for":     tc.when.Format(time.RFC3339),
			})
			if rec.Code != http.StatusCreated {
				t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
			}
			var o struct {
				Status string `json:"status"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &o); err != nil {
				t.Fatalf("decode: %v", err)
			}
			if o.Status != tc.want {
				t.Errorf("status = %q, want %q", o.Status, tc.want)
			}
		})
	}
}
