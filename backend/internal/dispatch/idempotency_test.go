package dispatch

// The Uber Direct create call carries an idempotency_key so a retry after a
// lost response REPLAYS the original delivery instead of buying a second one.
//
// Before this, a transport error / client-side timeout / 5xx on the create was
// (correctly) classified transient and retried — by the sweep, or by the
// seller's next "Dispatch to Uber" tap — with no way for Uber to tell the retry
// apart from a new order. When the first request had in fact reached Uber and
// created the delivery, the retry created and billed a second courier for the
// same food. Shipday's two-call flow had already been hardened against exactly
// this shape (AssignError.OutcomeUnknown); Uber, which offers a first-class
// idempotency_key, had nothing.
//
// The key must be stable across every retry of one dispatch cycle and differ
// once a delivery has been recorded and later cancelled — otherwise a legitimate
// re-dispatch would be answered with the dead delivery. These tests pin both
// halves against Postgres, capturing the key exactly as the fake Uber sees it.
//
// SAFETY: TestMain's sandbox transport rewrites every provider request to a
// loopback httptest server; nothing here can reach Uber.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"testing"
)

// uberKeyRecorder is a fake Uber create endpoint that records the
// idempotency_key of every request and answers with the given status/body.
type uberKeyRecorder struct {
	mu   sync.Mutex
	keys []string
}

func (r *uberKeyRecorder) handler(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, req *http.Request) {
		raw, _ := io.ReadAll(req.Body)
		var payload map[string]any
		_ = json.Unmarshal(raw, &payload)
		key, _ := payload["idempotency_key"].(string)
		r.mu.Lock()
		r.keys = append(r.keys, key)
		r.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}
}

func (r *uberKeyRecorder) seen() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.keys...)
}

func readGeneration(t *testing.T, id string) int {
	t.Helper()
	var gen int
	if err := testPool.QueryRow(context.Background(),
		`SELECT external_dispatch_generation FROM orders WHERE id = $1`, id).Scan(&gen); err != nil {
		t.Fatalf("read generation: %v", err)
	}
	return gen
}

// A create that fails transiently and is retried must present the SAME key to
// Uber both times — that is the whole mechanism: if the first request actually
// went through, the retry is a replay, not a second paid delivery.
func TestDispatch_UberCreateRetryReplaysTheSameIdempotencyKey(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on(uberQuotePath, jsonHandler(200, `{"id":"q1","fee":500,"duration":25}`))
	rec := &uberKeyRecorder{}

	id := seedOrder(t, orderOpts{status: "ready", deliveryMode: "external"})

	// Attempt 1: the create "fails" with a 5xx — the ambiguous outcome.
	router.on(uberCreatePath, rec.handler(503, `{"message":"gateway timeout"}`))
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("Dispatch returned nil after a 503 create")
	}
	if st := readOrder(t, id); st.attempts != 1 || st.deliveryMode != "external" {
		t.Fatalf("after a transient create failure: %+v, want 1 attempt and still external", st)
	}

	// Attempt 2: the sweep retries; Uber (dedupes on the key and) answers with
	// the delivery from attempt 1.
	router.on(uberCreatePath, rec.handler(200,
		`{"id":"del_original","tracking_url":"https://track/1","fee":500,"status":"pending"}`))
	provider, deliveryID, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("retry Dispatch: %v", err)
	}
	if provider != "uber_direct" || deliveryID != "del_original" {
		t.Fatalf("retry recorded %s/%s, want uber_direct/del_original", provider, deliveryID)
	}

	keys := rec.seen()
	if len(keys) != 2 {
		t.Fatalf("uber saw %d create(s), want 2: %v", len(keys), keys)
	}
	want := dispatchIdempotencyKey(id, 0)
	if keys[0] != want {
		t.Errorf("first create sent idempotency_key %q, want %q", keys[0], want)
	}
	if keys[1] != keys[0] {
		t.Errorf("retry sent idempotency_key %q, want the SAME key as the first attempt %q — "+
			"a differing key lets Uber create a second paid delivery", keys[1], keys[0])
	}
}

// Recording a delivery is what advances the cycle: the first dispatch after
// that delivery is cancelled must NOT replay the dead delivery's key.
func TestDispatch_RecordedDeliveryRollsTheIdempotencyKey(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on(uberQuotePath, jsonHandler(200, `{"id":"q1","fee":500,"duration":25}`))
	rec := &uberKeyRecorder{}
	router.on(uberCreatePath, rec.handler(200,
		`{"id":"del_1","tracking_url":"https://track/1","fee":500,"status":"pending"}`))

	id := seedOrder(t, orderOpts{status: "ready", deliveryMode: "external"})
	if gen := readGeneration(t, id); gen != 0 {
		t.Fatalf("fresh order generation = %d, want 0", gen)
	}
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err != nil {
		t.Fatalf("first Dispatch: %v", err)
	}
	if gen := readGeneration(t, id); gen != 1 {
		t.Fatalf("generation after a recorded delivery = %d, want 1", gen)
	}

	// The provider cancels del_1: the webhook clears the linkage exactly like
	// the Uber 'canceled' branch does, re-arming dispatch.
	if _, err := testPool.Exec(context.Background(), `
		UPDATE orders
		   SET external_delivery_id = NULL, external_provider = NULL, external_tracking_url = NULL,
		       updated_at = NOW()
		 WHERE id = $1`, id); err != nil {
		t.Fatalf("simulate cancel: %v", err)
	}
	router.on(uberCreatePath, rec.handler(200,
		`{"id":"del_2","tracking_url":"https://track/2","fee":500,"status":"pending"}`))
	if _, deliveryID, _, err := e.Dispatch(context.Background(), baseInput(id)); err != nil || deliveryID != "del_2" {
		t.Fatalf("re-dispatch after cancel: id=%q err=%v, want del_2", deliveryID, err)
	}

	keys := rec.seen()
	if len(keys) != 2 {
		t.Fatalf("uber saw %d create(s), want 2: %v", len(keys), keys)
	}
	if keys[0] != dispatchIdempotencyKey(id, 0) || keys[1] != dispatchIdempotencyKey(id, 1) {
		t.Errorf("keys across a cancel-and-redispatch = %v, want [%s %s]",
			keys, dispatchIdempotencyKey(id, 0), dispatchIdempotencyKey(id, 1))
	}
	if keys[1] == keys[0] {
		t.Errorf("post-cancel re-dispatch replayed the cancelled delivery's key %q", keys[0])
	}
}

// A seller escalation released without counting an attempt is the most likely
// real-world retry (the handler runs Dispatch under a 30s deadline and tells
// the seller to try again) — it must replay too.
func TestDispatch_EscalationRetapReplaysTheSameIdempotencyKey(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on(uberQuotePath, jsonHandler(200, `{"id":"q1","fee":500,"duration":25}`))
	rec := &uberKeyRecorder{}
	router.on(uberCreatePath, rec.handler(502, `{"message":"bad gateway"}`))

	id := seedOrder(t, orderOpts{status: "ready", deliveryMode: "restaurant"})
	in := baseInput(id)
	in.AllowRestaurantMode = true
	if _, _, _, err := e.Dispatch(context.Background(), in); err == nil {
		t.Fatal("first tap returned nil after a 502 create")
	}
	router.on(uberCreatePath, rec.handler(200,
		`{"id":"del_original","tracking_url":"https://track/1","fee":500,"status":"pending"}`))
	if _, deliveryID, _, err := e.Dispatch(context.Background(), in); err != nil || deliveryID != "del_original" {
		t.Fatalf("second tap: id=%q err=%v", deliveryID, err)
	}
	keys := rec.seen()
	if len(keys) != 2 || keys[0] != keys[1] {
		t.Errorf("escalation taps sent keys %v, want two identical keys", keys)
	}
}
