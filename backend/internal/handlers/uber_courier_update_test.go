package handlers

// Uber Direct's event.courier_update webhook carries the courier's live
// position. The handler persists it onto the order (external_courier_lat/lng/
// updated_at) so the consumer apps can draw the courier on their own map, and
// fans it out to the order's SSE location stream. These tests hold it to the
// same delivery-id scoping as the lifecycle branches and to the non-UUID
// external_id poison-pill guard.
//
// SAFETY: DB + local HMAC only. No provider client makes an outbound call here.

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func uberCourierUpdateBody(orderID, deliveryID string, lat, lng float64, created string) string {
	return fmt.Sprintf(
		`{"kind":"event.courier_update","delivery_id":%q,"created":%q,`+
			`"location":{"lat":%v,"lng":%v},`+
			`"data":{"external_id":%q,"status":"pickup_complete",`+
			`"courier":{"name":"Ada","rating":"4.9","location":{"lat":%v,"lng":%v}}}}`,
		deliveryID, created, lat, lng, orderID, lat, lng)
}

type externalCourierFix struct {
	lat, lng *float64
	at       *time.Time
}

func readExternalCourierFix(t *testing.T, id string) externalCourierFix {
	t.Helper()
	var f externalCourierFix
	if err := harness.h.db.Pool.QueryRow(t.Context(),
		`SELECT external_courier_lat, external_courier_lng, external_courier_updated_at
		   FROM orders WHERE id = $1`, id).Scan(&f.lat, &f.lng, &f.at); err != nil {
		t.Fatalf("read courier fix: %v", err)
	}
	return f
}

func TestIntegration_UberCourierUpdateAppliesToLiveDelivery(t *testing.T) {
	h := withProviderClients(t)
	ord := seedDispatchedOrder(t, "picked_up", "uber_direct", "uber_delivery_2")

	events, unsub := h.location.Subscribe(ord.id)
	defer unsub()

	created := "2026-09-13T12:00:00Z"
	rec := postUberWebhook(t, h, uberCourierUpdateBody(ord.id, "uber_delivery_2", 40.7109, -74.0119, created))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}

	f := readExternalCourierFix(t, ord.id)
	if f.lat == nil || f.lng == nil || f.at == nil {
		t.Fatalf("courier fix not persisted: %+v", f)
	}
	if *f.lat != 40.7109 || *f.lng != -74.0119 {
		t.Errorf("fix = (%v, %v), want (40.7109, -74.0119)", *f.lat, *f.lng)
	}
	if want, _ := time.Parse(time.RFC3339, created); !f.at.Equal(want) {
		t.Errorf("updated_at = %v, want the event's created %v", f.at, want)
	}

	// The order's lifecycle state must be untouched — courier_update is a
	// position-only event.
	if st := readWebhookOrder(t, ord.id); st.status != "picked_up" || st.deliveryID != "uber_delivery_2" {
		t.Errorf("order state changed by a courier_update: %+v", st)
	}

	// And the fix must have been fanned out to the SSE broker.
	select {
	case e := <-events:
		if e.OrderID != ord.id || e.Lat != 40.7109 || e.Lng != -74.0119 {
			t.Errorf("SSE event = %+v", e)
		}
	case <-time.After(2 * time.Second):
		t.Error("no location event published to the order's SSE stream")
	}

	// An OLDER fix arriving late (webhook delivery is unordered) must not
	// overwrite the newer one.
	rec = postUberWebhook(t, h, uberCourierUpdateBody(ord.id, "uber_delivery_2", 41.0, -75.0, "2026-09-13T11:59:00Z"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if f2 := readExternalCourierFix(t, ord.id); *f2.lat != 40.7109 || *f2.lng != -74.0119 {
		t.Errorf("older fix overwrote newer one: (%v, %v)", *f2.lat, *f2.lng)
	}

	// A newer fix does move the pin.
	rec = postUberWebhook(t, h, uberCourierUpdateBody(ord.id, "uber_delivery_2", 40.72, -74.0, "2026-09-13T12:00:05Z"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if f3 := readExternalCourierFix(t, ord.id); *f3.lat != 40.72 || *f3.lng != -74.0 {
		t.Errorf("newer fix not applied: (%v, %v)", *f3.lat, *f3.lng)
	}
}

// A late courier_update for a superseded delivery must not draw a courier the
// order is no longer out with.
func TestIntegration_UberCourierUpdateIgnoresStaleDelivery(t *testing.T) {
	h := withProviderClients(t)
	ord := seedDispatchedOrder(t, "picked_up", "uber_direct", "uber_delivery_2")

	rec := postUberWebhook(t, h, uberCourierUpdateBody(ord.id, "uber_delivery_1", 40.7109, -74.0119, "2026-09-13T12:00:00Z"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}
	if f := readExternalCourierFix(t, ord.id); f.lat != nil || f.lng != nil || f.at != nil {
		t.Errorf("stale delivery's fix was applied: %+v", f)
	}

	// Without a delivery_id the event cannot prove it belongs to the live
	// delivery — dropped, not applied provider-wide.
	rec = postUberWebhook(t, h, uberCourierUpdateBody(ord.id, "", 40.7109, -74.0119, "2026-09-13T12:00:00Z"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if f := readExternalCourierFix(t, ord.id); f.lat != nil {
		t.Errorf("fix with no delivery_id was applied: %+v", f)
	}

	// Wrong provider: an Uber-authenticated event naming an order out with
	// DoorDash must not touch it.
	dd := seedDispatchedOrder(t, "picked_up", "doordash_drive", "uber_delivery_2")
	rec = postUberWebhook(t, h, uberCourierUpdateBody(dd.id, "uber_delivery_2", 40.7109, -74.0119, "2026-09-13T12:00:00Z"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if f := readExternalCourierFix(t, dd.id); f.lat != nil {
		t.Errorf("cross-provider fix was applied: %+v", f)
	}
}

// A non-UUID external_id would 22P02 against orders.id; it must be ACKed and
// dropped rather than 500 into an Uber retry loop.
func TestIntegration_UberCourierUpdateNonUUIDExternalIDIsAcked(t *testing.T) {
	h := withProviderClients(t)
	for _, ext := range []string{"not-a-uuid", "urn:uuid:not-really", ""} {
		rec := postUberWebhook(t, h, uberCourierUpdateBody(ext, "uber_delivery_2", 40.7109, -74.0119, "2026-09-13T12:00:00Z"))
		if rec.Code != http.StatusOK {
			t.Errorf("external_id %q: status %d, want 200", ext, rec.Code)
		}
	}

	// And a bad signature still fails closed, exactly like delivery_status.
	ord := seedDispatchedOrder(t, "picked_up", "uber_direct", "uber_delivery_2")
	body := uberCourierUpdateBody(ord.id, "uber_delivery_2", 40.7109, -74.0119, "2026-09-13T12:00:00Z")
	req := httptest.NewRequest(http.MethodPost, "/webhooks/uber", bytes.NewReader([]byte(body)))
	req.Header.Set("X-Uber-Signature", "deadbeef")
	rec := httptest.NewRecorder()
	h.UberDirectWebhook(rec, req)
	if rec.Code == http.StatusOK {
		t.Errorf("unsigned courier_update accepted")
	}
	if f := readExternalCourierFix(t, ord.id); f.lat != nil {
		t.Errorf("unsigned courier_update applied a fix: %+v", f)
	}
}
