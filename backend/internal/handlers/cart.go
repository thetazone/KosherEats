package handlers

import (
	"encoding/json"
	"fmt"
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
	Quantity int    `json:"quantity"`
	Notes    string `json:"notes"`
}

func (h *Handler) GetCart(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var cart models.Cart
	err := h.db.Pool.QueryRow(r.Context(),
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

	if cart.Items == nil {
		cart.Items = []models.CartItem{}
	}
	cart.Subtotal = subtotal

	writeJSON(w, http.StatusOK, cart)
}

func (h *Handler) AddToCart(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req AddToCartRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Quantity <= 0 {
		req.Quantity = 1
	}

	// Get or create cart — if switching restaurants, clear existing cart
	var cartID string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM carts WHERE user_id = $1`, user["user_id"],
	).Scan(&cartID)

	if err != nil {
		// Create new cart
		err = h.db.Pool.QueryRow(r.Context(),
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
		h.db.Pool.QueryRow(r.Context(),
			`SELECT restaurant_id FROM carts WHERE id = $1`, cartID,
		).Scan(&existingRestID)

		if existingRestID != req.RestaurantID {
			// Clear old cart and update restaurant
			h.db.Pool.Exec(r.Context(), `DELETE FROM cart_items WHERE cart_id = $1`, cartID)
			h.db.Pool.Exec(r.Context(), `UPDATE carts SET restaurant_id = $1 WHERE id = $2`, req.RestaurantID, cartID)
		}
	}

	// Load base item price.
	var basePrice int
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT price FROM menu_items WHERE id = $1`, req.MenuItemID,
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
		  WHERE m.id = ANY($1) AND g.menu_item_id = $2`,
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

	// If the count doesn't match, some modifier ids were invalid or belonged
	// to a different menu item — reject the request rather than silently
	// applying a partial set.
	if len(out) != len(modifierIDs) {
		return nil, 0, fmt.Errorf("invalid modifier selection")
	}
	return out, total, nil
}

func (h *Handler) UpdateCartItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
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

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE cart_items SET quantity = $1
		 WHERE id = $2 AND cart_id IN (SELECT id FROM carts WHERE user_id = $3)`,
		req.Quantity, itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "cart item not found")
		return
	}

	h.GetCart(w, r)
}

func (h *Handler) RemoveCartItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
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
	user := getUserFromContext(r)

	h.db.Pool.Exec(r.Context(),
		`DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id = $1)`,
		user["user_id"])
	h.db.Pool.Exec(r.Context(),
		`DELETE FROM carts WHERE user_id = $1`, user["user_id"])

	writeJSON(w, http.StatusOK, map[string]string{"status": "cleared"})
}
