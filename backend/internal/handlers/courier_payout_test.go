package handlers

// The in-house courier payout: what a KosherEats courier is credited, and what
// is queued for transfer, when they mark an order delivered. This is the only
// remaining money path in the package with no coverage at all (DeliverOrder was
// at 0%), and it is the mirror of the self-delivery payout that
// seller_payout_test.go pins.
//
// SAFETY: DB only. Stripe stays in the harness's dev stub mode and no provider
// client is configured, so nothing dials out. The payout is only ENQUEUED here;
// the sweep that actually moves money is the scheduler's, not this handler's.

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
)

func courierRouter(h *Handler) http.Handler {
	r := chi.NewRouter()
	r.Route("/api/v1/courier/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Patch("/{id}/deliver", h.DeliverOrder)
	})
	return r
}

// courierEnv is an approved, onboarded courier plus a consumer to order for.
type courierEnv struct {
	router    http.Handler
	token     string
	courierID string
	consumer  string
	connectID string
}

func newCourierEnv(t *testing.T, onboarded bool) *courierEnv {
	t.Helper()
	h := harness.h
	ctx := context.Background()

	var courierID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Cour', 'Ier', $2, 'courier', 'kosher') RETURNING id`,
		uniqueEmail("courier"), uniquePhone(),
	).Scan(&courierID); err != nil {
		t.Fatalf("seed courier: %v", err)
	}
	var consumerID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Con', 'Sumer', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("cp-consumer"), uniquePhone(),
	).Scan(&consumerID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}

	connectID := ""
	if onboarded {
		connectID = fmt.Sprintf("acct_test_%d", time.Now().UnixNano())
	}
	var connectArg any
	if connectID != "" {
		connectArg = connectID
	}
	if _, err := h.db.Pool.Exec(ctx,
		`INSERT INTO courier_profiles (user_id, onboarding_status, stripe_connect_id, payout_ready)
		 VALUES ($1, 'approved', $2, $3)`, courierID, connectArg, onboarded); err != nil {
		t.Fatalf("seed courier profile: %v", err)
	}

	token, _, err := h.generateTokens(courierID, "courier", "kosher")
	if err != nil {
		t.Fatalf("mint courier token: %v", err)
	}
	t.Cleanup(func() {
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM courier_payout_queue WHERE courier_id = $1`, courierID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE courier_id = $1 OR user_id = $2`, courierID, consumerID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM courier_profiles WHERE user_id = $1`, courierID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id IN ($1, $2)`, courierID, consumerID)
	})
	return &courierEnv{
		router: courierRouter(h), token: token,
		courierID: courierID, consumer: consumerID, connectID: connectID,
	}
}

// pickedUpOrder seeds an order this courier has already picked up, priced the
// way checkout prices one: delivery_fee is what the CONSUMER paid (courier cost
// plus the KosherEats marketplace markup) and delivery_markup_cents is that
// markup frozen on the row.
func (c *courierEnv) pickedUpOrder(t *testing.T, subtotal, deliveryFee, tip int, markup *int) string {
	t.Helper()
	var id string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, courier_tip, fulfillment_type, delivery_mode,
		   courier_id, delivery_markup_cents)
		 VALUES ($1, $2, 'picked_up', $3, $4, 0, 0, $5, '2 Oak St', $6, $7, 'delivery', 'platform', $8, $9)
		 RETURNING id`,
		c.consumer, harness.approvedRestID, subtotal, deliveryFee, subtotal+deliveryFee+tip,
		uniqueEmail("pi_courier"), tip, c.courierID, markup,
	).Scan(&id); err != nil {
		t.Fatalf("seed picked-up order: %v", err)
	}
	return id
}

func (c *courierEnv) deliver(t *testing.T, orderID string) *httptest.ResponseRecorder {
	t.Helper()
	return doRequest(c.router, http.MethodPatch, "/api/v1/courier/orders/"+orderID+"/deliver", c.token, nil)
}

// courierPayout reads what the deliver transition credited and queued.
type courierPayoutRow struct {
	recorded  int
	queued    int
	queuedRow bool
	connectID string
	status    string
}

func readCourierPayout(t *testing.T, orderID string) courierPayoutRow {
	t.Helper()
	var out courierPayoutRow
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COALESCE(courier_payout, 0) FROM orders WHERE id = $1`, orderID).Scan(&out.recorded); err != nil {
		t.Fatalf("read courier_payout: %v", err)
	}
	var connect *string
	err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT amount_cents, stripe_connect_id, status FROM courier_payout_queue WHERE order_id = $1`,
		orderID).Scan(&out.queued, &connect, &out.status)
	if err == nil {
		out.queuedRow = true
		if connect != nil {
			out.connectID = *connect
		}
	}
	return out
}

// The happy path, and the arithmetic the courier is actually paid by.
//
// NOTE — open finding, deliberately pinned rather than silently changed: the
// courier is credited the FULL consumer-facing delivery_fee, which on a
// platform order is the courier cost PLUS the KosherEats marketplace markup
// that checkout added and that KE is supposed to keep. SellerDeliverOrder
// solves the identical split by subtracting the frozen delivery_markup_cents
// first (see seller_payout_test.go); DeliverOrder does not. This test records
// today's behavior so the divergence is visible and any change to the split
// is a deliberate, reviewed edit rather than a silent drift — changing what a
// real courier earns is a business decision, not a test-driven cleanup.
func TestIntegration_CourierPayoutIsDeliveryFeePlusFullTip(t *testing.T) {
	c := newCourierEnv(t, true)
	h := quoteHandlerWithProviders(t) // markup tiers: 100 / 200 / 300 at 4000 / 8000

	const subtotal, tip = 2000, 500
	markup := h.deliveryMarkupCents(subtotal) // 100 — baked into delivery_fee below
	const courierCost = 699
	deliveryFee := courierCost + markup

	orderID := c.pickedUpOrder(t, subtotal, deliveryFee, tip, &markup)
	if resp := c.deliver(t, orderID); resp.Code != http.StatusOK {
		t.Fatalf("deliver: status %d, body %s", resp.Code, resp.Body.String())
	}

	got := readCourierPayout(t, orderID)
	// Current behavior: the whole consumer-facing fee plus 100% of the tip.
	if want := deliveryFee + tip; got.recorded != want {
		t.Errorf("courier_payout = %d, want %d", got.recorded, want)
	}
	// The queued transfer must equal what was recorded — a queue that disagrees
	// with the order row is a reconciliation problem nobody can settle later.
	if !got.queuedRow {
		t.Fatal("no payout queued for a delivered order")
	}
	if got.queued != got.recorded {
		t.Errorf("queued %d but recorded %d — the ledger and the transfer disagree", got.queued, got.recorded)
	}
	if got.connectID != c.connectID {
		t.Errorf("queued connect id = %q, want %q", got.connectID, c.connectID)
	}
	// The markup KE is supposed to keep, per the split SellerDeliverOrder applies.
	if kept := deliveryFee - markup + tip; got.recorded != kept {
		t.Logf("OPEN FINDING: courier is paid %d; the seller-side split would pay %d, "+
			"leaving KE its %d markup. DeliverOrder does not subtract delivery_markup_cents.",
			got.recorded, kept, markup)
	}
}

// 100% of the tip goes to whoever delivered — that is the promise made at
// checkout, so the tip must never be scaled, split or absorbed.
func TestIntegration_CourierKeepsTheWholeTip(t *testing.T) {
	for _, tip := range []int{0, 1, 500, 5000} {
		t.Run(fmt.Sprintf("tip_%d", tip), func(t *testing.T) {
			c := newCourierEnv(t, true)
			const fee = 799
			markup := 100
			orderID := c.pickedUpOrder(t, 2000, fee, tip, &markup)
			if resp := c.deliver(t, orderID); resp.Code != http.StatusOK {
				t.Fatalf("deliver: status %d, body %s", resp.Code, resp.Body.String())
			}
			got := readCourierPayout(t, orderID)
			if got.recorded-fee != tip {
				t.Errorf("tip portion = %d, want the full %d", got.recorded-fee, tip)
			}
		})
	}
}

// A courier who hasn't finished Stripe onboarding must still have their payout
// QUEUED — with a NULL connect id the sweep skips, and the account.updated
// webhook backfills later. Dropping it instead would mean the courier is simply
// never paid for that delivery.
func TestIntegration_CourierPayoutQueuedEvenBeforeOnboarding(t *testing.T) {
	c := newCourierEnv(t, false) // no stripe_connect_id
	markup := 100
	orderID := c.pickedUpOrder(t, 2000, 799, 300, &markup)

	if resp := c.deliver(t, orderID); resp.Code != http.StatusOK {
		t.Fatalf("deliver: status %d, body %s", resp.Code, resp.Body.String())
	}
	got := readCourierPayout(t, orderID)
	if !got.queuedRow {
		t.Fatal("payout dropped for a not-yet-onboarded courier — they would never be paid")
	}
	if got.connectID != "" {
		t.Errorf("connect id = %q, want NULL until onboarding completes", got.connectID)
	}
	if got.status != "pending" {
		t.Errorf("queue status = %q, want pending", got.status)
	}
}

// The transition is a CAS on (order, courier, picked_up). Every other shape has
// to be refused, and refused WITHOUT queueing money.
func TestIntegration_CourierDeliverIsScopedAndStateGuarded(t *testing.T) {
	cases := []struct {
		name       string
		status     string
		otherOwner bool
	}{
		{"not yet picked up", "ready", false},
		{"still preparing", "preparing", false},
		{"already delivered", "delivered", false},
		{"cancelled", "cancelled", false},
		{"picked up but assigned to another courier", "picked_up", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := newCourierEnv(t, true)
			markup := 100
			orderID := c.pickedUpOrder(t, 2000, 799, 300, &markup)

			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET status = $2 WHERE id = $1`, orderID, tc.status); err != nil {
				t.Fatalf("set status: %v", err)
			}
			if tc.otherOwner {
				other := newCourierEnv(t, true)
				if _, err := harness.h.db.Pool.Exec(context.Background(),
					`UPDATE orders SET courier_id = $2 WHERE id = $1`, orderID, other.courierID); err != nil {
					t.Fatalf("reassign courier: %v", err)
				}
			}

			if resp := c.deliver(t, orderID); resp.Code != http.StatusBadRequest {
				t.Fatalf("status %d, want 400 (body %s)", resp.Code, resp.Body.String())
			}
			if got := readCourierPayout(t, orderID); got.queuedRow {
				t.Errorf("a refused deliver queued %d cents anyway", got.queued)
			}
		})
	}
}

// A retried deliver (the courier app resending after a lost response) must not
// queue a second transfer. The queue's ON CONFLICT (order_id) is the backstop;
// the status CAS is the first line.
func TestIntegration_CourierDeliverReplayQueuesOnePayout(t *testing.T) {
	c := newCourierEnv(t, true)
	markup := 100
	orderID := c.pickedUpOrder(t, 2000, 799, 300, &markup)

	if resp := c.deliver(t, orderID); resp.Code != http.StatusOK {
		t.Fatalf("first deliver: status %d, body %s", resp.Code, resp.Body.String())
	}
	if resp := c.deliver(t, orderID); resp.Code != http.StatusBadRequest {
		t.Fatalf("replay: status %d, want 400 (the order is no longer picked_up)", resp.Code)
	}

	var rows int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM courier_payout_queue WHERE order_id = $1`, orderID).Scan(&rows); err != nil {
		t.Fatalf("count queue rows: %v", err)
	}
	if rows != 1 {
		t.Errorf("%d payout rows queued for one delivery, want 1 — a courier paid twice", rows)
	}
	var deliveries int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT total_deliveries FROM courier_profiles WHERE user_id = $1`, c.courierID).Scan(&deliveries); err != nil {
		t.Fatalf("read delivery count: %v", err)
	}
	if deliveries != 1 {
		t.Errorf("total_deliveries = %d, want 1", deliveries)
	}
}
