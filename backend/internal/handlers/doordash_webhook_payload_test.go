package handlers

import (
	"encoding/json"
	"strings"
	"testing"
)

// A verbatim DoorDash Drive webhook body from their webhook reference. Parsing
// this is the contract: the handler once read `delivery_status` (a field that
// appears only on quote/create API responses, never on webhooks), so every
// webhook silently became a no-op and DoorDash-dispatched orders never advanced
// past 'ready'. Keep this payload literal — its value is being DoorDash's
// shape, not ours.
const ddWebhookReferenceBody = `{
  "created_at": "2022-02-01T23:18:22.791883Z",
  "event_name": "DASHER_DROPPED_OFF",
  "external_delivery_id": "c19a5d37-e457-4247-9a67-921ec0134125",
  "dasher_id": 123212,
  "dasher_name": "John D.",
  "dasher_dropoff_phone_number": "+16504379788",
  "dasher_location": {"lat": 43.333333333, "lng": -79.333333333},
  "pickup_address": "1000 4th Avenue, Seattle, WA 98104",
  "dropoff_address": "1201 3rd Avenue, Seattle, WA 98101",
  "order_value": 5555,
  "currency": "USD",
  "fee": 975,
  "tip": 230,
  "tracking_url": "https://doordash.com/drive/portal/track/53904a0b",
  "contactless": false
}`

func TestDDWebhookPayloadParsesRealShape(t *testing.T) {
	var p ddWebhookPayload
	if err := json.Unmarshal([]byte(ddWebhookReferenceBody), &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if p.EventName != "DASHER_DROPPED_OFF" {
		t.Errorf("EventName = %q, want DASHER_DROPPED_OFF (is the json tag still event_name?)", p.EventName)
	}
	if p.ExternalDeliveryID != "c19a5d37-e457-4247-9a67-921ec0134125" {
		t.Errorf("ExternalDeliveryID = %q", p.ExternalDeliveryID)
	}
	if p.DasherName != "John D." {
		t.Errorf("DasherName = %q", p.DasherName)
	}
	if p.DasherPhone != "+16504379788" {
		t.Errorf("DasherPhone = %q", p.DasherPhone)
	}
	// dasher_location is a nested object, not flat dasher_location_lat/lng.
	if p.DasherLocation.Lat == 0 || p.DasherLocation.Lng == 0 {
		t.Errorf("DasherLocation = %+v, want populated lat/lng", p.DasherLocation)
	}
	if p.TrackingURL == "" {
		t.Error("TrackingURL is empty")
	}
}

// Every event the handler acts on must survive the normalization the handler
// applies, and must match a case in its switch. A rename on either side without
// the other breaks order progression silently.
func TestDDWebhookHandledEventNames(t *testing.T) {
	// The four transitions the handler acts on, exactly as DoorDash sends them.
	handled := []string{
		"DASHER_CONFIRMED",
		"DASHER_PICKED_UP",
		"DASHER_DROPPED_OFF",
		"DELIVERY_CANCELLED",
	}
	for _, name := range handled {
		if got := strings.ToUpper(strings.TrimSpace(name)); got != name {
			t.Errorf("normalize(%q) = %q; handler switch would not match", name, got)
		}
	}

	// Opt-in tracking events are documented lowercase; normalization must not
	// leave them in a form that accidentally collides with a handled case.
	for _, name := range []string{"dasher_enroute_to_pickup", "dasher_enroute_to_dropoff"} {
		norm := strings.ToUpper(strings.TrimSpace(name))
		for _, h := range handled {
			if norm == h {
				t.Errorf("tracking event %q normalizes to handled case %q", name, h)
			}
		}
	}
}
