-- Adds an optional logo image to restaurants, separate from the existing
-- image_url (which is the hero/cover photo shown on the consumer card).
-- The logo appears as a small badge on the consumer marketplace listing
-- so sellers can use a branded mark distinct from their food photo.
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS logo_url TEXT NOT NULL DEFAULT '';
