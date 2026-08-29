package handlers

// The consumer cancel window vs. an in-flight external dispatch.
//
// dispatch.Dispatch claims an order by flipping external_provider to the
// sentinel 'dispatching' BEFORE it makes the paid CreateDelivery call, and only
// writes external_delivery_id afterwards. So for the whole duration of the
// provider round trip — two live HTTP calls, an auction plus a create — the row
// reads external_provider='dispatching', external_delivery_id IS NULL.
//
// CancelOrder's guard keyed on external_delivery_id IS NULL alone, which is
// true throughout that window, so a cancel landing inside it refunded the
// customer while Dispatch went on to buy a courier and persist it (the persist
// is scoped to external_provider='dispatching', which the cancel does not
// clear). Result: refunded customer, billed courier, food collected — the
// double loss the claim CAS exists to prevent.
//
// The same race against the seller's self-pickup was already closed by adding a
// status predicate to the claim CAS (see the comment in dispatch/external.go);
// this is the consumer-cancel half, and it needs the guard on the cancel side
// because cancel is legal from 'accepted', which is exactly a status the claim
// CAS accepts.
//
// SAFETY: DB only. The sentinel is seeded directly rather than by running a
// real dispatch, so no provider client is configured and nothing dials out.

import (
	"context"
	"fmt"
	"net/http"
	"testing"
	"time"
)

// orderDispatchState reads the two columns the cancel guard and the dispatch
// persist both key on.
func orderDispatchState(t *testing.T, orderID string) (status, provider, deliveryID string) {
	t.Helper()
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT status, COALESCE(external_provider, ''), COALESCE(external_delivery_id, '')
		   FROM orders WHERE id = $1`, orderID).Scan(&status, &provider, &deliveryID); err != nil {
		t.Fatalf("read dispatch state: %v", err)
	}
	return
}

// A cancel arriving while a dispatch holds the claim must be refused. The
// window is real and wide — it spans a courier auction and a create call — and
// losing this guard costs a refund AND a courier on the same order.
func TestIntegration_ConsumerCancelBlockedWhileADispatchHoldsTheClaim(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "cancel-claim")
	pi := fmt.Sprintf("pi_cc_%d", time.Now().UnixNano())
	orderID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)

	// Exactly the row state Dispatch leaves behind between winning the claim and
	// recording the delivery it has just paid for.
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE orders SET status = 'accepted', external_provider = 'dispatching',
		        external_delivery_id = NULL WHERE id = $1`, orderID); err != nil {
		t.Fatalf("seed in-flight claim: %v", err)
	}

	rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 — cancelling inside the dispatch window refunds the customer "+
			"while Dispatch goes on to buy and persist a courier for the same order (body %s)",
			rec.Code, rec.Body.String())
	}

	status, provider, _ := orderDispatchState(t, orderID)
	if status != "accepted" {
		t.Errorf("status = %q, want accepted (unchanged)", status)
	}
	// The claim must survive too: clearing it here would let the reaper hand the
	// order back to the sweep and buy a SECOND delivery.
	if provider != "dispatching" {
		t.Errorf("external_provider = %q, want the claim sentinel left intact", provider)
	}

	var refundedAt *time.Time
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT refunded_at FROM orders WHERE id = $1`, orderID).Scan(&refundedAt); err != nil {
		t.Fatalf("read refunded_at: %v", err)
	}
	if refundedAt != nil {
		t.Errorf("order was stamped refunded while a paid dispatch was in flight")
	}
}

// The block must be temporary, not a trap. When a dispatch fails it releases
// the sentinel (dispatch/external.go fail()), and the consumer must get their
// cancel window back — otherwise a provider outage would weld an unwanted order
// to the customer with no way out.
func TestIntegration_ConsumerCancelReopensAfterAFailedDispatchReleasesTheClaim(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "cancel-released")
	pi := fmt.Sprintf("pi_cr_%d", time.Now().UnixNano())
	orderID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)

	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE orders SET status = 'accepted', external_provider = 'dispatching' WHERE id = $1`,
		orderID); err != nil {
		t.Fatalf("seed in-flight claim: %v", err)
	}
	if rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("pre-release cancel: status %d, want 400", rec.Code)
	}

	// What fail() does on a transient provider failure: release the sentinel,
	// count the attempt, leave no delivery behind.
	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE orders SET external_provider = NULL, external_dispatch_attempts = 1 WHERE id = $1`,
		orderID); err != nil {
		t.Fatalf("release claim: %v", err)
	}

	rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("post-release cancel: status %d, want 200 — a released claim must reopen the cancel "+
			"window, or a failed dispatch strands the customer (body %s)", rec.Code, rec.Body.String())
	}
	if status, _, _ := orderDispatchState(t, orderID); status != "cancelled" {
		t.Errorf("status = %q, want cancelled", status)
	}
}

// The guard must key on the CLAIM, not on any non-null provider — and it must
// not regress the cases the cancel window is supposed to serve. Table covers
// every provider-linkage shape a cancellable order can be in.
func TestIntegration_ConsumerCancelAgainstEveryDispatchLinkage(t *testing.T) {
	cases := []struct {
		name       string
		provider   any
		deliveryID any
		wantStatus int
	}{
		{"no dispatch at all", nil, nil, http.StatusOK},
		{"claim held, no delivery yet", "dispatching", nil, http.StatusBadRequest},
		{"uber delivery bought", "uber_direct", "del_u", http.StatusBadRequest},
		{"doordash delivery bought", "doordash_drive", "del_d", http.StatusBadRequest},
		{"shipday delivery bought", "shipday", "77", http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			token, _ := harness.registerUser(t, "cancel-linkage")
			pi := fmt.Sprintf("pi_cl_%d", time.Now().UnixNano())
			orderID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)

			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET status = 'accepted', external_provider = $2, external_delivery_id = $3
				  WHERE id = $1`, orderID, tc.provider, tc.deliveryID); err != nil {
				t.Fatalf("seed linkage: %v", err)
			}

			rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			status, _, _ := orderDispatchState(t, orderID)
			if tc.wantStatus == http.StatusOK && status != "cancelled" {
				t.Errorf("status = %q, want cancelled", status)
			}
			if tc.wantStatus != http.StatusOK && status != "accepted" {
				t.Errorf("status = %q, want accepted (unchanged)", status)
			}
		})
	}
}
