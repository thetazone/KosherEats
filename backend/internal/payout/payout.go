// Package payout is the Temporal implementation of the courier payout flow.
//
// It exists to give EXACTLY-ONCE payout at both layers, which the polling sweep
// in internal/scheduler/dispatcher.go does not:
//   - WorkflowID = "payout-<orderID>" → Temporal refuses a second running
//     execution per order, so a duplicate enqueue can't start a second transfer.
//   - The transfer activity passes a Stripe idempotency key (the WorkflowID), so
//     a Temporal activity RETRY (at-least-once) still charges at-most-once.
//
// It is DISABLED BY DEFAULT and wired in behind cfg.Temporal.HostPort != "".
// When off, the existing sweep is unchanged. Turning it on requires a Temporal
// server (e.g. Temporal Cloud); see cmd/api wiring.
package payout

import (
	"context"
	"time"

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

// WorkflowID is deterministic per order: dedups duplicate enqueues AND seeds the
// Stripe idempotency key. order_id is UNIQUE in courier_payout_queue, so there
// is exactly one payout per order.
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

	idemKey := workflow.GetInfo(ctx).WorkflowExecution.ID // == WorkflowID(orderID)
	transferErr := workflow.ExecuteActivity(ctx, a.StripeTransfer, in, idemKey).Get(ctx, nil)
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
// processing). Idempotent: re-running is a no-op (Temporal activities are
// at-least-once). The row is the durable ledger written in-tx by DeliverOrder.
func (a *Activities) ReservePayout(ctx context.Context, in PayoutInput) error {
	activity.GetLogger(ctx).Info("ReservePayout", "order", in.OrderID, "cents", in.AmountCents)
	_, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='processing', updated_at=NOW()
		  WHERE order_id=$1 AND status IN ('pending','processing')`, in.OrderID)
	return err
}

// StripeTransfer moves the money. The Stripe idempotency key must match the
// legacy sweep's key (the courier_payout_queue row id, p.id in dispatcher.go) so
// that a payout re-attempted across a Temporal on/off cutover is deduped by
// Stripe rather than double-paid. order_id is UNIQUE in the queue, so the row id
// is stable for the order. The incoming idemKey (the WorkflowID) is ignored for
// the Stripe key for exactly this cross-path parity reason.
func (a *Activities) StripeTransfer(ctx context.Context, in PayoutInput, idemKey string) error {
	if in.StripeConnectID == "" || in.AmountCents <= 0 {
		// Non-retryable: bad payout data won't fix itself on retry.
		return temporal.NewNonRetryableApplicationError("invalid payout parameters", "InvalidPayout", nil)
	}
	// Resolve the canonical idempotency key (queue row id) shared with the legacy
	// path. Falls back to the WorkflowID-derived key only if the row is missing.
	stripeKey := idemKey
	var rowID string
	if err := a.pool.QueryRow(ctx,
		`SELECT id::text FROM courier_payout_queue WHERE order_id = $1`, in.OrderID,
	).Scan(&rowID); err == nil && rowID != "" {
		stripeKey = rowID
	}
	activity.GetLogger(ctx).Info("StripeTransfer", "order", in.OrderID, "key", stripeKey)
	return a.stripe.TransferToCourier(in.StripeConnectID, in.AmountCents, in.OrderID, stripeKey)
}

func (a *Activities) MarkComplete(ctx context.Context, in PayoutInput) error {
	activity.GetLogger(ctx).Info("MarkComplete", "order", in.OrderID)
	_, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='completed', completed_at=NOW(), updated_at=NOW()
		  WHERE order_id=$1`, in.OrderID)
	return err
}

func (a *Activities) MarkFailed(ctx context.Context, in PayoutInput, reason string) error {
	activity.GetLogger(ctx).Warn("MarkFailed", "order", in.OrderID, "reason", reason)
	_, err := a.pool.Exec(ctx,
		`UPDATE courier_payout_queue SET status='failed_permanent', last_error=$2, updated_at=NOW()
		  WHERE order_id=$1`, in.OrderID, reason)
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
