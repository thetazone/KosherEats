package scheduler

import (
	"testing"
	"time"

	"github.com/koshereats/backend/internal/payments"
)

// TestPayoutRetryHorizon is the guard on the double-pay bug: every payout
// attempt replays ONE Stripe idempotency key (the courier_payout_queue row id),
// and Stripe forgets a key after payments.IdempotencyRetention. If the retry
// run outlives that, a transfer that succeeded but lost its HTTP response gets
// replayed as a second REAL transfer to the courier's Connect account.
//
// The old schedule (5m/15m/1h/6h/24h) spanned 31h20m and did exactly that.
func TestPayoutRetryHorizon(t *testing.T) {
	horizon := payoutRetryHorizon()

	if horizon >= payments.IdempotencyRetention {
		t.Fatalf("payout retry run spans %v, which reaches or exceeds Stripe's "+
			"idempotency retention of %v — the final attempt would replay a "+
			"forgotten key and double-pay the courier",
			horizon, payments.IdempotencyRetention)
	}

	// The reconcile guard must fire before the schedule can reach the end of
	// its run, otherwise a normal run would be checked against Stripe on every
	// late attempt (wasteful) or, worse, the guard would never be the backstop
	// it is documented to be.
	if horizon >= payoutIdempotencyGuardAfter {
		t.Fatalf("payout retry run spans %v, at or past the reconcile guard "+
			"threshold of %v", horizon, payoutIdempotencyGuardAfter)
	}

	if payoutIdempotencyGuardAfter >= payments.IdempotencyRetention {
		t.Fatalf("reconcile guard fires at %v, at or past Stripe's retention "+
			"of %v — it would engage only after the key had already expired",
			payoutIdempotencyGuardAfter, payments.IdempotencyRetention)
	}
}

// TestPayoutBackoffMonotonic keeps the schedule a genuine backoff: no step may
// be shorter than the one before it, so a persistent Stripe fault doesn't get
// hammered faster the longer it lasts.
func TestPayoutBackoffMonotonic(t *testing.T) {
	prev := 0
	for attempt := 1; attempt < maxPayoutAttempts; attempt++ {
		secs := payoutBackoffSecs(attempt)
		if secs <= 0 {
			t.Fatalf("payoutBackoffSecs(%d) = %d, want a positive delay", attempt, secs)
		}
		if secs < prev {
			t.Fatalf("payoutBackoffSecs(%d) = %ds, shorter than the previous step of %ds",
				attempt, secs, prev)
		}
		prev = secs
	}
}

// TestPayoutFinalAttemptInsideWindow spells out the concrete failure the bug
// report described: the wall-clock offset of each attempt from the first one,
// with the last of them required to land inside the retention window.
func TestPayoutFinalAttemptInsideWindow(t *testing.T) {
	var offset time.Duration
	for attempt := 1; attempt < maxPayoutAttempts; attempt++ {
		offset += time.Duration(payoutBackoffSecs(attempt)) * time.Second
		if offset >= payments.IdempotencyRetention {
			t.Fatalf("attempt %d fires at t+%v, past Stripe's %v idempotency retention",
				attempt+1, offset, payments.IdempotencyRetention)
		}
	}
}
