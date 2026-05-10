// Package sms wraps Twilio Verify for phone-number OTP flows. Verify handles
// code generation, SMS delivery, expiry, and per-number rate limiting, so we
// don't store codes in our own DB — we just forward start/check calls.
//
// Dev stub mode: if any of TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN /
// TWILIO_VERIFY_SERVICE_SID are empty, Start() no-ops and Check() accepts a
// fixed code ("1234") so local dev and tests work without real Twilio keys.
// Production code length is configured in Twilio Verify, so callers should not
// hard-code a specific number of digits.
package sms

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/koshereats/backend/internal/config"
)

const devStubCode = "1234"

type Client struct {
	accountSID  string
	authToken   string
	serviceSID  string
	enabled     bool
	httpClient  *http.Client
}

func New(cfg *config.Config) *Client {
	c := &Client{
		accountSID: cfg.TwilioAccountSID,
		authToken:  cfg.TwilioAuthToken,
		serviceSID: cfg.TwilioVerifyServiceSID,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
	if c.accountSID != "" && c.authToken != "" && c.serviceSID != "" {
		c.enabled = true
	} else {
		log.Println("[twilio] verify credentials missing — running in dev stub mode (code: " + devStubCode + ")")
	}
	return c
}

// Start sends an SMS OTP to the given E.164 phone number. Returns nil on
// success; errors are surfaced so the caller can return a 502/5xx.
//
// After a successful first send, a background goroutine automatically
// resends after 5 seconds. Twilio Verify treats a Start call within 10
// seconds of the original as a resend of the *same* code, so the user
// won't see a different OTP — just a second delivery attempt that doubles
// the chance of carrier delivery.
func (c *Client) Start(ctx context.Context, phone string) error {
	if !c.enabled {
		log.Printf("[twilio stub] send OTP to %s (use %s)", phone, devStubCode)
		return nil
	}

	if err := c.sendVerification(ctx, phone); err != nil {
		return err
	}

	go func() {
		time.Sleep(5 * time.Second)
		retryCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := c.sendVerification(retryCtx, phone); err != nil {
			log.Printf("[twilio] auto-retry failed phone=%s err=%v", phone, err)
		} else {
			log.Printf("[twilio] auto-retry sent phone=%s", phone)
		}
	}()

	return nil
}

func (c *Client) sendVerification(ctx context.Context, phone string) error {
	form := url.Values{}
	form.Set("To", phone)
	form.Set("Channel", "sms")

	endpoint := fmt.Sprintf("https://verify.twilio.com/v2/Services/%s/Verifications", c.serviceSID)
	resp, err := c.do(ctx, endpoint, form)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("twilio verify start: %d %s", resp.StatusCode, string(body))
	}
	var parsed struct {
		SID    string `json:"sid"`
		Status string `json:"status"`
	}
	_ = json.Unmarshal(body, &parsed)
	log.Printf("[twilio] start phone=%s sid=%s status=%s", phone, parsed.SID, parsed.Status)
	return nil
}

// Check verifies the user-supplied OTP. Returns (true, nil) on approval,
// (false, nil) on mismatch/expiry (Twilio returns status != "approved"), and
// (false, err) on transport errors.
func (c *Client) Check(ctx context.Context, phone, code string) (bool, error) {
	if !c.enabled {
		return strings.TrimSpace(code) == devStubCode, nil
	}

	form := url.Values{}
	form.Set("To", phone)
	form.Set("Code", code)

	endpoint := fmt.Sprintf("https://verify.twilio.com/v2/Services/%s/VerificationCheck", c.serviceSID)
	resp, err := c.do(ctx, endpoint, form)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	// Twilio returns 404 when the verification has expired / doesn't exist.
	// Treat as a non-approval rather than a transport error so the handler
	// can show "invalid code" instead of a 502.
	if resp.StatusCode == http.StatusNotFound {
		log.Printf("[twilio] check 404 phone=%s body=%s — verification expired/consumed/not found", phone, string(body))
		return false, nil
	}
	if resp.StatusCode >= 300 {
		return false, fmt.Errorf("twilio verify check: %d %s", resp.StatusCode, string(body))
	}

	var parsed struct {
		SID    string `json:"sid"`
		Status string `json:"status"`
		Valid  bool   `json:"valid"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return false, fmt.Errorf("twilio verify check: decode: %w", err)
	}
	log.Printf("[twilio] check phone=%s sid=%s status=%s valid=%v", phone, parsed.SID, parsed.Status, parsed.Valid)
	return parsed.Status == "approved", nil
}

func (c *Client) do(ctx context.Context, endpoint string, form url.Values) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(c.accountSID, c.authToken)
	return c.httpClient.Do(req)
}
