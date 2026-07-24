package dispatch

import (
	"errors"
	"fmt"
	"testing"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/uberdirect"
)

// permanentStatus decides whether a provider HTTP status is a permanent
// rejection of THIS order's data (retry can never succeed → fall back) vs a
// transient/account-level failure (retry within the attempt cap). The
// exclusions are load-bearing — an adversarial review caught earlier revs
// mis-classifying 401/402/403 (account-level) and 409 (DoorDash
// duplicate_delivery_id, which could double-deliver on a reroute).
func TestPermanentStatus(t *testing.T) {
	cases := []struct {
		code int
		want bool
	}{
		// permanent: order-data validation failures
		{400, true}, // invalid_params (missing pickup phone, bad address)
		{404, true}, // unserviceable / not found
		{422, true}, // unprocessable entity
		// NOT permanent: transient
		{408, false}, // request timeout
		{429, false}, // rate limited
		// NOT permanent: account-level, not order data
		{401, false}, // auth
		{402, false}, // payment required / billing
		{403, false}, // forbidden
		// NOT permanent: conflict — a paid delivery may already exist
		{409, false},
		// NOT permanent: 5xx and success
		{500, false},
		{502, false},
		{503, false},
		{200, false},
		{0, false},
	}
	for _, c := range cases {
		if got := permanentStatus(c.code); got != c.want {
			t.Errorf("permanentStatus(%d) = %v, want %v", c.code, got, c.want)
		}
	}
}

func TestIsPermanentProviderError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"plain error (network/opaque)", errors.New("connection refused"), false},
		{"uber 400", &uberdirect.APIError{StatusCode: 400, Body: "pickup_phone_number required"}, true},
		{"uber 429 transient", &uberdirect.APIError{StatusCode: 429}, false},
		{"uber 401 account", &uberdirect.APIError{StatusCode: 401}, false},
		{"uber 503 transient", &uberdirect.APIError{StatusCode: 503}, false},
		{"doordash 400", &doordash.APIError{StatusCode: 400}, true},
		{"doordash 409 conflict", &doordash.APIError{StatusCode: 409}, false},
		// The real error path wraps with %w through GetQuote/CreateDelivery —
		// errors.As must still reach the typed APIError.
		{"uber 400 wrapped once", fmt.Errorf("uber create delivery: %w", &uberdirect.APIError{StatusCode: 400}), true},
		{"uber 400 wrapped twice", fmt.Errorf("all providers failed: %w",
			fmt.Errorf("uber quote: %w", &uberdirect.APIError{StatusCode: 400})), true},
		{"uber 500 wrapped", fmt.Errorf("uber create delivery: %w", &uberdirect.APIError{StatusCode: 500}), false},
	}
	for _, c := range cases {
		if got := isPermanentProviderError(c.err); got != c.want {
			t.Errorf("isPermanentProviderError(%s) = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestIsPermanent(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"transient plain", errors.New("timeout"), false},
		{"ErrNotDispatchable (missing phone, pre-check)", ErrNotDispatchable, true},
		{"ErrNotDispatchable wrapped", fmt.Errorf("%w: restaurant %q has no phone", ErrNotDispatchable, "Demo"), true},
		{"permanent provider error", &uberdirect.APIError{StatusCode: 400}, true},
		{"transient provider error", &uberdirect.APIError{StatusCode: 503}, false},
	}
	for _, c := range cases {
		if got := IsPermanent(c.err); got != c.want {
			t.Errorf("IsPermanent(%s) = %v, want %v", c.name, got, c.want)
		}
	}
}

// isPermanentProviderError on an errors.Join is UNSAFE for mixed batches — it
// only sees the first errors.As match, not the whole set. This is exactly why
// the all-quotes-failed path in Dispatch iterates per-error and treats the
// batch as permanent only when EVERY error is permanent. This test locks in
// that unsafe behavior so nobody "simplifies" the loop into a single
// isPermanentProviderError(errors.Join(...)) call.
func TestIsPermanentProviderError_JoinIsUnsafeForMixedBatches(t *testing.T) {
	// Same-type join: errors.As finds the FIRST *uberdirect.APIError in tree
	// order (503, transient) and stops — so a permanent 400 later in the batch
	// is invisible and the join is (correctly, for this case) classified
	// transient. A naive join-based caller would then RETIRE an order whose
	// batch actually contained a still-winnable transient... or miss a
	// permanent one, depending on order. Either way it's order-dependent and
	// wrong, which is the point.
	transientFirst := errors.Join(
		&uberdirect.APIError{StatusCode: 503}, // transient (first match)
		&uberdirect.APIError{StatusCode: 400}, // permanent (never reached)
	)
	if isPermanentProviderError(transientFirst) {
		t.Errorf("same-type join [503,400]: errors.As should stop at the transient 503, got permanent")
	}

	permanentFirst := errors.Join(
		&uberdirect.APIError{StatusCode: 400}, // permanent (first match)
		&uberdirect.APIError{StatusCode: 503}, // transient (never reached)
	)
	if !isPermanentProviderError(permanentFirst) {
		t.Errorf("same-type join [400,503]: errors.As should stop at the permanent 400, got transient")
	}
	// The two joins differ only in order yet classify oppositely → join-based
	// classification is order-dependent, so Dispatch must (and does) iterate.
}
