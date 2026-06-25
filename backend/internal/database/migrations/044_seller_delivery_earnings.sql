-- Seller's half of the delivery fee on self-delivered orders.
--
-- When a seller delivers an order with their OWN driver (no KE platform courier
-- and no external/Uber delivery — i.e. courier_id IS NULL AND
-- external_delivery_id IS NULL), KosherEats splits the customer-paid delivery_fee
-- 50/50: this column records the seller's half (floor of delivery_fee/2); KE
-- keeps the remainder (including the odd-cent rounding remainder). It is 0 for
-- orders delivered by a KE courier or by Uber/DoorDash.
--
-- Recorded once, at delivery time, inside the seller's deliver-order status CAS
-- (handlers.SellerDeliverOrder) so a replay can't double-count it. Ledger /
-- reporting only — there is no seller Stripe Connect payout today.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS seller_delivery_earnings INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN orders.seller_delivery_earnings IS
  'Seller half of delivery_fee on self-delivered orders (courier_id IS NULL AND external_delivery_id IS NULL); 0 otherwise. KE keeps the rounding remainder.';
