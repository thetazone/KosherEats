-- Preserve courier payout records when a courier deletes their account.
--
-- courier_payout_queue.courier_id was NOT NULL REFERENCES users(id) ON DELETE
-- CASCADE, so DeleteAccount's final `DELETE FROM users` silently destroyed
-- every queue row for that courier — including 'pending'/'processing' rows and
-- rows parked in 'failed_permanent' for a human to reconcile. Money owed just
-- vanished, with no ledger entry left behind. That contradicts the
-- anonymize-don't-delete rule the same handler already applies to orders.
--
-- Switch to ON DELETE SET NULL (mirroring 035's orders.courier_id /
-- restaurants.owner_id fix) so the financial record outlives the user row.
-- DeleteAccount freezes any still-outstanding rows to 'failed_permanent'
-- before deleting the user, so the payout sweep never picks up a row whose
-- courier — and Stripe Connect account — is gone.
--
-- Additive + idempotent (DROP NOT NULL, constraint swap); can't abort boot.
ALTER TABLE courier_payout_queue ALTER COLUMN courier_id DROP NOT NULL;
ALTER TABLE courier_payout_queue DROP CONSTRAINT IF EXISTS courier_payout_queue_courier_id_fkey;
ALTER TABLE courier_payout_queue ADD CONSTRAINT courier_payout_queue_courier_id_fkey
    FOREIGN KEY (courier_id) REFERENCES users(id) ON DELETE SET NULL;
