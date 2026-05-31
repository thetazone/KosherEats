package handlers

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
	"github.com/koshereats/backend/internal/pos"
	"github.com/koshereats/backend/internal/pos/clover"
)

// Per-restaurant POS integration endpoints. The seller app's Settings →
// Integrations screen drives all of these.
//
// OAuth flow shape (Clover today, Square/Toast follow same pattern):
//   1. Seller taps "Connect Clover" → app calls GET /seller/integrations/clover/connect-url
//   2. We mint a state token (HMAC of user_id + restaurant_id + nonce, signed
//      with JWTSecret) and return the AuthorizeURL pre-baked with the state.
//   3. App opens that URL in an in-app browser → Clover does its consent UI
//      → redirects back to /api/v1/integrations/clover/callback with ?code,
//      ?merchant_id, ?state.
//   4. Callback validates state, exchanges code → access_token, encrypts
//      tokens, upserts integration row, renders a small "you can close this
//      tab" HTML page that the in-app browser detects to close itself.

// CloverConnectURL returns the URL the seller's browser should open to
// begin Clover OAuth. The state token is the auth gate on the callback —
// without it we can't tell which seller initiated the flow.
func (h *Handler) CloverConnectURL(w http.ResponseWriter, r *http.Request) {
	if !clover.Configured() {
		writeError(w, http.StatusServiceUnavailable, "Clover OAuth is not configured on this deployment")
		return
	}
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restaurantID, err := h.resolveSellerRestaurantID(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusBadRequest, "restaurant not found")
		return
	}

	state, err := h.signPOSState(user["user_id"], restaurantID, string(pos.ProviderClover))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to mint state")
		return
	}
	redirect := h.publicBaseURL() + "/api/v1/integrations/clover/callback"
	writeJSON(w, http.StatusOK, map[string]string{
		"connect_url": clover.AuthorizeURL(state, redirect),
	})
}

// CloverOAuthCallback is hit by Clover redirecting the seller's browser
// after consent. NOT inside the /seller middleware — the request comes
// from the browser, not from an authenticated mobile-app session. State
// token is the auth gate.
func (h *Handler) CloverOAuthCallback(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("code")
	merchantID := r.URL.Query().Get("merchant_id")
	state := r.URL.Query().Get("state")
	if code == "" || merchantID == "" || state == "" {
		writeCloverCallbackPage(w, "Connection failed", "Missing parameters from Clover. Please try again from the app.")
		return
	}

	claims, err := h.verifyPOSState(state)
	if err != nil {
		writeCloverCallbackPage(w, "Connection failed", "Authorization expired or invalid. Please retry the connect flow from the seller app.")
		return
	}
	if claims.Provider != string(pos.ProviderClover) {
		writeCloverCallbackPage(w, "Connection failed", "State token provider mismatch.")
		return
	}

	redirect := h.publicBaseURL() + "/api/v1/integrations/clover/callback"
	tok, err := clover.ExchangeCode(r.Context(), code, merchantID, redirect)
	if err != nil {
		slog.Error("clover oauth exchange", slog.String("error", err.Error()))
		writeCloverCallbackPage(w, "Connection failed", "Couldn't exchange the auth code with Clover. Try again from the app.")
		return
	}

	encAccess, err := pos.Encrypt([]byte(tok.AccessToken))
	if err != nil {
		writeCloverCallbackPage(w, "Connection failed", "Server crypto error. Contact support.")
		return
	}
	var encRefresh []byte
	if tok.RefreshToken != "" {
		encRefresh, _ = pos.Encrypt([]byte(tok.RefreshToken))
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO restaurant_pos_integrations (
		     restaurant_id, provider, merchant_id, access_token, refresh_token, is_active
		 ) VALUES ($1, $2, $3, $4, $5, true)
		 ON CONFLICT (restaurant_id, provider) WHERE is_active
		 DO UPDATE SET merchant_id = EXCLUDED.merchant_id,
		               access_token = EXCLUDED.access_token,
		               refresh_token = EXCLUDED.refresh_token,
		               updated_at = NOW()`,
		claims.RestaurantID, string(pos.ProviderClover), tok.MerchantID, encAccess, encRefresh,
	); err != nil {
		slog.Error("clover integration upsert", slog.String("error", err.Error()))
		writeCloverCallbackPage(w, "Connection failed", "Couldn't save the integration. Try again.")
		return
	}

	writeCloverCallbackPage(w, "Connected!",
		"Your Clover account is now linked. You can close this window and return to the seller app.")
}

// ListPOSIntegrations returns the current connected integrations for the
// seller's restaurant. Used to populate the Integrations screen.
func (h *Handler) ListPOSIntegrations(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restaurantID, err := h.resolveSellerRestaurantID(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusBadRequest, "restaurant not found")
		return
	}
	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, provider, merchant_id, is_active, created_at, last_used_at
		   FROM restaurant_pos_integrations
		  WHERE restaurant_id = $1
		  ORDER BY created_at DESC`, restaurantID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list integrations")
		return
	}
	defer rows.Close()

	type row struct {
		ID         string     `json:"id"`
		Provider   string     `json:"provider"`
		MerchantID string     `json:"merchant_id"`
		IsActive   bool       `json:"is_active"`
		CreatedAt  time.Time  `json:"created_at"`
		LastUsedAt *time.Time `json:"last_used_at,omitempty"`
	}
	out := []row{}
	for rows.Next() {
		var x row
		if err := rows.Scan(&x.ID, &x.Provider, &x.MerchantID, &x.IsActive, &x.CreatedAt, &x.LastUsedAt); err != nil {
			continue
		}
		out = append(out, x)
	}
	writeJSON(w, http.StatusOK, out)
}

// DisconnectPOSIntegration soft-deletes by flipping is_active=false. The
// row stays so we keep the OAuth audit trail; a future reconnect upserts
// over the unique-index-where-active.
func (h *Handler) DisconnectPOSIntegration(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restaurantID, err := h.resolveSellerRestaurantID(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusBadRequest, "restaurant not found")
		return
	}
	id := chi.URLParam(r, "id")
	result, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurant_pos_integrations
		    SET is_active = false, updated_at = NOW()
		  WHERE id = $1 AND restaurant_id = $2`, id, restaurantID)
	if err != nil || result.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "integration not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "disconnected"})
}

// TestPOSIntegration hits the provider's auth-only health endpoint to
// verify the access token still works.
func (h *Handler) TestPOSIntegration(w http.ResponseWriter, r *http.Request) {
	if h.posRegistry == nil {
		writeError(w, http.StatusServiceUnavailable, "POS integrations not configured")
		return
	}
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restaurantID, err := h.resolveSellerRestaurantID(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusBadRequest, "restaurant not found")
		return
	}
	id := chi.URLParam(r, "id")

	var providerStr string
	var accessEnc, refreshEnc []byte
	var merchantID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT provider, merchant_id, access_token, refresh_token
		   FROM restaurant_pos_integrations
		  WHERE id = $1 AND restaurant_id = $2 AND is_active = true`,
		id, restaurantID,
	).Scan(&providerStr, &merchantID, &accessEnc, &refreshEnc); err != nil {
		writeError(w, http.StatusNotFound, "integration not found")
		return
	}
	at, err := pos.Decrypt(accessEnc)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "decrypt failed")
		return
	}
	adapter, ok := h.posRegistry.AdapterFor(pos.Provider(providerStr))
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown provider")
		return
	}
	integ := pos.Integration{
		ID: id, RestaurantID: restaurantID, Provider: pos.Provider(providerStr),
		MerchantID: merchantID, AccessToken: string(at), IsActive: true,
	}
	if err := adapter.TestConnection(r.Context(), integ); err != nil {
		slog.Error("POS TestConnection failed", "provider", providerStr, "restaurant_id", restaurantID, "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"status": "fail", "error": "POS connection test failed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ── helpers ──────────────────────────────────────────────

// resolveSellerRestaurantID picks the restaurant ID the request applies to.
// Honors ?restaurant_id= when set (multi-restaurant sellers); otherwise
// returns the seller's first owned restaurant.
func (h *Handler) resolveSellerRestaurantID(r *http.Request, userID string) (string, error) {
	if v := r.URL.Query().Get("restaurant_id"); v != "" {
		var ok bool
		if err := h.db.Pool.QueryRow(r.Context(),
			`SELECT EXISTS (SELECT 1 FROM restaurants WHERE id = $1 AND owner_id = $2)`,
			v, userID,
		).Scan(&ok); err != nil || !ok {
			return "", fmt.Errorf("restaurant not owned by seller")
		}
		return v, nil
	}
	var id string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM restaurants WHERE owner_id = $1 ORDER BY created_at ASC LIMIT 1`,
		userID,
	).Scan(&id); err != nil {
		return "", fmt.Errorf("no restaurants found for this seller")
	}
	return id, nil
}

// signPOSState builds a tamper-resistant state token for the OAuth flow.
// Token format: base64(json({user_id, restaurant_id, provider, exp})).sig
// where sig = HMAC-SHA256(token_body, JWTSecret).
type posStateClaims struct {
	UserID       string `json:"u"`
	RestaurantID string `json:"r"`
	Provider     string `json:"p"`
	Exp          int64  `json:"e"`
}

func (h *Handler) signPOSState(userID, restaurantID, provider string) (string, error) {
	if h.cfg.JWTSecret == "" {
		return "", fmt.Errorf("JWT_SECRET not set")
	}
	claims := posStateClaims{
		UserID: userID, RestaurantID: restaurantID, Provider: provider,
		Exp: time.Now().Add(15 * time.Minute).Unix(),
	}
	body, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	encoded := base64.RawURLEncoding.EncodeToString(body)
	mac := hmac.New(sha256.New, []byte(h.cfg.JWTSecret))
	mac.Write([]byte(encoded))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return encoded + "." + sig, nil
}

func (h *Handler) verifyPOSState(token string) (*posStateClaims, error) {
	parts := strings.SplitN(token, ".", 2)
	if len(parts) != 2 {
		return nil, fmt.Errorf("invalid state format")
	}
	encoded, sig := parts[0], parts[1]
	mac := hmac.New(sha256.New, []byte(h.cfg.JWTSecret))
	mac.Write([]byte(encoded))
	expected := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(sig), []byte(expected)) {
		return nil, fmt.Errorf("invalid state signature")
	}
	body, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	var claims posStateClaims
	if err := json.Unmarshal(body, &claims); err != nil {
		return nil, err
	}
	if time.Now().Unix() > claims.Exp {
		return nil, fmt.Errorf("state expired")
	}
	return &claims, nil
}

// writeCloverCallbackPage renders a small HTML page after the OAuth round
// trip. The in-app browser closes itself when it sees a specific marker
// (the deep-link scheme); we also surface a human-readable status so a
// regular browser session shows something useful.
func writeCloverCallbackPage(w http.ResponseWriter, title, message string) {
	body := fmt.Sprintf(`<!doctype html>
<html><head><title>%s</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:480px;margin:64px auto;padding:24px;text-align:center;color:#111;line-height:1.5}h2{margin-bottom:8px}</style>
</head>
<body>
<h2>%s</h2>
<p>%s</p>
<script>
  // Deep-link back into the seller app if it was launched from there.
  setTimeout(function(){
    try { window.location.href = "koshereats-seller://integrations/clover/callback"; } catch(e){}
  }, 600);
</script>
</body></html>`,
		html.EscapeString(title), html.EscapeString(title), html.EscapeString(message))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(body))
}

// PushOrderToPOS is the fire-and-forget entry point called from order
// status handlers. Lives here (in handlers) so the order handler doesn't
// import the pos package directly — keeps the handler import graph small.
func (h *Handler) PushOrderToPOS(ctx context.Context, restaurantID string, order *models.Order) {
	if h.posRegistry == nil {
		return
	}
	if err := h.posRegistry.PushToConnectedPOS(ctx, restaurantID, order); err != nil {
		// ErrNoIntegration is the common case — most restaurants haven't
		// connected a POS. Don't log it as an error.
		if err == pos.ErrNoIntegration {
			return
		}
		slog.Warn("pos push failed",
			slog.String("restaurant_id", restaurantID),
			slog.String("order_id", order.ID),
			slog.String("error", err.Error()))
	}
}
