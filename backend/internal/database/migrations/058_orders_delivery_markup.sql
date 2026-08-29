-- Freezes the KosherEats marketplace markup that an order was PRICED with.
--
-- delivery_fee is stamped on the order at checkout, but the self-delivery
-- payout used to recover the restaurant's share by subtracting the markup read
-- from LIVE config at delivery time (handlers.deliveryMarkupCents). The two
-- only agree while the config has not moved: change the markup (a config value,
-- so a secret edit plus a restart) and every order already placed and charged
-- under the old markup settles against the new one — KE keeps the difference
-- and the seller is short by exactly the delta on each in-flight order.
--
-- provider_fee_cents cannot serve here: it is written by external dispatch and
-- is 0 for self-delivery. So record the markup itself, at checkout.
--
-- NULL means "not stamped": pickup orders (no delivery fee at all) and rows
-- created before this migration, which still fall back to the live tiers.
-- Additive; can't abort boot.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_markup_cents INTEGER;

COMMENT ON COLUMN orders.delivery_markup_cents IS
  'KosherEats marketplace markup included in delivery_fee, frozen at checkout. NULL for pickup and pre-058 orders.';
