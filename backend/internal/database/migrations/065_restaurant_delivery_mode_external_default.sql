-- New restaurants default to external (Uber Direct) delivery.
--
-- 'platform' meant "KosherEats courier pool first, external fallback". There
-- is no KE courier fleet in production, so a 'platform' restaurant's delivery
-- orders just sat through the escalation timeout before reaching Uber. Flip
-- the column default and move existing 'platform' restaurants that have a
-- pickup phone (Uber Direct refuses a delivery without one — the seller API
-- blocks that same switch for a phoneless restaurant) straight to external.
-- 'restaurant' (self-delivery) rows are untouched, and orders already placed
-- keep the delivery_mode stamped on them at creation.
ALTER TABLE restaurants
    ALTER COLUMN delivery_mode SET DEFAULT 'external';

UPDATE restaurants
   SET delivery_mode = 'external'
 WHERE delivery_mode = 'platform'
   AND regexp_replace(COALESCE(phone, ''), '[^0-9]', '', 'g') ~ '^[0-9]{10,15}$';

COMMENT ON COLUMN restaurants.delivery_mode IS
    'external = Uber/DoorDash (default), restaurant = own couriers, platform = legacy KE courier pool then external fallback';
