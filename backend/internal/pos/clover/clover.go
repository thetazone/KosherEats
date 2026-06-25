// Package clover implements the POS Adapter for Clover. The merchant
// connects via OAuth (see oauth.go); after that, every accepted order is
// pushed to api.clover.com/v3/merchants/{mId}/orders. Clover routes the
// resulting receipt to whichever physical printers the merchant configured
// in their device's printer categories (kitchen, customer, etc) — we don't
// manage printers ourselves.
package clover

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/koshereats/backend/internal/models"
	"github.com/koshereats/backend/internal/pos"
)

// API base. Override with CLOVER_API_BASE for sandbox testing
// (https://apisandbox.dev.clover.com).
const defaultAPIBase = "https://api.clover.com"

func apiBase() string {
	if v := os.Getenv("CLOVER_API_BASE"); v != "" {
		return v
	}
	return defaultAPIBase
}

// Adapter implements pos.Adapter for Clover.
type Adapter struct {
	http *http.Client
}

func New() *Adapter {
	return &Adapter{http: &http.Client{Timeout: 30 * time.Second}}
}

func (a *Adapter) Provider() pos.Provider { return pos.ProviderClover }

// PushOrder pushes an accepted order to Clover. Maps our flat OrderItem
// list to Clover's v3 line_items shape. Item-level prices include any
// modifier deltas (per Order.Items spec), so we send them as-is — Clover's
// kitchen printer template will show item name + quantity + notes.
func (a *Adapter) PushOrder(ctx context.Context, integ pos.Integration, order *models.Order) error {
	body := orderPayload(order)
	url := fmt.Sprintf("%s/v3/merchants/%s/orders", apiBase(), integ.MerchantID)

	if err := a.postJSON(ctx, integ.AccessToken, url, body, nil); err != nil {
		return err
	}
	return nil
}

// TestConnection makes a cheap auth-only call to verify the access token
// is still valid. /v3/merchants/{mId} is a single-row GET that returns 200
// when auth works, 401 when it doesn't.
func (a *Adapter) TestConnection(ctx context.Context, integ pos.Integration) error {
	url := fmt.Sprintf("%s/v3/merchants/%s", apiBase(), integ.MerchantID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+integ.AccessToken)
	resp, err := a.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		return errors.New("clover: access token rejected (401) — merchant must reconnect")
	}
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("clover: test connection %d: %s", resp.StatusCode, string(b))
	}
	return nil
}

// orderPayload maps our Order to the minimal Clover v3 Order shape.
// Clover accepts much more (taxes, discounts, customer object, etc) but
// what's needed to get a ticket printing at the kitchen is just the line
// items + a title/note for the front desk to recognize the order.
func orderPayload(order *models.Order) map[string]any {
	items := make([]map[string]any, 0, len(order.Items))
	for _, it := range order.Items {
		// Clover prices are cents-as-int per unit, which matches our schema.
		items = append(items, map[string]any{
			"name":     it.Name,
			"price":    it.Price,
			"unitQty":  it.Quantity,
			"note":     it.Notes,
		})
	}
	// Deliberately omit a top-level "total": order.Total is the customer-paid
	// amount (subtotal − discount + delivery/service fees + tax + tip), which is
	// inconsistent with the full-price line-item sum and misrepresents what the
	// kitchen ticket should show. Let Clover compute the total from the line
	// items so the printed ticket is internally consistent. (Money settles via
	// Stripe, not Clover — this is a print-the-kitchen-ticket path only.)
	return map[string]any{
		"state":     "open",
		"title":     "KosherEats order " + shortID(order.ID),
		"note":      fmt.Sprintf("Customer: %s · %s", order.CustomerName, order.CustomerPhone),
		"lineItems": items,
	}
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func (a *Adapter) postJSON(ctx context.Context, token, url string, body any, out any) error {
	buf, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(buf))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := a.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		return errors.New("clover: 401 — access token rejected; merchant must reconnect")
	}
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("clover: %d: %s", resp.StatusCode, string(b))
	}
	if out != nil {
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return nil
}
