package handlers

import (
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

	// DoorDash Drive statuses: created, confirmed, enroute_to_pickup,
	// arrived_at_pickup, picked_up, enroute_to_dropoff, arrived_at_dropoff,
	// delivered, cancelled
	switch status {
	case "confirmed", "enroute_to_pickup":
		dasherName := "DoorDash courier"
		if payload.DasherName != "" {
			dasherName = payload.DasherName
		}

		var consumerID, restaurantID string
		err := h.db.Pool.QueryRow(ctx,
			`SELECT user_id, restaurant_id FROM orders WHERE id = $1`,
			orderID).Scan(&consumerID, &restaurantID)
		if err == nil && h.notify != nil {
			h.notify.OrderClaimed(ctx, orderID, consumerID, restaurantID, dasherName)
		}

	case "picked_up":
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE orders SET status = 'picked_up', picked_up_at = $1, updated_at = $1
			  WHERE id = $2 AND status = 'ready' AND external_delivery_id IS NOT NULL`,
			time.Now(), orderID)
		if err != nil {
			slog.Error("doordash webhook: pickup update failed",
				slog.String("order_id", orderID),
				slog.String("error", err.Error()))
			break
		}

		var consumerID string
		_ = h.db.Pool.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID)
		if h.notify != nil && consumerID != "" {
			h.notify.OrderPickedUp(ctx, orderID, consumerID)
		}

	case "delivered":
		now := time.Now()
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE orders SET status = 'delivered', delivered_at = $1, updated_at = $1
			  WHERE id = $2 AND status = 'picked_up' AND external_delivery_id IS NOT NULL`,
			now, orderID)
		if err != nil {
			slog.Error("doordash webhook: delivered update failed",
				slog.String("order_id", orderID),
				slog.String("error", err.Error()))
		}

	case "cancelled":
		slog.Warn("doordash delivery canceled — order needs re-dispatch",
			slog.String("order_id", orderID))
		_, _ = h.db.Pool.Exec(ctx,
			`UPDATE orders SET external_delivery_id = NULL, external_provider = NULL,
			        external_tracking_url = NULL, updated_at = NOW()
			  WHERE id = $1`, orderID)
	}

	w.WriteHeader(http.StatusOK)
}
