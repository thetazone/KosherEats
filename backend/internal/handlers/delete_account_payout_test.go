package handlers

// A courier deleting their account must not erase what the platform still owes
// them. courier_payout_queue.courier_id used to be NOT NULL ... ON DELETE
// CASCADE, so DeleteAccount's final `DELETE FROM users` took every queue row
// with it — pending, processing and failed_permanent alike. The money owed
// disappeared with no ledger row left for anyone to reconcile, which flatly
// contradicts the anonymize-don't-delete rule the same handler applies to
// orders. Migration 060 switches the FK to ON DELETE SET NULL and the handler
// freezes still-outstanding rows first.
//
// SAFETY: DB only. Stripe stays in the harness's dev stub mode; nothing dials
// out and no transfer is attempted — this covers the enqueue/ledger side only.

import (
	"context"
	"net/http"
	"testing"

	"github.com/go-chi/chi/v5"
)

func accountRouter(h *Handler) http.Handler {
	r := chi.NewRouter()
	r.Route("/api/v1/users", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Delete("/account", h.DeleteAccount)
	})
	return r
}

// TestIntegration_DeleteAccountPreservesOutstandingCourierPayouts walks the
// exact reported scenario: a courier delivers before finishing Stripe Connect
// onboarding (so the row is queued with a NULL connect id, waiting on the
// account.updated backfill), then quits and taps "Delete account".
func TestIntegration_DeleteAccountPreservesOutstandingCourierPayouts(t *testing.T) {
	c := newCourierEnv(t, false)
	ctx := context.Background()
	pool := harness.h.db.Pool

	orderID := c.pickedUpOrder(t, 4000, 500, 300, nil)
	if rec := c.deliver(t, orderID); rec.Code != http.StatusOK {
		t.Fatalf("deliver: status %d, body %s", rec.Code, rec.Body.String())
	}
	queued := readCourierPayout(t, orderID)
	if !queued.queuedRow || queued.status != "pending" || queued.connectID != "" {
		t.Fatalf("precondition: want a pending NULL-connect queue row, got %+v", queued)
	}
	// courierEnv's cleanup deletes queue rows by courier_id, which is exactly
	// the column this test nulls out — clean up by order instead.
	t.Cleanup(func() {
		_, _ = pool.Exec(ctx, `DELETE FROM courier_payout_queue WHERE order_id = $1`, orderID)
	})

	rec := doRequest(accountRouter(harness.h), http.MethodDelete, "/api/v1/users/account", c.token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete account: status %d, body %s", rec.Code, rec.Body.String())
	}

	var stillThere bool
	if err := pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, c.courierID).Scan(&stillThere); err != nil {
		t.Fatalf("check user deleted: %v", err)
	}
	if stillThere {
		t.Fatalf("user %s was not deleted", c.courierID)
	}

	var courierID *string
	var status string
	var amount int
	var lastError string
	if err := pool.QueryRow(ctx,
		`SELECT courier_id, status, amount_cents, last_error
		   FROM courier_payout_queue WHERE order_id = $1`, orderID,
	).Scan(&courierID, &status, &amount, &lastError); err != nil {
		t.Fatalf("outstanding payout row did not survive account deletion: %v", err)
	}
	if courierID != nil {
		t.Errorf("courier_id = %q, want NULL after the user row was deleted", *courierID)
	}
	if amount != queued.queued {
		t.Errorf("amount_cents = %d, want %d preserved", amount, queued.queued)
	}
	// Parked for a human: the sweep must not keep retrying a payout whose
	// courier — and Stripe Connect account — no longer exists.
	if status != "failed_permanent" {
		t.Errorf("status = %q, want failed_permanent", status)
	}
	if lastError == "" {
		t.Error("last_error is empty; the row gives an admin no reason for the failure")
	}
}

// An already-settled payout is a completed accounting record. It must survive
// the delete untouched apart from losing its courier link — nothing about the
// courier quitting makes a transfer that already happened un-happen.
func TestIntegration_DeleteAccountKeepsCompletedCourierPayouts(t *testing.T) {
	c := newCourierEnv(t, true)
	ctx := context.Background()
	pool := harness.h.db.Pool

	orderID := c.pickedUpOrder(t, 4000, 500, 300, nil)
	if rec := c.deliver(t, orderID); rec.Code != http.StatusOK {
		t.Fatalf("deliver: status %d, body %s", rec.Code, rec.Body.String())
	}
	if _, err := pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status = 'completed', completed_at = NOW()
		  WHERE order_id = $1`, orderID); err != nil {
		t.Fatalf("mark payout completed: %v", err)
	}
	t.Cleanup(func() {
		_, _ = pool.Exec(ctx, `DELETE FROM courier_payout_queue WHERE order_id = $1`, orderID)
	})

	rec := doRequest(accountRouter(harness.h), http.MethodDelete, "/api/v1/users/account", c.token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete account: status %d, body %s", rec.Code, rec.Body.String())
	}

	var courierID *string
	var status string
	if err := pool.QueryRow(ctx,
		`SELECT courier_id, status FROM courier_payout_queue WHERE order_id = $1`, orderID,
	).Scan(&courierID, &status); err != nil {
		t.Fatalf("completed payout row did not survive account deletion: %v", err)
	}
	if courierID != nil {
		t.Errorf("courier_id = %q, want NULL", *courierID)
	}
	if status != "completed" {
		t.Errorf("status = %q, want completed (a settled transfer stays settled)", status)
	}
}
