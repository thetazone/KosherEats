package handlers

// The IDENTITY a quote is requested under, as opposed to the price it comes
// back with. DoorDash Drive keys a quote on external_delivery_id and answers
// 409 duplicate_delivery_id when one is reused, so an id that is not unique per
// request removes DoorDash from the auction after its first ever use — the
// exact "provider silently drops out at checkout and reappears at dispatch"
// failure the quoteParams doc comment says was already fixed once.
//
// SAFETY: installFakeProviders redirects every provider host to a loopback
// httptest server and refuses anything else, so nothing here dials out.

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"testing"
)

// recordBodies installs a handler that captures every request body sent to a
// path and answers with a fixed response.
func (f *fakeCourierNet) recordBodies(path, response string) *[]map[string]any {
	var mu sync.Mutex
	seen := []map[string]any{}
	f.mu.Lock()
	f.handlers[path] = func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		seen = append(seen, body)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = w.Write([]byte(response))
	}
	f.mu.Unlock()
	return &seen
}

func quoteTwice(t *testing.T, h *Handler) {
	t.Helper()
	for _, dropoff := range []string{"2 Oak St", "3 Elm St"} {
		h.quoteDeliveryFee(context.Background(), quoteParams{
			pickupAddress: "1 Main St", dropoffAddress: dropoff,
			restaurantName: "Deli", restaurantPhone: "+17185551212",
			customerName: "Con Sumer", customerPhone: "+13156645801",
			subtotalCents: 2000, deliveryMode: "external",
		})
	}
}

// Every checkout quote must carry its own external_delivery_id.
//
// delivery_quote.go sends the literal "quote_check" on every request. DoorDash
// records the id on the first quote and rejects every later one with 409
// duplicate_delivery_id, which quoteDeliveryFee treats as "this provider
// failed" and drops from the auction. The consumer then never sees a DoorDash
// price even when DoorDash is cheapest — and if DoorDash is the only configured
// provider, quoteDeliveryFee returns the "flat_rate" sentinel and
// CreatePaymentIntent refuses the whole delivery order with a 503.
//
// dispatch/external.go gets this right (in.OrderID + "_quote"), and even the
// DoorDash stub client mints a unique id per call — checkout is the one caller
// that doesn't.
func TestIntegration_CheckoutQuoteIDIsUniquePerRequest(t *testing.T) {
	net := installFakeProviders(t, false, true, false)
	bodies := net.recordBodies(ddQuotePath, `{"external_delivery_id":"q","fee":875}`)

	quoteTwice(t, quoteHandlerWithProviders(t))

	if len(*bodies) != 2 {
		t.Fatalf("captured %d doordash quote requests, want 2", len(*bodies))
	}
	first, _ := (*bodies)[0]["external_delivery_id"].(string)
	second, _ := (*bodies)[1]["external_delivery_id"].(string)
	if first == "" {
		t.Fatal("no external_delivery_id sent on the checkout quote")
	}
	if first == second {
		t.Errorf("both checkout quotes reused external_delivery_id %q.\n"+
			"DoorDash keys quotes on this id and answers 409 duplicate_delivery_id on reuse, so "+
			"every quote after the first drops DoorDash from the consumer-facing auction while "+
			"dispatch (which mints a per-order id) still quotes it — the consumer is charged a "+
			"price computed without the provider that later delivers. With DoorDash as the only "+
			"provider, checkout refuses delivery outright with a 503.", first)
	}
}

// A 409 on the quote must not be silently absorbed as "no DoorDash today":
// this pins the consequence of the id collision above — the provider vanishes
// from the auction and the consumer pays the next-cheapest price.
func TestIntegration_DuplicateQuoteIDDropsDoorDashFromTheAuction(t *testing.T) {
	net := installFakeProviders(t, true, true, false)
	net.on(uberQuotePath, 200, `{"id":"q","fee":1299,"duration":25}`)
	// What DoorDash actually answers for a reused external_delivery_id.
	net.on(ddQuotePath, 409, `{"code":"duplicate_delivery_id","message":"The delivery id already exists"}`)

	h := quoteHandlerWithProviders(t)
	q := h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St",
		restaurantName: "Deli", restaurantPhone: "+17185551212",
		customerName: "Con Sumer", customerPhone: "+13156645801",
		subtotalCents: 2000, deliveryMode: "external",
	})
	if q.provider != "uber_direct" {
		t.Fatalf("provider = %q, want uber_direct — a 409'd DoorDash drops out", q.provider)
	}
	if q.providerFee != 1299 {
		t.Errorf("providerFee = %d, want 1299: the consumer pays Uber's price because DoorDash's "+
			"quote was rejected for a reason that has nothing to do with the route", q.providerFee)
	}
}

// With DoorDash as the only provider, a duplicate-id 409 turns into a refused
// checkout — the most visible symptom of the constant quote id.
func TestIntegration_SoleProviderQuoteCollisionRefusesDelivery(t *testing.T) {
	net := installFakeProviders(t, false, true, false)
	net.on(ddQuotePath, 409, `{"code":"duplicate_delivery_id","message":"The delivery id already exists"}`)

	h := quoteHandlerWithProviders(t)
	q := h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St", subtotalCents: 2000, deliveryMode: "external",
	})
	if q.provider != "flat_rate" {
		t.Fatalf("provider = %q, want the flat_rate sentinel", q.provider)
	}
	t.Log("flat_rate here means CreatePaymentIntent answers 503 " +
		"\"delivery is temporarily unavailable — please choose pickup\": a healthy DoorDash account " +
		"can block every delivery checkout purely because the quote id never changes")
}
