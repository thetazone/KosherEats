-- Persist + expose the deal discount on orders as discount_cents so receipts
-- reconcile: subtotal - discount + delivery + service + tax + tip == total.
-- Migration 027 already snapshots the same value in discount_amount; this adds
-- the canonical discount_cents column the API now reads/writes and backfills it
-- from discount_amount so historical orders carry the discount too.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_cents INTEGER NOT NULL DEFAULT 0;

-- Backfill from the older discount_amount snapshot for orders created before
-- this column existed. No-op for rows already at 0.
UPDATE orders SET discount_cents = discount_amount
 WHERE discount_cents = 0 AND discount_amount <> 0;
