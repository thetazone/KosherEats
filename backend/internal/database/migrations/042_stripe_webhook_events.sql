-- 042_stripe_webhook_events: idempotency ledger for incoming Stripe webhooks.
--
-- Stripe guarantees at-least-once delivery: the same event.ID can arrive
-- multiple times (retries on a slow/failed 2xx, or network duplicates). For
-- money-affecting events (disputes, refunds) we must process each exactly
-- once. StripeWebhook INSERTs the event id here ON CONFLICT DO NOTHING right
-- after signature verification; if the insert affects 0 rows the event was
-- already handled and the handler returns 200 immediately without re-running
-- any side effects.
CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    event_id    TEXT PRIMARY KEY,
    type        TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
