package handlers

import (
	"crypto/rand"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

// maxResetCodeAttempts caps how many wrong codes may be tried against a single
// active reset code before it is invalidated, bounding brute-force of the
// 6-digit space. The user can always request a fresh code.
const maxResetCodeAttempts = 5

// ForgotPassword issues a 6-digit reset code to the account's email (15-minute
// TTL, bcrypt-hashed at rest). It always returns 200 with the same message so it
// can't be used to probe which emails have accounts.
func (h *Handler) ForgotPassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
		// Role + Vertical scope the lookup since (email, role, vertical) is the
		// unique account key — an email can have both a consumer and a seller
		// account. Empty defaults to consumer/kosher so older clients that don't
		// send these fields keep resolving to the original consumer account.
		Role     models.UserRole `json:"role,omitempty"`
		Vertical string          `json:"vertical,omitempty"`
	}
	if err := readJSON(r, &req); err != nil || strings.TrimSpace(req.Email) == "" {
		writeError(w, http.StatusBadRequest, "email is required")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))

	respondOK := func() {
		writeJSON(w, http.StatusOK, map[string]string{
			"message": "If an account exists for that email, a reset code has been sent.",
		})
	}

	// New clients send role+vertical so the lookup targets the EXACT account
	// (email is unique per (role, vertical), not globally). Older deployed
	// clients send email only — fall back to the legacy email-only lookup so
	// their reset keeps working (no deploy-coordination break).
	var userID string
	var lookupErr error
	if req.Role != "" {
		lookupErr = h.db.Pool.QueryRow(r.Context(),
			`SELECT id FROM users WHERE lower(email) = $1 AND role = $2 AND vertical = $3`,
			email, req.Role, normalizeVertical(req.Vertical)).Scan(&userID)
	} else {
		lookupErr = h.db.Pool.QueryRow(r.Context(),
			`SELECT id FROM users WHERE lower(email) = $1 ORDER BY created_at LIMIT 1`,
			email).Scan(&userID)
	}
	if lookupErr != nil {
		respondOK() // don't reveal whether the email exists
		return
	}

	code := sixDigitCode()
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not start password reset")
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET reset_code_hash = $1, reset_code_expires_at = NOW() + interval '15 minutes', reset_code_attempts = 0 WHERE id = $2`,
		string(hash), userID,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "could not start password reset")
		return
	}

	if h.email != nil {
		subject := "Your KosherEats password reset code"
		text := fmt.Sprintf("Your KosherEats password reset code is %s. It expires in 15 minutes. "+
			"If you didn't request this, you can ignore this email.", code)
		htmlBody := fmt.Sprintf("<p>Your KosherEats password reset code is:</p>"+
			"<p style=\"font-size:28px;font-weight:bold;letter-spacing:4px\">%s</p>"+
			"<p>It expires in 15 minutes. If you didn't request this, you can ignore this email.</p>", code)
		_ = h.email.Send(email, subject, text, htmlBody)
	}
	respondOK()
}

// ResetPassword verifies the emailed code and sets a new password.
func (h *Handler) ResetPassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email       string `json:"email"`
		Code        string `json:"code"`
		NewPassword string `json:"new_password"`
		// Role + Vertical scope the lookup to the exact account the code was
		// issued for. Empty defaults to consumer/kosher for back-compat — must
		// match the defaults used by ForgotPassword so the code resolves to the
		// same row it was written to.
		Role     models.UserRole `json:"role,omitempty"`
		Vertical string          `json:"vertical,omitempty"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	code := strings.TrimSpace(req.Code)
	if email == "" || code == "" {
		writeError(w, http.StatusBadRequest, "email and code are required")
		return
	}
	if len(req.NewPassword) < 6 {
		writeError(w, http.StatusBadRequest, "new password must be at least 6 characters")
		return
	}
	var userID, codeHash string
	var expires *time.Time
	var attempts int
	// Scope to the exact account when the client sends role+vertical (new
	// clients); fall back to legacy email-only for older clients so their reset
	// still works. Must mirror ForgotPassword's lookup so the same row is hit.
	var lookupErr error
	if req.Role != "" {
		lookupErr = h.db.Pool.QueryRow(r.Context(),
			`SELECT id, COALESCE(reset_code_hash, ''), reset_code_expires_at, reset_code_attempts
			 FROM users WHERE lower(email) = $1 AND role = $2 AND vertical = $3`,
			email, req.Role, normalizeVertical(req.Vertical),
		).Scan(&userID, &codeHash, &expires, &attempts)
	} else {
		lookupErr = h.db.Pool.QueryRow(r.Context(),
			`SELECT id, COALESCE(reset_code_hash, ''), reset_code_expires_at, reset_code_attempts
			 FROM users WHERE lower(email) = $1 ORDER BY created_at LIMIT 1`,
			email,
		).Scan(&userID, &codeHash, &expires, &attempts)
	}
	// Same generic error for unknown email / no active code / expired.
	if lookupErr != nil || codeHash == "" || expires == nil || time.Now().After(*expires) {
		writeError(w, http.StatusBadRequest, "invalid or expired reset code")
		return
	}

	// Too many wrong codes already tried against this code — burn it so the
	// attacker can't keep guessing, and force the user to request a new one.
	if attempts >= maxResetCodeAttempts {
		_, _ = h.db.Pool.Exec(r.Context(),
			`UPDATE users SET reset_code_hash = NULL, reset_code_expires_at = NULL, reset_code_attempts = 0 WHERE id = $1`,
			userID,
		)
		writeError(w, http.StatusBadRequest, "invalid or expired reset code")
		return
	}

	if bcrypt.CompareHashAndPassword([]byte(codeHash), []byte(code)) != nil {
		// Wrong code — count the attempt. If this pushes us to the cap, clear
		// the code in the same write so it can't be used at all.
		attempts++
		if attempts >= maxResetCodeAttempts {
			_, _ = h.db.Pool.Exec(r.Context(),
				`UPDATE users SET reset_code_hash = NULL, reset_code_expires_at = NULL, reset_code_attempts = 0 WHERE id = $1`,
				userID,
			)
		} else {
			_, _ = h.db.Pool.Exec(r.Context(),
				`UPDATE users SET reset_code_attempts = $1 WHERE id = $2`,
				attempts, userID,
			)
		}
		writeError(w, http.StatusBadRequest, "invalid or expired reset code")
		return
	}

	newHash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not reset password")
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET password_hash = $1, reset_code_hash = NULL, reset_code_expires_at = NULL, reset_code_attempts = 0, updated_at = NOW() WHERE id = $2`,
		string(newHash), userID,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "could not reset password")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"message": "Password updated. You can now sign in with your new password.",
	})
}

// sixDigitCode returns a uniformly-random "000000".."999999" string.
func sixDigitCode() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	n := (uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])) % 1000000
	return fmt.Sprintf("%06d", n)
}
