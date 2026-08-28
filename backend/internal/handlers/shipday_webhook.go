package handlers

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

// shipdayWebhookPayload mirrors Shipday's order-status webhook body
// (docs.shipday.com/reference/order-status-update-2). Only the fields we act
// on are decoded.
type shipdayWebhookPayload struct {
	Event       string `json:"event"`
	OrderStatus string `json:"order_status"`
	Order       struct {
		// ID is Shipday's numeric order id — what dispatch stored as
		// external_delivery_id.
		ID int64 `json:"id"`
		// OrderNumber echoes what dispatch sent as orderNumber: our order UUID.
		OrderNumber string `json:"order_number"`
	} `json:"order"`
	Carrier struct {
		Name string `json:"name"`
	} `json:"carrier"`
}

// ShipdayWebhook ingests Shipday order-status webhooks. Structure mirrors
// DoorDashWebhook: static-token auth, idempotency claim + state change in one
// tx, provider-scoped updates, pushes after commit.
func (h *Handler) ShipdayWebhook(w http.ResponseWriter, r *http.Request) {
	if h.shipday == nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	// Shipday echoes the dashboard-configured validation token in a `token`
	// header — no body HMAC exists. 401, not 400: the request is well-formed,
	// its credential isn't. Fails closed when no token is configured.
	if !h.shipday.VerifyWebhook(r.Header.Get("token")) {
		slog.Warn("shipday webhook token verification failed")
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var payload shipdayWebhookPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		// ACK (a retry can't fix a malformed body) but leave a trace — a silent
		// drop here would make a Shipday payload-format change indistinguishable
		// from no webhooks at all. Review finding.
		slog.Warn("shipday webhook: unparseable body ignored",
			slog.String("error", err.Error()), slog.Int("bytes", len(body)))
		w.WriteHeader(http.StatusOK)
		return
	}

	ctx := r.Context()
	event := strings.ToUpper(strings.TrimSpace(payload.Event))
	orderID := strings.TrimSpace(payload.Order.OrderNumber)
	shipdayID := strconv.FormatInt(payload.Order.ID, 10)

	slog.Info("shipday webhook",
		slog.String("order_id", orderID),
		slog.String("shipday_id", shipdayID),
		slog.String("event", event),
		slog.String("order_status", payload.OrderStatus))

	if orderID == "" {
		w.WriteHeader(http.StatusOK)
		return
	}

	// orders.id is a uuid column: a non-UUID order_number would make every query
	// below fail 22P02 → 500 → provider retry loop (the DoorDash poison-pill
	// lesson). Orders created directly in the Shipday dashboard carry whatever
	// order number a human typed, so ACK and drop anything that can't be ours.
	parsedID, uerr := uuid.Parse(orderID)
	if uerr != nil {
		slog.Warn("shipday webhook: order_number is not one of our order ids, ignoring",
			slog.String("order_number", orderID), slog.String("event", event))
		w.WriteHeader(http.StatusOK)
		return
	}
	orderID = parsedID.String()

	tx, err := h.db.Pool.Begin(ctx)
	if err != nil {
		slog.Error("shipday webhook: begin tx failed", slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	fresh, err := claimWebhookEvent(ctx, tx, "shipday", webhookEventID(body), event)
	if err != nil {
		slog.Error("shipday webhook: claim event failed",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if !fresh {
		slog.Info("shipday webhook: duplicate event ignored", slog.String("order_id", orderID))
		w.WriteHeader(http.StatusOK)
		return
	}

	var postCommit []func()

	// Shipday event values: ORDER_INSERTED, ORDER_ASSIGNED,
	// ORDER_ACCEPTED_AND_STARTED, ORDER_PIKEDUP (their spelling), ORDER_ONTHEWAY,
	// ORDER_COMPLETED, ORDER_FAILED, ORDER_INCOMPLETE, ORDER_UNASSIGNED,
	// ORDER_DELETE, ORDER_POD_UPLOAD and *_REMOVED corrections. Unhandled events
	// are intentional no-ops (already logged above).
	//
	// All state changes are scoped to external_provider='shipday' AND
	// external_delivery_id = the webhook's own Shipday order id. The equality
	// (not just IS NOT NULL) matters: a retried dispatch can leave abandoned
	// unassigned Shipday orders carrying the same order_number, and their
	// webhooks must never advance an order that is out with a different
	// delivery — or with a different provider entirely.
	switch event {
	case "ORDER_ASSIGNED":
		// Fire the consumer "courier on the way" push on exactly ONE event;
		// ORDER_ACCEPTED_AND_STARTED also implies assignment but firing on both
		// would double-send. Mirrors DASHER_CONFIRMED / Uber's single OrderClaimed.
		courierName := "Courier"
		if payload.Carrier.Name != "" {
			courierName = payload.Carrier.Name
		}

		var consumerID, restaurantID string
		err := tx.QueryRow(ctx,
			`SELECT user_id, restaurant_id FROM orders
			  WHERE id = $1 AND external_provider = 'shipday' AND external_delivery_id = $2`,
			orderID, shipdayID).Scan(&consumerID, &restaurantID)
		if err == nil && h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderClaimed(context.Background(), orderID, consumerID, restaurantID, courierName)
			})
		}

	case "ORDER_PIKEDUP", "ORDER_ONTHEWAY":
		// Both events mean the food left the restaurant. The second to arrive
		// finds status already 'picked_up' and no-ops via the status guard.
		tag, uerr := tx.Exec(ctx,
			`UPDATE orders SET status = 'picked_up', picked_up_at = COALESCE(picked_up_at, $1), updated_at = $1
			  WHERE id = $2 AND status IN ('accepted', 'preparing', 'ready')
			    AND external_provider = 'shipday' AND external_delivery_id = $3`,
			time.Now(), orderID, shipdayID)
		if uerr != nil {
			// Fail closed so Shipday retries rather than stranding the order.
			slog.Error("shipday webhook: pickup update failed",
				slog.String("order_id", orderID), slog.String("error", uerr.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if tag.RowsAffected() == 0 {
			logProviderScopeMiss(ctx, tx, "shipday", event, orderID, "shipday")
			break
		}

		var consumerID string
		_ = tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID)
		if h.notify != nil && consumerID != "" {
			postCommit = append(postCommit, func() {
				h.notify.OrderPickedUp(context.Background(), orderID, consumerID)
			})
		}

	case "ORDER_COMPLETED":
		now := time.Now()
		// COALESCE picked_up_at: when the pickup events were missed (webhook
		// outage, late registration), delivered must still leave a plausible
		// pickup timestamp — analytics and courier-time metrics divide by it.
		tag, uerr := tx.Exec(ctx,
			`UPDATE orders SET status = 'delivered', delivered_at = $1,
			        picked_up_at = COALESCE(picked_up_at, $1), updated_at = $1
			  WHERE id = $2 AND status IN ('accepted','preparing','ready','picked_up')
			    AND external_provider = 'shipday' AND external_delivery_id = $3`,
			now, orderID, shipdayID)
		if uerr != nil {
			slog.Error("shipday webhook: delivered update failed",
				slog.String("order_id", orderID), slog.String("error", uerr.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if tag.RowsAffected() == 0 {
			logProviderScopeMiss(ctx, tx, "shipday", event, orderID, "shipday")
			break
		}

		var consumerID string
		if err := tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID); err != nil {
			slog.Warn("shipday webhook: failed to fetch consumer for delivery notification",
				slog.String("order_id", orderID), slog.String("error", err.Error()))
		}
		if consumerID != "" && h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderDelivered(context.Background(), orderID, consumerID)
			})
		}

	case "ORDER_FAILED", "ORDER_INCOMPLETE", "ORDER_UNASSIGNED":
		slog.Warn("shipday delivery failed/unassigned — order needs re-dispatch",
			slog.String("order_id", orderID), slog.String("event", event))
		// Provider + delivery-id scoping is LOAD-BEARING (see the DoorDash
		// DELIVERY_CANCELLED comment): clearing the linkage re-arms auto-dispatch,
		// and doing so for an order out with another delivery would buy a second
		// paid courier for food already in flight.
		// Status set matches the dispatch claim CAS ('accepted','preparing','ready')
		// plus 'picked_up': an order escalated to Shipday while still preparing
		// gets its dead linkage cleared too. A narrower set (the first cut used
		// only ready/picked_up) welded such orders to a failed delivery forever —
		// the event dedupes in external_webhook_events, so it never reprocesses,
		// and the claim CAS requires NULL linkage to re-arm. Review finding.
		tag, uerr := tx.Exec(ctx,
			`UPDATE orders
			    SET external_delivery_id = NULL, external_provider = NULL,
			        external_tracking_url = NULL,
			        status = CASE WHEN status = 'picked_up' THEN 'ready' ELSE status END,
			        updated_at = NOW()
			  WHERE id = $1 AND status IN ('accepted', 'preparing', 'ready', 'picked_up')
			    AND external_provider = 'shipday' AND external_delivery_id = $2`,
			orderID, shipdayID)
		if uerr != nil {
			slog.Error("shipday webhook: failure cleanup failed",
				slog.String("order_id", orderID), slog.String("error", uerr.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if tag.RowsAffected() == 0 {
			logProviderScopeMiss(ctx, tx, "shipday", event, orderID, "shipday")
		}
	}

	if err := tx.Commit(ctx); err != nil {
		slog.Error("shipday webhook: commit failed",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	for _, f := range postCommit {
		f()
	}

	w.WriteHeader(http.StatusOK)
}
