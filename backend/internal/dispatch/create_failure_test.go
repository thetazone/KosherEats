package dispatch

// The gap between "we picked a winner" and "we recorded a delivery".
//
// dispatch_pg_test.go covers the claim CAS, the attempts cap and the quote-round
// bookkeeping; persist_failure_test.go covers what happens after a delivery is
// bought. This file covers the middle: the CreateDelivery call for the auction
// winner failing, and the availability gate that decides whether we enter the
// paid path at all.
//
// A create failure is the most expensive thing to misclassify. Called permanent
// when it was transient, the order is retired from the external path after ONE
// provider hiccup and falls to a courier pool that may be empty. Called
// transient when it was permanent, every sweep tick burns another billed
// provider call on an order no courier will ever accept.
//
// SAFETY: TestMain's sandbox transport refuses any host outside the provider
// set and rewrites the rest to a loopback httptest server, so nothing here can
// reach Uber, DoorDash or Shipday. Credentials are obvious fakes.

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/shipday"
	"github.com/koshereats/backend/internal/uberdirect"
)

const (
	uberQuotePath  = "/v1/customers/fake-customer/delivery_quotes"
	uberCreatePath = "/v1/customers/fake-customer/deliveries"
	ddQuotePath    = "/drive/v2/quotes"
	ddCreatePath   = "/drive/v2/deliveries"
)

// AnyProviderEnabled is the gate callers check before deciding to dispatch
// externally at all — checkout, the sweep and the seller escalation all key on
// it. Reporting true with nothing configured sends orders down the paid path to
// fail; reporting false with a live provider strands deliverable orders in the
// internal pool.
func TestAnyProviderEnabled(t *testing.T) {
	enabledUber := uberdirect.New(uberdirect.Config{
		ClientID: "fake-id", ClientSecret: "fake-secret", CustomerID: "fake-customer",
	})
	enabledDD := doordash.New(doordash.Config{
		DeveloperID: "fake-dev", KeyID: "fake-key",
		SigningKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
	})
	enabledSD := shipday.New(shipday.Config{APIKey: "fake-shipday-key"})

	// Credential-less clients: constructed, but Enabled() is false.
	blankUber := uberdirect.New(uberdirect.Config{})
	blankDD := doordash.New(doordash.Config{})
	blankSD := shipday.New(shipday.Config{})

	tests := []struct {
		name string
		u    *uberdirect.Client
		d    *doordash.Client
		s    *shipday.Client
		want bool
	}{
		{"nothing wired at all", nil, nil, nil, false},
		{"all three clients present but uncredentialed", blankUber, blankDD, blankSD, false},
		{"only uber", enabledUber, nil, nil, true},
		{"only doordash", nil, enabledDD, nil, true},
		{"only shipday", nil, nil, enabledSD, true},
		{"shipday alive behind two dead clients", blankUber, blankDD, enabledSD, true},
		{"all three live", enabledUber, enabledDD, enabledSD, true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			e := New(testPool, tc.u, tc.d, tc.s, nil, nil)
			if got := e.AnyProviderEnabled(); got != tc.want {
				t.Errorf("AnyProviderEnabled() = %t, want %t", got, tc.want)
			}
		})
	}
}

// With no provider configured Dispatch must still release its claim and return
// a real error — not nil, which the escalation handler would report to the
// seller as "already being dispatched". Nothing may be billed, and the order
// must stay dispatchable rather than being retired on a configuration problem
// no retry could have fixed differently.
func TestDispatch_NoProviderEnabledReleasesTheClaim(t *testing.T) {
	e := newDispatcher(t, false, false, false)
	id := seedOrder(t, orderOpts{status: "ready"})

	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("Dispatch returned nil with no provider configured; the escalate handler would answer 409 'already dispatching'")
	}
	if !strings.Contains(err.Error(), "no external provider enabled") {
		t.Errorf("error = %q, want it to name the missing configuration", err.Error())
	}
	if IsPermanent(err) {
		t.Error("a missing provider configuration was classified permanent; it is an ops problem, not bad order data")
	}

	st := readOrder(t, id)
	if st.provider != "" {
		t.Errorf("claim sentinel left standing: external_provider = %q", st.provider)
	}
	if st.attempts != 1 {
		t.Errorf("attempts = %d, want 1 (one transient attempt counted)", st.attempts)
	}
}

// A create failure on the auction WINNER must be classified by the provider's
// status code, exactly like a quote failure: a 4xx validation rejection of this
// order's data is permanent (retrying burns a billed call every sweep), while a
// 5xx or a rate limit is transient and worth one counted retry.
func TestDispatch_CreateFailureClassification(t *testing.T) {
	tests := []struct {
		name          string
		uber          bool // false => doordash
		status        int
		body          string
		wantPermanent bool
		wantAttempts  int
		wantMode      string
		why           string
	}{
		{
			name: "uber 400 on the create is permanent", uber: true,
			status: 400, body: `{"code":"invalid_params","message":"dropoff address is not serviceable"}`,
			wantPermanent: true, wantAttempts: maxExternalDispatchAttempts, wantMode: "platform",
			why: "an unserviceable address cannot be fixed by retrying",
		},
		{
			name: "uber 503 on the create is transient", uber: true,
			status: 503, body: `{"message":"service unavailable"}`,
			wantPermanent: false, wantAttempts: 1, wantMode: "external",
			why: "a provider outage is worth another sweep tick",
		},
		{
			name: "uber 429 on the create is transient", uber: true,
			status: 429, body: `{"message":"rate limited"}`,
			wantPermanent: false, wantAttempts: 1, wantMode: "external",
			why: "a rate limit is the definition of retry-later",
		},
		{
			name: "uber 401 on the create is transient", uber: true,
			status: 401, body: `{"message":"unauthorized"}`,
			wantPermanent: false, wantAttempts: 1, wantMode: "external",
			why: "an expired credential is an account problem, not bad order data — it must not instantly reroute every order",
		},
		{
			name: "doordash 422 on the create is permanent", uber: false,
			status: 422, body: `{"code":"validation_error","message":"pickup_phone_number is invalid"}`,
			wantPermanent: true, wantAttempts: maxExternalDispatchAttempts, wantMode: "platform",
			why: "a rejected phone number is bad order data",
		},
		{
			name: "doordash 500 on the create is transient", uber: false,
			status: 500, body: `{"message":"internal"}`,
			wantPermanent: false, wantAttempts: 1, wantMode: "external",
			why: "a provider 500 is transient",
		},
		{
			name: "doordash 409 on the create is transient", uber: false,
			status: 409, body: `{"code":"duplicate_delivery_id","message":"already exists"}`,
			wantPermanent: false, wantAttempts: 1, wantMode: "external",
			why: "409 means a delivery for this order may ALREADY exist; auto-rerouting on it could double-deliver",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, tc.uber, !tc.uber, false)
			var creates *int32
			if tc.uber {
				router.on(uberQuotePath, jsonHandler(200, `{"id":"q1","fee":799,"duration":25}`))
				creates = router.count(uberCreatePath)
				router.on(uberCreatePath, jsonHandler(tc.status, tc.body))
			} else {
				router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q1","fee":799}`))
				creates = router.count(ddCreatePath)
				router.on(ddCreatePath, jsonHandler(tc.status, tc.body))
			}

			id := seedOrder(t, orderOpts{status: "ready", deliveryMode: "external"})
			provider, deliveryID, _, err := e.Dispatch(context.Background(), baseInput(id))
			if err == nil {
				t.Fatal("Dispatch returned nil after the create failed")
			}
			if provider != "" || deliveryID != "" {
				t.Errorf("failed create returned provider %q / delivery %q; nothing was bought", provider, deliveryID)
			}
			if atomic.LoadInt32(creates) != 1 {
				t.Errorf("%d create call(s), want exactly 1", atomic.LoadInt32(creates))
			}
			if got := IsPermanent(err); got != tc.wantPermanent {
				t.Errorf("IsPermanent = %t, want %t — %s", got, tc.wantPermanent, tc.why)
			}

			st := readOrder(t, id)
			if st.provider != "" {
				t.Errorf("claim sentinel left standing: external_provider = %q", st.provider)
			}
			if st.deliveryID != "" {
				t.Errorf("a failed create recorded delivery id %q", st.deliveryID)
			}
			if st.attempts != tc.wantAttempts {
				t.Errorf("attempts = %d, want %d", st.attempts, tc.wantAttempts)
			}
			if st.deliveryMode != tc.wantMode {
				t.Errorf("delivery_mode = %q, want %q", st.deliveryMode, tc.wantMode)
			}
			if st.status != "ready" {
				t.Errorf("status = %q, want it left at ready", st.status)
			}
		})
	}
}

// A create failure must not cost a second provider call by falling through to
// the runner-up. The quote we won on is consumed, the order is released, and the
// NEXT sweep tick re-runs the whole auction — trying the loser inline would
// double the billed calls per failure and race the claim we are about to drop.
func TestDispatch_CreateFailureDoesNotFallThroughToTheLoser(t *testing.T) {
	e := newDispatcher(t, true, true, false)
	router.on(uberQuotePath, jsonHandler(200, `{"id":"q1","fee":500,"duration":25}`)) // uber wins
	router.on(ddQuotePath, jsonHandler(200, `{"external_delivery_id":"q2","fee":900}`))
	uberCreates := router.count(uberCreatePath)
	ddCreates := router.count(ddCreatePath)
	router.on(uberCreatePath, jsonHandler(500, `{"message":"boom"}`))
	router.on(ddCreatePath, jsonHandler(200, `{"external_delivery_id":"dd1","fee":900,"tracking_url":"https://track"}`))

	id := seedOrder(t, orderOpts{status: "ready", deliveryMode: "external"})
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("Dispatch returned nil after the winning create failed")
	}

	if got := atomic.LoadInt32(uberCreates); got != 1 {
		t.Errorf("%d uber create(s), want 1", got)
	}
	if got := atomic.LoadInt32(ddCreates); got != 0 {
		t.Errorf("%d doordash create(s) — a failed winner must not silently buy from the runner-up", got)
	}
	if st := readOrder(t, id); st.deliveryID != "" || st.provider != "" {
		t.Errorf("order was linked to a delivery after a failed create: %+v", st)
	}
}

// truncate bounds an untrusted provider error before it reaches an operator's
// mailbox: provider bodies are unbounded and routinely echo the customer's
// address and phone back at us.
func TestTruncate(t *testing.T) {
	tests := []struct {
		name string
		in   string
		n    int
		want string
	}{
		{"shorter than the bound is untouched", "boom", 10, "boom"},
		{"exactly the bound is untouched", "0123456789", 10, "0123456789"},
		{"longer is cut and marked", "0123456789x", 10, "0123456789…(truncated)"},
		{"empty stays empty", "", 10, ""},
		{"zero bound truncates everything", "abc", 0, "…(truncated)"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := truncate(tc.in, tc.n); got != tc.want {
				t.Errorf("truncate(%q, %d) = %q, want %q", tc.in, tc.n, got, tc.want)
			}
		})
	}
}
