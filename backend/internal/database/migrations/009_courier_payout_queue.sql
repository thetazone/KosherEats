-- KosherEats Database Schema
-- Migration 009: Courier payout retry queue
--
-- Before this, DeliverOrder fired a Stripe Connect transfer in a
-- fire-and-forget goroutine. If that transfer failed (network error,
-- Stripe outage, invalid connect account), the error was logged and
-- dropped — the courier silently missed their payout.
--
-- This table is a durable record of every payout that needs to happen.
-- The delivery handler enqueues one row per delivery. The background
-- scheduler's payout sweep picks up pending rows due for retry, attempts
-- the Stripe transfer, and either marks the row completed or schedules
-- another retry with exponential backoff. After enough failures the row
-- is marked failed_permanent for manual admin review.

CREATE TABLE courier_payout_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- One queue row per delivered order. UNIQUE so re-enqueuing is a no-op
    -- if the handler somehow retries (ON CONFLICT DO NOTHING in the INSERT).
    order_id UUID UNIQUE NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    courier_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Snapshotted at enqueue time. If the courier later updates their
    -- Stripe Connect account, in-flight payouts still target the account
    -- that was bound to this specific delivery.
    stripe_connect_id VARCHAR(255) NOT NULL,
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),

    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'completed', 'failed_permanent')),

    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',

    -- The sweep picks up rows where status='pending' AND next_retry_at <= NOW().
    -- Set to NOW() on insert so the first attempt runs on the next tick.
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Hot path: the sweep's "what's due for retry" query.
CREATE INDEX idx_courier_payout_queue_due
    ON courier_payout_queue(next_retry_at)
    WHERE status = 'pending';

-- Admin visibility: payouts that exhausted all retries and need a human.
CREATE INDEX idx_courier_payout_queue_failed
    ON courier_payout_queue(created_at DESC)
    WHERE status = 'failed_permanent';
