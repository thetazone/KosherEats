-- Live external-courier position for provider-dispatched (Uber Direct) orders.
--
-- Uber Direct's event.courier_update webhook carries the courier's lat/lng
-- every few seconds while a delivery is active. Persisting the latest fix on
-- the order lets the consumer apps draw the courier pin on their own tracking
-- map (polled via GET /orders/{id}) instead of only deep-linking to Uber's
-- tracking page. Last-write per order; a stale event for a superseded
-- delivery is rejected by the handler's external_delivery_id scoping, and
-- external_courier_updated_at lets it drop out-of-order events for the live
-- one. Additive + idempotent.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_courier_lat DOUBLE PRECISION;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_courier_lng DOUBLE PRECISION;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_courier_updated_at TIMESTAMPTZ;
