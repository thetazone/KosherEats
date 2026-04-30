ALTER TABLE restaurants
    ADD COLUMN IF NOT EXISTS delivery_mode TEXT NOT NULL DEFAULT 'platform';

COMMENT ON COLUMN restaurants.delivery_mode IS
    'platform = KE couriers then external fallback, external = Uber/DoorDash only, restaurant = own couriers';
