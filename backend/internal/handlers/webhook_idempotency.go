package handlers

import (
	"context"
	"crypto/sha256"
	"encoding/hex"

	"github.com/jackc/pgx/v5"
)

// webhookEventID derives a stable idempotency key for an incoming third-party
// webhook from the raw request body: a SHA-256 hex digest. A replay of a
// captured webhook is byte-identical and therefore collides; two genuinely
// distinct transitions differ in at least their status field and so hash
// differently. This avoids having to assume any provider-specific field is
// unique/stable across re-dispatches (Uber rotates delivery_id per dispatch;
// DoorDash reuses our order id as the external id).
func webhookEventID(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// claimWebhookEvent records (provider, eventID) in the external_webhook_events
// ledger within the given transaction. It returns fresh=true when this is the
// first time we've seen the event (the caller should proceed with its side
// effects and Commit), or fresh=false when the event was already processed by a
// prior delivery (the caller should ACK 200 and run NO side effects).
//
// The claim shares the caller's transaction on purpose: commit it together with
// the order-state mutation so they're atomic. If processing fails and the tx
// rolls back, the claim is released too — so the provider's at-least-once retry
// reprocesses the event instead of it being permanently dropped. See
// migration 052.
func claimWebhookEvent(ctx context.Context, tx pgx.Tx, provider, eventID, eventType string) (fresh bool, err error) {
	ct, err := tx.Exec(ctx,
		`INSERT INTO external_webhook_events (provider, event_id, type)
		 VALUES ($1, $2, $3) ON CONFLICT (provider, event_id) DO NOTHING`,
		provider, eventID, eventType)
	if err != nil {
		return false, err
	}
	return ct.RowsAffected() == 1, nil
}
