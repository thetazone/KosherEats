// Package dispatch holds the external-courier (Uber Direct / DoorDash Drive)
// dispatch logic, factored out of the scheduler so it can be called both by the
// periodic auto-dispatch sweep AND inline from an HTTP handler (e.g. the instant
// dispatch when a seller marks an 'external'-mode order ready, or escalates a
// self-delivery order to Uber). A standalone package avoids a handlers<->scheduler
// import cycle.
package dispatch

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/notify"
	"github.com/koshereats/backend/internal/uberdirect"
)

// ExternalDispatcher dispatches an order to the cheapest configured external
// courier provider. Holds only what it needs so both the scheduler and the
// handlers can construct one from their shared clients + pool.
type ExternalDispatcher struct {
	db       *pgxpool.Pool
	uber     *uberdirect.Client
	doordash *doordash.Client
	// notify broadcasts OrderReady to online couriers when a failed external
	// dispatch falls back to the internal pool. Nil-safe (fallback still flips
	// the order; couriers then find it via the marketplace poll).
	notify *notify.Notifier
	// alerter raises an admin anomaly alert when an order silently changes
	// delivery paths. notify.Alerter.Alert is nil-receiver-safe, so a nil
	// alerter degrades to a logged WARN — the fallback still happens.
	alerter *notify.Alerter
}

func New(db *pgxpool.Pool, uber *uberdirect.Client, doordash *doordash.Client, n *notify.Notifier, alerter *notify.Alerter) *ExternalDispatcher {
	return &ExternalDispatcher{db: db, uber: uber, doordash: doordash, notify: n, alerter: alerter}
}

// SetAlerter injects the admin alerter after construction — the scheduler
// builds its ExternalDispatcher before its own alerter is wired.
func (e *ExternalDispatcher) SetAlerter(a *notify.Alerter) { e.alerter = a }

// maxExternalDispatchAttempts bounds how many failed dispatch attempts (quote
// or create) an order may accumulate before it stops being retried against
// external providers and falls back to the internal courier pool. Every sweep
// retry costs real provider API calls, so even "transient" failures must not
// spin forever. Mirrors the refund_attempts poison-pill bound (migration 051).
const maxExternalDispatchAttempts = 5

// ErrNotDispatchable marks order/restaurant data that no provider will accept
// (detected locally, before any paid call). Wrapped errors are classified
// permanent by IsPermanent.
var ErrNotDispatchable = errors.New("order not dispatchable")

// isPermanentProviderError reports whether the provider rejected the request
// for a reason a retry cannot fix — a 4xx validation rejection of THIS order's
// data, such as a missing pickup phone or an unserviceable address. Network
// errors and 5xx are transient. Deliberately NOT permanent despite being 4xx:
//   - 408/429: timeout and rate limit — classic transients;
//   - 401/402/403: account-level auth/billing problems, not order data — a
//     credential hiccup must not instantly reroute orders (DoorDash signs its
//     JWT locally, so even bad creds surface as an API 401 here);
//   - 409: conflict — DoorDash answers 409 duplicate_delivery_id when a
//     delivery for this order ALREADY EXISTS (e.g. created but unrecorded);
//     auto-falling-back on that could double-deliver. Those need the orphan
//     reconcile path, not a reroute.
//
// Account-level failures still stop looping via the attempts cap.
func isPermanentProviderError(err error) bool {
	var ue *uberdirect.APIError
	if errors.As(err, &ue) {
		return permanentStatus(ue.StatusCode)
	}
	var de *doordash.APIError
	if errors.As(err, &de) {
		return permanentStatus(de.StatusCode)
	}
	return false
}

func permanentStatus(code int) bool {
	switch code {
	case 401, 402, 403, 408, 409, 429:
		return false
	}
	return code >= 400 && code < 500
}

// truncate bounds an untrusted provider error string before it goes into an
// operator email — provider bodies are unbounded and can echo customer PII.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…(truncated)"
}

// Input is everything Dispatch needs about an order. Callers build it from
// whatever projection they already have (the scheduler's staleOrder, or a
// handler's order row).
type Input struct {
	OrderID         string
	RestaurantName  string
	RestAddress     string
	RestPhone       string
	DeliveryAddress string
	CustomerName    string
	CustomerPhone   string
	Subtotal        int
	TipCents        int
	// AllowRestaurantMode is true only for an explicit seller fallback action
	// ("Dispatch to Uber"). Automatic dispatch must not grab an order the seller
	// has just switched to self-delivery.
	AllowRestaurantMode bool
}

// AnyProviderEnabled reports whether at least one external provider is usable.
// Callers check this before deciding to dispatch externally.
func (e *ExternalDispatcher) AnyProviderEnabled() bool {
	return (e.uber != nil && e.uber.Enabled()) || (e.doordash != nil && e.doordash.Enabled())
}

// Dispatch quotes the configured providers, picks the cheapest, creates the
// delivery, and records it on the order. It is safe to call concurrently (the
// sweep and an inline handler can race): a claim-before-create CAS ensures only
// ONE caller ever reaches the paid CreateDelivery call for a given order.
//
// Returns the chosen provider, the provider's delivery id, and the provider fee.
// A nil error with an empty provider means another caller already owns this
// order (claim lost) — not a failure.
//
// Dispatch does NOT apply the grace wait or the hasExternal short-circuit; those
// are the scheduler caller's concern (the inline/escalate callers want immediate
// dispatch). It only guards on the canonical "not yet claimed" predicate.
func (e *ExternalDispatcher) Dispatch(ctx context.Context, in Input) (provider, deliveryID string, fee int, err error) {
	// Claim-before-create: flip external_provider to a sentinel ONLY if the
	// order has no courier and no external delivery and isn't already being
	// dispatched. If we don't win the row, another caller (sweep or a second
	// tap) owns it — returning here prevents a duplicate, real, *paid* delivery.
	// The bare courier_id-IS-NULL guard on the final UPDATE can't prevent that,
	// because by then CreateDelivery has already charged us.
	tag, err := e.db.Exec(ctx, `
		UPDATE orders
		   SET external_provider = 'dispatching', updated_at = NOW()
		 WHERE id = $1
		   AND courier_id IS NULL
		   AND external_delivery_id IS NULL
		   AND external_provider IS NULL
		   -- Don't dispatch an order that's already past 'ready' (e.g. a seller
		   -- just self-picked-it-up). Closes the other half of the
		   -- SellerPickupOrder ↔ EscalateToUber race; without it the claim CAS
		   -- ignored status and could pay for a delivery on a picked-up order.
		   AND status IN ('accepted','preparing','ready')
		   AND ($2 OR COALESCE(delivery_mode, (
		         SELECT rest.delivery_mode FROM restaurants rest WHERE rest.id = orders.restaurant_id
		       ), 'platform') <> 'restaurant')
		   -- Attempt cap: an order that failed dispatch permanently (attempts
		   -- jumped to the cap) or exhausted its transient retries never
		   -- re-enters the paid quote/create path — critical because platform
		   -- orders with no free courier fall through to external dispatch on
		   -- EVERY sweep tick (tryAutoAssign), which would otherwise retry a
		   -- hopeless order forever. Seller-initiated escalations ($2) bypass
		   -- the cap: an explicit human retry is allowed to try again.
		   AND ($2 OR external_dispatch_attempts < $3)`,
		in.OrderID, in.AllowRestaurantMode, maxExternalDispatchAttempts)
	if err != nil {
		return "", "", 0, err
	}
	if tag.RowsAffected() == 0 {
		// Already claimed/dispatched/assigned by someone else — not an error.
		slog.Info("external-dispatch: order already claimed, skipping",
			slog.String("order_id", in.OrderID))
		return "", "", 0, nil
	}

	// On any failure after we've claimed, release the claim and decide between
	// retry and fallback. Transient failures (5xx, network, rate limit) release
	// for the sweep to retry on a later tick — but each attempt is counted, and
	// the cap turns a retry storm into a fallback. Permanent failures (4xx
	// validation: bad pickup phone, unserviceable address) can never succeed,
	// and retrying burns a real provider call every sweep, so the order falls
	// back to the internal courier pool immediately (order-level delivery_mode
	// override → tryAutoAssign / courier ClaimOrder pick it up).
	//
	// Seller-initiated escalations (AllowRestaurantMode) skip the fallback: the
	// handler surfaces the error synchronously and the seller keeps
	// self-delivering; silently flipping their order to the platform pool would
	// contradict the mode they explicitly chose.
	//
	// context.Background() on purpose — the release must land even if the
	// caller's ctx was cancelled mid-dispatch.
	fail := func(permanent bool, cause error) {
		// Seller-initiated escalations are human-gated one-shots: release the
		// claim WITHOUT counting an attempt. Counting them would let failed
		// taps (each re-jumping the counter) poison the shared cap and later
		// cap-block an effective-external order out of the sweep with no
		// fallback — a stranded order. The error is surfaced synchronously by
		// the handler instead.
		if in.AllowRestaurantMode {
			if _, rerr := e.db.Exec(context.Background(), `
				UPDATE orders SET external_provider = NULL, updated_at = NOW()
				 WHERE id = $1 AND external_provider = 'dispatching'`, in.OrderID); rerr != nil {
				slog.Error("external-dispatch: failed to release claim",
					slog.String("order_id", in.OrderID), slog.String("error", rerr.Error()))
			}
			return
		}

		// ONE atomic statement: release the sentinel, count the attempt (a
		// permanent failure jumps straight to the cap — one 4xx is enough,
		// retrying can't fix bad data), and, when this failure retires the
		// order from the external path, flip the order-level delivery_mode to
		// 'platform' in the SAME statement. Atomicity matters: if the flip
		// were a separate statement, a crash between the two would leave a
		// cap-blocked external-mode order that no sweep can ever rescue (the
		// claim CAS's cap guard blocks re-entry, so fail() never runs again).
		var attempts int
		rerr := e.db.QueryRow(context.Background(), `
			UPDATE orders
			   SET external_provider = NULL,
			       external_dispatch_attempts = CASE WHEN $2
			           THEN GREATEST(external_dispatch_attempts + 1, $3::int)
			           ELSE external_dispatch_attempts + 1 END,
			       delivery_mode = CASE WHEN $2 OR external_dispatch_attempts + 1 >= $3::int
			           THEN 'platform' ELSE delivery_mode END,
			       updated_at = NOW()
			 WHERE id = $1 AND external_provider = 'dispatching'
			 RETURNING external_dispatch_attempts`,
			in.OrderID, permanent, maxExternalDispatchAttempts).Scan(&attempts)
		if errors.Is(rerr, pgx.ErrNoRows) {
			// Sentinel already cleared (reaper or a concurrent path) — whoever
			// cleared it owns the next step.
			return
		}
		if rerr != nil {
			slog.Error("external-dispatch: failed to release claim",
				slog.String("order_id", in.OrderID), slog.String("error", rerr.Error()))
			return
		}
		if permanent || attempts >= maxExternalDispatchAttempts {
			// External dispatch is retired for this order (delivery_mode is now
			// 'platform' if it wasn't already). Runs at most once per order —
			// the claim CAS's cap guard then blocks every non-escalation caller.
			// The announcement is worded to hold whether this was a genuine
			// external→platform reroute or a platform order that merely fell
			// through to external and failed (delivery_mode already 'platform').
			e.announceFallback(in, permanent, attempts, cause)
		}
	}

	// A missing pickup phone can never dispatch: providers require
	// pickup_phone_number and reject the create with a 400 — while the quote
	// succeeds, so the failure is only discovered AFTER a wasted quote call.
	// (This exact gap — a seeded restaurant with an empty phone — once looped
	// the sweep forever.) Fail fast as permanent before any provider call.
	if strings.TrimSpace(in.RestPhone) == "" {
		perr := fmt.Errorf("%w: restaurant %q has no phone on file; delivery providers require a pickup phone number", ErrNotDispatchable, in.RestaurantName)
		slog.Error("external-dispatch: missing pickup phone",
			slog.String("order_id", in.OrderID),
			slog.String("restaurant", in.RestaurantName))
		fail(true, perr)
		return "", "", 0, perr
	}

	type providerQuote struct {
		provider    string
		feeCents    int
		uberQuoteID string
	}
	var quotes []providerQuote
	var quoteErrs []error

	if e.uber != nil && e.uber.Enabled() {
		q, qerr := e.uber.GetQuote(ctx,
			uberdirect.Address{Street: []string{in.RestAddress}, Country: "US"},
			uberdirect.Address{Street: []string{in.DeliveryAddress}, Country: "US"})
		if qerr != nil {
			quoteErrs = append(quoteErrs, qerr)
			slog.Warn("external-dispatch: uber quote failed",
				slog.String("order_id", in.OrderID), slog.String("error", qerr.Error()))
		} else {
			quotes = append(quotes, providerQuote{provider: "uber_direct", feeCents: q.Fee, uberQuoteID: q.ID})
		}
	}
	if e.doordash != nil && e.doordash.Enabled() {
		q, qerr := e.doordash.GetQuote(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: in.OrderID + "_quote",
			PickupAddress:      in.RestAddress,
			PickupBusinessName: in.RestaurantName,
			PickupPhone:        in.RestPhone,
			DropoffAddress:     in.DeliveryAddress,
			DropoffContactName: in.CustomerName,
			DropoffPhone:       in.CustomerPhone,
			OrderValue:         in.Subtotal,
		})
		if qerr != nil {
			quoteErrs = append(quoteErrs, qerr)
			slog.Warn("external-dispatch: doordash quote failed",
				slog.String("order_id", in.OrderID), slog.String("error", qerr.Error()))
		} else {
			quotes = append(quotes, providerQuote{provider: "doordash_drive", feeCents: q.Fee})
		}
	}

	if len(quotes) == 0 {
		// Return a real error (used to be nil): EscalateToUber's derr branch
		// then answers 502 "could not dispatch" instead of mislabeling a quote
		// outage as 409 "already being dispatched", and the sweep caller can
		// log it. Permanent only when every provider that tried was rejected
		// with a validation 4xx — one transient failure means a retry could
		// still win.
		ferr := fmt.Errorf("all providers failed to quote: %w", errors.Join(quoteErrs...))
		if len(quoteErrs) == 0 {
			ferr = fmt.Errorf("no external provider enabled")
		}
		slog.Error("external-dispatch: all providers failed",
			slog.String("order_id", in.OrderID), slog.String("error", ferr.Error()))
		permanent := len(quoteErrs) > 0
		for _, qe := range quoteErrs {
			if !isPermanentProviderError(qe) {
				permanent = false
				break
			}
		}
		fail(permanent, ferr)
		return "", "", 0, ferr
	}

	best := quotes[0]
	for _, q := range quotes[1:] {
		if q.feeCents < best.feeCents {
			best = q
		}
	}
	slog.Info("external-dispatch: cheapest quote selected",
		slog.String("order_id", in.OrderID),
		slog.String("provider", best.provider),
		slog.Int("fee_cents", best.feeCents))

	var trackingURL string
	switch best.provider {
	case "uber_direct":
		del, cerr := e.uber.CreateDelivery(ctx, uberdirect.CreateDeliveryRequest{
			QuoteID:        best.uberQuoteID,
			ExternalID:     in.OrderID,
			PickupName:     in.RestaurantName,
			PickupAddress:  uberdirect.Address{Street: []string{in.RestAddress}, Country: "US"},
			PickupPhone:    in.RestPhone,
			DropoffName:    in.CustomerName,
			DropoffAddress: uberdirect.Address{Street: []string{in.DeliveryAddress}, Country: "US"},
			DropoffPhone:   in.CustomerPhone,
			TotalCents:     in.Subtotal,
			TipCents:       in.TipCents,
			Items: []uberdirect.ManifestItem{
				{Name: "Food order from " + in.RestaurantName, Quantity: 1, Price: in.Subtotal},
			},
		})
		if cerr != nil {
			slog.Error("external-dispatch: uber create failed",
				slog.String("order_id", in.OrderID), slog.String("error", cerr.Error()))
			fail(isPermanentProviderError(cerr), cerr)
			return "", "", 0, cerr
		}
		deliveryID, trackingURL, fee = del.ID, del.TrackingURL, del.Fee

	case "doordash_drive":
		del, cerr := e.doordash.CreateDelivery(ctx, doordash.CreateDeliveryRequest{
			ExternalDeliveryID: in.OrderID,
			PickupAddress:      in.RestAddress,
			PickupBusinessName: in.RestaurantName,
			PickupPhone:        in.RestPhone,
			DropoffAddress:     in.DeliveryAddress,
			DropoffContactName: in.CustomerName,
			DropoffPhone:       in.CustomerPhone,
			OrderValue:         in.Subtotal,
			TipCents:           in.TipCents,
		})
		if cerr != nil {
			slog.Error("external-dispatch: doordash create failed",
				slog.String("order_id", in.OrderID), slog.String("error", cerr.Error()))
			fail(isPermanentProviderError(cerr), cerr)
			return "", "", 0, cerr
		}
		deliveryID, trackingURL, fee = del.ExternalDeliveryID, del.TrackingURL, del.Fee
	}

	// Persist the real delivery id keyed on OUR claim sentinel — we won the
	// claim, so this reliably records the (already paid-for) delivery without
	// racing a courier. Courier claims now exclude 'dispatching' orders (see
	// tryAutoAssign / ClaimOrder), so the sentinel can't be stolen between claim
	// and create. If RowsAffected is still 0, a real provider delivery exists but
	// we couldn't record it — surface a loud error to reconcile, never drop it.
	tag2, uerr := e.db.Exec(ctx, `
		UPDATE orders
		   SET external_delivery_id = $1, external_provider = $2, external_tracking_url = $3,
		       provider_fee_cents = $5, updated_at = NOW()
		 WHERE id = $4 AND external_provider = 'dispatching'`,
		deliveryID, best.provider, trackingURL, in.OrderID, fee)
	if uerr != nil {
		slog.Error("external-dispatch: db update failed",
			slog.String("order_id", in.OrderID), slog.String("error", uerr.Error()))
		return best.provider, deliveryID, fee, uerr
	}
	if tag2.RowsAffected() == 0 {
		slog.Error("external-dispatch: ORPHANED PAID DELIVERY — claim sentinel lost after create; manual reconcile needed",
			slog.String("order_id", in.OrderID),
			slog.String("provider", best.provider),
			slog.String("delivery_id", deliveryID))
		// We have been billed for a courier we cannot associate with the order, so
		// nothing downstream will ever reference it: no tracking URL for the
		// customer, no cancel path, and no way to notice from the order row. That
		// is only recoverable by a human reading the provider dashboard, so it has
		// to leave the log and reach someone.
		go e.alerter.Alert(
			"URGENT: paid courier delivery is orphaned — manual reconciliation required",
			fmt.Sprintf(
				"A %s delivery (%s) was created and billed for order %s at %q, but the order row could not be "+
					"updated to reference it (the dispatch claim sentinel was lost between create and persist).\n\n"+
					"Consequences until reconciled: the customer sees no tracking, the order will not advance on "+
					"provider webhooks, and auto-dispatch may buy a SECOND delivery for the same order.\n\n"+
					"Do this now:\n"+
					"  1. Open the %s dashboard and find delivery %s.\n"+
					"  2. Cancel it if the order is not genuinely in flight.\n"+
					"  3. Otherwise set external_provider/external_delivery_id on order %s by hand so the "+
					"webhooks bind and the customer gets tracking.",
				best.provider, deliveryID, in.OrderID, in.RestaurantName,
				best.provider, deliveryID, in.OrderID),
		)
		return best.provider, deliveryID, fee,
			fmt.Errorf("dispatch persisted 0 rows for order %s (provider delivery %s already created)", in.OrderID, deliveryID)
	}

	slog.Info("external-dispatch: delivery created",
		slog.String("order_id", in.OrderID),
		slog.String("provider", best.provider),
		slog.String("delivery_id", deliveryID),
		slog.Int("fee_cents", fee))
	return best.provider, deliveryID, fee, nil
}

// announceFallback surfaces a fallback-to-platform that fail() already
// committed (the delivery_mode flip happens atomically with the attempts
// bump). The order is now in the internal courier pool: the sweep's
// tryAutoAssign and the courier marketplace (ListAvailableDeliveries /
// ClaimOrder, which all key on COALESCE(o.delivery_mode, rest.delivery_mode,
// 'platform')) pick it up; here we log the anomaly and give online couriers
// the same OrderReady broadcast a platform order fires when the kitchen marks
// it ready. The alternative to rerouting — a terminal failed state — would
// need new seller/admin UI; keeping the food moving degrades better.
func (e *ExternalDispatcher) announceFallback(in Input, permanent bool, attempts int, cause error) {
	ctx := context.Background()

	// Error-level on purpose: an order silently changing delivery paths is an
	// operational anomaly someone should see in the logs even though the
	// customer impact is contained.
	slog.Error("external-dispatch: falling back to internal courier pool",
		slog.String("order_id", in.OrderID),
		slog.String("restaurant", in.RestaurantName),
		slog.Bool("permanent", permanent),
		slog.Int("attempts", attempts),
		slog.String("cause", cause.Error()))

	// Alert an operator: the fallback keeps the food moving ONLY if a courier
	// is online. With a thin pool, "rerouted but nobody claims it" is a real
	// (and otherwise invisible) way for an order to stall — so page someone.
	// Alert is nil-receiver-safe, so this is always safe to call.
	//
	// Fired in a goroutine: Alert → email.Send → net/smtp has no timeout, and
	// announceFallback runs inside the sequential auto-dispatch sweep (which
	// holds a cluster-wide advisory lock), so a hung SMTP server must not stall
	// the tick. The truncated cause bounds the email size and limits how much of
	// the provider's raw response body (which can echo the customer's
	// address/phone) reaches the ops mailbox.
	reason := "transient failures exhausted the retry budget"
	if permanent {
		reason = "a permanent provider rejection (bad address/phone or account issue)"
	}
	// Wording holds for both a genuine external→platform reroute AND a platform
	// order that only fell through to external — in both cases the order now
	// depends on the internal courier pool. Avoid claiming a "reroute" that a
	// platform-origin order didn't experience.
	subject := "External courier dispatch failed — order is on the internal courier pool"
	body := fmt.Sprintf("Order %s at %q could not be dispatched to an external courier after %s (%d attempt(s)): %s. "+
		"It is now in the KosherEats courier pool and will be delivered only if a courier claims it — check courier coverage.",
		in.OrderID, in.RestaurantName, reason, attempts, truncate(cause.Error(), 300))
	go e.alerter.Alert(subject, body)

	if e.notify != nil {
		var payout int
		if qerr := e.db.QueryRow(ctx,
			`SELECT delivery_fee + COALESCE(courier_tip, 0) FROM orders WHERE id = $1`,
			in.OrderID).Scan(&payout); qerr != nil {
			slog.Warn("external-dispatch: fallback payout lookup failed",
				slog.String("order_id", in.OrderID), slog.String("error", qerr.Error()))
		}
		e.notify.OrderReady(ctx, in.OrderID, in.RestaurantName, payout)
	}
}

// IsPermanent reports whether a Dispatch error is a permanent, non-retryable
// failure (provider 4xx validation or locally-detected bad order data such as
// a missing pickup phone). Handlers use it to answer with an actionable 4xx
// instead of a retryable-looking 502.
func IsPermanent(err error) bool {
	return errors.Is(err, ErrNotDispatchable) || isPermanentProviderError(err)
}
