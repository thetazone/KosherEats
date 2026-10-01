-- Click-to-accept merchant agreement for NEW restaurants.
--
-- merchant_agreements already exists in production but no migration in this
-- repo ever created it, so a fresh database (CI, the migration-chain test, a
-- new environment) never had it. CREATE TABLE IF NOT EXISTS with exactly the
-- production columns: a no-op in prod, the real definition everywhere else.
-- Deliberately no foreign keys and no UNIQUE constraint — the production table
-- may hold rows that would violate either, and a failing migration aborts boot.
-- Idempotent acceptance is enforced in the handler (row lock + existence check).
CREATE TABLE IF NOT EXISTS merchant_agreements (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id     UUID NOT NULL,
    user_id           UUID,
    legal_name        TEXT NOT NULL,
    ip_address        TEXT,
    agreement_version TEXT NOT NULL,
    accepted_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The consumer listing / orderability gate asks "has this restaurant accepted
-- version X?" for every row it returns.
CREATE INDEX IF NOT EXISTS idx_merchant_agreements_restaurant_version
    ON merchant_agreements (restaurant_id, agreement_version, accepted_at DESC);

-- Grandfathering. The owner has personal agreements with every restaurant on
-- the platform today, so they are exempt from the click-to-accept gate; any
-- restaurant created after this migration defaults to NOT exempt and must
-- accept the current agreement before consumers can see or order from it.
--
-- Preview listings (057) are excluded: they are seeded catalog entries for
-- restaurants that have NOT onboarded (owner_id NULL, never orderable) — there
-- is no personal agreement with them, and exempting them would let one go live
-- later without the agreement.
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS agreement_exempt BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE restaurants SET agreement_exempt = TRUE
 WHERE listing_visibility IS DISTINCT FROM 'preview';
