-- Email password-reset: a short-lived 6-digit code (bcrypt-hashed, never stored
-- in plaintext) the user enters in the app to set a new password. One active
-- code per user; cleared on use or when a new one is requested.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS reset_code_hash       TEXT,
    ADD COLUMN IF NOT EXISTS reset_code_expires_at TIMESTAMPTZ;
