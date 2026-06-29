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
)

// flexFloat accepts a JSON number OR a quoted numeric string. Uber Direct sends
// some numeric fields (observed: courier.rating as "4.9") as strings, which
// would otherwise fail the whole-payload unmarshal and silently drop the status
// update bundled with it. Tolerating both keeps one stringly-typed field from
// stranding the delivery.
type flexFloat float64

func (f *flexFloat) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		return nil
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return err
	}
	*f = flexFloat(v)
	return nil
}

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
	Rating      flexFloat    `json:"rating"`
	VehicleType string       `json:"vehicle_type"`
	Location    *uberLatLng  `json:"location,omitempty"`
	ImgHref     string       `json:"img_href"`
}

type uberLatLng struct {
	Lat flexFloat `json:"lat"`
	Lng flexFloat `json:"lng"`
}

func (h *Handler) UberDirectWebhook(w http.ResponseWriter, r *http.Request) {
	if h.uber == nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		w.WriteHeader(http.StatusOK)
		return
	}

	if !h.uber.VerifyWebhook(body, r.Header.Get("X-Uber-Signature")) {
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

	// Idempotency + atomicity: claim the event and apply the order-state change in
	// one transaction (see migration 052). A replayed 'canceled' would otherwise
	// re-clear the provider linkage and re-arm auto-dispatch → a new paid delivery.
	tx, err := h.db.Pool.Begin(ctx)
	if err != nil {
		slog.Error("uber webhook: begin tx failed", slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	fresh, err := claimWebhookEvent(ctx, tx, "uber_direct", webhookEventID(body), status)
	if err != nil {
		slog.Error("uber webhook: claim event failed",
			slog.String("order_id", externalID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if !fresh {
		slog.Info("uber webhook: duplicate event ignored", slog.String("order_id", externalID))
		w.WriteHeader(http.StatusOK)
		return
	}

	// Notifications are side effects that must escape the tx exactly-once: capture
	// them here and fire only after Commit so a rollback can't send a stray push.
	var postCommit []func()

	switch status {
	case "pickup":
		courierName := "Uber courier"
		if payload.Data.Courier != nil && payload.Data.Courier.Name != "" {
			courierName = payload.Data.Courier.Name
		}

		var consumerID, restaurantID string
		err := tx.QueryRow(ctx,
			`SELECT user_id, restaurant_id FROM orders WHERE id = $1`,
			externalID).Scan(&consumerID, &restaurantID)
		if err != nil {
			// 'pickup' only drives a notification (no state mutation); a lookup miss
			// is non-recoverable context, so record the event and skip the push
			// rather than forcing endless provider retries.
			slog.Error("uber webhook: order lookup failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			break
		}
		if h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderClaimed(context.Background(), externalID, consumerID, restaurantID, courierName)
			})
		}

	case "pickup_complete":
		if _, err := tx.Exec(ctx,
			`UPDATE orders SET status = 'picked_up', picked_up_at = $1, updated_at = $1
			  WHERE id = $2 AND status IN ('accepted', 'preparing', 'ready') AND external_delivery_id IS NOT NULL`,
			time.Now(), externalID); err != nil {
			// State mutation failed — fail closed so Uber retries (the rolled-back
			// claim lets the retry reprocess) rather than stranding the order.
			slog.Error("uber webhook: pickup_complete update failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		var consumerID string
		_ = tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, externalID).Scan(&consumerID)
		if h.notify != nil && consumerID != "" {
			postCommit = append(postCommit, func() {
				h.notify.OrderPickedUp(context.Background(), externalID, consumerID)
			})
		}

	case "delivered":
		now := time.Now()
		// Accept any pre-delivered external-dispatched state, not just 'picked_up':
		// if the pickup_complete webhook was dropped/out-of-order the order is still
		// 'ready', and keying only on 'picked_up' would strand it permanently. A
		// 'delivered' event is authoritative.
		tag, err := tx.Exec(ctx,
			`UPDATE orders SET status = 'delivered', delivered_at = $1, updated_at = $1
			  WHERE id = $2 AND status IN ('accepted','preparing','ready','picked_up')
			    AND external_delivery_id IS NOT NULL`,
			now, externalID)
		if err != nil {
			slog.Error("uber webhook: delivered update failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		// Only notify when WE actually flipped it to delivered — a 0-row match is a
		// late webhook on an already-terminal order, which must not fire a second
		// "delivered" push. Still commit the claim so the event is recorded.
		if tag.RowsAffected() == 0 {
			break
		}

		var consumerID string
		if err := tx.QueryRow(ctx,
			`SELECT user_id FROM orders WHERE id = $1`, externalID).Scan(&consumerID); err != nil {
			slog.Warn("uber webhook: failed to fetch consumer for delivery notification",
				slog.String("order_id", externalID), slog.String("error", err.Error()))
		}
		if consumerID != "" && h.notify != nil {
			postCommit = append(postCommit, func() {
				h.notify.OrderDelivered(context.Background(), externalID, consumerID)
			})
		}

	case "canceled":
		slog.Warn("uber direct delivery canceled — order needs re-dispatch or consumer notification",
			slog.String("order_id", externalID),
			slog.String("delivery_id", payload.DeliveryID))

		if _, err := tx.Exec(ctx,
			`UPDATE orders
			    SET external_delivery_id = NULL, external_provider = NULL,
			        external_tracking_url = NULL,
			        -- A provider cancel after we recorded pickup would otherwise
			        -- strand the order past 'ready' forever (sweepAutoDispatch only
			        -- re-dispatches 'ready' orders). Reset picked_up -> ready so it
			        -- re-enters the dispatch pipeline; a 'ready' cancel just clears
			        -- the linkage and is picked up by the next sweep as before.
			        status = CASE WHEN status = 'picked_up' THEN 'ready' ELSE status END,
			        updated_at = NOW()
			  WHERE id = $1 AND status IN ('ready', 'picked_up')`,
			externalID); err != nil {
			slog.Error("uber webhook: cancel cleanup failed",
				slog.String("order_id", externalID),
				slog.String("error", err.Error()))
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
	}

	if err := tx.Commit(ctx); err != nil {
		slog.Error("uber webhook: commit failed",
			slog.String("order_id", externalID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	for _, f := range postCommit {
		f()
	}

	w.WriteHeader(http.StatusOK)
}
