-- Migration 007: Scheduled orders.
-- Consumers can schedule an order for a specific future time ("deliver at 7pm"
-- instead of ASAP). Orders with scheduled_for in the future sit in status
-- 'scheduled' and a background sweeper promotes them to 'pending' (visible
-- to the seller) a short time before the requested delivery window so the
-- kitchen has time to prepare.

-- Allow 'scheduled' as a valid order status.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('scheduled', 'pending', 'accepted', 'preparing', 'ready',
                      'picked_up', 'delivered', 'cancelled', 'rejected'));

ALTER TABLE orders ADD COLUMN scheduled_for TIMESTAMPTZ;

-- Partial index so the sweeper's lookup stays fast as the table grows.
CREATE INDEX idx_orders_scheduled ON orders(scheduled_for)
    WHERE status = 'scheduled';
