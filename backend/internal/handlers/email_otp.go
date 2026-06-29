package handlers

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

// Email one-time-code verification. Mirrors the password-reset hardening in
// password_reset.go (bcrypt-hashed 6-digit code, short TTL, attempt cap → burn)
// but stores state in the email_otp table (migration 054) instead of on the
// users row, because the signup flow needs to verify an email BEFORE any
// account exists.
//
// Two purposes share the table, keyed (email, purpose):
//   - 'signup'    — unauthenticated, pre-account. Verify sets verified_at; that
//                   timestamp is the proof window Register checks before it
//                   creates the consumer account (see signupEmailVerified).
//   - 'add_email' — authenticated. Used by the phone-first and Apple flows to
//                   attach and verify a real inbox onto an existing account.
const (
	emailOTPPurposeSignup   = "signup"
	emailOTPPurposeAddEmail = "add_email"

	// emailOTPTTL is how long a freshly-sent code stays valid.
	emailOTPTTL = 15 * time.Minute
	// emailVerifyProofTTL bounds how long a verified 'signup' code remains a
	// valid proof for Register — long enough to pick a password, short enough
	// that a leaked-then-abandoned verification can't be reused much later.
	emailVerifyProofTTL = 30 * time.Minute
	// maxEmailOTPAttempts caps wrong guesses against one active code before it's
	// burned, bounding brute-force of the 6-digit space (matches reset codes).
	maxEmailOTPAttempts = 5
)

// startEmailOTP generates a code, stores its bcrypt hash with a 15-minute TTL
// (resetting any prior code/attempts/verification for this email+purpose), and
// emails it. Reuses sixDigitCode() and h.email from the password-reset path.
func (h *Handler) startEmailOTP(ctx context.Context, email, purpose string) error {
	code := sixDigitCode()
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	if _, err := h.db.Pool.Exec(ctx,
		`INSERT INTO email_otp (email, purpose, code_hash, expires_at, attempts, verified_at, created_at)
		 VALUES ($1, $2, $3, NOW() + interval '15 minutes', 0, NULL, NOW())
		 ON CONFLICT (email, purpose) DO UPDATE
		   SET code_hash = $3, expires_at = NOW() + interval '15 minutes',
		       attempts = 0, verified_at = NULL, created_at = NOW()`,
		email, purpose, string(hash),
	); err != nil {
		return err
	}
	if h.email != nil {
		subject := "Your KosherEats verification code"
		text := fmt.Sprintf("Your KosherEats verification code is %s. It expires in 15 minutes. "+
			"If you didn't request this, you can ignore this email.", code)
		htmlBody := fmt.Sprintf("<p>Your KosherEats verification code is:</p>"+
			"<p style=\"font-size:28px;font-weight:bold;letter-spacing:4px\">%s</p>"+
			"<p>It expires in 15 minutes. If you didn't request this, you can ignore this email.</p>", code)
		_ = h.email.Send(email, subject, text, htmlBody)
	}
	return nil
}

// checkEmailOTP validates a submitted code against the stored hash with the same
// TTL + lockout discipline as verifyPhoneOTP / ResetPassword. It does NOT clear
// the row on success — the caller decides (signup marks verified_at; add_email
// updates the user then clears). On every failure it manages the attempt
// counter and burns the code at the cap. Returns the HTTP status + message the
// caller should write when !ok.
func (h *Handler) checkEmailOTP(ctx context.Context, email, purpose, code string) (ok bool, status int, msg string) {
	var codeHash string
	var expires *time.Time
	var attempts int
	err := h.db.Pool.QueryRow(ctx,
		`SELECT code_hash, expires_at, attempts FROM email_otp WHERE email = $1 AND purpose = $2`,
		email, purpose,
	).Scan(&codeHash, &expires, &attempts)
	if err != nil || codeHash == "" || expires == nil || time.Now().After(*expires) {
		return false, http.StatusBadRequest, "invalid or expired code"
	}
	if attempts >= maxEmailOTPAttempts {
		h.clearEmailOTP(ctx, email, purpose)
		return false, http.StatusBadRequest, "invalid or expired code"
	}
	if bcrypt.CompareHashAndPassword([]byte(codeHash), []byte(code)) != nil {
		attempts++
		if attempts >= maxEmailOTPAttempts {
			h.clearEmailOTP(ctx, email, purpose)
		} else {
			_, _ = h.db.Pool.Exec(ctx,
				`UPDATE email_otp SET attempts = $1 WHERE email = $2 AND purpose = $3`,
				attempts, email, purpose)
		}
		return false, http.StatusBadRequest, "invalid or expired code"
	}
	return true, 0, ""
}

// signupEmailVerified reports whether a recent, still-valid 'signup' code was
// verified for this email — the gate Register applies to consumer signups.
func (h *Handler) signupEmailVerified(ctx context.Context, email string) (bool, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	var verifiedAt *time.Time
	err := h.db.Pool.QueryRow(ctx,
		`SELECT verified_at FROM email_otp WHERE email = $1 AND purpose = $2`,
		email, emailOTPPurposeSignup,
	).Scan(&verifiedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	if verifiedAt == nil || time.Since(*verifiedAt) > emailVerifyProofTTL {
		return false, nil
	}
	return true, nil
}

// clearEmailOTP removes a code row (best-effort). Called after Register consumes
// a signup proof and after a successful add_email verification.
func (h *Handler) clearEmailOTP(ctx context.Context, email, purpose string) {
	email = strings.ToLower(strings.TrimSpace(email))
	_, _ = h.db.Pool.Exec(ctx, `DELETE FROM email_otp WHERE email = $1 AND purpose = $2`, email, purpose)
}

// ── Pre-register (unauthenticated) ───────────────────────────────────────────

// StartEmailSignup emails a verification code for the email-signup flow. Rides a
// stricter dedicated limiter (see cmd/api/main.go) so it can't be used to bomb
// arbitrary inboxes. Always sends to whatever is given (no account exists yet),
// so there is no enumeration signal here.
func (h *Handler) StartEmailSignup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if !looksLikeEmail(email) || isUnverifiableEmail(email) {
		writeError(w, http.StatusBadRequest, "enter a valid email address")
		return
	}
	if err := h.startEmailOTP(r.Context(), email, emailOTPPurposeSignup); err != nil {
		writeError(w, http.StatusInternalServerError, "could not send verification code")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}

// VerifyEmailSignup checks the signup code and stamps verified_at so the
// subsequent Register call (which carries the chosen password) can create the
// account with email_verified=true.
func (h *Handler) VerifyEmailSignup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
		Code  string `json:"code"`
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
	if ok, status, msg := h.checkEmailOTP(r.Context(), email, emailOTPPurposeSignup, code); !ok {
		writeError(w, status, msg)
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE email_otp SET verified_at = NOW() WHERE email = $1 AND purpose = $2`,
		email, emailOTPPurposeSignup,
	); err != nil {
		writeError(w, http.StatusInternalServerError, "could not verify email")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "verified"})
}

// ── Post-auth add / verify email (authenticated) ─────────────────────────────

// StartEmailChange emails a code to a real inbox the authenticated user wants to
// attach. Used by the phone-first and Apple onboarding flows. Rejects the
// synthesized phone placeholder and Apple private-relay forwarders — the whole
// point is to capture a genuinely reachable address.
func (h *Handler) StartEmailChange(w http.ResponseWriter, r *http.Request) {
	if _, err := getUserFromContext(r); err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req struct {
		Email string `json:"email"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if !looksLikeEmail(email) || isUnverifiableEmail(email) {
		writeError(w, http.StatusBadRequest, "enter a valid email address")
		return
	}
	if err := h.startEmailOTP(r.Context(), email, emailOTPPurposeAddEmail); err != nil {
		writeError(w, http.StatusInternalServerError, "could not send verification code")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}

// VerifyEmailChange verifies the code and writes the email onto the
// authenticated user with email_verified=true, rejecting an address already
// claimed by another account in the same (role, vertical) — same collision
// handling as VerifyPhoneChange.
func (h *Handler) VerifyEmailChange(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	code := strings.TrimSpace(req.Code)
	if !looksLikeEmail(email) || code == "" {
		writeError(w, http.StatusBadRequest, "email and code are required")
		return
	}
	if ok, status, msg := h.checkEmailOTP(r.Context(), email, emailOTPPurposeAddEmail, code); !ok {
		writeError(w, status, msg)
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET email = $1, email_verified = true, updated_at = NOW() WHERE id = $2`,
		email, user["user_id"],
	); err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			writeError(w, http.StatusConflict, "that email is already linked to another account")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to update email")
		return
	}
	h.clearEmailOTP(r.Context(), email, emailOTPPurposeAddEmail)
	writeJSON(w, http.StatusOK, map[string]string{"status": "email verified"})
}

// looksLikeEmail is a deliberately lightweight sanity check (real validation is
// that the OTP gets delivered and entered). Requires a single-ish "@", a dot in
// the domain, and a sane length.
func looksLikeEmail(s string) bool {
	at := strings.IndexByte(s, '@')
	if at <= 0 || at >= len(s)-1 || len(s) > 254 {
		return false
	}
	if strings.ContainsRune(s, ' ') {
		return false
	}
	domain := s[at+1:]
	dot := strings.LastIndexByte(domain, '.')
	return dot > 0 && dot < len(domain)-1
}

// isUnverifiableEmail rejects addresses we can't (or shouldn't) treat as a real
// reachable inbox: the phone-OTP placeholder we synthesize, and Apple's private
// relay forwarder (the consumer must supply a real email instead).
func isUnverifiableEmail(s string) bool {
	return strings.HasSuffix(s, "@phone.koshereats.local") ||
		strings.HasSuffix(s, "@privaterelay.appleid.com")
}

// RequireVerifiedMiddleware hard-gates consumer transactions on full
// verification. The JWT (15 min) can't carry the flags because they flip mid-
// session as the user completes onboarding, so this does one cheap lookup. On a
// missing flag it returns 403 with a machine-readable body so every client can
// route into the verification flow. Only consumers are gated — seller/courier/
// admin flows are out of scope and pass through untouched. Mount it with
// r.With(...) on the specific POST routes (CreateOrder, CreatePaymentIntent),
// never on a whole route group, so browsing/listing stays open.
func (h *Handler) RequireVerifiedMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userData, ok := r.Context().Value(userContextKey).(map[string]string)
		if !ok || userData["user_id"] == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if userData["role"] != string(models.RoleConsumer) {
			next.ServeHTTP(w, r)
			return
		}
		var emailVerified, phoneVerified bool
		if err := h.db.Pool.QueryRow(r.Context(),
			`SELECT email_verified, phone_verified FROM users WHERE id = $1`,
			userData["user_id"],
		).Scan(&emailVerified, &phoneVerified); err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if !emailVerified || !phoneVerified {
			writeJSON(w, http.StatusForbidden, map[string]any{
				"error":          "verification_required",
				"email_verified": emailVerified,
				"phone_verified": phoneVerified,
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}
