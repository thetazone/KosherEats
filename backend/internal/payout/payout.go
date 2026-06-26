// Package payout is the Temporal implementation of the courier payout flow.
//
// It exists to give EXACTLY-ONCE payout at both layers, which the polling sweep
// in internal/scheduler/dispatcher.go does not:
//   - WorkflowID = "payout-<orderID>" → Temporal refuses a second running
//     execution per order, so a duplicate enqueue can't start a second transfer.
//   - The transfer activity passes a Stripe idempotency key (the courier_payout_queue
//     row id, shared with the legacy sweep), so a Temporal activity RETRY
//     (at-least-once) still charges at-most-once. The key is resolved with no
//     fallback — a read failure retries with the same key rather than swapping it.
//
// It is DISABLED BY DEFAULT and wired in behind cfg.Temporal.HostPort != "".
// When off, the existing sweep is unchanged. Turning it on requires a Temporal
// server (e.g. Temporal Cloud); see cmd/api wiring.
package payout

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/payments"
	enums "go.temporal.io/api/enums/v1"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/worker"
	"go.temporal.io/sdk/workflow"
)

const DefaultTaskQueue = "payout-task-queue"

// WorkflowID is deterministic per order: dedups duplicate enqueues (Temporal
// refuses a second running execution with the same id). The Stripe idempotency
// key is the queue row id, resolved in StripeTransfer — not this id. order_id is
// UNIQUE in courier_payout_queue, so there is exactly one payout per order.
func WorkflowID(orderID string) string { return "payout-" + orderID }

type PayoutInput struct {
	OrderID         string
	CourierID       string
	StripeConnectID string
	AmountCents     int
}

// PayoutWorkflow: claim (processing) -> transfer (idempotent, retried) -> complete.
// On terminal transfer failure the row is marked failed_permanent (mirrors the
// sweep's semantics) and the workflow fails so it is visible in the Temporal UI.
func PayoutWorkflow(ctx workflow.Context, in PayoutInput) error {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{
		StartToCloseTimeout: 30 * time.Second,
		RetryPolicy: &temporal.RetryPolicy{
			InitialInterval:    2 * time.Second,
			BackoffCoefficient: 2.0,
			MaximumInterval:    time.Minute,
			MaximumAttempts:    6, // matches maxPayoutAttempts in the old sweep
		},
	})

	var a *Activities // activities are registered as methods; name resolves by func
	if err := workflow.ExecuteActivity(ctx, a.ReservePayout, in).Get(ctx, nil); err != nil {
		return err
	}

	transferErr := workflow.ExecuteActivity(ctx, a.StripeTransfer, in).Get(ctx, nil)
	if transferErr != nil {
		// Best-effort: record terminal failure so the queue row is an accurate
		// ledger and admin can see it (don't mask the original error).
		_ = workflow.ExecuteActivity(ctx, a.MarkFailed, in, transferErr.Error()).Get(ctx, nil)
		return transferErr
	}

	return workflow.ExecuteActivity(ctx, a.MarkComplete, in).Get(ctx, nil)
}

// Activities hold the real dependencies (Stripe + DB). Registered as methods so
// the workflow's a.ReservePayout / a.StripeTransfer references resolve by name.
type Activities struct {
	stripe *payments.Client
	pool   *pgxpool.Pool
}

func NewActivities(stripe *payments.Client, pool *pgxpool.Pool) *Activities {
	return &Activities{stripe: stripe, pool: pool}
}

// ReservePayout atomically claims the payout row (pending|processing ->
// processing) and gates the transfer: if no row is claimable the workflow must
// NOT proceed to move money. The row is the durable ledger written in-tx by
// DeliverOrder. A reclaim of an already-'processing' row is intentional — it
// lets the reconcile reaper re-drive a stuck row, which is safe because
// StripeTransfer's idempotency key is stable per order (Stripe dedupes the
// re-transfer).
func (a *Activities) ReservePayout(ctx context.Context, in PayoutInput) error {
	activity.GetLogger(ctx).Info("ReservePayout", "order", in.OrderID, "cents", in.AmountCents)
	ct, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='processing', updated_at=NOW()
		  WHERE order_id=$1 AND status IN ('pending','processing')`, in.OrderID)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		// Row is missing, or already terminal ('completed'/'failed_permanent').
		// Stop the workflow non-retryably BEFORE the transfer — without this gate
		// the claim was decorative and a completed payout could be re-sent (the
		// Stripe key is the only thing that would have caught it).
		return temporal.NewNonRetryableApplicationError(
			"payout row not claimable (already terminal or missing)", "PayoutNotClaimable", nil)
	}
	return nil
}

// StripeTransfer moves the money. The Stripe idempotency key must match the
// legacy sweep's key (the courier_payout_queue row id, p.id in dispatcher.go) so
// that a payout re-attempted across a Temporal on/off cutover is deduped by
// Stripe rather than double-paid. order_id is UNIQUE in the queue, so the row id
// is stable for the order. CRITICAL: this key must be a SINGLE stable value
// across every retry of this logical transfer — so if the row read fails we
// return an error and retry rather than transferring with a divergent fallback
// key (a per-attempt key change is a direct double-pay vector on a DB blip).
func (a *Activities) StripeTransfer(ctx context.Context, in PayoutInput) error {
	if in.StripeConnectID == "" || in.AmountCents <= 0 {
		// Non-retryable: bad payout data won't fix itself on retry.
		return temporal.NewNonRetryableApplicationError("invalid payout parameters", "InvalidPayout", nil)
	}
	// Resolve the canonical idempotency key (queue row id) shared with the legacy
	// path. NO fallback: a transient read failure is retried with the SAME key,
	// never swapped for a different one.
	var rowID string
	err := a.pool.QueryRow(ctx,
		`SELECT id::text FROM courier_payout_queue WHERE order_id = $1`, in.OrderID,
	).Scan(&rowID)
	if errors.Is(err, pgx.ErrNoRows) {
		// No ledger row to pay against — don't transfer; surface terminally.
		return temporal.NewNonRetryableApplicationError(
			"no payout queue row for order", "MissingPayoutRow", nil)
	}
	if err != nil {
		// Transient: retry with the same key once the DB recovers.
		return fmt.Errorf("resolve payout idempotency key for order %s: %w", in.OrderID, err)
	}
	activity.GetLogger(ctx).Info("StripeTransfer", "order", in.OrderID, "key", rowID)
	return a.stripe.TransferToCourier(in.StripeConnectID, in.AmountCents, in.OrderID, rowID)
}

// MarkComplete flips the claimed row to 'completed'. Status-guarded to
// 'processing' so it can never resurrect a 'failed_permanent' row into
// 'completed' (a halt set by the Stripe refund/dispute webhook must stick). A
// 0-row update is treated as an idempotent no-op, not an error.
func (a *Activities) MarkComplete(ctx context.Context, in PayoutInput) error {
	activity.GetLogger(ctx).Info("MarkComplete", "order", in.OrderID)
	_, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='completed', completed_at=NOW(), updated_at=NOW()
		  WHERE order_id=$1 AND status='processing'`, in.OrderID)
	return err
}

// MarkFailed records a terminal transfer failure. Status-guarded to 'processing'
// so it can't overwrite an already-'completed' row (e.g. a late MarkFailed after
// a successful retry committed completion).
func (a *Activities) MarkFailed(ctx context.Context, in PayoutInput, reason string) error {
	activity.GetLogger(ctx).Warn("MarkFailed", "order", in.OrderID, "reason", reason)
	_, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='failed_permanent', last_error=$2, updated_at=NOW()
		  WHERE order_id=$1 AND status='processing'`, in.OrderID, reason)
	return err
}

// NewWorker builds + registers a worker for the payout task queue. Caller Starts
// and Stops it.
func NewWorker(c client.Client, taskQueue string, acts *Activities) worker.Worker {
	w := worker.New(c, taskQueue, worker.Options{})
	w.RegisterWorkflow(PayoutWorkflow)
	w.RegisterActivity(acts.ReservePayout)
	w.RegisterActivity(acts.StripeTransfer)
	w.RegisterActivity(acts.MarkComplete)
	w.RegisterActivity(acts.MarkFailed)
	return w
}

// Start fires a payout workflow (fire-and-forget; the durable queue row + a
// reconcile pass guarantee eventual start even if this call is lost). Safe to
// call twice for the same order: USE_EXISTING attaches to the running execution.
func Start(ctx context.Context, c client.Client, taskQueue string, in PayoutInput) error {
	_, err := c.ExecuteWorkflow(ctx, client.StartWorkflowOptions{
		ID:                       WorkflowID(in.OrderID),
		TaskQueue:                taskQueue,
		WorkflowIDConflictPolicy: enums.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING,
	}, PayoutWorkflow, in)
	return err
}

// Starter is injected into the handler + scheduler. A nil *Starter (Temporal
// disabled) makes Start a no-op, so callers need no enabled-flag branching —
// when the Starter is nil the legacy direct-transfer path runs unchanged.
type Starter struct {
	Client    client.Client
	TaskQueue string
}

// Enabled reports whether payouts should route through Temporal.
func (s *Starter) Enabled() bool { return s != nil && s.Client != nil }

// Start launches the payout workflow for one order; no-op when disabled.
func (s *Starter) Start(ctx context.Context, in PayoutInput) error {
	if !s.Enabled() {
		return nil
	}
	return Start(ctx, s.Client, s.TaskQueue, in)
}
