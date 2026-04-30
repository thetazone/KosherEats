-- Role-scoped uniqueness on email and phone.
--
-- Previously: (email UNIQUE) and (phone UNIQUE WHERE phone <> '') — one user
-- per identifier. That forced a single role per person, which doesn't fit a
-- marketplace where the same human may legitimately be a consumer, a seller,
-- AND a courier (each with their own orders, settings, profile).
--
-- New rule: identifiers are unique *within* a role, not across roles. A given
-- phone or email can exist in up to four rows — one per role (consumer/seller/
-- courier/admin) — but never twice within the same role.
--
-- Existing rows are unaffected: today's UNIQUE constraints already guarantee
-- there are no duplicates that would violate the new (identifier, role) keys.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
DROP INDEX IF EXISTS idx_users_phone;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_role
    ON users (email, role);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone_role
    ON users (phone, role)
    WHERE phone <> '';
