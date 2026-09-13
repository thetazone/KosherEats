package dispatch

// What a DoorDash 409 duplicate_delivery_id on CREATE has to mean to Dispatch.
//
// DoorDash has no idempotency_key; the external_delivery_id IS the dedupe, and
// the id is stable across every retry of one dispatch cycle. So a create whose
// response was lost (timeout, 5xx after DoorDash committed, a persist that
// never landed before the reaper recycled the claim) is followed by a retry
// that draws 409 — for a delivery that EXISTS, with a Dasher on the way and
// the bill running. Reading it as a plain transient burned an attempt per
// tick under the same id until the cap retired the order to the internal
// pool: a KE courier assigned to food a Dasher was already collecting. The
// seller's escalate path never counts attempts, so every tap was a 502 with
// nothing to show for it.
//
// SAFETY: TestMain's sandbox transport redirects every provider host to the
// loopback fake and refuses anything else, so nothing here can reach DoorDash.

import (
	"context"
	"net/http"
	"strings"
	"testing"
)

const ddDeliveriesPrefix = ddCreatePath + "/"

// A 409 on create must read the existing delivery back and record it, exactly
// as if the create had returned it.
func TestDispatch_DoorDashDuplicateCreateAdoptsTheExistingDelivery(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q","fee":899}`))
	router.on(ddCreatePath, jsonHandler(http.StatusConflict,
		`{"code":"duplicate_delivery_id","message":"The delivery id already exists"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	extID := DoorDashExternalDeliveryID(id, 0)
	gets := router.count(ddDeliveriesPrefix + extID)
	router.on(ddDeliveriesPrefix+extID, jsonHandler(200,
		`{"external_delivery_id":"`+extID+`","tracking_url":"https://t/existing","fee":1099,"delivery_status":"enroute_to_pickup"}`))

	provider, deliveryID, fee, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("Dispatch after a 409 duplicate_delivery_id = %v\n"+
			"the id already names a live DoorDash delivery; the retry must adopt it, not fail", err)
	}
	if provider != "doordash_drive" || deliveryID != extID || fee != 1099 {
		t.Fatalf("Dispatch = (%q, %q, %d), want the existing doordash_drive delivery %q at fee 1099",
			provider, deliveryID, fee, extID)
	}
	if *gets != 1 {
		t.Errorf("GET /deliveries/%s called %d times, want 1", extID, *gets)
	}
	st := readOrder(t, id)
	if st.provider != "doordash_drive" || st.deliveryID != extID ||
		st.trackingURL != "https://t/existing" || st.providerFee != 1099 || st.attempts != 0 {
		t.Errorf("order row = %+v, want the adopted delivery recorded with no failed attempt", st)
	}
	if gen := readGeneration(t, id); gen != 1 {
		t.Errorf("generation after adopting = %d, want 1 (a delivery is now on file)", gen)
	}
}

// If the id names a delivery DoorDash has already CANCELLED, nobody is
// delivering under it and DoorDash will 409 that id forever. The cycle has to
// roll so the retry presents a fresh id — otherwise the order re-draws the
// same 409 until the cap, and a seller re-tapping "Dispatch" can never win.
func TestDispatch_DoorDashDuplicateOfACancelledDeliveryRollsTheCycle(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q","fee":899}`))
	rec := newCreateIDRecorder()
	router.on(ddCreatePath, rec.handler)

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	gen0 := DoorDashExternalDeliveryID(id, 0)
	// Cycle 0's id is already taken at DoorDash by a delivery that was cancelled.
	rec.mu.Lock()
	rec.seen[gen0] = true
	rec.mu.Unlock()
	router.on(ddDeliveriesPrefix+gen0, jsonHandler(200,
		`{"external_delivery_id":"`+gen0+`","fee":899,"delivery_status":"cancelled"}`))

	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("Dispatch adopted a CANCELLED DoorDash delivery — the order would sit welded to a dead delivery")
	}
	st := readOrder(t, id)
	if st.provider != "" || st.deliveryID != "" {
		t.Fatalf("order row = %+v, want no linkage after refusing a cancelled delivery", st)
	}
	if st.attempts != 1 || st.deliveryMode != "external" {
		t.Errorf("order row = %+v, want one transient attempt counted and the external path kept", st)
	}
	if gen := readGeneration(t, id); gen != 1 {
		t.Fatalf("generation after a cancelled duplicate = %d, want 1 — the burned id must not be reused", gen)
	}

	// The retry runs under the fresh id and succeeds.
	provider, deliveryID, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil || provider != "doordash_drive" {
		t.Fatalf("retry after rolling the cycle = (%q, %q, %v), want a doordash_drive delivery", provider, deliveryID, err)
	}
	ids := rec.createIDs()
	if len(ids) != 2 || ids[0] != gen0 || ids[1] == gen0 || !strings.HasPrefix(ids[1], id) {
		t.Errorf("create ids = %v, want %q then a different id for the same order", ids, gen0)
	}
	if st := readOrder(t, id); st.deliveryID != ids[1] || st.attempts != 1 {
		t.Errorf("order row = %+v, want the fresh delivery %q recorded", st, ids[1])
	}
}

// When the read-back itself fails the delivery's state is unknown, so neither
// adopting nor re-creating is safe: count a transient attempt and let the next
// tick ask again under the SAME id.
func TestDispatch_DoorDashDuplicateWithUnreadableDeliveryIsTransient(t *testing.T) {
	e := newDispatcher(t, false, true, false)
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q","fee":899}`))
	router.on(ddCreatePath, jsonHandler(http.StatusConflict, `{"code":"duplicate_delivery_id"}`))
	// No GET handler installed: the fake answers 404 for the read-back.

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("Dispatch succeeded with no delivery to adopt")
	}
	st := readOrder(t, id)
	if st.provider != "" || st.attempts != 1 || st.deliveryMode != "external" {
		t.Errorf("order row = %+v, want claim released, one transient attempt, external path kept", st)
	}
	if gen := readGeneration(t, id); gen != 0 {
		t.Errorf("generation = %d, want 0 — an unknown delivery state must keep the same id for the retry", gen)
	}
}
