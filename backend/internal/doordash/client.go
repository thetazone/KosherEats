// Package doordash is a thin HTTP client for the DoorDash Drive API.
// Used as a second fallback courier provider after Uber Direct. Falls back
// to stub mode when credentials are empty.
//
// DoorDash Drive uses a JWT signed with your developer credentials for auth
// (no OAuth token exchange). JWTs are minted locally and valid for 5 minutes.
package doordash

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

const apiBase = "https://openapi.doordash.com/drive/v2"

type Config struct {
	DeveloperID string
	KeyID       string
	SigningKey   string
	WebhookSec  string
}

type Client struct {
	cfg     Config
	enabled bool
	http    *http.Client
}

func New(cfg Config) *Client {
	return &Client{
		cfg:     cfg,
		enabled: cfg.DeveloperID != "" && cfg.KeyID != "" && cfg.SigningKey != "",
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *Client) Enabled() bool { return c.enabled }

type Quote struct {
	ExternalDeliveryID string `json:"external_delivery_id"`
	Fee                int    `json:"fee"`
	Currency           string `json:"currency"`
	EstPickupTime      string `json:"estimated_pickup_time"`
	EstDeliveryTime    string `json:"estimated_delivery_time"`
}

type Delivery struct {
	ExternalDeliveryID string `json:"external_delivery_id"`
	TrackingURL        string `json:"tracking_url"`
	Fee                int    `json:"fee"`
	Currency           string `json:"currency"`
	DeliveryStatus     string `json:"delivery_status"`
}

type CreateDeliveryRequest struct {
	ExternalDeliveryID  string
	PickupAddress       string
	PickupBusinessName  string
	PickupPhone         string
	PickupInstructions  string
	DropoffAddress      string
	DropoffContactName  string
	DropoffPhone        string
	DropoffInstructions string
	OrderValue          int
	TipCents            int
	Items               []Item
}

type Item struct {
	Name     string `json:"name"`
	Quantity int    `json:"quantity"`
	Price    int    `json:"price"`
}

func (c *Client) GetQuote(ctx context.Context, req CreateDeliveryRequest) (*Quote, error) {
	if !c.enabled {
		return &Quote{
			ExternalDeliveryID: "stub_dd_" + fmt.Sprintf("%d", time.Now().UnixMilli()),
			Fee: 975, Currency: "USD",
		}, nil
	}

	token, err := c.mintJWT()
	if err != nil {
		return nil, fmt.Errorf("doordash jwt: %w", err)
	}

	body := c.buildBody(req)
	data, err := c.post(ctx, token, apiBase+"/quotes", body)
	if err != nil {
		return nil, fmt.Errorf("doordash quote: %w", err)
	}

	var q Quote
	if err := json.Unmarshal(data, &q); err != nil {
		return nil, fmt.Errorf("doordash quote parse: %w", err)
	}
	return &q, nil
}

func (c *Client) CreateDelivery(ctx context.Context, req CreateDeliveryRequest) (*Delivery, error) {
	if !c.enabled {
		slog.Info("doordash stub: would create delivery",
			slog.String("external_id", req.ExternalDeliveryID))
		return &Delivery{
			ExternalDeliveryID: req.ExternalDeliveryID,
			TrackingURL:        "https://stub.doordash.com/track/test",
			Fee: 975, Currency: "USD", DeliveryStatus: "created",
		}, nil
	}

	token, err := c.mintJWT()
	if err != nil {
		return nil, fmt.Errorf("doordash jwt: %w", err)
	}

	body := c.buildBody(req)
	data, err := c.post(ctx, token, apiBase+"/deliveries", body)
	if err != nil {
		return nil, fmt.Errorf("doordash create: %w", err)
	}

	var d Delivery
	if err := json.Unmarshal(data, &d); err != nil {
		return nil, fmt.Errorf("doordash delivery parse: %w", err)
	}
	return &d, nil
}

func (c *Client) CancelDelivery(ctx context.Context, externalID string) error {
	if !c.enabled {
		return nil
	}
	token, err := c.mintJWT()
	if err != nil {
		return err
	}
	_, err = c.doReq(ctx, "PUT", token,
		fmt.Sprintf("%s/deliveries/%s/cancel", apiBase, externalID), nil)
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

func (c *Client) buildBody(req CreateDeliveryRequest) map[string]any {
	body := map[string]any{
		"external_delivery_id":     req.ExternalDeliveryID,
		"pickup_address":           req.PickupAddress,
		"pickup_business_name":     req.PickupBusinessName,
		"pickup_phone_number":      req.PickupPhone,
		"dropoff_address":          req.DropoffAddress,
		"dropoff_contact_given_name": req.DropoffContactName,
		"dropoff_phone_number":     req.DropoffPhone,
		"order_value":              req.OrderValue,
	}
	if req.PickupInstructions != "" {
		body["pickup_instructions"] = req.PickupInstructions
	}
	if req.DropoffInstructions != "" {
		body["dropoff_instructions"] = req.DropoffInstructions
	}
	if req.TipCents > 0 {
		body["tip"] = req.TipCents
	}
	if len(req.Items) > 0 {
		items := make([]map[string]any, len(req.Items))
		for i, it := range req.Items {
			items[i] = map[string]any{
				"name": it.Name, "quantity": it.Quantity,
				"external_id": fmt.Sprintf("item_%d", i),
			}
		}
		body["items"] = items
	}
	return body
}

// mintJWT creates a short-lived JWT for DoorDash API auth. DoorDash uses
// a custom HS256 JWT scheme where you sign with your signing_secret and
// include developer_id + key_id in the claims.
func (c *Client) mintJWT() (string, error) {
	header := base64url([]byte(`{"alg":"HS256","dd-ver":"DD-JWT-V1","typ":"JWT"}`))

	claims := map[string]any{
		"aud":          "doordash",
		"iss":          c.cfg.DeveloperID,
		"kid":          c.cfg.KeyID,
		"iat":          time.Now().Unix(),
		"exp":          time.Now().Add(5 * time.Minute).Unix(),
	}
	claimsJSON, _ := json.Marshal(claims)
	payload := base64url(claimsJSON)

	signingInput := header + "." + payload

	// DoorDash signing key is base64-encoded; decode it first.
	keyBytes, err := base64.URLEncoding.WithPadding(base64.NoPadding).DecodeString(c.cfg.SigningKey)
	if err != nil {
		keyBytes = []byte(c.cfg.SigningKey)
	}

	mac := hmac.New(sha256.New, keyBytes)
	mac.Write([]byte(signingInput))
	sig := base64url(mac.Sum(nil))

	return signingInput + "." + sig, nil
}

func base64url(data []byte) string {
	return strings.TrimRight(base64.URLEncoding.EncodeToString(data), "=")
}

func (c *Client) post(ctx context.Context, token, url string, body any) ([]byte, error) {
	payload, _ := json.Marshal(body)
	return c.doReq(ctx, "POST", token, url, payload)
}

func (c *Client) doReq(ctx context.Context, method, token, url string, body []byte) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
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
		return nil, fmt.Errorf("doordash %d: %s", resp.StatusCode, string(data))
	}
	return data, nil
}
