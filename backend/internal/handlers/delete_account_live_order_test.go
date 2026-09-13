package handlers

// DeleteAccount used to run `UPDATE orders SET user_id = NULL` with no status
// filter, so a consumer could delete their account mid-order and cut a paid,
// non-terminal order loose from its customer. Nothing cancelled it and nothing
// refunded it: the captured PaymentIntent just stayed captured, while the
// stale-rejection sweep choked scanning the NULL user_id and every downstream
// consumer lookup (auto-dispatch, mark-ready, seller escalation) INNER JOINed
// the now-missing users row and stopped seeing the order at all.
//
// The handler now refuses the delete until the order reaches a terminal state.
//
// SAFETY: DB only. Stripe stays in the harness's dev stub mode.

import (
	"context"
	"net/http"
	"testing"
)

// liveOrderEnv is a consumer holding one order in a caller-chosen status.
type liveOrderEnv struct {
	token   string
	userID  string
	orderID string
}

func newLiveOrderEnv(t *testing.T, status string) *liveOrderEnv {
	t.Helper()
	h := harness.h
	ctx := context.Background()

	var userID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Live', 'Order', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("live-order"), uniquePhone(),
	).Scan(&userID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}

	// A charged order: stripe_payment_id set is what makes an abandoned row cost
	// the customer real money rather than just leaving a dangling record.
	var orderID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, fulfillment_type, delivery_mode)
		 VALUES ($1, $2, $3, 2000, 500, 0, 0, 2500, '9 Elm St', $4, 'delivery', 'platform')
		 RETURNING id`,
		userID, harness.approvedRestID, status, uniqueEmail("pi_live"),
	).Scan(&orderID); err != nil {
		t.Fatalf("seed %s order: %v", status, err)
	}

	token, _, err := h.generateTokens(ctx, userID, "consumer", "kosher")
	if err != nil {
		t.Fatalf("mint consumer token: %v", err)
	}

	t.Cleanup(func() {
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID)
	})
	return &liveOrderEnv{token: token, userID: userID, orderID: orderID}
}

func (e *liveOrderEnv) deleteAccount(t *testing.T) int {
	t.Helper()
	return doRequest(accountRouter(harness.h), http.MethodDelete,
		"/api/v1/users/account", e.token, nil).Code
}

// orderOwner reports the order's user_id, or "" once it has been anonymized.
func orderOwner(t *testing.T, orderID string) string {
	t.Helper()
	var owner *string
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&owner); err != nil {
		t.Fatalf("read order owner: %v", err)
	}
	if owner == nil {
		return ""
	}
	return *owner
}

// TestIntegration_DeleteAccountRefusesWhileOrderIsLive walks the reported
// scenario for every status an order can be sitting in with the card already
// charged. The account must survive intact so the order stays refundable and
// deliverable.
func TestIntegration_DeleteAccountRefusesWhileOrderIsLive(t *testing.T) {
	for _, status := range []string{"scheduled", "pending", "accepted", "preparing", "ready", "picked_up"} {
		t.Run(status, func(t *testing.T) {
			e := newLiveOrderEnv(t, status)

			if code := e.deleteAccount(t); code != http.StatusConflict {
				t.Fatalf("delete account with a %s order: status %d, want %d",
					status, code, http.StatusConflict)
			}
			if owner := orderOwner(t, e.orderID); owner != e.userID {
				t.Errorf("order user_id = %q, want it left intact as %q — the order is still "+
					"live and paid, so anonymizing it strands the refund", owner, e.userID)
			}

			var stillThere bool
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, e.userID).Scan(&stillThere); err != nil {
				t.Fatalf("check user: %v", err)
			}
			if !stillThere {
				t.Errorf("user %s was deleted despite a live %s order", e.userID, status)
			}
		})
	}
}

// A terminal order is settled money, so it must not block the delete — it is
// anonymized and kept for accounting, exactly as before.
func TestIntegration_DeleteAccountAllowedOnceOrdersAreTerminal(t *testing.T) {
	for _, status := range []string{"delivered", "completed", "cancelled", "rejected"} {
		t.Run(status, func(t *testing.T) {
			e := newLiveOrderEnv(t, status)

			if code := e.deleteAccount(t); code != http.StatusOK {
				t.Fatalf("delete account with a %s order: status %d, want %d",
					status, code, http.StatusOK)
			}
			if owner := orderOwner(t, e.orderID); owner != "" {
				t.Errorf("order user_id = %q, want NULL — a terminal order is kept but anonymized", owner)
			}
		})
	}
}

// ---- the other two hats an account can wear on a live order ---------------
//
// The guard above only counted orders.user_id, so a COURIER holding a claimed
// delivery and a SELLER whose restaurant had open orders could still delete
// their accounts. The anonymizing steps then stranded paid orders: the
// courier step nulls courier_id on a 'picked_up' order (food with a departed
// courier, no DeliverOrder possible, consumer past the cancel window), and the
// seller step nulls restaurants.owner_id (no owner left to accept, ready,
// hand off or reject). Both must be refused exactly like the consumer case.

// newCourierLiveOrderEnv is an approved courier who has claimed (and, when
// status is picked_up, collected) one platform delivery.
func newCourierLiveOrderEnv(t *testing.T, status string) *liveOrderEnv {
	t.Helper()
	h := harness.h
	ctx := context.Background()

	var courierID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Live', 'Courier', $2, 'courier', 'kosher') RETURNING id`,
		uniqueEmail("live-courier"), uniquePhone(),
	).Scan(&courierID); err != nil {
		t.Fatalf("seed courier: %v", err)
	}
	if _, err := h.db.Pool.Exec(ctx,
		`INSERT INTO courier_profiles (user_id, onboarding_status) VALUES ($1, 'approved')`,
		courierID); err != nil {
		t.Fatalf("seed courier profile: %v", err)
	}

	var orderID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, fulfillment_type, delivery_mode, courier_id, claimed_at)
		 VALUES ($1, $2, $3, 2000, 500, 0, 0, 2500, '9 Elm St', $4, 'delivery', 'platform', $5, NOW())
		 RETURNING id`,
		seedBystanderConsumer(t), harness.approvedRestID, status, uniqueEmail("pi_live_courier"), courierID,
	).Scan(&orderID); err != nil {
		t.Fatalf("seed %s order: %v", status, err)
	}

	token, _, err := h.generateTokens(ctx, courierID, "courier", "kosher")
	if err != nil {
		t.Fatalf("mint courier token: %v", err)
	}
	t.Cleanup(func() {
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, courierID)
	})
	return &liveOrderEnv{token: token, userID: courierID, orderID: orderID}
}

// newSellerLiveOrderEnv is a seller who owns a restaurant with one order on it.
func newSellerLiveOrderEnv(t *testing.T, status string) *liveOrderEnv {
	t.Helper()
	h := harness.h
	ctx := context.Background()

	var sellerID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Live', 'Seller', $2, 'seller', 'kosher') RETURNING id`,
		uniqueEmail("live-seller"), uniquePhone(),
	).Scan(&sellerID); err != nil {
		t.Fatalf("seed seller: %v", err)
	}
	var restID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO restaurants (owner_id, name, street, city, state, zip_code, phone,
		   is_active, is_open, approval_status, vertical, delivery_mode)
		 VALUES ($1, 'Live Seller Deli', '1 Main St', 'Brooklyn', 'NY', '11218', '+17185551212',
		   true, true, 'approved', 'kosher', 'platform') RETURNING id`, sellerID,
	).Scan(&restID); err != nil {
		t.Fatalf("seed restaurant: %v", err)
	}

	var orderID string
	if err := h.db.Pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, fulfillment_type, delivery_mode)
		 VALUES ($1, $2, $3, 2000, 500, 0, 0, 2500, '9 Elm St', $4, 'delivery', 'platform')
		 RETURNING id`,
		seedBystanderConsumer(t), restID, status, uniqueEmail("pi_live_seller"),
	).Scan(&orderID); err != nil {
		t.Fatalf("seed %s order: %v", status, err)
	}

	token, _, err := h.generateTokens(ctx, sellerID, "seller", "kosher")
	if err != nil {
		t.Fatalf("mint seller token: %v", err)
	}
	t.Cleanup(func() {
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, restID)
		_, _ = h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, sellerID)
	})
	return &liveOrderEnv{token: token, userID: sellerID, orderID: orderID}
}

// seedBystanderConsumer is the customer on the courier/seller orders below —
// a real user row so the order looks exactly like a live checkout.
func seedBystanderConsumer(t *testing.T) string {
	t.Helper()
	ctx := context.Background()
	var id string
	if err := harness.h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'By', 'Stander', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("bystander"), uniquePhone(),
	).Scan(&id); err != nil {
		t.Fatalf("seed bystander consumer: %v", err)
	}
	t.Cleanup(func() {
		_, _ = harness.h.db.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	})
	return id
}

func userExists(t *testing.T, userID string) bool {
	t.Helper()
	var exists bool
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, userID).Scan(&exists); err != nil {
		t.Fatalf("check user: %v", err)
	}
	return exists
}

func TestIntegration_DeleteAccountRefusesWhileCourierHoldsALiveDelivery(t *testing.T) {
	for _, status := range []string{"ready", "picked_up"} {
		t.Run(status, func(t *testing.T) {
			e := newCourierLiveOrderEnv(t, status)

			if code := e.deleteAccount(t); code != http.StatusConflict {
				t.Fatalf("courier delete account with a claimed %s order: status %d, want %d",
					status, code, http.StatusConflict)
			}
			if !userExists(t, e.userID) {
				t.Errorf("courier %s was deleted despite holding a %s order", e.userID, status)
			}
			var courier *string
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT courier_id FROM orders WHERE id = $1`, e.orderID).Scan(&courier); err != nil {
				t.Fatalf("read order courier: %v", err)
			}
			if courier == nil || *courier != e.userID {
				t.Errorf("order courier_id was cleared — the delivery is still in progress")
			}
		})
	}
}

func TestIntegration_DeleteAccountAllowedOnceCourierDeliveryIsTerminal(t *testing.T) {
	e := newCourierLiveOrderEnv(t, "delivered")
	if code := e.deleteAccount(t); code != http.StatusOK {
		t.Fatalf("courier delete account with a delivered order: status %d, want %d", code, http.StatusOK)
	}
	if userExists(t, e.userID) {
		t.Errorf("courier %s still exists after a permitted delete", e.userID)
	}
}

func TestIntegration_DeleteAccountRefusesWhileSellerHasOpenOrders(t *testing.T) {
	for _, status := range []string{"pending", "accepted", "preparing", "ready", "picked_up"} {
		t.Run(status, func(t *testing.T) {
			e := newSellerLiveOrderEnv(t, status)

			if code := e.deleteAccount(t); code != http.StatusConflict {
				t.Fatalf("seller delete account with a %s order on their restaurant: status %d, want %d",
					status, code, http.StatusConflict)
			}
			if !userExists(t, e.userID) {
				t.Errorf("seller %s was deleted despite an open %s order", e.userID, status)
			}
			var owner *string
			if err := harness.h.db.Pool.QueryRow(context.Background(),
				`SELECT rest.owner_id FROM orders o JOIN restaurants rest ON rest.id = o.restaurant_id
				  WHERE o.id = $1`, e.orderID).Scan(&owner); err != nil {
				t.Fatalf("read restaurant owner: %v", err)
			}
			if owner == nil || *owner != e.userID {
				t.Errorf("restaurant owner_id was cleared — nobody is left to work the open order")
			}
		})
	}
}

func TestIntegration_DeleteAccountAllowedOnceSellerOrdersAreTerminal(t *testing.T) {
	e := newSellerLiveOrderEnv(t, "completed")
	if code := e.deleteAccount(t); code != http.StatusOK {
		t.Fatalf("seller delete account with a completed order: status %d, want %d", code, http.StatusOK)
	}
	if userExists(t, e.userID) {
		t.Errorf("seller %s still exists after a permitted delete", e.userID)
	}
}
