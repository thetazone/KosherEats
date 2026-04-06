// Package scheduler runs small in-process background loops. Right now the
// only loop is the scheduled-order dispatcher, which promotes future-dated
// orders to 'pending' (visible to sellers) 30 minutes before their delivery
// window.
//
// In-process is fine for a single-instance deploy. If we ever scale to
// multiple API instances we'll need a distributed lock (Redis or
// pg_advisory_lock) so only one node runs the sweep.
package scheduler

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Dispatcher struct {
	db *pgxpool.Pool
}

func New(db *pgxpool.Pool) *Dispatcher {
	return &Dispatcher{db: db}
}

// Start launches a goroutine that sweeps the orders table every minute,
// flipping any scheduled order whose scheduled_for is within the next 30
// minutes to 'pending' status. Seller dashboards then see the order
// immediately and can start preparing.
func (d *Dispatcher) Start(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()

		// Run once immediately on start so we don't wait a minute on boot.
		d.sweep(ctx)

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				d.sweep(ctx)
			}
		}
	}()
}

func (d *Dispatcher) sweep(ctx context.Context) {
	result, err := d.db.Exec(ctx,
		`UPDATE orders
		   SET status = 'pending', updated_at = NOW()
		 WHERE status = 'scheduled'
		   AND scheduled_for <= NOW() + INTERVAL '30 minutes'`)
	if err != nil {
		slog.Error("scheduler sweep failed", slog.String("error", err.Error()))
		return
	}
	if n := result.RowsAffected(); n > 0 {
		slog.Info("scheduler dispatched orders", slog.Int64("count", n))
	}
}
