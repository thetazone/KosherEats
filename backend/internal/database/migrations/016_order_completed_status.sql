-- Migration 016: Add 'completed' as a valid order status.
-- Sellers mark pickup orders complete directly (no courier hand-off).
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('scheduled', 'pending', 'accepted', 'preparing', 'ready',
                      'picked_up', 'delivered', 'completed', 'cancelled', 'rejected'));
