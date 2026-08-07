package handlers

// Preview-listing integration tests: the property under test is that a preview
// restaurant is browsable (behind the opt-in flag) but money can NEVER move
// against it — not via cart, not via a stale cart at order time, and not via a
// PaymentIntent. The UI graying-out is cosmetic; these guards are the feature.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
)

// seedPreviewRestaurant inserts an ownerless preview listing the way the
// catalog seeder does: pending, inactive, empty kosher_certification (no
// hechsher claim until verified), plus one menu item so the cart guard can be
// exercised against a real item id.
func (e *testEnv) seedPreviewRestaurant(ctx context.Context, name string) (restID, itemID string, err error) {
	pool := e.h.db.Pool
	if err = pool.QueryRow(ctx,
		`INSERT INTO restaurants
		   (owner_id, name, street, city, state, zip_code, is_active, is_open,
		    approval_status, vertical, kosher_certification, listing_visibility, listing_priority)
		 VALUES (NULL, $1, '527 Kings Hwy', 'Brooklyn', 'NY', '11223', false, false,
		    'pending', 'kosher', '', 'preview', 1)
		 RETURNING id`, name,
	).Scan(&restID); err != nil {
		return "", "", fmt.Errorf("insert preview restaurant: %w", err)
	}
	var categoryID string
	if err = pool.QueryRow(ctx,
		`INSERT INTO menu_categories (restaurant_id, name, sort_order)
		 VALUES ($1, 'Menu', 0) RETURNING id`, restID).Scan(&categoryID); err != nil {
		return "", "", fmt.Errorf("insert preview category: %w", err)
	}
	if err = pool.QueryRow(ctx,
		`INSERT INTO menu_items (restaurant_id, category_id, name, price, is_available, sort_order)
		 VALUES ($1, $2, 'Shawarma Laffa', 1800, true, 0) RETURNING id`,
		restID, categoryID).Scan(&itemID); err != nil {
		return "", "", fmt.Errorf("insert preview item: %w", err)
	}
	return restID, itemID, nil
}

func TestIntegration_PreviewListings(t *testing.T) {
	harness.resetVolatile(t)
	ctx := context.Background()

	previewID, previewItemID, err := harness.seedPreviewRestaurant(ctx, "Preview Falafel House")
	if err != nil {
		t.Fatalf("seed preview: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, previewID)
	})

	token, userID := harness.registerUser(t, "previewtester")

	listIDs := func(t *testing.T, path string) map[string]json.RawMessage {
		t.Helper()
		rec := harness.do(http.MethodGet, path, token, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: status %d, body %s", path, rec.Code, rec.Body.String())
		}
		var raw []json.RawMessage
		if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
			t.Fatalf("GET %s decode: %v", path, err)
		}
		out := map[string]json.RawMessage{}
		for _, r := range raw {
			var row struct {
				ID string `json:"id"`
			}
			_ = json.Unmarshal(r, &row)
			out[row.ID] = r
		}
		return out
	}

	t.Run("hidden from the classic feed, shipped builds unaffected", func(t *testing.T) {
		if _, found := listIDs(t, "/api/v1/restaurants/")[previewID]; found {
			t.Fatal("preview restaurant leaked into the feed WITHOUT include_previews — old app builds would render it as orderable")
		}
	})

	t.Run("visible, not orderable, behind the opt-in flag", func(t *testing.T) {
		row, found := listIDs(t, "/api/v1/restaurants/?include_previews=1")[previewID]
		if !found {
			t.Fatal("preview restaurant missing from the preview-aware feed")
		}
		var got struct {
			Orderable         bool   `json:"orderable"`
			ListingVisibility string `json:"listing_visibility"`
			KosherCert        string `json:"kosher_certification"`
		}
		if err := json.Unmarshal(row, &got); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if got.Orderable {
			t.Fatal("preview restaurant reported orderable=true")
		}
		if got.ListingVisibility != "preview" {
			t.Fatalf("listing_visibility = %q, want preview", got.ListingVisibility)
		}
		if got.KosherCert != "" {
			t.Fatalf("preview row carries a hechsher claim %q — certification renders only once verified", got.KosherCert)
		}
	})

	t.Run("orderable restaurants sort above previews", func(t *testing.T) {
		rec := harness.do(http.MethodGet, "/api/v1/restaurants/?include_previews=1", token, nil)
		var rows []struct {
			ID        string `json:"id"`
			Orderable bool   `json:"orderable"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &rows); err != nil {
			t.Fatalf("decode: %v", err)
		}
		seenPreview := false
		for _, r := range rows {
			if !r.Orderable {
				seenPreview = true
			} else if seenPreview {
				t.Fatal("an orderable restaurant appeared BELOW a preview — approved must sort on top")
			}
		}
	})

	t.Run("detail and menu browsable only with the flag", func(t *testing.T) {
		if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+previewID, token, nil); rec.Code != http.StatusNotFound {
			t.Fatalf("detail without flag: status %d, want 404", rec.Code)
		}
		if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+previewID+"?include_previews=1", token, nil); rec.Code != http.StatusOK {
			t.Fatalf("detail with flag: status %d, want 200 (body %s)", rec.Code, rec.Body.String())
		}
		if rec := harness.do(http.MethodGet, "/api/v1/restaurants/"+previewID+"/menu?include_previews=1", token, nil); rec.Code != http.StatusOK {
			t.Fatalf("menu with flag: status %d, want 200", rec.Code)
		}
	})

	t.Run("cart is refused", func(t *testing.T) {
		rec := harness.do(http.MethodPost, "/api/v1/cart/items", token, AddToCartRequest{
			MenuItemID: previewItemID, RestaurantID: previewID, Quantity: 1,
		})
		if rec.Code != http.StatusForbidden {
			t.Fatalf("AddToCart against a preview: status %d, want 403 (body %s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("stale cart cannot order or pay", func(t *testing.T) {
		// Force a cart pointing at the preview restaurant, simulating a cart
		// built before the restaurant lost orderability (or a hostile client).
		var cartID string
		if err := harness.h.db.Pool.QueryRow(ctx,
			`INSERT INTO carts (user_id, restaurant_id) VALUES ($1, $2) RETURNING id`,
			userID, previewID).Scan(&cartID); err != nil {
			t.Fatalf("force cart: %v", err)
		}
		if _, err := harness.h.db.Pool.Exec(ctx,
			`INSERT INTO cart_items (cart_id, menu_item_id, quantity, unit_price)
			 VALUES ($1, $2, 1, 1800)`, cartID, previewItemID); err != nil {
			t.Fatalf("force cart item: %v", err)
		}

		if rec := harness.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
			"restaurant_id": previewID, "payment_intent_id": "pi_preview_test", "fulfillment_type": "pickup",
		}); rec.Code != http.StatusForbidden {
			t.Fatalf("CreateOrder against a preview cart: status %d, want 403 (body %s)", rec.Code, rec.Body.String())
		}
		if rec := harness.do(http.MethodPost, "/api/v1/payments/intent", token, map[string]any{}); rec.Code != http.StatusForbidden {
			t.Fatalf("CreatePaymentIntent against a preview cart: status %d, want 403 (body %s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("request toggle", func(t *testing.T) {
		type resp struct {
			Requested    bool `json:"requested"`
			RequestCount int  `json:"request_count"`
		}
		var r1 resp
		rec := harness.do(http.MethodPost, "/api/v1/restaurants/"+previewID+"/request", token, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("request toggle: status %d, body %s", rec.Code, rec.Body.String())
		}
		_ = json.Unmarshal(rec.Body.Bytes(), &r1)
		if !r1.Requested || r1.RequestCount != 1 {
			t.Fatalf("first toggle: requested=%v count=%d, want true/1", r1.Requested, r1.RequestCount)
		}

		// Heart shows up on the decorated feed for this user.
		row := listIDs(t, "/api/v1/restaurants/?include_previews=1")[previewID]
		var deco struct {
			RequestCount  int  `json:"request_count"`
			RequestedByMe bool `json:"requested_by_me"`
		}
		_ = json.Unmarshal(row, &deco)
		if deco.RequestCount != 1 || !deco.RequestedByMe {
			t.Fatalf("feed decoration: count=%d requested_by_me=%v, want 1/true", deco.RequestCount, deco.RequestedByMe)
		}

		// Toggle off.
		var r2 resp
		rec = harness.do(http.MethodPost, "/api/v1/restaurants/"+previewID+"/request", token, nil)
		_ = json.Unmarshal(rec.Body.Bytes(), &r2)
		if r2.Requested || r2.RequestCount != 0 {
			t.Fatalf("second toggle: requested=%v count=%d, want false/0", r2.Requested, r2.RequestCount)
		}

		// Requesting a LIVE restaurant is rejected — the table means "demand for
		// restaurants we don't have", and live ones are simply orderable.
		rec = harness.do(http.MethodPost, "/api/v1/restaurants/"+harness.approvedRestID+"/request", token, nil)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("request against a live restaurant: status %d, want 400", rec.Code)
		}
	})
}
