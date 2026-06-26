-- 052_external_webhook_events: idempotency ledger for incoming third-party
-- courier/background-check webhooks (Uber Direct, DoorDash Drive, Checkr).
--
-- These endpoints are public and drive money + order state (a forged-or-replayed
-- 'canceled' event clears an order's provider linkage and re-arms auto-dispatch,
-- which fires a brand-new, real, *billed* CreateDelivery — a single captured
-- valid webhook could be replayed to burn money on unlimited re-dispatches).
-- Signature verification stops anonymous forgery, but not replay of a captured
-- valid body. This is the courier-side analogue of stripe_webhook_events (042).
--
-- The handler claims (provider, event_id) inside the SAME transaction as its
-- order-state mutation: if the insert affects 0 rows the event was already
-- processed → ACK 200 with no side effects; if processing then fails the tx
-- rolls back (releasing the claim) so the provider's retry reprocesses rather
-- than permanently dropping the event. event_id is a SHA-256 of the raw request
-- body — a replay is byte-identical (same hash → deduped), while two genuinely
-- distinct transitions differ in at least their status field (different hash →
-- both processed).
CREATE TABLE IF NOT EXISTS external_webhook_events (
    provider    TEXT        NOT NULL,
    event_id    TEXT        NOT NULL,
    type        TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider, event_id)
);
