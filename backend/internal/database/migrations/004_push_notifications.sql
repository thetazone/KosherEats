-- Migration 004: Push notification device tokens
-- One user can have multiple devices (iOS phone, iPad, Android, etc.).
-- 'app' identifies which of the 3 apps the token belongs to so we don't
-- push a "new delivery available" to a consumer device.

CREATE TABLE device_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    platform VARCHAR(10) NOT NULL CHECK (platform IN ('ios', 'android')),
    app VARCHAR(20) NOT NULL CHECK (app IN ('consumer', 'seller', 'courier')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (token, app)
);

CREATE INDEX idx_device_tokens_user ON device_tokens(user_id);
CREATE INDEX idx_device_tokens_app ON device_tokens(app);
