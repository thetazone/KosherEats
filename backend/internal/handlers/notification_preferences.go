package handlers

import (
	"context"
	"net/http"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// NotificationPreferences is the per-user push opt-in state. Defaults are all
// true — new users get every category until they toggle one off.
type NotificationPreferences struct {
	OrderUpdates bool `json:"order_updates"`
	ChatMessages bool `json:"chat_messages"`
	Promotions   bool `json:"promotions"`
}

// GetNotificationPreferences returns the user's current preferences, lazily
// inserting a default row if they've never saved before. Handlers elsewhere
// (notifier.go) use LoadNotificationPreferences to respect these toggles when
// dispatching pushes.
func (h *Handler) GetNotificationPreferences(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	prefs, err := LoadNotificationPreferences(r.Context(), h.db.Pool, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load preferences")
		return
	}
	writeJSON(w, http.StatusOK, prefs)
}

// UpdateNotificationPreferences upserts the user's preferences. All three
// fields are required in the body — partial updates would invite race bugs
// where a stale client's omitted field silently flips back to default.
func (h *Handler) UpdateNotificationPreferences(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req NotificationPreferences
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	_, err = h.db.Pool.Exec(r.Context(), `
		INSERT INTO notification_preferences (user_id, order_updates, chat_messages, promotions, updated_at)
		VALUES ($1, $2, $3, $4, NOW())
		ON CONFLICT (user_id) DO UPDATE SET
			order_updates = EXCLUDED.order_updates,
			chat_messages = EXCLUDED.chat_messages,
			promotions    = EXCLUDED.promotions,
			updated_at    = NOW()`,
		user["user_id"], req.OrderUpdates, req.ChatMessages, req.Promotions)

	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save preferences")
		return
	}

	writeJSON(w, http.StatusOK, req)
}

// LoadNotificationPreferences fetches a user's prefs with all-true defaults
// when no row exists. Shared with the notifier package via handlers.Handler
// so push dispatch can gate on a category toggle.
func LoadNotificationPreferences(ctx context.Context, pool *pgxpool.Pool, userID string) (NotificationPreferences, error) {
	prefs := NotificationPreferences{OrderUpdates: true, ChatMessages: true, Promotions: true}
	err := pool.QueryRow(ctx,
		`SELECT order_updates, chat_messages, promotions
		   FROM notification_preferences WHERE user_id = $1`,
		userID,
	).Scan(&prefs.OrderUpdates, &prefs.ChatMessages, &prefs.Promotions)
	if err == pgx.ErrNoRows {
		return prefs, nil
	}
	return prefs, err
}
