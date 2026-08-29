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
