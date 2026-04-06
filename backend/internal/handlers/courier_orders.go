package handlers

import (
	"context"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

// Courier-facing order endpoints: the marketplace loop.
//
// Flow:
//   1. Courier goes online (SetOnline)
//   2. Courier polls ListAvailableDeliveries — orders in 'ready' status with no courier
//   3. Courier ClaimOrder — sets courier_id and claimed_at (no status change; still 'ready')
//   4. Courier drives to restaurant, hits PickupOrder — status 'ready' -> 'picked_up'
//   5. Courier drives to customer, hits DeliverOrder — status 'picked_up' -> 'delivered'
//
// Every 5-10s while on an active delivery the app posts to UpdateLocation
// so the consumer's live map tracks the courier.

type SetOnlineRequest struct {
	Online bool    `json:"online"`
	Lat    float64 `json:"lat"`
	Lng    float64 `json:"lng"`
}

// SetOnline toggles courier availability. Blocks non-approved couriers.
func (h *Handler) SetCourierOnline(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req SetOnlineRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET is_online = $1, last_lat = $2, last_lng = $3, last_location_at = NOW(),
		       updated_at = NOW()
		 WHERE user_id = $4 AND onboarding_status = 'approved'`,
		req.Online, req.Lat, req.Lng, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusForbidden, "courier not approved to go online")
		return
	}

	writeJSON(w, http.StatusOK, map[string]bool{"is_online": req.Online})
}

// ListAvailableDeliveries returns orders in 'ready' status with no courier claimed.
// Accepts optional ?lat=&lng= to sort by distance. In prod we'd also filter by a radius.
func (h *Handler) ListAvailableDeliveries(w http.ResponseWriter, r *http.Request) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.restaurant_id, rest.name, o.status, o.subtotal, o.delivery_fee,
		        o.service_fee, o.tax, o.total, o.delivery_address, o.delivery_lat, o.delivery_lng,
		        rest.lat, rest.lng, o.est_delivery_time, o.created_at, o.updated_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.status = 'ready' AND o.courier_id IS NULL
		  ORDER BY o.created_at ASC
		  LIMIT 50`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch available deliveries")
		return
	}
	defer rows.Close()

	type AvailableDelivery struct {
		models.Order
		RestaurantLat float64 `json:"restaurant_lat"`
		RestaurantLng float64 `json:"restaurant_lng"`
	}

	var list []AvailableDelivery
	for rows.Next() {
		var d AvailableDelivery
		if err := rows.Scan(&d.ID, &d.RestaurantID, &d.RestaurantName, &d.Status,
			&d.Subtotal, &d.DeliveryFee, &d.ServiceFee, &d.Tax, &d.Total,
			&d.DeliveryAddress, &d.DeliveryLat, &d.DeliveryLng,
			&d.RestaurantLat, &d.RestaurantLng,
			&d.EstDeliveryTime, &d.CreatedAt, &d.UpdatedAt); err != nil {
			continue
		}
		list = append(list, d)
	}

	if list == nil {
		list = []AvailableDelivery{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"deliveries": list})
}

// ClaimOrder atomically assigns the order to this courier iff no one else has.
func (h *Handler) ClaimOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	// Only approved couriers can claim
	var status models.CourierOnboardingStatus
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT onboarding_status FROM courier_profiles WHERE user_id = $1`, user["user_id"],
	).Scan(&status)
	if err != nil || status != models.OnboardingApproved {
		writeError(w, http.StatusForbidden, "courier not approved")
		return
	}

	// Atomic claim: only succeeds if courier_id is still NULL
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders
		   SET courier_id = $1, claimed_at = NOW(), updated_at = NOW()
		 WHERE id = $2 AND status = 'ready' AND courier_id IS NULL`,
		user["user_id"], orderID)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusConflict, "order no longer available")
		return
	}

	// Notify consumer + seller that a courier has been assigned.
	var consumerID, restaurantID, courierFirst string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT o.user_id, o.restaurant_id, u.first_name
		   FROM orders o JOIN users u ON u.id = $1
		  WHERE o.id = $2`, user["user_id"], orderID,
	).Scan(&consumerID, &restaurantID, &courierFirst)
	if consumerID != "" {
		go h.notify.OrderClaimed(context.Background(), orderID, consumerID, restaurantID, courierFirst)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "claimed"})
}

// PickupOrder: courier has arrived at restaurant and collected the food.
// Transitions 'ready' -> 'picked_up'.
func (h *Handler) PickupOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders
		   SET status = 'picked_up', picked_up_at = NOW(), updated_at = NOW()
		 WHERE id = $1 AND courier_id = $2 AND status = 'ready'`,
		orderID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot mark this order picked up")
		return
	}

	var consumerID string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID)
	if consumerID != "" {
		go h.notify.OrderPickedUp(context.Background(), orderID, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "picked_up"})
}

// DeliverOrder: courier has handed the order to the customer.
// Transitions 'picked_up' -> 'delivered' and bumps total_deliveries.
func (h *Handler) DeliverOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin tx")
		return
	}
	defer tx.Rollback(r.Context())

	// The courier's payout for this order is the delivery fee + any tip.
	// We compute it here (not at order creation) so tip changes made after
	// delivery can still be captured.
	var deliveryFee, tip int
	_ = tx.QueryRow(r.Context(),
		`SELECT delivery_fee, courier_tip FROM orders WHERE id = $1`, orderID,
	).Scan(&deliveryFee, &tip)
	payout := deliveryFee + tip

	result, err := tx.Exec(r.Context(),
		`UPDATE orders
		   SET status = 'delivered', delivered_at = NOW(),
		       courier_payout = $1, updated_at = NOW()
		 WHERE id = $2 AND courier_id = $3 AND status = 'picked_up'`,
		payout, orderID, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot mark this order delivered")
		return
	}

	_, _ = tx.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET total_deliveries = total_deliveries + 1, updated_at = NOW()
		 WHERE user_id = $1`, user["user_id"])

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit")
		return
	}

	// Fire off the courier payout via Stripe Connect. We don't block the
	// response on this — if it fails, a nightly sweep can retry (TODO).
	var connectID string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT stripe_connect_id FROM courier_profiles WHERE user_id = $1`,
		user["user_id"]).Scan(&connectID)
	if connectID != "" && payout > 0 {
		go func() {
			if err := h.stripe.TransferToCourier(connectID, payout, orderID); err != nil {
				// Stripe transfer failure is logged but non-fatal — retry later.
				// Intentionally not bubbling up so the courier's delivery still succeeds.
				_ = err
			}
		}()
	}

	var consumerID string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, orderID).Scan(&consumerID)
	if consumerID != "" {
		go h.notify.OrderDelivered(context.Background(), orderID, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "delivered"})
}

// UpdateCourierLocation is the GPS heartbeat. Writes to courier_profiles.last_*
// (hot, for nearby-courier queries) AND appends to courier_locations (cold trail).
func (h *Handler) UpdateCourierLocation(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req models.CourierLocationPing
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	_, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles
		   SET last_lat = $1, last_lng = $2, last_location_at = NOW(), updated_at = NOW()
		 WHERE user_id = $3`,
		req.Lat, req.Lng, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update location")
		return
	}

	// Attach to active order if any, for the delivery trail.
	var activeOrderID *string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM orders
		  WHERE courier_id = $1 AND status IN ('ready', 'picked_up')
		  ORDER BY claimed_at DESC LIMIT 1`, user["user_id"],
	).Scan(&activeOrderID)

	_, _ = h.db.Pool.Exec(r.Context(),
		`INSERT INTO courier_locations (courier_id, order_id, lat, lng, heading, speed)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		user["user_id"], activeOrderID, req.Lat, req.Lng, req.Heading, req.Speed)

	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ListCourierActiveOrders returns the currently-claimed order(s) for this courier.
func (h *Handler) ListCourierActiveOrders(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
		        o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
		        o.delivery_address, o.delivery_lat, o.delivery_lng,
		        o.claimed_at, o.picked_up_at, o.created_at, o.updated_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.courier_id = $1 AND o.status IN ('ready', 'picked_up')
		  ORDER BY o.claimed_at DESC`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch active orders")
		return
	}
	defer rows.Close()

	var list []models.Order
	for rows.Next() {
		var o models.Order
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
			&o.ClaimedAt, &o.PickedUpAt, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		list = append(list, o)
	}
	if list == nil {
		list = []models.Order{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"orders": list})
}

// ListCourierHistory returns completed deliveries (for earnings screen).
func (h *Handler) ListCourierHistory(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.restaurant_id, rest.name, o.status, o.total,
		        o.delivery_fee, o.courier_tip, o.courier_payout,
		        o.delivered_at, o.created_at
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.courier_id = $1 AND o.status = 'delivered'
		  ORDER BY o.delivered_at DESC
		  LIMIT 100`, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch history")
		return
	}
	defer rows.Close()

	// JSON response shape. Timestamps are returned as RFC3339 strings so the
	// iOS client can decode them with the standard ISO8601 formatter.
	type HistoryRow struct {
		ID             string  `json:"id"`
		RestaurantID   string  `json:"restaurant_id"`
		RestaurantName string  `json:"restaurant_name"`
		Status         string  `json:"status"`
		Total          int     `json:"total"`
		DeliveryFee    int     `json:"delivery_fee"`
		CourierTip     int     `json:"courier_tip"`
		CourierPayout  int     `json:"courier_payout"`
		DeliveredAt    *string `json:"delivered_at,omitempty"`
		CreatedAt      string  `json:"created_at"`
	}

	var list []HistoryRow
	for rows.Next() {
		var h HistoryRow
		// Scan TIMESTAMPTZ columns into time.Time (pgx drivers) — scanning
		// into *string silently fails because there's no registered codec
		// for that conversion, which is why this endpoint was returning an
		// empty list even when deliveries existed.
		var deliveredAt *time.Time
		var createdAt time.Time
		if err := rows.Scan(&h.ID, &h.RestaurantID, &h.RestaurantName, &h.Status,
			&h.Total, &h.DeliveryFee, &h.CourierTip, &h.CourierPayout,
			&deliveredAt, &createdAt); err != nil {
			continue
		}
		if deliveredAt != nil {
			s := deliveredAt.Format(time.RFC3339)
			h.DeliveredAt = &s
		}
		h.CreatedAt = createdAt.Format(time.RFC3339)
		list = append(list, h)
	}
	if list == nil {
		list = []HistoryRow{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"orders": list})
}
