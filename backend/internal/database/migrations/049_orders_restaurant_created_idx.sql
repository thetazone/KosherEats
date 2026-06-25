-- GetDashboardStats (seller dashboard) aggregates a restaurant's orders for
-- "today" on a 30s poll. With only idx_orders_restaurant(restaurant_id) the
-- planner had to read ALL of a restaurant's lifetime orders to compute one
-- day's numbers, and that cost grows unbounded with order history. The query
-- now prunes with a sargable `created_at >= <NY-midnight-today>`; this composite
-- (restaurant_id, created_at) index lets the planner serve that as a same-day
-- range scan instead of a full per-restaurant scan. Additive; can't abort boot.
CREATE INDEX IF NOT EXISTS idx_orders_restaurant_created ON orders (restaurant_id, created_at);
