package database

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// RunMigrations walks the given directory and applies any .sql files whose
// names haven't been recorded in the schema_migrations table yet.
//
// Migrations are applied in lexicographic order, wrapped in a single
// transaction each so a syntax error halts the run without leaving the
// database half-migrated.
//
// Keeps things simple — no down migrations, no dependency graph, just
// "apply all new files in order and remember which ones ran." Good enough
// for the MVP and avoids pulling in a dependency like golang-migrate.
//
// Multi-instance safe: two instances deploying at once must not race the
// schema_migrations apply (both reading "not applied", both running the same
// file). We serialize the whole run behind a session-level Postgres advisory
// lock (pg_advisory_lock, blocking) held on a single dedicated connection for
// the duration. The first instance runs the migrations; any concurrent
// instance blocks on the lock, then — once it acquires — finds every file
// already recorded in schema_migrations and applies nothing. Single-instance
// deploys are unaffected: the lock is uncontended and acquired immediately.
const migrationAdvisoryLockKey int64 = 0x4B45_5357_0002

func (db *DB) RunMigrations(ctx context.Context, dir string) error {
	// Hold the advisory lock on its own connection for the whole run; session
	// advisory locks are per-connection so the same conn must take and release.
	conn, err := db.Pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("acquire migration lock conn: %w", err)
	}
	defer conn.Release()

	// Bound lock acquisition so a wedged-but-alive peer (a stuck migration /
	// hung connection still holding the session lock) surfaces a clear error
	// instead of hanging startup forever. pg_try_advisory_lock returns
	// immediately; poll until we get it or the deadline passes.
	lockCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	for {
		var got bool
		if err := conn.QueryRow(lockCtx, `SELECT pg_try_advisory_lock($1)`, migrationAdvisoryLockKey).Scan(&got); err != nil {
			return fmt.Errorf("acquire migration advisory lock: %w", err)
		}
		if got {
			break
		}
		select {
		case <-lockCtx.Done():
			return fmt.Errorf("could not acquire migration advisory lock within 60s (held by another instance): %w", lockCtx.Err())
		case <-time.After(500 * time.Millisecond):
		}
	}
	defer func() {
		if _, err := conn.Exec(ctx, `SELECT pg_advisory_unlock($1)`, migrationAdvisoryLockKey); err != nil {
			// Best-effort: the lock also drops when the conn closes/resets.
			_ = err
		}
	}()

	if _, err := db.Pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			name TEXT PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	applied, err := db.loadAppliedMigrations(ctx)
	if err != nil {
		return fmt.Errorf("load applied: %w", err)
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return fmt.Errorf("read migrations dir: %w", err)
	}

	var files []string
	for _, e := range entries {
		name := e.Name()
		// Only apply ordered .sql files (001_initial.sql, 002_social.sql, …).
		// Skip dev seed + any helper files — they're run manually.
		if e.IsDir() || !strings.HasSuffix(name, ".sql") || strings.HasPrefix(name, "dev_") {
			continue
		}
		files = append(files, name)
	}
	sort.Strings(files)

	for _, f := range files {
		if applied[f] {
			continue
		}

		sql, err := os.ReadFile(filepath.Join(dir, f))
		if err != nil {
			return fmt.Errorf("read %s: %w", f, err)
		}

		tx, err := db.Pool.Begin(ctx)
		if err != nil {
			return fmt.Errorf("begin tx for %s: %w", f, err)
		}

		if _, err := tx.Exec(ctx, string(sql)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("apply %s: %w", f, err)
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO schema_migrations (name) VALUES ($1) ON CONFLICT DO NOTHING`, f); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record %s: %w", f, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit %s: %w", f, err)
		}
	}
	return nil
}

func (db *DB) loadAppliedMigrations(ctx context.Context) (map[string]bool, error) {
	rows, err := db.Pool.Query(ctx, `SELECT name FROM schema_migrations`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		out[name] = true
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}
