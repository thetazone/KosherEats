-- Persist the Stripe Customer id on users so saved payment methods survive
-- between orders. Previously we created a fresh Customer on every checkout,
-- which meant the Profile → Payment Methods screen had nothing to manage
-- (no customer = no saved cards to list).
--
-- Nullable because existing rows (pre-migration) have no customer yet; the
-- payments handler upserts it on next checkout or on first customer-bundle
-- request from the profile screen.

ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_stripe_customer ON users(stripe_customer_id)
    WHERE stripe_customer_id IS NOT NULL;
