package scheduler

// The orphan-payment sweep refunds a succeeded checkout PaymentIntent that has
// produced no order after a grace period. Its "no order yet?" check and its
// Stripe refund are two steps, and a CreateOrder for the SAME PaymentIntent can
// land between them — the web client's stored-PaymentIntent recovery retries
// exactly such late orders. That request passes VerifyPaymentSucceeded
// (nothing refunded yet), commits the order, and then the refund lands: an
// order the kitchen cooks and a courier is paid for, on a charge the customer
// got back.
//
// Both sides now take a transaction-scoped advisory lock keyed on the PI id
// (handlers.CreateOrder before its money guards; the sweep around its check +
// refund), which makes the two strictly ordered. This test plays the
// CreateOrder side by holding that lock from a transaction of its own.
//
// SAFETY: DB only. The Stripe client runs in dev stub mode (no key), so the
// refund call is logged, not dialed out.

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/payments"
)

// A CreateOrder holding the lock must make the sweep WAIT, and once that order
// is committed the sweep must see it and leave the charge alone.
func TestIntegration_OrphanRefundWaitsForAnInFlightCreateOrder(t *testing.T) {
	pool := staleSweepDB(t)
	ctx := context.Background()
	pi := fmt.Sprintf("pi_orphan_lock_%d", time.Now().UnixNano())

	// Restaurant + consumer for the order row the "CreateOrder" will insert.
	var ownerID, consumerID, restID string
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Lock', 'Owner', $2, 'seller', 'kosher') RETURNING id`,
		fmt.Sprintf("lock-owner-%d@example.test", time.Now().UnixNano()),
		fmt.Sprintf("+1555%07d", time.Now().UnixNano()%10000000),
	).Scan(&ownerID); err != nil {
		t.Fatalf("seed owner: %v", err)
	}
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Lock', 'Consumer', $2, 'consumer', 'kosher') RETURNING id`,
		fmt.Sprintf("lock-consumer-%d@example.test", time.Now().UnixNano()),
		fmt.Sprintf("+1555%07d", (time.Now().UnixNano()+1)%10000000),
	).Scan(&consumerID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}
	if err := pool.QueryRow(ctx,
		`INSERT INTO restaurants (owner_id, name, description, street, city, state, zip_code,
		   phone, lat, lng, is_active, vertical)
		 VALUES ($1, 'Orphan Lock Deli', '', '1 Main St', 'Brooklyn', 'NY', '11219',
		   '+15550000000', 40.63, -73.99, true, 'kosher')
		 RETURNING id`, ownerID,
	).Scan(&restID); err != nil {
		t.Fatalf("seed restaurant: %v", err)
	}
	t.Cleanup(func() {
		_, _ = pool.Exec(ctx, `DELETE FROM orders WHERE stripe_payment_id = $1`, pi)
		_, _ = pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, restID)
		_, _ = pool.Exec(ctx, `DELETE FROM users WHERE id IN ($1, $2)`, ownerID, consumerID)
	})

	// The in-flight CreateOrder: lock taken, order not yet committed.
	createTx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin create tx: %v", err)
	}
	defer createTx.Rollback(ctx) //nolint:errcheck
	if _, err := createTx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, pi); err != nil {
		t.Fatalf("take create-order lock: %v", err)
	}

	d := &Dispatcher{db: pool, stripe: payments.New(&config.Config{})}
	done := make(chan bool, 1)
	go func() {
		done <- d.refundOrphanUnlessOrdered(ctx, payments.OrphanCandidate{
			PaymentIntentID: pi, UserID: consumerID, AmountCents: 2500,
		})
	}()

	select {
	case refunded := <-done:
		t.Fatalf("sweep returned (refunded=%v) while a CreateOrder for the same PaymentIntent held the lock — "+
			"its check-then-refund can interleave with the order commit and refund a fulfilled order", refunded)
	case <-time.After(300 * time.Millisecond):
		// Blocked, as it must be.
	}

	// CreateOrder commits its order and releases the lock.
	if _, err := createTx.Exec(ctx,
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, fulfillment_type, delivery_mode)
		 VALUES ($1, $2, 'pending', 2000, 500, 0, 0, 2500, '9 Elm St', $3, 'delivery', 'platform')`,
		consumerID, restID, pi); err != nil {
		t.Fatalf("insert order: %v", err)
	}
	if err := createTx.Commit(ctx); err != nil {
		t.Fatalf("commit create tx: %v", err)
	}

	select {
	case refunded := <-done:
		if refunded {
			t.Fatal("sweep refunded a PaymentIntent that a committed order now references — " +
				"the customer gets their money back AND the food")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("sweep never returned after the CreateOrder lock was released")
	}
}

// With no order and no contender the sweep still refunds — the lock must not
// turn into a refusal.
func TestIntegration_OrphanRefundStillRefundsAGenuineOrphan(t *testing.T) {
	pool := staleSweepDB(t)
	d := &Dispatcher{db: pool, stripe: payments.New(&config.Config{})}
	pi := fmt.Sprintf("pi_orphan_free_%d", time.Now().UnixNano())
	if !d.refundOrphanUnlessOrdered(context.Background(), payments.OrphanCandidate{PaymentIntentID: pi}) {
		t.Fatal("a PaymentIntent with no order was not refunded")
	}
}
