package handlers

// CreateOrder must serialize against the orphan-payment sweep on the
// PaymentIntent it is redeeming. The sweep (scheduler.refundOrphanUnlessOrdered)
// checks "no order for this PI?" and then refunds; a CreateOrder that verifies
// the intent and commits between those two steps produces a fulfilled order
// on a refunded charge. Both sides take pg_advisory_xact_lock on the PI id, so
// whichever starts second waits for the other's transaction to end.
//
// This test plays the sweep: it holds that lock from a transaction of its own
// and asserts CreateOrder blocks until the lock is released, then succeeds.
//
// SAFETY: DB only. Stripe runs in stub mode, so nothing is dialed out.

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestIntegration_CreateOrderWaitsForTheOrphanSweepLockOnItsPaymentIntent(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "orphan-lock")
	pi := fmt.Sprintf("pi_orphan_lock_%d", time.Now().UnixNano())
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	ctx := context.Background()
	sweepTx, err := harness.h.db.Pool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin sweep tx: %v", err)
	}
	defer sweepTx.Rollback(ctx) //nolint:errcheck
	if _, err := sweepTx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, pi); err != nil {
		t.Fatalf("take sweep lock: %v", err)
	}

	done := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		done <- harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
			"restaurant_id":     harness.approvedRestID,
			"payment_intent_id": pi,
			"fulfillment_type":  "pickup",
		})
	}()

	select {
	case rec := <-done:
		t.Fatalf("CreateOrder returned %d while the orphan sweep held the lock on its PaymentIntent — "+
			"it can commit an order between the sweep's no-order check and its refund", rec.Code)
	case <-time.After(300 * time.Millisecond):
		// Blocked, as it must be.
	}

	if err := sweepTx.Commit(ctx); err != nil {
		t.Fatalf("release sweep lock: %v", err)
	}

	select {
	case rec := <-done:
		if rec.Code != http.StatusCreated {
			t.Fatalf("CreateOrder after the lock was released: status %d, body %s", rec.Code, rec.Body.String())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("CreateOrder never returned after the sweep lock was released")
	}
}
