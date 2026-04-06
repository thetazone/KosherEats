package handlers

import "net/http"

// Device token registration. The iOS apps call RegisterDevice after
// UNUserNotificationCenter hands them an APNs device token.
//
// The same physical device may appear for different users over time (login /
// logout), so we upsert on (token, app) and just overwrite the user_id.

type RegisterDeviceRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"` // "ios" | "android"
	App      string `json:"app"`      // "consumer" | "seller" | "courier"
}

func (h *Handler) RegisterDevice(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req RegisterDeviceRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Token == "" || req.Platform == "" || req.App == "" {
		writeError(w, http.StatusBadRequest, "token, platform, and app are required")
		return
	}

	_, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO device_tokens (user_id, token, platform, app)
		 VALUES ($1, $2, $3, $4)
		 ON CONFLICT (token, app) DO UPDATE
		   SET user_id = EXCLUDED.user_id, updated_at = NOW()`,
		user["user_id"], req.Token, req.Platform, req.App)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to register device")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"status": "registered"})
}

func (h *Handler) UnregisterDevice(w http.ResponseWriter, r *http.Request) {
	user := getUserFromContext(r)

	var req RegisterDeviceRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	_, _ = h.db.Pool.Exec(r.Context(),
		`DELETE FROM device_tokens WHERE user_id = $1 AND token = $2 AND app = $3`,
		user["user_id"], req.Token, req.App)
	writeJSON(w, http.StatusOK, map[string]string{"status": "unregistered"})
}
