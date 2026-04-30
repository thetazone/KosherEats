-- Pickup-vs-delivery support. Until now every order assumed a courier
-- handoff (pending → accepted → preparing → ready → picked_up → delivered).
-- Self-pickup orders skip the courier states entirely:
--   pending → accepted → preparing → ready → completed.
--
-- A new fulfillment_type column tags each row so the dispatcher knows to
-- skip pickup orders, the courier marketplace queries can filter them out,
-- and consumer/seller UI can branch labels accordingly. Defaults to
-- 'delivery' so all existing orders behave exactly as before.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS fulfillment_type TEXT NOT NULL DEFAULT 'delivery'
    CHECK (fulfillment_type IN ('delivery', 'pickup'));

-- Index used by the courier marketplace queries (ListAvailableDeliveries
-- and ListUpcomingDeliveries) which now add `fulfillment_type = 'delivery'`
-- to keep pickup orders out of the courier feeds.
CREATE INDEX IF NOT EXISTS idx_orders_fulfillment_type
    ON orders (fulfillment_type)
    WHERE fulfillment_type <> 'delivery';
