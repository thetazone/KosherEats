package handlers

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"
)

type uberWebhookPayload struct {
	Kind       string              `json:"kind"`
	DeliveryID string              `json:"delivery_id"`
	Status     string              `json:"status"`
	Data       uberWebhookData     `json:"data"`
}

type uberWebhookData struct {
	Status           string          `json:"status"`
	CourierImminent  bool            `json:"courier_imminent"`
	TrackingURL      string          `json:"tracking_url"`
	Fee              int             `json:"fee"`
	Tip              int             `json:"tip"`
	Courier          *uberCourier    `json:"courier,omitempty"`
	ExternalID       string          `json:"external_id"`
}

type uberCourier struct {
	Name        string       `json:"name"`
	Phone       string       `json:"phone_number"`
	Rating      float64      `json:"rating"`
	VehicleType string       `json:"vehicle_type"`
	Location    *uberLatLng  `json:"location,omitempty"`
	ImgHref     string       `json:"img_href"`
}

type uberLatLng struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

func (h *Handler) UberDirectWebhook(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	if h.uber != nil && !h.uber.VerifyWebhook(body, r.Header.Get("X-Uber-Signature")) {
		slog.Warn("uber direct webhook signature verification failed")
		writeError(w, http.StatusBadRequest, "invalid signature")
		return
	}

	var payload uberWebhookPayload
	if err := json.Unmarshal(body, &payload); err != nil {
		slog.Error("uber direct webhook: bad json", slog.String("error", err.Error()))
		w.WriteHeader(http.StatusOK)
		return
	}

	if payload.Kind != "event.delivery_status" {
		w.WriteHeader(http.StatusOK)
		return
	}

	ctx := r.Context()
	externalID := payload.Data.ExternalID
	status := payload.Data.Status

	slog.Info("uber direct webhook",
		slog.String("delivery_id", payload.DeliveryID),
		slog.String("status", status),
		slog.String("order_id", externalID))

	if externalID == "" {
		w.WriteHeader(http.StatusOK)
		return
	}

	switch status {
	case "pickup":
		courierName := "Uber courier"
		if payload.Data.Courier != nil && payload.Data.Courier.Name != "" {
			courierName = payload.Data.Courier.Name
		}

		var consumerID, restaurantID string
		err := h.db.Pool.QueryRow(ctx,
			`SELECT user_id, restaurant_id FROM orders WHERE id = $1`,
			externalID).Scan(&consumerID, &restaurantID)
		if err != nil {
			slog.Error("uber webhook: order lookup failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			break
		}

		if h.notify != nil {
			h.notify.OrderClaimed(ctx, externalID, consumerID, restaurantID, courierName)
		}

	case "pickup_complete":
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE orders SET status = 'picked_up', picked_up_at = $1, updated_at = $1
			  WHERE id = $2 AND status = 'ready'`,
			time.Now(), externalID)
		if err != nil {
			slog.Error("uber webhook: pickup_complete update failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			break
		}

		var consumerID string
		_ = h.db.Pool.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, externalID).Scan(&consumerID)
		if h.notify != nil && consumerID != "" {
			h.notify.OrderPickedUp(ctx, externalID, consumerID)
		}

	case "delivered":
		now := time.Now()
		_, err := h.db.Pool.Exec(ctx,
			`UPDATE orders SET status = 'delivered', delivered_at = $1, updated_at = $1
			  WHERE id = $2 AND status = 'picked_up'`,
			now, externalID)
		if err != nil {
			slog.Error("uber webhook: delivered update failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
		}

	case "canceled":
		slog.Warn("uber direct delivery canceled — order needs re-dispatch or consumer notification",
			slog.String("order_id", externalID),
			slog.String("delivery_id", payload.DeliveryID))

		_, err := h.db.Pool.Exec(ctx,
			`UPDATE orders SET external_delivery_id = NULL, external_provider = NULL,
			        external_tracking_url = NULL, updated_at = NOW()
			  WHERE id = $1`,
			externalID)
		if err != nil {
			slog.Error("uber webhook: cancel cleanup failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
		}
	}

	w.WriteHeader(http.StatusOK)
}
