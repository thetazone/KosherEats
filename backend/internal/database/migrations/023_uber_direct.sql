ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS external_delivery_id  TEXT,
  ADD COLUMN IF NOT EXISTS external_provider     TEXT,
  ADD COLUMN IF NOT EXISTS external_tracking_url TEXT;

CREATE INDEX IF NOT EXISTS idx_orders_external_delivery
  ON orders (external_delivery_id) WHERE external_delivery_id IS NOT NULL;
