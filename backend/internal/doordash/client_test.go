package doordash

import "testing"

// DoorDash Drive authenticates webhooks with a static bearer token, not a body
// HMAC — these lock in that contract (an HMAC check would never match a real
// DoorDash call) plus the fail-closed and prefix-tolerance behavior.
func TestVerifyWebhook(t *testing.T) {
	const token = "ee714d52f6f92e8dc8eb59a669dd64a2"

	tests := []struct {
		name       string
		secret     string
		authHeader string
		want       bool
	}{
		// Fail closed: an unconfigured secret must never accept a caller,
		// including one presenting no credential at all.
		{"no secret configured rejects any token", "", "Bearer " + token, false},
		{"no secret configured rejects empty header", "", "", false},

		{"bare secret, header with prefix", token, "Bearer " + token, true},
		{"bare secret, bare header", token, token, true},
		// The portal displays the value with the prefix included, so an operator
		// may paste it into the env var verbatim.
		{"prefixed secret, header with prefix", "Bearer " + token, "Bearer " + token, true},
		{"prefixed secret, bare header", "Bearer " + token, token, true},

		{"prefix casing is ignored", token, "bearer " + token, true},
		{"prefix casing is ignored (upper)", token, "BEARER " + token, true},
		{"surrounding whitespace is ignored", token, "  Bearer  " + token + "  ", true},

		{"wrong token rejected", token, "Bearer wrongtoken00000000000000000", false},
		{"empty header rejected", token, "", false},
		{"prefix with no token rejected", token, "Bearer ", false},
		// Tokens are secrets: only the prefix is case-insensitive.
		{"token comparison is case-sensitive", "abc123def456", "Bearer ABC123DEF456", false},
		// A prefix match must not be enough.
		{"token prefix is not accepted", token, "Bearer " + token[:16], false},
		{"token with extra suffix rejected", token, "Bearer " + token + "x", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := New(Config{WebhookSec: tt.secret})
			if got := c.VerifyWebhook(tt.authHeader); got != tt.want {
				t.Errorf("VerifyWebhook(%q) with secret %q = %v, want %v",
					tt.authHeader, tt.secret, got, tt.want)
			}
		})
	}
}

// Enabled gates the client into stub mode; the webhook secret alone must not
// flip it live (dispatch needs the JWT credential triple).
func TestEnabled(t *testing.T) {
	tests := []struct {
		name string
		cfg  Config
		want bool
	}{
		{"fully configured", Config{DeveloperID: "d", KeyID: "k", SigningKey: "s"}, true},
		{"empty config", Config{}, false},
		{"webhook secret only", Config{WebhookSec: "w"}, false},
		{"missing signing key", Config{DeveloperID: "d", KeyID: "k"}, false},
		{"missing key id", Config{DeveloperID: "d", SigningKey: "s"}, false},
		{"missing developer id", Config{KeyID: "k", SigningKey: "s"}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := New(tt.cfg).Enabled(); got != tt.want {
				t.Errorf("Enabled() = %v, want %v", got, tt.want)
			}
		})
	}
}
