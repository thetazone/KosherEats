package handlers

import (
	"crypto/rand"
	"fmt"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// ForgotPassword issues a 6-digit reset code to the account's email (15-minute
// TTL, bcrypt-hashed at rest). It always returns 200 with the same message so it
// can't be used to probe which emails have accounts.
func (h *Handler) ForgotPassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
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

	var userID string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id FROM users WHERE lower(email) = $1`, email,
	).Scan(&userID); err != nil {
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
		`UPDATE users SET reset_code_hash = $1, reset_code_expires_at = NOW() + interval '15 minutes' WHERE id = $2`,
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
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, COALESCE(reset_code_hash, ''), reset_code_expires_at FROM users WHERE lower(email) = $1`, email,
	).Scan(&userID, &codeHash, &expires)
	// Same generic error for unknown email / no active code / expired / mismatch.
	if err != nil || codeHash == "" || expires == nil || time.Now().After(*expires) ||
		bcrypt.CompareHashAndPassword([]byte(codeHash), []byte(code)) != nil {
		writeError(w, http.StatusBadRequest, "invalid or expired reset code")
		return
	}

	newHash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not reset password")
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET password_hash = $1, reset_code_hash = NULL, reset_code_expires_at = NULL, updated_at = NOW() WHERE id = $2`,
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
