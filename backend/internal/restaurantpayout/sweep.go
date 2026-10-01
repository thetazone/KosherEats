package restaurantpayout

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/payments"
)

// Stripe is the slice of *payments.Client the sweep uses. An interface so the
// sweep can be exercised against a fake without a Stripe key.
type Stripe interface {
	ChargeFeeForPaymentIntent(paymentIntentID string) (payments.ChargeFee, error)
	TransferToRestaurant(t payments.RestaurantTransfer) (string, error)
	ReverseRestaurantTransfer(transferID string, amountCents int, idempotencyKey string) error
	FindRestaurantTransfer(destination, orderID string, since time.Time) (string, int, error)
}

// Alerter is notify.Alerter's shape (nil-safe there; guarded here too).
type Alerter interface {
	Alert(subject, body string)
}

const (
	// batchLimit caps each sweep step per tick; every step is a blocking Stripe
	// call per row and the scheduler ticks once a minute.
	batchLimit = 20

	// maxTransferAttempts mirrors the courier payout queue: after this many
	// failed transfers the line goes to 'failed' for a human.
	maxTransferAttempts = 6

	// processingTimeout: a line claimed for transfer longer than this is assumed
	// orphaned by a crashed instance and is released (the idempotency key makes
	// the re-attempt safe).
	processingTimeout = 15 * time.Minute

	// idempotencyGuardAfter: past this age a retry can no longer trust Stripe's
	// 24h idempotency retention, so it asks Stripe whether the transfer already
	// landed first (same reasoning as scheduler.payoutIdempotencyGuardAfter).
	idempotencyGuardAfter = 20 * time.Hour

	// reconcileRetrySecs: how long a line waits when that Stripe lookup fails.
	reconcileRetrySecs = 900

	// backfillLookback bounds the "terminal order with no line" scan.
	backfillLookback = 14 * 24 * time.Hour

	// ledgerMigration is the migration that created the ledger. The backfill
	// only considers orders that reached a terminal state AFTER it was applied,
	// so deploying it never manufactures lines for historical orders the owner
	// already settled by hand.
	ledgerMigration = "063_restaurant_payouts.sql"
)

// BackoffSecs is the transfer retry schedule keyed by the upcoming attempt. The
// whole run (sum over maxTransferAttempts-1 gaps) is 13h20m — inside Stripe's
// 24h idempotency retention, so every retry under the one key is deduped.
func BackoffSecs(upcomingAttempt int) int {
	switch upcomingAttempt {
	case 1:
		return 300
	case 2:
		return 900
	case 3:
		return 3600
	default:
		return 21600
	}
}

// RetryHorizon is the span of a full retry run.
func RetryHorizon() time.Duration {
	var total time.Duration
	for attempt := 1; attempt < maxTransferAttempts; attempt++ {
		total += time.Duration(BackoffSecs(attempt)) * time.Second
	}
	return total
}

// TransferIdempotencyKey is derived from the ledger line id — one key per line
// for its whole life, replayed with the frozen amount on every retry.
func TransferIdempotencyKey(lineID string) string { return "restaurant_payout:" + lineID }

// ReversalIdempotencyKey is stable per reversal STEP: the cumulative clawback
// target is part of the key, so a retry of the same step dedupes while a later,
// larger refund gets its own reversal.
func ReversalIdempotencyKey(lineID string, targetCents int) string {
	return "restaurant_reversal:" + lineID + ":" + strconv.Itoa(targetCents)
}

// Processor runs the restaurant payout sweep.
type Processor struct {
	db        *pgxpool.Pool
	stripe    Stripe
	enabled   bool
	markupFor MarkupFunc
	alerter   Alerter

	alertMu sync.Mutex
	alerted map[string]bool
}

// NewProcessor wires the sweep. enabled is RESTAURANT_PAYOUTS_ENABLED: when
// false every bookkeeping step still runs (lines, fees, refunds) but no
// transfer is ever made.
func NewProcessor(db *pgxpool.Pool, s Stripe, enabled bool, markupFor MarkupFunc, a Alerter) *Processor {
	return &Processor{db: db, stripe: s, enabled: enabled, markupFor: markupFor, alerter: a, alerted: map[string]bool{}}
}

// Enabled reports whether transfers are switched on.
func (p *Processor) Enabled() bool { return p != nil && p.enabled }

// RecordOrder records the ledger line for an order on the pool (used by the
// scheduler's own terminal transition) and logs a failure for the backfill.
func (p *Processor) RecordOrder(ctx context.Context, site, orderID string) {
	if p == nil {
		return
	}
	_, err := RecordLine(ctx, p.db, orderID, p.markupFor)
	LogRecordError(site, orderID, err)
}

// Sweep runs every step once. Called from the scheduler tick, which already
// holds the cross-instance advisory lock.
func (p *Processor) Sweep(ctx context.Context) {
	if p == nil || p.db == nil || p.stripe == nil {
		return
	}
	p.BackfillMissingLines(ctx)
	p.ResolveProcessingFees(ctx)
	p.SyncAccountReadiness(ctx)
	p.ReconcileRefunds(ctx)
	if p.enabled {
		p.reapStuckTransfers(ctx)
		p.TransferDue(ctx)
	}
}

func (p *Processor) alert(subject, body string) {
	if p.alerter == nil {
		slog.Warn("admin-alert (no alerter wired)", slog.String("subject", subject), slog.String("body", body))
		return
	}
	p.alerter.Alert(subject, body)
}

// alertOnce alerts at most once per key per process, for conditions the sweep
// re-detects every tick until a human fixes them.
func (p *Processor) alertOnce(key, subject, body string) {
	p.alertMu.Lock()
	seen := p.alerted[key]
	p.alerted[key] = true
	p.alertMu.Unlock()
	if !seen {
		p.alert(subject, body)
	}
}

// BackfillMissingLines records the line for any order that reached a
// successful terminal state without one — a terminal-state hook that failed,
// or a future transition site that forgot the hook.
func (p *Processor) BackfillMissingLines(ctx context.Context) {
	rows, err := p.db.Query(ctx, `
		SELECT o.id
		  FROM orders o
		 WHERE o.status IN ('delivered', 'completed')
		   AND o.created_at > NOW() - make_interval(secs => $1)
		   AND COALESCE(o.delivered_at, o.updated_at) >=
		       (SELECT applied_at FROM schema_migrations WHERE name = $2)
		   AND NOT EXISTS (SELECT 1 FROM restaurant_payout_lines l WHERE l.order_id = o.id)
		 ORDER BY o.created_at
		 LIMIT $3`,
		int(backfillLookback.Seconds()), ledgerMigration, batchLimit)
	if err != nil {
		slog.Error("restaurant-payout: backfill scan failed", slog.String("error", err.Error()))
		return
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		created, err := RecordLine(ctx, p.db, id, p.markupFor)
		if err != nil {
			slog.Error("restaurant-payout: backfill record failed",
				slog.String("order_id", id), slog.String("error", err.Error()))
			continue
		}
		if created {
			slog.Warn("restaurant-payout: backfilled a ledger line a terminal-state hook missed",
				slog.String("order_id", id))
		}
	}
}

type unresolvedLine struct {
	id, orderID, restaurantID, paymentIntentID string
	fulfillment                                Fulfillment
	food, tax, delivery, tip                   int
}

// ResolveProcessingFees reads each new line's charge from Stripe: the charge
// id (the transfer's source_transaction), the actual processing fee, and any
// refund that happened before the line existed. The net is recomputed with the
// real fee; only then is the line eligible for transfer.
func (p *Processor) ResolveProcessingFees(ctx context.Context) {
	rows, err := p.db.Query(ctx, `
		SELECT id, order_id, restaurant_id, payment_intent_id, fulfillment,
		       food_subtotal_cents, sales_tax_cents, delivery_fee_cents, tip_cents
		  FROM restaurant_payout_lines
		 WHERE NOT processing_fee_resolved
		   AND status IN ('awaiting_account', 'pending')
		 ORDER BY created_at
		 LIMIT $1`, batchLimit)
	if err != nil {
		slog.Error("restaurant-payout: load unresolved lines failed", slog.String("error", err.Error()))
		return
	}
	var lines []unresolvedLine
	for rows.Next() {
		var l unresolvedLine
		var f string
		if err := rows.Scan(&l.id, &l.orderID, &l.restaurantID, &l.paymentIntentID, &f,
			&l.food, &l.tax, &l.delivery, &l.tip); err != nil {
			continue
		}
		l.fulfillment = Fulfillment(f)
		lines = append(lines, l)
	}
	rows.Close()

	for _, l := range lines {
		cf, err := p.stripe.ChargeFeeForPaymentIntent(l.paymentIntentID)
		if err != nil {
			if payments.IsPermanentError(err) {
				if _, uerr := p.db.Exec(ctx, `
					UPDATE restaurant_payout_lines
					   SET status = 'failed', last_error = $2, updated_at = NOW()
					 WHERE id = $1 AND NOT processing_fee_resolved`,
					l.id, "cannot read charge: "+err.Error()); uerr != nil {
					slog.Error("restaurant-payout: mark unreadable charge failed",
						slog.String("line_id", l.id), slog.String("error", uerr.Error()))
				}
				p.alert("Restaurant payout held — order charge unreadable",
					fmt.Sprintf("The restaurant payout line for order %s could not read its Stripe charge "+
						"(PaymentIntent %q) and was set to failed for review.\n\nError: %s\n",
						l.orderID, l.paymentIntentID, err.Error()))
				continue
			}
			slog.Warn("restaurant-payout: processing fee not resolvable yet, will retry",
				slog.String("line_id", l.id), slog.String("order_id", l.orderID),
				slog.String("error", err.Error()))
			continue
		}

		b := Compute(FeeInput{
			Fulfillment:                l.fulfillment,
			FoodSubtotalCents:          l.food,
			SalesTaxCents:              l.tax,
			RestaurantDeliveryFeeCents: l.delivery,
			TipCents:                   l.tip,
			ProcessingFeeCents:         cf.FeeCents,
		})
		if b.Clamped {
			p.alert("Restaurant payout clamped to $0",
				fmt.Sprintf("Order %s (restaurant %s, %s) computed a NEGATIVE restaurant net of %d cents "+
					"(food %d + tax %d + delivery %d + tip %d - KE fee %d - processing %d). It was clamped to 0; "+
					"review the order's pricing.\n",
					l.orderID, l.restaurantID, l.fulfillment, b.RawNetCents,
					b.FoodSubtotalCents, b.SalesTaxCents, b.DeliveryFeeCents, b.TipCents, b.KEFeeCents, b.ProcessingFeeCents))
		}

		var chargeArg any
		if cf.ChargeID != "" {
			chargeArg = cf.ChargeID
		}
		// A zero net has nothing to transfer: settle it now so it never waits on
		// an account or the kill switch.
		if _, err := p.db.Exec(ctx, `
			UPDATE restaurant_payout_lines
			   SET processing_fee_cents = $2,
			       processing_fee_resolved = TRUE,
			       ke_fee_cents = $3,
			       net_cents = $4,
			       charge_id = $5,
			       order_total_cents = CASE WHEN $6 > 0 THEN $6 ELSE order_total_cents END,
			       refunded_cents = GREATEST(refunded_cents, $7),
			       status = CASE WHEN $4 = 0 THEN 'paid' ELSE status END,
			       paid_at = CASE WHEN $4 = 0 THEN NOW() ELSE paid_at END,
			       updated_at = NOW()
			 WHERE id = $1 AND NOT processing_fee_resolved`,
			l.id, b.ProcessingFeeCents, b.KEFeeCents, b.NetCents, chargeArg,
			cf.AmountCents, cf.AmountRefundedCents); err != nil {
			slog.Error("restaurant-payout: store resolved processing fee failed",
				slog.String("line_id", l.id), slog.String("error", err.Error()))
		}
	}
}

// SyncAccountReadiness keeps untransferred lines' status in step with their
// restaurant's Connect account (the webhook and status endpoint do this too;
// this is the backstop).
func (p *Processor) SyncAccountReadiness(ctx context.Context) {
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines l
		   SET status = 'pending', updated_at = NOW()
		  FROM restaurants r
		 WHERE r.id = l.restaurant_id AND l.status = 'awaiting_account'
		   AND r.payout_ready AND COALESCE(r.stripe_connect_id, '') <> ''`); err != nil {
		slog.Error("restaurant-payout: promote awaiting lines failed", slog.String("error", err.Error()))
	}
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines l
		   SET status = 'awaiting_account', updated_at = NOW()
		  FROM restaurants r
		 WHERE r.id = l.restaurant_id AND l.status = 'pending'
		   AND NOT (r.payout_ready AND COALESCE(r.stripe_connect_id, '') <> '')`); err != nil {
		slog.Error("restaurant-payout: demote unready lines failed", slog.String("error", err.Error()))
	}
}

type refundLine struct {
	id, orderID, status, transferID           string
	net, refunded, total, reversed, transferd int
}

// ReconcileRefunds books customer refunds against restaurant nets.
//
//   - Not yet transferred (awaiting_account / pending / failed): nothing has
//     moved, so the clawback is just withheld — reversed_cents is set to the
//     proportional target and the payable amount shrinks; a full refund voids
//     the line.
//   - Transferred ('paid' with a transfer): a Stripe transfer reversal for the
//     difference between the new proportional target and what was already
//     clawed back; a full refund leaves the line 'reversed'.
//
// Lines mid-transfer ('processing') are left alone and reconciled once they
// settle. Orders that ended up cancelled/rejected after completion are treated
// as fully refunded.
func (p *Processor) ReconcileRefunds(ctx context.Context) {
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines l
		   SET refunded_cents = l.order_total_cents, updated_at = NOW()
		  FROM orders o
		 WHERE o.id = l.order_id AND o.status IN ('cancelled', 'rejected')
		   AND l.refunded_cents < l.order_total_cents`); err != nil {
		slog.Error("restaurant-payout: mark cancelled orders refunded failed", slog.String("error", err.Error()))
	}

	rows, err := p.db.Query(ctx, `
		SELECT id, order_id, status, COALESCE(transfer_id, ''), net_cents, refunded_cents,
		       order_total_cents, reversed_cents, transferred_cents
		  FROM restaurant_payout_lines
		 WHERE refunded_cents > refunds_applied_cents
		   AND status IN ('awaiting_account', 'pending', 'failed', 'paid')
		   -- A frozen transfer amount means an attempt was already made (or will
		   -- be retried) with that exact amount, so withholding more now could
		   -- not change what moves. Let it settle to 'paid' and reverse then.
		   AND NOT (status <> 'paid' AND transfer_amount_cents IS NOT NULL)
		 ORDER BY updated_at
		 LIMIT $1`, batchLimit)
	if err != nil {
		slog.Error("restaurant-payout: load refunded lines failed", slog.String("error", err.Error()))
		return
	}
	var lines []refundLine
	for rows.Next() {
		var l refundLine
		if err := rows.Scan(&l.id, &l.orderID, &l.status, &l.transferID, &l.net, &l.refunded,
			&l.total, &l.reversed, &l.transferd); err != nil {
			continue
		}
		lines = append(lines, l)
	}
	rows.Close()

	for _, l := range lines {
		target := ReversalTarget(l.net, l.refunded, l.total)
		if target < l.reversed {
			target = l.reversed // clawbacks only ever grow
		}
		full := l.total <= 0 || l.refunded >= l.total

		moved := l.status == StatusPaid && l.transferID != "" && l.transferd > 0
		if !moved {
			newStatus := l.status
			if full {
				newStatus = StatusVoid
			}
			if _, err := p.db.Exec(ctx, `
				UPDATE restaurant_payout_lines
				   SET reversed_cents = $2, refunds_applied_cents = $3, status = $4, updated_at = NOW()
				 WHERE id = $1 AND status = $5 AND reversed_cents = $6`,
				l.id, target, l.refunded, newStatus, l.status, l.reversed); err != nil {
				slog.Error("restaurant-payout: withhold refund failed",
					slog.String("line_id", l.id), slog.String("error", err.Error()))
			}
			continue
		}

		delta := target - l.reversed
		if delta > 0 {
			if err := p.stripe.ReverseRestaurantTransfer(l.transferID, delta, ReversalIdempotencyKey(l.id, target)); err != nil {
				if _, uerr := p.db.Exec(ctx, `
					UPDATE restaurant_payout_lines SET last_error = $2, updated_at = NOW() WHERE id = $1`,
					l.id, "reversal failed: "+err.Error()); uerr != nil {
					slog.Error("restaurant-payout: record reversal error failed", slog.String("error", uerr.Error()))
				}
				slog.Error("restaurant-payout: transfer reversal failed, will retry",
					slog.String("line_id", l.id), slog.String("transfer_id", l.transferID),
					slog.Int("amount_cents", delta), slog.String("error", err.Error()))
				p.alertOnce("reversal:"+l.id, "Restaurant payout reversal failing",
					fmt.Sprintf("A customer refund on order %s requires reversing %d cents of restaurant transfer %s, "+
						"and the reversal failed (it will keep retrying every minute).\n\nError: %s\n",
						l.orderID, delta, l.transferID, err.Error()))
				continue
			}
		}
		newStatus := StatusPaid
		if full {
			newStatus = StatusReversed
		}
		if _, err := p.db.Exec(ctx, `
			UPDATE restaurant_payout_lines
			   SET reversed_cents = $2, refunds_applied_cents = $3, status = $4,
			       last_error = '', updated_at = NOW()
			 WHERE id = $1 AND status = 'paid' AND reversed_cents = $5`,
			l.id, target, l.refunded, newStatus, l.reversed); err != nil {
			// The reversal is real; the next tick replays it under the same
			// idempotency key (Stripe dedupes) and re-attempts this write.
			slog.Error("restaurant-payout: reversal ok but recording it failed",
				slog.String("line_id", l.id), slog.String("error", err.Error()))
		}
	}
}

// reapStuckTransfers releases lines stuck in 'processing' (an instance died
// mid-transfer). Does not bump attempt_count; the idempotency key plus the
// age guard make the re-attempt safe.
func (p *Processor) reapStuckTransfers(ctx context.Context) {
	if tag, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = 'pending', updated_at = NOW()
		 WHERE status = 'processing'
		   AND updated_at < NOW() - make_interval(secs => $1)`,
		int(processingTimeout.Seconds())); err != nil {
		slog.Error("restaurant-payout: reaper failed", slog.String("error", err.Error()))
	} else if tag.RowsAffected() > 0 {
		slog.Warn("restaurant-payout: reaped stuck processing lines", slog.Int64("count", tag.RowsAffected()))
	}
}

type dueLine struct {
	id, orderID, restaurantID, connectID, chargeID string
	net, refunded, total, reversed, attempts       int
	frozenAmount                                   *int
	createdAt                                      time.Time
}

// TransferDue claims due lines and transfers each restaurant's net. Callers
// gate it on Enabled(); it is exported for tests.
func (p *Processor) TransferDue(ctx context.Context) {
	rows, err := p.db.Query(ctx, `
		UPDATE restaurant_payout_lines l
		   SET status = 'processing', stripe_connect_id = r.stripe_connect_id, updated_at = NOW()
		  FROM restaurants r
		 WHERE r.id = l.restaurant_id
		   AND l.id IN (
		       SELECT l2.id
		         FROM restaurant_payout_lines l2
		         JOIN restaurants r2 ON r2.id = l2.restaurant_id
		        WHERE l2.status = 'pending'
		          AND l2.processing_fee_resolved
		          AND l2.next_retry_at <= NOW()
		          AND r2.payout_ready AND COALESCE(r2.stripe_connect_id, '') <> ''
		        ORDER BY l2.next_retry_at
		        LIMIT $1
		        FOR UPDATE OF l2 SKIP LOCKED)
		RETURNING l.id, l.order_id, l.restaurant_id, l.stripe_connect_id, COALESCE(l.charge_id, ''),
		          l.net_cents, l.refunded_cents, l.order_total_cents, l.reversed_cents,
		          l.attempt_count, l.transfer_amount_cents, l.created_at`, batchLimit)
	if err != nil {
		slog.Error("restaurant-payout: claim due lines failed", slog.String("error", err.Error()))
		return
	}
	var due []dueLine
	for rows.Next() {
		var d dueLine
		if err := rows.Scan(&d.id, &d.orderID, &d.restaurantID, &d.connectID, &d.chargeID,
			&d.net, &d.refunded, &d.total, &d.reversed, &d.attempts, &d.frozenAmount, &d.createdAt); err != nil {
			slog.Error("restaurant-payout: scan claimed line failed", slog.String("error", err.Error()))
			continue
		}
		due = append(due, d)
	}
	rows.Close()

	for _, d := range due {
		p.transferOne(ctx, d)
	}
}

func (p *Processor) transferOne(ctx context.Context, d dueLine) {
	amount := 0
	if d.frozenAmount != nil {
		amount = *d.frozenAmount
	} else {
		// First attempt: fold in any refund booked so far, then freeze the
		// amount so every retry replays exactly this request.
		target := ReversalTarget(d.net, d.refunded, d.total)
		if target < d.reversed {
			target = d.reversed
		}
		if d.refunded > 0 && (d.total <= 0 || d.refunded >= d.total) {
			p.settleWithoutTransfer(ctx, d.id, StatusVoid, target, d.refunded)
			return
		}
		amount = d.net - target
		if amount <= 0 {
			p.settleWithoutTransfer(ctx, d.id, StatusPaid, target, d.refunded)
			return
		}
		if _, err := p.db.Exec(ctx, `
			UPDATE restaurant_payout_lines
			   SET transfer_amount_cents = $2, reversed_cents = $3, refunds_applied_cents = $4, updated_at = NOW()
			 WHERE id = $1 AND status = 'processing' AND transfer_amount_cents IS NULL`,
			d.id, amount, target, d.refunded); err != nil {
			slog.Error("restaurant-payout: freeze transfer amount failed; releasing claim",
				slog.String("line_id", d.id), slog.String("error", err.Error()))
			p.release(ctx, d.id, 60)
			return
		}
	}

	// Past Stripe's idempotency retention the key can no longer be trusted to
	// dedupe a retry whose earlier attempt actually landed — ask Stripe first.
	if !d.createdAt.IsZero() && time.Since(d.createdAt) >= idempotencyGuardAfter {
		trID, trAmount, err := p.stripe.FindRestaurantTransfer(d.connectID, d.orderID, d.createdAt)
		if err != nil {
			slog.Error("restaurant-payout: cannot verify whether transfer already landed; skipping attempt",
				slog.String("line_id", d.id), slog.String("error", err.Error()))
			p.release(ctx, d.id, reconcileRetrySecs)
			return
		}
		if trID != "" {
			slog.Warn("restaurant-payout: transfer already exists at Stripe; reconciling line to paid",
				slog.String("line_id", d.id), slog.String("transfer_id", trID))
			p.markPaid(ctx, d.id, trID, trAmount)
			return
		}
	}

	trID, err := p.stripe.TransferToRestaurant(payments.RestaurantTransfer{
		AccountID:      d.connectID,
		AmountCents:    amount,
		OrderID:        d.orderID,
		RestaurantID:   d.restaurantID,
		ChargeID:       d.chargeID,
		IdempotencyKey: TransferIdempotencyKey(d.id),
	})
	if err == nil {
		p.markPaid(ctx, d.id, trID, amount)
		slog.Info("restaurant-payout: transfer succeeded",
			slog.String("line_id", d.id), slog.String("order_id", d.orderID),
			slog.Int("amount_cents", amount), slog.String("transfer_id", trID))
		return
	}

	next := d.attempts + 1
	status, backoff := StatusPending, BackoffSecs(next)
	if next >= maxTransferAttempts {
		status, backoff = StatusFailed, 0
		p.alert("Restaurant payout failed permanently — admin review required",
			fmt.Sprintf("A restaurant payout exhausted all %d attempts and is now failed.\n\n"+
				"Ledger line: %s\nOrder: %s\nRestaurant: %s\nConnect account: %s\nAmount (cents): %d\nLast error: %s\n",
				maxTransferAttempts, d.id, d.orderID, d.restaurantID, d.connectID, amount, err.Error()))
	} else {
		slog.Warn("restaurant-payout: transfer failed, will retry",
			slog.String("line_id", d.id), slog.Int("attempt", next),
			slog.Int("retry_in_secs", backoff), slog.String("error", err.Error()))
	}
	if _, uerr := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = $2, attempt_count = $3, last_error = $4,
		       next_retry_at = NOW() + make_interval(secs => $5), updated_at = NOW()
		 WHERE id = $1 AND status = 'processing'`,
		d.id, status, next, err.Error(), backoff); uerr != nil {
		slog.Error("restaurant-payout: record retry state failed",
			slog.String("line_id", d.id), slog.String("error", uerr.Error()))
	}
}

func (p *Processor) markPaid(ctx context.Context, lineID, transferID string, amount int) {
	// 'pending' too: the reaper may have released the claim while the transfer
	// was still in flight; the transfer is real either way.
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = 'paid', transfer_id = $2, transferred_cents = $3, paid_at = NOW(),
		       attempt_count = attempt_count + 1, last_error = '', updated_at = NOW()
		 WHERE id = $1 AND status IN ('processing', 'pending')`,
		lineID, transferID, amount); err != nil {
		slog.Error("restaurant-payout: transfer succeeded but marking paid failed — the idempotency key covers the retry",
			slog.String("line_id", lineID), slog.String("transfer_id", transferID), slog.String("error", err.Error()))
	}
}

func (p *Processor) settleWithoutTransfer(ctx context.Context, lineID, status string, reversed, refunded int) {
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = $2, reversed_cents = $3, refunds_applied_cents = $4,
		       paid_at = CASE WHEN $2 = 'paid' THEN NOW() ELSE paid_at END, updated_at = NOW()
		 WHERE id = $1 AND status = 'processing'`, lineID, status, reversed, refunded); err != nil {
		slog.Error("restaurant-payout: settle without transfer failed",
			slog.String("line_id", lineID), slog.String("error", err.Error()))
	}
}

func (p *Processor) release(ctx context.Context, lineID string, afterSecs int) {
	if _, err := p.db.Exec(ctx, `
		UPDATE restaurant_payout_lines
		   SET status = 'pending', next_retry_at = NOW() + make_interval(secs => $2), updated_at = NOW()
		 WHERE id = $1 AND status = 'processing'`, lineID, afterSecs); err != nil {
		slog.Error("restaurant-payout: release claim failed",
			slog.String("line_id", lineID), slog.String("error", err.Error()))
	}
}
