-- Refund atomicity + reconciliation. CancelOrder / RejectOrder / tryStaleReject
-- used to issue the Stripe refund BEFORE committing the status flip — so if the
-- commit failed after a successful refund, the deferred rollback reverted the
-- flip and left a refunded order still fulfillable (free food). The fix flips +
-- commits FIRST, then refunds; the inverted failure mode (committed-cancel but
-- refund didn't land) is recovered by a reconcile reaper. refunded_at tracks
-- whether the refund (or "no payment, nothing to refund") has settled.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;

-- Poison-pill bound for the reconcile reaper: a PaymentIntent that keeps failing
-- to refund with something OTHER than already-refunded (disputed, unrefundable,
-- permanent transient) would otherwise be retried every tick forever and, since
-- stuck rows are the oldest, would starve the bounded batch. The reaper bumps
-- this on each failed attempt and stops retrying past a cap (the row then needs
-- manual reconciliation; it's logged loudly).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refund_attempts INT NOT NULL DEFAULT 0;

-- Backfill: mark every EXISTING terminal order as already-settled so the new
-- reaper only ever acts on cancellations/rejections that happen AFTER this
-- migration. Without this, historical cancelled/rejected orders (already
-- refunded under the old flow) would look refund-pending and get re-refunded.
UPDATE orders SET refunded_at = COALESCE(updated_at, NOW())
 WHERE status IN ('cancelled', 'rejected') AND refunded_at IS NULL;

-- Reaper lookup: terminal orders whose refund hasn't settled and haven't hit the
-- retry cap. New cancellations enter this partial index (refunded_at NULL,
-- attempts 0) and leave once refunded OR once they exhaust retries.
CREATE INDEX IF NOT EXISTS idx_orders_refund_pending
    ON orders (updated_at)
 WHERE status IN ('cancelled', 'rejected') AND refunded_at IS NULL AND refund_attempts < 10;
