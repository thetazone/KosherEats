-- Prevent duplicate order charges: one order per Stripe payment intent.
-- Partial index excludes empty strings (pickup/cash orders have no PI).
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_stripe_payment_id
ON orders(stripe_payment_id)
WHERE stripe_payment_id != '';
