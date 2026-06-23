// Package payout is a LOCAL, Stripe-STUBBED Temporal implementation of the
// courier payout flow — built to demonstrate that Temporal structurally
// eliminates the double-pay bug in internal/scheduler/dispatcher.go (where
// FOR UPDATE SKIP LOCKED releases the row lock before the Stripe transfer, so
// two concurrent sweeps can pay the same payout twice).
//
// It is NOT wired into the live API or dispatcher and makes NO real Stripe
// calls. The "Stripe" here is an in-memory stub that honours idempotency keys
// so we can prove exactly-once money movement under both double-enqueue and
// activity-retry.
//
// Two guarantees this design gives that the polling sweep does not:
//  1. One workflow per payout. The WorkflowID is `payout-<orderID>`; Temporal
//     refuses a second RUNNING execution with the same ID, so a duplicate
//     enqueue can never start a second transfer.
//  2. Idempotent transfer. The transfer activity passes an idempotency key
//     (the WorkflowID) to Stripe, so a Temporal activity RETRY (at-least-once)
//     still charges at-most-once.
package payout

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"
)

const TaskQueue = "payout-task-queue"

// WorkflowID is deterministic per payout so Temporal dedups duplicate enqueues
// AND the same value seeds the Stripe idempotency key.
func WorkflowID(orderID string) string { return "payout-" + orderID }

type PayoutInput struct {
	OrderID         string
	CourierID       string
	StripeConnectID string
	AmountCents     int64
}

type PayoutResult struct {
	TransferID string
	Charged    bool // true if THIS run moved money (false = idempotent no-op)
}

// PayoutWorkflow: reserve -> transfer (idempotent, retried) -> mark complete.
func PayoutWorkflow(ctx workflow.Context, in PayoutInput) (PayoutResult, error) {
	ao := workflow.ActivityOptions{
		StartToCloseTimeout: 15 * time.Second,
		RetryPolicy: &temporal.RetryPolicy{
			InitialInterval:    time.Second,
			BackoffCoefficient: 2.0,
			MaximumAttempts:    5,
		},
	}
	ctx = workflow.WithActivityOptions(ctx, ao)

	if err := workflow.ExecuteActivity(ctx, ReservePayout, in).Get(ctx, nil); err != nil {
		return PayoutResult{}, err
	}

	// Idempotency key is stable across activity retries (the WorkflowID).
	idemKey := workflow.GetInfo(ctx).WorkflowExecution.ID
	var res PayoutResult
	if err := workflow.ExecuteActivity(ctx, StripeTransfer, in, idemKey).Get(ctx, &res); err != nil {
		return PayoutResult{}, err
	}

	if err := workflow.ExecuteActivity(ctx, MarkComplete, in, res.TransferID).Get(ctx, nil); err != nil {
		return PayoutResult{}, err
	}
	return res, nil
}

// --- Activities (in real life these touch the DB + Stripe; here: stubbed) ---

func ReservePayout(ctx context.Context, in PayoutInput) error {
	activity.GetLogger(ctx).Info("ReservePayout", "order", in.OrderID, "cents", in.AmountCents)
	return nil
}

func StripeTransfer(ctx context.Context, in PayoutInput, idemKey string) (PayoutResult, error) {
	id, charged, err := defaultStripe.Transfer(idemKey, in.AmountCents, in.StripeConnectID)
	if err != nil {
		activity.GetLogger(ctx).Warn("StripeTransfer failed (will retry)", "key", idemKey, "err", err)
		return PayoutResult{}, err
	}
	activity.GetLogger(ctx).Info("StripeTransfer ok", "transfer", id, "charged", charged)
	return PayoutResult{TransferID: id, Charged: charged}, nil
}

func MarkComplete(ctx context.Context, in PayoutInput, transferID string) error {
	activity.GetLogger(ctx).Info("MarkComplete", "order", in.OrderID, "transfer", transferID)
	return nil
}

// --- Stripe stub: in-memory, idempotency-key aware ---

type stubStripe struct {
	mu       sync.Mutex
	byKey    map[string]string // idempotencyKey -> transferID (already charged)
	charges  int               // count of REAL money movements
	failOnce map[string]bool   // keys to fail exactly once (to exercise retry)
}

var defaultStripe = &stubStripe{byKey: map[string]string{}, failOnce: map[string]bool{}}

// FailKeyOnce makes the next transfer for idemKey fail once (transient), to
// demonstrate retry-safety. Test/demo helper.
func FailKeyOnce(idemKey string) {
	defaultStripe.mu.Lock()
	defer defaultStripe.mu.Unlock()
	defaultStripe.failOnce[idemKey] = true
}

// Charges returns how many real charges happened (for assertions).
func Charges() int {
	defaultStripe.mu.Lock()
	defer defaultStripe.mu.Unlock()
	return defaultStripe.charges
}

func (s *stubStripe) Transfer(key string, amountCents int64, dest string) (string, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if id, ok := s.byKey[key]; ok {
		return id, false, nil // idempotent: Stripe already has this key -> no new charge
	}
	if s.failOnce[key] {
		delete(s.failOnce, key)
		return "", false, fmt.Errorf("transient stripe error (will retry)")
	}
	s.charges++
	id := fmt.Sprintf("tr_%s", key)
	s.byKey[key] = id
	return id, true, nil
}
