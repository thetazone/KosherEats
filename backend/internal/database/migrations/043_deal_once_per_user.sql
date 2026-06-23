-- Enforce "one use of a deal per customer" atomically. The application-level
-- EXISTS check in resolveDealDiscount (deals.go) runs on a separate connection
-- under READ COMMITTED, so two concurrent CreateOrder calls can both see
-- used=false and each redeem the same single-use deal. A partial unique index
-- makes the second insert fail at the DB, closing the race and backing the
-- per-customer limit with a real constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_user_deal_active
    ON orders (user_id, applied_deal_id)
    WHERE applied_deal_id IS NOT NULL
      AND status NOT IN ('rejected', 'cancelled');
