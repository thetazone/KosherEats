package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
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
	// FulfillmentType is "delivery" (default) or "pickup". Pickup orders
	// skip the courier handoff entirely — the seller marks them completed
	// when the customer arrives. Empty/missing defaults to delivery for
	// back-compat with older clients that don't know about pickup.
	FulfillmentType string `json:"fulfillment_type,omitempty"`
	// AppliedDealID, when set, applies the deal's discount to the subtotal
	// before tax. Must match the id passed to /payments/intent so the
	// recorded order total agrees with the Stripe charge.
	AppliedDealID string `json:"applied_deal_id,omitempty"`
}

func (h *Handler) CreateOrder(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req CreateOrderRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to start transaction")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	// FOR UPDATE serialises concurrent CreateOrder calls on the same cart;
	// the second caller will find no cart row after the first commits.
	var cart models.Cart
	err = tx.QueryRow(r.Context(),
		`SELECT id, user_id, restaurant_id FROM carts WHERE user_id = $1 FOR UPDATE`,
		user["user_id"],
	).Scan(&cart.ID, &cart.UserID, &cart.RestaurantID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	// Get cart items. ci.unit_price is the modifier-adjusted per-unit price
	// snapshotted at add-to-cart time — use that, not mi.price, so the
	// subtotal matches what the customer saw.
	itemRows, err := tx.Query(r.Context(),
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
			slog.Error("failed to scan cart item row during order creation", "cart_id", cart.ID, "error", err)
			writeError(w, http.StatusInternalServerError, "failed to read cart items")
			return
		}
		if len(modJSON) > 0 {
			if err := json.Unmarshal(modJSON, &item.SelectedModifiers); err != nil {
				slog.Error("failed to unmarshal modifier JSON during order creation", "cart_id", cart.ID, "error", err)
				writeError(w, http.StatusInternalServerError, "failed to read cart item modifiers")
				return
			}
		}
		subtotal += item.Price * item.Quantity
		items = append(items, item)
		modifierJSONs = append(modifierJSONs, modJSON)
	}

	if len(items) == 0 {
		writeError(w, http.StatusBadRequest, "cart is empty")
		return
	}

	fulfillmentType := req.FulfillmentType
	if fulfillmentType == "" {
		fulfillmentType = "delivery"
	}
	if fulfillmentType != "delivery" && fulfillmentType != "pickup" {
		writeError(w, http.StatusBadRequest, "fulfillment_type must be 'delivery' or 'pickup'")
		return
	}
	if fulfillmentType == "delivery" && req.DeliveryAddress == "" {
		writeError(w, http.StatusBadRequest, "delivery address is required for delivery orders")
		return
	}

	deliveryFee := 0
	if fulfillmentType != "pickup" {
		var restAddress string
		err := tx.QueryRow(r.Context(),
			`SELECT COALESCE(street || ', ' || city || ', ' || state || ' ' || zip_code, '')
			   FROM restaurants WHERE id = $1`, cart.RestaurantID,
		).Scan(&restAddress)
		if err == nil && restAddress != "" {
			quote := h.quoteDeliveryFee(r.Context(), restAddress, req.DeliveryAddress)
			deliveryFee = quote.consumerFee
		} else {
			deliveryFee = deliveryFeeFallbackCents
		}
	}
	serviceFee := 0
	// Apply the deal discount before tax so the recorded total agrees with
	// the Stripe charge that CreatePaymentIntent computed using the same
	// helper. resolveDealDiscount returns 0 when AppliedDealID is empty.
	discount, err := h.resolveDealDiscount(r.Context(), req.AppliedDealID, cart.RestaurantID, user["user_id"], subtotal, items)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	discountedSubtotal := subtotal - discount
	tax := discountedSubtotal * h.cfg.TaxRatePercent / 100
	tip := req.Tip
	if tip < 0 {
		tip = 0
	}
	if fulfillmentType == "pickup" {
		// No courier means no tip lane; ignore any client-supplied value.
		tip = 0
	}
	if tip > discountedSubtotal {
		writeError(w, http.StatusBadRequest, "tip cannot exceed subtotal")
		return
	}
	total := discountedSubtotal + deliveryFee + serviceFee + tax + tip

	if err := h.stripe.VerifyPaymentSucceeded(req.PaymentIntentID, user["user_id"], total); err != nil {
		slog.Warn("CreateOrder: payment verification failed",
			slog.String("payment_intent_id", req.PaymentIntentID),
			slog.String("user_id", user["user_id"]),
			slog.String("error", err.Error()))
		writeError(w, http.StatusPaymentRequired, "payment not confirmed")
		return
	}

	// Scheduled vs ASAP. Orders scheduled for more than 30 minutes in the
	// future start in 'scheduled' status; the background dispatcher flips
	// them to 'pending' 30 minutes before the delivery window.
	initialStatus := models.OrderPending
	if req.ScheduledFor != nil && req.ScheduledFor.After(time.Now().Add(30*time.Minute)) {
		initialStatus = models.OrderScheduled
	}

	// applied_deal_id is nullable in the DB — pass NULL when no deal was used
	// so we don't violate the FK to deals(id).
	var dealIDArg interface{}
	if req.AppliedDealID != "" {
		dealIDArg = req.AppliedDealID
	}

	var order models.Order
	err = tx.QueryRow(r.Context(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id, courier_tip, scheduled_for, fulfillment_type,
		 applied_deal_id, discount_amount, discount_cents)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $17)
		 ON CONFLICT (stripe_payment_id) WHERE stripe_payment_id != '' DO NOTHING
		 RETURNING id, user_id, restaurant_id, status, subtotal, discount_cents, delivery_fee, service_fee, tax, total,
		 delivery_address, delivery_lat, delivery_lng, stripe_payment_id, courier_tip, est_delivery_time,
		 fulfillment_type, created_at, updated_at`,
		user["user_id"], cart.RestaurantID, initialStatus,
		subtotal, deliveryFee, serviceFee, tax, total,
		req.DeliveryAddress, req.DeliveryLat, req.DeliveryLng, req.PaymentIntentID, tip, req.ScheduledFor,
		fulfillmentType,
		dealIDArg, discount,
	).Scan(&order.ID, &order.UserID, &order.RestaurantID, &order.Status,
		&order.Subtotal, &order.Discount, &order.DeliveryFee, &order.ServiceFee, &order.Tax, &order.Total,
		&order.DeliveryAddress, &order.DeliveryLat, &order.DeliveryLng,
		&order.StripePaymentID, &order.CourierTip, &order.EstDeliveryTime,
		&order.FulfillmentType, &order.CreatedAt, &order.UpdatedAt)
	if err == pgx.ErrNoRows {
		// Duplicate payment_intent_id — the 034 unique index already has a row
		// for this PaymentIntent (DO NOTHING returned no row). Treat this as an
		// idempotent replay: roll back our in-flight transaction and return the
		// existing order so a retrying client converges on the same result.
		// User-scoped lookup: the unique index is global, so without the
		// user_id filter this would be an IDOR.
		tx.Rollback(r.Context()) //nolint:errcheck
		existing, loadErr := h.loadOrderByPaymentIntent(r, req.PaymentIntentID, user["user_id"])
		if loadErr != nil {
			writeError(w, http.StatusConflict, "order already created for this payment")
			return
		}
		writeJSON(w, http.StatusOK, existing)
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create order")
		return
	}

	// Insert order items, carrying forward the modifier snapshot JSONB from
	// the cart row so historical orders show exactly what was ordered.
	for i, item := range items {
		modJSON := modifierJSONs[i]
		if len(modJSON) == 0 {
			modJSON = []byte("[]")
		}
		if _, err := tx.Exec(r.Context(),
			`INSERT INTO order_items (order_id, menu_item_id, name, price, quantity, notes, selected_modifiers)
			 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			order.ID, item.MenuItemID, item.Name, item.Price, item.Quantity, item.Notes, modJSON); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to insert order items")
			return
		}
	}

	if _, err := tx.Exec(r.Context(), `DELETE FROM cart_items WHERE cart_id = $1`, cart.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to clear cart")
		return
	}
	if _, err := tx.Exec(r.Context(), `DELETE FROM carts WHERE id = $1`, cart.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to clear cart")
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit order")
		return
	}

	// Populate restaurant name for the response — outside the transaction so
	// the DB lock is released before this non-critical read.
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT name FROM restaurants WHERE id = $1`, cart.RestaurantID,
	).Scan(&order.RestaurantName); err != nil {
		slog.Warn("failed to enrich order response with restaurant name",
			slog.String("order_id", order.ID), slog.String("error", err.Error()))
	}

	order.Items = items

	// Notify the seller that a new order just came in.
	go h.notify.OrderCreated(context.Background(), order.RestaurantID, order.RestaurantName, order.ID, order.Total)

	writeJSON(w, http.StatusCreated, order)
}

func (h *Handler) ListOrders(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	limit := 50
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l <= 100 {
		limit = l
	}
	cursor := r.URL.Query().Get("cursor") // RFC3339 timestamp of last item's created_at

	// JOIN restaurants so the orders list shows which restaurant each order
	// was from without a second round trip per row.
	var rows pgx.Rows
	if cursor != "" {
		cursorTime, parseErr := time.Parse(time.RFC3339Nano, cursor)
		if parseErr != nil {
			writeError(w, http.StatusBadRequest, "invalid cursor format")
			return
		}
		rows, err = h.db.Pool.Query(r.Context(),
			`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
			        o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			        o.courier_tip, o.delivery_address, o.delivery_lat, o.delivery_lng,
			        o.est_delivery_time, o.fulfillment_type, o.stripe_payment_id, o.created_at, o.updated_at
			   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
			  WHERE o.user_id = $1 AND o.created_at < $2
			  ORDER BY o.created_at DESC LIMIT $3`,
			user["user_id"], cursorTime, limit)
	} else {
		rows, err = h.db.Pool.Query(r.Context(),
			`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
			        o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			        o.courier_tip, o.delivery_address, o.delivery_lat, o.delivery_lng,
			        o.est_delivery_time, o.fulfillment_type, o.stripe_payment_id, o.created_at, o.updated_at
			   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
			  WHERE o.user_id = $1
			  ORDER BY o.created_at DESC LIMIT $2`,
			user["user_id"], limit)
	}
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
			&o.Subtotal, &o.Discount, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.CourierTip, &o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
			&o.EstDeliveryTime, &o.FulfillmentType, &o.StripePaymentID, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
		orderIDs = append(orderIDs, o.ID)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}

	// Batch-load items so the list row can render the item summary without
	// N+1 round trips. Always assign a non-nil slice — a nil slice serializes
	// as JSON null, which fails to decode into Swift's non-optional [OrderItem].
	itemsByOrder := map[string][]models.OrderItem{}
	if len(orderIDs) > 0 {
		var batchErr error
		itemsByOrder, batchErr = h.loadOrderItemsBatch(r, orderIDs)
		if batchErr != nil {
			writeError(w, http.StatusInternalServerError, "failed to fetch orders")
			return
		}
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	id := chi.URLParam(r, "id")

	order, err := h.loadOrderWithCourier(r, id, "user_id", user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load order items")
		return
	}

	writeJSON(w, http.StatusOK, order)
}

// GetOrderByPaymentIntent looks up an order by the Stripe PaymentIntent the
// client just confirmed, scoped to the calling user. Used as an idempotent
// recovery path when the client confirmed payment but lost the CreateOrder
// response (network drop, app kill). Returns the full order with items, or
// 404 if no such order exists for this user.
func (h *Handler) GetOrderByPaymentIntent(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	pi := chi.URLParam(r, "pi")

	order, err := h.loadOrderByPaymentIntent(r, pi, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}

	writeJSON(w, http.StatusOK, order)
}

// loadOrderByPaymentIntent resolves an order id from a PaymentIntent id
// (user-scoped — the 034 unique index on stripe_payment_id is global, so the
// user_id filter prevents an IDOR), then loads the full order with courier
// info and line items via the same helpers GetOrder uses. Returns an error
// when no order matches for this user.
func (h *Handler) loadOrderByPaymentIntent(r *http.Request, paymentIntentID, userID string) (*models.Order, error) {
	if paymentIntentID == "" {
		return nil, pgx.ErrNoRows
	}
	var orderID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM orders WHERE stripe_payment_id = $1 AND user_id = $2`,
		paymentIntentID, userID,
	).Scan(&orderID); err != nil {
		return nil, err
	}

	order, err := h.loadOrderWithCourier(r, orderID, "user_id", userID)
	if err != nil {
		return nil, err
	}
	order.Items, err = h.loadOrderItems(r, orderID)
	if err != nil {
		return nil, err
	}
	return order, nil
}

// loadOrderItems fetches the line items for an order, including the
// selected_modifiers JSONB snapshot. Used by both consumer GetOrder and
// seller GetSellerOrder.
func (h *Handler) loadOrderItems(r *http.Request, orderID string) ([]models.OrderItem, error) {
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, order_id, menu_item_id, name, price, quantity, notes, selected_modifiers
		   FROM order_items WHERE order_id = $1 ORDER BY id`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var it models.OrderItem
		var modJSON []byte
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name,
			&it.Price, &it.Quantity, &it.Notes, &modJSON); err != nil {
			slog.Error("loadOrderItems: scan error", slog.String("order_id", orderID), slog.String("error", err.Error()))
			continue
		}
		if len(modJSON) > 0 {
			if err := json.Unmarshal(modJSON, &it.SelectedModifiers); err != nil {
				slog.Error("loadOrderItems: modifier unmarshal error",
					slog.String("item_id", it.ID), slog.String("error", err.Error()))
			}
		}
		items = append(items, it)
	}
	return items, rows.Err()
}

// loadOrderWithCourier fetches a single order joined with the assigned courier's
// public info (if any). ownerColumn is either "user_id" (consumer lookup) or
// "restaurant_owner" — restaurant ownership is checked via join instead.
func (h *Handler) loadOrderWithCourier(r *http.Request, orderID, scope, scopeValue string) (*models.Order, error) {
	var query string
	if scope == "user_id" {
		query = `
			SELECT o.id, o.user_id, o.restaurant_id, rest.name, rest.lat, rest.lng, o.status,
			       o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			       o.delivery_address, o.delivery_lat, o.delivery_lng,
			       o.stripe_payment_id, o.est_delivery_time,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.fulfillment_type,
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
			       o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			       o.delivery_address, o.delivery_lat, o.delivery_lng,
			       o.stripe_payment_id, o.est_delivery_time,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.fulfillment_type,
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
		&o.Subtotal, &o.Discount, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
		&o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
		&o.StripePaymentID, &o.EstDeliveryTime,
		&courierID, &o.ClaimedAt, &o.PickedUpAt, &o.DeliveredAt,
		&o.CourierPayout, &o.CourierTip,
		&o.FulfillmentType,
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

	// Minimize PII exposure by scope. Sellers don't need the customer's
	// raw phone or the Stripe PI ID; couriers don't need the user_id.
	if scope == "restaurant_owner" {
		o.CustomerPhone = maskPhone(o.CustomerPhone)
		o.StripePaymentID = ""
	}

	return &o, nil
}

func maskPhone(phone string) string {
	if len(phone) < 4 {
		return "****"
	}
	return strings.Repeat("*", len(phone)-4) + phone[len(phone)-4:]
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	id := chi.URLParam(r, "id")

	// Lock the row inside a transaction so concurrent cancel requests can't
	// both read the same payment ID and race to issue duplicate refunds.
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot cancel this order")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	// FOR UPDATE locks the row; the status guard ensures only one caller wins.
	var paymentID string
	err = tx.QueryRow(r.Context(),
		`SELECT COALESCE(stripe_payment_id, '') FROM orders
		 WHERE id = $1 AND user_id = $2 AND status IN ($3, $4)
		 FOR UPDATE`,
		id, user["user_id"], models.OrderPending, models.OrderAccepted,
	).Scan(&paymentID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot cancel this order")
		return
	}

	// Allow cancel while pending OR accepted (before kitchen starts preparing).
	if _, err = tx.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW() WHERE id = $2`,
		models.OrderCancelled, id,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot cancel this order")
		return
	}

	// Refund before commit so both succeed or both fail — if the refund
	// fails, the order stays in its original state and the customer isn't
	// charged without recourse. Mirrors the pattern in RejectOrder.
	if paymentID != "" {
		if err := h.stripe.RefundPaymentIntent(paymentID); err != nil {
			slog.Error("cancel refund failed",
				slog.String("order_id", id),
				slog.String("error", err.Error()))
			writeError(w, http.StatusInternalServerError, "refund failed — order not cancelled")
			return
		}
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot cancel this order")
		return
	}

	order, err := h.loadOrderWithCourier(r, id, "user_id", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to reload cancelled order")
		return
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to reload cancelled order")
		return
	}

	if order.RestaurantID != "" {
		go h.notify.OrderCancelled(context.Background(), id, order.RestaurantID)
	}

	writeJSON(w, http.StatusOK, order)
}

// RateOrder lets a consumer rate their courier after delivery. One rating
// per order (enforced by unique(order_id)). On insert the courier's
// aggregate rating is recomputed from the average of all their stars so the
// displayed rating stays fresh. Only the consumer who placed the order can
// rate it, and only once the order is in `delivered` state.
func (h *Handler) RateOrder(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	orderID := chi.URLParam(r, "id")

	var req struct {
		Stars   int    `json:"stars"`
		Comment string `json:"comment"`
	}
	if err := readJSON(r, &req); err != nil {
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
	err = h.db.Pool.QueryRow(r.Context(),
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
		 ), 5.0), updated_at = NOW() WHERE user_id = $1`,
		*courierID,
	); err != nil {
		// Non-fatal — the rating was saved even if the aggregate update
		// failed; the next submission will correct it.
		slog.Warn("rating recompute failed", slog.String("courier_id", *courierID), slog.String("error", err.Error()))
	}

	writeJSON(w, http.StatusOK, map[string]any{"stars": req.Stars})
}

// Seller order handlers

func (h *Handler) ListSellerOrders(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}

	sellerLimit := 50
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l <= 100 {
		sellerLimit = l
	}
	sellerCursor := r.URL.Query().Get("cursor")

	// Scoped to the resolved restaurant so multi-restaurant sellers see
	// only the orders for the one they've currently selected in the app.
	var rows pgx.Rows
	if sellerCursor != "" {
		cursorTime, parseErr := time.Parse(time.RFC3339Nano, sellerCursor)
		if parseErr != nil {
			writeError(w, http.StatusBadRequest, "invalid cursor format")
			return
		}
		rows, err = h.db.Pool.Query(r.Context(),
			`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
			        o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			        o.courier_tip, o.delivery_address, o.est_delivery_time,
			        o.fulfillment_type, o.created_at, o.updated_at
			   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
			  WHERE o.restaurant_id = $1 AND o.created_at < $2
			  ORDER BY o.created_at DESC LIMIT $3`,
			restID, cursorTime, sellerLimit)
	} else {
		rows, err = h.db.Pool.Query(r.Context(),
			`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
			        o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			        o.courier_tip, o.delivery_address, o.est_delivery_time,
			        o.fulfillment_type, o.created_at, o.updated_at
			   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
			  WHERE o.restaurant_id = $1
			  ORDER BY o.created_at DESC LIMIT $2`,
			restID, sellerLimit)
	}
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
			&o.Subtotal, &o.Discount, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
			&o.CourierTip, &o.DeliveryAddress, &o.EstDeliveryTime,
			&o.FulfillmentType, &o.CreatedAt, &o.UpdatedAt); err != nil {
			continue
		}
		orders = append(orders, o)
		orderIDs = append(orderIDs, o.ID)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch orders")
		return
	}

	// Batch-load items for every listed order so the dashboard can render
	// item summaries without N+1 queries.
	itemsByOrder := map[string][]models.OrderItem{}
	if len(orderIDs) > 0 {
		var batchErr error
		itemsByOrder, batchErr = h.loadOrderItemsBatch(r, orderIDs)
		if batchErr != nil {
			writeError(w, http.StatusInternalServerError, "failed to fetch orders")
			return
		}
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
func (h *Handler) loadOrderItemsBatch(r *http.Request, orderIDs []string) (map[string][]models.OrderItem, error) {
	out := map[string][]models.OrderItem{}
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, order_id, menu_item_id, name, price, quantity, notes, selected_modifiers
		   FROM order_items WHERE order_id = ANY($1) ORDER BY order_id, id`, orderIDs)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var it models.OrderItem
		var modJSON []byte
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name,
			&it.Price, &it.Quantity, &it.Notes, &modJSON); err != nil {
			slog.Error("loadOrderItemsBatch: scan error", slog.String("error", err.Error()))
			continue
		}
		if len(modJSON) > 0 {
			if err := json.Unmarshal(modJSON, &it.SelectedModifiers); err != nil {
				slog.Error("loadOrderItemsBatch: modifier unmarshal error",
					slog.String("item_id", it.ID), slog.String("error", err.Error()))
			}
		}
		out[it.OrderID] = append(out[it.OrderID], it)
	}
	return out, rows.Err()
}

func (h *Handler) GetSellerOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "order not found")
		return
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load order items")
		return
	}

	writeJSON(w, http.StatusOK, order)
}

func (h *Handler) AcceptOrder(w http.ResponseWriter, r *http.Request) {
	if !h.updateSellerOrderStatus(w, r, models.OrderAccepted, models.OrderPending) {
		return
	}
	orderID := chi.URLParam(r, "id")
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(r, orderID)
	if consumerID != "" && restaurantName != "" {
		go h.notify.OrderAccepted(context.Background(), orderID, consumerID, restaurantName)
	}

	// Fire-and-forget POS push (Clover, etc). Loads the connected
	// integration for this restaurant and pushes the order so the kitchen
	// printer fires. Errors are logged inside PushOrderToPOS — they never
	// surface to the seller, since the order itself was already accepted.
	go h.pushAcceptedOrderToPOS(orderID)
}

func (h *Handler) pushAcceptedOrderToPOS(orderID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	var restaurantID string
	if err := h.db.Pool.QueryRow(ctx,
		`SELECT restaurant_id FROM orders WHERE id = $1`, orderID,
	).Scan(&restaurantID); err != nil {
		return
	}
	order, err := h.loadOrderByID(ctx, orderID)
	if err != nil {
		return
	}
	h.PushOrderToPOS(ctx, restaurantID, order)
}

// loadOrderByID is a thin wrapper around the existing order-load helpers
// for the POS hook. Returns a complete Order with items so adapters can
// build their payloads.
func (h *Handler) loadOrderByID(ctx context.Context, orderID string) (*models.Order, error) {
	var o models.Order
	if err := h.db.Pool.QueryRow(ctx,
		`SELECT o.id, o.user_id, o.restaurant_id, rest.name,
		        o.status, o.subtotal, o.delivery_fee, o.service_fee,
		        o.tax, o.total, o.delivery_address, o.delivery_lat, o.delivery_lng,
		        COALESCE(u.first_name || ' ' || u.last_name, ''), COALESCE(u.phone, '')
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		   LEFT JOIN users u ON o.user_id = u.id
		  WHERE o.id = $1`, orderID,
	).Scan(
		&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName,
		&o.Status, &o.Subtotal, &o.DeliveryFee, &o.ServiceFee,
		&o.Tax, &o.Total, &o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
		&o.CustomerName, &o.CustomerPhone,
	); err != nil {
		return nil, err
	}
	rows, err := h.db.Pool.Query(ctx,
		`SELECT id, order_id, menu_item_id, name, price, quantity, COALESCE(notes,'')
		   FROM order_items WHERE order_id = $1 ORDER BY id`, orderID)
	if err != nil {
		return &o, nil
	}
	defer rows.Close()
	for rows.Next() {
		var it models.OrderItem
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name, &it.Price, &it.Quantity, &it.Notes); err != nil {
			continue
		}
		o.Items = append(o.Items, it)
	}
	return &o, nil
}

func (h *Handler) MarkOrderPreparing(w http.ResponseWriter, r *http.Request) {
	if !h.updateSellerOrderStatus(w, r, models.OrderPreparing, models.OrderAccepted) {
		return
	}
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(r, chi.URLParam(r, "id"))
	if consumerID != "" && restaurantName != "" {
		go h.notify.OrderPreparing(context.Background(), chi.URLParam(r, "id"), consumerID, restaurantName)
	}
}

// consumerAndRestaurantForOrder is a small helper used by the seller-side
// status-transition handlers to fan a push to the consumer. Returns empty
// strings on failure — caller skips the push rather than surfacing a 500
// because the underlying status flip already succeeded.
func (h *Handler) consumerAndRestaurantForOrder(r *http.Request, orderID string) (string, string) {
	var consumerID, restaurantName string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT o.user_id, rest.name
		   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1`, orderID,
	).Scan(&consumerID, &restaurantName); err != nil {
		slog.Warn("consumerAndRestaurantForOrder lookup failed",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		return "", ""
	}
	return consumerID, restaurantName
}

func (h *Handler) CompleteOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// For pickup orders, allow completing from 'preparing' as well as 'ready'
	// since there is no courier handoff step.
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 FROM restaurants WHERE orders.restaurant_id = restaurants.id
		 AND orders.id = $2 AND restaurants.owner_id = $3
		 AND (orders.status = $4 OR (orders.status = $5 AND orders.fulfillment_type = 'pickup'))`,
		models.OrderCompleted, id, user["user_id"], models.OrderReady, models.OrderPreparing)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}

	if order.UserID != "" {
		if order.FulfillmentType == "pickup" {
			go h.notify.OrderCompleted(context.Background(), id, order.UserID)
		} else {
			go h.notify.OrderDelivered(context.Background(), id, order.UserID)
		}
	}

	writeJSON(w, http.StatusOK, order)
}

func (h *Handler) MarkOrderReady(w http.ResponseWriter, r *http.Request) {
	if !h.updateSellerOrderStatus(w, r, models.OrderReady, models.OrderPreparing) {
		return
	}

	// After marking ready, broadcast a "new delivery available" push to
	// every currently-online courier. We re-query for the order's restaurant
	// name + delivery fee so the notification has context.
	orderID := chi.URLParam(r, "id")
	var restaurantName string
	var deliveryFee int
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT rest.name, o.delivery_fee
		   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1`, orderID,
	).Scan(&restaurantName, &deliveryFee); err != nil {
		slog.Warn("failed to fetch order data for ready notification",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
	}
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// Read the body once up front — r.Body is a one-shot io.ReadCloser, so
	// any later readJSON call would see EOF.
	var rejectBody struct {
		Reason string `json:"reason,omitempty"`
	}
	_ = readJSON(r, &rejectBody) // body is optional

	// Lock the row inside a transaction so concurrent AcceptOrder can't flip
	// status between our SELECT and UPDATE (same pattern as CancelOrder).
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot reject this order")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	var paymentIntentID string
	err = tx.QueryRow(r.Context(),
		`SELECT orders.stripe_payment_id
		   FROM orders JOIN restaurants ON orders.restaurant_id = restaurants.id
		  WHERE orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = $3
		  FOR UPDATE OF orders`,
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

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 WHERE id = $2 AND status = $3`,
		models.OrderRejected, id, models.OrderPending)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot reject this order")
		return
	}

	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return
	}

	// Notify the consumer that the seller manually rejected (and the refund
	// was already issued above).
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(r, id)
	if consumerID != "" && restaurantName != "" {
		go h.notify.OrderRejected(context.Background(), id, consumerID, restaurantName, rejectBody.Reason)
	}

	writeJSON(w, http.StatusOK, order)
}

// SellerPickupOrder marks an order as picked up by the restaurant's own
// courier. Only allowed when the restaurant's delivery_mode is 'restaurant'.
func (h *Handler) SellerPickupOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// Lock the row inside a transaction so concurrent pickup requests can't
	// race between the SELECT check and the UPDATE (same pattern as CancelOrder).
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot update order status")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	var deliveryMode string
	err = tx.QueryRow(r.Context(),
		`SELECT rest.delivery_mode FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1 AND rest.owner_id = $2 AND o.status = 'ready'
		  FOR UPDATE OF o`,
		id, user["user_id"]).Scan(&deliveryMode)
	if err != nil {
		writeError(w, http.StatusBadRequest, "order not found or not ready")
		return
	}
	if deliveryMode != "restaurant" {
		writeError(w, http.StatusBadRequest, "restaurant delivery mode is not set to restaurant")
		return
	}

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = 'picked_up', picked_up_at = NOW(), updated_at = NOW()
		   FROM restaurants WHERE orders.restaurant_id = restaurants.id
		   AND orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = 'ready'`,
		id, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot update order status")
		return
	}

	var consumerID string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, id).Scan(&consumerID)
	if h.notify != nil && consumerID != "" {
		h.notify.OrderPickedUp(r.Context(), id, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "picked_up"})
}

// SellerDeliverOrder marks an order as delivered by the restaurant's own
// courier. Only allowed when delivery_mode is 'restaurant'.
func (h *Handler) SellerDeliverOrder(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// Lock the row inside a transaction so concurrent deliver requests can't
	// race between the SELECT check and the UPDATE (same pattern as CancelOrder).
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot update order status")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	var deliveryMode string
	err = tx.QueryRow(r.Context(),
		`SELECT rest.delivery_mode FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1 AND rest.owner_id = $2 AND o.status = 'picked_up'
		  FOR UPDATE OF o`,
		id, user["user_id"]).Scan(&deliveryMode)
	if err != nil {
		writeError(w, http.StatusBadRequest, "order not found or not picked up")
		return
	}
	if deliveryMode != "restaurant" {
		writeError(w, http.StatusBadRequest, "restaurant delivery mode is not set to restaurant")
		return
	}

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = 'delivered', delivered_at = NOW(), updated_at = NOW()
		   FROM restaurants WHERE orders.restaurant_id = restaurants.id
		   AND orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = 'picked_up'`,
		id, user["user_id"])
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot update order status")
		return
	}

	var consumerID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT user_id FROM orders WHERE id = $1`, id).Scan(&consumerID); err != nil {
		slog.Warn("failed to fetch consumer for delivery notification",
			slog.String("order_id", id), slog.String("error", err.Error()))
	}
	if consumerID != "" {
		go h.notify.OrderDelivered(context.Background(), id, consumerID)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "delivered"})
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
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

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

	const sseHeartbeatInterval = 25 * time.Second
	heartbeat := time.NewTicker(sseHeartbeatInterval)
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

func (h *Handler) updateSellerOrderStatus(w http.ResponseWriter, r *http.Request, newStatus, requiredStatus models.OrderStatus) bool {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 FROM restaurants WHERE orders.restaurant_id = restaurants.id
		 AND orders.id = $2 AND restaurants.owner_id = $3 AND orders.status = $4`,
		newStatus, id, user["user_id"], requiredStatus)

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return false
	}

	// Return the full refreshed order (courier info + items) so the seller
	// client can update its local state without a follow-up GET.
	order, err := h.loadOrderWithCourier(r, id, "restaurant_owner", user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return true // transition succeeded, just reload failed
	}
	order.Items, err = h.loadOrderItems(r, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "status updated but failed to reload order")
		return true
	}
	writeJSON(w, http.StatusOK, order)
	return true
}
