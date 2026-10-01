-- Restaurant (seller) payouts: a Stripe Connect account per restaurant and a
-- per-order payout ledger.
--
-- Until now the platform paid couriers (courier_payout_queue, 009) but paid
-- restaurants nothing. Customer payments are charged to the KosherEats platform
-- account (separate charges & transfers — KE is merchant of record), so every
-- restaurant's share has to be moved to its own connected account by a Stripe
-- Transfer.
--
-- One ledger line per order that reaches its SUCCESSFUL terminal state
-- (delivered, or completed for pickup), unique on order_id so every terminal
-- path can insert idempotently. The fee model (internal/restaurantpayout):
--   courier_delivery — KE fee 10% of the food subtotal (NYC "delivery fee").
--   pickup           — KE fee 5% of the food subtotal + the order's actual
--                      Stripe processing fee (pass-through).
--   self_delivery    — same as pickup; the restaurant also keeps its own
--                      delivery fee and the tip.
-- Sales tax collected is passed through 100% (the restaurant is the vendor).
--
-- The line is a durable work item as well as a statement row, mirroring
-- courier_payout_queue: the scheduler sweep resolves the processing fee from
-- Stripe, transfers the net (source_transaction = the order's charge) once the
-- restaurant's account is ready AND RESTAURANT_PAYOUTS_ENABLED=true, retries
-- with backoff, and reverses / voids on refunds.
--
-- Additive + idempotent; can't abort boot.

ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS stripe_connect_id TEXT;
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS payout_ready BOOLEAN NOT NULL DEFAULT FALSE;

-- account.updated webhooks look restaurants up by connect id; one account per
-- restaurant. Safe as UNIQUE: the column is new, so no existing data can clash.
CREATE UNIQUE INDEX IF NOT EXISTS uq_restaurants_stripe_connect_id
    ON restaurants (stripe_connect_id)
    WHERE stripe_connect_id IS NOT NULL AND stripe_connect_id <> '';

CREATE TABLE IF NOT EXISTS restaurant_payout_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Exactly one line per order: every terminal-state site inserts with
    -- ON CONFLICT (order_id) DO NOTHING.
    order_id      UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),

    fulfillment TEXT NOT NULL
        CHECK (fulfillment IN ('courier_delivery', 'pickup', 'self_delivery')),

    -- Statement amounts (cents). delivery_fee_cents / tip_cents are what is
    -- CREDITED to the restaurant (non-zero only for self_delivery).
    -- ke_fee_cents is the percentage fee only; processing_fee_cents is stored
    -- for every class but deducted only for pickup / self_delivery.
    food_subtotal_cents  INTEGER NOT NULL,
    sales_tax_cents      INTEGER NOT NULL,
    delivery_fee_cents   INTEGER NOT NULL DEFAULT 0,
    tip_cents            INTEGER NOT NULL DEFAULT 0,
    ke_fee_cents         INTEGER NOT NULL,
    processing_fee_cents INTEGER NOT NULL DEFAULT 0,
    -- FALSE until the sweep has read the charge's balance transaction from
    -- Stripe; net_cents is provisional (no processing deducted) until then and
    -- the line is never transferred while unresolved.
    processing_fee_resolved BOOLEAN NOT NULL DEFAULT FALSE,
    net_cents            INTEGER NOT NULL CHECK (net_cents >= 0),

    -- The charged amount, the denominator for proportional refund reversals.
    order_total_cents INTEGER NOT NULL DEFAULT 0,
    payment_intent_id TEXT NOT NULL DEFAULT '',
    charge_id         TEXT,

    status TEXT NOT NULL DEFAULT 'awaiting_account'
        CHECK (status IN ('awaiting_account', 'pending', 'processing', 'paid',
                          'failed', 'reversed', 'void')),

    -- Snapshotted when the transfer is claimed.
    stripe_connect_id TEXT,
    -- Frozen on the first transfer claim so every retry replays the SAME amount
    -- under the SAME Stripe idempotency key.
    transfer_amount_cents INTEGER,
    transfer_id       TEXT,
    transferred_cents INTEGER NOT NULL DEFAULT 0,

    -- Refund bookkeeping. refunded_cents: cumulative customer refund on the
    -- charge (charge.refunded webhook / charge read). reversed_cents: cumulative
    -- amount clawed back from the restaurant's net (withheld before transfer, or
    -- reversed after). refunds_applied_cents: the refunded_cents value the sweep
    -- last reconciled, so settled lines drop out of the refund scan.
    refunded_cents        INTEGER NOT NULL DEFAULT 0,
    reversed_cents        INTEGER NOT NULL DEFAULT 0,
    refunds_applied_cents INTEGER NOT NULL DEFAULT 0,

    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT NOT NULL DEFAULT '',
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- When the order reached its successful terminal state (statement date).
    completed_at TIMESTAMPTZ NOT NULL,
    paid_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seller statement list (newest first, keyset cursor) and date-range summary.
CREATE INDEX IF NOT EXISTS idx_restaurant_payout_lines_restaurant_completed
    ON restaurant_payout_lines (restaurant_id, completed_at DESC, id DESC);

-- Sweep hot paths.
CREATE INDEX IF NOT EXISTS idx_restaurant_payout_lines_due
    ON restaurant_payout_lines (next_retry_at)
    WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_restaurant_payout_lines_awaiting
    ON restaurant_payout_lines (restaurant_id)
    WHERE status = 'awaiting_account';
CREATE INDEX IF NOT EXISTS idx_restaurant_payout_lines_unresolved_fee
    ON restaurant_payout_lines (created_at)
    WHERE NOT processing_fee_resolved;
CREATE INDEX IF NOT EXISTS idx_restaurant_payout_lines_refund_pending
    ON restaurant_payout_lines (updated_at)
    WHERE refunded_cents > refunds_applied_cents;

-- Self-delivery: the restaurant keeps its FULL own delivery fee (the
-- consumer-paid delivery_fee minus the KosherEats marketplace markup frozen in
-- delivery_markup_cents, which is KE's separate consumer-side line) plus 100%
-- of the tip. 044's comment described the original 50/50 split, which no
-- longer applies.
COMMENT ON COLUMN orders.seller_delivery_earnings IS
  'Self-delivered orders only (courier_id IS NULL AND external_delivery_id IS NULL): the restaurant''s full own delivery fee (delivery_fee - delivery_markup_cents) plus 100% of the tip; 0 otherwise.';
