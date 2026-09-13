package handlers

// Seller-controlled checkout gates that must run BEFORE the card is charged:
// the dashboard "closed" toggle and the restaurant's minimum order. Neither was
// enforced server-side — a closed restaurant charged the customer and left the
// order to be auto-rejected + refunded ten minutes later, and the advertised
// "Min. order" floor was decorative.
//
// SAFETY: Stripe is in dev stub mode and no courier provider is configured, so
// nothing external is contacted; these tests use pickup so the delivery quote
// path (which refuses without a provider) is not in play.

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"
)

// setRestaurantGates flips the seeded restaurant's is_open / min_order for one
// test and restores the seeded values (open, no minimum) afterwards —
// restaurants are not part of resetVolatile.
func setRestaurantGates(t *testing.T, isOpen bool, minOrder int) {
	t.Helper()
	ctx := context.Background()
	if _, err := harness.h.db.Pool.Exec(ctx,
		`UPDATE restaurants SET is_open = $1, min_order = $2 WHERE id = $3`,
		isOpen, minOrder, harness.approvedRestID); err != nil {
		t.Fatalf("set restaurant gates: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx,
			`UPDATE restaurants SET is_open = true, min_order = 0 WHERE id = $1`, harness.approvedRestID)
	})
}

func TestIntegration_IntentRefusedWhileRestaurantIsClosed(t *testing.T) {
	harness.resetVolatile(t)
	setRestaurantGates(t, false, 0)
	token, _ := harness.registerUser(t, "closed-intent")
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

	rec, _ := createIntent(t, token, map[string]any{"fulfillment_type": "pickup"})
	if rec.Code != http.StatusConflict {
		t.Fatalf("ASAP intent at a closed restaurant: status %d, want %d (body %s)",
			rec.Code, http.StatusConflict, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "closed") {
		t.Errorf("refusal should tell the customer the restaurant is closed, got %s", rec.Body.String())
	}
}

// A closed restaurant still takes an order scheduled for later — that is what
// scheduling exists for — but only when the requested time is far enough out
// to actually land as a 'scheduled' order (the same threshold CreateOrder
// uses); a "scheduled" time within the ASAP window is an ASAP order.
func TestIntegration_ClosedRestaurantStillTakesScheduledOrders(t *testing.T) {
	cases := []struct {
		name       string
		lead       time.Duration
		wantStatus int
	}{
		{"two hours out is a scheduled order", 2 * time.Hour, http.StatusOK},
		{"ten minutes out is effectively ASAP", 10 * time.Minute, http.StatusConflict},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			setRestaurantGates(t, false, 0)
			token, _ := harness.registerUser(t, "closed-scheduled")
			harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

			rec, _ := createIntent(t, token, map[string]any{
				"fulfillment_type": "pickup",
				"scheduled_for":    time.Now().Add(tc.lead).UTC().Format(time.RFC3339),
			})
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
		})
	}
}

func TestIntegration_IntentEnforcesRestaurantMinimumOrder(t *testing.T) {
	cases := []struct {
		name       string
		minOrder   int
		wantStatus int
	}{
		{"no minimum", 0, http.StatusOK},
		{"subtotal equals the minimum", seededItemPrice, http.StatusOK},
		{"subtotal one cent under the minimum", seededItemPrice + 1, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			harness.resetVolatile(t)
			setRestaurantGates(t, true, tc.minOrder)
			token, _ := harness.registerUser(t, "min-order")
			harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)

			rec, _ := createIntent(t, token, map[string]any{"fulfillment_type": "pickup"})
			if rec.Code != tc.wantStatus {
				t.Fatalf("status %d, want %d (body %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if tc.wantStatus == http.StatusBadRequest && !strings.Contains(rec.Body.String(), "minimum order") {
				t.Errorf("refusal should name the minimum, got %s", rec.Body.String())
			}
		})
	}
}
