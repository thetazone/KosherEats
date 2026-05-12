-- Per-restaurant POS integration linkage. Each row is one connected provider
-- (clover today; square/toast later). Access + refresh tokens are stored
-- AES-GCM encrypted in bytea; decryption key comes from POS_ENCRYPTION_KEY.
-- When the seller's "Disconnect" button is hit we soft-delete by setting
-- is_active=false rather than dropping the row, so the OAuth audit trail
-- (created_at, last_used_at) survives a reconnect.
CREATE TABLE IF NOT EXISTS restaurant_pos_integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id   UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL CHECK (provider IN ('clover','square','toast')),
    merchant_id     TEXT NOT NULL,
    access_token    BYTEA NOT NULL,
    refresh_token   BYTEA,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ
);

-- Only one active integration per (restaurant, provider). Re-OAuthing the
-- same provider should update the existing row, not create a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS uniq_restaurant_provider_active
    ON restaurant_pos_integrations (restaurant_id, provider)
    WHERE is_active;

CREATE INDEX IF NOT EXISTS idx_pos_integrations_restaurant
    ON restaurant_pos_integrations (restaurant_id);
