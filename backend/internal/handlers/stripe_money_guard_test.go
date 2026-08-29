package handlers

// The charge↔order money guards in CreateOrder: the four checks that decide
// whether a card that has ALREADY been charged is allowed to become an order.
//
// These were the only completely unexercised money path in the package. The
// shared harness leaves STRIPE_SECRET_KEY unset, which puts payments.Client in
// dev stub mode — there, VerifyPaymentSucceeded returns nil unconditionally and
// every Stamped* reader answers ok=false, so CreateOrder skips the
// fulfillment-type guard, the delivery-address guard, the stamped-fee reuse and
// the amount match. Every existing checkout test therefore proves only that the
// guards are not reached.
//
// This file runs those guards for real by pointing stripe-go's backend at a
// local httptest server that speaks just enough of the PaymentIntents and
// Refunds API, and installing a payments.Client whose key LOOKS real (so
// c.enabled is true) but which can only ever reach that loopback server.
//
// SAFETY: no request leaves the machine. The fake backend is constructed with
// its own http.Client bound to the httptest server, api.stripe.com is never
// resolved, and the "secret key" is a literal string of x's. No provider client
// is configured either, so the courier auction falls through to the flat rate
// without dialing out.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stripe/stripe-go/v78"

	"github.com/koshereats/backend/internal/payments"
)

// ---- the loopback Stripe --------------------------------------------------

// fakePI is the subset of a PaymentIntent CreateOrder actually reads: the
// amount and status the amount-match guard asserts on, the metadata the three
// Stamped* readers consume, and the latest_charge refund state that stops a
// refunded PI from being redeemed a second time.
type fakePI struct {
	amount      int
	status      string // defaults to "succeeded"
	userID      string
	fulfillment string // "" = unstamped (legacy PI)
	deliveryFee *int   // nil = unstamped
	addrHash    string // "" = unstamped
	refunded    bool
}

func (p fakePI) json(id string) string {
	meta := map[string]string{}
	if p.userID != "" {
		meta["user_id"] = p.userID
	}
	if p.fulfillment != "" {
		meta["fulfillment_type"] = p.fulfillment
	}
	if p.deliveryFee != nil {
		meta["delivery_fee"] = strconv.Itoa(*p.deliveryFee)
	}
	if p.addrHash != "" {
		meta["delivery_addr_hash"] = p.addrHash
	}
	status := p.status
	if status == "" {
		status = "succeeded"
	}
	obj := map[string]any{
		"id": id, "object": "payment_intent",
		"amount": p.amount, "status": status, "metadata": meta,
	}
	if p.refunded {
		// A fully refunded PaymentIntent KEEPS status 'succeeded' — the refund
		// lives on the Charge — which is exactly why verifyPI expands it.
		obj["latest_charge"] = map[string]any{
			"id": "ch_fake", "object": "charge",
			"refunded": true, "amount_refunded": p.amount,
		}
	}
	b, _ := json.Marshal(obj)
	return string(b)
}

// fakeStripe serves the two endpoints CreateOrder can reach.
type fakeStripe struct {
	mu      sync.Mutex
	intents map[string]fakePI
	refunds []string
}

func (f *fakeStripe) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if r.URL.Path == "/v1/refunds" {
		_ = r.ParseForm()
		f.mu.Lock()
		f.refunds = append(f.refunds, r.PostForm.Get("payment_intent"))
		f.mu.Unlock()
		_, _ = w.Write([]byte(`{"id":"re_fake","object":"refund","status":"succeeded"}`))
		return
	}

	if id, ok := strings.CutPrefix(r.URL.Path, "/v1/payment_intents/"); ok && id != "" {
		f.mu.Lock()
		pi, known := f.intents[id]
		f.mu.Unlock()
		if !known {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"type":"invalid_request_error","message":"No such payment_intent"}}`))
			return
		}
		_, _ = w.Write([]byte(pi.json(id)))
		return
	}

	w.WriteHeader(http.StatusNotFound)
	_, _ = w.Write([]byte(`{"error":{"message":"unrouted in test"}}`))
}

func (f *fakeStripe) refundCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.refunds)
}

// installFakeStripe swaps the harness handler onto a live-mode payments.Client
// whose every call lands on a loopback server, and restores the stub client
// (and stripe-go's package globals) afterwards.
func installFakeStripe(t *testing.T) *fakeStripe {
	t.Helper()
	fake := &fakeStripe{intents: map[string]fakePI{}}
	srv := httptest.NewServer(fake)
	t.Cleanup(srv.Close)

	// Own http.Client bound to the test server, so this never consults
	// http.DefaultTransport (which installFakeProviders may have replaced) and
	// can never reach api.stripe.com.
	origBackends := stripe.GetBackend(stripe.APIBackend)
	stripe.SetBackend(stripe.APIBackend, stripe.GetBackendWithConfig(stripe.APIBackend, &stripe.BackendConfig{
		URL:               stripe.String(srv.URL),
		HTTPClient:        srv.Client(),
		MaxNetworkRetries: stripe.Int64(0),
		LeveledLogger:     &stripe.LeveledLogger{Level: stripe.LevelNull},
	}))
	origKey := stripe.Key
	t.Cleanup(func() {
		stripe.SetBackend(stripe.APIBackend, origBackends)
		stripe.Key = origKey
	})

	h := harness.h
	origClient := h.stripe
	t.Cleanup(func() { h.stripe = origClient })

	cfg := *h.cfg
	// Long enough and not ending in "_key", so looksLikeRealStripeKey accepts it
	// and payments.New flips the client out of stub mode. It is not a credential:
	// the only server it can address is srv.
	cfg.StripeSecretKey = "sk_test_" + strings.Repeat("x", 40)
	h.stripe = payments.New(&cfg)
	return fake
}

// ---- fixtures -------------------------------------------------------------

// moneyGuardEnv is a fully-verified consumer with one seeded item in the cart.
type moneyGuardEnv struct {
	token  string
	userID string
	piID   string
}

func newMoneyGuardEnv(t *testing.T, prefix string) *moneyGuardEnv {
	t.Helper()
	harness.resetVolatile(t)
	token, userID := harness.registerUser(t, prefix)
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	return &moneyGuardEnv{
		token:  token,
		userID: userID,
		piID:   fmt.Sprintf("pi_guard_%d", time.Now().UnixNano()),
	}
}

func (e *moneyGuardEnv) createOrder(t *testing.T, body map[string]any) *httptest.ResponseRecorder {
	t.Helper()
	if _, ok := body["payment_intent_id"]; !ok {
		body["payment_intent_id"] = e.piID
	}
	if _, ok := body["restaurant_id"]; !ok {
		body["restaurant_id"] = harness.approvedRestID
	}
	return harness.do(http.MethodPost, "/api/v1/orders/", e.token, body)
}

// orderCount reports how many orders exist for this PaymentIntent. Every
// refusal below must leave it at zero: the card is already charged, so an order
// created past a failed guard is the exploit, and no order created is the
// charged-but-no-order failure the guards deliberately accept as the lesser
// evil.
func orderCountForPI(t *testing.T, pi string) int {
	t.Helper()
	var n int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM orders WHERE stripe_payment_id = $1`, pi).Scan(&n); err != nil {
		t.Fatalf("count orders for %s: %v", pi, err)
	}
	return n
}

// The seeded item is $15.00 and the harness tax rate is 9%; the harness config
// leaves every markup tier at 0.
const moneyGuardTax = seededItemPrice * testTaxPercent / 100

// ---- the fulfillment-type stamp ------------------------------------------

// A pickup PaymentIntent is priced with no delivery fee and a forced-zero tip.
// Redeeming one on a delivery order would ship the food for free, because
// CreateOrder reuses the stamped (zero) delivery fee verbatim. The stamp must
// bind the two together.
func TestIntegration_CreateOrderRejectsAPickupIntentRedeemedOnDelivery(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-fulfil")

	zero := 0
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice + moneyGuardTax,
		userID:      e.userID,
		fulfillment: "pickup",
		deliveryFee: &zero,
	}

	rec := e.createOrder(t, map[string]any{
		"fulfillment_type": "delivery",
		"delivery_address": "2 Oak St, Brooklyn, NY 11218",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 — a pickup PI redeemed on a delivery order ships for free (body %s)",
			rec.Code, rec.Body.String())
	}
	if n := orderCountForPI(t, e.piID); n != 0 {
		t.Errorf("%d orders created for a refused PI, want 0", n)
	}
}

// The mirror image, and the reason the guard compares rather than merely
// requires a stamp: matching stamps must sail through.
func TestIntegration_CreateOrderAcceptsAMatchingFulfillmentStamp(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-fulfil-ok")

	zero := 0
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice + moneyGuardTax,
		userID:      e.userID,
		fulfillment: "pickup",
		deliveryFee: &zero,
	}

	rec := e.createOrder(t, map[string]any{"fulfillment_type": "pickup"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status %d, want 201 (body %s)", rec.Code, rec.Body.String())
	}
}

// An unstamped (pre-guard, still in-flight) PaymentIntent must stay redeemable
// — the guard is a comparison, not a requirement, so rolling it out could not
// strand checkouts that were already open.
func TestIntegration_CreateOrderAllowsAnUnstampedLegacyIntent(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-legacy")

	// No fulfillment/fee/address metadata at all: only the user binding, which
	// verifyPI still requires.
	fake.intents[e.piID] = fakePI{
		amount: seededItemPrice + deliveryFeeFallbackCents + moneyGuardTax,
		userID: e.userID,
	}

	rec := e.createOrder(t, map[string]any{
		"fulfillment_type": "delivery",
		"delivery_address": "2 Oak St, Brooklyn, NY 11218",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status %d, want 201 — an unstamped legacy PI must remain redeemable (body %s)",
			rec.Code, rec.Body.String())
	}
}

// ---- the delivery-address stamp ------------------------------------------

// The delivery fee scales with distance, so a client could quote cheaply
// against a nearby address and then redeem the same PaymentIntent on a distant
// one — CreateOrder reuses the stamped cheap fee verbatim, and the platform
// eats the difference. The destination is bound to the fee by a hash stamp.
func TestIntegration_CreateOrderRejectsASwappedDeliveryAddress(t *testing.T) {
	const quotedAddr = "2 Oak St, Brooklyn, NY 11218"
	const swappedAddr = "9000 Remote Rd, Montauk, NY 11954"

	fee := 599
	cases := []struct {
		name       string
		orderAddr  string
		wantStatus int
	}{
		{"the address the fee was quoted against", quotedAddr, http.StatusCreated},
		// Only whitespace/case differ: DeliveryAddrHash normalizes, so the
		// legitimate client that re-sends the same selected address always
		// matches even if a keyboard or a form added padding.
		{"same address, different spacing", "  2  oak st,  Brooklyn, NY 11218 ", http.StatusCreated},
		{"a different, farther destination", swappedAddr, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fake := installFakeStripe(t)
			e := newMoneyGuardEnv(t, "pi-addr")
			fake.intents[e.piID] = fakePI{
				amount:      seededItemPrice + fee + moneyGuardTax,
				userID:      e.userID,
				fulfillment: "delivery",
				deliveryFee: &fee,
				addrHash:    payments.DeliveryAddrHash(quotedAddr),
			}

			rec := e.createOrder(t, map[string]any{
				"fulfillment_type": "delivery",
				"delivery_address": tc.orderAddr,
			})
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if tc.wantStatus != http.StatusCreated {
				if n := orderCountForPI(t, e.piID); n != 0 {
					t.Errorf("%d orders created for a refused PI, want 0", n)
				}
			}
		})
	}
}

// ---- the stamped delivery fee --------------------------------------------

// The recorded order must be priced against the fee the card was CHARGED for,
// not against a fresh courier quote. Nothing here configures a provider, so the
// live re-quote inside CreateOrder produces the flat fallback — a value nothing
// like the stamp. If the stamp did not win, the recorded delivery_fee would be
// 599 and the total would miss the amount match by dollars.
func TestIntegration_CreateOrderPricesTheOrderFromTheStampedFee(t *testing.T) {
	const stamped = 1234 // deliberately unlike deliveryFeeFallbackCents
	if stamped == deliveryFeeFallbackCents {
		t.Fatal("the stamped fee must differ from the fallback or this test proves nothing")
	}
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-fee")

	fee := stamped
	const tip = 300
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice + stamped + moneyGuardTax + tip,
		userID:      e.userID,
		fulfillment: "delivery",
		deliveryFee: &fee,
		addrHash:    payments.DeliveryAddrHash("2 Oak St, Brooklyn, NY 11218"),
	}

	rec := e.createOrder(t, map[string]any{
		"fulfillment_type": "delivery",
		"delivery_address": "2 Oak St, Brooklyn, NY 11218",
		"tip":              tip,
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status %d, want 201 (body %s)", rec.Code, rec.Body.String())
	}

	var gotFee, gotTotal, gotSubtotal, gotTax, gotTip int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT delivery_fee, total, subtotal, tax, COALESCE(courier_tip, 0)
		   FROM orders WHERE stripe_payment_id = $1`, e.piID,
	).Scan(&gotFee, &gotTotal, &gotSubtotal, &gotTax, &gotTip); err != nil {
		t.Fatalf("read order: %v", err)
	}
	if gotFee != stamped {
		t.Errorf("delivery_fee = %d, want the stamped %d (a live re-quote would have recorded %d)",
			gotFee, stamped, deliveryFeeFallbackCents)
	}
	// The recorded total is what the seller is paid against and what a dispute
	// is argued from, so it has to equal the charge exactly.
	want := gotSubtotal + gotFee + gotTax + gotTip
	if gotTotal != want {
		t.Errorf("total = %d, want %d (subtotal+fee+tax+tip)", gotTotal, want)
	}
	if gotTotal != seededItemPrice+stamped+moneyGuardTax+tip {
		t.Errorf("total = %d, want %d — the recorded order disagrees with the amount charged",
			gotTotal, seededItemPrice+stamped+moneyGuardTax+tip)
	}
}

// ---- the amount / ownership / refund guards ------------------------------

// VerifyPaymentSucceeded is the last line before an order exists. Each of these
// must refuse, and each must leave NO order behind — a charged card with no
// order is recoverable by the orphan sweep, an order for an unpaid or
// someone-else's charge is not.
func TestIntegration_CreateOrderRefusesUnverifiablePayments(t *testing.T) {
	addr := "2 Oak St, Brooklyn, NY 11218"
	fee := 599
	good := func(userID string) fakePI {
		f := fee
		return fakePI{
			amount:      seededItemPrice + fee + moneyGuardTax,
			userID:      userID,
			fulfillment: "delivery",
			deliveryFee: &f,
			addrHash:    payments.DeliveryAddrHash(addr),
		}
	}

	cases := []struct {
		name   string
		mutate func(pi *fakePI, callerID string)
	}{
		{
			// One cent short. The guard has to be exact: a tolerance is a
			// discount an attacker sets.
			name:   "amount one cent below the order total",
			mutate: func(pi *fakePI, _ string) { pi.amount-- },
		},
		{
			name:   "amount above the order total",
			mutate: func(pi *fakePI, _ string) { pi.amount += 5000 },
		},
		{
			// The card was never actually charged.
			name:   "payment intent never succeeded",
			mutate: func(pi *fakePI, _ string) { pi.status = "requires_payment_method" },
		},
		{
			// The orphan sweep already refunded this PI. Status stays
			// 'succeeded', so only the expanded charge reveals it — without that
			// expansion this is a free order.
			name:   "payment intent already refunded",
			mutate: func(pi *fakePI, _ string) { pi.refunded = true },
		},
		{
			// The write-path IDOR: an attacker who learns a victim's
			// payment_intent_id gets an order charged to the victim's card.
			name:   "payment intent belongs to another user",
			mutate: func(pi *fakePI, _ string) { pi.userID = "00000000-0000-0000-0000-000000000001" },
		},
		{
			name:   "payment intent carries no user binding",
			mutate: func(pi *fakePI, _ string) { pi.userID = "" },
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fake := installFakeStripe(t)
			e := newMoneyGuardEnv(t, "pi-verify")
			pi := good(e.userID)
			tc.mutate(&pi, e.userID)
			fake.intents[e.piID] = pi

			rec := e.createOrder(t, map[string]any{
				"fulfillment_type": "delivery",
				"delivery_address": addr,
			})
			if rec.Code != http.StatusPaymentRequired {
				t.Fatalf("status %d, want 402 (body %s)", rec.Code, rec.Body.String())
			}
			if n := orderCountForPI(t, e.piID); n != 0 {
				t.Errorf("%d orders created despite an unverifiable payment, want 0", n)
			}
			// The cart must survive a refusal, or the customer loses their
			// basket to a payment problem they can still fix.
			var items int
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT COUNT(*) FROM cart_items ci JOIN carts c ON ci.cart_id = c.id WHERE c.user_id = $1`,
				e.userID).Scan(&items); err != nil {
				t.Fatalf("count cart items: %v", err)
			}
			if items == 0 {
				t.Errorf("cart was cleared by a refused order — the customer lost their basket")
			}
		})
	}
}

// A PaymentIntent Stripe has never heard of must not create an order either:
// the retrieve errors, VerifyPaymentSucceeded surfaces it, and the answer is
// the same 402.
func TestIntegration_CreateOrderRefusesAnUnknownPaymentIntent(t *testing.T) {
	installFakeStripe(t) // no intents registered
	e := newMoneyGuardEnv(t, "pi-unknown")

	rec := e.createOrder(t, map[string]any{"fulfillment_type": "pickup"})
	if rec.Code != http.StatusPaymentRequired {
		t.Fatalf("status %d, want 402 (body %s)", rec.Code, rec.Body.String())
	}
	if n := orderCountForPI(t, e.piID); n != 0 {
		t.Errorf("%d orders created for an unknown PI, want 0", n)
	}
}

// The idempotent replay short-circuit has to hold in live mode too: a client
// that retries after losing the response gets the SAME order back, and the card
// is never charged into a second one.
func TestIntegration_CreateOrderReplayIsIdempotentInLiveMode(t *testing.T) {
	fake := installFakeStripe(t)
	e := newMoneyGuardEnv(t, "pi-replay")
	fake.intents[e.piID] = fakePI{
		amount:      seededItemPrice + moneyGuardTax,
		userID:      e.userID,
		fulfillment: "pickup",
	}

	first := e.createOrder(t, map[string]any{"fulfillment_type": "pickup"})
	if first.Code != http.StatusCreated {
		t.Fatalf("first create: status %d (body %s)", first.Code, first.Body.String())
	}
	// The first create cleared the cart, so a naive retry would 400 "cart is
	// empty" for what was in fact a success.
	second := e.createOrder(t, map[string]any{"fulfillment_type": "pickup"})
	if second.Code != http.StatusOK {
		t.Fatalf("replay: status %d, want 200 (body %s)", second.Code, second.Body.String())
	}

	var firstOrder, secondOrder struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &firstOrder)
	_ = json.Unmarshal(second.Body.Bytes(), &secondOrder)
	if firstOrder.ID == "" || firstOrder.ID != secondOrder.ID {
		t.Errorf("replay returned order %q, want the original %q", secondOrder.ID, firstOrder.ID)
	}
	if n := orderCountForPI(t, e.piID); n != 1 {
		t.Errorf("%d orders exist for one PaymentIntent, want 1", n)
	}
	if fake.refundCount() != 0 {
		t.Errorf("a replay issued %d refunds, want 0", fake.refundCount())
	}
}
