-- Restaurant approval workflow. New seller signups land as pending + inactive
-- and the platform admin reviews them via emailed magic links. Existing rows
-- are backfilled to 'approved' so the live marketplace doesn't go dark when
-- this migration runs.
ALTER TABLE restaurants
    ADD COLUMN IF NOT EXISTS approval_status TEXT NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS approval_notes TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS approval_token TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

-- Backfill: anything that pre-dates the approval gate counts as already
-- approved. Without this, every existing restaurant would disappear from the
-- consumer marketplace the instant this migration deploys.
UPDATE restaurants
   SET approval_status = 'approved'
 WHERE approval_status = 'pending';
