package handlers

// Restaurant (seller) payouts: the per-order ledger line every successful
// terminal state records, the sweep that resolves processing fees / transfers /
// reverses, the Stripe webhook hooks, and the seller-facing payout endpoints.
//
// SAFETY: DB only. The handler's Stripe client is in dev stub mode, and every
// sweep here runs against fakeRestaurantStripe, so nothing dials out.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/payments"
	"github.com/koshereats/backend/internal/restaurantpayout"
)

// ---- fakes & helpers -----------------------------------------------------

type fakeReversal struct {
	transferID string
	amount     int
	key        string
}

// fakeRestaurantStripe stands in for *payments.Client in the sweep.
type fakeRestaurantStripe struct {
	mu         sync.Mutex
	fees       map[string]payments.ChargeFee // keyed by PaymentIntent
	defaultFee int
	transfers  []payments.RestaurantTransfer
	reversals  []fakeReversal
}

func newFakeRestaurantStripe() *fakeRestaurantStripe {
	return &fakeRestaurantStripe{fees: map[string]payments.ChargeFee{}}
}

func (f *fakeRestaurantStripe) ChargeFeeForPaymentIntent(pi string) (payments.ChargeFee, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if cf, ok := f.fees[pi]; ok {
		return cf, nil
	}
	return payments.ChargeFee{ChargeID: "ch_" + pi, FeeCents: f.defaultFee}, nil
}

func (f *fakeRestaurantStripe) TransferToRestaurant(t payments.RestaurantTransfer) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.transfers = append(f.transfers, t)
	return fmt.Sprintf("tr_fake_%d", len(f.transfers)), nil
}

func (f *fakeRestaurantStripe) ReverseRestaurantTransfer(transferID string, amount int, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reversals = append(f.reversals, fakeReversal{transferID, amount, key})
	return nil
}

func (f *fakeRestaurantStripe) FindRestaurantTransfer(string, string, time.Time) (string, int, error) {
	return "", 0, nil
}

func (f *fakeRestaurantStripe) transfersFor(orderID string) []payments.RestaurantTransfer {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []payments.RestaurantTransfer
	for _, t := range f.transfers {
		if t.OrderID == orderID {
			out = append(out, t)
		}
	}
	return out
}

func (f *fakeRestaurantStripe) reversalsOf(transferID string) []fakeReversal {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []fakeReversal
	for _, r := range f.reversals {
		if r.transferID == transferID {
			out = append(out, r)
		}
	}
	return out
}

func newTestPayoutProcessor(fake *fakeRestaurantStripe, enabled bool) *restaurantpayout.Processor {
	return restaurantpayout.NewProcessor(harness.h.db.Pool, fake, enabled, harness.h.deliveryMarkupCents, nil)
}

type payoutLineRow struct {
	id, fulfillment, status, chargeID, transferID string
	food, tax, delivery, tip, ke, processing, net int
	reversed, transferred, refunded, total        int
	resolved                                      bool
	paidAt                                        *time.Time
}

func payoutLineCount(t *testing.T, orderID string) int {
	t.Helper()
	var n int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM restaurant_payout_lines WHERE order_id = $1`, orderID).Scan(&n); err != nil {
		t.Fatalf("count lines: %v", err)
	}
	return n
}

func readPayoutLine(t *testing.T, orderID string) payoutLineRow {
	t.Helper()
	var l payoutLineRow
	if err := harness.h.db.Pool.QueryRow(context.Background(), `
		SELECT id, fulfillment, status, COALESCE(charge_id, ''), COALESCE(transfer_id, ''),
		       food_subtotal_cents, sales_tax_cents, delivery_fee_cents, tip_cents, ke_fee_cents,
		       processing_fee_cents, net_cents, reversed_cents, transferred_cents, refunded_cents,
		       order_total_cents, processing_fee_resolved, paid_at
		  FROM restaurant_payout_lines WHERE order_id = $1`, orderID).Scan(
		&l.id, &l.fulfillment, &l.status, &l.chargeID, &l.transferID,
		&l.food, &l.tax, &l.delivery, &l.tip, &l.ke, &l.processing, &l.net,
		&l.reversed, &l.transferred, &l.refunded, &l.total, &l.resolved, &l.paidAt); err != nil {
		t.Fatalf("read payout line for order %s: %v", orderID, err)
	}
	return l
}

// pickupOrder seeds a ready pickup order on the seller's restaurant.
func (s *sellerEnv) pickupOrder(t *testing.T, subtotal, discount, tax int) string {
	t.Helper()
	return s.order(t, "ready", func(id string) {
		if _, err := harness.h.db.Pool.Exec(context.Background(), `
			UPDATE orders SET fulfillment_type = 'pickup', delivery_fee = 0, courier_tip = 0,
			       subtotal = $2, discount_cents = $3, discount_amount = $3, tax = $4,
			       total = $2::int - $3::int + $4::int, delivery_address = ''
			 WHERE id = $1`, id, subtotal, discount, tax); err != nil {
			t.Fatalf("make pickup order: %v", err)
		}
	})
}

func paymentIntentOf(t *testing.T, orderID string) (pi string, total int) {
	t.Helper()
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT stripe_payment_id, total FROM orders WHERE id = $1`, orderID).Scan(&pi, &total); err != nil {
		t.Fatalf("read payment intent: %v", err)
	}
	return pi, total
}

// sellerPayoutRouter mirrors the production /seller payout + agreement routes.
func sellerPayoutRouter(h *Handler) http.Handler {
	r := chi.NewRouter()
	r.Route("/api/v1/seller", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.SellerMiddleware)
		r.Post("/payouts/account", h.SellerCreatePayoutAccount)
		r.Get("/payouts/link", h.SellerGetPayoutLink)
		r.Get("/payouts/status", h.SellerGetPayoutStatus)
		r.Get("/payouts/summary", h.SellerPayoutSummary)
		r.Get("/payouts", h.SellerListPayouts)
		r.Get("/agreement", h.SellerGetAgreement)
		r.Post("/agreement/accept", h.SellerAcceptAgreement)
	})
	return r
}

// doRequestWithHeader is doRequest plus one extra request header (skipped
// when the value is empty).
func doRequestWithHeader(router http.Handler, method, path, token string, body any, key, value string) *httptest.ResponseRecorder {
	var rdr *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	} else {
		rdr = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, rdr)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if value != "" {
		req.Header.Set(key, value)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

func decodeJSON[T any](t *testing.T, body []byte) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(body, &v); err != nil {
		t.Fatalf("decode %s: %v", string(body), err)
	}
	return v
}

// makePayoutReady onboards the seller's restaurant through the real endpoint
// (stub Stripe reports a fully onboarded account) and returns the connect id.
func (s *sellerEnv) makePayoutReady(t *testing.T) string {
	t.Helper()
	rec := doRequest(sellerPayoutRouter(harness.h), http.MethodPost,
		"/api/v1/seller/payouts/account?restaurant_id="+s.restID, s.token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("payouts/account: %d %s", rec.Code, rec.Body.String())
	}
	st := decodeJSON[RestaurantPayoutStatusResponse](t, rec.Body.Bytes())
	if !st.PayoutReady || st.ConnectID == "" {
		t.Fatalf("payouts/account: %+v, want ready with a connect id", st)
	}
	return st.ConnectID
}

func postChargeRefunded(t *testing.T, pi string, refunded, amount int) {
	t.Helper()
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	body := stripeEventBody(uniqueEventID(), "charge.refunded",
		fmt.Sprintf(`{"id":"ch_%s","object":"charge","payment_intent":%q,"amount_refunded":%d,"amount":%d}`,
			pi, pi, refunded, amount))
	if rec := postStripeWebhook(t, h, body); rec.Code != http.StatusOK {
		t.Fatalf("charge.refunded webhook: %d %s", rec.Code, rec.Body.String())
	}
}

// ---- ledger line on every terminal path -----------------------------------

func TestIntegration_RestaurantPayoutLineRecordedOnEveryTerminalPath(t *testing.T) {
	t.Run("pickup completed by the seller", func(t *testing.T) {
		s := newSellerEnv(t)
		id := s.pickupOrder(t, 2000, 300, 151)
		if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
			t.Fatalf("complete: %d", resp.StatusCode)
		}
		l := readPayoutLine(t, id)
		// Deal is restaurant-funded: food = 2000 - 300. KE fee = 5% of 1700 = 85.
		if l.fulfillment != "pickup" || l.food != 1700 || l.tax != 151 || l.ke != 85 ||
			l.delivery != 0 || l.tip != 0 || l.net != 1700+151-85 || l.resolved {
			t.Fatalf("pickup line = %+v", l)
		}
		if l.status != "awaiting_account" {
			t.Errorf("status = %s, want awaiting_account (restaurant has no payout account)", l.status)
		}
		if resp := s.patch(t, id, "complete", s.token); resp.StatusCode == http.StatusOK {
			t.Error("a replayed complete was accepted")
		}
		if n := payoutLineCount(t, id); n != 1 {
			t.Errorf("lines = %d, want exactly 1", n)
		}
	})

	t.Run("self delivery delivered by the seller", func(t *testing.T) {
		s := newSellerEnv(t)
		h := quoteHandlerWithProviders(t)
		id := s.selfDeliveryOrder(t, 2000, 699, 500, pricedAt(h, 2000)) // markup 100
		if resp := s.patch(t, id, "deliver", s.token); resp.StatusCode != http.StatusOK {
			t.Fatalf("deliver: %d", resp.StatusCode)
		}
		l := readPayoutLine(t, id)
		// Restaurant keeps its full own fee (699 - 100 markup) and the whole tip.
		if l.fulfillment != "self_delivery" || l.food != 2000 || l.delivery != 599 || l.tip != 500 ||
			l.ke != 100 || l.net != 2000+599+500-100 {
			t.Fatalf("self-delivery line = %+v", l)
		}
		if got := sellerEarnings(t, id); got != 599+500 {
			t.Errorf("seller_delivery_earnings = %d, want the full own fee + tip (1099)", got)
		}
		_ = s.patch(t, id, "deliver", s.token)
		if n := payoutLineCount(t, id); n != 1 {
			t.Errorf("lines = %d, want 1", n)
		}
	})

	t.Run("platform courier delivery", func(t *testing.T) {
		c := newCourierEnv(t, true)
		id := c.pickedUpOrder(t, 2000, 699, 300, nil)
		if rec := c.deliver(t, id); rec.Code != http.StatusOK {
			t.Fatalf("courier deliver: %d %s", rec.Code, rec.Body.String())
		}
		l := readPayoutLine(t, id)
		if l.fulfillment != "courier_delivery" || l.food != 2000 || l.ke != 200 ||
			l.delivery != 0 || l.tip != 0 || l.net != 1800 {
			t.Fatalf("courier line = %+v", l)
		}
		_ = c.deliver(t, id)
		if n := payoutLineCount(t, id); n != 1 {
			t.Errorf("lines = %d, want 1", n)
		}
	})

	h := withProviderClients(t)
	for _, tc := range []struct {
		name, provider, deliveryID string
		post                       func(o webhookOrder) int
	}{
		{"uber webhook", "uber_direct", "del_pay_1", func(o webhookOrder) int {
			return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"del_pay_1","data":{"status":"delivered","external_id":%q}}`, o.id)).Code
		}},
		{"doordash webhook", "doordash_drive", ownOrderID, func(o webhookOrder) int {
			return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, o.id)).Code
		}},
		{"shipday webhook", "shipday", "4242", func(o webhookOrder) int {
			return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":4242,"order_number":%q}}`, o.id)).Code
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			o := seedDispatchedOrder(t, "picked_up", tc.provider, tc.deliveryID)
			if code := tc.post(o); code != http.StatusOK {
				t.Fatalf("delivered webhook: %d", code)
			}
			l := readPayoutLine(t, o.id)
			// seedDispatchedOrder: subtotal 2599, tax 234 → fee 260 (10%, half up).
			if l.fulfillment != "courier_delivery" || l.food != 2599 || l.tax != 234 || l.ke != 260 || l.net != 2599+234-260 {
				t.Fatalf("webhook line = %+v", l)
			}
			// A redelivered event (ledger cleared so it is not deduped) must not
			// add a second line.
			clearWebhookLedger(t)
			_ = tc.post(o)
			if n := payoutLineCount(t, o.id); n != 1 {
				t.Errorf("lines = %d, want 1", n)
			}
		})
	}
}

// A terminal transition that slipped past every hook is picked up by the
// sweep — but never an order that was already terminal before the ledger
// existed (those were settled by hand).
func TestIntegration_RestaurantPayoutBackfillsMissedLinesOnly(t *testing.T) {
	s := newSellerEnv(t)
	fresh := s.order(t, "delivered", func(id string) {
		_, _ = harness.h.db.Pool.Exec(context.Background(),
			`UPDATE orders SET delivered_at = NOW(), courier_id = NULL, delivery_mode = 'external',
			        external_provider = 'uber_direct', external_delivery_id = 'd_backfill' WHERE id = $1`, id)
	})
	historic := s.order(t, "delivered", func(id string) {
		_, _ = harness.h.db.Pool.Exec(context.Background(),
			`UPDATE orders SET delivered_at = '2020-01-01', updated_at = '2020-01-01' WHERE id = $1`, id)
	})

	newTestPayoutProcessor(newFakeRestaurantStripe(), false).BackfillMissingLines(context.Background())

	if n := payoutLineCount(t, fresh); n != 1 {
		t.Errorf("missed terminal order: lines = %d, want 1 (backfilled)", n)
	}
	if n := payoutLineCount(t, historic); n != 0 {
		t.Errorf("order delivered before the ledger existed got %d lines, want 0", n)
	}
}

// ---- sweep: fees, transfers -----------------------------------------------

func TestIntegration_RestaurantPayoutSweepResolvesFeeAndTransfersOnceReady(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	fake := newFakeRestaurantStripe()
	p := newTestPayoutProcessor(fake, true)

	id := s.pickupOrder(t, 2000, 0, 178)
	pi, total := paymentIntentOf(t, id)
	fake.fees[pi] = payments.ChargeFee{ChargeID: "ch_pickup_1", FeeCents: 93, AmountCents: total}
	if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("complete: %d", resp.StatusCode)
	}

	// No account yet: the fee is resolved, nothing moves.
	p.Sweep(ctx)
	l := readPayoutLine(t, id)
	wantNet := 2000 + 178 - 100 - 93
	if !l.resolved || l.processing != 93 || l.net != wantNet || l.chargeID != "ch_pickup_1" {
		t.Fatalf("after fee resolution: %+v, want processing 93 deducted (net %d)", l, wantNet)
	}
	if l.status != "awaiting_account" || len(fake.transfersFor(id)) != 0 {
		t.Fatalf("transferred without a payout account: status %s, transfers %v", l.status, fake.transfersFor(id))
	}

	connectID := s.makePayoutReady(t)
	if l := readPayoutLine(t, id); l.status != "pending" {
		t.Fatalf("after onboarding: status %s, want pending", l.status)
	}

	// Kill switch off: still nothing moves.
	newTestPayoutProcessor(fake, false).Sweep(ctx)
	if len(fake.transfersFor(id)) != 0 {
		t.Fatal("transferred with RESTAURANT_PAYOUTS_ENABLED off")
	}

	p.Sweep(ctx)
	trs := fake.transfersFor(id)
	if len(trs) != 1 {
		t.Fatalf("transfers = %d, want 1", len(trs))
	}
	tr := trs[0]
	l = readPayoutLine(t, id)
	if tr.AccountID != connectID || tr.AmountCents != wantNet || tr.ChargeID != "ch_pickup_1" ||
		tr.IdempotencyKey != "restaurant_payout:"+l.id || tr.RestaurantID != s.restID {
		t.Errorf("transfer = %+v, want %d cents to %s sourced from ch_pickup_1 keyed on the line id", tr, wantNet, connectID)
	}
	if l.status != "paid" || l.transferID == "" || l.transferred != wantNet || l.paidAt == nil {
		t.Errorf("after transfer: %+v", l)
	}

	p.Sweep(ctx)
	if n := len(fake.transfersFor(id)); n != 1 {
		t.Errorf("a second sweep transferred again: %d transfers", n)
	}
}

// KE absorbs processing on courier deliveries: stored, not deducted.
func TestIntegration_RestaurantPayoutCourierDeliveryAbsorbsProcessing(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	fake := newFakeRestaurantStripe()
	id := s.order(t, "delivered", func(id string) {
		_, _ = harness.h.db.Pool.Exec(ctx,
			`UPDATE orders SET delivered_at = NOW(), delivery_mode = 'external', external_provider = 'doordash_drive',
			        external_delivery_id = 'dd_1' WHERE id = $1`, id)
	})
	pi, _ := paymentIntentOf(t, id)
	fake.fees[pi] = payments.ChargeFee{ChargeID: "ch_cd", FeeCents: 98}
	if _, err := restaurantpayout.RecordLine(ctx, harness.h.db.Pool, id, harness.h.deliveryMarkupCents); err != nil {
		t.Fatalf("record: %v", err)
	}
	newTestPayoutProcessor(fake, false).ResolveProcessingFees(ctx)
	l := readPayoutLine(t, id)
	// s.order: subtotal 1500, tax 135 → fee 150.
	if l.fulfillment != "courier_delivery" || l.processing != 98 || l.net != 1500+135-150 {
		t.Fatalf("courier line = %+v, want processing stored (98) but not deducted (net %d)", l, 1500+135-150)
	}
}

// ---- refunds & disputes ---------------------------------------------------

// paidPickupLine drives a pickup order to a transferred ('paid') line.
func paidPickupLine(t *testing.T, s *sellerEnv, fake *fakeRestaurantStripe, p *restaurantpayout.Processor) (orderID, pi string, total int, l payoutLineRow) {
	t.Helper()
	orderID = s.pickupOrder(t, 4000, 0, 355)
	pi, total = paymentIntentOf(t, orderID)
	fake.fees[pi] = payments.ChargeFee{ChargeID: "ch_" + pi, FeeCents: 146, AmountCents: total}
	if resp := s.patch(t, orderID, "complete", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("complete: %d", resp.StatusCode)
	}
	p.Sweep(context.Background())
	l = readPayoutLine(t, orderID)
	if l.status != "paid" || l.transferID == "" {
		t.Fatalf("setup: line %+v, want paid", l)
	}
	return orderID, pi, total, l
}

func TestIntegration_RestaurantPayoutRefundsReverseProportionally(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	s.makePayoutReady(t)
	fake := newFakeRestaurantStripe()
	p := newTestPayoutProcessor(fake, true)
	orderID, pi, total, l := paidPickupLine(t, s, fake, p)
	net := l.net

	// Partial refund of a third of the charge: the restaurant gives back a
	// third of its net (rounded half up).
	third := total / 3
	postChargeRefunded(t, pi, third, total)
	p.Sweep(ctx)
	want1 := restaurantpayout.ReversalTarget(net, third, total)
	revs := fake.reversalsOf(l.transferID)
	if len(revs) != 1 || revs[0].amount != want1 {
		t.Fatalf("after partial refund: reversals %+v, want one of %d", revs, want1)
	}
	if got := readPayoutLine(t, orderID); got.status != "paid" || got.reversed != want1 {
		t.Fatalf("after partial refund: %+v, want paid with reversed %d", got, want1)
	}

	// A replayed sweep must not reverse again.
	p.Sweep(ctx)
	if n := len(fake.reversalsOf(l.transferID)); n != 1 {
		t.Fatalf("reversals after replay = %d, want 1", n)
	}

	// The rest of the charge is refunded: the remainder is reversed and the
	// line is fully reversed.
	postChargeRefunded(t, pi, total, total)
	p.Sweep(ctx)
	revs = fake.reversalsOf(l.transferID)
	if len(revs) != 2 || revs[1].amount != net-want1 || revs[0].key == revs[1].key {
		t.Fatalf("after full refund: reversals %+v, want a second of %d under a new key", revs, net-want1)
	}
	if got := readPayoutLine(t, orderID); got.status != "reversed" || got.reversed != net {
		t.Fatalf("after full refund: %+v, want reversed of all %d", got, net)
	}
}

func TestIntegration_RestaurantPayoutRefundBeforeTransfer(t *testing.T) {
	ctx := context.Background()

	t.Run("full refund voids the line and nothing is ever transferred", func(t *testing.T) {
		s := newSellerEnv(t)
		fake := newFakeRestaurantStripe()
		p := newTestPayoutProcessor(fake, true)
		id := s.pickupOrder(t, 2500, 0, 222)
		pi, total := paymentIntentOf(t, id)
		if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
			t.Fatalf("complete: %d", resp.StatusCode)
		}
		postChargeRefunded(t, pi, total, total)
		p.Sweep(ctx)
		if l := readPayoutLine(t, id); l.status != "void" {
			t.Fatalf("status = %s, want void", l.status)
		}
		s.makePayoutReady(t)
		p.Sweep(ctx)
		if n := len(fake.transfersFor(id)); n != 0 {
			t.Fatalf("a voided line was transferred (%d)", n)
		}
	})

	t.Run("partial refund is withheld from the transfer", func(t *testing.T) {
		s := newSellerEnv(t)
		fake := newFakeRestaurantStripe()
		p := newTestPayoutProcessor(fake, true)
		id := s.pickupOrder(t, 2500, 0, 222)
		pi, total := paymentIntentOf(t, id)
		fake.fees[pi] = payments.ChargeFee{ChargeID: "ch_w", FeeCents: 103, AmountCents: total}
		if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
			t.Fatalf("complete: %d", resp.StatusCode)
		}
		half := total / 2
		postChargeRefunded(t, pi, half, total)
		p.Sweep(ctx) // resolves fee + books the withhold
		l := readPayoutLine(t, id)
		want := restaurantpayout.ReversalTarget(l.net, half, total)
		if l.status != "awaiting_account" || l.reversed != want {
			t.Fatalf("after partial refund: %+v, want awaiting_account with %d withheld", l, want)
		}
		s.makePayoutReady(t)
		p.Sweep(ctx)
		trs := fake.transfersFor(id)
		if len(trs) != 1 || trs[0].AmountCents != l.net-want {
			t.Fatalf("transfers %+v, want one of %d (net %d minus withheld %d)", trs, l.net-want, l.net, want)
		}
	})
}

// An order cancelled after completion is treated as fully refunded.
func TestIntegration_RestaurantPayoutCancelledAfterCompletionReverses(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	s.makePayoutReady(t)
	fake := newFakeRestaurantStripe()
	p := newTestPayoutProcessor(fake, true)
	orderID, _, _, l := paidPickupLine(t, s, fake, p)
	if _, err := harness.h.db.Pool.Exec(ctx, `UPDATE orders SET status = 'cancelled' WHERE id = $1`, orderID); err != nil {
		t.Fatalf("cancel: %v", err)
	}
	p.Sweep(ctx)
	revs := fake.reversalsOf(l.transferID)
	if len(revs) != 1 || revs[0].amount != l.net {
		t.Fatalf("reversals %+v, want the full net %d", revs, l.net)
	}
	if got := readPayoutLine(t, orderID); got.status != "reversed" {
		t.Fatalf("status = %s, want reversed", got.status)
	}
}

func TestIntegration_RestaurantPayoutDisputeHoldsUntransferredLine(t *testing.T) {
	s := newSellerEnv(t)
	id := s.pickupOrder(t, 2000, 0, 178)
	pi, total := paymentIntentOf(t, id)
	if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("complete: %d", resp.StatusCode)
	}
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	body := stripeEventBody(uniqueEventID(), "charge.dispute.created",
		fmt.Sprintf(`{"id":"dp_1","charge":"ch_x","payment_intent":%q,"amount":%d,"currency":"usd","reason":"fraudulent","status":"needs_response"}`, pi, total))
	if rec := postStripeWebhook(t, h, body); rec.Code != http.StatusOK {
		t.Fatalf("dispute webhook: %d", rec.Code)
	}
	if l := readPayoutLine(t, id); l.status != "failed" {
		t.Fatalf("status = %s, want failed (held for review)", l.status)
	}
}

func TestIntegration_RestaurantAccountUpdatedWebhookFlipsReadiness(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	connectID := uniqueConnectID()
	if _, err := harness.h.db.Pool.Exec(ctx,
		`UPDATE restaurants SET stripe_connect_id = $2, payout_ready = false WHERE id = $1`, s.restID, connectID); err != nil {
		t.Fatalf("seed connect id: %v", err)
	}
	id := s.pickupOrder(t, 1000, 0, 89)
	if resp := s.patch(t, id, "complete", s.token); resp.StatusCode != http.StatusOK {
		t.Fatalf("complete: %d", resp.StatusCode)
	}
	if l := readPayoutLine(t, id); l.status != "awaiting_account" {
		t.Fatalf("status = %s, want awaiting_account", l.status)
	}

	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	post := func(enabled bool) {
		body := stripeEventBody(uniqueEventID(), "account.updated",
			fmt.Sprintf(`{"id":%q,"object":"account","payouts_enabled":%t,"details_submitted":true}`, connectID, enabled))
		if rec := postStripeWebhook(t, h, body); rec.Code != http.StatusOK {
			t.Fatalf("account.updated: %d", rec.Code)
		}
	}
	ready := func() bool {
		var b bool
		_ = harness.h.db.Pool.QueryRow(ctx, `SELECT payout_ready FROM restaurants WHERE id = $1`, s.restID).Scan(&b)
		return b
	}

	post(true)
	if !ready() || readPayoutLine(t, id).status != "pending" {
		t.Fatalf("after onboarding: ready=%v line=%s, want true/pending", ready(), readPayoutLine(t, id).status)
	}
	post(false)
	if ready() || readPayoutLine(t, id).status != "awaiting_account" {
		t.Fatalf("after disable: ready=%v line=%s, want false/awaiting_account", ready(), readPayoutLine(t, id).status)
	}
}

// ---- seller payout endpoints ----------------------------------------------

func TestIntegration_SellerPayoutAccountEndpoints(t *testing.T) {
	s := newSellerEnv(t)
	router := sellerPayoutRouter(harness.h)

	rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts/status", s.token, nil)
	if rec.Code != http.StatusOK || strings.Contains(rec.Body.String(), "connect_id") {
		t.Fatalf("status before onboarding: %d %s", rec.Code, rec.Body.String())
	}
	if st := decodeJSON[RestaurantPayoutStatusResponse](t, rec.Body.Bytes()); st.PayoutReady {
		t.Fatal("payout_ready before an account exists")
	}
	if rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts/link", s.token, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("link before account: %d, want 400", rec.Code)
	}

	first := s.makePayoutReady(t)
	rec = doRequest(router, http.MethodPost, "/api/v1/seller/payouts/account", s.token, nil)
	if st := decodeJSON[RestaurantPayoutStatusResponse](t, rec.Body.Bytes()); st.ConnectID != first || !st.DetailsSubmitted {
		t.Fatalf("second account call: %+v, want the same account %s (idempotent)", st, first)
	}
	rec = doRequest(router, http.MethodGet, "/api/v1/seller/payouts/link", s.token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("link: %d %s", rec.Code, rec.Body.String())
	}
	if link := decodeJSON[PayoutLinkResponse](t, rec.Body.Bytes()); !strings.Contains(link.URL, first) {
		t.Fatalf("link %q does not reference the restaurant's account %s", link.URL, first)
	}
	rec = doRequest(router, http.MethodGet, "/api/v1/seller/payouts/status", s.token, nil)
	if st := decodeJSON[RestaurantPayoutStatusResponse](t, rec.Body.Bytes()); !st.PayoutReady || st.ConnectID != first {
		t.Fatalf("status: %+v", st)
	}
	var stored string
	var ready bool
	_ = harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT stripe_connect_id, payout_ready FROM restaurants WHERE id = $1`, s.restID).Scan(&stored, &ready)
	if stored != first || !ready {
		t.Fatalf("restaurant row: connect %q ready %v", stored, ready)
	}
}

// insertPayoutLine writes a ledger line with exact values (statement tests).
func insertPayoutLine(t *testing.T, restID, orderID string, completedAt time.Time, status string,
	food, tax, delivery, tip, ke, proc, net, reversed int) string {
	t.Helper()
	var id string
	if err := harness.h.db.Pool.QueryRow(context.Background(), `
		INSERT INTO restaurant_payout_lines
		    (order_id, restaurant_id, fulfillment, food_subtotal_cents, sales_tax_cents, delivery_fee_cents,
		     tip_cents, ke_fee_cents, processing_fee_cents, processing_fee_resolved, net_cents,
		     order_total_cents, status, reversed_cents, completed_at)
		VALUES ($1, $2, 'pickup', $3, $4, $5, $6, $7, $8, TRUE, $9, $3::int + $4::int, $10, $11, $12)
		RETURNING id`, orderID, restID, food, tax, delivery, tip, ke, proc, net, status, reversed, completedAt,
	).Scan(&id); err != nil {
		t.Fatalf("insert line: %v", err)
	}
	return id
}

func TestIntegration_SellerPayoutListAndSummary(t *testing.T) {
	s := newSellerEnv(t)
	router := sellerPayoutRouter(harness.h)
	ny, _ := time.LoadLocation("America/New_York")

	// Two lines on NY 2026-09-30 (one late at night, 03:30Z on 10-01) and three
	// on NY 2026-10-01.
	lateSep30 := time.Date(2026, 9, 30, 23, 30, 0, 0, ny)
	oct1a := time.Date(2026, 10, 1, 0, 30, 0, 0, ny)
	oct1b := time.Date(2026, 10, 1, 12, 0, 0, 0, ny)
	oct1c := time.Date(2026, 10, 1, 18, 0, 0, 0, ny)
	sep30 := time.Date(2026, 9, 30, 9, 0, 0, 0, ny)

	insertPayoutLine(t, s.restID, s.order(t, "completed"), sep30, "paid", 1000, 89, 0, 0, 50, 30, 1009, 0)
	insertPayoutLine(t, s.restID, s.order(t, "completed"), lateSep30, "pending", 2000, 178, 0, 0, 100, 60, 2018, 0)
	insertPayoutLine(t, s.restID, s.order(t, "completed"), oct1a, "paid", 3000, 266, 0, 0, 150, 90, 3026, 1000)
	insertPayoutLine(t, s.restID, s.order(t, "completed"), oct1b, "processing", 1000, 89, 300, 200, 50, 40, 1499, 0)
	insertPayoutLine(t, s.restID, s.order(t, "completed"), oct1c, "void", 5000, 444, 0, 0, 250, 0, 5194, 0)

	// Summary for NY 2026-10-01 only.
	rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts/summary?from=2026-10-01&to=2026-10-01", s.token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("summary: %d %s", rec.Code, rec.Body.String())
	}
	sum := decodeJSON[RestaurantPayoutSummaryResponse](t, rec.Body.Bytes())
	want := RestaurantPayoutSummaryResponse{
		From: "2026-10-01", To: "2026-10-01",
		Orders: 2, FoodSubtotalCents: 4000, SalesTaxCents: 355, DeliveryFeeCents: 300, TipCents: 200,
		KEFeeCents: 200, ProcessingFeeCents: 130, NetCents: 3026 + 1499,
		PaidCents: 3026 - 1000, PendingCents: 1499,
	}
	if sum != want {
		t.Errorf("summary 10-01 =\n %+v\nwant\n %+v", sum, want)
	}

	rec = doRequest(router, http.MethodGet, "/api/v1/seller/payouts/summary?from=2026-09-30&to=2026-09-30", s.token, nil)
	sum = decodeJSON[RestaurantPayoutSummaryResponse](t, rec.Body.Bytes())
	if sum.Orders != 2 || sum.SalesTaxCents != 89+178 || sum.PaidCents != 1009 || sum.PendingCents != 2018 {
		t.Errorf("summary 09-30 = %+v (the 23:30 NY order belongs to 09-30)", sum)
	}

	for _, bad := range []string{"", "?from=2026-10-01", "?from=2026-10-02&to=2026-10-01", "?from=10/01/2026&to=2026-10-01"} {
		if rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts/summary"+bad, s.token, nil); rec.Code != http.StatusBadRequest {
			t.Errorf("summary%s: %d, want 400", bad, rec.Code)
		}
	}

	// List: newest first, keyset pagination, processing reported as pending.
	var all []RestaurantPayoutLine
	cursor := ""
	for page := 0; page < 5; page++ {
		path := "/api/v1/seller/payouts?limit=2"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		rec := doRequest(router, http.MethodGet, path, s.token, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("list: %d %s", rec.Code, rec.Body.String())
		}
		resp := decodeJSON[RestaurantPayoutListResponse](t, rec.Body.Bytes())
		all = append(all, resp.Lines...)
		if resp.NextCursor == "" {
			break
		}
		cursor = resp.NextCursor
	}
	if len(all) != 5 {
		t.Fatalf("paged through %d lines, want 5", len(all))
	}
	for i := 1; i < len(all); i++ {
		if all[i].CompletedAt.After(all[i-1].CompletedAt) {
			t.Fatalf("list not newest-first at %d", i)
		}
	}
	if !all[0].CompletedAt.Equal(oct1c) || all[0].Status != "void" {
		t.Errorf("first line = %+v, want the newest (void)", all[0])
	}
	if all[1].Status != "pending" {
		t.Errorf("processing line reported as %q, want pending", all[1].Status)
	}
	if rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts?cursor=garbage!", s.token, nil); rec.Code != http.StatusBadRequest {
		t.Errorf("bad cursor: %d, want 400", rec.Code)
	}
}

// Payout data is scoped to the restaurant's owner.
func TestIntegration_SellerPayoutsScopedToOwner(t *testing.T) {
	owner := newSellerEnv(t)
	other := newSellerEnv(t)
	router := sellerPayoutRouter(harness.h)
	insertPayoutLine(t, owner.restID, owner.order(t, "completed"), time.Now(), "pending", 1000, 89, 0, 0, 50, 0, 1039, 0)

	rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts", other.token, nil)
	if resp := decodeJSON[RestaurantPayoutListResponse](t, rec.Body.Bytes()); len(resp.Lines) != 0 {
		t.Fatalf("another seller sees %d of the owner's lines", len(resp.Lines))
	}
	for _, path := range []string{
		"/api/v1/seller/payouts?restaurant_id=" + owner.restID,
		"/api/v1/seller/payouts/summary?from=2026-01-01&to=2026-12-31&restaurant_id=" + owner.restID,
		"/api/v1/seller/payouts/status?restaurant_id=" + owner.restID,
		"/api/v1/seller/agreement?restaurant_id=" + owner.restID,
	} {
		if rec := doRequest(router, http.MethodGet, path, other.token, nil); rec.Code != http.StatusNotFound {
			t.Errorf("GET %s as another seller: %d, want 404", path, rec.Code)
		}
	}
	if rec := doRequest(router, http.MethodPost, "/api/v1/seller/payouts/account?restaurant_id="+owner.restID, other.token, nil); rec.Code != http.StatusNotFound {
		t.Errorf("POST account for another seller's restaurant: %d, want 404", rec.Code)
	}
	consumerToken, _ := harness.registerUser(t, "payout-consumer")
	if rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts", consumerToken, nil); rec.Code != http.StatusForbidden {
		t.Errorf("consumer on /seller/payouts: %d, want 403", rec.Code)
	}
	if rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts", "", nil); rec.Code != http.StatusUnauthorized {
		t.Errorf("anonymous on /seller/payouts: %d, want 401", rec.Code)
	}
}

// A seller with two restaurants: ?restaurant_id= targets either; no param
// falls back to the first by name (the Android client sends none).
func TestIntegration_SellerPayoutsMultiRestaurantOwner(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t) // restaurant "State Machine Deli …"
	second, _, err := harness.seedRestaurant(ctx, s.ownerID, "AAA First By Name "+uniqueEmail("x"), "approved")
	if err != nil {
		t.Fatalf("seed second restaurant: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM merchant_agreements WHERE restaurant_id = $1`, second)
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE restaurant_id = $1`, second)
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM menu_items WHERE restaurant_id = $1`, second)
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM menu_categories WHERE restaurant_id = $1`, second)
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, second)
	})
	if _, err := harness.h.db.Pool.Exec(ctx,
		`UPDATE restaurants SET agreement_exempt = false WHERE id IN ($1, $2)`, s.restID, second); err != nil {
		t.Fatalf("un-exempt: %v", err)
	}
	router := sellerPayoutRouter(harness.h)

	insertPayoutLine(t, s.restID, s.order(t, "completed"), time.Now(), "pending", 1000, 89, 0, 0, 50, 0, 1039, 0)

	// Default (no param) = the first restaurant by name = `second`.
	rec := doRequest(router, http.MethodGet, "/api/v1/seller/payouts", s.token, nil)
	if resp := decodeJSON[RestaurantPayoutListResponse](t, rec.Body.Bytes()); len(resp.Lines) != 0 {
		t.Fatalf("default restaurant list has %d lines, want 0 (the line belongs to the other restaurant)", len(resp.Lines))
	}
	rec = doRequest(router, http.MethodGet, "/api/v1/seller/payouts?restaurant_id="+s.restID, s.token, nil)
	if resp := decodeJSON[RestaurantPayoutListResponse](t, rec.Body.Bytes()); len(resp.Lines) != 1 {
		t.Fatalf("explicit restaurant list has %d lines, want 1", len(resp.Lines))
	}

	// Accept the agreement for the explicit (non-default) restaurant only.
	rec = doRequest(router, http.MethodPost, "/api/v1/seller/agreement/accept?restaurant_id="+s.restID, s.token,
		AcceptAgreementRequest{LegalName: "State Machine LLC", Version: CurrentMerchantAgreementVersion})
	if rec.Code != http.StatusOK {
		t.Fatalf("accept: %d %s", rec.Code, rec.Body.String())
	}
	agreed := decodeJSON[AgreementStatusResponse](t,
		doRequest(router, http.MethodGet, "/api/v1/seller/agreement?restaurant_id="+s.restID, s.token, nil).Body.Bytes())
	def := decodeJSON[AgreementStatusResponse](t,
		doRequest(router, http.MethodGet, "/api/v1/seller/agreement", s.token, nil).Body.Bytes())
	if !agreed.Accepted || agreed.Required || def.Accepted || !def.Required {
		t.Fatalf("explicit=%+v default=%+v, want only the explicit restaurant accepted", agreed, def)
	}

	// Payout accounts are per restaurant.
	a := decodeJSON[RestaurantPayoutStatusResponse](t,
		doRequest(router, http.MethodPost, "/api/v1/seller/payouts/account?restaurant_id="+s.restID, s.token, nil).Body.Bytes())
	b := decodeJSON[RestaurantPayoutStatusResponse](t,
		doRequest(router, http.MethodPost, "/api/v1/seller/payouts/account", s.token, nil).Body.Bytes())
	if a.ConnectID == "" || b.ConnectID == "" || a.ConnectID == b.ConnectID {
		t.Fatalf("accounts %q / %q, want two distinct per-restaurant accounts", a.ConnectID, b.ConnectID)
	}
}

// ---- merchant agreement gate ----------------------------------------------

func TestIntegration_MerchantAgreementGatesListingAndCheckout(t *testing.T) {
	ctx := context.Background()
	s := newSellerEnv(t)
	if _, err := harness.h.db.Pool.Exec(ctx,
		`UPDATE restaurants SET agreement_exempt = false WHERE id = $1`, s.restID); err != nil {
		t.Fatalf("un-exempt: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM merchant_agreements WHERE restaurant_id = $1`, s.restID)
	})
	router := sellerPayoutRouter(harness.h)
	token, userID := harness.registerUser(t, "agreement-consumer")

	listed := func() bool {
		rec := harness.do(http.MethodGet, "/api/v1/restaurants/", token, nil)
		var rows []struct {
			ID        string `json:"id"`
			Orderable bool   `json:"orderable"`
		}
		_ = json.Unmarshal(rec.Body.Bytes(), &rows)
		for _, r := range rows {
			if r.ID == s.restID {
				return r.Orderable
			}
		}
		return false
	}

	// --- gated ---
	if listed() {
		t.Fatal("a non-exempt restaurant that never accepted the agreement is listed")
	}
	if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+s.restID, token, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("detail: %d, want 404", rec.Code)
	}
	if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+s.restID+"/menu", token, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("menu: %d, want 404", rec.Code)
	}
	if rec := harness.do(http.MethodPost, "/api/v1/cart/items", token, AddToCartRequest{
		MenuItemID: s.itemID, RestaurantID: s.restID, Quantity: 1,
	}); rec.Code != http.StatusForbidden {
		t.Fatalf("AddToCart: %d, want 403", rec.Code)
	}
	// A cart that predates the gate (or a hostile client) cannot pay or order.
	var cartID string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`INSERT INTO carts (user_id, restaurant_id) VALUES ($1, $2) RETURNING id`, userID, s.restID).Scan(&cartID); err != nil {
		t.Fatalf("force cart: %v", err)
	}
	if _, err := harness.h.db.Pool.Exec(ctx,
		`INSERT INTO cart_items (cart_id, menu_item_id, quantity, unit_price) VALUES ($1, $2, 1, 1500)`, cartID, s.itemID); err != nil {
		t.Fatalf("force cart item: %v", err)
	}
	if rec := harness.do(http.MethodPost, "/api/v1/payments/intent", token, map[string]any{}); rec.Code != http.StatusForbidden {
		t.Fatalf("CreatePaymentIntent: %d, want 403 (%s)", rec.Code, rec.Body.String())
	}
	if rec := harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
		"restaurant_id": s.restID, "payment_intent_id": "pi_agreement_gate", "fulfillment_type": "pickup",
	}); rec.Code != http.StatusForbidden {
		t.Fatalf("CreateOrder: %d, want 403 (%s)", rec.Code, rec.Body.String())
	}
	if ok, err := harness.h.restaurantOrderable(ctx, s.restID); err != nil || ok {
		t.Fatalf("restaurantOrderable = %v, %v; want false", ok, err)
	}

	// --- seller side ---
	st := decodeJSON[AgreementStatusResponse](t,
		doRequest(router, http.MethodGet, "/api/v1/seller/agreement", s.token, nil).Body.Bytes())
	if !st.Required || st.Accepted || st.CurrentVersion != "2026-10-01" ||
		st.TermsURL != strings.TrimRight(harness.h.cfg.WebURL, "/")+"/restaurant-terms" || st.AcceptedAt != nil {
		t.Fatalf("status before accept: %+v", st)
	}
	accept := func(body AcceptAgreementRequest, xff string) (int, AgreementStatusResponse) {
		req := doRequestWithHeader(router, http.MethodPost, "/api/v1/seller/agreement/accept", s.token, body, "X-Forwarded-For", xff)
		var out AgreementStatusResponse
		_ = json.Unmarshal(req.Body.Bytes(), &out)
		return req.Code, out
	}
	if code, _ := accept(AcceptAgreementRequest{LegalName: "Deli LLC", Version: "2025-01-01"}, ""); code != http.StatusConflict {
		t.Fatalf("stale version: %d, want 409", code)
	}
	if code, _ := accept(AcceptAgreementRequest{LegalName: "   ", Version: CurrentMerchantAgreementVersion}, ""); code != http.StatusBadRequest {
		t.Fatalf("blank legal name: %d, want 400", code)
	}
	if code, _ := accept(AcceptAgreementRequest{LegalName: strings.Repeat("x", 201), Version: CurrentMerchantAgreementVersion}, ""); code != http.StatusBadRequest {
		t.Fatalf("201-char legal name: %d, want 400", code)
	}
	code, st := accept(AcceptAgreementRequest{LegalName: "  State Machine Deli LLC  ", Version: CurrentMerchantAgreementVersion},
		"203.0.113.7, 10.0.0.1")
	if code != http.StatusOK || !st.Accepted || st.Required || st.AcceptedVersion == nil ||
		*st.AcceptedVersion != CurrentMerchantAgreementVersion || st.AcceptedAt == nil {
		t.Fatalf("accept: %d %+v", code, st)
	}
	var legal, ip, version, acceptedBy string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`SELECT legal_name, ip_address, agreement_version, user_id::text FROM merchant_agreements WHERE restaurant_id = $1`,
		s.restID).Scan(&legal, &ip, &version, &acceptedBy); err != nil {
		t.Fatalf("read acceptance: %v", err)
	}
	if legal != "State Machine Deli LLC" || ip != "203.0.113.7" || version != CurrentMerchantAgreementVersion || acceptedBy != s.ownerID {
		t.Fatalf("acceptance row = (%q, %q, %q, %q)", legal, ip, version, acceptedBy)
	}
	// Idempotent.
	if code, _ := accept(AcceptAgreementRequest{LegalName: "Someone Else", Version: CurrentMerchantAgreementVersion}, ""); code != http.StatusOK {
		t.Fatalf("re-accept: %d", code)
	}
	var rows int
	_ = harness.h.db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM merchant_agreements WHERE restaurant_id = $1`, s.restID).Scan(&rows)
	if rows != 1 {
		t.Fatalf("acceptance rows = %d, want 1", rows)
	}

	// --- ungated ---
	if !listed() {
		t.Fatal("restaurant still hidden after accepting the agreement")
	}
	if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+s.restID+"/menu", token, nil); rec.Code != http.StatusOK {
		t.Fatalf("menu after accept: %d", rec.Code)
	}
	if ok, err := harness.h.restaurantOrderable(ctx, s.restID); err != nil || !ok {
		t.Fatalf("restaurantOrderable after accept = %v, %v; want true", ok, err)
	}
}

func TestIntegration_MerchantAgreementExemptAndNewSeller(t *testing.T) {
	ctx := context.Background()
	router := sellerPayoutRouter(harness.h)

	// Grandfathered restaurant: not required.
	s := newSellerEnv(t)
	st := decodeJSON[AgreementStatusResponse](t,
		doRequest(router, http.MethodGet, "/api/v1/seller/agreement", s.token, nil).Body.Bytes())
	if st.Required || st.Accepted {
		t.Fatalf("exempt restaurant: %+v, want required=false accepted=false", st)
	}

	// Brand-new seller with no restaurant yet: 200 + required, and accepting
	// has nothing to attach to.
	var sellerID string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'New', 'Seller', $2, 'seller', 'kosher') RETURNING id`,
		uniqueEmail("new-seller"), uniquePhone()).Scan(&sellerID); err != nil {
		t.Fatalf("seed seller: %v", err)
	}
	t.Cleanup(func() { _, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, sellerID) })
	tok, _, err := harness.h.generateTokens(ctx, sellerID, "seller", "kosher")
	if err != nil {
		t.Fatalf("token: %v", err)
	}
	rec := doRequest(router, http.MethodGet, "/api/v1/seller/agreement", tok, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("new seller GET: %d, want 200", rec.Code)
	}
	if st := decodeJSON[AgreementStatusResponse](t, rec.Body.Bytes()); !st.Required || st.Accepted || st.CurrentVersion != CurrentMerchantAgreementVersion {
		t.Fatalf("new seller: %+v", st)
	}
	if rec := doRequest(router, http.MethodPost, "/api/v1/seller/agreement/accept", tok,
		AcceptAgreementRequest{LegalName: "X", Version: CurrentMerchantAgreementVersion}); rec.Code != http.StatusNotFound {
		t.Fatalf("new seller accept: %d, want 404", rec.Code)
	}

	// A restaurant created through the seller endpoint is NOT exempt.
	var exempt bool
	var newRest string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`INSERT INTO restaurants (owner_id, name, street, city, state, zip_code) VALUES ($1, 'Fresh', '1 A St', 'Brooklyn', 'NY', '11218')
		 RETURNING id, agreement_exempt`, sellerID).Scan(&newRest, &exempt); err != nil {
		t.Fatalf("insert restaurant: %v", err)
	}
	t.Cleanup(func() { _, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, newRest) })
	if exempt {
		t.Fatal("a newly created restaurant defaulted to agreement_exempt = true")
	}
}
