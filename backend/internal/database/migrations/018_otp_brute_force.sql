-- Persist OTP brute-force counters in the DB so they survive restarts and
-- work across horizontally scaled instances (the old sync.Map was per-process).
ALTER TABLE phone_otp_starts
    ADD COLUMN IF NOT EXISTS failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
