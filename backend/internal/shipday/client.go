// Package shipday is a thin HTTP client for the Shipday API, used as a courier
// aggregator: one Shipday account dispatches DoorDash/Uber/local couriers under
// SHIPDAY'S master agreements, so KosherEats needs no direct provider account.
// (Adopted 2026-08 while the Uber Direct production account is disabled by Uber
// and DoorDash Drive production access is restricted to existing partners.)
//
// Flow differs from the direct providers: a dispatch is TWO calls — insert an
// order (POST /orders → numeric orderId), then assign it to a third-party
// service (POST /on-demand/assign). The fee quote comes from
// POST /on-demand/availability, which returns one entry per courier service.
//
// Unlike the uberdirect/doordash clients, a disabled client returns ERRORS from
// every call rather than canned stub successes. The stub-success pattern was
// flagged in review as a latent fail-open (one missing Enabled() guard turns a
// dead provider into a fake dispatch); callers must gate on Enabled().
//
// Money: Shipday speaks DOLLARS (floats) everywhere; this codebase speaks
// cents. All conversion happens at this boundary and nowhere else.
package shipday

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/koshereats/backend/internal/phone"
)

const apiBase = "https://api.shipday.com"

type Config struct {
	// APIKey from the Shipday dashboard (My Account). Sent as
	// `Authorization: Basic <APIKey>` — Shipday's scheme, not real RFC 7617
	// user:pass Basic auth.
	APIKey string
	// WebhookToken is the optional validation token (max 32 chars) configured
	// alongside the webhook URL in the Shipday dashboard. Shipday echoes it
	// verbatim in a `token` header on every webhook POST.
	WebhookToken string
}

// APIError is a non-2xx response from the Shipday API. Carries the status code
// so the dispatcher can tell a permanent validation rejection (4xx) from a
// transient outage (5xx). Reachable via errors.As through the %w wraps.
type APIError struct {
	StatusCode int
	Body       string
}

func (e *APIError) Error() string { return fmt.Sprintf("shipday %d: %s", e.StatusCode, e.Body) }

// ErrDisabled is returned by every API method when the client has no API key.
var ErrDisabled = errors.New("shipday client disabled (no API key)")

type Client struct {
	cfg     Config
	enabled bool
	http    *http.Client
}

func New(cfg Config) *Client {
	return &Client{
		cfg:     cfg,
		enabled: cfg.APIKey != "",
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *Client) Enabled() bool { return c.enabled }

// Quote is the cheapest available third-party service for a route.
type Quote struct {
	// ServiceName is the third-party service to later pass to assign
	// (e.g. "DoorDash", "Uber").
	ServiceName string
	// EstimateReference ties the later assign call to this estimate.
	// May be empty — assign treats it as optional.
	EstimateReference string
	FeeCents          int
	EstMinutes        int
}

// availabilityService mirrors one entry of POST /on-demand/availability.
// Fee and durations verified against docs.shipday.com/reference/availability-1.
type availabilityService struct {
	// ID is the estimate reference for assign. Type is undocumented, so accept
	// number or string.
	ID               json.RawMessage `json:"id"`
	Name             string          `json:"name"`
	Fee              float64         `json:"fee"`
	RegulatoryFee    float64         `json:"regulatoryFee"`
	PickupDuration   int             `json:"pickupDuration"`
	DeliveryDuration int             `json:"deliveryDuration"`
	Error            bool            `json:"error"`
	ErrorMessage     string          `json:"errorMessage"`
}

// GetQuote returns the cheapest available third-party service for the route,
// or an error when no service can take it. Addresses are full single-line
// strings ("street, city, state zip").
func (c *Client) GetQuote(ctx context.Context, pickupAddress, dropoffAddress string) (*Quote, error) {
	if !c.enabled {
		return nil, ErrDisabled
	}

	body := map[string]any{
		"pickupAddress":   pickupAddress,
		"deliveryAddress": dropoffAddress,
	}
	data, err := c.doReq(ctx, http.MethodPost, apiBase+"/on-demand/availability", body)
	if err != nil {
		return nil, fmt.Errorf("shipday availability: %w", err)
	}

	var services []availabilityService
	if err := json.Unmarshal(data, &services); err != nil {
		return nil, fmt.Errorf("shipday availability parse: %w", err)
	}

	best, err := cheapestService(services)
	if err != nil {
		return nil, err
	}

	est := best.PickupDuration + best.DeliveryDuration
	if est <= 0 {
		est = 40 // availability omitted durations; a rough urban courier ETA
	}
	return &Quote{
		ServiceName:       best.Name,
		EstimateReference: rawToString(best.ID),
		// The regulatory fee is part of what Shipday bills, so it must be part
		// of what checkout charges the consumer — quoting fee-only would
		// under-collect on every NY delivery.
		FeeCents:   dollarsToCents(best.Fee + best.RegulatoryFee),
		EstMinutes: est,
	}, nil
}

// cheapestService picks the lowest-fee usable service. Split out for tests.
func cheapestService(services []availabilityService) (*availabilityService, error) {
	var best *availabilityService
	var unavailable []string
	for i := range services {
		s := &services[i]
		if s.Error || s.Name == "" {
			if s.Name != "" {
				unavailable = append(unavailable, fmt.Sprintf("%s: %s", s.Name, s.ErrorMessage))
			}
			continue
		}
		// A zero fee on an available service is a payload we don't understand,
		// not a free courier — trusting it would quote the consumer $0.00 cost
		// and dispatch at an unknown real price.
		if s.Fee <= 0 {
			unavailable = append(unavailable, fmt.Sprintf("%s: zero fee", s.Name))
			continue
		}
		if best == nil || s.Fee+s.RegulatoryFee < best.Fee+best.RegulatoryFee {
			best = s
		}
	}
	if best == nil {
		return nil, fmt.Errorf("shipday: no third-party service available (%s)", strings.Join(unavailable, "; "))
	}
	return best, nil
}

type CreateDeliveryRequest struct {
	// OrderID is our order UUID; sent as Shipday's orderNumber and echoed back
	// on webhooks as order.order_number — the correlation key.
	OrderID              string
	RestaurantName       string
	RestaurantAddress    string
	RestaurantPhone      string
	CustomerName         string
	CustomerAddress      string
	CustomerPhone        string
	DeliveryInstructions string
	SubtotalCents        int
	TipCents             int
	// ServiceName/EstimateReference from the winning GetQuote.
	ServiceName       string
	EstimateReference string
}

// Delivery is the result of a successful insert+assign.
type Delivery struct {
	// ShipdayOrderID is Shipday's numeric order id as a string; stored as
	// external_delivery_id and matched against webhook order.id.
	ShipdayOrderID string
	TrackingURL    string
	// FeeCents is Shipday's total billable amount for the dispatch.
	FeeCents int
	Status   string
}

type insertOrderResponse struct {
	Success bool   `json:"success"`
	OrderID int64  `json:"orderId"`
	Message string `json:"response"`
}

type assignResponse struct {
	OrderID             int64   `json:"orderId"`
	ThirdPartyName      string  `json:"thirdPartyName"`
	ReferenceID         string  `json:"referenceId"`
	ThirdPartyFee       float64 `json:"thirdPartyFee"`
	ShipdayCharge       float64 `json:"shipdayCharge"`
	TotalBillableAmount float64 `json:"totalBillableAmount"`
	TrackingURL         string  `json:"trackingUrl"`
	Status              string  `json:"status"`
}

// CreateDelivery inserts the order into Shipday and assigns it to the given
// third-party service. If the insert succeeds but the assign fails, the error
// is returned with the inserted id embedded — the unassigned Shipday order
// costs nothing (no courier was engaged), and a later retry inserts a fresh
// order; webhook matching is scoped to the assigned order's id, so the
// abandoned insert can never advance our order.
func (c *Client) CreateDelivery(ctx context.Context, req CreateDeliveryRequest) (*Delivery, error) {
	if !c.enabled {
		return nil, ErrDisabled
	}

	insertBody := map[string]any{
		"orderNumber":         req.OrderID,
		"customerName":        customerName(req.CustomerName),
		"customerAddress":     req.CustomerAddress,
		"customerPhoneNumber": phone.ToE164(req.CustomerPhone),
		"restaurantName":      req.RestaurantName,
		"restaurantAddress":   req.RestaurantAddress,
		// Shipday money fields are dollars.
		"totalOrderCost": centsToDollars(req.SubtotalCents),
	}
	if req.RestaurantPhone != "" {
		insertBody["restaurantPhoneNumber"] = phone.ToE164(req.RestaurantPhone)
	}
	if req.DeliveryInstructions != "" {
		insertBody["deliveryInstruction"] = req.DeliveryInstructions
	}
	if req.TipCents > 0 {
		insertBody["tips"] = centsToDollars(req.TipCents)
	}

	data, err := c.doReq(ctx, http.MethodPost, apiBase+"/orders", insertBody)
	if err != nil {
		return nil, fmt.Errorf("shipday insert: %w", err)
	}
	var ins insertOrderResponse
	if err := json.Unmarshal(data, &ins); err != nil {
		return nil, fmt.Errorf("shipday insert parse: %w", err)
	}
	if !ins.Success || ins.OrderID == 0 {
		return nil, fmt.Errorf("shipday insert rejected: %s", ins.Message)
	}

	assignBody := map[string]any{
		"name":    req.ServiceName,
		"orderId": ins.OrderID,
	}
	if req.EstimateReference != "" {
		assignBody["estimateReference"] = req.EstimateReference
	}
	if req.TipCents > 0 {
		assignBody["tip"] = centsToDollars(req.TipCents)
	}

	data, err = c.doReq(ctx, http.MethodPost, apiBase+"/on-demand/assign", assignBody)
	if err != nil {
		return nil, fmt.Errorf("shipday assign (inserted order %d): %w", ins.OrderID, err)
	}
	var asg assignResponse
	if err := json.Unmarshal(data, &asg); err != nil {
		return nil, fmt.Errorf("shipday assign parse (inserted order %d): %w", ins.OrderID, err)
	}

	fee := asg.TotalBillableAmount
	if fee <= 0 {
		// Some responses may omit the rollup; reconstruct from the parts.
		fee = asg.ThirdPartyFee + asg.ShipdayCharge
	}
	return &Delivery{
		ShipdayOrderID: fmt.Sprintf("%d", ins.OrderID),
		TrackingURL:    asg.TrackingURL,
		FeeCents:       dollarsToCents(fee),
		Status:         asg.Status,
	}, nil
}

// CancelDelivery unassigns the third-party carrier from a Shipday order.
// Shipday documents no order-delete API; unassign is the strongest cancel
// available.
func (c *Client) CancelDelivery(ctx context.Context, shipdayOrderID string) error {
	if !c.enabled {
		return ErrDisabled
	}
	_, err := c.doReq(ctx, http.MethodPut,
		fmt.Sprintf("%s/orders/unassign/%s", apiBase, url.PathEscape(shipdayOrderID)), nil)
	return err
}

// VerifyWebhook authenticates an inbound Shipday webhook. Shipday does not
// sign payloads; it echoes the dashboard-configured validation token verbatim
// in a `token` header. Constant-time compare; fail closed when unconfigured.
func (c *Client) VerifyWebhook(tokenHeader string) bool {
	if c.cfg.WebhookToken == "" {
		return false
	}
	return subtle.ConstantTimeCompare(
		[]byte(strings.TrimSpace(tokenHeader)), []byte(c.cfg.WebhookToken)) == 1
}

// customerName guarantees a non-empty customer name — Shipday requires one,
// and a consumer profile without a name must not block its own delivery.
// Mirrors doordash.givenName.
func customerName(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "Customer"
	}
	return s
}

func dollarsToCents(d float64) int { return int(math.Round(d * 100)) }

func centsToDollars(c int) float64 { return float64(c) / 100 }

// rawToString renders the untyped availability id (number or quoted string)
// as a plain string, empty when absent.
func rawToString(raw json.RawMessage) string {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		return ""
	}
	return strings.Trim(s, `"`)
}

func (c *Client) doReq(ctx context.Context, method, url string, body any) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Basic "+c.cfg.APIKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &APIError{StatusCode: resp.StatusCode, Body: string(data)}
	}
	return data, nil
}
