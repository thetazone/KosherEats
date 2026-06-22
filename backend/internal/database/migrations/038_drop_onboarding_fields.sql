-- Drop orphan onboarding columns from a superseded migration.
--
-- An earlier branch added these via a since-removed `026_onboarding_fields.sql`.
-- The migrations were later renumbered and that file no longer exists, so these
-- columns survive only on environments deployed from the old numbering (prod/Fly)
-- and are referenced by no current code. Remove them so every environment
-- converges on the schema the current migrations actually describe.
--
-- IF EXISTS keeps this a no-op on databases that never had the orphan columns
-- (e.g. local dev, which was built from the current numbering).
ALTER TABLE restaurants
    DROP COLUMN IF EXISTS dba_name,
    DROP COLUMN IF EXISTS owner_legal_name,
    DROP COLUMN IF EXISTS operating_hours,
    DROP COLUMN IF EXISTS is_approved,
    DROP COLUMN IF EXISTS kosher_cert_image_url;
