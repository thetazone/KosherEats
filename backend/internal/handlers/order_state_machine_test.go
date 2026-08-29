package handlers

// The seller-side order state machine: which transitions are legal, who may
// make them, and — critically — the guards that stop an order being handled
// twice once a PAID external courier owns it.
//
// SAFETY: DB only. No provider client is configured in these tests, so the
// dispatch hooks (MarkOrderReady's inline dispatch, EscalateToUber) short out
// before any network call; the transitions themselves are what's under test.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
)

// sellerRouter mirrors the production seller order routes, with the same
// middleware and {id} param shape as cmd/api/main.go.
func sellerRouter(h *Handler) http.Handler {
	r := chi.NewRouter()
	r.Route("/api/v1/seller/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Patch("/{id}/accept", h.AcceptOrder)
		r.Patch("/{id}/preparing", h.MarkOrderPreparing)
		r.Patch("/{id}/delivery-mode", h.SetOrderDeliveryMode)
		r.Patch("/{id}/ready", h.MarkOrderReady)
		r.Patch("/{id}/complete", h.CompleteOrder)
		r.Patch("/{id}/reject", h.RejectOrder)
		r.Patch("/{id}/pickup", h.SellerPickupOrder)
		r.Patch("/{id}/deliver", h.SellerDeliverOrder)
		// PATCH, matching cmd/api/main.go. A test router that accepts a method
		// production does not would let a real method regression through.
		r.Patch("/{id}/escalate", h.EscalateToUber)
	})
	return r
}

// sellerEnv is a restaurant plus a token for its owner.
type sellerEnv struct {
	router   http.Handler
	token    string
	ownerID  string
	restID   string
	itemID   string
	consumer string
}

func newSellerEnv(t *testing.T) *sellerEnv {
	t.Helper()
	h := harness.h
	ctx := context.Background()

	var ownerID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Sell', 'Er', $2, 'seller', 'kosher') RETURNING id`,
		uniqueEmail("seller"), uniquePhone(),
	).Scan(&ownerID); err != nil {
		t.Fatalf("seed seller: %v", err)
	}
	var consumerID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Con', 'Sumer', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("sm-consumer"), uniquePhone(),
	).Scan(&consumerID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}
	restID, itemID, err := harness.seedRestaurant(ctx, ownerID, "State Machine Deli "+uniqueEmail("x"), "approved")
	if err != nil {
		t.Fatalf("seed restaurant: %v", err)
	}
	token, _, err := h.generateTokens(ctx, ownerID, "seller", "kosher")
	if err != nil {
		t.Fatalf("mint seller token: %v", err)
	}
	t.Cleanup(func() {
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE restaurant_id = $1`, restID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM menu_items WHERE restaurant_id = $1`, restID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM menu_categories WHERE restaurant_id = $1`, restID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, restID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id IN ($1, $2)`, ownerID, consumerID)
	})
	return &sellerEnv{
		router: sellerRouter(h), token: token, ownerID: ownerID,
		restID: restID, itemID: itemID, consumer: consumerID,
	}
}

// order seeds an order on this seller's restaurant in the given state.
func (s *sellerEnv) order(t *testing.T, status string, mutate ...func(id string)) string {
	t.Helper()
	var id string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, courier_tip, fulfillment_type, delivery_mode)
		 VALUES ($1, $2, $3, 1500, 699, 0, 135, 2334, '2 Oak St', $4, 500, 'delivery', 'platform')
		 RETURNING id`,
		s.consumer, s.restID, status, fmt.Sprintf("pi_sm_%d", time.Now().UnixNano()),
	).Scan(&id); err != nil {
		t.Fatalf("seed order: %v", err)
	}
	for _, m := range mutate {
		m(id)
	}
	return id
}

func (s *sellerEnv) patch(t *testing.T, orderID, action, token string) *http.Response {
	t.Helper()
	return s.call(t, http.MethodPatch, orderID, action, token)
}

func (s *sellerEnv) call(t *testing.T, method, orderID, action, token string) *http.Response {
	t.Helper()
	rec := doRequest(s.router, method, "/api/v1/seller/orders/"+orderID+"/"+action, token, nil)
	return rec.Result()
}

func statusOf(t *testing.T, orderID string) string {
	t.Helper()
	var s string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT status FROM orders WHERE id = $1`, orderID).Scan(&s); err != nil {
		t.Fatalf("read status: %v", err)
	}
	return s
}

// ---- legal and illegal transitions ---------------------------------------

// The seller ladder is pending → accepted → preparing → ready. Every step
// asserts its REQUIRED predecessor, so an out-of-order tap (a stale client, a
// double tap, a replayed request) can't skip the kitchen or resurrect a
// terminal order.
func TestIntegration_SellerStatusLadder(t *testing.T) {
	s := newSellerEnv(t)

	cases := []struct {
		name       string
		from       string
		action     string
		wantStatus int
		wantState  string
	}{
		{"pending accepts", "pending", "accept", http.StatusOK, "accepted"},
		{"accepted prepares", "accepted", "preparing", http.StatusOK, "preparing"},
		{"preparing is ready", "preparing", "ready", http.StatusOK, "ready"},

		// Skipping a rung.
		{"pending cannot go straight to preparing", "pending", "preparing", http.StatusBadRequest, "pending"},
		{"pending cannot go straight to ready", "pending", "ready", http.StatusBadRequest, "pending"},
		{"accepted cannot skip preparing", "accepted", "ready", http.StatusBadRequest, "accepted"},

		// Re-tapping a rung already climbed.
		{"accepting twice is refused", "accepted", "accept", http.StatusBadRequest, "accepted"},
		{"preparing twice is refused", "preparing", "preparing", http.StatusBadRequest, "preparing"},
		{"ready twice is refused", "ready", "ready", http.StatusBadRequest, "ready"},

		// Terminal states are terminal.
		{"cancelled cannot be accepted", "cancelled", "accept", http.StatusBadRequest, "cancelled"},
		{"delivered cannot be accepted", "delivered", "accept", http.StatusBadRequest, "delivered"},
		{"delivered cannot go back to preparing", "delivered", "preparing", http.StatusBadRequest, "delivered"},
		{"rejected cannot be accepted", "rejected", "accept", http.StatusBadRequest, "rejected"},
		{"picked_up cannot be re-readied", "picked_up", "ready", http.StatusBadRequest, "picked_up"},

		// A scheduled order isn't acceptable until the dispatcher promotes it.
		{"scheduled cannot be accepted yet", "scheduled", "accept", http.StatusBadRequest, "scheduled"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := s.order(t, tc.from)
			resp := s.patch(t, id, tc.action, s.token)
			if resp.StatusCode != tc.wantStatus {
				t.Fatalf("%s from %s: status %d, want %d", tc.action, tc.from, resp.StatusCode, tc.wantStatus)
			}
			if got := statusOf(t, id); got != tc.wantState {
				t.Errorf("order is %q, want %q", got, tc.wantState)
			}
		})
	}
}

// Every seller transition is scoped to the restaurant's OWNER. Without that,
// any authenticated seller could accept, ready, or reject another restaurant's
// orders — and reject issues a refund.
func TestIntegration_SellerTransitionsAreOwnerScoped(t *testing.T) {
	mine := newSellerEnv(t)
	theirs := newSellerEnv(t)

	for _, action := range []string{"accept", "preparing", "ready", "reject", "pickup", "deliver"} {
		t.Run(action, func(t *testing.T) {
			id := mine.order(t, "pending")
			resp := mine.patch(t, id, action, theirs.token) // another seller's token
			if resp.StatusCode < 400 {
				t.Fatalf("%s by a different restaurant's owner returned %d, want a 4xx", action, resp.StatusCode)
			}
			if got := statusOf(t, id); got != "pending" {
				t.Errorf("order moved to %q under a foreign seller's token", got)
			}
		})
	}
}

// No token at all must never move an order.
func TestIntegration_SellerTransitionsRequireAuth(t *testing.T) {
	s := newSellerEnv(t)
	id := s.order(t, "pending")
	resp := s.patch(t, id, "accept", "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status %d, want 401", resp.StatusCode)
	}
	if got := statusOf(t, id); got != "pending" {
		t.Errorf("order moved to %q without a token", got)
	}
}

// ---- the double-handling guards -----------------------------------------

// SellerPickupOrder ↔ external dispatch is a real race: EscalateToUber takes no
// row lock, so without the external_provider/external_delivery_id guards a
// seller could mark an order self-picked-up while a provider is already
// paid-dispatching it → the food goes out twice and the platform pays for a
// courier nobody needed.
func TestIntegration_SellerPickupBlockedOnceAProviderOwnsTheOrder(t *testing.T) {
	s := newSellerEnv(t)

	setSelfDelivery := func(id string) {
		if _, err := harness.h.db.Pool.Exec(context.Background(),
			`UPDATE orders SET delivery_mode = 'restaurant' WHERE id = $1`, id); err != nil {
			t.Fatalf("set self-delivery: %v", err)
		}
	}

	cases := []struct {
		name   string
		mutate func(id string)
		wantOK bool
	}{
		{"clean self-delivery order can be picked up", setSelfDelivery, true},
		{"a delivery already claimed by the dispatch sentinel cannot", func(id string) {
			setSelfDelivery(id)
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET external_provider = 'dispatching' WHERE id = $1`, id); err != nil {
				t.Fatalf("claim: %v", err)
			}
		}, false},
		{"a delivery already out with a provider cannot", func(id string) {
			setSelfDelivery(id)
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET external_provider = 'uber_direct', external_delivery_id = 'del_1' WHERE id = $1`, id); err != nil {
				t.Fatalf("dispatch: %v", err)
			}
		}, false},
		// Self-pickup is only for restaurants that actually self-deliver.
		{"a platform-mode order cannot be self-picked-up", func(id string) {}, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := s.order(t, "ready", tc.mutate)
			resp := s.patch(t, id, "pickup", s.token)
			got := statusOf(t, id)
			if tc.wantOK {
				if resp.StatusCode != http.StatusOK {
					t.Fatalf("status %d, want 200", resp.StatusCode)
				}
				if got != "picked_up" {
					t.Errorf("status = %q, want picked_up", got)
				}
				return
			}
			if resp.StatusCode == http.StatusOK {
				t.Fatalf("pickup succeeded when it must not — the order can now be delivered twice")
			}
			if got != "ready" {
				t.Errorf("status = %q, want ready (unchanged)", got)
			}
		})
	}
}

// Escalation is one-way and only for OPEN delivery orders. Once a courier or a
// provider owns the order, handing it to a second provider would buy a second
// paid delivery.
func TestIntegration_EscalateRejectsIneligibleOrders(t *testing.T) {
	s := newSellerEnv(t)

	cases := []struct {
		name   string
		status string
		mutate func(id string)
	}{
		{"already out with a provider", "ready", func(id string) {
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET external_provider = 'uber_direct', external_delivery_id = 'del_1' WHERE id = $1`, id); err != nil {
				t.Fatalf("dispatch: %v", err)
			}
		}},
		{"already claimed by a platform courier", "ready", func(id string) {
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET courier_id = $2 WHERE id = $1`, id, s.consumer); err != nil {
				t.Fatalf("claim: %v", err)
			}
		}},
		{"a pickup order has no delivery to escalate", "ready", func(id string) {
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET fulfillment_type = 'pickup' WHERE id = $1`, id); err != nil {
				t.Fatalf("set pickup: %v", err)
			}
		}},
		{"already picked up", "picked_up", func(id string) {}},
		{"already delivered", "delivered", func(id string) {}},
		{"cancelled", "cancelled", func(id string) {}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id := s.order(t, tc.status, tc.mutate)
			rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/escalate", s.token, nil)
			if rec.Code < 400 {
				t.Fatalf("escalate returned %d, want a 4xx/5xx refusal (body %s)", rec.Code, rec.Body.String())
			}
			// Whatever the refusal, the linkage must be untouched.
			var provider, delivery string
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT COALESCE(external_provider,''), COALESCE(external_delivery_id,'') FROM orders WHERE id = $1`,
				id).Scan(&provider, &delivery); err != nil {
				t.Fatalf("read: %v", err)
			}
			if provider == "dispatching" {
				t.Errorf("a refused escalation left the claim sentinel behind — the order is now welded shut")
			}
		})
	}
}

// With no provider configured at all, escalation must fail fast with a clear
// 503 rather than claiming the order (and stranding it behind the claim
// sentinel) or pretending to dispatch.
func TestIntegration_EscalateWithNoProviderFailsFastAndLeavesNoClaim(t *testing.T) {
	h := harness.h
	origU, origD, origS, origDisp := h.uber, h.doordash, h.shipday, h.dispatcher
	defer func() { h.uber, h.doordash, h.shipday, h.dispatcher = origU, origD, origS, origDisp }()
	h.uber, h.doordash, h.shipday = nil, nil, nil

	s := newSellerEnv(t)
	id := s.order(t, "ready")
	rec := doRequest(sellerRouter(h), http.MethodPatch, "/api/v1/seller/orders/"+id+"/escalate", s.token, nil)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503 (body %s)", rec.Code, rec.Body.String())
	}
	var provider string
	if err := h.db.Pool.QueryRow(context.Background(),
		`SELECT COALESCE(external_provider,'') FROM orders WHERE id = $1`, id).Scan(&provider); err != nil {
		t.Fatalf("read: %v", err)
	}
	if provider != "" {
		t.Errorf("external_provider = %q after a fail-fast refusal, want empty", provider)
	}
	if got := statusOf(t, id); got != "ready" {
		t.Errorf("status = %q, want ready", got)
	}
}

// ---- consumer cancel guards ---------------------------------------------

// Once a PAID provider delivery is in flight, the consumer cancel path must be
// closed: cancelling refunds the customer while the platform still owes the
// courier, and nothing cancels the provider delivery.
func TestIntegration_ConsumerCancelBlockedOnceAProviderIsDispatched(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "cancel-guard")
	pi := fmt.Sprintf("pi_cg_%d", time.Now().UnixNano())
	orderID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)

	if _, err := harness.h.db.Pool.Exec(context.Background(),
		`UPDATE orders SET status = 'accepted', external_provider = 'uber_direct',
		        external_delivery_id = 'del_live' WHERE id = $1`, orderID); err != nil {
		t.Fatalf("dispatch order: %v", err)
	}

	rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 — refunding an order with a paid courier in flight "+
			"leaves the platform paying for a delivery on a refunded order (body %s)", rec.Code, rec.Body.String())
	}
	if got := statusOf(t, orderID); got != "accepted" {
		t.Errorf("status = %q, want accepted (unchanged)", got)
	}
}

// The cancel window closes once the kitchen starts cooking.
func TestIntegration_ConsumerCancelWindow(t *testing.T) {
	cases := []struct {
		status     string
		wantStatus int
	}{
		{"pending", http.StatusOK},
		{"accepted", http.StatusOK},
		{"scheduled", http.StatusOK},
		{"preparing", http.StatusBadRequest},
		{"ready", http.StatusBadRequest},
		{"picked_up", http.StatusBadRequest},
		{"delivered", http.StatusBadRequest},
		{"cancelled", http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.status, func(t *testing.T) {
			harness.resetVolatile(t)
			token, _ := harness.registerUser(t, "cancel-window")
			pi := fmt.Sprintf("pi_cw_%d", time.Now().UnixNano())
			orderID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)
			if _, err := harness.h.db.Pool.Exec(context.Background(),
				`UPDATE orders SET status = $2 WHERE id = $1`, orderID, tc.status); err != nil {
				t.Fatalf("set status: %v", err)
			}

			rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", token, nil)
			if rec.Code != tc.wantStatus {
				t.Fatalf("cancel from %s: status %d, want %d (body %s)",
					tc.status, rec.Code, tc.wantStatus, rec.Body.String())
			}
			got := statusOf(t, orderID)
			if tc.wantStatus == http.StatusOK && got != "cancelled" {
				t.Errorf("status = %q, want cancelled", got)
			}
			if tc.wantStatus != http.StatusOK && got != tc.status {
				t.Errorf("status = %q, want %q (unchanged)", got, tc.status)
			}
		})
	}
}

// A consumer must not be able to cancel someone else's order.
func TestIntegration_ConsumerCancelIsUserScoped(t *testing.T) {
	harness.resetVolatile(t)
	ownerTok, _ := harness.registerUser(t, "cancel-owner")
	pi := fmt.Sprintf("pi_cs_%d", time.Now().UnixNano())
	orderID := harness.placeOrder(t, ownerTok, harness.approvedRestID, harness.menuItemID, pi)

	otherTok, _ := harness.registerUser(t, "cancel-stranger")
	rec := harness.do(http.MethodPatch, "/api/v1/orders/"+orderID+"/cancel", otherTok, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400 for a stranger's order", rec.Code)
	}
	if got := statusOf(t, orderID); got == "cancelled" {
		t.Fatal("a stranger cancelled (and refunded) someone else's order")
	}
}

// ---- helper --------------------------------------------------------------

// doRequest issues a request against an arbitrary router with an optional
// bearer token. Mirrors testEnv.do, which is pinned to the shared router.
func doRequest(router http.Handler, method, path, token string, body any) *httptest.ResponseRecorder {
	var rdr *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	} else {
		rdr = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, rdr)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}
