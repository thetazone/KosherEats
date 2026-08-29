package scheduler

// An order whose consumer deleted their account carries user_id = NULL.
// sweepStaleRejection used to SELECT o.user_id straight into a plain string, so
// pgx failed the scan, the row was `continue`d past on every single tick, and a
// paid pending order was never auto-rejected and never refunded — the customer
// stayed charged indefinitely behind a repeating "scan failed" log line. The
// query now COALESCEs to '' so an anonymized row is still rejected + refunded;
// only the (undeliverable) push is lost.
//
// DeleteAccount now refuses to anonymize a live order at all, but rows written
// before that fix already exist in prod, so the sweep has to handle them.
//
// SAFETY: DB only. The Stripe client runs in dev stub mode (no key), so the
// refund call is logged, not dialed out — this asserts the DB side of the
// refund, not a real Stripe movement.

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/payments"
)

// staleSweepDB connects to the same database the handlers suite migrates and
// makes sure the schema is present. Skips (rather than fails) when no Postgres
// is reachable, matching how this package is run locally vs. in CI.
func staleSweepDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		url = "postgres://postgres:postgres@localhost:5433/koshereats_test?sslmode=disable"
	}
	db, err := database.Connect(url)
	if err != nil {
		t.Skipf("no test Postgres at %s: %v", url, err)
	}
	t.Cleanup(db.Close)

	_, thisFile, _, _ := runtime.Caller(0)
	migrations := filepath.Join(filepath.Dir(thisFile), "..", "database", "migrations")
	if err := db.RunMigrations(context.Background(), migrations); err != nil {
		t.Skipf("cannot migrate test database: %v", err)
	}
	return db.Pool
}

// seedAnonymizedPendingOrder creates a charged 'pending' order that is already
// past pendingOrderTTL and has no consumer — the exact shape DeleteAccount used
// to leave behind.
func seedAnonymizedPendingOrder(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	ctx := context.Background()

	var ownerID string
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Rest', 'Owner', $2, 'seller', 'kosher') RETURNING id`,
		fmt.Sprintf("stale-owner-%d@example.test", time.Now().UnixNano()),
		fmt.Sprintf("+1555%07d", time.Now().UnixNano()%10000000),
	).Scan(&ownerID); err != nil {
		t.Fatalf("seed owner: %v", err)
	}

	var restID string
	if err := pool.QueryRow(ctx,
		`INSERT INTO restaurants (owner_id, name, description, street, city, state, zip_code,
		   phone, lat, lng, is_active, vertical)
		 VALUES ($1, 'Stale Sweep Deli', '', '1 Main St', 'Brooklyn', 'NY', '11219',
		   '+15550000000', 40.63, -73.99, true, 'kosher')
		 RETURNING id`, ownerID,
	).Scan(&restID); err != nil {
		t.Fatalf("seed restaurant: %v", err)
	}

	// user_id NULL = the consumer deleted their account. updated_at is backdated
	// past the TTL so the sweep considers the order stale on this tick.
	var orderID string
	if err := pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, fulfillment_type, delivery_mode, created_at, updated_at)
		 VALUES (NULL, $1, 'pending', 2000, 500, 0, 0, 2500, '9 Elm St', $2, 'delivery', 'platform',
		   NOW() - make_interval(secs => $3), NOW() - make_interval(secs => $3))
		 RETURNING id`,
		restID, fmt.Sprintf("pi_stale_%d", time.Now().UnixNano()),
		int(pendingOrderTTL.Seconds())+60,
	).Scan(&orderID); err != nil {
		t.Fatalf("seed anonymized pending order: %v", err)
	}

	t.Cleanup(func() {
		_, _ = pool.Exec(ctx, `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = pool.Exec(ctx, `DELETE FROM restaurants WHERE id = $1`, restID)
		_, _ = pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, ownerID)
	})
	return orderID
}

func TestIntegration_StaleRejectionRefundsAnonymizedOrder(t *testing.T) {
	pool := staleSweepDB(t)
	orderID := seedAnonymizedPendingOrder(t, pool)

	// No Stripe key -> dev stub mode: RefundPaymentIntent logs and returns nil,
	// so the post-commit refunded_at stamp still runs. notify is nil (the sweep
	// nil-checks it) because an anonymized order has nobody to push to.
	d := &Dispatcher{db: pool, stripe: payments.New(&config.Config{})}
	d.sweepStaleRejection(context.Background())

	var status string
	var refundedAt *time.Time
	if err := pool.QueryRow(context.Background(),
		`SELECT status, refunded_at FROM orders WHERE id = $1`, orderID,
	).Scan(&status, &refundedAt); err != nil {
		t.Fatalf("read swept order: %v", err)
	}
	if status != "rejected" {
		t.Errorf("status = %q, want %q — an anonymized order must still be auto-rejected, "+
			"not skipped forever on a scan error", status, "rejected")
	}
	if refundedAt == nil {
		t.Errorf("refunded_at is NULL — the customer would stay charged for an order nobody will cook")
	}
}
