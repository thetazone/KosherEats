package handlers

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

type AddToCartRequest struct {
	MenuItemID   string `json:"menu_item_id"`
	RestaurantID string `json:"restaurant_id"`
	Quantity     int    `json:"quantity"`
	Notes        string `json:"notes"`
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

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT ci.id, ci.cart_id, ci.menu_item_id, mi.name, mi.price, ci.quantity, ci.notes
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
		if err := rows.Scan(&item.ID, &item.CartID, &item.MenuItemID, &item.Name,
			&item.Price, &item.Quantity, &item.Notes); err != nil {
			continue
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

	// Add item
	var itemID string
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO cart_items (cart_id, menu_item_id, quantity, notes)
		 VALUES ($1, $2, $3, $4) RETURNING id`,
		cartID, req.MenuItemID, req.Quantity, req.Notes,
	).Scan(&itemID)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to add item to cart")
		return
	}

	writeJSON(w, http.StatusCreated, map[string]string{"id": itemID, "cart_id": cartID})
}

func (h *Handler) UpdateCartItem(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	itemID := chi.URLParam(r, "id")

	var req UpdateCartItemRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE cart_items SET quantity = $1, notes = $2
		 WHERE id = $3 AND cart_id IN (SELECT id FROM carts WHERE user_id = $4)`,
		req.Quantity, req.Notes, itemID, user["user_id"])

	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "cart item not found")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
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

	writeJSON(w, http.StatusOK, map[string]string{"status": "removed"})
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
