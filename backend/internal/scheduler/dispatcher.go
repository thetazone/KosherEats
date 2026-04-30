// Package scheduler runs small in-process background loops. Four sweeps run
// every minute off the same ticker:
//
//  1. sweepScheduled — promotes future-dated orders to 'pending' 30 minutes
//     before their delivery window, so seller dashboards can start prepping.
//
//  2. sweepAutoDispatch — safety net for the courier marketplace. When a
//     seller marks an order 'ready' we broadcast to all online couriers and
//     wait for one to self-claim. If no courier has claimed after a short
//     grace period (autoDispatchGrace), the dispatcher picks the nearest
//     online, approved, unbusy courier and assigns them directly. This
//     prevents an order from sitting unclaimed indefinitely.
//
//  3. sweepStaleRejection — the symmetric safety net on the seller side.
//     If a seller doesn't accept/reject a 'pending' order within
//     pendingOrderTTL, we auto-reject it and refund the customer so their
//     money doesn't sit in limbo and they can reorder from somewhere else.
//
//  4. sweepCourierPayouts — durable processor for the courier_payout_queue.
//     Delivery handlers enqueue rows; this sweep attempts the Stripe
//     Connect transfer and retries with exponential backoff on failure.
//     Replaces the old fire-and-forget goroutine that silently dropped
//     failed payouts.
//
// In-process is fine for a single-instance deploy. If we ever scale to
// multiple API instances we'll need a distributed lock (Redis or
// pg_advisory_lock) so only one node runs the sweeps.
package scheduler

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/notify"
	"github.com/koshereats/backend/internal/payments"
	"github.com/koshereats/backend/internal/uberdirect"
)

// autoDispatchGrace is how long an order may sit in 'ready' with no courier
// claimed before the dispatcher auto-assigns one. This gives couriers a
// chance to self-claim from the marketplace broadcast first, preserving the
// feel of a driver-driven system while guaranteeing no ghost orders.
const autoDispatchGrace = 2 * time.Minute

// autoDispatchBatchLimit caps how many stale orders we process per sweep.
// Prevents a backlog from blocking the sweep loop or fanning out too many
// push notifications at once.
const autoDispatchBatchLimit = 20

// pendingOrderTTL is how long an order may sit in 'pending' without seller
// action before we auto-reject and refund. Keep this tight — cold food is
// the enemy. Sellers who don't respond this fast shouldn't be taking orders.
const pendingOrderTTL = 10 * time.Minute

// externalDispatchGrace is the total time an order sits in 'ready' before we
// fall back to an external courier (Uber Direct). Must be longer than
// autoDispatchGrace to give own-fleet assignment a chance first.
const externalDispatchGrace = 5 * time.Minute

// staleRejectionBatchLimit caps orders auto-rejected per sweep. Protects
// Stripe from a burst of refund requests if the API was down for a while.
const staleRejectionBatchLimit = 20

// payoutBatchLimit caps how many queued payouts we try per sweep tick. Each
// try is a blocking Stripe API call (~500ms) so with 60s between ticks we
// want comfortable headroom. 20 keeps us well under a minute in the worst case.
const payoutBatchLimit = 20

// maxPayoutAttempts is the number of failed transfer attempts before the
// queue row flips to 'failed_permanent' for admin review. After this many
// tries, either Stripe has a real problem with the courier's account or
// something structural is wrong — human intervention beats infinite retry.
const maxPayoutAttempts = 6

type Dispatcher struct {
	db       *pgxpool.Pool
	notify   *notify.Notifier
	stripe   *payments.Client
	uber     *uberdirect.Client
	doordash *doordash.Client
}

func New(db *pgxpool.Pool, n *notify.Notifier, s *payments.Client, u *uberdirect.Client, dd *doordash.Client) *Dispatcher {
	return &Dispatcher{db: db, notify: n, stripe: s, uber: u, doordash: dd}
}

// Start launches a goroutine that runs both sweeps every minute. Runs once
// immediately on boot so we don't wait a full tick on startup.
func (d *Dispatcher) Start(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()

		d.runAll(ctx)

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				d.runAll(ctx)
			}
		}
	}()
}

func (d *Dispatcher) runAll(ctx context.Context) {
	d.sweepScheduled(ctx)
	d.sweepAutoDispatch(ctx)
	d.sweepStaleRejection(ctx)
	d.sweepCourierPayouts(ctx)
}

// sweepScheduled flips scheduled orders into 'pending' 30 minutes before
// their delivery window so seller dashboards pick them up.
func (d *Dispatcher) sweepScheduled(ctx context.Context) {
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

// staleOrder is the minimum projection of an unclaimed 'ready' order that
// the auto-dispatch sweep needs to find a courier and fire push payloads.
type staleOrder struct {
	orderID         string
	consumerID      string
	restaurantID    string
	restaurantName  string
	restLat         float64
	restLng         float64
	restAddress     string
	restPhone       string
	deliveryAddress string
	deliveryLat     float64
	deliveryLng     float64
	customerName    string
	customerPhone   string
	subtotal        int
	tipCents        int
	payout          int
	updatedAt       time.Time
	hasExternal     bool
	deliveryMode    string
}

// sweepAutoDispatch finds orders that have been 'ready' without a courier
// for longer than autoDispatchGrace and assigns each to the nearest eligible
// courier. Uses the existing idx_courier_profiles_location GiST index for
// the distance lookup. Safe to run concurrently with manual ClaimOrder
// because the assignment UPDATE is CAS-style on courier_id IS NULL.
func (d *Dispatcher) sweepAutoDispatch(ctx context.Context) {
	rows, err := d.db.Query(ctx, `
		SELECT o.id, o.user_id, o.restaurant_id, rest.name, rest.lat, rest.lng,
		       COALESCE(rest.street || ', ' || rest.city || ', ' || rest.state || ' ' || rest.zip_code, ''),
		       COALESCE(rest.phone, ''),
		       COALESCE(o.delivery_address, ''), o.delivery_lat, o.delivery_lng,
		       COALESCE(u.first_name || ' ' || u.last_name, ''),
		       COALESCE(u.phone, ''),
		       o.subtotal, COALESCE(o.courier_tip, 0),
		       o.delivery_fee + COALESCE(o.courier_tip, 0) AS payout,
		       o.updated_at,
		       o.external_delivery_id IS NOT NULL AS has_external,
		       COALESCE(rest.delivery_mode, 'platform')
		  FROM orders o
		  JOIN restaurants rest ON rest.id = o.restaurant_id
		  JOIN users u ON u.id = o.user_id
		 WHERE o.status = 'ready'
		   AND o.courier_id IS NULL
		   AND o.fulfillment_type = 'delivery'
		   AND o.updated_at < NOW() - make_interval(secs => $1)
		 ORDER BY o.updated_at ASC
		 LIMIT $2`,
		int(autoDispatchGrace.Seconds()), autoDispatchBatchLimit)
	if err != nil {
		slog.Error("auto-dispatch: load stale orders failed",
			slog.String("error", err.Error()))
		return
	}

	var stale []staleOrder
	for rows.Next() {
		var o staleOrder
		if err := rows.Scan(&o.orderID, &o.consumerID, &o.restaurantID,
			&o.restaurantName, &o.restLat, &o.restLng,
			&o.restAddress, &o.restPhone,
			&o.deliveryAddress, &o.deliveryLat, &o.deliveryLng,
			&o.customerName, &o.customerPhone,
			&o.subtotal, &o.tipCents, &o.payout,
			&o.updatedAt, &o.hasExternal, &o.deliveryMode); err != nil {
			slog.Error("auto-dispatch: scan failed",
				slog.String("error", err.Error()))
			continue
		}
		stale = append(stale, o)
	}
	rows.Close()

	for _, o := range stale {
		d.tryAutoAssign(ctx, o)
	}
}

// tryAutoAssign picks the nearest free online courier for a single order and
// atomically claims it on their behalf. If no courier is available this is a
// quiet no-op — the next sweep will retry.
func (d *Dispatcher) tryAutoAssign(ctx context.Context, o staleOrder) {
	if o.deliveryMode == "restaurant" {
		return
	}

	if o.deliveryMode == "external" {
		d.tryExternalDispatch(ctx, o)
		return
	}

	var courierID, courierFirstName string
	err := d.db.QueryRow(ctx, `
		SELECT cp.user_id, u.first_name
		  FROM courier_profiles cp
		  JOIN users u ON u.id = cp.user_id
		 WHERE cp.is_online = true
		   AND cp.onboarding_status = 'approved'
		   AND u.role = 'courier'
		   AND NOT EXISTS (
		     SELECT 1 FROM orders o2
		      WHERE o2.courier_id = cp.user_id
		        AND o2.status IN ('ready', 'picked_up')
		   )
		 ORDER BY point(cp.last_lng, cp.last_lat) <-> point($1, $2)
		 LIMIT 1`,
		o.restLng, o.restLat).Scan(&courierID, &courierFirstName)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			d.tryExternalDispatch(ctx, o)
			return
		}
		slog.Error("auto-dispatch: courier lookup failed",
			slog.String("order_id", o.orderID),
			slog.String("error", err.Error()))
		return
	}

	// Atomic claim: only succeeds if nobody self-claimed between the stale
	// query and now. Mirrors the CAS in handlers.ClaimOrder so the two paths
	// can't double-assign.
	result, err := d.db.Exec(ctx, `
		UPDATE orders
		   SET courier_id = $1, claimed_at = NOW(), updated_at = NOW()
		 WHERE id = $2 AND status = 'ready' AND courier_id IS NULL`,
		courierID, o.orderID)
	if err != nil {
		slog.Error("auto-dispatch: claim update failed",
			slog.String("order_id", o.orderID),
			slog.String("courier_id", courierID),
			slog.String("error", err.Error()))
		return
	}
	if result.RowsAffected() == 0 {
		// Someone beat us to it in the interval between sweep and assign;
		// not an error, just the marketplace working as intended.
		return
	}

	slog.Info("auto-dispatch: order assigned",
		slog.String("order_id", o.orderID),
		slog.String("courier_id", courierID))

	if d.notify != nil {
		// Same consumer + seller notification a manual claim would fire.
		d.notify.OrderClaimed(ctx, o.orderID, o.consumerID, o.restaurantID, courierFirstName)
		// Direct push to the assigned courier since they didn't tap claim.
		d.notify.CourierAutoAssigned(ctx, o.orderID, courierID, o.restaurantName, o.payout)
	}
}

// tryExternalDispatch gets quotes from available external providers (Uber
// Direct, DoorDash Drive) and dispatches with the cheapest one. Waits until
// externalDispatchGrace has passed to give the own fleet maximum time.
func (d *Dispatcher) tryExternalDispatch(ctx context.Context, o staleOrder) {
	if o.hasExternal {
		return
	}

	uberEnabled := d.uber != nil && d.uber.Enabled()
	ddEnabled := d.doordash != nil && d.doordash.Enabled()
	if !uberEnabled && !ddEnabled {
		slog.Info("auto-dispatch: no eligible courier and no external provider configured",
			slog.String("order_id", o.orderID))
		return
	}

	if time.Since(o.updatedAt) < externalDispatchGrace {
		slog.Info("auto-dispatch: no own courier yet, waiting for external grace period",
			slog.String("order_id", o.orderID))
		return
	}

	type externalQuote struct {
		provider    string
		feeCents    int
		uberQuoteID string
	}
	var quotes []externalQuote

	if uberEnabled {
		pickup := uberdirect.Address{
			Street: []string{o.restAddress}, Country: "US",
		}
		dropoff := uberdirect.Address{
			Street: []string{o.deliveryAddress}, Country: "US",
		}
		q, err := d.uber.GetQuote(ctx, pickup, dropoff)
		if err != nil {
			slog.Warn("external-dispatch: uber quote failed",
				slog.String("order_id", o.orderID),
				slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, externalQuote{
				provider: "uber_direct", feeCents: q.Fee, uberQuoteID: q.ID,
			})
		}
	}

	if ddEnabled {
		q, err := d.doordash.GetQuote(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: o.orderID + "_quote",
			PickupAddress:      o.restAddress,
			PickupBusinessName: o.restaurantName,
			PickupPhone:        o.restPhone,
			DropoffAddress:     o.deliveryAddress,
			DropoffContactName: o.customerName,
			DropoffPhone:       o.customerPhone,
			OrderValue:         o.subtotal,
		})
		if err != nil {
			slog.Warn("external-dispatch: doordash quote failed",
				slog.String("order_id", o.orderID),
				slog.String("error", err.Error()))
		} else {
			quotes = append(quotes, externalQuote{
				provider: "doordash_drive", feeCents: q.Fee,
			})
		}
	}

	if len(quotes) == 0 {
		slog.Error("external-dispatch: all providers failed",
			slog.String("order_id", o.orderID))
		return
	}

	// Pick cheapest.
	best := quotes[0]
	for _, q := range quotes[1:] {
		if q.feeCents < best.feeCents {
			best = q
		}
	}

	slog.Info("external-dispatch: cheapest quote selected",
		slog.String("order_id", o.orderID),
		slog.String("provider", best.provider),
		slog.Int("fee_cents", best.feeCents))

	var deliveryID, trackingURL string
	var fee int

	switch best.provider {
	case "uber_direct":
		pickup := uberdirect.Address{
			Street: []string{o.restAddress}, Country: "US",
		}
		dropoff := uberdirect.Address{
			Street: []string{o.deliveryAddress}, Country: "US",
		}
		del, err := d.uber.CreateDelivery(ctx, uberdirect.CreateDeliveryRequest{
			QuoteID:        best.uberQuoteID,
			ExternalID:     o.orderID,
			PickupName:     o.restaurantName,
			PickupAddress:  pickup,
			PickupPhone:    o.restPhone,
			DropoffName:    o.customerName,
			DropoffAddress: dropoff,
			DropoffPhone:   o.customerPhone,
			TotalCents:     o.subtotal,
			TipCents:       o.tipCents,
			Items: []uberdirect.ManifestItem{
				{Name: "Food order from " + o.restaurantName, Quantity: 1, Price: o.subtotal},
			},
		})
		if err != nil {
			slog.Error("external-dispatch: uber create failed",
				slog.String("order_id", o.orderID),
				slog.String("error", err.Error()))
			return
		}
		deliveryID = del.ID
		trackingURL = del.TrackingURL
		fee = del.Fee

	case "doordash_drive":
		del, err := d.doordash.CreateDelivery(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: o.orderID,
			PickupAddress:      o.restAddress,
			PickupBusinessName: o.restaurantName,
			PickupPhone:        o.restPhone,
			DropoffAddress:     o.deliveryAddress,
			DropoffContactName: o.customerName,
			DropoffPhone:       o.customerPhone,
			OrderValue:         o.subtotal,
			TipCents:           o.tipCents,
		})
		if err != nil {
			slog.Error("external-dispatch: doordash create failed",
				slog.String("order_id", o.orderID),
				slog.String("error", err.Error()))
			return
		}
		deliveryID = del.ExternalDeliveryID
		trackingURL = del.TrackingURL
		fee = del.Fee
	}

	_, err := d.db.Exec(ctx, `
		UPDATE orders
		   SET external_delivery_id = $1,
		       external_provider = $2,
		       external_tracking_url = $3,
		       updated_at = NOW()
		 WHERE id = $4 AND courier_id IS NULL`,
		deliveryID, best.provider, trackingURL, o.orderID)
	if err != nil {
		slog.Error("external-dispatch: db update failed",
			slog.String("order_id", o.orderID),
			slog.String("error", err.Error()))
		return
	}

	slog.Info("external-dispatch: delivery created",
		slog.String("order_id", o.orderID),
		slog.String("provider", best.provider),
		slog.String("delivery_id", deliveryID),
		slog.Int("fee_cents", fee))
}

// stalePending is the projection of a pending order that overshot the SLA.
type stalePending struct {
	orderID         string
	consumerID      string
	restaurantName  string
	paymentIntentID string
}

// sweepStaleRejection finds 'pending' orders that the seller never acted on
// within pendingOrderTTL, auto-rejects them, and refunds the customer. We do
// this one-at-a-time (not a bulk UPDATE) because each rejection has a side
// effect — a Stripe refund — and we only want to flip the DB row once the
// refund succeeds, so a partial failure is recoverable.
func (d *Dispatcher) sweepStaleRejection(ctx context.Context) {
	rows, err := d.db.Query(ctx, `
		SELECT o.id, o.user_id, rest.name, COALESCE(o.stripe_payment_id, '')
		  FROM orders o
		  JOIN restaurants rest ON rest.id = o.restaurant_id
		 WHERE o.status = 'pending'
		   AND o.created_at < NOW() - make_interval(secs => $1)
		 ORDER BY o.created_at ASC
		 LIMIT $2`,
		int(pendingOrderTTL.Seconds()), staleRejectionBatchLimit)
	if err != nil {
		slog.Error("stale-rejection: load pending orders failed",
			slog.String("error", err.Error()))
		return
	}

	var stale []stalePending
	for rows.Next() {
		var o stalePending
		if err := rows.Scan(&o.orderID, &o.consumerID, &o.restaurantName, &o.paymentIntentID); err != nil {
			slog.Error("stale-rejection: scan failed",
				slog.String("error", err.Error()))
			continue
		}
		stale = append(stale, o)
	}
	rows.Close()

	for _, o := range stale {
		d.tryStaleReject(ctx, o)
	}
}

// tryStaleReject refunds an order's payment first, then flips status to
// 'rejected' only on refund success. Uses SELECT ... FOR UPDATE inside a
// transaction to prevent the race where a seller AcceptOrder flips the
// status between our read and the refund.
func (d *Dispatcher) tryStaleReject(ctx context.Context, o stalePending) {
	tx, err := d.db.Begin(ctx)
	if err != nil {
		slog.Error("stale-rejection: begin tx failed",
			slog.String("order_id", o.orderID),
			slog.String("error", err.Error()))
		return
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	// Lock the row so concurrent AcceptOrder blocks until we finish.
	var lockedPaymentID string
	err = tx.QueryRow(ctx,
		`SELECT COALESCE(stripe_payment_id, '') FROM orders
		 WHERE id = $1 AND status = 'pending'
		 FOR UPDATE`,
		o.orderID,
	).Scan(&lockedPaymentID)
	if err != nil {
		// Row not found or no longer pending — seller already acted.
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Error("stale-rejection: lock row failed",
				slog.String("order_id", o.orderID),
				slog.String("error", err.Error()))
		}
		return
	}

	// Refund first. If the order had no payment intent stored (shouldn't
	// happen in prod, but possible in dev stub data) we skip the refund
	// call and just reject the order so the stuck state clears.
	if lockedPaymentID != "" && d.stripe != nil {
		if err := d.stripe.RefundPaymentIntent(lockedPaymentID); err != nil {
			slog.Error("stale-rejection: refund failed, leaving order pending",
				slog.String("order_id", o.orderID),
				slog.String("payment_intent", lockedPaymentID),
				slog.String("error", err.Error()))
			return
		}
	}

	_, err = tx.Exec(ctx,
		`UPDATE orders SET status = 'rejected', updated_at = NOW()
		 WHERE id = $1`,
		o.orderID)
	if err != nil {
		slog.Error("stale-rejection: status update failed",
			slog.String("order_id", o.orderID),
			slog.String("error", err.Error()))
		return
	}

	if err = tx.Commit(ctx); err != nil {
		slog.Error("stale-rejection: commit failed",
			slog.String("order_id", o.orderID),
			slog.String("error", err.Error()))
		return
	}

	slog.Info("stale-rejection: order auto-rejected",
		slog.String("order_id", o.orderID),
		slog.String("consumer_id", o.consumerID))

	if d.notify != nil {
		d.notify.OrderAutoRejected(ctx, o.orderID, o.consumerID, o.restaurantName)
	}
}

// pendingPayout is a queue row we picked up for attempt. We snapshot the
// connect account + amount so the attempt uses the same values that were
// current at enqueue time even if the courier's Stripe account has since
// changed.
type pendingPayout struct {
	id           string
	orderID      string
	courierID    string
	connectID    string
	amountCents  int
	attemptCount int
}

// sweepCourierPayouts processes rows in courier_payout_queue that are due
// for retry. Each row is one Stripe Connect transfer. On success, the row
// is marked completed; on failure, attempt_count is bumped and
// next_retry_at is pushed out via payoutBackoffSecs. After maxPayoutAttempts
// failures the row flips to failed_permanent and stops being retried.
func (d *Dispatcher) sweepCourierPayouts(ctx context.Context) {
	if d.stripe == nil {
		return
	}
	rows, err := d.db.Query(ctx, `
		SELECT id, order_id, courier_id, stripe_connect_id, amount_cents, attempt_count
		  FROM courier_payout_queue
		 WHERE status = 'pending'
		   AND next_retry_at <= NOW()
		 ORDER BY next_retry_at ASC
		 LIMIT $1
		 FOR UPDATE SKIP LOCKED`,
		payoutBatchLimit)
	if err != nil {
		slog.Error("payout-sweep: load queue failed",
			slog.String("error", err.Error()))
		return
	}

	var due []pendingPayout
	for rows.Next() {
		var p pendingPayout
		if err := rows.Scan(&p.id, &p.orderID, &p.courierID,
			&p.connectID, &p.amountCents, &p.attemptCount); err != nil {
			slog.Error("payout-sweep: scan failed",
				slog.String("error", err.Error()))
			continue
		}
		due = append(due, p)
	}
	rows.Close()

	for _, p := range due {
		d.tryPayout(ctx, p)
	}
}

// tryPayout fires a single Stripe transfer. On success marks the row
// completed; on failure reschedules with backoff or — if we've exhausted
// maxPayoutAttempts — flips to failed_permanent so a human can intervene.
func (d *Dispatcher) tryPayout(ctx context.Context, p pendingPayout) {
	err := d.stripe.TransferToCourier(p.connectID, p.amountCents, p.orderID, p.id)
	if err == nil {
		_, uerr := d.db.Exec(ctx, `
			UPDATE courier_payout_queue
			   SET status = 'completed',
			       completed_at = NOW(),
			       attempt_count = attempt_count + 1,
			       last_error = '',
			       updated_at = NOW()
			 WHERE id = $1`, p.id)
		if uerr != nil {
			// We did the transfer but failed to record completion. Next
			// sweep would re-attempt — log so we can reconcile manually.
			slog.Error("payout-sweep: transfer succeeded but mark-completed failed",
				slog.String("payout_id", p.id),
				slog.String("order_id", p.orderID),
				slog.String("error", uerr.Error()))
			return
		}
		slog.Info("payout-sweep: transfer succeeded",
			slog.String("order_id", p.orderID),
			slog.String("courier_id", p.courierID),
			slog.Int("amount_cents", p.amountCents))
		return
	}

	nextAttempt := p.attemptCount + 1
	var nextStatus string
	var backoffSecs int
	if nextAttempt >= maxPayoutAttempts {
		nextStatus = "failed_permanent"
		backoffSecs = 0 // moot for permanent failure, but keeps SQL simple
		slog.Error("payout-sweep: giving up after max attempts — admin review required",
			slog.String("order_id", p.orderID),
			slog.String("courier_id", p.courierID),
			slog.Int("attempts", nextAttempt),
			slog.String("last_error", err.Error()))
	} else {
		nextStatus = "pending"
		backoffSecs = payoutBackoffSecs(nextAttempt)
		slog.Warn("payout-sweep: transfer failed, will retry",
			slog.String("order_id", p.orderID),
			slog.Int("attempt", nextAttempt),
			slog.Int("retry_in_secs", backoffSecs),
			slog.String("error", err.Error()))
	}

	_, uerr := d.db.Exec(ctx, `
		UPDATE courier_payout_queue
		   SET status = $1,
		       attempt_count = $2,
		       last_error = $3,
		       next_retry_at = NOW() + make_interval(secs => $4),
		       updated_at = NOW()
		 WHERE id = $5`,
		nextStatus, nextAttempt, err.Error(), backoffSecs, p.id)
	if uerr != nil {
		slog.Error("payout-sweep: failed to record retry state",
			slog.String("payout_id", p.id),
			slog.String("error", uerr.Error()))
	}
}

// payoutBackoffSecs is the retry schedule in seconds keyed by the upcoming
// attempt number. Rough shape: a quick second try (5 min), then a slower
// climb through the hour/day range so a persistent Stripe issue doesn't
// hammer their API or burn through attempts too fast.
func payoutBackoffSecs(upcomingAttempt int) int {
	switch upcomingAttempt {
	case 1:
		return 300 // 5 min
	case 2:
		return 900 // 15 min
	case 3:
		return 3600 // 1 hour
	case 4:
		return 21600 // 6 hours
	default:
		return 86400 // 24 hours
	}
}
