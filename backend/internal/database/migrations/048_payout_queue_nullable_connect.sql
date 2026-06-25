-- Allow a courier payout to be QUEUED before the courier has onboarded to Stripe
-- Connect. Previously DeliverOrder skipped the enqueue when stripe_connect_id was
-- empty, silently losing the payout forever. Now the row is queued with a NULL
-- connect id; the payout sweep skips NULL-connect rows, and the account.updated
-- webhook backfills the connect id once the courier finishes onboarding, at which
-- point the sweep pays them. Additive (DROP NOT NULL); can't abort boot.
ALTER TABLE courier_payout_queue ALTER COLUMN stripe_connect_id DROP NOT NULL;
