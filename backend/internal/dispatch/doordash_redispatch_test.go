package dispatch

// The identity of the DoorDash CREATE request across a cancel-and-re-dispatch.
//
// DoorDash never lets an external_delivery_id be reused: a cancelled delivery
// keeps its id, and a second create under it draws 409 duplicate_delivery_id.
// The create used to send the bare order id, so every re-dispatch after a
// DoorDash cancel (the DELIVERY_CANCELLED webhook clears the linkage and the
// sweep re-arms) was a guaranteed 409 — read as transient, so the order burned
// an attempt per tick until the cap retired it to the internal pool. The id
// now carries the dispatch generation, which advances only when a delivery is
// recorded, so the post-cancel cycle presents DoorDash with a fresh id while
// retries inside one cycle keep the same one.
//
// SAFETY: TestMain's sandbox transport redirects every provider host to the
// loopback fake and refuses anything else, so nothing here can reach DoorDash.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// createIDRecorder is a fake DoorDash /deliveries with the real one's rule
// about external_delivery_id: the first create under an id succeeds, any
// later create under the same id — even after that delivery was cancelled —
// draws the documented 409.
type createIDRecorder struct {
	mu   sync.Mutex
	seen map[string]bool
	ids  []string
}

func newCreateIDRecorder() *createIDRecorder {
	return &createIDRecorder{seen: map[string]bool{}}
}

func (c *createIDRecorder) handler(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	var req struct {
		ExternalDeliveryID string `json:"external_delivery_id"`
	}
	_ = json.Unmarshal(body, &req)

	c.mu.Lock()
	c.ids = append(c.ids, req.ExternalDeliveryID)
	duplicate := c.seen[req.ExternalDeliveryID]
	c.seen[req.ExternalDeliveryID] = true
	c.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	if duplicate {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"code":"duplicate_delivery_id","message":"The delivery id already exists"}`))
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"external_delivery_id":"` + req.ExternalDeliveryID +
		`","tracking_url":"https://t/1","fee":899,"delivery_status":"created"}`))
}

func (c *createIDRecorder) createIDs() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]string, len(c.ids))
	copy(out, c.ids)
	return out
}

// cancelDoorDashDelivery applies exactly what handlers.DoorDashWebhook's
// DELIVERY_CANCELLED branch does to the row (clear the linkage, re-arm), so
// the next Dispatch is the sweep's post-cancel re-dispatch.
func cancelDoorDashDelivery(t *testing.T, id string) {
	t.Helper()
	if _, err := testPool.Exec(context.Background(), `
		UPDATE orders
		   SET external_delivery_id = NULL, external_provider = NULL,
		       external_tracking_url = NULL, updated_at = NOW()
		 WHERE id = $1 AND external_provider = 'doordash_drive'`, id); err != nil {
		t.Fatalf("cancel doordash delivery: %v", err)
	}
}

func TestDispatch_DoorDashRedispatchAfterCancelUsesAFreshExternalID(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q","fee":899}`))
	rec := newCreateIDRecorder()
	router.on(ddCreatePath, rec.handler)

	id := seedOrder(t, orderOpts{deliveryMode: "external"})

	// Cycle 1: a delivery is bought and recorded.
	provider, deliveryID, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil || provider != "doordash_drive" {
		t.Fatalf("cycle 1 = (%q, %q, %v), want a doordash_drive delivery", provider, deliveryID, err)
	}
	if ParseDoorDashExternalDeliveryID(deliveryID) != id {
		t.Fatalf("delivery id %q does not resolve back to order %s — the webhook could never bind it", deliveryID, id)
	}
	if st := readOrder(t, id); st.deliveryID != deliveryID {
		t.Fatalf("order row records delivery %q, want %q", st.deliveryID, deliveryID)
	}

	// DoorDash cancels it; the webhook clears the linkage and the order re-arms.
	cancelDoorDashDelivery(t, id)

	// Cycle 2: the re-dispatch. With the bare order id this was the 409.
	provider, deliveryID2, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("re-dispatch after a DoorDash cancel failed: %v\n"+
			"DoorDash refuses to reuse an external_delivery_id even after cancelling the delivery under it, "+
			"so a per-order constant makes every post-cancel re-dispatch a 409 — the order burns its attempt "+
			"budget and leaves the external path for good.", err)
	}
	if provider != "doordash_drive" {
		t.Fatalf("re-dispatch provider = %q, want doordash_drive", provider)
	}
	ids := rec.createIDs()
	if len(ids) != 2 || ids[0] == ids[1] {
		t.Fatalf("DoorDash create ids across the two cycles = %v, want two distinct ids", ids)
	}
	if deliveryID2 == deliveryID {
		t.Errorf("both cycles recorded delivery id %q — a late webhook for the cancelled delivery could not be told apart", deliveryID)
	}
	if ParseDoorDashExternalDeliveryID(deliveryID2) != id {
		t.Errorf("delivery id %q does not resolve back to order %s", deliveryID2, id)
	}
	if st := readOrder(t, id); st.provider != "doordash_drive" || st.deliveryID != deliveryID2 || st.attempts != 0 {
		t.Errorf("order row = %+v, want doordash_drive at %q with no failed attempts", st, deliveryID2)
	}
}

// Retries INSIDE one cycle (create failed before any delivery was recorded)
// must keep the same id — that is what keeps a create that succeeded at
// DoorDash but was never recorded here from being bought twice: the retry
// draws the 409 instead of a second delivery.
func TestDispatch_DoorDashExternalIDIsStableWithinACycle(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q","fee":899}`))
	rec := newCreateIDRecorder()
	// Every create fails transiently, so no delivery is ever recorded.
	router.on(ddCreatePath, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		var req struct {
			ExternalDeliveryID string `json:"external_delivery_id"`
		}
		_ = json.Unmarshal(body, &req)
		rec.mu.Lock()
		rec.ids = append(rec.ids, req.ExternalDeliveryID)
		rec.mu.Unlock()
		jsonHandler(503, `{"message":"unavailable"}`)(w, r)
	})

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	for attempt := 1; attempt <= 2; attempt++ {
		if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
			t.Fatalf("attempt %d: expected the seeded create failure", attempt)
		}
	}
	ids := rec.createIDs()
	if len(ids) != 2 || ids[0] != ids[1] {
		t.Fatalf("create ids across two attempts of one cycle = %v, want the same id twice", ids)
	}
	if !strings.HasPrefix(ids[0], id) {
		t.Errorf("create id %q does not start with the order id %s", ids[0], id)
	}
}

func TestParseDoorDashExternalDeliveryID(t *testing.T) {
	const order = "c19a5d37-e457-4247-9a67-921ec0134125"
	cases := map[string]string{
		DoorDashExternalDeliveryID(order, 0):  order,
		DoorDashExternalDeliveryID(order, 17): order,
		order:                                 order, // pre-suffix rows
		"sim-1234":                            "sim-1234",
		"":                                    "",
	}
	for in, want := range cases {
		if got := ParseDoorDashExternalDeliveryID(in); got != want {
			t.Errorf("ParseDoorDashExternalDeliveryID(%q) = %q, want %q", in, got, want)
		}
	}
}
