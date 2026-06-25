package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/koshereats/backend/internal/dispatch"
	"github.com/koshereats/backend/internal/models"
)

type CreateOrderRequest struct {
	RestaurantID    string     `json:"restaurant_id"`
	DeliveryAddress string     `json:"delivery_address"`
	DeliveryLat     float64    `json:"delivery_lat"`
	DeliveryLng     float64    `json:"delivery_lng"`
	PaymentIntentID string     `json:"payment_intent_id"`
	Tip             int        `json:"tip"`           // cents
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

	// Idempotent replay short-circuit: if an order already exists for this
	// PaymentIntent (scoped to this user — the unique index is global, so the
	// user filter prevents an IDOR), return it instead of reprocessing. Client
	// retries after a network blip on the original response are common, and the
	// first call already cleared the cart — so without this, a retry surfaces a
	// 4xx for what was actually a success: "cart is empty", or for a deal order
	// the "deal already used" check fires off the just-created order. A genuine
	// new order always carries a fresh PaymentIntent, so it won't match here.
	// The INSERT's ON CONFLICT below stays as the race-safe backstop for retries
	// that arrive before this one has committed.
	if existing, lerr := h.loadOrderByPaymentIntent(r, req.PaymentIntentID, user["user_id"]); lerr == nil {
		writeJSON(w, http.StatusOK, existing)
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
	// A mid-iteration error makes Next() return false just like a clean end of
	// rows. Without this check a partial cart would build an order missing items
	// (wrong subtotal, customer shorted) — fail the whole create instead.
	if err := itemRows.Err(); err != nil {
		slog.Error("cart item iteration failed during order creation", "cart_id", cart.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "failed to read cart items")
		return
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

	// SECURITY: the order's fulfillment_type must match the one the PaymentIntent
	// was priced for. Otherwise a client mints a pickup PI (delivery_fee = 0, tip
	// forced 0) and redeems it on a delivery order — and because CreateOrder
	// reuses the stamped delivery_fee (StampedDeliveryFee), the delivery ships for
	// free. ok=false means a legacy PI with no stamp — skip the check for compat.
	if stamped, ok, ferr := h.stripe.StampedFulfillmentType(req.PaymentIntentID); ferr == nil && ok && stamped != fulfillmentType {
		writeError(w, http.StatusBadRequest, "payment was created for a different fulfillment type")
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
			quote := h.quoteDeliveryFee(r.Context(), restAddress, req.DeliveryAddress, subtotal)
			deliveryFee = quote.consumerFee
		} else {
			deliveryFee = deliveryFeeFallbackCents
		}
	}
	// Reuse the delivery fee the PaymentIntent was actually charged against,
	// rather than the fresh quote computed just above. quoteDeliveryFee hits a
	// live courier API whose price drifts second-to-second, so re-quoting here
	// would routinely disagree with what CreatePaymentIntent charged by a few
	// cents (or, when the PI fell back to the flat rate, by dollars) and fail
	// the amount-match guard below — charging the customer but rejecting the
	// order. The stamp is authoritative and tamper-proof (set server-side at PI
	// creation). Falls back to the quote above for stub mode / pre-stamp PIs.
	if h.stripe != nil {
		if fee, ok, err := h.stripe.StampedDeliveryFee(req.PaymentIntentID); err != nil {
			slog.Warn("CreateOrder: could not read stamped delivery fee, using live quote",
				slog.String("payment_intent_id", req.PaymentIntentID),
				slog.String("error", err.Error()))
		} else if ok {
			deliveryFee = fee
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
	// Mirror CreatePaymentIntent's tax seam exactly so the PI total and the order
	// total can never diverge once Stripe Tax (taxForOrder) is wired — divergence
	// here would trip the amount-match guard and produce charged-but-no-order.
	var tax int
	if h.cfg.StripeTaxEnabled {
		tax = h.taxForOrder(discountedSubtotal)
	} else {
		tax = discountedSubtotal * h.cfg.TaxRatePercent / 100
	}
	tip := req.Tip
	if tip < 0 {
		tip = 0
	}
	if fulfillmentType == "pickup" {
		// No courier means no tip lane; ignore any client-supplied value.
		tip = 0
	}
	if tip > subtotal {
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
		// A unique-violation on the once-per-user deal index (043,
		// uq_orders_user_deal_active) means this user already redeemed this deal
		// (a retry, or a race past the app-level EXISTS check). The PaymentIntent
		// is already captured, so the previous behavior (500, no order) left the
		// customer charged with nothing. Refund and return a clear 409 instead.
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" &&
			strings.Contains(pgErr.ConstraintName, "user_deal") {
			tx.Rollback(r.Context()) //nolint:errcheck
			if rerr := h.stripe.RefundPaymentIntent(req.PaymentIntentID); rerr != nil {
				slog.Error("CreateOrder: deal-conflict refund FAILED — customer charged with no order, manual reconcile",
					slog.String("payment_intent_id", req.PaymentIntentID),
					slog.String("user_id", user["user_id"]),
					slog.String("error", rerr.Error()))
			}
			writeError(w, http.StatusConflict, "this deal has already been used — your payment was refunded")
			return
		}
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

	// Notify the seller that a new order just came in — but only for orders that
	// are actually pending. A far-future scheduled order isn't acceptable yet, so
	// firing the "New order — tap to accept" push now would be noise the seller
	// can't act on; the dispatcher re-fires OrderCreated when it promotes the
	// scheduled order to pending.
	if order.Status == models.OrderPending {
		go h.notify.OrderCreated(context.Background(), order.RestaurantID, order.RestaurantName, order.ID, order.Total)
	}

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
			        o.est_delivery_time, o.scheduled_for, o.fulfillment_type, o.stripe_payment_id, o.created_at, o.updated_at
			   FROM orders o JOIN restaurants rest ON o.restaurant_id = rest.id
			  WHERE o.user_id = $1 AND o.created_at < $2
			  ORDER BY o.created_at DESC LIMIT $3`,
			user["user_id"], cursorTime, limit)
	} else {
		rows, err = h.db.Pool.Query(r.Context(),
			`SELECT o.id, o.user_id, o.restaurant_id, rest.name, o.status,
			        o.subtotal, o.discount_cents, o.delivery_fee, o.service_fee, o.tax, o.total,
			        o.courier_tip, o.delivery_address, o.delivery_lat, o.delivery_lng,
			        o.est_delivery_time, o.scheduled_for, o.fulfillment_type, o.stripe_payment_id, o.created_at, o.updated_at
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
			&o.EstDeliveryTime, &o.ScheduledFor, &o.FulfillmentType, &o.StripePaymentID, &o.CreatedAt, &o.UpdatedAt); err != nil {
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
			       o.stripe_payment_id, o.est_delivery_time, o.scheduled_for,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.fulfillment_type, o.external_delivery_id, o.external_provider, o.external_tracking_url, COALESCE(rest.delivery_mode, 'platform'),
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
			       o.stripe_payment_id, o.est_delivery_time, o.scheduled_for,
			       o.courier_id, o.claimed_at, o.picked_up_at, o.delivered_at,
			       o.courier_payout, o.courier_tip,
			       o.fulfillment_type, o.external_delivery_id, o.external_provider, o.external_tracking_url, COALESCE(rest.delivery_mode, 'platform'),
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
		courierID                               *string
		cFirst, cPhone, cAvatar                 *string
		cVehType, cMake, cModel, cColor, cPlate *string
		cRating                                 *float64
		cTotal                                  *int
		cLat, cLng                              *float64
		ratingStars                             *int
		consumerFirst, consumerPhone            *string
	)

	err := h.db.Pool.QueryRow(r.Context(), query, orderID, scopeValue).Scan(
		&o.ID, &o.UserID, &o.RestaurantID, &o.RestaurantName, &o.RestaurantLat, &o.RestaurantLng, &o.Status,
		&o.Subtotal, &o.Discount, &o.DeliveryFee, &o.ServiceFee, &o.Tax, &o.Total,
		&o.DeliveryAddress, &o.DeliveryLat, &o.DeliveryLng,
		&o.StripePaymentID, &o.EstDeliveryTime, &o.ScheduledFor,
		&courierID, &o.ClaimedAt, &o.PickedUpAt, &o.DeliveredAt,
		&o.CourierPayout, &o.CourierTip,
		&o.FulfillmentType, &o.ExternalDeliveryID, &o.ExternalProvider, &o.ExternalTrackingURL, &o.DeliveryMode,
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
	// external_delivery_id IS NULL: an order escalated to Uber/DoorDash sits at
	// 'accepted' while a PAID provider delivery is already in flight — cancelling
	// + refunding it here would leave the platform paying for a delivery on a
	// refunded order, with no provider cancel. Block the customer cancel once a
	// provider owns it (it's out for delivery).
	var paymentID string
	err = tx.QueryRow(r.Context(),
		`SELECT COALESCE(stripe_payment_id, '') FROM orders
		 WHERE id = $1 AND user_id = $2 AND status IN ($3, $4)
		   AND external_delivery_id IS NULL
		 FOR UPDATE`,
		id, user["user_id"], models.OrderPending, models.OrderAccepted,
	).Scan(&paymentID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot cancel this order")
		return
	}

	// Allow cancel while pending OR accepted (before kitchen starts preparing).
	// Mark refunded_at now when there's nothing to refund, so a no-payment order
	// never lingers as refund-pending for the reconcile reaper.
	if _, err = tx.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW(),
		   refunded_at = CASE WHEN COALESCE(stripe_payment_id, '') = '' THEN NOW() ELSE refunded_at END
		 WHERE id = $2`,
		models.OrderCancelled, id,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot cancel this order")
		return
	}

	// Commit the cancel FIRST, then refund. Doing the refund before commit was a
	// money bug: a real Stripe refund is not part of the Postgres tx, so if the
	// commit failed after a successful refund, the deferred rollback reverted the
	// status flip and left a REFUNDED order still fulfillable (free food). Now the
	// order is definitively cancelled before any money moves; a refund that fails
	// post-commit (rare) leaves the customer charged-but-cancelled, which the
	// reconcile reaper (sweepPendingRefunds) retries idempotently — strictly
	// better than the unrecoverable free-food failure.
	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot cancel this order")
		return
	}

	if paymentID != "" {
		if err := h.stripe.RefundPaymentIntent(paymentID); err != nil {
			// The cancel succeeded; only the refund is pending. Don't fail the
			// request — log loudly and let the reaper settle it.
			slog.Error("cancel: refund failed post-commit — reaper will retry",
				slog.String("order_id", id),
				slog.String("error", err.Error()))
		} else {
			if _, err := h.db.Pool.Exec(r.Context(),
				`UPDATE orders SET refunded_at = NOW() WHERE id = $1`, id); err != nil {
				slog.Error("cancel: refund succeeded but marking refunded_at failed — reaper will reconcile",
					slog.String("order_id", id), slog.String("error", err.Error()))
			}
		}
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
	// Detached context: updateSellerOrderStatus already flushed the response, so
	// r.Context() is effectively done — a client disconnect must not cancel the
	// consumer-push lookup.
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(context.Background(), orderID)
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
	// Items are load-critical for the POS kitchen ticket: an item-less or
	// truncated ticket must surface as an error rather than be silently pushed.
	rows, err := h.db.Pool.Query(ctx,
		`SELECT id, order_id, menu_item_id, name, price, quantity, COALESCE(notes,'')
		   FROM order_items WHERE order_id = $1 ORDER BY id`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var it models.OrderItem
		if err := rows.Scan(&it.ID, &it.OrderID, &it.MenuItemID, &it.Name, &it.Price, &it.Quantity, &it.Notes); err != nil {
			return nil, err
		}
		o.Items = append(o.Items, it)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return &o, nil
}

func (h *Handler) MarkOrderPreparing(w http.ResponseWriter, r *http.Request) {
	if !h.updateSellerOrderStatus(w, r, models.OrderPreparing, models.OrderAccepted) {
		return
	}
	// Detached context: the status flip already flushed the response, so a client
	// disconnect must not cancel the consumer-push lookup.
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(context.Background(), chi.URLParam(r, "id"))
	if consumerID != "" && restaurantName != "" {
		go h.notify.OrderPreparing(context.Background(), chi.URLParam(r, "id"), consumerID, restaurantName)
	}
}

// consumerAndRestaurantForOrder is a small helper used by the seller-side
// status-transition handlers to fan a push to the consumer. Returns empty
// strings on failure — caller skips the push rather than surfacing a 500
// because the underlying status flip already succeeded.
func (h *Handler) consumerAndRestaurantForOrder(ctx context.Context, orderID string) (string, string) {
	var consumerID, restaurantName string
	if err := h.db.Pool.QueryRow(ctx,
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

	// CompleteOrder is the PICKUP terminal step (customer collects at the
	// counter) — allowed from 'preparing' or 'ready' since there's no courier
	// handoff. Delivery orders must NOT be completable here: a courier drives
	// ready->picked_up->delivered (and earns payout on delivery), and a
	// self-delivery seller uses sellerPickup/sellerDeliver. Without the pickup
	// guard a seller could force a ready delivery order straight to completed,
	// stranding the assigned courier and skipping their payout.
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW()
		 FROM restaurants WHERE orders.restaurant_id = restaurants.id
		 AND orders.id = $2 AND restaurants.owner_id = $3
		 AND orders.fulfillment_type = 'pickup'
		 AND (orders.status = $4 OR orders.status = $5)`,
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

	// After marking ready we route the order. Re-query everything needed for
	// either the own-fleet courier broadcast OR an inline external dispatch.
	// updateSellerOrderStatus already wrote the HTTP response, so all downstream
	// work is fire-and-forget on context.Background() (r.Context() is now dead).
	orderID := chi.URLParam(r, "id")
	var (
		restaurantName, restAddress, restPhone       string
		deliveryAddress, customerName, customerPhone string
		deliveryMode, fulfillmentType                string
		externalDeliveryID                           *string
		deliveryFee, subtotal, courierTip            int
	)
	// context.Background(): the HTTP response is already sent, so r.Context() is
	// dead. Using it here would let a client disconnect abort the query that
	// DECIDES whether to dispatch, silently skipping the courier entirely.
	if err := h.db.Pool.QueryRow(context.Background(),
		`SELECT rest.name,
		        COALESCE(rest.street || ', ' || rest.city || ', ' || rest.state || ' ' || rest.zip_code, ''),
		        COALESCE(rest.phone, ''), COALESCE(rest.delivery_mode, 'platform'),
		        COALESCE(o.delivery_address, ''),
		        COALESCE(u.first_name || ' ' || u.last_name, ''), COALESCE(u.phone, ''),
		        o.fulfillment_type, o.external_delivery_id,
		        o.delivery_fee, o.subtotal, COALESCE(o.courier_tip, 0)
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		   JOIN users u ON u.id = o.user_id
		  WHERE o.id = $1`, orderID,
	).Scan(&restaurantName, &restAddress, &restPhone, &deliveryMode,
		&deliveryAddress, &customerName, &customerPhone,
		&fulfillmentType, &externalDeliveryID,
		&deliveryFee, &subtotal, &courierTip); err != nil {
		slog.Warn("failed to fetch order data for ready handling",
			slog.String("order_id", orderID), slog.String("error", err.Error()))
		return
	}

	// 'external' delivery → dispatch Uber/DoorDash immediately (no 60s sweep
	// wait), and DON'T broadcast to KE couriers (the order is already going to a
	// provider). hasExternal guard avoids a re-dispatch on a retried /ready.
	if fulfillmentType == "delivery" && deliveryMode == "external" && externalDeliveryID == nil {
		in := dispatch.Input{
			OrderID: orderID, RestaurantName: restaurantName, RestAddress: restAddress,
			RestPhone: restPhone, DeliveryAddress: deliveryAddress, CustomerName: customerName,
			CustomerPhone: customerPhone, Subtotal: subtotal, TipCents: courierTip,
		}
		go func() {
			if _, _, _, err := h.dispatcher.Dispatch(context.Background(), in); err != nil {
				slog.Error("mark-ready: inline external dispatch failed",
					slog.String("order_id", orderID), slog.String("error", err.Error()))
			}
		}()
		return
	}

	// Own-fleet marketplace broadcast only for 'platform' delivery orders.
	// 'restaurant' mode = seller self-delivers (no courier); pickup = no delivery.
	// externalDeliveryID guard: an order already escalated to Uber/DoorDash must
	// not be broadcast to KE couriers (spurious "new delivery available" push).
	if fulfillmentType == "delivery" && deliveryMode == "platform" && externalDeliveryID == nil && restaurantName != "" {
		// Advertise the payout the courier will actually receive (delivery fee +
		// 100% of the tip), matching the DeliverOrder payout and the auto-assign
		// broadcast — not the bare delivery fee, which under-states earnings.
		go h.notify.OrderReady(context.Background(), orderID, restaurantName, deliveryFee+courierTip)
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
		`SELECT COALESCE(orders.stripe_payment_id, '')
		   FROM orders JOIN restaurants ON orders.restaurant_id = restaurants.id
		  WHERE orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = $3
		  FOR UPDATE OF orders`,
		id, user["user_id"], models.OrderPending,
	).Scan(&paymentIntentID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = $1, updated_at = NOW(),
		   refunded_at = CASE WHEN COALESCE(stripe_payment_id, '') = '' THEN NOW() ELSE refunded_at END
		 WHERE id = $2 AND status = $3`,
		models.OrderRejected, id, models.OrderPending)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	// Commit the rejection FIRST, then refund. Refunding before commit risked a
	// refunded-but-still-fulfillable order if the commit failed (see CancelOrder).
	// A post-commit refund failure is retried by the reconcile reaper.
	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot reject this order")
		return
	}

	if paymentIntentID != "" && h.stripe != nil {
		if err := h.stripe.RefundPaymentIntent(paymentIntentID); err != nil {
			slog.Error("reject: refund failed post-commit — reaper will retry",
				slog.String("order_id", id), slog.String("error", err.Error()))
		} else if _, err := h.db.Pool.Exec(r.Context(),
			`UPDATE orders SET refunded_at = NOW() WHERE id = $1`, id); err != nil {
			slog.Error("reject: refund succeeded but marking refunded_at failed — reaper will reconcile",
				slog.String("order_id", id), slog.String("error", err.Error()))
		}
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
	consumerID, restaurantName := h.consumerAndRestaurantForOrder(r.Context(), id)
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
		// Guard external_provider/external_delivery_id too: a self-delivery order
		// can be escalated to Uber concurrently (EscalateToUber takes no row lock),
		// and without this a seller could mark an order picked-up that a provider
		// is already paid-dispatching → double delivery. The matching guard in
		// dispatch.Dispatch's status check closes the other side of the window.
		`SELECT rest.delivery_mode FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1 AND rest.owner_id = $2 AND o.status = 'ready'
		    AND o.external_provider IS NULL AND o.external_delivery_id IS NULL
		  FOR UPDATE OF o`,
		id, user["user_id"]).Scan(&deliveryMode)
	if err != nil {
		writeError(w, http.StatusBadRequest, "order not found, not ready, or already dispatched")
		return
	}
	if deliveryMode != "restaurant" {
		writeError(w, http.StatusBadRequest, "restaurant delivery mode is not set to restaurant")
		return
	}

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = 'picked_up', picked_up_at = NOW(), updated_at = NOW()
		   FROM restaurants WHERE orders.restaurant_id = restaurants.id
		   AND orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = 'ready'
		   AND orders.external_provider IS NULL AND orders.external_delivery_id IS NULL`,
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

// EscalateToUber lets a seller hand an open self-delivery order off to an
// external courier (Uber Direct / DoorDash) when they're overwhelmed. One-way:
// once an order has a platform courier or an external delivery it can't revert
// to self-delivery. Synchronous (unlike the fire-and-forget mark-ready hook) so
// the seller gets a real success/failure + tracking. Concurrency is handled by
// Dispatch's claim-before-create CAS — NOT a FOR UPDATE row lock here, which
// would deadlock against Dispatch's own UPDATEs on the same row.
func (h *Handler) EscalateToUber(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if h.dispatcher == nil || !h.dispatcher.AnyProviderEnabled() {
		writeError(w, http.StatusServiceUnavailable, "no delivery provider configured")
		return
	}

	// Eligibility + dispatch inputs in one ownership-scoped query. The one-way
	// lock (courier_id IS NULL AND external_delivery_id IS NULL) filters here AND
	// is re-asserted atomically inside Dispatch's claim, so no row lock is needed.
	var in dispatch.Input
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT o.id, rest.name,
		        COALESCE(rest.street || ', ' || rest.city || ', ' || rest.state || ' ' || rest.zip_code, ''),
		        COALESCE(rest.phone, ''),
		        COALESCE(o.delivery_address, ''),
		        COALESCE(u.first_name || ' ' || u.last_name, ''), COALESCE(u.phone, ''),
		        o.subtotal, COALESCE(o.courier_tip, 0)
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		   JOIN users u ON u.id = o.user_id
		  WHERE o.id = $1 AND rest.owner_id = $2
		    AND o.fulfillment_type = 'delivery'
		    AND o.status IN ('accepted','preparing','ready')
		    AND o.courier_id IS NULL AND o.external_delivery_id IS NULL`,
		id, user["user_id"]).Scan(&in.OrderID, &in.RestaurantName, &in.RestAddress,
		&in.RestPhone, &in.DeliveryAddress, &in.CustomerName, &in.CustomerPhone,
		&in.Subtotal, &in.TipCents)
	if err != nil {
		writeError(w, http.StatusBadRequest,
			"order not eligible for Uber dispatch (already dispatched, or not an open delivery order)")
		return
	}

	// Dispatch on a detached context, NOT the request context: it makes a paid
	// CreateDelivery call followed by a persist UPDATE, and if the seller's HTTP
	// request is cancelled in that window the persist is skipped — leaving a paid
	// provider delivery un-recorded (the reaper then re-dispatches → double pay).
	// Mirrors the MarkOrderReady inline-dispatch fix.
	dispatchCtx, cancelDispatch := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelDispatch()
	provider, deliveryID, _, derr := h.dispatcher.Dispatch(dispatchCtx, in)
	if derr != nil {
		writeError(w, http.StatusBadGateway, "could not dispatch a courier — please try again")
		return
	}
	if provider == "" {
		// Claim lost — a sweep or a concurrent tap already owns it.
		writeError(w, http.StatusConflict, "order is already being dispatched")
		return
	}

	var trackingURL string
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT COALESCE(external_tracking_url, '') FROM orders WHERE id = $1`, id).Scan(&trackingURL)
	writeJSON(w, http.StatusOK, map[string]string{
		"status": "dispatched", "provider": provider,
		"delivery_id": deliveryID, "tracking_url": trackingURL,
	})
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
	var deliveryFee, courierTip int
	err = tx.QueryRow(r.Context(),
		`SELECT rest.delivery_mode, o.delivery_fee, COALESCE(o.courier_tip, 0) FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1 AND rest.owner_id = $2 AND o.status = 'picked_up'
		  FOR UPDATE OF o`,
		id, user["user_id"]).Scan(&deliveryMode, &deliveryFee, &courierTip)
	if err != nil {
		writeError(w, http.StatusBadRequest, "order not found or not picked up")
		return
	}
	if deliveryMode != "restaurant" {
		writeError(w, http.StatusBadRequest, "restaurant delivery mode is not set to restaurant")
		return
	}

	// Self-delivered earnings = 50% of the customer-paid delivery_fee (KE keeps
	// the remainder, including the odd-cent floor — never compute KE's half
	// independently) PLUS 100% of the courier tip. The seller performed the
	// delivery, so the tip is theirs exactly as it would be a platform courier's
	// ("100% of the tip goes to your courier"); previously the tip was charged to
	// the customer but dropped from the seller's ledger and kept by the platform.
	// Folded into the status CAS below so a replayed deliver can't double-count.
	// The CASE guard keys off who ACTUALLY delivered (courier_id / external_
	// delivery_id), not delivery_mode, so an order escalated to Uber pays 0 here.
	sellerShare := deliveryFee/2 + courierTip

	result, err := tx.Exec(r.Context(),
		`UPDATE orders SET status = 'delivered', delivered_at = NOW(), updated_at = NOW(),
		    seller_delivery_earnings = CASE
		        WHEN orders.courier_id IS NULL AND orders.external_delivery_id IS NULL THEN $3
		        ELSE 0 END
		   FROM restaurants WHERE orders.restaurant_id = restaurants.id
		   AND orders.id = $1 AND restaurants.owner_id = $2 AND orders.status = 'picked_up'`,
		id, user["user_id"], sellerShare)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusBadRequest, "cannot update order status")
		return
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot update order status")
		return
	}

	// Detached context: the response/commit is done and this lookup only drives a
	// best-effort push, so a client disconnect must not cancel it.
	var consumerID string
	if err := h.db.Pool.QueryRow(context.Background(),
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

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	// Flush + write-deadline go through http.ResponseController so it can unwrap
	// the logging middleware's statusRecorder to reach the real connection. A
	// direct w.(http.Flusher) assertion fails on the wrapped writer, which used
	// to 500 every SSE stream with "streaming unsupported". The server has a 15s
	// WriteTimeout; clear the per-write deadline so this long-lived stream isn't
	// killed mid-connection.
	rc := http.NewResponseController(w)
	_ = rc.SetWriteDeadline(time.Time{})
	if err := rc.Flush(); err != nil {
		// Transport genuinely can't stream; the 200 + headers are already sent,
		// so there's nothing to do but stop.
		slog.Error("StreamOrderLocation: streaming unsupported", slog.String("error", err.Error()))
		return
	}

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
			_ = rc.Flush()
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
			_ = rc.Flush()
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
