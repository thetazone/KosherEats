-- Phone login: enforce uniqueness of non-empty phone numbers so the OTP
-- login flow can look a user up by phone. Stored in E.164 ("+15551234567").
-- Pre-existing rows with empty phones are unaffected by the partial index.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone
    ON users(phone) WHERE phone <> '';
