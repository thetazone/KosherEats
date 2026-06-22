-- Password-reset brute-force guard: count how many wrong codes have been tried
-- against the current active reset code. ResetPassword increments this on each
-- mismatch and clears the code once the cap (~5) is exceeded, so a stolen email
-- address can't be brute-forced through the 6-digit code space. Reset to 0 each
-- time a fresh code is issued (ForgotPassword) and on a successful reset.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS reset_code_attempts INTEGER NOT NULL DEFAULT 0;
