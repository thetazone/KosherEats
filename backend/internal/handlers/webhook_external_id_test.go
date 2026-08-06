package handlers

import (
	"testing"

	"github.com/google/uuid"
)

// Both provider webhooks guard their external id with uuid.Parse and then query
// on the CANONICAL form. The reason is a mismatch between two parsers:
// uuid.Parse accepts spellings that Postgres's uuid input does NOT (notably the
// urn:uuid: prefix). Passing a raw provider string straight into `WHERE id = $1`
// therefore fails with SQLSTATE 22P02 rather than matching zero rows, the
// handler answers 500, and the provider retries that forever.
//
// This test pins the contract the guards rely on: every form uuid.Parse accepts
// must normalize to a plain hyphenated UUID, which Postgres always accepts.
func TestWebhookExternalIDNormalization(t *testing.T) {
	const canonical = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

	accepted := []struct {
		name string
		in   string
	}{
		{"canonical hyphenated", canonical},
		{"unhyphenated", "6ba7b8109dad11d180b400c04fd430c8"},
		{"brace wrapped", "{6ba7b810-9dad-11d1-80b4-00c04fd430c8}"},
		// The dangerous one: accepted by uuid.Parse, rejected by Postgres. It must
		// be normalized before it reaches a query.
		{"urn prefixed", "urn:uuid:6ba7b810-9dad-11d1-80b4-00c04fd430c8"},
	}
	for _, tc := range accepted {
		t.Run("accept/"+tc.name, func(t *testing.T) {
			u, err := uuid.Parse(tc.in)
			if err != nil {
				t.Fatalf("uuid.Parse(%q) = %v, want accepted", tc.in, err)
			}
			if got := u.String(); got != canonical {
				t.Errorf("normalized %q to %q, want %q", tc.in, got, canonical)
			}
		})
	}

	// Ids no provider delivery of ours can legitimately carry. Each must be
	// rejected so the handler ACKs 200 instead of 500-looping.
	rejected := []struct {
		name string
		in   string
	}{
		// Real value observed 500-looping against prod from the DoorDash
		// Simulator before the guard existed.
		{"simulator style id", "ke_whtest_20260729213222"},
		{"empty", ""},
		{"arbitrary text", "not-a-uuid"},
		{"truncated uuid", "6ba7b810-9dad-11d1-80b4"},
		{"uuid with trailing junk", canonical + "x"},
	}
	for _, tc := range rejected {
		t.Run("reject/"+tc.name, func(t *testing.T) {
			if _, err := uuid.Parse(tc.in); err == nil {
				t.Errorf("uuid.Parse(%q) accepted; handler would query and 22P02", tc.in)
			}
		})
	}
}
