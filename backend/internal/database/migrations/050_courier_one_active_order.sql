-- Enforce "a courier holds at most one active delivery" at the DB level. The
-- app-level busy guard (ClaimOrder pre-check + the NOT EXISTS in the claim CAS,
-- and the mirrored predicate in the auto-dispatcher) is not race-safe: under
-- READ COMMITTED the NOT EXISTS subquery takes no lock on the courier's other
-- orders, so two concurrent claims for DIFFERENT orders by the same courier
-- (double-tap / two devices, or a manual claim racing the auto-dispatcher) can
-- both pass and double-book the courier.
--
-- A partial UNIQUE index on the courier across active statuses makes the second
-- concurrent assignment fail with 23505, which the claim paths catch and turn
-- into "busy". An order leaves the index when it reaches delivered/cancelled,
-- which frees the courier for the next claim.
--
-- Safe to create: verified zero couriers currently hold 2+ active orders in
-- prod, so the unique build can't fail on existing data.
CREATE UNIQUE INDEX IF NOT EXISTS uq_courier_one_active_order
    ON orders (courier_id)
 WHERE courier_id IS NOT NULL AND status IN ('ready', 'picked_up');
