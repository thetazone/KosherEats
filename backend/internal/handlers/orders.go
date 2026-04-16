package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type CreateOrderRequest struct {
	RestaurantID    string     `json:"restaurant_id"`
	DeliveryAddress string     `json:"delivery_address"`
	DeliveryLat     float64    `json:"delivery_lat"`
	DeliveryLng     float64    `json:"delivery_lng"`
	PaymentIntentID string     `json:"payment_intent_id"`
	Tip             int        `json:"tip"` // cents
	ScheduledFor    *time.Time `json:"scheduled_for"` // nil = ASAP; otherwise RFC3339
}

func (h *Handler) CreateOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req CreateOrderRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// Get cart items for this user
	var cart models.Cart
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, user_id, restaurant_id FROM carts WHERE user_id = $1`,
		user["user_id"],
	).Scan(&cart.ID, &cart.UserID, &cart.RestaurantID)

	if err != nil {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Get cart items. ci.unit_price is the modifier-adjusted per-unit price
	// snapshotted at add-to-cart time — use that, not mi.price, so the
	// subtotal matches what the customer saw.
	itemRows, err := h.db.Pool.Query(r.Context(),
		`SELECT ci.id, ci.menu_item_id, mi.name, ci.unit_price, ci.quantity, ci.notes, ci.selected_modifiers
		 FROM cart_items ci JOIN menu_items mi ON ci.menu_item_id = mi.id
		 WHERE ci.cart_id = $1`, cart.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch cart items")
		return
	}
	defer itemRows.Close()

	var items []models.OrderItem
	// Parallel slice of raw JSONB so we can pass the snapshot straight into
	// order_items without a round trip.
	var modifierJSONs [][]byte
	var subtotal int
	for itemRows.Next() {
		var item models.OrderItem
		var modJSON []byte
		if err := itemRows.Scan(&item.ID, &item.MenuItemID, &item.Name, &item.Price, &item.Quantity, &item.Notes, &modJSON); err != nil {
			continue
		}
		if len(modJSON) > 0 {
			_ = json.Unmarshal(modJSON, &item.SelectedModifiers)
		}
		subtotal += item.Price * item.Quantity
		items = append(items, item)
		modifierJSONs = append(modifierJSONs, modJSON)
	}

	if len(items) == 0 {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Calculate fees — MUST match payments.CreatePaymentIntent so the Stripe
	// charge and the saved order total agree.
	deliveryFee := 399 // $3.99
	serviceFee := subtotal * 15 / 100 // 15%
	tax := subtotal * 9 / 100 // ~9% tax
	tip := req.Tip
	if tip < 0 {
		tip = 0
	}
	total := subtotal + deliveryFee + serviceFee + tax + tip

	// Scheduled vs ASAP. Orders scheduled for more than 30 minutes in the
	// future start in 'scheduled' status; the background dispatcher flips
	// them to 'pending' 30 minutes before the delivery window.
	initialStatus := models.OrderPending
	if req.ScheduledFor != nil && req.ScheduledFor.After(time.Now().Add(30*time.Minute)) {
		initialStatus = models.OrderScheduled
	}

	var order models.Order
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id, courier_tip, scheduled_for)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
		 RETURNING id, user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id, courier_tip, est_delivery_time, created_at, updated_at`,
		user["user_id"], cart.RestaurantID, initialStatus,
		subtotal, deliveryFee, serviceFee, tax, total,
		req.DeliveryAddress, req.DeliveryLat, req.DeliveryLng, req.PaymentIntentID, tip, req.ScheduledFor,
	).Scan(&order.ID, &order.UserID, &order.RestaurantID, &order.Status,
		&order.Subtotal, &order.DeliveryFee, &order.ServiceFee, &order.Tax, &order.Total,
		&order.DeliveryAddress, &order.DeliveryLat, &order.DeliveryLng,
		&order.StripePaymentID, &order.CourierTip, &order.EstDeliveryTime, &order.CreatedAt, &order.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create order")
		return
	}

	// Populate restaurant name for the response so the confirmation screen
	// can display it without a second fetch.
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT name FROM restaurants WHERE id = $1`, cart.RestaurantID,
	).Scan(&order.RestaurantName)

	// Insert order items, carrying forward the modifier snapshot JSONB from
	// the cart row so historical orders show exactly what was ordered.
	for i, item := range items {
		modJSON := modifierJSONs[i]
		if len(modJSON) == 0 {
			modJSON = []byte("[]")
		}
		if _, err := h.db.Pool.Exec(r.Context(),
			`INSERT INTO order_items (order_id, menu_item_id, name, price, quantity, notes, selected_modifiers)
			 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			order.ID, item.MenuItemID, item.Name, item.Price, item.Quantity, item.Notes, modJSON); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to insert order items")
			return
		}
	}

	// Clear cart — only reached when all item inserts succeeded above.
	h.db.Pool.Exec(r.Context(), `DELETE FROM cart_items WHERE cart_id = $1`, cart.ID)
	h.db.Pool.Exec(r.Context(), `DELETE FROM carts WHERE id = $1`, cart.ID)

	order.Items = items

	// Notify the seller that a new order just came in.
	go h.notify.OrderCreated(r.Context(), order.RestaurantID, order.RestaurantName, order.ID, order.Total)

	writeJSON(w, http.StatusCreated, order)
}

func (h *Handler) ListOrders(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	// JOIN restaurants so the orders list shows which restaurant each order
	// was from without a second round trip per row.
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
		        o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
		        o.courier_tip, o.delivery_address, o.delivery_lat, o.delivery_lng,
		        o.est_delivery_time, o.created_at, o.updated_at
		   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.user_id = $1
		  ORDER BY o.created_at DESC LIMIT 50`,
		user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}
	defer rows.Close()

	var orders []models.Order
	var orderIDs []string
	for rows.Next() {
		var o models.Order
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.CourierTip, &o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
			&o.EstDeliveryTime, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
		orderIDs = append(orderIDs, o.ID)
	}

	// Batch-load items so the list row can render the item summary without
	// N+1 round trips. Always assign a non-nil slice — a nil slice serializes
	// as JSON null, which fails to decode into Swift's non-optional [OrderItem].
	itemsByOrder := map[string][]models.OrderItem{}
	if len(orderIDs) > 0 {
		itemsByOrder = h.loadOrderItemsBatch(r, orderIDs)
	}
	for i := range orders {
		items := itemsByOrder[orders[i].ID]
		if items == nil {
			items = []models.OrderItem{}
		}
		orders[i].Items = items
	}

	if orders == nil {
		orders = []models.Order{}
	}

	writeJSON(w, http.StatusOK, orders)
}

func (h *Handler) GetOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	id := chi.URLParam(r, "id")

	order, err := h.loadOrderWithCourier(r, id, "user_id", user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}
	order.Items = h.loadOrderItems(r, id)

	writeJSON(w, http.StatusOK, order)
}

// loadOrderItems fetches the line items for an order, including the
// selected_modifiers JSONB snapshot. Used by both consumer GetOrder and
// seller GetSellerOrder.
func (h *Handler) loadOrderItems(r *http.Request, orderID string) []models.OrderItem {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, order_id, menu_item_id, name, price, quantity, notes, selected_modifiers
		   FROM order_items WHERE order_id = $1 ORDER BY id`, orderID)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var it models.OrderItem
		var modJSON []byte
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name,
			&it.Price, &it.Quantity, &it.Notes, &modJSON); err != nil {
			continue
		}
		if len(modJSON) > 0 {
			_ = json.Unmarshal(modJSON, &it.SelectedModifiers)
		}
		items = append(items, it)
	}
	return items
}

// loadOrderWithCourier fetches a single order joined with the assigned courier's
// public info (if any). ownerColumn is either "user_id" (consumer lookup) or
// "restaurant_owner" — restaurant ownership is checked via join instead.
func (h *Handler) loadOrderWithCourier(r *http.Request, orderID, scope, scopeValue string) (*models.Order, error) {
	var query string
	if scope == "user_id" {
		query = `
			SELECT o.id, o.user_id, o.restaurant_id, rest.name, rest.lat, rest.lng, o.status,
			       o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
			       o.delivery_address, o.delivery_lat, o.delivery_lng,
			       o.stripe_payment_id, o.est_delivery_time,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.created_at, o.updated_at,
			       cu.first_name, cu.phone, cu.avatar_url,
			       cp.vehicle_type, cp.vehicle_make, cp.vehicle_model, cp.vehicle_color,
			       cp.license_plate, cp.rating, cp.total_deliveries,
			       cp.last_lat, cp.last_lng,
			       cr.stars,
			       consumer.first_name, consumer.phone
			  FROM orders o
			  JOIN restaurants rest ON o.restaurant_id = rest.id
			  LEFT JOIN users cu ON o.courier_id = cu.id
			  LEFT JOIN courier_profiles cp ON cp.user_id = o.courier_id
			  LEFT JOIN courier_ratings cr ON cr.order_id = o.id
			  LEFT JOIN users consumer ON o.user_id = consumer.id
			 WHERE o.id = $1 AND o.user_id = $2`
	} else { // seller scope — scopeValue is the owner's user id
		query = `
			SELECT o.id, o.user_id, o.restaurant_id, rest.name, rest.lat, rest.lng, o.status,
			       o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
			       o.delivery_address, o.delivery_lat, o.delivery_lng,
			       o.stripe_payment_id, o.est_delivery_time,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.created_at, o.updated_at,
			       cu.first_name, cu.phone, cu.avatar_url,
			       cp.vehicle_type, cp.vehicle_make, cp.vehicle_model, cp.vehicle_color,
			       cp.license_plate, cp.rating, cp.total_deliveries,
			       cp.last_lat, cp.last_lng,
			       cr.stars,
			       consumer.first_name, consumer.phone
			  FROM orders o
			  JOIN restaurants rest ON o.restaurant_id = rest.id
			  LEFT JOIN users cu ON o.courier_id = cu.id
			  LEFT JOIN courier_profiles cp ON cp.user_id = o.courier_id
			  LEFT JOIN courier_ratings cr ON cr.order_id = o.id
			  LEFT JOIN users consumer ON o.user_id = consumer.id
			 WHERE o.id = $1 AND rest.owner_id = $2`
	}

	var o models.Order
	var (
		courierID                                 *string
		cFirst, cPhone, cAvatar                   *string
		cVehType, cMake, cModel, cColor, cPlate   *string
		cRating                                   *float64
		cTotal                                    *int
		cLat, cLng                                *float64
		ratingStars                               *int
		consumerFirst, consumerPhone              *string
	)

	err := h.db.Pool.QueryRow(r.Context(), query, orderID, scopeValue).Scan(
		&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.RestaurantLat, &o.RestaurantLng, &o.Status,
		&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
		&o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
		&o.StripePaymentID, &o.EstDeliveryTime,
		&courierID, &o.ClaimedAt, &o.PickedUpAt, &o.DeliveredAt,
		&o.CourierPayout, &o.CourierTip,
		&o.CreatedAt, &o.UpdatedAt,
		&cFirst, &cPhone, &cAvatar,
		&cVehType, &cMake, &cModel, &cColor, &cPlate, &cRating, &cTotal,
		&cLat, &cLng,
		&ratingStars,
		&consumerFirst, &consumerPhone,
	)
	if err != nil {
		return nil, err
	}

	if courierID != nil {
		o.CourierID = courierID
		o.Courier = &models.CourierPublic{
			ID:              *courierID,
			FirstName:       strOr(cFirst, ""),
			Phone:           strOr(cPhone, ""),
			AvatarURL:       strOr(cAvatar, ""),
			VehicleType:     models.VehicleType(strOr(cVehType, "")),
			VehicleMake:     strOr(cMake, ""),
			VehicleModel:    strOr(cModel, ""),
			VehicleColor:    strOr(cColor, ""),
			LicensePlate:    strOr(cPlate, ""),
			Rating:          floatOr(cRating, 5.0),
			TotalDeliveries: intOr(cTotal, 0),
			Lat:             floatOr(cLat, 0),
			Lng:             floatOr(cLng, 0),
		}
	}
	o.CourierRating = ratingStars
	o.CustomerName = strOr(consumerFirst, "")
	o.CustomerPhone = strOr(consumerPhone, "")

	return &o, nil
}

func strOr(p *string, def string) string {
	if p == nil {
		return def
	}
	return *p
}
func floatOr(p *float64, def float64) float64 {
	if p == nil {
		return def
	}
	return *p
}
func intOr(p *int, def int) int {
	if p == nil {
		return def
	}
	return *p
}

func (h *Handler) CancelOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	id := chi.URLParam(r, "id")

	// Allow cancel while pending OR accepted (before kitchen starts preparing).
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 WHERE id = $2 AND user_id = $3 AND status IN ($4, $5)`,
		models.OrderCancelled, id, user["user_id"], models.OrderPending, models.OrderAccepted)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot cancel this order")
		return
	}

	// Best-effort Stripe refund -- log failure but don't block the cancel.
	var paymentID string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT stripe_payment_id FROM orders WHERE id = $1`, id).Scan(&paymentID)
	if paymentID != "" {
		if err := h.stripe.RefundPaymentIntent(paymentID); err != nil {
			slog.Error("cancel refund failed",
				slog.String("order_id", id),
				slog.String("error", err.Error()))
		}
	}

	order, err := h.loadOrderWithCourier(r, id, "user_id", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to reload cancelled order")
		return
	}
	order.Items = h.loadOrderItems(r, id)

	writeJSON(w, http.StatusOK, order)
}

// RateOrder lets a consumer rate their courier after delivery. One rating
// per order (enforced by unique(order_id)). On insert the courier's
// aggregate rating is recomputed from the average of all their stars so the
// displayed rating stays fresh. Only the consumer who placed the order can
// rate it, and only once the order is in `delivered` state.
func (h *Handler) RateOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	var req struct {
		Stars   int    `json:"stars"`
		Comment string `json:"comment"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if req.Stars < 1 || req.Stars > 5 {
		writeError(w, http.StatusBadRequest, "stars must be between 1 and 5")
		return
	}

	// Confirm the order belongs to this consumer, is delivered, and has a
	// courier. We need the courier_id for the ratings row and the avg
	// recompute.
	var courierID *string
	var status string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT status, courier_id FROM orders WHERE id = $1 AND user_id = $2`,
		orderID, user["user_id"],
	).Scan(&status, &courierID)
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}
	if status != string(models.OrderDelivered) {
		writeError(w, http.StatusBadRequest, "order is not delivered")
		return
	}
	if courierID == nil {
		writeError(w, http.StatusBadRequest, "order has no courier to rate")
		return
	}

	comment := strings.TrimSpace(req.Comment)
	var commentArg any = comment
	if comment == "" {
		commentArg = nil
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO courier_ratings (order_id, consumer_id, courier_id, stars, comment)
		 VALUES ($1, $2, $3, $4, $5)
		 ON CONFLICT (order_id) DO NOTHING`,
		orderID, user["user_id"], *courierID, req.Stars, commentArg,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save rating")
		return
	}

	// Recompute courier's aggregate rating from the average stars across all
	// submitted ratings. Kept as a single UPDATE so the read path doesn't
	// need to aggregate on every fetch.
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE courier_profiles SET rating = COALESCE((
		     SELECT AVG(stars)::FLOAT FROM courier_ratings WHERE courier_id = $1
		 ), 5.0), updated_at = NOW() WHERE id = $1`,
		*courierID,
	); err != nil {
		// Non-fatal — the rating was saved even if the aggregate update
		// failed; the next submission will correct it.
		log.Printf("[rating] recompute failed for courier=%s: %v", *courierID, err)
	}

	writeJSON(w, http.StatusOK, map[string]any{"stars": req.Stars})
}

// Seller order handlers

func (h *Handler) ListSellerOrders(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	// Scoped to the resolved restaurant so multi-restaurant sellers see
	// only the orders for the one they've currently selected in the app.
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
		        o.subtotal, o.delivery_fee, o.service_fee, o.tax, o.total,
		        o.courier_tip, o.delivery_address, o.est_delivery_time,
		        o.created_at, o.updated_at
		   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.restaurant_id = $1
		  ORDER BY o.created_at DESC LIMIT 50`,
		restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}
	defer rows.Close()

	var orders []models.Order
	var orderIDs []string
	for rows.Next() {
		var o models.Order
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.CourierTip, &o.DeliveryAddress, &o.EstDeliveryTime,
			&o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
		orderIDs = append(orderIDs, o.ID)
	}

	// Batch-load items for every listed order so the dashboard can render
	// item summaries without N+1 queries.
	itemsByOrder := map[string][]models.OrderItem{}
	if len(orderIDs) > 0 {
		itemsByOrder = h.loadOrderItemsBatch(r, orderIDs)
	}
	for i := range orders {
		items := itemsByOrder[orders[i].ID]
		if items == nil {
			items = []models.OrderItem{}
		}
		orders[i].Items = items
	}

	if orders == nil {
		orders = []models.Order{}
	}

	writeJSON(w, http.StatusOK, orders)
}

// loadOrderItemsBatch fetches order items for many orders in a single query
// and buckets them by order id. Used by ListSellerOrders so the dashboard
// can show item summaries without N round trips.
func (h *Handler) loadOrderItemsBatch(r *http.Request, orderIDs []string) map[string][]models.OrderItem {
	out := map[string][]models.OrderItem{}
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, order_id, menu_item_id, name, price, quantity, notes, selected_modifiers
		   FROM order_items WHERE order_id = ANY($1) ORDER BY order_id, id`, orderIDs)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var it models.OrderItem
		var modJSON []byte
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name,
			&it.Price, &it.Quantity, &it.Notes, &modJSON); err != nil {
			continue
		}
		if len(modJSON) > 0 {
			_ = json.Unmarshal(modJSON, &it.SelectedModifiers)
		}
		out[it.OrderID] = append(out[it.OrderID], it)
	}
	return out
}

func (h *Handler) GetSellerOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user := getUserFromContext(r)

	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}
	order.Items = h.loadOrderItems(r, id)

	writeJSON(w, http.StatusOK, order)
}

func (h *Handler) AcceptOrder(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderAccepted, models.OrderPending)
}

func (h *Handler) MarkOrderPreparing(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderPreparing, models.OrderAccepted)
}

func (h *Handler) MarkOrderReady(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderReady, models.OrderPreparing)

	// After marking ready, broadcast a "new delivery available" push to
	// every currently-online courier. We re-query for the order's restaurant
	// name + delivery fee so the notification has context.
	orderID := chi.URLParam(r, "id")
	var restaurantName string
	var deliveryFee int
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT rest.name, o.delivery_fee
		   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1`, orderID,
	).Scan(&restaurantName, &deliveryFee)
	if restaurantName != "" {
		go h.notify.OrderReady(context.Background(), orderID, restaurantName, deliveryFee)
	}
}

// Sellers no longer mark orders delivered — that transition is owned by the
// courier app (picked_up → delivered). Keeping only reject.

// RejectOrder refunds the customer first, then flips status to 'rejected'.
// Refund-then-flip mirrors tryStaleReject in the dispatcher: a Stripe failure
// leaves the order 'pending' for a retry instead of stranding the customer
// in a "rejected but not refunded" state.
func (h *Handler) RejectOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user := getUserFromContext(r)

	var paymentIntentID string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT orders.stripe_payment_id
		   FROM orders JOIN restaurants ON orders.restaurant_id = restaurants.id
		  WHERE orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = $3`,
		id, user["user_id"], models.OrderPending,
	).Scan(&paymentIntentID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	if paymentIntentID != "" && h.stripe != nil {
		if err := h.stripe.RefundPaymentIntent(paymentIntentID); err != nil {
			writeError(w, http.StatusBadGateway, "refund failed, order not rejected")
			return
		}
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 FROM restaurants WHERE orders.restaurant_id = restaurants.id
		 AND orders.id = $2 AND restaurants.owner_id = $3 AND orders.status = $4`,
		models.OrderRejected, id, user["user_id"], models.OrderPending)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}
	order.Items = h.loadOrderItems(r, id)
	writeJSON(w, http.StatusOK, order)
}

// StreamOrderLocation streams courier location pings for an order as
// Server-Sent Events. The consumer app holds this open for the duration of
// the order-tracking screen; each courier UpdateCourierLocation call fans an
// event to every open stream for that order via the in-memory broker.
//
// We also emit a heartbeat comment every 25s to keep intermediaries from
// dropping the idle connection, and clear the server's WriteTimeout via
// http.ResponseController so the long-lived connection isn't killed.
func (h *Handler) StreamOrderLocation(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user := getUserFromContext(r)

	// Ownership check — same contract as GetOrder: only the placing consumer
	// can stream. (Seller/courier already have first-class order access via
	// their own apps; if we need them later, widen here.)
	var exists bool
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT EXISTS(SELECT 1 FROM orders WHERE id = $1 AND user_id = $2)`,
		id, user["user_id"],
	).Scan(&exists); err != nil || !exists {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	// The server has a 15s WriteTimeout; clear the per-write deadline so this
	// long-lived stream isn't killed mid-connection.
	rc := http.NewResponseController(w)
	_ = rc.SetWriteDeadline(time.Time{})
	flusher.Flush()

	events, unsub := h.location.Subscribe(id)
	defer unsub()

	heartbeat := time.NewTicker(25 * time.Second)
	defer heartbeat.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case <-heartbeat.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
			flusher.Flush()
		case e, ok := <-events:
			if !ok {
				return
			}
			payload, err := json.Marshal(e)
			if err != nil {
				continue
			}
			if _, err := fmt.Fprintf(w, "event: location\ndata: %s\n\n", payload); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

func (h *Handler) updateSellerOrderStatus(w http.ResponseWriter, r *http.Request, newStatus, requiredStatus models.OrderStatus) {
	id := chi.URLParam(r, "id")
	user := getUserFromContext(r)

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 FROM restaurants WHERE orders.restaurant_id = restaurants.id
		 AND orders.id = $2 AND restaurants.owner_id = $3 AND orders.status = $4`,
		newStatus, id, user["user_id"], requiredStatus)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	// Return the full refreshed order (courier info + items) so the seller
	// client can update its local state without a follow-up GET.
	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}
	order.Items = h.loadOrderItems(r, id)
	writeJSON(w, http.StatusOK, order)
}
