package shipday

import (
	"context"
	"encoding/json"
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
