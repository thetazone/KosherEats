-- SECURITY: one external identity (provider, provider_id) must map to at most
-- one account. The app-level guard in LinkProvider is racy (check-then-insert),
-- so enforce it in the DB. This closes the "one OAuth/phone identity bound to
-- multiple accounts" hole.
--
-- De-dup first: if any identity is already linked to several accounts, keep the
-- EARLIEST binding (created_at, then id as a stable tiebreak) and drop the rest.
-- NOTE: an account that loses its only provider link can no longer sign in via
-- that provider — acceptable for the security fix and rare in practice, but the
-- reason it's a deliberate, reviewed migration. Idempotent: the DELETE is a
-- no-op once unique; IF NOT EXISTS guards the index.
DELETE FROM user_auth_providers a
 USING user_auth_providers b
 WHERE a.provider = b.provider
   AND a.provider_id = b.provider_id
   AND (a.created_at, a.id) > (b.created_at, b.id);

-- Replace the non-unique lookup index with a UNIQUE one (it still serves the
-- (provider, provider_id) lookups social/phone sign-in does).
DROP INDEX IF EXISTS idx_uap_provider_lookup;
CREATE UNIQUE INDEX IF NOT EXISTS uq_uap_provider_identity
    ON user_auth_providers (provider, provider_id);
