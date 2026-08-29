package dispatch

// The persist step — the LAST thing Dispatch does, and the only one that runs
// after the platform has already been billed for a courier.
//
// SAFETY: same sandbox as dispatch_pg_test.go. Every provider request is
// rewritten to the loopback fake server by TestMain's transport, and any
// unrecognized host is refused, so nothing here can reach a real courier API.

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"sync/atomic"
	"testing"
)

// reapStaleDispatchClaimsSQL is a verbatim copy of the statement in
// scheduler.reapStaleDispatchClaims (internal/scheduler/dispatcher.go). It is
// duplicated rather than imported because internal/scheduler depends on
// internal/dispatch — importing it back here would be an import cycle. Keep the
// two in sync; the tests below only rely on its predicate, not its timing.
const reapStaleDispatchClaimsSQL = `
	UPDATE orders
	   SET external_provider = NULL, updated_at = NOW()
	 WHERE external_provider = 'dispatching'
	   AND external_delivery_id IS NULL
	   AND courier_id IS NULL
	   AND updated_at < NOW() - INTERVAL '10 minutes'`

// overflowFee is larger than the INTEGER column orders.provider_fee_cents can
// hold (migration 047), so the persist UPDATE fails inside Postgres while
// everything before it — including the PAID CreateDelivery — succeeds. It is a
// deterministic stand-in for the whole class of "the persist Exec returned an
// error": a connection reset, a failover, a statement timeout, or the caller's
// context being cancelled between create and persist.
const overflowFee = 3000000000

// A failed persist must not leave the order looking like a dead claim, because
// a dead claim is precisely what the reaper is built to recycle.
//
// Dispatch handles the OTHER persist failure — RowsAffected() == 0, the stolen
// sentinel — loudly: it returns the delivery ids and pages an operator
// (TestDispatch_OrphanedDeliverySurfacesIDsWithError). The Exec-error branch is
// strictly worse yet strictly quieter: it returns early leaving
// external_provider = 'dispatching', external_delivery_id NULL and
// external_dispatch_attempts un-incremented. That row matches
// reapStaleDispatchClaims exactly, so ten minutes later the claim is released,
// the next sweep re-dispatches, and a SECOND courier is bought and billed for
// food the first one is already carrying.
func TestDispatch_PersistFailureStrandsClaimAndLetsTheReaperDoubleBuy(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))

	creates := router.count("/v1/customers/fake-customer/deliveries")
	// Delivery #1: created and BILLED, but its fee cannot be stored.
	router.on("/v1/customers/fake-customer/deliveries",
		jsonHandler(200, fmt.Sprintf(`{"id":"paid_delivery_1","tracking_url":"https://u/1","fee":%d}`, overflowFee)))

	id := seedOrder(t, orderOpts{})
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("a persist that cannot store the delivery must return an error")
	}
	if n := atomic.LoadInt32(creates); n != 1 {
		t.Fatalf("provider create called %d times, want 1", n)
	}

	// The order is now in the stranded shape: claimed, unrecorded, uncounted.
	st := readOrder(t, id)
	if st.provider != "dispatching" {
		t.Fatalf("external_provider = %q, want the stuck 'dispatching' sentinel", st.provider)
	}
	if st.deliveryID != "" {
		t.Fatalf("external_delivery_id = %q, want empty — the persist failed", st.deliveryID)
	}
	if st.attempts != 0 {
		t.Errorf("external_dispatch_attempts = %d, want 0 — the Exec-error branch counts nothing, "+
			"so the attempts cap cannot stop the re-dispatch this strands into", st.attempts)
	}

	// Age the claim past the reaper's window and run the reaper's own statement.
	if _, err := testPool.Exec(context.Background(),
		`UPDATE orders SET updated_at = NOW() - INTERVAL '11 minutes' WHERE id = $1`, id); err != nil {
		t.Fatalf("age claim: %v", err)
	}
	tag, err := testPool.Exec(context.Background(), reapStaleDispatchClaimsSQL)
	if err != nil {
		t.Fatalf("reap: %v", err)
	}
	if tag.RowsAffected() == 0 {
		t.Fatal("the reaper did not match the stranded claim — re-check the predicate copy above")
	}

	// Delivery #2. Nothing about the order records that a courier is already
	// carrying this food, so the claim CAS admits the sweep and we pay twice.
	router.on("/v1/customers/fake-customer/deliveries",
		jsonHandler(200, `{"id":"paid_delivery_2","tracking_url":"https://u/2","fee":799}`))
	provider, delID, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("second dispatch: %v", err)
	}

	if n := atomic.LoadInt32(creates); n == 2 && delID == "paid_delivery_2" && provider == "uber_direct" {
		t.Logf("KNOWN RESIDUAL: a persist the database itself refuses (here an out-of-range fee; "+
			"in production a hard outage) still strands delivery %q, and the reaper recycles the "+
			"claim into a second PAID delivery %q for order %s. Dispatch now runs the persist on a "+
			"detached context (removing the common cause — see "+
			"TestDispatch_PersistSurvivesCallerCancellation) and pages an operator on this branch "+
			"instead of logging silently, but it cannot write a marker into a database that is "+
			"refusing writes. Closing this fully needs the reaper to distinguish a claim taken "+
			"before the create from one taken after it.",
			"paid_delivery_1", delID, id)
	} else {
		t.Errorf("expected the documented residual (2 creates, second delivery persisted); "+
			"got %d creates, provider %q, delivery %q — if this branch was fixed, update this test",
			n, provider, delID)
	}
}

// cancelAfterResponse kills a context the moment a given provider call has
// FULLY returned. It drains and re-buffers the response body inside the
// RoundTrip, so the client's own read cannot be interrupted by the
// cancellation — which makes "the caller's context died after the courier was
// bought but before we recorded it" deterministic rather than a race.
type cancelAfterResponse struct {
	base   http.RoundTripper
	path   string
	cancel context.CancelFunc
}

func (c cancelAfterResponse) RoundTrip(r *http.Request) (*http.Response, error) {
	resp, err := c.base.RoundTrip(r)
	if err != nil || resp == nil || r.URL.Path != c.path {
		return resp, err
	}
	body, rerr := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if rerr != nil {
		return nil, rerr
	}
	resp.Body = io.NopCloser(bytes.NewReader(body))
	// The delivery now exists and is billed. Everything after this point must
	// still land.
	c.cancel()
	return resp, nil
}

// The window between "the provider charged us" and "we wrote the delivery id"
// must not depend on the caller still being around. A seller who backgrounds
// the app mid-tap, or a sweep tick whose deadline expires, cancels the context
// — and the courier is already bought by then.
func TestDispatch_PersistSurvivesCallerCancellation(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
	router.on("/v1/customers/fake-customer/deliveries",
		jsonHandler(200, `{"id":"paid_del","tracking_url":"https://u/paid","fee":799}`))

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	orig := http.DefaultTransport
	http.DefaultTransport = cancelAfterResponse{
		base: orig, path: "/v1/customers/fake-customer/deliveries", cancel: cancel,
	}
	t.Cleanup(func() { http.DefaultTransport = orig })

	id := seedOrder(t, orderOpts{})
	provider, delID, _, err := e.Dispatch(ctx, baseInput(id))
	if err != nil {
		t.Fatalf("dispatch: %v", err)
	}
	if provider != "uber_direct" || delID != "paid_del" {
		t.Fatalf("got (%q,%q), want (uber_direct,paid_del)", provider, delID)
	}

	st := readOrder(t, id)
	if st.deliveryID != "paid_del" || st.provider != "uber_direct" {
		t.Fatalf("order = %+v, want the paid delivery recorded.\n"+
			"A cancelled caller must not cost us the ONE write that ties a billed courier to its "+
			"order — without it the customer gets no tracking, webhooks never bind, and the "+
			"stale-claim reaper hands the order back to the sweep for a second paid delivery.", st)
	}
	if st.trackingURL == "" {
		t.Error("tracking URL not persisted — the consumer sees no tracking for a delivery we paid for")
	}
}

// The claim release deliberately runs on context.Background() so it lands even
// when the caller's context died mid-dispatch — otherwise a cancelled request
// would leak the sentinel on every failure, not just rare ones.
//
// This pins the pattern the persist step is missing: everything Dispatch does
// AFTER it has taken the claim must survive caller cancellation, and the one
// step that runs after money has actually been spent is the one that doesn't.
func TestDispatch_ClaimReleaseSurvivesCallerCancellation(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Kill the caller's context while the quote is in flight, exactly as a
	// seller backgrounding the app mid-tap or an expiring sweep deadline would.
	router.on("/v1/customers/fake-customer/delivery_quotes", func(w http.ResponseWriter, r *http.Request) {
		cancel()
		jsonHandler(200, `{"id":"q1","fee":799}`)(w, r)
	})

	id := seedOrder(t, orderOpts{})
	if _, _, _, err := e.Dispatch(ctx, baseInput(id)); err == nil {
		t.Fatal("want an error once the caller's context is cancelled")
	}

	st := readOrder(t, id)
	if st.provider != "" {
		t.Errorf("external_provider = %q, want released — a cancelled caller must not leak the "+
			"claim sentinel, or the order is unreachable until the reaper runs", st.provider)
	}
	if st.attempts != 1 {
		t.Errorf("external_dispatch_attempts = %d, want 1 — the attempt bookkeeping must land on "+
			"a detached context too, or the cap silently stops counting", st.attempts)
	}
}
