package clover

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Clover OAuth. The seller app opens AuthorizeURL in an in-app browser;
// Clover redirects back to our callback with ?code=...&merchant_id=...;
// the callback handler exchanges the code for an access token via
// ExchangeCode and stores the integration row.
//
// Clover's OAuth has both an "App Market" production flow and a sandbox
// flow at sandbox.dev.clover.com. CLOVER_OAUTH_BASE controls which.

const (
	defaultOAuthBase = "https://www.clover.com"
)

func oauthBase() string {
	if v := os.Getenv("CLOVER_OAUTH_BASE"); v != "" {
		return v
	}
	return defaultOAuthBase
}

func clientID() string     { return os.Getenv("CLOVER_CLIENT_ID") }
func clientSecret() string { return os.Getenv("CLOVER_CLIENT_SECRET") }

// Configured returns true when the env vars for OAuth are present. Used by
// the integrations endpoints to decide whether to expose "Connect Clover"
// at all; an unconfigured deploy hides the button.
func Configured() bool {
	return clientID() != "" && clientSecret() != ""
}

// AuthorizeURL builds the URL the seller's browser is sent to. `state` is
// a random opaque value the callback uses to identify which seller started
// the flow (avoid replay + CSRF). `redirectURI` must match the value
// registered on Clover's developer dashboard exactly.
func AuthorizeURL(state, redirectURI string) string {
	v := url.Values{}
	v.Set("client_id", clientID())
	v.Set("redirect_uri", redirectURI)
	v.Set("response_type", "code")
	v.Set("state", state)
	return fmt.Sprintf("%s/oauth/authorize?%s", oauthBase(), v.Encode())
}

// TokenResponse mirrors the JSON Clover returns from the code-exchange
// endpoint.
type TokenResponse struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token,omitempty"`
	ExpiresAt    time.Time `json:"-"`
	MerchantID   string    `json:"-"`
}

// ExchangeCode swaps an OAuth authorization code for an access token.
// `merchantID` comes from the callback query string (Clover passes it
// alongside the code).
func ExchangeCode(ctx context.Context, code, merchantID, redirectURI string) (*TokenResponse, error) {
	if !Configured() {
		return nil, errors.New("clover: CLOVER_CLIENT_ID / CLOVER_CLIENT_SECRET not set")
	}
	form := url.Values{}
	form.Set("client_id", clientID())
	form.Set("client_secret", clientSecret())
	form.Set("code", code)
	form.Set("redirect_uri", redirectURI)
	form.Set("grant_type", "authorization_code")

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		oauthBase()+"/oauth/token",
		strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("clover oauth %d: %s", resp.StatusCode, string(b))
	}

	var out TokenResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	out.MerchantID = merchantID
	return &out, nil
}
