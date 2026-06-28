// Package dispatch holds the external-courier (Uber Direct / DoorDash Drive)
// dispatch logic, factored out of the scheduler so it can be called both by the
// periodic auto-dispatch sweep AND inline from an HTTP handler (e.g. the instant
// dispatch when a seller marks an 'external'-mode order ready, or escalates a
// self-delivery order to Uber). A standalone package avoids a handlers<->scheduler
// import cycle.
package dispatch

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/uberdirect"
)

// ExternalDispatcher dispatches an order to the cheapest configured external
// courier provider. Holds only what it needs so both the scheduler and the
// handlers can construct one from their shared clients + pool.
type ExternalDispatcher struct {
	db       *pgxpool.Pool
	uber     *uberdirect.Client
	doordash *doordash.Client
}

func New(db *pgxpool.Pool, uber *uberdirect.Client, doordash *doordash.Client) *ExternalDispatcher {
	return &ExternalDispatcher{db: db, uber: uber, doordash: doordash}
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
		       ), 'platform') <> 'restaurant')`, in.OrderID, in.AllowRestaurantMode)
	if err != nil {
		return "", "", 0, err
	}
	if tag.RowsAffected() == 0 {
		// Already claimed/dispatched/assigned by someone else — not an error.
		slog.Info("external-dispatch: order already claimed, skipping",
			slog.String("order_id", in.OrderID))
		return "", "", 0, nil
	}

	// On any failure after we've claimed, release the claim so the sweep can
	// retry the order on a later tick.
	release := func() {
		if _, rerr := e.db.Exec(context.Background(), `
			UPDATE orders SET external_provider = NULL, updated_at = NOW()
			 WHERE id = $1 AND external_provider = 'dispatching'`, in.OrderID); rerr != nil {
			slog.Error("external-dispatch: failed to release claim",
				slog.String("order_id", in.OrderID), slog.String("error", rerr.Error()))
		}
	}

	type providerQuote struct {
		provider    string
		feeCents    int
		uberQuoteID string
	}
	var quotes []providerQuote

	if e.uber != nil && e.uber.Enabled() {
		q, qerr := e.uber.GetQuote(ctx,
			uberdirect.Address{Street: []string{in.RestAddress}, Country: "US"},
			uberdirect.Address{Street: []string{in.DeliveryAddress}, Country: "US"})
		if qerr != nil {
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
			slog.Warn("external-dispatch: doordash quote failed",
				slog.String("order_id", in.OrderID), slog.String("error", qerr.Error()))
		} else {
			quotes = append(quotes, providerQuote{provider: "doordash_drive", feeCents: q.Fee})
		}
	}

	if len(quotes) == 0 {
		slog.Error("external-dispatch: all providers failed", slog.String("order_id", in.OrderID))
		release()
		return "", "", 0, nil
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
			release()
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
			release()
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
