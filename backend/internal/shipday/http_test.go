package shipday

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// ---- fake-provider plumbing ---------------------------------------------
//
// The package pins apiBase to https://api.shipday.com, so these tests redirect
// the client's transport to a local httptest server instead. Nothing here ever
// leaves loopback: rewriteTransport swaps scheme+host before the request is
// dialed, so a mistake fails the test rather than reaching Shipday.

type rewriteTransport struct{ base *url.URL }

func (rt rewriteTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	r2 := r.Clone(r.Context())
	r2.URL.Scheme = rt.base.Scheme
	r2.URL.Host = rt.base.Host
	r2.Host = ""
	return http.DefaultTransport.RoundTrip(r2)
}

// recordedRequest is what the fake server captured, so tests can assert on the
// exact wire payload (phone normalization, dollars-not-cents, omitted fields).
type recordedRequest struct {
	path   string
	method string
	auth   string
	body   map[string]any
}

// fakeShipday serves the given handler and returns a client wired to it.
// routes maps a URL path to a handler; an unrouted path fails the test.
func fakeShipday(t *testing.T, routes map[string]http.HandlerFunc) (*Client, *[]recordedRequest) {
	t.Helper()
	var seen []recordedRequest
	mux := http.NewServeMux()
	for path, h := range routes {
		p, hh := path, h
		mux.HandleFunc(p, func(w http.ResponseWriter, r *http.Request) {
			raw, _ := io.ReadAll(r.Body)
			rec := recordedRequest{path: r.URL.Path, method: r.Method, auth: r.Header.Get("Authorization")}
			if len(raw) > 0 {
				_ = json.Unmarshal(raw, &rec.body)
			}
			seen = append(seen, rec)
			r.Body = io.NopCloser(strings.NewReader(string(raw)))
			hh(w, r)
		})
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("unexpected call to %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusNotFound)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	base, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatalf("parse test server url: %v", err)
	}
	c := New(Config{APIKey: "test-key-not-a-credential", WebhookToken: "tok"})
	c.http = &http.Client{Transport: rewriteTransport{base: base}}
	return c, &seen
}

func jsonResponse(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

// ---- GetQuote ------------------------------------------------------------

// The availability auction is the first half of the money path: whatever this
// returns is what checkout charges the consumer. Each case pins a payload shape
// Shipday can actually send.
func TestGetQuote_AvailabilityAuction(t *testing.T) {
	cases := []struct {
		name     string
		body     string
		wantErr  bool
		wantSvc  string
		wantFee  int
		wantEst  int
		wantRef  string
		errMatch string
	}{
		{
			name: "cheapest total wins, regulatory fee included",
			body: `[{"id":"est_uber","name":"Uber","fee":6.99,"regulatoryFee":0,"pickupDuration":10,"deliveryDuration":15},
			        {"id":"est_dd","name":"DoorDash","fee":6.49,"regulatoryFee":1.99,"pickupDuration":8,"deliveryDuration":12}]`,
			// Uber total 6.99 beats DoorDash total 8.48 — the regulatory fee flips
			// the winner versus a naive fee-only comparison.
			wantSvc: "Uber", wantFee: 699, wantEst: 25, wantRef: "est_uber",
		},
		{
			name: "regulatory fee is charged, not dropped",
			body: `[{"id":42,"name":"DoorDash","fee":6.49,"regulatoryFee":1.99,"pickupDuration":8,"deliveryDuration":12}]`,
			// 8.48 in float is 8.479999...; must still round to 848 cents.
			wantSvc: "DoorDash", wantFee: 848, wantEst: 20, wantRef: "42",
		},
		{
			name: "errored and zero-fee services are skipped",
			body: `[{"name":"Relay","fee":3.00,"error":true,"errorMessage":"outside coverage"},
			        {"name":"MotoClick","fee":0},
			        {"id":"est_ok","name":"Uber","fee":11.25,"pickupDuration":0,"deliveryDuration":0}]`,
			wantSvc: "Uber", wantFee: 1125, wantEst: 40, wantRef: "est_ok", // durations omitted -> 40m default
		},
		{
			name:     "every service unavailable is an error, never a free courier",
			body:     `[{"name":"Uber","error":true,"errorMessage":"no couriers"},{"name":"DoorDash","fee":0}]`,
			wantErr:  true,
			errMatch: "no third-party service available",
		},
		{
			name:     "empty availability list is an error",
			body:     `[]`,
			wantErr:  true,
			errMatch: "no third-party service available",
		},
		{
			name:     "an object where an array was promised must not quote",
			body:     `{"error":true,"errorMessage":"bad address"}`,
			wantErr:  true,
			errMatch: "parse",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c, seen := fakeShipday(t, map[string]http.HandlerFunc{
				"/on-demand/availability": jsonResponse(200, tc.body),
			})
			q, err := c.GetQuote(context.Background(), "1 Main St, Brooklyn NY", "2 Oak St, Brooklyn NY")
			if tc.wantErr {
				if err == nil {
					t.Fatalf("want error, got quote %+v", q)
				}
				if tc.errMatch != "" && !strings.Contains(err.Error(), tc.errMatch) {
					t.Fatalf("error %q does not contain %q", err, tc.errMatch)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if q.ServiceName != tc.wantSvc {
				t.Errorf("ServiceName = %q, want %q", q.ServiceName, tc.wantSvc)
			}
			if q.FeeCents != tc.wantFee {
				t.Errorf("FeeCents = %d, want %d", q.FeeCents, tc.wantFee)
			}
			if q.EstMinutes != tc.wantEst {
				t.Errorf("EstMinutes = %d, want %d", q.EstMinutes, tc.wantEst)
			}
			if q.EstimateReference != tc.wantRef {
				t.Errorf("EstimateReference = %q, want %q", q.EstimateReference, tc.wantRef)
			}
			// The availability call must carry both addresses and the API key.
			reqs := *seen
			if len(reqs) != 1 {
				t.Fatalf("made %d requests, want 1", len(reqs))
			}
			if reqs[0].auth != "Basic test-key-not-a-credential" {
				t.Errorf("Authorization = %q, want Shipday's Basic <key> scheme", reqs[0].auth)
			}
			if reqs[0].body["pickupAddress"] != "1 Main St, Brooklyn NY" ||
				reqs[0].body["deliveryAddress"] != "2 Oak St, Brooklyn NY" {
				t.Errorf("availability body = %v, want both addresses", reqs[0].body)
			}
		})
	}
}

// A non-2xx availability response must surface as a typed *APIError so
// dispatch's permanent-vs-transient classifier can read the status code. A
// plain error here would make every provider outage look transient (or worse,
// every 400 look retryable) to the attempt cap.
func TestGetQuote_HTTPErrorsCarryStatusCode(t *testing.T) {
	for _, status := range []int{400, 401, 422, 429, 500, 503} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			c, _ := fakeShipday(t, map[string]http.HandlerFunc{
				"/on-demand/availability": jsonResponse(status, `{"message":"nope"}`),
			})
			_, err := c.GetQuote(context.Background(), "a", "b")
			var ae *APIError
			if !errors.As(err, &ae) {
				t.Fatalf("error %v is not an *APIError; dispatch cannot classify it", err)
			}
			if ae.StatusCode != status {
				t.Errorf("StatusCode = %d, want %d", ae.StatusCode, status)
			}
		})
	}
}

// ---- CreateDelivery: the two-call insert+assign flow ---------------------

func TestCreateDelivery_HappyPath(t *testing.T) {
	c, seen := fakeShipday(t, map[string]http.HandlerFunc{
		"/orders":           jsonResponse(200, `{"success":true,"orderId":90210,"response":"ok"}`),
		"/on-demand/assign": jsonResponse(200, `{"orderId":90210,"thirdPartyName":"DoorDash","referenceId":"dd_1","thirdPartyFee":6.49,"shipdayCharge":1.99,"totalBillableAmount":8.48,"trackingUrl":"https://track/1","status":"ASSIGNED"}`),
	})

	del, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{
		OrderID:           "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
		RestaurantName:    "Approved Deli",
		RestaurantAddress: "1 Main St",
		RestaurantPhone:   "(917) 813-0167",
		CustomerName:      "  ",
		CustomerAddress:   "2 Oak St",
		CustomerPhone:     "3156645801",
		SubtotalCents:     2599,
		TipCents:          500,
		ServiceName:       "DoorDash",
		EstimateReference: "est_dd",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// external_delivery_id must be Shipday's numeric order id (webhooks match on
	// it), NOT our UUID.
	if del.ShipdayOrderID != "90210" {
		t.Errorf("ShipdayOrderID = %q, want \"90210\"", del.ShipdayOrderID)
	}
	if del.FeeCents != 848 {
		t.Errorf("FeeCents = %d, want 848 (totalBillableAmount in cents)", del.FeeCents)
	}
	if del.TrackingURL != "https://track/1" || del.Status != "ASSIGNED" {
		t.Errorf("tracking/status = %q/%q", del.TrackingURL, del.Status)
	}

	reqs := *seen
	if len(reqs) != 2 || reqs[0].path != "/orders" || reqs[1].path != "/on-demand/assign" {
		t.Fatalf("call sequence = %+v, want insert then assign", reqs)
	}

	insert := reqs[0].body
	// orderNumber is the webhook correlation key back to our order.
	if insert["orderNumber"] != "6ba7b810-9dad-11d1-80b4-00c04fd430c8" {
		t.Errorf("orderNumber = %v, want our order UUID", insert["orderNumber"])
	}
	// Phones must be E.164 on the wire — the bare-10-digit form is what got
	// order 356a73e9 rejected by a courier provider.
	if insert["customerPhoneNumber"] != "+13156645801" {
		t.Errorf("customerPhoneNumber = %v, want +13156645801", insert["customerPhoneNumber"])
	}
	if insert["restaurantPhoneNumber"] != "+19178130167" {
		t.Errorf("restaurantPhoneNumber = %v, want +19178130167", insert["restaurantPhoneNumber"])
	}
	// A blank consumer name must not block their own delivery.
	if insert["customerName"] != "Customer" {
		t.Errorf("customerName = %v, want the \"Customer\" placeholder", insert["customerName"])
	}
	// Shipday speaks DOLLARS. Sending cents here would 100x every order value.
	if insert["totalOrderCost"] != 25.99 {
		t.Errorf("totalOrderCost = %v, want 25.99 dollars (not cents)", insert["totalOrderCost"])
	}
	if insert["tips"] != 5.0 {
		t.Errorf("tips = %v, want 5 dollars", insert["tips"])
	}
	// deliveryInstruction is omitted when empty rather than sent blank.
	if _, ok := insert["deliveryInstruction"]; ok {
		t.Errorf("deliveryInstruction should be omitted when empty, got %v", insert["deliveryInstruction"])
	}

	assign := reqs[1].body
	if assign["name"] != "DoorDash" || assign["estimateReference"] != "est_dd" {
		t.Errorf("assign body = %v, want the winning service + estimate reference", assign)
	}
	if assign["orderId"] != float64(90210) {
		t.Errorf("assign orderId = %v, want the inserted 90210", assign["orderId"])
	}
	if assign["tip"] != 5.0 {
		t.Errorf("assign tip = %v, want 5 dollars", assign["tip"])
	}
}

// Optional fields must be absent, not blank/zero: Shipday rejects some empty
// strings, and a zero tip sent explicitly has been seen to override the
// insert-time value.
func TestCreateDelivery_OmitsEmptyOptionalFields(t *testing.T) {
	c, seen := fakeShipday(t, map[string]http.HandlerFunc{
		"/orders":           jsonResponse(200, `{"success":true,"orderId":7}`),
		"/on-demand/assign": jsonResponse(200, `{"orderId":7,"totalBillableAmount":5.00}`),
	})
	if _, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{
		OrderID: "o1", CustomerName: "Sam", CustomerAddress: "2 Oak St",
		SubtotalCents: 1000, ServiceName: "Uber",
	}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	insert, assign := (*seen)[0].body, (*seen)[1].body
	for _, k := range []string{"restaurantPhoneNumber", "deliveryInstruction", "tips"} {
		if _, ok := insert[k]; ok {
			t.Errorf("insert included empty %q = %v; want omitted", k, insert[k])
		}
	}
	for _, k := range []string{"estimateReference", "tip"} {
		if _, ok := assign[k]; ok {
			t.Errorf("assign included empty %q = %v; want omitted", k, assign[k])
		}
	}
}

// totalBillableAmount is what Shipday actually bills. When it is missing the
// client must reconstruct it from the parts rather than record a free delivery.
func TestCreateDelivery_FeeFallsBackToParts(t *testing.T) {
	c, _ := fakeShipday(t, map[string]http.HandlerFunc{
		"/orders":           jsonResponse(200, `{"success":true,"orderId":5}`),
		"/on-demand/assign": jsonResponse(200, `{"orderId":5,"thirdPartyFee":7.25,"shipdayCharge":1.50}`),
	})
	del, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{OrderID: "o", ServiceName: "Uber"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if del.FeeCents != 875 {
		t.Errorf("FeeCents = %d, want 875 (7.25 + 1.50)", del.FeeCents)
	}
}

// A 2xx insert carrying success:false engaged no courier. It must not be
// reported as a delivery, and it must not look like an assign failure.
func TestCreateDelivery_InsertRejectedNeverAssigns(t *testing.T) {
	c, seen := fakeShipday(t, map[string]http.HandlerFunc{
		"/orders": jsonResponse(200, `{"success":false,"response":"invalid address"}`),
		// No /on-demand/assign route: reaching it fails the test.
	})
	_, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{OrderID: "o", ServiceName: "Uber"})
	if err == nil {
		t.Fatal("want error on success:false insert")
	}
	var ae *AssignError
	if errors.As(err, &ae) {
		t.Errorf("insert rejection must not be an AssignError (nothing was assigned), got %v", err)
	}
	if len(*seen) != 1 {
		t.Errorf("made %d calls, want 1 — assign must not run after a rejected insert", len(*seen))
	}
}

// The assign half is where a duplicate PAID delivery is born: the two-call flow
// carries no idempotency key, so a retry after an assign that may have executed
// can buy a second courier. OutcomeUnknown is the flag dispatch keys on to
// choose "page a human" over "retry", so its classification is load-bearing.
func TestCreateDelivery_AssignFailureOutcomeClassification(t *testing.T) {
	cases := []struct {
		name        string
		status      int
		body        string
		wantUnknown bool
	}{
		{"clean 4xx rejection engaged nobody", 400, `{"message":"unknown service"}`, false},
		{"422 rejection engaged nobody", 422, `{"message":"unprocessable"}`, false},
		{"5xx may have executed server-side", 500, `{"message":"boom"}`, true},
		{"503 may have executed server-side", 503, ``, true},
		// The most dangerous shape: Shipday says 200 (so the assign ran and a
		// courier is engaged) but we cannot read the result.
		{"2xx with unreadable body definitely executed", 200, `<html>gateway</html>`, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c, _ := fakeShipday(t, map[string]http.HandlerFunc{
				"/orders":           jsonResponse(200, `{"success":true,"orderId":31337}`),
				"/on-demand/assign": jsonResponse(tc.status, tc.body),
			})
			_, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{OrderID: "o", ServiceName: "Uber"})
			var ae *AssignError
			if !errors.As(err, &ae) {
				t.Fatalf("error %v is not an *AssignError", err)
			}
			if ae.OutcomeUnknown != tc.wantUnknown {
				t.Errorf("OutcomeUnknown = %v, want %v", ae.OutcomeUnknown, tc.wantUnknown)
			}
			// The inserted id is the only handle a human has to reconcile against
			// the Shipday dashboard — losing it makes the alert unactionable.
			if ae.InsertedOrderID != 31337 {
				t.Errorf("InsertedOrderID = %d, want 31337", ae.InsertedOrderID)
			}
		})
	}
}

// A transport-level failure (no response at all) is the other unknown outcome.
func TestCreateDelivery_AssignTransportFailureIsUnknown(t *testing.T) {
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/orders", jsonResponse(200, `{"success":true,"orderId":11}`))
	mux.HandleFunc("/on-demand/assign", func(w http.ResponseWriter, r *http.Request) {
		// Hijack and drop the connection so the client sees a transport error.
		conn, _, err := w.(http.Hijacker).Hijack()
		if err == nil {
			_ = conn.Close()
		}
	})
	srv = httptest.NewServer(mux)
	defer srv.Close()
	base, _ := url.Parse(srv.URL)
	c := New(Config{APIKey: "k"})
	c.http = &http.Client{Transport: rewriteTransport{base: base}}

	_, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{OrderID: "o", ServiceName: "Uber"})
	var ae *AssignError
	if !errors.As(err, &ae) {
		t.Fatalf("error %v is not an *AssignError", err)
	}
	if !ae.OutcomeUnknown {
		t.Error("a transport failure must be outcome-unknown: the assign may have executed")
	}
}

// ---- CancelDelivery ------------------------------------------------------

// The Shipday order id goes into the URL path. It comes from our own DB today,
// but path-escaping is what stops a future caller from redirecting the call.
func TestCancelDelivery_PathAndMethod(t *testing.T) {
	var gotPath, gotMethod string
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.EscapedPath(), r.Method
		w.WriteHeader(200)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	base, _ := url.Parse(srv.URL)
	c := New(Config{APIKey: "k"})
	c.http = &http.Client{Transport: rewriteTransport{base: base}}

	if err := c.CancelDelivery(context.Background(), "90210"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPut || gotPath != "/orders/unassign/90210" {
		t.Errorf("cancel called %s %s, want PUT /orders/unassign/90210", gotMethod, gotPath)
	}

	if err := c.CancelDelivery(context.Background(), "../../admin"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if strings.Contains(gotPath, "/admin") && !strings.Contains(gotPath, "%2F") {
		t.Errorf("id was not path-escaped: %s", gotPath)
	}
}

// A non-2xx cancel must surface as a typed error, not be swallowed — a silently
// failed cancel leaves a paid courier running for an order we think is dead.
func TestCancelDelivery_SurfacesAPIError(t *testing.T) {
	c, _ := fakeShipday(t, map[string]http.HandlerFunc{
		"/orders/unassign/1": jsonResponse(409, `{"message":"already picked up"}`),
	})
	err := c.CancelDelivery(context.Background(), "1")
	var ae *APIError
	if !errors.As(err, &ae) || ae.StatusCode != 409 {
		t.Fatalf("want *APIError 409, got %v", err)
	}
}

// A disabled client must never reach the network at all — the whole point of
// this package failing closed rather than stub-succeeding.
func TestDisabledClientMakesNoRequest(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("disabled client reached the network: %s %s", r.Method, r.URL.Path)
	}))
	defer srv.Close()
	base, _ := url.Parse(srv.URL)
	c := New(Config{}) // no API key
	c.http = &http.Client{Transport: rewriteTransport{base: base}}

	if _, err := c.GetQuote(context.Background(), "a", "b"); !errors.Is(err, ErrDisabled) {
		t.Errorf("GetQuote err = %v, want ErrDisabled", err)
	}
	if _, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{}); !errors.Is(err, ErrDisabled) {
		t.Errorf("CreateDelivery err = %v, want ErrDisabled", err)
	}
	if err := c.CancelDelivery(context.Background(), "1"); !errors.Is(err, ErrDisabled) {
		t.Errorf("CancelDelivery err = %v, want ErrDisabled", err)
	}
}

// Bodies are read under a 1MB limit; an oversized error body must not be echoed
// wholesale into logs/alerts or read unbounded into memory.
func TestErrorBodyIsLengthLimited(t *testing.T) {
	c, _ := fakeShipday(t, map[string]http.HandlerFunc{
		"/on-demand/availability": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(500)
			_, _ = io.WriteString(w, strings.Repeat("x", (1<<20)+5000))
		},
	})
	_, err := c.GetQuote(context.Background(), "a", "b")
	var ae *APIError
	if !errors.As(err, &ae) {
		t.Fatalf("want *APIError, got %v", err)
	}
	if len(ae.Body) > 1<<20 {
		t.Errorf("error body is %d bytes, want capped at 1MB", len(ae.Body))
	}
}
