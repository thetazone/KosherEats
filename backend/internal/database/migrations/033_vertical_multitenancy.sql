-- Migration 033: Vertical multi-tenancy
--
-- Adds a `vertical` column to users and restaurants so the same backend can
-- serve multiple branded apps (KosherEats kosher-only, GreenEats vegan-only,
-- etc.) while keeping each app's accounts and catalog totally separate.
--
-- After this migration:
--   - Existing rows are backfilled to 'kosher' (the original vertical).
--   - Email uniqueness becomes scoped: (email, role, vertical) is unique, so
--     the same email can register on both apps and gets two distinct user
--     rows (one per vertical).
--   - Phone uniqueness similarly becomes (phone, role, vertical).
--   - Restaurants are scoped by vertical too — KosherEats listings never
--     surface vegan restaurants and vice versa.
--
-- The pre-existing uniqueness is enforced by *indexes* (idx_users_email_role,
-- idx_users_phone_role) added in migration 019, not by table-level UNIQUE
-- constraints. So we drop and recreate the indexes here, not constraints.

-- ── users ─────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN vertical TEXT NOT NULL DEFAULT 'kosher';
ALTER TABLE users ADD CONSTRAINT users_vertical_check
    CHECK (vertical IN ('kosher', 'vegan'));

-- Widen the role-scoped uniqueness indexes to include vertical so the same
-- (email, role) pair can exist independently in KosherEats and GreenEats.
DROP INDEX IF EXISTS idx_users_email_role;
CREATE UNIQUE INDEX idx_users_email_role_vertical ON users (email, role, vertical);

DROP INDEX IF EXISTS idx_users_phone_role;
CREATE UNIQUE INDEX idx_users_phone_role_vertical ON users (phone, role, vertical)
    WHERE phone <> '';

CREATE INDEX idx_users_vertical ON users(vertical);

-- ── restaurants ───────────────────────────────────────────────
ALTER TABLE restaurants ADD COLUMN vertical TEXT NOT NULL DEFAULT 'kosher';
ALTER TABLE restaurants ADD CONSTRAINT restaurants_vertical_check
    CHECK (vertical IN ('kosher', 'vegan'));

CREATE INDEX idx_restaurants_vertical ON restaurants(vertical);
