// Command demo proves Temporal eliminates the courier double-pay bug.
//
//	Demo A: enqueue the SAME payout twice, concurrently. The polling sweep would
//	        double-pay; here the second enqueue attaches to the running workflow
//	        (WorkflowID dedup) -> exactly ONE charge.
//	Demo B: a payout whose Stripe transfer fails once. Temporal retries the
//	        activity (at-least-once), but the idempotency key makes Stripe charge
//	        at-most-once -> exactly ONE charge.
//
// Expected: 2 distinct orders -> 2 charges total (never 3+). Stripe is stubbed;
// no real money moves.
package main

import (
	"context"
	"log"
	"sync"

	"github.com/koshereats/temporal/payout"
	enums "go.temporal.io/api/enums/v1"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"
)

func main() {
	c, err := client.Dial(client.Options{HostPort: "127.0.0.1:7233"})
	if err != nil {
		log.Fatalln("dial:", err)
	}
	defer c.Close()

	// In-process worker so we can read the stub's charge count after.
	w := worker.New(c, payout.TaskQueue, worker.Options{})
	w.RegisterWorkflow(payout.PayoutWorkflow)
	w.RegisterActivity(payout.ReservePayout)
	w.RegisterActivity(payout.StripeTransfer)
	w.RegisterActivity(payout.MarkComplete)
	if err := w.Start(); err != nil {
		log.Fatalln("worker start:", err)
	}
	defer w.Stop()

	ctx := context.Background()

	// ---- Demo A: double enqueue, same payout, concurrent ----
	inA := payout.PayoutInput{OrderID: "orderA", CourierID: "c1", StripeConnectID: "acct_1", AmountCents: 1500}
	optsA := client.StartWorkflowOptions{
		ID:                       payout.WorkflowID(inA.OrderID),
		TaskQueue:                payout.TaskQueue,
		WorkflowIDConflictPolicy: enums.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING,
	}
	var wg sync.WaitGroup
	runIDs := make([]string, 2)
	results := make([]payout.PayoutResult, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			we, err := c.ExecuteWorkflow(ctx, optsA, payout.PayoutWorkflow, inA)
			if err != nil {
				log.Fatalf("A enqueue %d: %v", i, err)
			}
			runIDs[i] = we.GetRunID()
			if err := we.Get(ctx, &results[i]); err != nil {
				log.Fatalf("A result %d: %v", i, err)
			}
		}(i)
	}
	wg.Wait()
	log.Printf("Demo A: two enqueues -> runIDs %s / %s (same=%v)", runIDs[0], runIDs[1], runIDs[0] == runIDs[1])
	log.Printf("Demo A: results charged=%v / %v", results[0].Charged, results[1].Charged)

	// ---- Demo B: transfer fails once, retries, charges once ----
	inB := payout.PayoutInput{OrderID: "orderB", CourierID: "c2", StripeConnectID: "acct_2", AmountCents: 2200}
	payout.FailKeyOnce(payout.WorkflowID(inB.OrderID))
	optsB := client.StartWorkflowOptions{ID: payout.WorkflowID(inB.OrderID), TaskQueue: payout.TaskQueue}
	weB, err := c.ExecuteWorkflow(ctx, optsB, payout.PayoutWorkflow, inB)
	if err != nil {
		log.Fatalln("B enqueue:", err)
	}
	var resB payout.PayoutResult
	if err := weB.Get(ctx, &resB); err != nil {
		log.Fatalln("B result:", err)
	}
	log.Printf("Demo B: transfer %s charged=%v (after one forced failure+retry)", resB.TransferID, resB.Charged)

	// ---- Assertion ----
	charges := payout.Charges()
	log.Printf("TOTAL real charges: %d (expected 2)", charges)
	if charges == 2 {
		log.Println("PASS ✅ — double-enqueue and activity-retry each charged exactly once; double-pay structurally prevented.")
	} else {
		log.Printf("FAIL ❌ — expected 2 charges, got %d", charges)
	}
}
