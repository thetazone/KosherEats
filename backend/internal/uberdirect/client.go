// Package uberdirect is a thin HTTP client for the Uber Direct delivery API.
// Handles OAuth token caching, quote retrieval, delivery creation/cancellation,
// and webhook signature verification. Falls back to stub mode when credentials
// are empty so dev environments work without Uber keys.
package uberdirect

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	authURL = "https://auth.uber.com/oauth/v2/token"
	apiBase = "https://api.uber.com/v1"
)

type Config struct {
	ClientID     string
	ClientSecret string
	CustomerID   string
	WebhookSec   string
}

type Client struct {
	cfg     Config
	enabled bool
	http    *http.Client

	mu    sync.RWMutex
	token string
	expAt time.Time
}

func New(cfg Config) *Client {
	return &Client{
		cfg:     cfg,
		enabled: cfg.ClientID != "" && cfg.ClientSecret != "" && cfg.CustomerID != "",
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *Client) Enabled() bool { return c.enabled }

type Quote struct {
	ID              string    `json:"id"`
	Fee             int       `json:"fee"`
	Currency        string    `json:"currency"`
	DropoffETA      time.Time `json:"dropoff_eta"`
	DurationMinutes int       `json:"duration"`
	PickupDuration  int       `json:"pickup_duration"`
	Expires         time.Time `json:"expires"`
}

type Delivery struct {
	ID          string `json:"id"`
	TrackingURL string `json:"tracking_url"`
	Fee         int    `json:"fee"`
	Status      string `json:"status"`
}

type Address struct {
	Street  []string `json:"street_address"`
	City    string   `json:"city"`
	State   string   `json:"state"`
	ZipCode string   `json:"zip_code"`
	Country string   `json:"country"`
}

func (a Address) JSON() string {
	b, _ := json.Marshal(a)
	return string(b)
}

type CreateDeliveryRequest struct {
	QuoteID        string
	ExternalID     string
	PickupName     string
	PickupAddress  Address
	PickupPhone    string
	PickupNotes    string
	DropoffName    string
	DropoffAddress Address
	DropoffPhone   string
	DropoffNotes   string
	Items          []ManifestItem
	TotalCents     int
	TipCents       int
}

type ManifestItem struct {
	Name     string `json:"name"`
	Quantity int    `json:"quantity"`
	Price    int    `json:"price"`
}

func (c *Client) GetQuote(ctx context.Context, pickup, dropoff Address) (*Quote, error) {
	if !c.enabled {
		return &Quote{
			ID: "stub_quote_" + fmt.Sprintf("%d", time.Now().UnixMilli()),
			Fee: 799, Currency: "usd", DurationMinutes: 25, PickupDuration: 12,
			Expires: time.Now().Add(15 * time.Minute),
		}, nil
	}

	token, err := c.getToken(ctx)
	if err != nil {
		return nil, fmt.Errorf("uber auth: %w", err)
	}

	body := map[string]string{
		"pickup_address":  pickup.JSON(),
		"dropoff_address": dropoff.JSON(),
	}
	data, err := c.post(ctx, token,
		fmt.Sprintf("%s/customers/%s/delivery_quotes", apiBase, c.cfg.CustomerID), body)
	if err != nil {
		return nil, fmt.Errorf("uber quote: %w", err)
	}

	var q Quote
	if err := json.Unmarshal(data, &q); err != nil {
		return nil, fmt.Errorf("uber quote parse: %w", err)
	}
	return &q, nil
}

func (c *Client) CreateDelivery(ctx context.Context, req CreateDeliveryRequest) (*Delivery, error) {
	if !c.enabled {
		slog.Info("uberdirect stub: would create delivery",
			slog.String("external_id", req.ExternalID))
		return &Delivery{
			ID: "stub_del_" + fmt.Sprintf("%d", time.Now().UnixMilli()),
			TrackingURL: "https://stub.uber.com/track/test",
			Fee: 799, Status: "pending",
		}, nil
	}

	token, err := c.getToken(ctx)
	if err != nil {
		return nil, fmt.Errorf("uber auth: %w", err)
	}

	items := make([]map[string]any, len(req.Items))
	for i, it := range req.Items {
		items[i] = map[string]any{
			"name": it.Name, "quantity": it.Quantity,
			"price": it.Price, "currency_code": "USD",
		}
	}

	body := map[string]any{
		"quote_id":             req.QuoteID,
		"pickup_name":          req.PickupName,
		"pickup_address":       req.PickupAddress.JSON(),
		"pickup_phone_number":  req.PickupPhone,
		"dropoff_name":         req.DropoffName,
		"dropoff_address":      req.DropoffAddress.JSON(),
		"dropoff_phone_number": req.DropoffPhone,
		"manifest_items":       items,
		"manifest_total_value": req.TotalCents,
		"external_id":          req.ExternalID,
	}
	if req.PickupNotes != "" {
		body["pickup_notes"] = req.PickupNotes
	}
	if req.DropoffNotes != "" {
		body["dropoff_notes"] = req.DropoffNotes
	}
	if req.TipCents > 0 {
		body["tip"] = req.TipCents
	}

	data, err := c.post(ctx, token,
		fmt.Sprintf("%s/customers/%s/deliveries", apiBase, c.cfg.CustomerID), body)
	if err != nil {
		return nil, fmt.Errorf("uber create delivery: %w", err)
	}

	var d Delivery
	if err := json.Unmarshal(data, &d); err != nil {
		return nil, fmt.Errorf("uber delivery parse: %w", err)
	}
	return &d, nil
}

func (c *Client) CancelDelivery(ctx context.Context, deliveryID, reason string) error {
	if !c.enabled {
		slog.Info("uberdirect stub: would cancel delivery",
			slog.String("delivery_id", deliveryID))
		return nil
	}

	token, err := c.getToken(ctx)
	if err != nil {
		return fmt.Errorf("uber auth: %w", err)
	}

	body := map[string]string{
		"reason":           reason,
		"cancelling_party": "MERCHANT",
	}
	_, err = c.post(ctx, token,
		fmt.Sprintf("%s/eats/orders/%s/cancel", apiBase, deliveryID), body)
	return err
}

func (c *Client) VerifyWebhook(body []byte, signature string) bool {
	if c.cfg.WebhookSec == "" {
		return false
	}
	mac := hmac.New(sha256.New, []byte(c.cfg.WebhookSec))
	mac.Write(body)
	expected := hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(signature), []byte(expected))
}

// getToken returns a cached Bearer token, refreshing when expired.
func (c *Client) getToken(ctx context.Context) (string, error) {
	c.mu.RLock()
	if c.token != "" && time.Now().Before(c.expAt) {
		t := c.token
		c.mu.RUnlock()
		return t, nil
	}
	c.mu.RUnlock()

	c.mu.Lock()
	defer c.mu.Unlock()

	// Double-check after acquiring write lock.
	if c.token != "" && time.Now().Before(c.expAt) {
		return c.token, nil
	}

	form := url.Values{
		"client_id":     {c.cfg.ClientID},
		"client_secret": {c.cfg.ClientSecret},
		"grant_type":    {"client_credentials"},
		"scope":         {"eats.deliveries"},
	}

	req, err := http.NewRequestWithContext(ctx, "POST", authURL,
		strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("uber auth %d: %s", resp.StatusCode, string(b))
	}

	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil {
		return "", err
	}

	c.token = tok.AccessToken
	c.expAt = time.Now().Add(time.Duration(tok.ExpiresIn-60) * time.Second)
	return c.token, nil
}

func (c *Client) post(ctx context.Context, token, url string, body any) ([]byte, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("uber %d: %s", resp.StatusCode, string(data))
	}
	return data, nil
}
