package shipday

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
)

func TestEnabled(t *testing.T) {
	if New(Config{}).Enabled() {
		t.Error("client with no API key must be disabled")
	}
	if !New(Config{APIKey: "k"}).Enabled() {
		t.Error("client with API key must be enabled")
	}
}

// A disabled client must fail closed — the uberdirect/doordash stub-success
// pattern was flagged as a latent fail-open, so this package returns errors.
func TestDisabledClientErrors(t *testing.T) {
	c := New(Config{})
	if _, err := c.GetQuote(context.Background(), "a", "b"); err == nil {
		t.Error("GetQuote on disabled client must error, not stub-succeed")
	}
	if _, err := c.CreateDelivery(context.Background(), CreateDeliveryRequest{}); err == nil {
		t.Error("CreateDelivery on disabled client must error, not stub-succeed")
	}
	if err := c.CancelDelivery(context.Background(), "1"); err == nil {
		t.Error("CancelDelivery on disabled client must error")
	}
}

func TestVerifyWebhook(t *testing.T) {
	c := New(Config{APIKey: "k", WebhookToken: "tok123"})
	if !c.VerifyWebhook("tok123") {
		t.Error("matching token must verify")
	}
	if !c.VerifyWebhook(" tok123 ") {
		t.Error("token with surrounding whitespace must verify")
	}
	if c.VerifyWebhook("wrong") {
		t.Error("wrong token must not verify")
	}
	if c.VerifyWebhook("") {
		t.Error("empty token must not verify")
	}
	// Fail closed: no configured token rejects everything, including empty==empty.
	unconfigured := New(Config{APIKey: "k"})
	if unconfigured.VerifyWebhook("") {
		t.Error("unconfigured webhook token must reject all requests")
	}
}

func TestCheapestService(t *testing.T) {
	svcs := []availabilityService{
		{Name: "Uber", Fee: 12.99, Error: false},
		{Name: "DoorDash", Fee: 6.49, RegulatoryFee: 1.99, Error: false},
		{Name: "Relay", Fee: 4.00, Error: true, ErrorMessage: "outside coverage"},
		{Name: "MotoClick", Fee: 0, Error: false}, // zero fee = malformed, skip
	}
	best, err := cheapestService(svcs)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// DoorDash wins: 6.49+1.99=8.48 < Uber 12.99. Relay is errored, MotoClick zero-fee.
	if best.Name != "DoorDash" {
		t.Errorf("best = %s, want DoorDash", best.Name)
	}
}

func TestCheapestServiceAllUnavailable(t *testing.T) {
	svcs := []availabilityService{
		{Name: "Uber", Error: true, ErrorMessage: "no couriers"},
		{Name: "DoorDash", Fee: 0, Error: false},
	}
	if _, err := cheapestService(svcs); err == nil {
		t.Error("all-unavailable must return an error, not a zero-fee quote")
	}
}

// Shipday speaks dollars; the platform speaks cents. The regulatory fee is
// part of what Shipday bills, so it must be inside the quoted fee.
func TestMoneyConversion(t *testing.T) {
	if got := dollarsToCents(6.49); got != 649 {
		t.Errorf("dollarsToCents(6.49) = %d, want 649", got)
	}
	// Classic float trap: 8.48 stored as 8.4799999... must still round to 848.
	if got := dollarsToCents(6.49 + 1.99); got != 848 {
		t.Errorf("dollarsToCents(6.49+1.99) = %d, want 848", got)
	}
	if got := centsToDollars(1399); got != 13.99 {
		t.Errorf("centsToDollars(1399) = %v, want 13.99", got)
	}
}

func TestRawToString(t *testing.T) {
	cases := []struct{ in, want string }{
		{`"est_abc123"`, "est_abc123"},
		{`42`, "42"},
		{`null`, ""},
		{``, ""},
	}
	for _, c := range cases {
		if got := rawToString(json.RawMessage(c.in)); got != c.want {
			t.Errorf("rawToString(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestCustomerNamePlaceholder(t *testing.T) {
	if got := customerName("  "); got != "Customer" {
		t.Errorf("blank name = %q, want Customer", got)
	}
	if got := customerName("Sam Mamiye"); got != "Sam Mamiye" {
		t.Errorf("real name = %q, want unchanged", got)
	}
}

// Assign failures after a successful insert are where duplicate paid
// deliveries are born (review finding): a clean 4xx means Shipday rejected the
// assign and no courier was engaged; everything else must be treated as
// outcome-unknown so dispatch never blindly retries.
func TestAssignOutcomeUnknown(t *testing.T) {
	if assignOutcomeUnknown(&APIError{StatusCode: 400, Body: "bad service"}) {
		t.Error("clean 4xx = known non-execution, must not be unknown")
	}
	if assignOutcomeUnknown(&APIError{StatusCode: 422}) {
		t.Error("422 = known non-execution")
	}
	if !assignOutcomeUnknown(&APIError{StatusCode: 500}) {
		t.Error("5xx may have executed server-side, must be unknown")
	}
	if !assignOutcomeUnknown(errors.New("net/http: request canceled (timeout)")) {
		t.Error("transport error must be unknown")
	}
}

// AssignError must keep the underlying *APIError reachable through errors.As —
// dispatch's isPermanentProviderError classifies through the wrap chain.
func TestAssignErrorUnwrap(t *testing.T) {
	wrapped := &AssignError{InsertedOrderID: 42, OutcomeUnknown: false,
		Err: &APIError{StatusCode: 422, Body: "no"}}
	var api *APIError
	if !errors.As(wrapped, &api) || api.StatusCode != 422 {
		t.Error("errors.As must reach the wrapped *APIError through AssignError")
	}
}

// A Fly secret pasted with a trailing newline must not 401 every webhook.
func TestVerifyWebhookTrimsConfiguredToken(t *testing.T) {
	c := New(Config{APIKey: "k", WebhookToken: "tok123\n"})
	if !c.VerifyWebhook("tok123") {
		t.Error("configured token with trailing newline must still verify")
	}
	whitespaceOnly := New(Config{APIKey: "k", WebhookToken: "  \n"})
	if whitespaceOnly.VerifyWebhook("") {
		t.Error("whitespace-only configured token must fail closed")
	}
}
