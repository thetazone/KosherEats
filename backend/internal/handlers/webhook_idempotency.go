package handlers

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"log/slog"

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

// logProviderScopeMiss explains a 0-row provider webhook update. The mutating
// statements are scoped to `external_provider = '<this provider>'`, so a 0-row
// result is now ambiguous: it can mean the benign "late/duplicate webhook on an
// already-terminal order", OR it can mean the event named an order that belongs
// to the OTHER provider (the cross-provider collision the scoping exists to
// block), OR — the case worth paging about — that our stored provider value
// doesn't look the way this handler expects, in which case the scoping is
// silently dropping legitimate events and orders will strand.
//
// Reading the row back distinguishes them, cheaply and only on the 0-row path.
// Deliberately best-effort: it runs inside the caller's transaction and never
// changes control flow, because a diagnostic must not turn a benign duplicate
// into a 500.
func logProviderScopeMiss(ctx context.Context, tx pgx.Tx, logPrefix, event, orderID, wantProvider string) {
	var status, provider string
	err := tx.QueryRow(ctx,
		`SELECT status, COALESCE(external_provider, '') FROM orders WHERE id = $1`,
		orderID).Scan(&status, &provider)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		slog.Warn(logPrefix+" webhook: no such order, ignoring",
			slog.String("order_id", orderID), slog.String("event", event))
	case err != nil:
		slog.Warn(logPrefix+" webhook: 0 rows updated and readback failed",
			slog.String("order_id", orderID), slog.String("event", event),
			slog.String("error", err.Error()))
	case provider == wantProvider:
		// Same provider, so scoping isn't the cause — the status guard is. Benign.
		slog.Info(logPrefix+" webhook: no-op, order not in an advanceable status",
			slog.String("order_id", orderID), slog.String("event", event),
			slog.String("status", status))
	case provider == "":
		slog.Warn(logPrefix+" webhook: order has no external provider recorded, ignoring",
			slog.String("order_id", orderID), slog.String("event", event),
			slog.String("status", status))
	default:
		// The dangerous one: another provider owns this order.
		slog.Error(logPrefix+" webhook: PROVIDER MISMATCH — event names an order dispatched to a different provider, ignoring",
			slog.String("order_id", orderID), slog.String("event", event),
			slog.String("want_provider", wantProvider),
			slog.String("actual_provider", provider),
			slog.String("status", status))
	}
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
