package handlers

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/koshereats/backend/internal/models"
	"golang.org/x/crypto/bcrypt"
)

// Match Twilio Verify's default validity window so we don't reject a code the
// provider still considers active, especially after resends that reuse the
// same pending verification.
const phoneOTPTTL = 10 * time.Minute

const (
	otpMaxAttempts = 3
	otpLockoutDur  = 10 * time.Minute
)

// OTP brute-force state is stored in the phone_otp_starts table (columns
// failed_attempts, locked_until) so it survives restarts and works across
// horizontally scaled instances.

type PhoneStartRequest struct {
	Phone string `json:"phone"`
}

type PhoneVerifyRequest struct {
	Phone     string          `json:"phone"`
	Code      string          `json:"code"`
	Role      models.UserRole `json:"role,omitempty"`       // consumer | seller | courier (set by caller app)
	Vertical  string          `json:"vertical,omitempty"`   // 'kosher' (default) | 'vegan'
	FirstName string          `json:"first_name,omitempty"` // used only when creating a brand-new user
	LastName  string          `json:"last_name,omitempty"`
	Email     string          `json:"email,omitempty"` // optional — we synthesize if missing
}

// StartPhoneLogin sends an SMS OTP to the given phone via Twilio Verify.
// Intentionally returns 200 even when we don't recognize the number — we
// don't want to leak whether a given phone is registered. New-user creation
// happens in the verify step after the SMS is proven delivered.
func (h *Handler) StartPhoneLogin(w http.ResponseWriter, r *http.Request) {
	var req PhoneStartRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone := normalizePhone(req.Phone)
	if !looksLikeE164(phone) {
		writeError(w, http.StatusBadRequest, "phone must be in E.164 format (+15551234567)")
		return
	}

	// Don't send a new code or reset the counters while the phone is in an active
	// brute-force lockout — otherwise an attacker re-requests to wipe the lockout
	// and keep guessing (and the victim gets spammed with SMS).
	var lockedUntil *time.Time
	_ = h.db.Pool.QueryRow(r.Context(),
		`SELECT locked_until FROM phone_otp_starts WHERE phone = $1`, phone).Scan(&lockedUntil)
	if lockedUntil != nil && time.Now().Before(*lockedUntil) {
		writeError(w, http.StatusTooManyRequests, "too many attempts — try again in a few minutes")
		return
	}

	if err := h.sms.Start(r.Context(), phone); err != nil {
		writeError(w, http.StatusBadGateway, "failed to send verification code")
		return
	}

	// Record the start time and reset brute-force counters (safe — not locked).
	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO phone_otp_starts (phone, started_at, failed_attempts, locked_until)
		 VALUES ($1, NOW(), 0, NULL)
		 ON CONFLICT (phone) DO UPDATE SET started_at = NOW(), failed_attempts = 0, locked_until = NULL`,
		phone); err != nil {
		slog.Error("failed to record OTP start timestamp",
			slog.String("phone", phone), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to start phone login")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}

// VerifyPhoneLogin checks the OTP with Twilio, then either:
//   - Signs in the existing user that owns this (phone, role) pair.
//   - Creates a brand-new user for this (phone, role) pair if none exists.
//
// Identifiers are scoped by role (see migration 019), so the same phone can
// independently belong to a consumer account AND a seller account AND a
// courier account. Each app's auth call carries its own role — the consumer
// app can never sign in as a seller, even with the same phone number.
// verifyPhoneOTP runs the DB-backed brute-force lockout (phone_otp_starts: TTL,
// failed_attempts, locked_until) and then the SMS provider check, clearing the
// row on success. Returns ok plus, when !ok, the HTTP status + client message
// the caller should write. Used by LinkProvider so the phone-link path can't
// bypass the lockout that VerifyPhoneLogin enforces. (VerifyPhoneLogin still has
// the equivalent logic inline; consolidating it is a safe follow-up.)
func (h *Handler) verifyPhoneOTP(ctx context.Context, phone, code string) (ok bool, status int, msg string) {
	var startedAt time.Time
	var failedAttempts int
	var lockedUntil *time.Time
	if err := h.db.Pool.QueryRow(ctx,
		`SELECT started_at, failed_attempts, locked_until FROM phone_otp_starts WHERE phone = $1`, phone,
	).Scan(&startedAt, &failedAttempts, &lockedUntil); err != nil || time.Since(startedAt) > phoneOTPTTL {
		return false, http.StatusUnauthorized, "code expired — request a new one"
	}
	if lockedUntil != nil && time.Now().Before(*lockedUntil) {
		return false, http.StatusTooManyRequests, "too many failed attempts — try again in 10 minutes"
	}
	verified, err := h.sms.Check(ctx, phone, code)
	if err != nil {
		return false, http.StatusBadGateway, "verification failed"
	}
	if !verified {
		if _, uerr := h.db.Pool.Exec(ctx,
			`UPDATE phone_otp_starts
			    SET failed_attempts = failed_attempts + 1,
			        locked_until = CASE WHEN failed_attempts + 1 >= $2 THEN NOW() + $3::interval ELSE locked_until END
			  WHERE phone = $1`,
			phone, otpMaxAttempts, fmt.Sprintf("%d seconds", int(otpLockoutDur.Seconds()))); uerr != nil {
			slog.Error("failed to increment OTP attempt counter",
				slog.String("phone", phone), slog.String("error", uerr.Error()))
		}
		return false, http.StatusUnauthorized, "invalid or expired code"
	}
	if _, derr := h.db.Pool.Exec(ctx, `DELETE FROM phone_otp_starts WHERE phone = $1`, phone); derr != nil {
		slog.Error("failed to clear OTP start row", slog.String("phone", phone), slog.String("error", derr.Error()))
	}
	return true, 0, ""
}

// StartPhoneChange sends an OTP to a NEW phone number for the authenticated
// user. VerifyPhoneChange then verifies it and writes the new phone — the ONLY
// way to change an account's phone, since UpdateProfile no longer writes it
// (an unverified phone write was an account-squat/hijack vector).
func (h *Handler) StartPhoneChange(w http.ResponseWriter, r *http.Request) {
	if _, err := getUserFromContext(r); err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req PhoneStartRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone := normalizePhone(req.Phone)
	if !looksLikeE164(phone) {
		writeError(w, http.StatusBadRequest, "phone must be in E.164 format (+15551234567)")
		return
	}
	if err := h.sms.Start(r.Context(), phone); err != nil {
		writeError(w, http.StatusBadGateway, "failed to send verification code")
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`INSERT INTO phone_otp_starts (phone, started_at, failed_attempts, locked_until)
		 VALUES ($1, NOW(), 0, NULL)
		 ON CONFLICT (phone) DO UPDATE SET started_at = NOW(), failed_attempts = 0, locked_until = NULL`,
		phone); err != nil {
		slog.Error("failed to record OTP start for phone change",
			slog.String("phone", phone), slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, "failed to start phone change")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}

// VerifyPhoneChange verifies the OTP for the new phone (lockout-aware) and sets
// it on the authenticated user's account, rejecting a number already in use.
func (h *Handler) VerifyPhoneChange(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req PhoneVerifyRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone := normalizePhone(req.Phone)
	if !looksLikeE164(phone) || req.Code == "" {
		writeError(w, http.StatusBadRequest, "phone and code are required")
		return
	}
	if ok, status, msg := h.verifyPhoneOTP(r.Context(), phone, req.Code); !ok {
		writeError(w, status, msg)
		return
	}
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE users SET phone = $1, phone_verified = true, updated_at = NOW() WHERE id = $2`,
		phone, user["user_id"]); err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			writeError(w, http.StatusConflict, "that phone number is already linked to another account")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to update phone")
		return
	}
	// Keep the phone auth-provider link in sync so phone-OTP login still resolves
	// the account by its new number (best-effort; the user may have no phone link).
	if _, perr := h.db.Pool.Exec(r.Context(),
		`UPDATE user_auth_providers SET provider_id = $1 WHERE user_id = $2 AND provider = 'phone'`,
		phone, user["user_id"]); perr != nil {
		slog.Warn("phone change: failed to sync auth-provider link",
			slog.String("user_id", user["user_id"]), slog.String("error", perr.Error()))
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "phone updated"})
}

func (h *Handler) VerifyPhoneLogin(w http.ResponseWriter, r *http.Request) {
	var req PhoneVerifyRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone := normalizePhone(req.Phone)
	if !looksLikeE164(phone) || req.Code == "" {
		writeError(w, http.StatusBadRequest, "phone and code are required")
		return
	}

	// SECURITY: clamp the requested role — phone-OTP signup of a fresh (phone,
	// role) pair must never self-assign admin (see allowedSignupRole). req.Role
	// scopes the existing-user lookup and the createPhoneUser INSERT below.
	if clamped, okRole := allowedSignupRole(req.Role); okRole {
		req.Role = clamped
	} else {
		writeError(w, http.StatusBadRequest, "invalid role")
		return
	}
	vertical := normalizeVertical(req.Vertical)

	// Brute-force protection + TTL enforcement, both DB-backed so they
	// survive restarts and work across horizontally scaled instances.
	var startedAt time.Time
	var failedAttempts int
	var lockedUntil *time.Time
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT started_at, failed_attempts, locked_until
		   FROM phone_otp_starts WHERE phone = $1`, phone,
	).Scan(&startedAt, &failedAttempts, &lockedUntil)
	if err != nil || time.Since(startedAt) > phoneOTPTTL {
		writeError(w, http.StatusUnauthorized, "code expired — request a new one")
		return
	}
	if lockedUntil != nil && time.Now().Before(*lockedUntil) {
		writeError(w, http.StatusTooManyRequests, "too many failed attempts — try again in 10 minutes")
		return
	}

	ok, err := h.sms.Check(r.Context(), phone, req.Code)
	if err != nil {
		writeError(w, http.StatusBadGateway, "verification failed")
		return
	}
	if !ok {
		if _, err := h.db.Pool.Exec(r.Context(),
			`UPDATE phone_otp_starts
			   SET failed_attempts = failed_attempts + 1,
			       locked_until = CASE
			           WHEN failed_attempts + 1 >= $2 THEN NOW() + $3::interval
			           ELSE locked_until END
			 WHERE phone = $1`,
			phone, otpMaxAttempts, fmt.Sprintf("%d seconds", int(otpLockoutDur.Seconds()))); err != nil {
			slog.Error("failed to increment OTP attempt counter",
				slog.String("phone", phone), slog.String("error", err.Error()))
		}
		writeError(w, http.StatusUnauthorized, "invalid or expired code")
		return
	}

	// Successful verification — clear the row so the phone can start fresh.
	if _, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM phone_otp_starts WHERE phone = $1`, phone); err != nil {
		slog.Error("failed to clear OTP start row",
			slog.String("phone", phone), slog.String("error", err.Error()))
	}

	// Check user_auth_providers first (handles linked phone numbers that
	// may differ from the user's primary phone field), then fall back to
	// the direct phone column on users. Both lookups scope by vertical so
	// the same phone can hold separate KosherEats and GreenEats accounts.
	var user models.User
	err = h.db.Pool.QueryRow(r.Context(),
		`SELECT u.id, u.email, u.first_name, u.last_name, u.phone, u.role, u.vertical, u.email_verified, u.phone_verified, u.created_at, u.updated_at
		 FROM users u
		 JOIN user_auth_providers uap ON u.id = uap.user_id
		 WHERE uap.provider = 'phone' AND uap.provider_id = $1 AND u.role = $2 AND u.vertical = $3`,
		phone, req.Role, vertical,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName,
		&user.Phone, &user.Role, &user.Vertical, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)

	if err != nil {
		err = h.db.Pool.QueryRow(r.Context(),
			`SELECT id, email, first_name, last_name, phone, role, vertical, email_verified, phone_verified, created_at, updated_at
			   FROM users WHERE phone = $1 AND role = $2 AND vertical = $3`, phone, req.Role, vertical,
		).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName,
			&user.Phone, &user.Role, &user.Vertical, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)
	}

	switch {
	case errors.Is(err, pgx.ErrNoRows):
		// No account exists for this (phone, role, vertical) — create one.
		// A different role or vertical with the same phone is a separate row
		// and does not conflict here.
		user, err = h.createPhoneUser(r, phone, vertical, req)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create user")
			return
		}
	case err != nil:
		writeError(w, http.StatusInternalServerError, "lookup failed")
		return
	}

	// The OTP just proved control of this number — mark the account
	// phone-verified. Covers a brand-new phone signup AND an older account
	// (e.g. an email signup that listed this phone) logging in by phone for the
	// first time. New phone accounts still need to verify a real email next.
	if !user.PhoneVerified {
		if _, err := h.db.Pool.Exec(r.Context(),
			`UPDATE users SET phone_verified = true, updated_at = NOW() WHERE id = $1`, user.ID); err != nil {
			slog.Warn("failed to set phone_verified on phone login",
				slog.String("user_id", user.ID), slog.String("error", err.Error()))
		}
		user.PhoneVerified = true
	}

	// Ensure user_auth_providers has the phone link.
	if _, uapErr := h.db.Pool.Exec(r.Context(),
		`INSERT INTO user_auth_providers (user_id, provider, provider_id)
		 VALUES ($1, 'phone', $2)
		 ON CONFLICT (user_id, provider) DO UPDATE SET provider_id = $2`,
		user.ID, phone,
	); uapErr != nil {
		slog.Warn("failed to upsert user_auth_providers on phone login",
			slog.String("user_id", user.ID), slog.String("error", uapErr.Error()))
	}

	// Ensure a courier_profiles row exists so GetCourierProfile doesn't 404.
	// Status is 'pending_info' — full approval requires KYC via /courier/auth/register.
	if user.Role == models.RoleCourier {
		if _, err := h.db.Pool.Exec(r.Context(),
			`INSERT INTO courier_profiles (user_id, onboarding_status, phone_verified)
			 VALUES ($1, 'pending_info', true)
			 ON CONFLICT (user_id) DO NOTHING`,
			user.ID); err != nil {
			slog.Error("failed to create courier_profiles row on phone login",
				slog.String("user_id", user.ID), slog.String("error", err.Error()))
		}
	}

	token, refresh, err := h.generateTokens(user.ID, string(user.Role), user.Vertical)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}
	writeJSON(w, http.StatusOK, AuthResponse{
		Token:        token,
		RefreshToken: refresh,
		User:         user,
	})
}

// createPhoneUser inserts a fresh account for a verified phone number.
// Email is synthesized when the client didn't supply one so the NOT NULL +
// UNIQUE index on users.email stays happy; the "@phone.koshereats.local"
// suffix makes these accounts easy to spot in the DB and migrate later.
func (h *Handler) createPhoneUser(r *http.Request, phone, vertical string, req PhoneVerifyRequest) (models.User, error) {
	// Lowercase to match Register and the lower(email) lookups elsewhere (login,
	// password reset). Storing mixed case let "Victim@x.com" and "victim@x.com"
	// coexist as distinct rows that both match a lower(email) reset lookup.
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if email == "" {
		email = fmt.Sprintf("%s@phone.koshereats.local", strings.TrimPrefix(phone, "+"))
	}

	firstName := strings.TrimSpace(req.FirstName)
	if firstName == "" {
		firstName = "New"
	}
	lastName := strings.TrimSpace(req.LastName)
	if lastName == "" {
		lastName = "User"
	}

	// Synthetic password hash — phone auth doesn't use it, but the schema
	// requires a non-null value. SECURITY: must be over CRYPTO-RANDOM bytes, not
	// a function of the phone ("phone-"+phone was derivable from public data and
	// let an attacker password-login as any phone user). The Login guard already
	// blocks password login for auth_provider='phone', but never store a
	// guessable secret.
	randPwd := make([]byte, 32)
	if _, rerr := rand.Read(randPwd); rerr != nil {
		return models.User{}, rerr
	}
	dummyHash, err := bcrypt.GenerateFromPassword(randPwd, bcrypt.DefaultCost)
	if err != nil {
		return models.User{}, err
	}

	role := req.Role
	if role == "" {
		role = models.RoleConsumer
	}
	if vertical == "" {
		vertical = "kosher"
	}

	// phone_verified=true: this row is only created after a successful Twilio
	// OTP. email_verified=false: the email here is either client-supplied
	// (unverified) or the synthesized @phone.koshereats.local placeholder — the
	// consumer must verify a real inbox next via /user/email/start + /verify.
	var user models.User
	err = h.db.Pool.QueryRow(r.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical, auth_provider, email_verified, phone_verified)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, 'phone', false, true)
		 RETURNING id, email, first_name, last_name, phone, role, vertical, email_verified, phone_verified, created_at, updated_at`,
		email, string(dummyHash), firstName, lastName, phone, role, vertical,
	).Scan(&user.ID, &user.Email, &user.FirstName, &user.LastName,
		&user.Phone, &user.Role, &user.Vertical, &user.EmailVerified, &user.PhoneVerified, &user.CreatedAt, &user.UpdatedAt)
	return user, err
}

// normalizePhone strips spaces, parens, and dashes. Clients are expected to
// send E.164 already ("+15551234567"); this just tolerates common formatting.
func normalizePhone(s string) string {
	s = strings.TrimSpace(s)
	var b strings.Builder
	for _, r := range s {
		switch {
		case r == '+':
			b.WriteRune(r)
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		}
	}
	return b.String()
}

func looksLikeE164(s string) bool {
	if len(s) < 8 || len(s) > 16 {
		return false
	}
	if s[0] != '+' {
		return false
	}
	for _, r := range s[1:] {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
