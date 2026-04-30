-- Account linking: allows multiple auth providers (Apple, Google, phone) to
-- resolve to the same user. Replaces the single auth_provider/auth_provider_id
-- columns on users for provider lookups during social and phone sign-in.

CREATE TABLE IF NOT EXISTS user_auth_providers (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider    VARCHAR(20) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- One link per provider per user (can't link two Apple IDs to one account).
    UNIQUE(user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_uap_provider_lookup
    ON user_auth_providers (provider, provider_id);

-- Seed from existing social auth users.
INSERT INTO user_auth_providers (user_id, provider, provider_id)
SELECT id, auth_provider, auth_provider_id
FROM users
WHERE auth_provider IN ('apple', 'google') AND auth_provider_id <> ''
ON CONFLICT DO NOTHING;

-- Seed from existing phone auth users.
INSERT INTO user_auth_providers (user_id, provider, provider_id)
SELECT id, 'phone', phone
FROM users
WHERE auth_provider = 'phone' AND phone <> ''
ON CONFLICT DO NOTHING;
