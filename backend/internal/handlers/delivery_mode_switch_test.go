package handlers

// The two seller transitions that had no coverage at all: the per-order
// delivery-mode override and the pickup completion.
//
// Both sit directly on the dispatch/money path. SetOrderDeliveryMode decides
// whether an order goes to a PAID external courier or to the restaurant's own
// driver, and it resets external_dispatch_attempts — the counter the dispatch
// claim CAS uses to keep a hopeless order out of the paid quote/create loop, so
// a reset in the wrong state re-opens that loop. CompleteOrder is a terminal
// status flip whose pickup-only guard is what stops a seller closing out a
// delivery order under an assigned courier and skipping their payout.
//
// SAFETY: DB only. No provider client is configured, so nothing dials out.

import (
	"context"
	"net/http"
	"testing"
)

func deliveryModeOf(t *testing.T, orderID string) string {
	t.Helper()
	var mode string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COALESCE(delivery_mode, '') FROM orders WHERE id = $1`, orderID).Scan(&mode); err != nil {
		t.Fatalf("read delivery_mode: %v", err)
	}
	return mode
}

func attemptsOf(t *testing.T, orderID string) int {
	t.Helper()
	var n int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT external_dispatch_attempts FROM orders WHERE id = $1`, orderID).Scan(&n); err != nil {
		t.Fatalf("read external_dispatch_attempts: %v", err)
	}
	return n
}

// mustExec runs a fixture statement and fails the test on error.
func mustExec(t *testing.T, sql string, args ...any) {
	t.Helper()
	if _, err := harness.h.db.Pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("fixture exec: %v", err)
	}
}

// ---- SetOrderDeliveryMode ------------------------------------------------

// The happy path both ways, plus the attempts reset that is the whole reason
// the endpoint touches the dispatch bookkeeping: an order retired from the
// external path (attempts at the cap) is cap-blocked out of every sweep, so a
// seller explicitly choosing 'external' again has to clear the counter or the
// choice does nothing.
func TestIntegration_SetDeliveryModeSwitchesAndResetsTheRetryBudget(t *testing.T) {
	s := newSellerEnv(t)

	for _, mode := range []string{"restaurant", "external"} {
		t.Run(mode, func(t *testing.T) {
			id := s.order(t, "accepted", func(id string) {
				if _, err := harness.h.db.Pool.Exec(context.Background(),
					`UPDATE orders SET external_dispatch_attempts = 5 WHERE id = $1`, id); err != nil {
					t.Fatalf("seed exhausted attempts: %v", err)
				}
			})
			rec := doRequest(s.router, http.MethodPatch,
				"/api/v1/seller/orders/"+id+"/delivery-mode", s.token,
				map[string]any{"delivery_mode": mode})
			if rec.Code != http.StatusOK {
				t.Fatalf("status %d, want 200 (body %s)", rec.Code, rec.Body.String())
			}
			if got := deliveryModeOf(t, id); got != mode {
				t.Errorf("delivery_mode = %q, want %q", got, mode)
			}
			if got := attemptsOf(t, id); got != 0 {
				t.Errorf("external_dispatch_attempts = %d, want 0 — an order left at the cap is "+
					"blocked by the dispatch claim CAS, so the seller's choice would never take effect", got)
			}
		})
	}
}

// Anything that isn't one of the two modes must be refused before it reaches
// the database: quoteDeliveryFee and the dispatch claim CAS both branch on the
// exact string 'restaurant', so a third value silently routes the order into
// the paid external path while the seller believes otherwise.
func TestIntegration_SetDeliveryModeValidatesTheMode(t *testing.T) {
	s := newSellerEnv(t)
	for _, mode := range []string{"", "platform", "Restaurant", "uber", "restaurant; DROP"} {
		t.Run("rejects "+mode, func(t *testing.T) {
			id := s.order(t, "accepted")
			rec := doRequest(s.router, http.MethodPatch,
				"/api/v1/seller/orders/"+id+"/delivery-mode", s.token,
				map[string]any{"delivery_mode": mode})
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status %d for mode %q, want 400 (body %s)", rec.Code, mode, rec.Body.String())
			}
			if got := deliveryModeOf(t, id); got != "platform" {
				t.Errorf("delivery_mode = %q — the seeded value must be untouched", got)
			}
		})
	}
}

// Once anyone owns the delivery the choice is over. Each of these guards
// prevents a distinct double-delivery: a claimed courier driving to a
// restaurant that has just been told to self-deliver, or a re-routed order
// whose paid provider delivery is already bought (or being bought — the
// 'dispatching' sentinel covers the window before the id exists).
func TestIntegration_SetDeliveryModeIsClosedOnceSomeoneOwnsTheDelivery(t *testing.T) {
	s := newSellerEnv(t)

	cases := []struct {
		name   string
		status string
		mutate func(id string)
	}{
		{"a courier has claimed it", "ready", func(id string) {
			var courierID string
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
				 VALUES ($1, '', 'Cour', 'Ier', $2, 'courier', 'kosher') RETURNING id`,
				uniqueEmail("dm-courier"), uniquePhone()).Scan(&courierID); err != nil {
				t.Fatalf("seed courier: %v", err)
			}
			t.Cleanup(func() {
				_, _ = harness.h.db.Pool.Exec(context.Background(), `DELETE FROM users WHERE id = $1`, courierID)
			})
			mustExec(t, `UPDATE orders SET courier_id = $2 WHERE id = $1`, id, courierID)
		}},
		{"a dispatch holds the claim sentinel", "ready", func(id string) {
			mustExec(t, `UPDATE orders SET external_provider = 'dispatching' WHERE id = $1`, id)
		}},
		{"a paid provider delivery exists", "ready", func(id string) {
			mustExec(t, `UPDATE orders SET external_provider = 'uber_direct', external_delivery_id = 'del_1' WHERE id = $1`, id)
		}},
		{"a pickup order has no delivery to route", "ready", func(id string) {
			mustExec(t, `UPDATE orders SET fulfillment_type = 'pickup' WHERE id = $1`, id)
		}},
		{"the order has not been accepted yet", "pending", nil},
		{"the order is already delivered", "delivered", nil},
		{"the order was cancelled", "cancelled", nil},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var id string
			if tc.mutate != nil {
				id = s.order(t, tc.status, tc.mutate)
			} else {
				id = s.order(t, tc.status)
			}
			before := deliveryModeOf(t, id)
			rec := doRequest(s.router, http.MethodPatch,
				"/api/v1/seller/orders/"+id+"/delivery-mode", s.token,
				map[string]any{"delivery_mode": "restaurant"})
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status %d, want 400 (body %s)", rec.Code, rec.Body.String())
			}
			if got := deliveryModeOf(t, id); got != before {
				t.Errorf("delivery_mode changed from %q to %q despite the refusal", before, got)
			}
		})
	}
}

// Ownership: another restaurant's owner must not be able to re-route this
// order, and the endpoint must be authenticated at all.
func TestIntegration_SetDeliveryModeIsOwnerScoped(t *testing.T) {
	s := newSellerEnv(t)
	other := newSellerEnv(t)
	id := s.order(t, "accepted")

	rec := doRequest(s.router, http.MethodPatch,
		"/api/v1/seller/orders/"+id+"/delivery-mode", other.token,
		map[string]any{"delivery_mode": "restaurant"})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("a foreign seller got %d, want 400 (body %s)", rec.Code, rec.Body.String())
	}
	if got := deliveryModeOf(t, id); got != "platform" {
		t.Errorf("delivery_mode = %q after a foreign seller's request", got)
	}

	rec = doRequest(s.router, http.MethodPatch,
		"/api/v1/seller/orders/"+id+"/delivery-mode", "",
		map[string]any{"delivery_mode": "restaurant"})
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("unauthenticated request got %d, want 401", rec.Code)
	}
}

// ---- CompleteOrder -------------------------------------------------------

// The pickup-only guard is the load-bearing part: a delivery order completed
// here jumps straight to a terminal status, so the courier who is mid-delivery
// can never mark it delivered and their payout — queued only by DeliverOrder —
// is never enqueued at all.
func TestIntegration_CompleteIsPickupOnly(t *testing.T) {
	s := newSellerEnv(t)

	for _, status := range []string{"preparing", "ready"} {
		t.Run("delivery order at "+status+" is refused", func(t *testing.T) {
			id := s.order(t, status)
			rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", s.token, nil)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status %d, want 400 (body %s)", rec.Code, rec.Body.String())
			}
			if got := statusOf(t, id); got != status {
				t.Errorf("status = %q, want %q — completing a delivery order strands its courier "+
					"and skips the payout DeliverOrder would have queued", got, status)
			}
		})

		t.Run("pickup order at "+status+" completes", func(t *testing.T) {
			id := s.order(t, status, func(id string) {
				mustExec(t, `UPDATE orders SET fulfillment_type = 'pickup', delivery_fee = 0, courier_tip = 0 WHERE id = $1`, id)
			})
			rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", s.token, nil)
			if rec.Code != http.StatusOK {
				t.Fatalf("status %d, want 200 (body %s)", rec.Code, rec.Body.String())
			}
			if got := statusOf(t, id); got != "completed" {
				t.Errorf("status = %q, want completed", got)
			}
		})
	}
}

// A completion is a one-shot: a replayed tap on an already-completed order, or
// one on an order that never reached the counter, must change nothing.
func TestIntegration_CompleteIsStateGuardedAndOwnerScoped(t *testing.T) {
	s := newSellerEnv(t)
	other := newSellerEnv(t)

	pickup := func(status string) string {
		return s.order(t, status, func(id string) {
			mustExec(t, `UPDATE orders SET fulfillment_type = 'pickup' WHERE id = $1`, id)
		})
	}

	t.Run("replay after completion is a no-op", func(t *testing.T) {
		id := pickup("ready")
		if rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", s.token, nil); rec.Code != http.StatusOK {
			t.Fatalf("first complete: status %d", rec.Code)
		}
		if rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", s.token, nil); rec.Code != http.StatusBadRequest {
			t.Errorf("replayed complete: status %d, want 400", rec.Code)
		}
		if got := statusOf(t, id); got != "completed" {
			t.Errorf("status = %q, want completed", got)
		}
	})

	for _, status := range []string{"pending", "accepted", "cancelled", "rejected"} {
		t.Run("refused at "+status, func(t *testing.T) {
			id := pickup(status)
			if rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", s.token, nil); rec.Code != http.StatusBadRequest {
				t.Fatalf("status %d, want 400", rec.Code)
			}
			if got := statusOf(t, id); got != status {
				t.Errorf("status = %q, want %q", got, status)
			}
		})
	}

	t.Run("a foreign seller cannot complete", func(t *testing.T) {
		id := pickup("ready")
		if rec := doRequest(s.router, http.MethodPatch, "/api/v1/seller/orders/"+id+"/complete", other.token, nil); rec.Code != http.StatusBadRequest {
			t.Fatalf("status %d, want 400", rec.Code)
		}
		if got := statusOf(t, id); got != "ready" {
			t.Errorf("status = %q, want ready", got)
		}
	})
}
