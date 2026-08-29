package handlers

// The checkout-time provider auction. This is the price the consumer agrees to
// and the card is charged for; dispatch later re-quotes, so a provider that
// silently drops out HERE and reappears at dispatch charges a price the
// consumer never saw.
//
// SAFETY: no request leaves the machine. installFakeProviders swaps
// http.DefaultTransport for one that rewrites every provider host to a local
// httptest server and REFUSES any host it doesn't recognize, so a real Uber /
// DoorDash / Shipday call fails the test instead of dialing out. All
// credentials are obvious fakes.

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/shipday"
	"github.com/koshereats/backend/internal/uberdirect"
)

var courierHosts = map[string]bool{
	"api.uber.com":         true,
	"auth.uber.com":        true,
	"openapi.doordash.com": true,
	"api.shipday.com":      true,
}

type courierSandboxTransport struct {
	base *url.URL
	real http.RoundTripper
}

func (t courierSandboxTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Host == t.base.Host {
		return t.real.RoundTrip(r)
	}
	if !courierHosts[r.URL.Hostname()] {
		return nil, fmt.Errorf("sandbox: refusing request to unexpected host %q", r.URL.Host)
	}
	r2 := r.Clone(r.Context())
	r2.URL.Scheme, r2.URL.Host, r2.Host = t.base.Scheme, t.base.Host, ""
	return t.real.RoundTrip(r2)
}

// fakeCourierNet is the loopback stand-in for all three provider APIs.
type fakeCourierNet struct {
	mu       sync.Mutex
	handlers map[string]http.HandlerFunc
	hits     map[string]*int32
}

func (f *fakeCourierNet) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	h := f.handlers[r.URL.Path]
	c := f.hits[r.URL.Path]
	f.mu.Unlock()
	if c != nil {
		atomic.AddInt32(c, 1)
	}
	if h == nil {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"unrouted in test"}`))
		return
	}
	h(w, r)
}

func (f *fakeCourierNet) on(path string, status int, body string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.handlers[path] = func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}
}

func (f *fakeCourierNet) counter(path string) *int32 {
	f.mu.Lock()
	defer f.mu.Unlock()
	var c int32
	f.hits[path] = &c
	return &c
}

// installFakeProviders points the harness handler's provider clients at a
// loopback fake and restores everything afterwards.
func installFakeProviders(t *testing.T, uber, dd, sd bool) *fakeCourierNet {
	t.Helper()
	net := &fakeCourierNet{handlers: map[string]http.HandlerFunc{}, hits: map[string]*int32{}}
	srv := httptest.NewServer(net)
	t.Cleanup(srv.Close)
	base, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatalf("parse fake server url: %v", err)
	}

	realTransport := http.DefaultTransport
	http.DefaultTransport = courierSandboxTransport{base: base, real: realTransport}
	t.Cleanup(func() { http.DefaultTransport = realTransport })

	h := harness.h
	origU, origD, origS := h.uber, h.doordash, h.shipday
	t.Cleanup(func() { h.uber, h.doordash, h.shipday = origU, origD, origS })
	h.uber, h.doordash, h.shipday = nil, nil, nil

	if uber {
		h.uber = uberdirect.New(uberdirect.Config{
			ClientID: "fake-id", ClientSecret: "fake-secret", CustomerID: "fake-customer",
		})
		net.on("/oauth/v2/token", 200, `{"access_token":"fake","expires_in":2592000}`)
	}
	if dd {
		h.doordash = doordash.New(doordash.Config{
			DeveloperID: "fake-dev", KeyID: "fake-key",
			SigningKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
		})
	}
	if sd {
		h.shipday = shipday.New(shipday.Config{APIKey: "fake-shipday-key"})
	}
	return net
}

const (
	uberQuotePath    = "/v1/customers/fake-customer/delivery_quotes"
	ddQuotePath      = "/drive/v2/quotes"
	shipdayQuotePath = "/on-demand/availability"
)

// The consumer pays the CHEAPEST provider cost plus the tiered marketplace fee
// — never a floor, never a ceiling, never a provider we didn't actually pick.
func TestIntegration_QuoteDeliveryFeePicksCheapestPlusMarkup(t *testing.T) {
	cases := []struct {
		name         string
		uberFee      int
		ddFee        int
		shipdayFee   float64
		shipdayReg   float64
		subtotal     int
		wantProvider string
		wantProvFee  int
		wantConsumer int
	}{
		{"uber cheapest, small basket", 599, 975, 9.99, 0, 2000, "uber_direct", 599, 599 + 100},
		{"doordash cheapest", 1299, 875, 9.99, 0, 2000, "doordash_drive", 875, 875 + 100},
		{"shipday cheapest", 1299, 975, 6.49, 1.99, 2000, "shipday", 848, 848 + 100},
		// Markup tiers ride on the ITEM subtotal, not the delivery cost.
		{"large basket pays the $2 tier", 599, 975, 9.99, 0, 5000, "uber_direct", 599, 599 + 200},
		{"highest basket pays the $3 tier", 599, 975, 9.99, 0, 9000, "uber_direct", 599, 599 + 300},
		// The regulatory fee is part of what Shipday bills; excluding it would
		// pick Shipday at a price we never charged.
		{"regulatory fee keeps shipday from winning", 900, 1200, 8.50, 1.00, 2000, "uber_direct", 900, 1000},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			net := installFakeProviders(t, true, true, true)
			net.on(uberQuotePath, 200, fmt.Sprintf(`{"id":"q","fee":%d,"duration":25}`, tc.uberFee))
			net.on(ddQuotePath, 200, fmt.Sprintf(`{"external_delivery_id":"q","fee":%d}`, tc.ddFee))
			net.on(shipdayQuotePath, 200, fmt.Sprintf(
				`[{"id":"e","name":"DoorDash","fee":%v,"regulatoryFee":%v,"pickupDuration":10,"deliveryDuration":15}]`,
				tc.shipdayFee, tc.shipdayReg))

			h := quoteHandlerWithProviders(t)
			q := h.quoteDeliveryFee(context.Background(), quoteParams{
				pickupAddress: "1 Main St", dropoffAddress: "2 Oak St",
				restaurantName: "Deli", restaurantPhone: "+17185551212",
				customerName: "Con Sumer", customerPhone: "+13156645801",
				subtotalCents: tc.subtotal, deliveryMode: "external",
			})
			if q.provider != tc.wantProvider {
				t.Errorf("provider = %q, want %q", q.provider, tc.wantProvider)
			}
			if q.providerFee != tc.wantProvFee {
				t.Errorf("providerFee = %d, want %d", q.providerFee, tc.wantProvFee)
			}
			if q.consumerFee != tc.wantConsumer {
				t.Errorf("consumerFee = %d, want %d (cheapest cost + the tiered marketplace fee)",
					q.consumerFee, tc.wantConsumer)
			}
			// What KE keeps must be exactly the markup — no hidden padding.
			if kept := q.consumerFee - q.providerFee; kept != h.deliveryMarkupCents(tc.subtotal) {
				t.Errorf("KE keeps %d, want the markup %d", kept, h.deliveryMarkupCents(tc.subtotal))
			}
		})
	}
}

// quoteHandlerWithProviders gives the harness handler the markup config the
// quote math needs, restoring the original afterwards.
func quoteHandlerWithProviders(t *testing.T) *Handler {
	t.Helper()
	h := harness.h
	orig := h.cfg
	t.Cleanup(func() { h.cfg = orig })
	clone := *orig
	clone.DeliveryMarkupCents = 100
	clone.DeliveryMarkupLargeCents = 200
	clone.DeliveryMarkupHighestCents = 300
	clone.DeliveryLargeOrderCents = 4000
	clone.DeliveryHighestOrderCents = 8000
	h.cfg = &clone
	return h
}

// One provider being down must not take the order down with it — the survivors
// still quote. Only when EVERY provider fails do we fall back to the flat rate,
// which checkout then treats as "refuse the order".
func TestIntegration_QuoteSurvivesPartialProviderOutage(t *testing.T) {
	net := installFakeProviders(t, true, true, true)
	net.on(uberQuotePath, 503, `{"message":"down"}`)
	net.on(ddQuotePath, 500, `{"message":"down"}`)
	net.on(shipdayQuotePath, 200, `[{"id":"e","name":"Uber","fee":7.50,"pickupDuration":10,"deliveryDuration":15}]`)

	h := quoteHandlerWithProviders(t)
	q := h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St", subtotalCents: 2000, deliveryMode: "external",
	})
	if q.provider != "shipday" || q.providerFee != 750 {
		t.Fatalf("got %+v, want the surviving shipday quote at 750", q)
	}
}

// When every provider fails the result must be the "flat_rate" sentinel —
// checkout keys on exactly that string to refuse the charge, so renaming or
// silently substituting a real-looking provider here would re-open the
// charged-but-undeliverable hole.
func TestIntegration_QuoteAllProvidersFailedYieldsFlatRateSentinel(t *testing.T) {
	net := installFakeProviders(t, true, true, true)
	net.on(uberQuotePath, 503, `{"message":"down"}`)
	net.on(ddQuotePath, 500, `{"message":"down"}`)
	net.on(shipdayQuotePath, 500, `{"message":"down"}`)

	h := quoteHandlerWithProviders(t)
	q := h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St", subtotalCents: 2000, deliveryMode: "external",
	})
	if q.provider != "flat_rate" {
		t.Fatalf("provider = %q, want the flat_rate sentinel checkout refuses on", q.provider)
	}
	if q.consumerFee != deliveryFeeFallbackCents {
		t.Errorf("consumerFee = %d, want the fallback %d", q.consumerFee, deliveryFeeFallbackCents)
	}
	if q.providerFee != 0 {
		t.Errorf("providerFee = %d, want 0 — there is no provider", q.providerFee)
	}
}

// Self-delivery must not contact a provider at all: the restaurant drives, and
// a quote call would be a wasted (and rate-limited) API hit per checkout.
func TestIntegration_SelfDeliveryContactsNoProvider(t *testing.T) {
	net := installFakeProviders(t, true, true, true)
	uberHits := net.counter(uberQuotePath)
	ddHits := net.counter(ddQuotePath)
	sdHits := net.counter(shipdayQuotePath)
	net.on(uberQuotePath, 200, `{"id":"q","fee":100}`)
	net.on(ddQuotePath, 200, `{"external_delivery_id":"q","fee":100}`)
	net.on(shipdayQuotePath, 200, `[{"id":"e","name":"Uber","fee":1.00}]`)

	h := quoteHandlerWithProviders(t)
	q := h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St",
		subtotalCents: 2000, deliveryMode: "restaurant", restaurantFee: 399,
	})
	if q.provider != "self_delivery" || q.consumerFee != 499 || q.providerFee != 399 {
		t.Fatalf("got %+v, want self_delivery 499/399", q)
	}
	if atomic.LoadInt32(uberHits)+atomic.LoadInt32(ddHits)+atomic.LoadInt32(sdHits) != 0 {
		t.Error("self-delivery contacted an external provider")
	}
}

// The checkout quote must send the SAME field set dispatch sends. DoorDash
// rejects the shorter payload (400 on pickup_phone_number / "first_name
// contains no letters"), so a thinner checkout payload makes the provider drop
// out here and reappear at dispatch — quoting a price the consumer never saw,
// after the card is charged.
func TestIntegration_CheckoutQuoteSendsTheSamePayloadAsDispatch(t *testing.T) {
	net := installFakeProviders(t, false, true, false)
	var body []byte
	net.mu.Lock()
	net.handlers[ddQuotePath] = func(w http.ResponseWriter, r *http.Request) {
		body = make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"external_delivery_id":"q","fee":900}`))
	}
	net.mu.Unlock()

	h := quoteHandlerWithProviders(t)
	h.quoteDeliveryFee(context.Background(), quoteParams{
		pickupAddress: "1 Main St", dropoffAddress: "2 Oak St",
		restaurantName: "Approved Deli", restaurantPhone: "9178130167",
		customerName: "Con Sumer", customerPhone: "3156645801",
		subtotalCents: 2599, deliveryMode: "external",
	})

	got := string(body)
	for _, want := range []string{
		"pickup_business_name", "pickup_phone_number",
		"dropoff_contact_given_name", "dropoff_phone_number",
	} {
		if !contains(got, want) {
			t.Errorf("checkout quote omitted %q — DoorDash 400s on the short payload here "+
				"but succeeds at dispatch, hiding the provider from the price the consumer agrees to\nbody: %s",
				want, got)
		}
	}
	// Phones must already be E.164 on the wire.
	if !contains(got, "+19178130167") || !contains(got, "+13156645801") {
		t.Errorf("phones were not normalized to E.164 in the checkout quote\nbody: %s", got)
	}
}

func contains(haystack, needle string) bool {
	return len(needle) > 0 && len(haystack) >= len(needle) && indexOf(haystack, needle) >= 0
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
