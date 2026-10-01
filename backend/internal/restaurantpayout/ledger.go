package restaurantpayout

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// DBTX is the subset of pgx shared by *pgxpool.Pool, *pgx.Conn and pgx.Tx, so
// the ledger insert runs inside a caller's transaction or on the pool alike.
type DBTX interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// MarkupFunc returns the KosherEats delivery markup for an item subtotal under
// the live tiers (config.DeliveryMarkupFor). Only used for orders created
// before checkout started freezing the markup (migration 058).
type MarkupFunc func(subtotalCents int) int

// Line statuses. 'processing' is internal (a transfer is in flight) and is
// reported to clients as 'pending'.
const (
	StatusAwaitingAccount = "awaiting_account"
	StatusPending         = "pending"
	StatusProcessing      = "processing"
	StatusPaid            = "paid"
	StatusFailed          = "failed"
	StatusReversed        = "reversed"
	StatusVoid            = "void"
)

// PublicStatus maps an internal line status to the API contract.
func PublicStatus(s string) string {
	if s == StatusProcessing {
		return StatusPending
	}
	return s
}

const loadOrderForLineSQL = `
SELECT o.restaurant_id, o.status, o.fulfillment_type,
       COALESCE(o.delivery_mode, r.delivery_mode, 'platform'),
       o.courier_id IS NOT NULL,
       (COALESCE(o.external_provider, '') <> '' OR COALESCE(o.external_delivery_id, '') <> ''),
       o.subtotal, COALESCE(o.discount_cents, 0), COALESCE(o.tax, 0),
       COALESCE(o.delivery_fee, 0), o.delivery_markup_cents, COALESCE(o.courier_tip, 0),
       o.total, COALESCE(o.stripe_payment_id, ''),
       CASE WHEN o.status = 'delivered' THEN COALESCE(o.delivered_at, o.updated_at) ELSE o.updated_at END,
       (r.payout_ready AND COALESCE(r.stripe_connect_id, '') <> '')
  FROM orders o
  JOIN restaurants r ON r.id = o.restaurant_id
 WHERE o.id = $1`

// RecordLine idempotently inserts the payout ledger line for an order that has
// reached its successful terminal state ('delivered', or 'completed' for
// pickup). It returns (true, nil) when it inserted the line, (false, nil) when
// the line already existed or the order is not in a successful terminal state.
//
// Every terminal-state site calls this; the UNIQUE(order_id) constraint plus
// ON CONFLICT DO NOTHING makes repeats (replayed webhooks, the backfill sweep)
// harmless. The processing fee is resolved later by the sweep (a Stripe read
// has no place inside a webhook or a delivery transaction).
func RecordLine(ctx context.Context, db DBTX, orderID string, markupFor MarkupFunc) (bool, error) {
	var (
		restaurantID, status, fulfillmentType, deliveryMode string
		hasCourier, hasExternal, accountReady               bool
		subtotal, discount, tax, deliveryFee, tip, total    int
		stampedMarkup                                       *int
		paymentIntentID                                     string
		completedAt                                         time.Time
	)
	err := db.QueryRow(ctx, loadOrderForLineSQL, orderID).Scan(
		&restaurantID, &status, &fulfillmentType, &deliveryMode,
		&hasCourier, &hasExternal,
		&subtotal, &discount, &tax, &deliveryFee, &stampedMarkup, &tip,
		&total, &paymentIntentID, &completedAt, &accountReady)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("load order %s for payout line: %w", orderID, err)
	}
	if status != "delivered" && status != "completed" {
		return false, nil
	}

	class := Classify(fulfillmentType, deliveryMode, hasCourier, hasExternal)
	in := FeeInput{
		Fulfillment:       class,
		FoodSubtotalCents: FoodSubtotal(subtotal, discount, DealsRestaurantFunded),
		SalesTaxCents:     tax,
	}
	if class == SelfDelivery {
		markup := 0
		if stampedMarkup != nil {
			markup = *stampedMarkup
		} else if markupFor != nil {
			markup = markupFor(subtotal)
		}
		in.RestaurantDeliveryFeeCents = RestaurantDeliveryShare(deliveryFee, markup)
		in.TipCents = tip
	}
	// Processing is unknown until the sweep reads the charge; the net is
	// provisional until then and the line cannot be transferred.
	b := Compute(in)
	if b.Clamped {
		slog.Error("restaurant payout: net clamped to zero at record time",
			slog.String("order_id", orderID), slog.Int("raw_net_cents", b.RawNetCents))
	}

	lineStatus := StatusAwaitingAccount
	if accountReady {
		lineStatus = StatusPending
	}

	tag, err := db.Exec(ctx, `
		INSERT INTO restaurant_payout_lines
		    (order_id, restaurant_id, fulfillment, food_subtotal_cents, sales_tax_cents,
		     delivery_fee_cents, tip_cents, ke_fee_cents, processing_fee_cents, net_cents,
		     order_total_cents, payment_intent_id, status, completed_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 0, $9, $10, $11, $12, $13)
		ON CONFLICT (order_id) DO NOTHING`,
		orderID, restaurantID, string(class), b.FoodSubtotalCents, b.SalesTaxCents,
		b.DeliveryFeeCents, b.TipCents, b.KEFeeCents, b.NetCents,
		total, paymentIntentID, lineStatus, completedAt)
	if err != nil {
		return false, fmt.Errorf("insert payout line for order %s: %w", orderID, err)
	}
	return tag.RowsAffected() == 1, nil
}

// RecordLineInTx runs RecordLine inside the caller's transaction under a
// SAVEPOINT, so the line commits atomically with the terminal status flip, but
// a ledger failure can never abort the delivery transaction itself (the
// courier/webhook transition must still land; the backfill sweep then records
// the missed line).
func RecordLineInTx(ctx context.Context, tx pgx.Tx, orderID string, markupFor MarkupFunc) (bool, error) {
	sp, err := tx.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("savepoint for payout line: %w", err)
	}
	created, err := RecordLine(ctx, sp, orderID, markupFor)
	if err != nil {
		_ = sp.Rollback(ctx)
		return false, err
	}
	if err := sp.Commit(ctx); err != nil {
		return false, fmt.Errorf("release savepoint for payout line: %w", err)
	}
	return created, nil
}

// LogRecordError is the shared "the transition landed but its ledger line did
// not" log line for terminal-state call sites.
func LogRecordError(site, orderID string, err error) {
	if err == nil {
		return
	}
	slog.Error("restaurant payout: failed to record ledger line — the backfill sweep will retry",
		slog.String("site", site), slog.String("order_id", orderID), slog.String("error", err.Error()))
}

// SyncRestaurantLines moves a restaurant's untransferred lines between
// 'awaiting_account' and 'pending' to match its account readiness. Called when
// readiness changes (status refresh, account.updated webhook) and by the sweep.
func SyncRestaurantLines(ctx context.Context, db DBTX, restaurantID string, ready bool) error {
	from, to := StatusPending, StatusAwaitingAccount
	if ready {
		from, to = StatusAwaitingAccount, StatusPending
	}
	_, err := db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = $3, updated_at = NOW()
		 WHERE restaurant_id = $1 AND status = $2`, restaurantID, from, to)
	return err
}
