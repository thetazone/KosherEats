-- Tracks when each phone last requested an OTP via /auth/phone/start. Used to
-- enforce a tighter TTL (3 min) on /auth/phone/verify than Twilio's built-in
-- 10-min window. Twilio still considers the code valid for the full 10 min on
-- their side; this table just lets us reject sooner.
CREATE TABLE IF NOT EXISTS phone_otp_starts (
    phone      TEXT PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
