package handlers

// The /api/v1/delivery-quote endpoint — the price the checkout screen shows
// BEFORE the card is charged. delivery_quote_auction_test.go covers the inner
// auction (quoteDeliveryFee); this file covers the HTTP handler around it,
// which had no coverage at all: authentication, input validation, and the two
// pieces of state the handler pulls from the database rather than the request —
// the restaurant's delivery mode/fee and the CART subtotal that decides the
// marketplace markup tier.
//
// The cart lookup is the one worth guarding: the tier comes from the server's
// view of the basket, never from the client, so a client that lies about its
// basket size cannot buy the cheap tier — and the quoted fee still matches what
// CreatePaymentIntent independently computes at charge time.
//
// SAFETY: installFakeProviders (delivery_quote_auction_test.go) reroutes every
// provider host to a loopback httptest server and refuses any other host, so no
// request leaves the machine.

import (
	"context"
	"encoding/json"
	"net/http"
	"sync/atomic"
	"testing"
)

func postDeliveryQuote(t *testing.T, token string, body map[string]any) (*http.Response, DeliveryQuoteResponse) {
	t.Helper()
	rec := harness.do(http.MethodPost, "/api/v1/delivery-quote/", token, body)
	var out DeliveryQuoteResponse
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode quote: %v (body %s)", err, rec.Body.String())
		}
	}
	return rec.Result(), out
}

// withRestaurantDelivery sets a restaurant's delivery mode + own fee for the
// duration of one test, restoring them afterwards. The fixtures are shared
// across the whole package, so leaking a 'restaurant' mode would silently turn
// other tests' external-dispatch orders into self-delivery ones.
func withRestaurantDelivery(t *testing.T, restID, mode string, fee int) {
	t.Helper()
	pool := harness.h.db.Pool
	var origMode *string
	var origFee int
	if err := pool.QueryRow(t.Context(),
		`SELECT delivery_mode, delivery_fee FROM restaurants WHERE id = $1`, restID,
	).Scan(&origMode, &origFee); err != nil {
		t.Fatalf("read restaurant delivery settings: %v", err)
	}
	// context.Background(), not t.Context(): the test context is already
	// cancelled by the time cleanups run, and a failed restore leaks a
	// 'restaurant' delivery mode onto a fixture the whole package shares.
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(),
			`UPDATE restaurants SET delivery_mode = $1, delivery_fee = $2 WHERE id = $3`,
			origMode, origFee, restID); err != nil {
			t.Errorf("restore restaurant delivery settings: %v", err)
		}
	})
	if _, err := pool.Exec(t.Context(),
		`UPDATE restaurants SET delivery_mode = $1, delivery_fee = $2 WHERE id = $3`,
		mode, fee, restID); err != nil {
		t.Fatalf("set restaurant delivery settings: %v", err)
	}
}

// The quote reveals a restaurant's address-to-address courier pricing and reads
// the caller's own cart, so it is authenticated in production. An anonymous
// caller must get nothing.
func TestIntegration_DeliveryQuoteRequiresAuth(t *testing.T) {
	resp, _ := postDeliveryQuote(t, "", map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status %d, want 401", resp.StatusCode)
	}
}

// Bad input must be refused before any provider call: a quote with no
// destination would otherwise be priced against an empty address, and the
// provider bills us for the round trip either way.
func TestIntegration_DeliveryQuoteValidatesInput(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-validate")

	tests := []struct {
		name     string
		body     map[string]any
		wantCode int
	}{
		{"no restaurant id", map[string]any{"delivery_address": "2 Oak St"}, http.StatusBadRequest},
		{"no delivery address", map[string]any{"restaurant_id": harness.approvedRestID}, http.StatusBadRequest},
		{"neither", map[string]any{}, http.StatusBadRequest},
		{
			"empty delivery address",
			map[string]any{"restaurant_id": harness.approvedRestID, "delivery_address": ""},
			http.StatusBadRequest,
		},
		{
			"unknown restaurant",
			map[string]any{"restaurant_id": "00000000-0000-0000-0000-000000000000", "delivery_address": "2 Oak St"},
			http.StatusNotFound,
		},
		{
			// A non-UUID id must not reach Postgres as one: orders/restaurants
			// ids are uuid columns and a raw string would 22P02 into a 500.
			"non-uuid restaurant id",
			map[string]any{"restaurant_id": "not-a-uuid", "delivery_address": "2 Oak St"},
			http.StatusNotFound,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			net := installFakeProviders(t, true, false, false)
			hits := net.counter(uberQuotePath)
			net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)

			resp, _ := postDeliveryQuote(t, token, tc.body)
			if resp.StatusCode != tc.wantCode {
				t.Errorf("status %d, want %d", resp.StatusCode, tc.wantCode)
			}
			if n := atomic.LoadInt32(hits); n != 0 {
				t.Errorf("a rejected quote request still cost %d provider call(s)", n)
			}
		})
	}
}

// The markup tier is decided by the server's view of the CART, not by anything
// the client sends. If it weren't, a client could quote the $1 tier on a $90
// basket, see a cheap fee, and then be charged the $3 tier at checkout — the
// quote-vs-charge mismatch this endpoint exists to prevent.
func TestIntegration_DeliveryQuoteMarkupTierComesFromTheCart(t *testing.T) {
	tests := []struct {
		name       string
		cartItems  int // seeded item is $15.00
		wantMarkup int
	}{
		{"small basket pays the base tier", 1, 100},   // $15 -> tier 1
		{"large basket pays the middle tier", 3, 200}, // $45 -> over $40
		{"highest basket pays the top tier", 6, 300},  // $90 -> over $80
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			token, _ := harness.registerUser(t, "quote-tier")
			for i := 0; i < tc.cartItems; i++ {
				harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
			}

			net := installFakeProviders(t, true, false, false)
			net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)
			quoteHandlerWithProviders(t)

			resp, got := postDeliveryQuote(t, token, map[string]any{
				"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
			})
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("status %d", resp.StatusCode)
			}
			if got.Provider != "uber_direct" {
				t.Fatalf("provider = %q, want uber_direct", got.Provider)
			}
			if got.ProviderFeeCents != 599 {
				t.Errorf("provider_fee = %d, want the raw 599 quote", got.ProviderFeeCents)
			}
			if got.DeliveryFeeCents != 599+tc.wantMarkup {
				t.Errorf("delivery_fee = %d, want %d (599 courier cost + the %d markup tier)",
					got.DeliveryFeeCents, 599+tc.wantMarkup, tc.wantMarkup)
			}
			if kept := got.DeliveryFeeCents - got.ProviderFeeCents; kept != tc.wantMarkup {
				t.Errorf("KosherEats keeps %d, want exactly the markup %d", kept, tc.wantMarkup)
			}
		})
	}
}

// The quote and the charge are computed by two different endpoints from the
// same inputs. If they disagree the customer is shown one price and charged
// another, so pin them to the same number over the same cart and quote.
func TestIntegration_DeliveryQuoteAgreesWithTheCheckoutCharge(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-vs-charge")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID) // $45 -> middle tier

	net := installFakeProviders(t, true, true, false)
	net.on(uberQuotePath, 200, `{"id":"q","fee":742,"duration":25}`)
	net.on(ddQuotePath, 200, `{"external_delivery_id":"q","fee":980}`)
	quoteHandlerWithProviders(t)

	resp, quoted := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("quote status %d", resp.StatusCode)
	}

	rec, charged := createIntent(t, token, map[string]any{
		"fulfillment_type": "delivery",
		"restaurant_id":    harness.approvedRestID,
		"delivery_address": "2 Oak St",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("intent status %d, body %s", rec.Code, rec.Body.String())
	}

	if quoted.DeliveryFeeCents != charged.DeliveryFee {
		t.Errorf("the checkout screen quoted %d but the card is charged %d",
			quoted.DeliveryFeeCents, charged.DeliveryFee)
	}
	if quoted.Provider != charged.DeliveryMethod {
		t.Errorf("quote says %q delivers, the charge says %q", quoted.Provider, charged.DeliveryMethod)
	}
}

// A self-delivery restaurant drives its own orders: the consumer pays the
// restaurant's configured fee plus the marketplace markup, and no provider is
// contacted (a quote call per checkout would be a wasted, rate-limited API hit).
func TestIntegration_DeliveryQuoteSelfDeliveryUsesTheRestaurantsFee(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-self")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	withRestaurantDelivery(t, harness.approvedRestID, "restaurant", 450)

	net := installFakeProviders(t, true, true, true)
	uberHits, ddHits, sdHits := net.counter(uberQuotePath), net.counter(ddQuotePath), net.counter(shipdayQuotePath)
	net.on(uberQuotePath, 200, `{"id":"q","fee":100,"duration":25}`)
	net.on(ddQuotePath, 200, `{"external_delivery_id":"q","fee":100}`)
	net.on(shipdayQuotePath, 200, `[{"id":"e","name":"Uber","fee":1.00}]`)
	quoteHandlerWithProviders(t)

	resp, got := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got.Provider != "self_delivery" {
		t.Errorf("provider = %q, want self_delivery", got.Provider)
	}
	if got.ProviderFeeCents != 450 {
		t.Errorf("provider_fee = %d, want the restaurant's own 450", got.ProviderFeeCents)
	}
	if got.DeliveryFeeCents != 550 {
		t.Errorf("delivery_fee = %d, want 550 (the restaurant's 450 + the 100 markup)", got.DeliveryFeeCents)
	}
	if got.EstMinutes != selfDeliveryEstMinutes {
		t.Errorf("est_minutes = %d, want %d", got.EstMinutes, selfDeliveryEstMinutes)
	}
	if n := atomic.LoadInt32(uberHits) + atomic.LoadInt32(ddHits) + atomic.LoadInt32(sdHits); n != 0 {
		t.Errorf("self-delivery made %d external provider call(s)", n)
	}
}

// One provider being down must not take the quote down with it.
func TestIntegration_DeliveryQuoteSurvivesAProviderOutage(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-outage")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	net := installFakeProviders(t, true, true, false)
	net.on(uberQuotePath, 503, `{"message":"down"}`)
	net.on(ddQuotePath, 200, `{"external_delivery_id":"q","fee":880}`)
	quoteHandlerWithProviders(t)

	resp, got := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got.Provider != "doordash_drive" || got.ProviderFeeCents != 880 {
		t.Errorf("got %+v, want the surviving doordash quote at 880", got)
	}
	// The flag is the inverse hazard of the all-fail case: raised on a quote a
	// courier CAN honor, it would push the consumer to pickup for nothing.
	if got.DeliveryUnavailable {
		t.Error("delivery_unavailable = true on a quote a provider actually returned")
	}
}

// When every provider fails, this endpoint still answers 200 with the
// "flat_rate" sentinel and the $5.99 fallback — the number the checkout screen
// renders as the delivery fee — while CreatePaymentIntent keys on that same
// sentinel to REFUSE the charge with a 503 ("delivery is temporarily
// unavailable — please choose pickup"). The consumer used to pick delivery, see
// a price, fill in payment, and only then learn delivery was never available.
//
// The 200 and the `provider` value are a published contract that iOS, Android
// and web all read, so they stay as they are; the quote now carries an explicit
// `delivery_unavailable` flag instead, which says up front what checkout is
// about to do. This test pins both halves end-to-end — same cart, same dead
// providers — so the flag and the refusal cannot drift apart.
func TestIntegration_DeliveryQuoteAdvertisesAFlatRateCheckoutWillRefuse(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-allfail")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	net := installFakeProviders(t, true, true, true)
	net.on(uberQuotePath, 503, `{"message":"down"}`)
	net.on(ddQuotePath, 500, `{"message":"down"}`)
	net.on(shipdayQuotePath, 500, `{"message":"down"}`)
	quoteHandlerWithProviders(t)

	resp, got := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("quote status %d, want 200", resp.StatusCode)
	}
	if got.Provider != deliveryProviderUnavailable {
		t.Fatalf("provider = %q, want the flat_rate sentinel", got.Provider)
	}
	if got.DeliveryFeeCents != deliveryFeeFallbackCents {
		t.Errorf("delivery_fee = %d, want the %d fallback", got.DeliveryFeeCents, deliveryFeeFallbackCents)
	}
	// The in-band signal a client can act on without decoding the sentinel: the
	// fee above is not bookable, so offer pickup instead of rendering it.
	if !got.DeliveryUnavailable {
		t.Error("delivery_unavailable = false, but no provider could quote and " +
			"checkout is about to refuse this exact cart")
	}

	// The very next call the checkout screen makes, with the same cart and the
	// same dead providers, refuses.
	rec, _ := createIntent(t, token, map[string]any{
		"fulfillment_type": "delivery",
		"restaurant_id":    harness.approvedRestID,
		"delivery_address": "2 Oak St",
	})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("intent status %d, want 503", rec.Code)
	}
	t.Logf("quote flagged delivery_unavailable while quoting %d cents via %q; "+
		"checkout then refused with 503 %s", got.DeliveryFeeCents, got.Provider, rec.Body.String())
}

// An empty cart must still quote rather than 500 — the checkout screen can call
// this before the cart round-trips — and it prices at the base markup tier.
func TestIntegration_DeliveryQuoteWithAnEmptyCartUsesTheBaseTier(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "quote-emptycart")

	net := installFakeProviders(t, true, false, false)
	net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)
	quoteHandlerWithProviders(t)

	resp, got := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got.DeliveryFeeCents != 599+100 {
		t.Errorf("delivery_fee = %d, want %d (base tier)", got.DeliveryFeeCents, 599+100)
	}
}

// The auction runs per request against the CALLER's cart. Two users with
// different baskets quoting the same route must get their own tier — a shared
// or cached tier would charge one of them the other's price.
func TestIntegration_DeliveryQuoteIsScopedToTheCallersCart(t *testing.T) {
	harness.resetVolatile(t)
	smallToken, _ := harness.registerUser(t, "quote-small")
	bigToken, _ := harness.registerUser(t, "quote-big")
	harness.addToCart(t, smallToken, harness.approvedRestID, harness.menuItemID)
	for i := 0; i < 6; i++ {
		harness.addToCart(t, bigToken, harness.approvedRestID, harness.menuItemID)
	}

	net := installFakeProviders(t, true, false, false)
	net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)
	quoteHandlerWithProviders(t)

	body := map[string]any{"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St"}
	_, small := postDeliveryQuote(t, smallToken, body)
	_, big := postDeliveryQuote(t, bigToken, body)

	if small.DeliveryFeeCents != 699 {
		t.Errorf("small basket delivery_fee = %d, want 699", small.DeliveryFeeCents)
	}
	if big.DeliveryFeeCents != 899 {
		t.Errorf("big basket delivery_fee = %d, want 899", big.DeliveryFeeCents)
	}
	if small.DeliveryFeeCents == big.DeliveryFeeCents {
		t.Error("both callers got the same tier — the quote is not scoped to the caller's cart")
	}
}

// The quote must send the dropoff contact the provider needs. DoorDash rejects
// a payload without a pickup phone or a usable contact name with a 400, which
// quoteDeliveryFee can only read as "this provider failed" — so the provider
// silently drops out of the price the consumer is shown and reappears at
// dispatch. Assert the endpoint fills those fields from the DB, not from the
// request body (which carries neither).
func TestIntegration_DeliveryQuoteSendsTheDropoffContactFromTheDatabase(t *testing.T) {
	harness.resetVolatile(t)
	token, userID := harness.registerUser(t, "quote-contact")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	// users carries a unique index on (phone, role, vertical).
	contactPhone := uniquePhone()
	if _, err := harness.h.db.Pool.Exec(t.Context(),
		`UPDATE users SET first_name = 'Quote', last_name = 'Contact', phone = $2 WHERE id = $1`,
		userID, contactPhone); err != nil {
		t.Fatalf("set user contact: %v", err)
	}

	net := installFakeProviders(t, false, true, false)
	var seen []byte
	net.mu.Lock()
	net.handlers[ddQuotePath] = func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(buf)
		seen = buf
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"external_delivery_id":"q","fee":900}`))
	}
	net.mu.Unlock()
	quoteHandlerWithProviders(t)

	if resp, _ := postDeliveryQuote(t, token, map[string]any{
		"restaurant_id": harness.approvedRestID, "delivery_address": "2 Oak St",
	}); resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}

	var payload map[string]any
	if err := json.Unmarshal(seen, &payload); err != nil {
		t.Fatalf("decode doordash quote payload: %v (raw %q)", err, seen)
	}
	for field, want := range map[string]string{
		"dropoff_contact_given_name": "Quote Contact",
		"dropoff_phone_number":       contactPhone,
		"pickup_business_name":       "Approved Deli",
	} {
		got, _ := payload[field].(string)
		if got != want {
			t.Errorf("quote payload %s = %q, want %q — a provider that 400s here drops out of "+
				"the price the consumer is shown but still quotes at dispatch", field, got, want)
		}
	}
	if _, ok := payload["dropoff_address"]; !ok {
		t.Error("quote payload has no dropoff_address")
	}
	// Unique per request: DoorDash records a quote under its
	// external_delivery_id and 409s a reused one, which the auction can only
	// read as "this provider failed".
	if id, _ := payload["external_delivery_id"].(string); id == "" {
		t.Error("quote payload has no external_delivery_id")
	}
}
