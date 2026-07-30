package handlers

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ddWebhookPayload mirrors DoorDash Drive's webhook body. Field names verified
// against the Drive webhook reference and a live sandbox delivery.
type ddWebhookPayload struct {
	ExternalDeliveryID string `json:"external_delivery_id"`
	// EventName carries the transition, e.g. DASHER_DROPPED_OFF. NOT
	// delivery_status: that field exists only on quote/create API *responses*,
	// never on webhooks, so reading it here parsed as "" and made every webhook
	// a silent no-op — orders dispatched to DoorDash never advanced past 'ready'.
	EventName   string `json:"event_name"`
	TrackingURL string `json:"tracking_url"`
	DasherName  string `json:"dasher_name,omitempty"`
	DasherPhone string `json:"dasher_dropoff_phone_number,omitempty"`
	// A nested object, not flat dasher_location_lat/lng fields.
	DasherLocation struct {
		Lat float64 `json:"lat"`
		Lng float64 `json:"lng"`
	} `json:"dasher_location,omitempty"`
}

func (h *Handler) DoorDashWebhook(w http.ResponseWriter, r *http.Request) {
	if h.doordash == nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	// DoorDash Drive authenticates webhooks with a static bearer token echoed in
	// the header configured in the Developer Portal (we use `Authorization`), not
	// a body HMAC — see doordash.Client.VerifyWebhook. 401, not 400: the request
	// is well-formed, its credential isn't.
	if !h.doordash.VerifyWebhook(r.Header.Get("Authorization")) {
		slog.Warn("doordash webhook authorization failed")
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var payload ddWebhookPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	ctx := r.Context()
	orderID := payload.ExternalDeliveryID
	// Uppercased because the documented casing isn't uniform: the core lifecycle
	// events are UPPER_SNAKE while the opt-in tracking events
	// (dasher_enroute_to_pickup, …) are lowercase.
	event := strings.ToUpper(strings.TrimSpace(payload.EventName))

	slog.Info("doordash webhook",
		slog.String("order_id", orderID),
		slog.String("event", event))

	if orderID == "" {
		w.WriteHeader(http.StatusOK)
		return
	}

	// orders.id is a uuid column, so a non-UUID external_delivery_id makes every
	// query below fail with SQLSTATE 22P02 rather than simply matching no rows.
	// That returned 500, which DoorDash retries — an unfixable poison pill that
	// hammers this endpoint forever. Deliveries created outside our dispatch path
	// (the portal's Delivery Simulator mints its own ids) land here, so ACK and
	// drop: an id that cannot name one of our orders is nothing to reconcile.
	if _, uerr := uuid.Parse(orderID); uerr != nil {
		slog.Warn("doordash webhook: external_delivery_id is not one of our order ids, ignoring",
			slog.String("external_delivery_id", orderID),
			slog.String("event", event))
		w.WriteHeader(http.StatusOK)
		return
	}

	// Idempotency + atomicity: claim the event and mutate order state in one tx
	// (migration 052). Blocks replay of a captured 'cancelled' from re-clearing
	// the provider linkage and forcing a new paid dispatch; failed processing
	// rolls back the claim so DoorDash's retry reprocesses. Mirrors Uber webhook.
	tx, err := h.db.Pool.Begin(ctx)
	if err != nil {
		slog.Error("doordash webhook: begin tx failed", slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	fresh, err := claimWebhookEvent(ctx, tx, "doordash_drive", webhookEventID(body), event)
	if err != nil {
		slog.Error("doordash webhook: claim event failed",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if !fresh {
		slog.Info("doordash webhook: duplicate event ignored", slog.String("order_id", orderID))
		w.WriteHeader(http.StatusOK)
		return
	}

	var postCommit []func()

	// DoorDash Drive event_name values: DASHER_CONFIRMED,
	// DASHER_CONFIRMED_PICKUP_ARRIVAL, DASHER_PICKED_UP,
	// DASHER_CONFIRMED_DROPOFF_ARRIVAL, DASHER_DROPPED_OFF, DELIVERY_CANCELLED,
	// plus return-flow, batching and opt-in tracking events. Anything not handled
	// below is an intentional no-op (already logged above).
	switch event {
	case "DASHER_CONFIRMED":
		// Fire the consumer "a courier is on the way" push on exactly ONE event.
		// The arrival/tracking events also imply a Dasher is assigned; firing on
		// those too would double-send. Matches the Uber Direct webhook
		// (single-event OrderClaimed).
		dasherName := "DoorDash courier"
		if payload.DasherName != "" {
			dasherName = payload.DasherName
		}

		var consumerID, restaurantID string
		err := tx.QueryRow(ctx,
			`SELECT user_id, restaurant_id FROM orders WHERE id = $1`,
			orderID).Scan(&consumerID, &restaurantID)
		if err == nil && h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderClaimed(context.Background(), orderID, consumerID, restaurantID, dasherName)
			})
		}

	case "DASHER_PICKED_UP":
		if _, err := tx.Exec(ctx,
			// Match any pre-pickup state: an order can be escalated to a provider
			// while still 'accepted'/'preparing' (EscalateToUber allows those), so
			// keying only on 'ready' stranded those orders. Mirrors the Uber webhook.
			`UPDATE orders SET status = 'picked_up', picked_up_at = $1, updated_at = $1
			  WHERE id = $2 AND status IN ('accepted', 'preparing', 'ready') AND external_delivery_id IS NOT NULL`,
			time.Now(), orderID); err != nil {
			// Fail closed so DoorDash retries rather than stranding the order.
			slog.Error("doordash webhook: pickup update failed",
				slog.String("order_id", orderID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		var consumerID string
		_ = tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID)
		if h.notify != nil && consumerID != "" {
			postCommit = append(postCommit, func() {
				h.notify.OrderPickedUp(context.Background(), orderID, consumerID)
			})
		}

	case "DASHER_DROPPED_OFF":
		now := time.Now()
		// Accept any pre-delivered external-dispatched state (a dropped pickup
		// webhook would otherwise strand the order); mirrors the Uber webhook.
		tag, err := tx.Exec(ctx,
			`UPDATE orders SET status = 'delivered', delivered_at = $1, updated_at = $1
			  WHERE id = $2 AND status IN ('accepted','preparing','ready','picked_up')
			    AND external_delivery_id IS NOT NULL`,
			now, orderID)
		if err != nil {
			slog.Error("doordash webhook: delivered update failed",
				slog.String("order_id", orderID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		// Don't fire a duplicate "delivered" push on a 0-row (late/duplicate) match.
		if tag.RowsAffected() == 0 {
			break
		}

		var consumerID string
		if err := tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID); err != nil {
			slog.Warn("doordash webhook: failed to fetch consumer for delivery notification",
				slog.String("order_id", orderID), slog.String("error", err.Error()))
		}
		if consumerID != "" && h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderDelivered(context.Background(), orderID, consumerID)
			})
		}

	case "DELIVERY_CANCELLED":
		slog.Warn("doordash delivery canceled — order needs re-dispatch",
			slog.String("order_id", orderID))
		if _, err := tx.Exec(ctx,
			`UPDATE orders
			    SET external_delivery_id = NULL, external_provider = NULL,
			        external_tracking_url = NULL,
			        status = CASE WHEN status = 'picked_up' THEN 'ready' ELSE status END,
			        updated_at = NOW()
			  WHERE id = $1 AND status IN ('ready', 'picked_up')`, orderID); err != nil {
			slog.Error("doordash webhook: cancel cleanup failed",
				slog.String("order_id", orderID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
	}

	if err := tx.Commit(ctx); err != nil {
		slog.Error("doordash webhook: commit failed",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	for _, f := range postCommit {
		f()
	}

	w.WriteHeader(http.StatusOK)
}
