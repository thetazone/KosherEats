package handlers

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type AddToCartRequest struct {
	MenuItemID   string   `json:"menu_item_id"`
	RestaurantID string   `json:"restaurant_id"`
	Quantity     int      `json:"quantity"`
	Notes        string   `json:"notes"`
	ModifierIDs  []string `json:"modifier_ids"` // selected modifier option ids
}

type UpdateCartItemRequest struct {
	Quantity int `json:"quantity"`
	// Pointer so we can tell "notes omitted" (keep existing) from "notes set to
	// empty" (explicit clear). A quantity-only update must not wipe notes.
	Notes *string `json:"notes"`
}

func (h *Handler) GetCart(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var cart models.Cart
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT id, user_id, restaurant_id FROM carts WHERE user_id = $1`,
		user["user_id"],
	).Scan(&cart.ID, &cart.UserID, &cart.RestaurantID)

	if err != nil {
		writeJSON(w, http.StatusOK, models.Cart{Items: []models.CartItem{}})
		return
	}

	// ci.price is the unit price snapshot that already includes modifier deltas.
	// selected_modifiers is the JSONB snapshot.
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT ci.id, ci.cart_id, ci.menu_item_id, mi.name, ci.unit_price, ci.quantity, ci.notes, ci.selected_modifiers
		 FROM cart_items ci JOIN menu_items mi ON ci.menu_item_id = mi.id
		 WHERE ci.cart_id = $1`, cart.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch cart")
		return
	}
	defer rows.Close()

	var subtotal int
	for rows.Next() {
		var item models.CartItem
		var modJSON []byte
		if err := rows.Scan(&item.ID, &item.CartID, &item.MenuItemID, &item.Name,
			&item.Price, &item.Quantity, &item.Notes, &modJSON); err != nil {
			continue
		}
		if len(modJSON) > 0 {
			_ = json.Unmarshal(modJSON, &item.SelectedModifiers)
		}
		subtotal += item.Price * item.Quantity
		cart.Items = append(cart.Items, item)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch cart")
		return
	}

	if cart.Items == nil {
		cart.Items = []models.CartItem{}
	}
	cart.Subtotal = subtotal

	writeJSON(w, http.StatusOK, cart)
}

func (h *Handler) AddToCart(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req AddToCartRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Quantity <= 0 {
		req.Quantity = 1
	}
	if req.Quantity > 99 {
		writeError(w, http.StatusBadRequest, "quantity cannot exceed 99")
		return
	}
	if len(req.Notes) > 500 {
		writeError(w, http.StatusBadRequest, "notes cannot exceed 500 characters")
		return
	}
	if req.RestaurantID == "" {
		writeError(w, http.StatusBadRequest, "restaurant_id is required")
		return
	}

	// Get or create cart — if switching restaurants, clear existing cart.
	// Use a transaction with SELECT FOR UPDATE to prevent TOCTOU races when
	// concurrent requests from different restaurants interleave here.
	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to begin transaction")
		return
	}
	defer tx.Rollback(r.Context())

	var cartID string
	err = tx.QueryRow(r.Context(),
		`SELECT id FROM carts WHERE user_id = $1 FOR UPDATE`, user["user_id"],
	).Scan(&cartID)

	if err != nil {
		// Create new cart
		err = tx.QueryRow(r.Context(),
			`INSERT INTO carts (user_id, restaurant_id) VALUES ($1, $2) RETURNING id`,
			user["user_id"], req.RestaurantID,
		).Scan(&cartID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create cart")
			return
		}
	} else {
		// Check if same restaurant
		var existingRestID string
		if err := tx.QueryRow(r.Context(),
			`SELECT restaurant_id FROM carts WHERE id = $1`, cartID,
		).Scan(&existingRestID); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to read cart restaurant")
			return
		}

		if existingRestID != req.RestaurantID {
			// Clear old cart and update restaurant
			if _, err := tx.Exec(r.Context(), `DELETE FROM cart_items WHERE cart_id = $1`, cartID); err != nil {
				writeError(w, http.StatusInternalServerError, "failed to clear cart items")
				return
			}
			if _, err := tx.Exec(r.Context(), `UPDATE carts SET restaurant_id = $1 WHERE id = $2`, req.RestaurantID, cartID); err != nil {
				writeError(w, http.StatusInternalServerError, "failed to update cart restaurant")
				return
			}
		}
	}

	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to commit cart transaction")
		return
	}

	// Load base item price.
	var basePrice int
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT price FROM menu_items WHERE id = $1 AND restaurant_id = $2 AND is_available = true`,
		req.MenuItemID, req.RestaurantID,
	).Scan(&basePrice)
	if err != nil {
		writeError(w, http.StatusBadRequest, "menu item not found")
		return
	}

	// Validate + snapshot selected modifiers. We re-fetch from the DB using
	// the supplied ids so we're using server-side truth for names + prices,
	// never trusting client-side values.
	selected, modifierDelta, err := h.snapshotModifiers(r, req.MenuItemID, req.ModifierIDs)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	unitPrice := basePrice + modifierDelta

	selectedJSON, _ := json.Marshal(selected)

	// Atomic upsert: if the same menu item with the same modifiers already
	// exists in the cart, increment the quantity instead of adding a duplicate
	// row. Using INSERT ... ON CONFLICT eliminates the race condition that
	// existed with the previous SELECT-then-INSERT/UPDATE pattern.
	_, err = h.db.Pool.Exec(r.Context(),
		`INSERT INTO cart_items (cart_id, menu_item_id, quantity, notes, unit_price, selected_modifiers)
		 VALUES ($1, $2, $3, $4, $5, $6)
		 ON CONFLICT (cart_id, menu_item_id, selected_modifiers)
		 DO UPDATE SET quantity = cart_items.quantity + excluded.quantity`,
		cartID, req.MenuItemID, req.Quantity, req.Notes, unitPrice, selectedJSON)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to add item to cart")
		return
	}

	// Return the full updated cart so the client can display it without a
	// second round trip. Matches what UpdateCartItem / RemoveCartItem return.
	h.GetCart(w, r)
}

// snapshotModifiers validates that each supplied modifier id belongs to a
// group on the given menu item, then returns a SelectedModifier snapshot
// (with server-side names + prices) and the total price delta in cents.
//
// Returns an error if a modifier id doesn't exist or belongs to a different
// item — never trusts client-supplied prices.
func (h *Handler) snapshotModifiers(r *http.Request, menuItemID string, modifierIDs []string) ([]models.SelectedModifier, int, error) {
	if len(modifierIDs) == 0 {
		return nil, 0, nil
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT m.id, m.group_id, g.name AS group_name, m.name, m.price_delta
		   FROM menu_item_modifiers m
		   JOIN menu_item_modifier_groups g ON m.group_id = g.id
		  WHERE m.id = ANY($1) AND g.menu_item_id = $2
		    AND m.is_available = true`,
		modifierIDs, menuItemID)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var out []models.SelectedModifier
	total := 0
	for rows.Next() {
		var s models.SelectedModifier
		if err := rows.Scan(&s.ID, &s.GroupID, &s.GroupName, &s.Name, &s.PriceDelta); err != nil {
			continue
		}
		out = append(out, s)
		total += s.PriceDelta
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}

	// If the count doesn't match, some modifier ids were invalid or belonged
	// to a different menu item — reject the request rather than silently
	// applying a partial set.
	if len(out) != len(modifierIDs) {
		return nil, 0, fmt.Errorf("invalid modifier selection")
	}
	return out, total, nil
}

func (h *Handler) UpdateCartItem(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	itemID := chi.URLParam(r, "id")

	var req UpdateCartItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Quantity <= 0 {
		writeError(w, http.StatusBadRequest, "quantity must be at least 1")
		return
	}
	if req.Quantity > 99 {
		writeError(w, http.StatusBadRequest, "quantity cannot exceed 99")
		return
	}
	if req.Notes != nil && len(*req.Notes) > 500 {
		writeError(w, http.StatusBadRequest, "notes cannot exceed 500 characters")
		return
	}

	// notes = COALESCE($2, notes): persist edited special instructions, but keep
	// the existing notes when the client sends a quantity-only update (req.Notes
	// nil). Previously notes were read off the body and silently dropped.
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE cart_items SET quantity = $1, notes = COALESCE($2, notes)
		 WHERE id = $3 AND cart_id IN (SELECT id FROM carts WHERE user_id = $4)`,
		req.Quantity, req.Notes, itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "cart item not found")
		return
	}

	h.GetCart(w, r)
}

func (h *Handler) RemoveCartItem(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	itemID := chi.URLParam(r, "id")

	result, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM cart_items
		 WHERE id = $1 AND cart_id IN (SELECT id FROM carts WHERE user_id = $2)`,
		itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "cart item not found")
		return
	}

	h.GetCart(w, r)
}

func (h *Handler) ClearCart(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id = $1)`,
		user["user_id"]); err != nil {
		slog.Error("ClearCart: failed to delete cart items",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to clear cart")
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM carts WHERE user_id = $1`, user["user_id"]); err != nil {
		slog.Error("ClearCart: failed to delete cart",
			slog.String("user_id", user["user_id"]), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to clear cart")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "cleared"})
}
