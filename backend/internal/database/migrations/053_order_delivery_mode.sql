-- Per-order delivery routing.
--
-- restaurants.delivery_mode remains the default for new orders, but a seller
-- can now choose self-delivery or Uber Direct for an individual open order
-- without changing the whole restaurant.
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS delivery_mode TEXT
  CHECK (delivery_mode IN ('platform', 'external', 'restaurant'));

UPDATE orders o
   SET delivery_mode = COALESCE(rest.delivery_mode, 'platform')
  FROM restaurants rest
 WHERE o.restaurant_id = rest.id
   AND o.delivery_mode IS NULL;

COMMENT ON COLUMN orders.delivery_mode IS
  'Per-order delivery mode override/default: platform, external, or restaurant. Defaults from restaurants.delivery_mode at order creation.';
