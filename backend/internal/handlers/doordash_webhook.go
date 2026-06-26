package handlers

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"
)

type ddWebhookPayload struct {
	ExternalDeliveryID string `json:"external_delivery_id"`
	DeliveryStatus     string `json:"delivery_status"`
	TrackingURL        string `json:"tracking_url"`
	DasherName         string `json:"dasher_name,omitempty"`
	DasherPhone        string `json:"dasher_dropoff_phone_number,omitempty"`
	DasherLat          float64 `json:"dasher_location_lat,omitempty"`
	DasherLng          float64 `json:"dasher_location_lng,omitempty"`
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

	if !h.doordash.VerifyWebhook(body, r.Header.Get("X-Doordash-Signature")) {
		slog.Warn("doordash webhook signature verification failed")
		writeError(w, http.StatusBadRequest, "invalid signature")
		return
	}

	var payload ddWebhookPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	ctx := r.Context()
	orderID := payload.ExternalDeliveryID
	status := payload.DeliveryStatus

	slog.Info("doordash webhook",
		slog.String("order_id", orderID),
		slog.String("status", status))

	if orderID == "" {
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

	fresh, err := claimWebhookEvent(ctx, tx, "doordash_drive", webhookEventID(body), status)
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

	// DoorDash Drive statuses: created, confirmed, enroute_to_pickup,
	// arrived_at_pickup, picked_up, enroute_to_dropoff, arrived_at_dropoff,
	// delivered, cancelled
	switch status {
	case "confirmed":
		// Fire the consumer "a courier is on the way" push on exactly ONE status.
		// The DoorDash Drive lifecycle emits both 'confirmed' and 'enroute_to_pickup';
		// firing on both double-sent the push. 'enroute_to_pickup' is now a no-op,
		// matching the Uber Direct webhook (single-status OrderClaimed).
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

	case "picked_up":
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

	case "delivered":
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

	case "cancelled":
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
