package payout

import (
	"errors"
	"strings"
	"testing"

	"github.com/stretchr/testify/mock"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"
)

// These exercise PayoutWorkflow's orchestration logic against mocked activities
// — no DB, no Stripe, no live Temporal server. What actually matters here is the
// sequencing/error-handling contract this workflow promises the go-live runbook:
// a claim failure must never reach StripeTransfer, and a transfer failure must
// record MarkFailed and still surface as a failed workflow (visible in the UI).

func testInput() PayoutInput {
	return PayoutInput{OrderID: "order-1", CourierID: "courier-1", StripeConnectID: "acct_1", AmountCents: 1500}
}

// newTestEnv wires a WorkflowTestSuite with a zero-value *Activities registered
// by function reference — its nil stripe/pool fields are never dereferenced
// because every activity call below is intercepted by OnActivity.
func newTestEnv(ts *testsuite.WorkflowTestSuite) (*testsuite.TestWorkflowEnvironment, *Activities) {
	env := ts.NewTestWorkflowEnvironment()
	acts := &Activities{}
	env.RegisterActivity(acts.ReservePayout)
	env.RegisterActivity(acts.StripeTransfer)
	env.RegisterActivity(acts.MarkComplete)
	env.RegisterActivity(acts.MarkFailed)
	return env, acts
}

func TestPayoutWorkflow_HappyPath(t *testing.T) {
	var ts testsuite.WorkflowTestSuite
	env, acts := newTestEnv(&ts)

	env.OnActivity(acts.ReservePayout, mock.Anything, testInput()).Return(nil).Once()
	env.OnActivity(acts.StripeTransfer, mock.Anything, testInput()).Return(nil).Once()
	env.OnActivity(acts.MarkComplete, mock.Anything, testInput()).Return(nil).Once()

	env.ExecuteWorkflow(PayoutWorkflow, testInput())

	if !env.IsWorkflowCompleted() {
		t.Fatal("workflow did not complete")
	}
	if err := env.GetWorkflowError(); err != nil {
		t.Fatalf("unexpected workflow error: %v", err)
	}
	env.AssertExpectations(t)
}

// ReservePayout gates the transfer: if the row isn't claimable (already terminal
// or missing), the workflow must stop BEFORE StripeTransfer — otherwise the claim
// is decorative and a completed payout could be re-sent.
func TestPayoutWorkflow_ReserveFails_NeverTransfers(t *testing.T) {
	var ts testsuite.WorkflowTestSuite
	env, acts := newTestEnv(&ts)

	claimErr := temporal.NewNonRetryableApplicationError(
		"payout row not claimable (already terminal or missing)", "PayoutNotClaimable", nil)
	env.OnActivity(acts.ReservePayout, mock.Anything, testInput()).Return(claimErr).Once()

	env.ExecuteWorkflow(PayoutWorkflow, testInput())

	if !env.IsWorkflowCompleted() {
		t.Fatal("workflow did not complete")
	}
	if err := env.GetWorkflowError(); err == nil {
		t.Fatal("expected workflow error, got nil")
	}
	env.AssertNotCalled(t, "StripeTransfer", mock.Anything, mock.Anything)
	env.AssertNotCalled(t, "MarkComplete", mock.Anything, mock.Anything)
}

// A transfer error is retried per the ActivityOptions RetryPolicy (6 attempts,
// matching the old sweep's maxPayoutAttempts) before the workflow gives up. Once
// exhausted it must still record MarkFailed (so the queue row is an accurate
// ledger and admin can see it) while surfacing the original error — not masking
// it with a MarkFailed-specific error.
func TestPayoutWorkflow_TransferFails_MarksFailedAndReturnsOriginalError(t *testing.T) {
	var ts testsuite.WorkflowTestSuite
	env, acts := newTestEnv(&ts)

	transferErr := errors.New("stripe: connect account ineligible for transfers")
	env.OnActivity(acts.ReservePayout, mock.Anything, testInput()).Return(nil).Once()
	env.OnActivity(acts.StripeTransfer, mock.Anything, testInput()).Return(transferErr).Times(6)
	env.OnActivity(acts.MarkFailed, mock.Anything, testInput(), mock.AnythingOfType("string")).Return(nil).Once()

	env.ExecuteWorkflow(PayoutWorkflow, testInput())

	if !env.IsWorkflowCompleted() {
		t.Fatal("workflow did not complete")
	}
	err := env.GetWorkflowError()
	if err == nil {
		t.Fatal("expected workflow error, got nil")
	}
	if !strings.Contains(err.Error(), "connect account ineligible") {
		t.Fatalf("expected original transfer error to surface, got: %v", err)
	}
	env.AssertNotCalled(t, "MarkComplete", mock.Anything, mock.Anything)
	env.AssertExpectations(t)
}
