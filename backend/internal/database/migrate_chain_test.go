package database

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// defaultMigrateTestDatabaseURL points at the project's docker-compose Postgres
// (same server the handler integration suite uses). We only borrow its host /
// credentials — the chain test creates and drops its OWN throwaway database so
// it never touches koshereats_test.
const defaultMigrateTestDatabaseURL = "postgres://postgres:postgres@localhost:5433/koshereats_test?sslmode=disable"

// throwawayMigrateDB is a dedicated database created fresh and dropped for the
// migration-chain test, so the run always starts from a truly empty schema.
const throwawayMigrateDB = "koshereats_migchain_test"

// TestMigrationsApplyFromScratch applies the ENTIRE ordered migration chain
// (001 -> latest) against a brand-new, empty database and asserts every file
// applies without error.
//
// Why this exists separately from the handler integration suite: that suite
// REUSES koshereats_test if it already exists, so it only ever applies the
// newest migrations on top of an already-migrated schema — it never proves a
// clean apply from 001. A correctly-tracked Fly boot does exactly the latter,
// and migration failures are now fatal (main.go log.Fatalf), so a single bad /
// out-of-order migration is an outage, not a warning. This test catches that in
// CI instead of at boot.
func TestMigrationsApplyFromScratch(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	baseURL := os.Getenv("TEST_DATABASE_URL")
	if baseURL == "" {
		baseURL = defaultMigrateTestDatabaseURL
	}

	db, dropDB := freshThrowawayDB(ctx, t, baseURL)
	defer dropDB()

	if err := db.RunMigrations(ctx, "migrations"); err != nil {
		t.Fatalf("migration chain failed to apply from scratch: %v", err)
	}

	// Sanity: the whole numbered chain got recorded (40+ files and counting).
	var applied int
	if err := db.Pool.QueryRow(ctx, `SELECT count(*) FROM schema_migrations`).Scan(&applied); err != nil {
		t.Fatalf("count schema_migrations: %v", err)
	}
	if applied < 43 {
		t.Fatalf("expected >=43 migrations recorded, got %d", applied)
	}

	// Running again must be a no-op (idempotent) — proves the advisory-locked
	// "skip already-applied" path on the same DB the second instance would hit.
	if err := db.RunMigrations(ctx, "migrations"); err != nil {
		t.Fatalf("second RunMigrations (idempotent re-apply) failed: %v", err)
	}
}

// TestDealOncePerUserConstraintEnforced proves migration 043's partial unique
// index actually enforces "one active use of a deal per customer" — the atomic
// backstop for the race in resolveDealDiscount. It checks both halves of the
// index predicate: a second ACTIVE order on the same (user, deal) is rejected,
// but a cancelled one is allowed (excluded by the WHERE clause).
func TestDealOncePerUserConstraintEnforced(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	baseURL := os.Getenv("TEST_DATABASE_URL")
	if baseURL == "" {
		baseURL = defaultMigrateTestDatabaseURL
	}

	db, dropDB := freshThrowawayDB(ctx, t, baseURL)
	defer dropDB()

	if err := db.RunMigrations(ctx, "migrations"); err != nil {
		t.Fatalf("migrations: %v", err)
	}

	// The index must exist by name (clearer failure than a missing-enforcement
	// assertion if 043 silently didn't run).
	var idxExists bool
	if err := db.Pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM pg_indexes WHERE indexname = 'uq_orders_user_deal_active')`,
	).Scan(&idxExists); err != nil {
		t.Fatalf("look up index: %v", err)
	}
	if !idxExists {
		t.Fatal("migration 043 did not create uq_orders_user_deal_active")
	}

	// Minimal FK-valid fixtures: user -> restaurant -> deal.
	var userID, restID, dealID string
	if err := db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name) VALUES ($1,$2,$3) RETURNING id`,
		"migtest@example.com", "x", "Mig").Scan(&userID); err != nil {
		t.Fatalf("insert user: %v", err)
	}
	if err := db.Pool.QueryRow(ctx,
		`INSERT INTO restaurants (owner_id, name, street, city, state, zip_code)
		 VALUES ($1,$2,$3,$4,$5,$6) RETURNING id`,
		userID, "Mig Diner", "1 Main", "Town", "NY", "10001").Scan(&restID); err != nil {
		t.Fatalf("insert restaurant: %v", err)
	}
	if err := db.Pool.QueryRow(ctx,
		`INSERT INTO deals (restaurant_id, title, expires_at)
		 VALUES ($1,$2, NOW() + interval '1 day') RETURNING id`,
		restID, "Mig Deal").Scan(&dealID); err != nil {
		t.Fatalf("insert deal: %v", err)
	}

	insertOrder := func(status string) error {
		_, err := db.Pool.Exec(ctx,
			`INSERT INTO orders (user_id, restaurant_id, applied_deal_id, status, subtotal, total, delivery_address)
			 VALUES ($1,$2,$3,$4,1000,1000,'addr')`,
			userID, restID, dealID, status)
		return err
	}

	// First active redemption: allowed.
	if err := insertOrder("pending"); err != nil {
		t.Fatalf("first active deal order should insert, got: %v", err)
	}

	// Second active redemption of the SAME deal by the SAME user: must hit the
	// unique index (SQLSTATE 23505) — this is the race backstop.
	err := insertOrder("accepted")
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != "23505" {
		t.Fatalf("second active deal order should violate uq_orders_user_deal_active (23505), got: %v", err)
	}

	// A cancelled order on the same (user, deal) is OUTSIDE the index predicate,
	// so it must still be allowed — otherwise a cancelled order would wrongly
	// burn the customer's one allowed use.
	if err := insertOrder("cancelled"); err != nil {
		t.Fatalf("cancelled order on same (user,deal) should be allowed by the partial index, got: %v", err)
	}
}

// freshThrowawayDB drops + recreates throwawayMigrateDB on the same server as
// baseURL and returns a connected *DB plus a cleanup func that closes the pool
// and drops the database. It t.Skip()s (not Fatal) when no Postgres is
// reachable, so `go test ./...` on a machine without the dev DB stays green —
// CI runs Postgres, so the test executes there.
func freshThrowawayDB(ctx context.Context, t *testing.T, baseURL string) (*DB, func()) {
	t.Helper()

	adminCfg, err := pgx.ParseConfig(baseURL)
	if err != nil {
		t.Fatalf("parse base url: %v", err)
	}
	adminCfg.Database = "postgres" // maintenance DB — can't DROP/CREATE while connected to the target

	adminConn, err := pgx.ConnectConfig(ctx, adminCfg)
	if err != nil {
		t.Skipf("no Postgres reachable at %s (%v) — skipping migration-chain test", adminCfg.Host, err)
	}

	dropStmt := fmt.Sprintf(`DROP DATABASE IF EXISTS %s WITH (FORCE)`, pgx.Identifier{throwawayMigrateDB}.Sanitize())
	createStmt := fmt.Sprintf(`CREATE DATABASE %s`, pgx.Identifier{throwawayMigrateDB}.Sanitize())
	if _, err := adminConn.Exec(ctx, dropStmt); err != nil {
		adminConn.Close(ctx)
		t.Fatalf("drop throwaway db: %v", err)
	}
	if _, err := adminConn.Exec(ctx, createStmt); err != nil {
		adminConn.Close(ctx)
		t.Fatalf("create throwaway db: %v", err)
	}
	adminConn.Close(ctx)

	poolCfg, err := pgxpool.ParseConfig(baseURL)
	if err != nil {
		t.Fatalf("parse pool config: %v", err)
	}
	poolCfg.ConnConfig.Database = throwawayMigrateDB
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		t.Fatalf("connect throwaway db: %v", err)
	}
	db := &DB{Pool: pool}

	cleanup := func() {
		pool.Close()
		// Reconnect to maintenance and drop the throwaway DB (FORCE in case a
		// connection lingers).
		cctx, ccancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer ccancel()
		if c, err := pgx.ConnectConfig(cctx, adminCfg); err == nil {
			_, _ = c.Exec(cctx, dropStmt)
			c.Close(cctx)
		}
	}
	return db, cleanup
}
