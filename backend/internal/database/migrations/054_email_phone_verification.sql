-- Verified-onboarding state. We now require new consumer accounts to confirm
-- BOTH a real phone (Twilio Verify OTP) and a real email (emailed OTP) before
-- they can transact. These two flags are the source of truth the API gate and
-- all three apps read to decide whether onboarding is complete.
--
-- Phone OTP already existed; email OTP is new (see email_otp below). The flags
-- are set explicitly by each auth path (phone verify, social login, register,
-- and the post-auth add-email / change-phone endpoints).
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT false;

-- Grandfather every existing account: only accounts created AFTER this ships go
-- through the new OTP onboarding, so the live user base is never blocked at
-- checkout. This runs exactly once (the migration runner records applied files
-- and never re-applies), so it can't later clobber a user who un-verifies.
UPDATE users SET email_verified = true, phone_verified = true;

-- Email one-time-code store, mirroring the password-reset hardening on the
-- users table (bcrypt-hashed code, short TTL, attempt cap → burn). Keyed by
-- (email, purpose) so a single email can have an independent signup code and
-- add-email code in flight:
--   'signup'    — pre-register verification; verified_at is the proof window
--                 that /auth/register checks before creating the account.
--   'add_email' — authenticated, used by the phone/Apple flows to attach and
--                 verify a real inbox onto an existing account.
CREATE TABLE IF NOT EXISTS email_otp (
    email       TEXT NOT NULL,
    purpose     TEXT NOT NULL CHECK (purpose IN ('signup', 'add_email')),
    code_hash   TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    verified_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (email, purpose)
);
