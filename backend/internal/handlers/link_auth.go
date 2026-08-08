package handlers

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type LinkProviderRequest struct {
	Provider string `json:"provider"`        // "google", "apple", "phone"
	Token    string `json:"token,omitempty"` // ID token (Google/Apple)
	Phone    string `json:"phone,omitempty"` // E.164, phone linking only
	Code     string `json:"code,omitempty"`  // OTP, phone linking only
	Nonce    string `json:"nonce,omitempty"` // Apple only
}

type LinkedProvider struct {
	Provider  string    `json:"provider"`
	CreatedAt time.Time `json:"created_at"`
}

func (h *Handler) LinkProvider(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	userID := user["user_id"]

	var req LinkProviderRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var providerID string

	switch req.Provider {
	case "google":
		if req.Token == "" {
			writeError(w, http.StatusBadRequest, "token is required")
			return
		}
		_, _, _, _, providerID, err = h.verifyGoogleToken(req.Token)
	case "apple":
		if req.Token == "" {
			writeError(w, http.StatusBadRequest, "token is required")
			return
		}
		_, _, _, providerID, err = h.verifyAppleToken(req.Token, "", "", req.Nonce)
	case "phone":
		phone := normalizePhone(req.Phone)
		if !looksLikeE164(phone) || req.Code == "" {
			writeError(w, http.StatusBadRequest, "phone and code are required for phone linking")
			return
		}
		// Use the lockout-aware verifier (TTL + failed_attempts + locked_until),
		// not a bare sms.Check — otherwise an attacker could brute-force a phone's
		// OTP through the link path, bypassing the protection phone login enforces.
		if okv, status, msg := h.verifyPhoneOTP(r.Context(), phone, req.Code); !okv {
			writeError(w, status, msg)
			return
		}
		providerID = phone
		err = nil
	default:
		writeError(w, http.StatusBadRequest, "unsupported provider: "+req.Provider)
		return
	}

	if err != nil {
		writeError(w, http.StatusUnauthorized, "failed to verify token")
		return
	}

	// Reject if this provider identity is already linked to a different user
	// with the same role and vertical.
	var existingUserID string
	lookupErr := h.db.Pool.QueryRow(r.Context(),
		`SELECT u.id FROM users u
		 JOIN user_auth_providers uap ON u.id = uap.user_id
		 WHERE uap.provider = $1 AND uap.provider_id = $2
		   AND u.role = (SELECT role FROM users WHERE id = $3)
		   AND u.vertical = (SELECT vertical FROM users WHERE id = $3)
		   AND u.id != $3`,
		req.Provider, providerID, userID,
	).Scan(&existingUserID)
	if lookupErr == nil {
		writeError(w, http.StatusConflict, "this account is already linked to another user")
		return
	}

	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO user_auth_providers (user_id, provider, provider_id)
		 VALUES ($1, $2, $3)
		 ON CONFLICT (user_id, provider) DO UPDATE SET provider_id = $3`,
		userID, req.Provider, providerID,
	); err != nil {
		// uq_uap_provider_identity (migration 046) is globally unique on
		// (provider, provider_id), but the pre-check above is scoped to this
		// user's role+vertical. Linking an identity already bound to a DIFFERENT
		// account (e.g. the same Apple ID on your consumer account, now linking
		// on the seller app) slips past the pre-check and violates the global
		// index — a 23505 that must surface as a clean 409, not a 500.
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			writeError(w, http.StatusConflict,
				fmt.Sprintf("this %s account is already linked to another KosherEats account", req.Provider))
			return
		}
		slog.Error("failed to link provider",
			slog.String("user_id", userID), slog.String("provider", req.Provider),
			slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to link provider")
		return
	}

	// Intentionally does NOT sync users.auth_provider. That legacy column is the
	// password-eligibility gate (Login + password_reset filter on
	// auth_provider IS NULL OR 'email'); its WHERE clause here targeted exactly
	// the password accounts and flipped them to the social provider, silently
	// disabling password login AND password reset for anyone who linked a
	// provider to their password account. The junction insert above is the
	// authoritative link record; the legacy column must stay put.

	writeJSON(w, http.StatusOK, map[string]string{"status": "linked"})
}

func (h *Handler) UnlinkProvider(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	userID := user["user_id"]

	provider := chi.URLParam(r, "provider")
	if provider == "" {
		writeError(w, http.StatusBadRequest, "provider is required")
		return
	}

	var count int
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COUNT(*) FROM user_auth_providers WHERE user_id = $1`, userID,
	).Scan(&count); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to check providers")
		return
	}

	if count <= 1 {
		writeError(w, http.StatusBadRequest, "cannot remove last sign-in method")
		return
	}

	tag, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM user_auth_providers WHERE user_id = $1 AND provider = $2`,
		userID, provider,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to unlink provider")
		return
	}
	if tag.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, fmt.Sprintf("provider %q is not linked", provider))
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "unlinked"})
}

func (h *Handler) ListLinkedProviders(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT provider, created_at FROM user_auth_providers
		 WHERE user_id = $1 ORDER BY created_at`, user["user_id"],
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list providers")
		return
	}
	defer rows.Close()

	providers := make([]LinkedProvider, 0)
	for rows.Next() {
		var p LinkedProvider
		if err := rows.Scan(&p.Provider, &p.CreatedAt); err != nil {
			continue
		}
		providers = append(providers, p)
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list providers")
		return
	}

	writeJSON(w, http.StatusOK, providers)
}
