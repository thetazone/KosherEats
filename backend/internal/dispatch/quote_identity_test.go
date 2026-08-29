package dispatch

// The identity of the DoorDash quote request, across the retries a single order
// makes through the dispatch auction.
//
// DoorDash records a quote under the external_delivery_id it was sent with and
// answers 409 duplicate_delivery_id when that id comes back. The dispatcher can
// only read a 409 as "this provider failed", so an id that is CONSTANT per order
// silently removes DoorDash from every dispatch attempt after the first — and an
// order gets up to maxExternalDispatchAttempts of them, plus one per seller
// escalation and one per cancel-and-re-dispatch. The consumer was charged the
// checkout-time cheapest quote, so a dispatch auction that has lost its cheapest
// bidder is money out of KosherEats' pocket; with DoorDash as the sole provider
// it strands the order entirely.
//
// The checkout-side auction already mints a fresh id per request for exactly
// this reason (delivery_quote.go, quote_identity_test.go in internal/handlers).
// These tests hold the dispatch-side auction to the same rule.
//
// SAFETY: TestMain's sandbox transport redirects every provider host to the
// loopback fake and refuses anything else, so nothing here can reach DoorDash.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"sync"
	"testing"
	"unicode/utf8"
)

// quoteIDRecorder is a fake DoorDash /quotes that behaves the way the real one
// does about external_delivery_id: the first use of an id is quoted, any reuse
// draws the documented 409.
type quoteIDRecorder struct {
	mu   sync.Mutex
	seen map[string]bool
	ids  []string
	fee  int
}

func newQuoteIDRecorder(fee int) *quoteIDRecorder {
	return &quoteIDRecorder{seen: map[string]bool{}, fee: fee}
}

func (q *quoteIDRecorder) handler(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	var req struct {
		ExternalDeliveryID string `json:"external_delivery_id"`
	}
	_ = json.Unmarshal(body, &req)

	q.mu.Lock()
	q.ids = append(q.ids, req.ExternalDeliveryID)
	duplicate := q.seen[req.ExternalDeliveryID]
	q.seen[req.ExternalDeliveryID] = true
	fee := q.fee
	q.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	if duplicate {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"code":"duplicate_delivery_id","message":"The delivery id already exists"}`))
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"external_delivery_id":"q","fee":` + strconv.Itoa(fee) + `}`))
}

func (q *quoteIDRecorder) quoteIDs() []string {
	q.mu.Lock()
	defer q.mu.Unlock()
	out := make([]string, len(q.ids))
	copy(out, q.ids)
	return out
}

// Two dispatch attempts for the SAME order must not send DoorDash the same
// external_delivery_id. Nothing downstream joins on the quote id — the create
// call uses the order UUID — so it only has to be unique.
func TestDispatch_DoorDashQuoteIDIsUniquePerAttempt(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	rec := newQuoteIDRecorder(899)
	router.on(ddQuotePath, rec.handler)
	// Fail the create with a transient 503 so the claim is released and the
	// order stays eligible for a second attempt — the ordinary retry shape.
	router.on(ddCreatePath, jsonHandler(503, `{"message":"unavailable"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})

	for attempt := 1; attempt <= 2; attempt++ {
		if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
			t.Fatalf("attempt %d: expected the seeded create failure", attempt)
		}
	}

	ids := rec.quoteIDs()
	if len(ids) != 2 {
		t.Fatalf("DoorDash was quoted %d time(s) (%v), want 2 — the second attempt never reached the auction", len(ids), ids)
	}
	if ids[0] == ids[1] {
		t.Errorf("both dispatch attempts quoted DoorDash with external_delivery_id %q.\n"+
			"DoorDash answers 409 duplicate_delivery_id to the reuse, which the auction can only read as "+
			"\"provider failed\", so DoorDash is dropped from every retry of this order. Mint a fresh id per "+
			"quote the way delivery_quote.go does.", ids[0])
	}
	if ids[0] == id || ids[1] == id {
		t.Errorf("a quote was sent under the order's own id %q — the create call needs that id to stay unused", id)
	}
}

// The consequence, end to end, with DoorDash as the only provider: on the
// second attempt a healthy DoorDash account must still be able to quote. With a
// constant id the 409 makes the whole auction empty, the order burns another
// attempt, and after the cap it leaves the external path for good.
func TestDispatch_SoleDoorDashStillQuotesOnRetry(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	rec := newQuoteIDRecorder(899)
	router.on(ddQuotePath, rec.handler)

	id := seedOrder(t, orderOpts{deliveryMode: "external"})

	// Attempt 1: create fails transiently, claim released, one attempt counted.
	router.on(ddCreatePath, jsonHandler(503, `{"message":"unavailable"}`))
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("attempt 1: expected the seeded create failure")
	}
	if got := readOrder(t, id).attempts; got != 1 {
		t.Fatalf("attempts after the transient failure = %d, want 1", got)
	}

	// Attempt 2: DoorDash is healthy. The dispatch must succeed.
	router.on(ddCreatePath,
		jsonHandler(200, `{"external_delivery_id":"`+id+`","tracking_url":"https://t/1","fee":899,"delivery_status":"created"}`))
	provider, deliveryID, fee, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("attempt 2 failed against a healthy DoorDash: %v\n"+
			"A reused quote id 409s, emptying the auction — the order is charged for a delivery "+
			"no provider will be asked for again.", err)
	}
	if provider != "doordash_drive" || deliveryID == "" {
		t.Fatalf("dispatch = (%q, %q, %d), want a doordash_drive delivery", provider, deliveryID, fee)
	}
	if st := readOrder(t, id); st.provider != "doordash_drive" || st.providerFee != 899 {
		t.Errorf("order row = %+v, want provider doordash_drive at 899", st)
	}
}

// truncate bounds a provider's error body before it goes into an operator
// email. Provider bodies carry the customer's address and name, which outside
// ASCII are multi-byte — cutting on a byte index splits a rune and puts an
// invalid UTF-8 sequence (rendered as a replacement character, or dropped by a
// strict mail client) in the middle of the identifier a human has to read to
// reconcile a billed delivery.
func TestTruncate_DoesNotSplitAMultibyteRune(t *testing.T) {
	// "Beit Miriam Café" — the é starts at byte 15 and occupies bytes 15-16.
	const body = "Beit Miriam Café, Brooklyn"

	for n := 1; n < len(body); n++ {
		got := truncate(body, n)
		if !utf8.ValidString(got) {
			t.Fatalf("truncate(%q, %d) = %q, which is not valid UTF-8 — a rune was cut in half", body, n, got)
		}
	}
}
