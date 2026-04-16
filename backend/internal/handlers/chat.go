package handlers

import (
	"context"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

// Order-scoped chat. All three parties (consumer, seller, courier) can read
// and write messages on the same order as long as they're associated with
// it. This matches how UberEats / DoorDash surface "Message your driver" and
// "Contact the restaurant" in a single thread.
//
// Polling-based (3s interval in iOS). A WebSocket upgrade is a later win if
// typing indicators matter; for MVP the latency is fine.

type ChatMessage struct {
	ID         string `json:"id"`
	OrderID    string `json:"order_id"`
	SenderID   string `json:"sender_user_id"`
	SenderRole string `json:"sender_role"`
	Text       string `json:"text"`
	CreatedAt  string `json:"created_at"`
}

// canAccessChat verifies the authenticated user is allowed to read/write
// messages on this order — they must be the consumer, the owning seller,
// or the assigned courier. Admins also get access for support cases.
func (h *Handler) canAccessChat(r *http.Request, orderID, userID, role string) bool {
	if role == "admin" {
		return true
	}
	var consumerID, ownerID string
	var courierID *string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT o.user_id, rest.owner_id, o.courier_id
		   FROM orders o
		   JOIN restaurants rest ON o.restaurant_id = rest.id
		  WHERE o.id = $1`, orderID,
	).Scan(&consumerID, &ownerID, &courierID)
	if err != nil {
		return false
	}

	switch role {
	case "consumer":
		return consumerID == userID
	case "seller":
		return ownerID == userID
	case "courier":
		return courierID != nil && *courierID == userID
	}
	return false
}

// ListChatMessages returns all messages on an order sorted oldest-first.
// Clients poll this every few seconds while the chat view is open.
func (h *Handler) ListChatMessages(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	if !h.canAccessChat(r, orderID, user["user_id"], user["role"]) {
		writeError(w, http.StatusForbidden, "not authorized for this order's chat")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, order_id, sender_user_id, sender_role, text, created_at
		   FROM chat_messages
		  WHERE order_id = $1
		  ORDER BY created_at ASC LIMIT 500`, orderID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to fetch messages")
		return
	}
	defer rows.Close()

	var out []ChatMessage
	for rows.Next() {
		var m ChatMessage
		var createdAt time.Time
		if err := rows.Scan(&m.ID, &m.OrderID, &m.SenderID, &m.SenderRole, &m.Text, &createdAt); err != nil {
			continue
		}
		m.CreatedAt = createdAt.Format(time.RFC3339)
		out = append(out, m)
	}
	if out == nil {
		out = []ChatMessage{}
	}
	writeJSON(w, http.StatusOK, out)
}

// SendChatMessage appends a message to the order chat. Role comes from the
// authenticated user's role claim, not from the request body, so clients
// can't spoof a seller message.
func (h *Handler) SendChatMessage(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)
	orderID := chi.URLParam(r, "id")

	if !h.canAccessChat(r, orderID, user["user_id"], user["role"]) {
		writeError(w, http.StatusForbidden, "not authorized for this order's chat")
		return
	}

	var req struct {
		Text string `json:"text"`
	}
	if err := readJSON(r, &req); err != nil || req.Text == "" {
		writeError(w, http.StatusBadRequest, "text required")
		return
	}
	if len(req.Text) > 2000 {
		writeError(w, http.StatusBadRequest, "message too long")
		return
	}

	var m ChatMessage
	var createdAt time.Time
	err := h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO chat_messages (order_id, sender_user_id, sender_role, text)
		 VALUES ($1, $2, $3, $4)
		 RETURNING id, order_id, sender_user_id, sender_role, text, created_at`,
		orderID, user["user_id"], user["role"], req.Text,
	).Scan(&m.ID, &m.OrderID, &m.SenderID, &m.SenderRole, &m.Text, &createdAt)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save message")
		return
	}
	m.CreatedAt = createdAt.Format(time.RFC3339)

	// Fan out push notifications to the other order participants so they
	// see the message even if their app is backgrounded. Runs in its own
	// goroutine with a detached context so slow APNs/FCM calls don't block
	// the client's send-message response.
	go h.notify.ChatMessageSent(context.Background(), orderID, user["user_id"], user["role"], req.Text)

	writeJSON(w, http.StatusCreated, m)
}
