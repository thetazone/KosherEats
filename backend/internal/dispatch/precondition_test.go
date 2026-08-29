package dispatch

// Preconditions and cap arithmetic that the existing suite leaves at the edges:
// the exact boundary of the attempt cap, the phone shapes that are "present"
// but unusable, and the 4xx codes whose classification decides between a retry
// and an immediate reroute.
//
// SAFETY: same sandbox as dispatch_pg_test.go — every outbound request is
// rewritten to the loopback fake server by TestMain's transport, and any
// unrecognized host is refused rather than dialed.

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/koshereats/backend/internal/phone"
)

// A phone that is non-empty but carries no digits is exactly as unusable as an
// absent one: phone.ToE164 (applied at every provider edge — uberdirect,
// doordash and shipday all normalize through it) reduces it to "", and the
// provider answers 400 on the CREATE while the QUOTE succeeds. That is the
// precise failure mode missingRequiredPhone exists to catch before any paid
// call, and a seller typing "N/A" or "call us" into Settings walks straight
// past a TrimSpace-only check.
func TestMissingRequiredPhone_RejectsDigitlessValues(t *testing.T) {
	cases := []struct {
		name       string
		restPhone  string
		custPhone  string
		want       string
		wantReason string
	}{
		{"both real", "+17185551212", "+13156645801", phonePresent, "usable"},
		{"pickup empty", "", "+13156645801", phonePickup, "absent"},
		{"dropoff empty", "+17185551212", "", phoneDropoff, "absent"},
		{"pickup whitespace only", "   ", "+13156645801", phonePickup, "absent"},
		// The gap: these are all non-empty after TrimSpace, so a TrimSpace-only
		// check calls them present — but ToE164 returns "" for every one, so the
		// provider receives an empty phone number.
		{"pickup is a placeholder word", "N/A", "+13156645801", phonePickup, "no digits"},
		{"pickup is punctuation only", "(   ) -  ", "+13156645801", phonePickup, "no digits"},
		{"dropoff is a placeholder word", "+17185551212", "none", phoneDropoff, "no digits"},
		{"dropoff is a lone plus", "+17185551212", "+", phoneDropoff, "no digits"},
		// Formatting variants are genuinely present — they normalize fine.
		{"pickup formatted the human way", "(718) 555-1212", "+13156645801", phonePresent, "usable"},
		{"pickup bare ten digits", "9178130167", "+13156645801", phonePresent, "usable"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := missingRequiredPhone(tc.restPhone, tc.custPhone)
			if got != tc.want {
				t.Errorf("missingRequiredPhone(%q, %q) = %q, want %q (%s)",
					tc.restPhone, tc.custPhone, got, tc.want, tc.wantReason)
			}
			// The invariant that makes the check meaningful: anything reported
			// present must survive normalization to a non-empty E.164 value,
			// because that normalized value is what the provider is sent.
			if got == phonePresent {
				if phone.ToE164(tc.restPhone) == "" {
					t.Errorf("reported present but ToE164(%q) is empty — the provider gets no pickup phone", tc.restPhone)
				}
				if phone.ToE164(tc.custPhone) == "" {
					t.Errorf("reported present but ToE164(%q) is empty — the provider gets no dropoff phone", tc.custPhone)
				}
			}
		})
	}
}

// The end-to-end consequence: a digitless phone must be caught locally, before
// a single provider call, and retire the order the same way an absent one does.
// Without that it costs a real quote AND a real create on every attempt.
func TestDispatch_DigitlessPhoneFailsBeforeAnyProviderCall(t *testing.T) {
	for _, tc := range []struct {
		name              string
		restPhone, custPh string
	}{
		{"pickup phone is a placeholder", "N/A", "+13156645801"},
		{"dropoff phone is a placeholder", "+17185551212", "n/a"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			quotes := router.count("/v1/customers/fake-customer/delivery_quotes")
			creates := router.count("/v1/customers/fake-customer/deliveries")
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries", jsonHandler(200, `{"id":"d","fee":799}`))

			id := seedOrder(t, orderOpts{deliveryMode: "external"})
			in := baseInput(id)
			in.RestPhone, in.CustomerPhone = tc.restPhone, tc.custPh
			_, _, _, err := e.Dispatch(context.Background(), in)

			if err == nil || !IsPermanent(err) {
				t.Fatalf("want a permanent error, got %v", err)
			}
			if q, c := atomic.LoadInt32(quotes), atomic.LoadInt32(creates); q != 0 || c != 0 {
				t.Errorf("burned %d quotes and %d creates on an order no provider can accept", q, c)
			}
			st := readOrder(t, id)
			if st.attempts != maxExternalDispatchAttempts || st.deliveryMode != "platform" {
				t.Errorf("state = %+v, want the order retired to the platform pool", st)
			}
		})
	}
}

// The cap has to be a boundary, not an approximation. One attempt below it the
// order is still dispatchable — retiring a step early throws away a retry the
// order was entitled to, and the sweep is the only thing that would have
// delivered it.
func TestDispatch_AttemptCapBoundary(t *testing.T) {
	cases := []struct {
		name      string
		attempts  int
		wantClaim bool
	}{
		{"fresh order", 0, true},
		{"one attempt below the cap — the last retry", maxExternalDispatchAttempts - 1, true},
		{"exactly at the cap", maxExternalDispatchAttempts, false},
		{"past the cap", maxExternalDispatchAttempts + 1, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries",
				jsonHandler(200, `{"id":"del_cap","tracking_url":"https://t/cap","fee":799}`))

			id := seedOrder(t, orderOpts{attempts: tc.attempts})
			provider, _, _, err := e.Dispatch(context.Background(), baseInput(id))
			if err != nil {
				t.Fatalf("dispatch: %v", err)
			}
			if got := provider != ""; got != tc.wantClaim {
				t.Fatalf("claimed = %v, want %v (attempts %d, cap %d)",
					got, tc.wantClaim, tc.attempts, maxExternalDispatchAttempts)
			}
		})
	}
}

// A successful dispatch on the last allowed attempt must NOT bump the counter —
// only failures cost an attempt. Counting a success would leave a delivered
// order looking exhausted to anything that later reads the column.
func TestDispatch_SuccessDoesNotConsumeAnAttempt(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
	router.on("/v1/customers/fake-customer/deliveries",
		jsonHandler(200, `{"id":"del_ok","tracking_url":"https://t/ok","fee":799}`))

	const startingAttempts = maxExternalDispatchAttempts - 1
	id := seedOrder(t, orderOpts{attempts: startingAttempts})
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err != nil {
		t.Fatalf("dispatch: %v", err)
	}
	if st := readOrder(t, id); st.attempts != startingAttempts {
		t.Errorf("attempts = %d, want %d unchanged — a success is not a failed attempt",
			st.attempts, startingAttempts)
	}
}

// The 4xx codes that must NOT retire an order, exercised through Dispatch's
// real bookkeeping rather than only through permanentStatus. Each has a
// specific reason it is transient, and each reason is a way to lose money or
// double-deliver if it were classified permanent:
//
//   - 401/402/403 are account-level (expired token, billing hold). A credential
//     hiccup must not instantly reroute every order to the internal pool.
//   - 408/429 are timeout and rate limit — the textbook retryables.
//   - 409 is DoorDash's duplicate_delivery_id, meaning a delivery for this order
//     ALREADY EXISTS. Falling back on that could put a second courier on food a
//     first one is already carrying.
func TestDispatch_TransientFourXXDoesNotRetireTheOrder(t *testing.T) {
	for _, code := range []int{401, 402, 403, 408, 409, 429} {
		t.Run(http4xxName(code), func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries",
				jsonHandler(code, `{"code":"duplicate_delivery_id","message":"rejected"}`))

			id := seedOrder(t, orderOpts{deliveryMode: "external"})
			_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
			if err == nil {
				t.Fatal("want an error")
			}
			if IsPermanent(err) {
				t.Fatalf("%d classified permanent — an account/rate/duplicate failure must stay retryable", code)
			}
			st := readOrder(t, id)
			// One attempt counted, not a jump to the cap.
			if st.attempts != 1 {
				t.Errorf("attempts = %d, want 1 (a transient failure costs exactly one retry)", st.attempts)
			}
			// Still an external order: the sweep gets to try again.
			if st.deliveryMode == "platform" {
				t.Error("order was rerouted to the internal pool on a retryable failure")
			}
			// And the claim is released so the retry can win it.
			if st.provider != "" {
				t.Errorf("external_provider = %q, want the claim released", st.provider)
			}
		})
	}
}

// The mirror: a genuine validation 4xx retires the order at once. Retrying an
// unserviceable address or a malformed payload burns a real provider call every
// sweep tick and can never succeed.
func TestDispatch_ValidationFourXXRetiresTheOrderImmediately(t *testing.T) {
	for _, code := range []int{400, 404, 422} {
		t.Run(http4xxName(code), func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries",
				jsonHandler(code, `{"code":"unserviceable","message":"we do not deliver there"}`))

			id := seedOrder(t, orderOpts{deliveryMode: "external"})
			_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
			if err == nil || !IsPermanent(err) {
				t.Fatalf("want a permanent error for %d, got %v", code, err)
			}
			st := readOrder(t, id)
			if st.attempts != maxExternalDispatchAttempts {
				t.Errorf("attempts = %d, want the cap — one validation 4xx is enough", st.attempts)
			}
			if st.deliveryMode != "platform" {
				t.Errorf("delivery_mode = %q, want platform (fallback to the internal pool)", st.deliveryMode)
			}
		})
	}
}

func http4xxName(code int) string {
	switch code {
	case 400:
		return "400_bad_request"
	case 401:
		return "401_unauthorized"
	case 402:
		return "402_payment_required"
	case 403:
		return "403_forbidden"
	case 404:
		return "404_not_found"
	case 408:
		return "408_timeout"
	case 409:
		return "409_duplicate_delivery_id"
	case 422:
		return "422_unprocessable"
	case 429:
		return "429_rate_limited"
	}
	return "status_" + strings.TrimSpace(string(rune('0'+code/100))) + "xx"
}
