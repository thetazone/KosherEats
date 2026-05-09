-- Link orders to the deal that was redeemed (if any) and snapshot the
-- discount cents that were taken off the subtotal at order time. Both
-- columns are nullable / default 0 so existing inserts keep working.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS applied_deal_id UUID;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_amount INTEGER NOT NULL DEFAULT 0;

DO $$ BEGIN
    ALTER TABLE orders
        ADD CONSTRAINT orders_applied_deal_fk
        FOREIGN KEY (applied_deal_id) REFERENCES deals(id) ON DELETE SET NULL;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_orders_applied_deal ON orders(applied_deal_id) WHERE applied_deal_id IS NOT NULL;
