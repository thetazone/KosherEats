-- Allow the 'processing' status on courier_payout_queue so a payout can be
-- atomically claimed (status flips pending -> processing) before money moves,
-- closing the lock-before-transfer gap in the polling sweep. Also used by the
-- Temporal payout activities (ReservePayout sets 'processing', MarkComplete
-- sets 'completed'). Additive + idempotent.
ALTER TABLE courier_payout_queue DROP CONSTRAINT IF EXISTS courier_payout_queue_status_check;
ALTER TABLE courier_payout_queue ADD CONSTRAINT courier_payout_queue_status_check
    CHECK (status IN ('pending', 'processing', 'completed', 'failed_permanent'));
