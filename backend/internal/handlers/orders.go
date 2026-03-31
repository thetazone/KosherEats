package handlers

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type CreateOrderRequest struct {
	RestaurantID    string `json:"restaurant_id"`
	DeliveryAddress string `json:"delivery_address"`
	DeliveryLat     float64 `json:"delivery_lat"`
	DeliveryLng     float64 `json:"delivery_lng"`
	PaymentIntentID string `json:"payment_intent_id"`
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

	// Get cart items
	itemRows, err := h.db.Pool.Query(r.Context(),
		`SELECT ci.id, ci.menu_item_id, mi.name, mi.price, ci.quantity, ci.notes
		 FROM cart_items ci JOIN menu_items mi ON ci.menu_item_id = mi.id
		 WHERE ci.cart_id = $1`, cart.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch cart items")
		return
	}
	defer itemRows.Close()

	var items []models.OrderItem
	var subtotal int
	for itemRows.Next() {
		var item models.OrderItem
		if err := itemRows.Scan(&item.ID, &item.MenuItemID, &item.Name, &item.Price, &item.Quantity, &item.Notes); err != nil {
			continue
		}
		subtotal += item.Price * item.Quantity
		items = append(items, item)
	}

	if len(items) == 0 {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Calculate fees
	deliveryFee := 399 // $3.99
	serviceFee := subtotal * 15 / 100 // 15%
	tax := subtotal * 9 / 100 // ~9% tax
	total := subtotal + deliveryFee + serviceFee + tax

	var order models.Order
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
		 RETURNING id, user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id, est_delivery_time, created_at, updated_at`,
		user["user_id"], cart.RestaurantID, models.OrderPending,
		subtotal, deliveryFee, serviceFee, tax, total,
		req.DeliveryAddress, req.DeliveryLat, req.DeliveryLng, req.PaymentIntentID,
	).Scan(&order.ID, &order.UserID, &order.RestaurantID, &order.Status,
		&order.Subtotal, &order.DeliveryFee, &order.ServiceFee, &order.Tax, &order.Total,
		&order.DeliveryAddress, &order.DeliveryLat, &order.DeliveryLng,
		&order.StripePaymentID, &order.EstDeliveryTime, &order.CreatedAt, &order.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create order")
		return
	}

	// Insert order items
	for _, item := range items {
		h.db.Pool.Exec(r.Context(),
			`INSERT INTO order_items (order_id, menu_item_id, name, price, quantity, notes)
			 VALUES ($1, $2, $3, $4, $5, $6)`,
			order.ID, item.MenuItemID, item.Name, item.Price, item.Quantity, item.Notes)
	}

	// Clear cart
	h.db.Pool.Exec(r.Context(), `DELETE FROM cart_items WHERE cart_id = $1`, cart.ID)
	h.db.Pool.Exec(r.Context(), `DELETE FROM carts WHERE id = $1`, cart.ID)

	order.Items = items
	writeJSON(w, http.StatusCreated, order)
}

func (h *Handler) ListOrders(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, user_id, restaurant_id, status, subtotal, delivery_fee, service_fee,
		 tax, total, delivery_address, created_at, updated_at
		 FROM orders WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50`,
		user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}
	defer rows.Close()

	var orders []models.Order
	for rows.Next() {
		var o models.Order
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.DeliveryAddress, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
	}

	if orders == nil {
		orders = []models.Order{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{"orders": orders})
}

func (h *Handler) GetOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	id := chi.URLParam(r, "id")

	var order models.Order
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, user_id, restaurant_id, status, subtotal, delivery_fee, service_fee,
		 tax, total, delivery_address, delivery_lat, delivery_lng, stripe_payment_id,
		 est_delivery_time, created_at, updated_at
		 FROM orders WHERE id = $1 AND user_id = $2`, id, user["user_id"],
	).Scan(&order.ID, &order.UserID, &order.RestaurantID, &order.Status,
		&order.Subtotal, &order.DeliveryFee, &order.ServiceFee, &order.Tax, &order.Total,
		&order.DeliveryAddress, &order.DeliveryLat, &order.DeliveryLng,
		&order.StripePaymentID, &order.EstDeliveryTime, &order.CreatedAt, &order.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}

	writeJSON(w, http.StatusOK, order)
}

func (h *Handler) CancelOrder(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	id := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 WHERE id = $2 AND user_id = $3 AND status = $4`,
		models.OrderCancelled, id, user["user_id"], models.OrderPending)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot cancel this order")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "cancelled"})
}

// Seller order handlers

func (h *Handler) ListSellerOrders(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, o.status, o.subtotal, o.delivery_fee,
		 o.service_fee, o.tax, o.total, o.delivery_address, o.created_at, o.updated_at
		 FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		 WHERE rest.owner_id = $1 ORDER BY o.created_at DESC LIMIT 50`,
		user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}
	defer rows.Close()

	var orders []models.Order
	for rows.Next() {
		var o models.Order
		if err := rows.Scan(&o.ID, &o.UserID, &o.RestaurantID, &o.Status,
			&o.Subtotal, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.DeliveryAddress, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
	}

	if orders == nil {
		orders = []models.Order{}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{"orders": orders})
}

func (h *Handler) GetSellerOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user := getUserFromContext(r)

	var order models.Order
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT o.id, o.user_id, o.restaurant_id, o.status, o.subtotal, o.delivery_fee,
		 o.service_fee, o.tax, o.total, o.delivery_address, o.delivery_lat, o.delivery_lng,
		 o.created_at, o.updated_at
		 FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		 WHERE o.id = $1 AND rest.owner_id = $2`, id, user["user_id"],
	).Scan(&order.ID, &order.UserID, &order.RestaurantID, &order.Status,
		&order.Subtotal, &order.DeliveryFee, &order.ServiceFee, &order.Tax, &order.Total,
		&order.DeliveryAddress, &order.DeliveryLat, &order.DeliveryLng,
		&order.CreatedAt, &order.UpdatedAt)

	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}

	writeJSON(w, http.StatusOK, order)
}

func (h *Handler) AcceptOrder(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderAccepted, models.OrderPending)
}

func (h *Handler) MarkOrderReady(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderReady, models.OrderPreparing)
}

func (h *Handler) CompleteOrder(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderDelivered, models.OrderReady)
}

func (h *Handler) RejectOrder(w http.ResponseWriter, r *http.Request) {
	h.updateSellerOrderStatus(w, r, models.OrderRejected, models.OrderPending)
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

	writeJSON(w, http.StatusOK, map[string]string{"status": string(newStatus)})
}
