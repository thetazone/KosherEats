package handlers

// DB-backed tests for StripeWebhook — the money endpoint with no coverage at
// all before this file. It is the only path that (a) decides whether a
// courier's pending payout is killed, (b) flips a courier's payout_ready flag
// and backfills the Stripe Connect id onto a backlog of queued payouts, and
// (c) must fail closed when its signing secret is missing or the signature
// doesn't verify. Every one of those is irreversible in one direction, so each
// gets a test.
//
// SAFETY: nothing here talks to Stripe. Event bodies are hand-built JSON signed
// locally with an obviously-fake secret using Stripe's documented scheme
// (`t=<unix>,v1=<hex hmac-sha256 of "<t>.<body>">`), which is exactly what
// webhook.ConstructEventWithOptions verifies. The handler only touches the
// shared local Postgres harness (TestMain in integration_test.go).

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

const fakeStripeWebhookSecret = "whsec_fake_test_secret_not_a_real_key"

// withStripeWebhookSecret points the harness config at a fake signing secret,
// restoring the original afterwards. Without a secret the handler fails closed
// (500), which is itself one of the cases under test.
func withStripeWebhookSecret(t *testing.T, secret string) *Handler {
	t.Helper()
	h := harness.h
	orig := h.cfg.StripeWebhookSec
	t.Cleanup(func() { h.cfg.StripeWebhookSec = orig })
	h.cfg.StripeWebhookSec = secret
	return h
}

// ---- request builders ----------------------------------------------------

var stripeEventSeq int64

// uniqueEventID returns a per-call-unique Stripe event id. stripe_webhook_events
// is the idempotency ledger and is never truncated between tests, so two tests
// reusing an id would make the second one look like a replay.
func uniqueEventID() string {
	return fmt.Sprintf("evt_test_%d_%d", time.Now().UnixNano(), atomic.AddInt64(&stripeEventSeq, 1))
}

// stripeEventBody wraps a `data.object` in the Event envelope stripe-go decodes.
func stripeEventBody(eventID, eventType, object string) string {
	return fmt.Sprintf(
		`{"id":%q,"object":"event","api_version":"2020-08-27","type":%q,"data":{"object":%s}}`,
		eventID, eventType, object)
}

// signStripe builds the Stripe-Signature header for a body: the scheme is
// documented at stripe.com/docs/webhooks/signatures and is what stripe-go's
// ConstructEvent verifies. Signing locally keeps the test offline.
func signStripe(body, secret string, ts time.Time) string {
	unix := strconv.FormatInt(ts.Unix(), 10)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(unix + "." + body))
	return "t=" + unix + ",v1=" + hex.EncodeToString(mac.Sum(nil))
}

func postStripeWebhook(t *testing.T, h *Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	return postStripeWebhookSigned(t, h, body, signStripe(body, fakeStripeWebhookSecret, time.Now()))
}

func postStripeWebhookSigned(t *testing.T, h *Handler, body, signature string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/stripe", bytes.NewReader([]byte(body)))
	if signature != "" {
		req.Header.Set("Stripe-Signature", signature)
	}
	rec := httptest.NewRecorder()
	h.StripeWebhook(rec, req)
	return rec
}

// ---- fixtures ------------------------------------------------------------

type payoutFixture struct {
	orderID   string
	courierID string
	connectID string
	paymentID string
}

// seedPayoutOrder creates a delivered order tied to a PaymentIntent id, a
// courier with a Connect account, and one courier_payout_queue row in the given
// status. connectID == "" queues the payout with a NULL connect id, which is
// what DeliverOrder does for a courier who hasn't finished Stripe onboarding
// (migration 048) and what account.updated is supposed to backfill.
func seedPayoutOrder(t *testing.T, paymentIntentID, payoutStatus, connectID string) payoutFixture {
	t.Helper()
	e := harness
	ctx := t.Context()

	var consumerID string
	if err := e.h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Stripe', 'Consumer', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("stripeconsumer"), uniquePhone(),
	).Scan(&consumerID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}

	var courierID string
	if err := e.h.db.Pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Stripe', 'Courier', $2, 'courier', 'kosher') RETURNING id`,
		uniqueEmail("stripecourier"), uniquePhone(),
	).Scan(&courierID); err != nil {
		t.Fatalf("seed courier: %v", err)
	}
	if _, err := e.h.db.Pool.Exec(ctx,
		`INSERT INTO courier_profiles (user_id, stripe_connect_id, payout_ready)
		 VALUES ($1, $2, false)`, courierID, connectID); err != nil {
		t.Fatalf("seed courier profile: %v", err)
	}

	var orderID string
	if err := e.h.db.Pool.QueryRow(ctx,
		`INSERT INTO orders (user_id, restaurant_id, courier_id, status, subtotal, delivery_fee,
		   service_fee, tax, total, delivery_address, stripe_payment_id, courier_tip,
		   fulfillment_type, delivery_mode, delivered_at)
		 VALUES ($1, $2, $3, 'delivered', 2599, 699, 0, 234, 3532, '2 Oak St', $4, 500,
		   'delivery', 'platform', NOW()) RETURNING id`,
		consumerID, e.approvedRestID, courierID, paymentIntentID,
	).Scan(&orderID); err != nil {
		t.Fatalf("seed order: %v", err)
	}

	var connectArg any
	if connectID != "" {
		connectArg = connectID
	}
	if _, err := e.h.db.Pool.Exec(ctx,
		`INSERT INTO courier_payout_queue (order_id, courier_id, stripe_connect_id, amount_cents, status)
		 VALUES ($1, $2, $3, 899, $4)`,
		orderID, courierID, connectArg, payoutStatus); err != nil {
		t.Fatalf("seed payout queue row: %v", err)
	}

	t.Cleanup(func() {
		pool := e.h.db.Pool
		_, _ = pool.Exec(ctx, `DELETE FROM courier_payout_queue WHERE order_id = $1`, orderID)
		_, _ = pool.Exec(ctx, `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = pool.Exec(ctx, `DELETE FROM courier_profiles WHERE user_id = $1`, courierID)
		_, _ = pool.Exec(ctx, `DELETE FROM users WHERE id IN ($1, $2)`, courierID, consumerID)
	})

	return payoutFixture{orderID: orderID, courierID: courierID, connectID: connectID, paymentID: paymentIntentID}
}

func payoutStatusOf(t *testing.T, orderID string) string {
	t.Helper()
	var status string
	if err := harness.h.db.Pool.QueryRow(t.Context(),
		`SELECT status FROM courier_payout_queue WHERE order_id = $1`, orderID).Scan(&status); err != nil {
		t.Fatalf("read payout status: %v", err)
	}
	return status
}

func payoutConnectIDOf(t *testing.T, orderID string) string {
	t.Helper()
	var connect string
	if err := harness.h.db.Pool.QueryRow(t.Context(),
		`SELECT COALESCE(stripe_connect_id, '') FROM courier_payout_queue WHERE order_id = $1`,
		orderID).Scan(&connect); err != nil {
		t.Fatalf("read payout connect id: %v", err)
	}
	return connect
}

func ledgerHasEvent(t *testing.T, eventID string) bool {
	t.Helper()
	var exists bool
	if err := harness.h.db.Pool.QueryRow(t.Context(),
		`SELECT EXISTS(SELECT 1 FROM stripe_webhook_events WHERE event_id = $1)`, eventID).Scan(&exists); err != nil {
		t.Fatalf("read stripe ledger: %v", err)
	}
	return exists
}

// uniqueConnectID keeps account.updated tests from matching each other's
// courier_profiles rows (the UPDATE is keyed on stripe_connect_id alone).
func uniqueConnectID() string {
	return fmt.Sprintf("acct_test_%d_%d", time.Now().UnixNano(), atomic.AddInt64(&stripeEventSeq, 1))
}

// ---- authentication ------------------------------------------------------

// StripeWebhook halts courier payouts and flips payout_ready, so an
// unauthenticated caller could stop every courier getting paid. It must fail
// closed on a missing secret, a missing/garbage signature, a tampered body and
// a signature outside Stripe's replay tolerance — and must never record a
// rejected event in the idempotency ledger, which would make the genuine
// retry of that event a silent no-op.
func TestIntegration_StripeWebhookRejectsUnverifiedPayloads(t *testing.T) {
	goodBody := stripeEventBody(uniqueEventID(), "charge.refunded",
		`{"id":"ch_1","payment_intent":"pi_nomatch","amount":1000,"amount_refunded":1000}`)

	tests := []struct {
		name     string
		secret   string
		body     string
		sigFor   string    // body the signature is computed over ("" => use body)
		sigTime  time.Time // zero => now
		noSig    bool
		wantCode int
	}{
		{name: "no signing secret configured", secret: "", body: goodBody, wantCode: http.StatusInternalServerError},
		{name: "no signature header", secret: fakeStripeWebhookSecret, body: goodBody, noSig: true, wantCode: http.StatusBadRequest},
		{
			name: "signature over a different body", secret: fakeStripeWebhookSecret, body: goodBody,
			sigFor:   stripeEventBody(uniqueEventID(), "charge.refunded", `{"id":"ch_other"}`),
			wantCode: http.StatusBadRequest,
		},
		{
			name: "signature outside the replay tolerance", secret: fakeStripeWebhookSecret, body: goodBody,
			sigTime: time.Now().Add(-2 * time.Hour), wantCode: http.StatusBadRequest,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h := withStripeWebhookSecret(t, tc.secret)

			var sig string
			if !tc.noSig {
				signBody := tc.body
				if tc.sigFor != "" {
					signBody = tc.sigFor
				}
				at := tc.sigTime
				if at.IsZero() {
					at = time.Now()
				}
				sig = signStripe(signBody, fakeStripeWebhookSecret, at)
			}

			if code := postStripeWebhookSigned(t, h, tc.body, sig).Code; code != tc.wantCode {
				t.Errorf("status %d, want %d", code, tc.wantCode)
			}
		})
	}

	// A rejected event must leave no ledger trace: recording it would burn the
	// idempotency key, so Stripe's retry of the SAME event would dedupe to a
	// 200 no-op and the dispute/refund would never be processed.
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	forgedID := uniqueEventID()
	forged := stripeEventBody(forgedID, "charge.refunded", `{"id":"ch_forged"}`)
	if code := postStripeWebhookSigned(t, h, forged, "t=1,v1=deadbeef").Code; code != http.StatusBadRequest {
		t.Fatalf("forged signature: status %d, want 400", code)
	}
	if ledgerHasEvent(t, forgedID) {
		t.Error("a signature-rejected event was written to the idempotency ledger; its genuine retry would now be dropped")
	}
}

// ---- idempotency ---------------------------------------------------------

// Stripe delivers at-least-once. A redelivered dispute must not re-run its side
// effects: the halt is scoped to status='pending', so a replay arriving after an
// admin manually re-queued the payout would kill it a second time.
func TestIntegration_StripeWebhookDedupesReplays(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	pi := "pi_dedupe_" + strconv.FormatInt(time.Now().UnixNano(), 10)
	f := seedPayoutOrder(t, pi, "pending", uniqueConnectID())

	eventID := uniqueEventID()
	body := stripeEventBody(eventID, "charge.dispute.created", fmt.Sprintf(
		`{"id":"dp_1","charge":"ch_1","payment_intent":%q,"amount":3532,"currency":"usd","reason":"fraudulent","status":"warning_needs_response"}`, pi))

	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("first delivery: status %d, want 200", code)
	}
	if got := payoutStatusOf(t, f.orderID); got != "failed_permanent" {
		t.Fatalf("after dispute: payout status %q, want failed_permanent", got)
	}

	// An admin re-queues the payout (dispute won, courier still owed). Stripe
	// then redelivers the same event.
	if _, err := h.db.Pool.Exec(t.Context(),
		`UPDATE courier_payout_queue SET status = 'pending' WHERE order_id = $1`, f.orderID); err != nil {
		t.Fatalf("re-queue payout: %v", err)
	}

	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("replay: status %d, want 200", code)
	}
	if got := payoutStatusOf(t, f.orderID); got != "pending" {
		t.Errorf("a replayed dispute re-ran its side effects: payout status %q, want pending", got)
	}
}

// ---- account.updated -----------------------------------------------------

// payout_ready must track BOTH Stripe flags: a courier who submitted details
// but isn't payouts-enabled (or vice versa) is not payable, and marking them
// ready sends transfers that fail.
func TestIntegration_StripeWebhookAccountUpdatedTracksBothFlags(t *testing.T) {
	tests := []struct {
		name             string
		payoutsEnabled   bool
		detailsSubmitted bool
		wantReady        bool
	}{
		{"fully onboarded", true, true, true},
		{"payouts enabled but details missing", true, false, false},
		{"details submitted but payouts disabled", false, true, false},
		{"neither", false, false, false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
			connect := uniqueConnectID()
			f := seedPayoutOrder(t, "pi_acct_"+connect, "pending", connect)

			body := stripeEventBody(uniqueEventID(), "account.updated", fmt.Sprintf(
				`{"id":%q,"payouts_enabled":%t,"details_submitted":%t}`,
				connect, tc.payoutsEnabled, tc.detailsSubmitted))
			if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
				t.Fatalf("status %d, want 200", code)
			}

			var ready bool
			if err := h.db.Pool.QueryRow(t.Context(),
				`SELECT payout_ready FROM courier_profiles WHERE user_id = $1`, f.courierID).Scan(&ready); err != nil {
				t.Fatalf("read payout_ready: %v", err)
			}
			if ready != tc.wantReady {
				t.Errorf("payout_ready = %t, want %t", ready, tc.wantReady)
			}
		})
	}
}

// A courier who delivers before finishing Stripe onboarding gets a payout row
// with a NULL connect id, which the sweep skips. account.updated is the ONLY
// thing that backfills it — without this the courier is never paid for those
// deliveries. Terminal rows must be left alone: stamping a connect id onto a
// completed/failed_permanent row would misrepresent a payout that already
// resolved.
func TestIntegration_StripeWebhookBackfillsQueuedPayoutsOnOnboarding(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	connect := uniqueConnectID()

	pending := seedPayoutOrder(t, "pi_backfill_pending_"+connect, "pending", "")
	completed := seedPayoutOrder(t, "pi_backfill_done_"+connect, "completed", "")

	// Both couriers onboard under the same Connect account id so one event
	// covers both rows; only the pending one may be backfilled.
	for _, courierID := range []string{pending.courierID, completed.courierID} {
		if _, err := h.db.Pool.Exec(t.Context(),
			`UPDATE courier_profiles SET stripe_connect_id = $1 WHERE user_id = $2`,
			connect, courierID); err != nil {
			t.Fatalf("attach connect id: %v", err)
		}
	}

	body := stripeEventBody(uniqueEventID(), "account.updated", fmt.Sprintf(
		`{"id":%q,"payouts_enabled":true,"details_submitted":true}`, connect))
	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d, want 200", code)
	}

	if got := payoutConnectIDOf(t, pending.orderID); got != connect {
		t.Errorf("pending payout connect id = %q, want %q — the courier's backlog can never be paid", got, connect)
	}
	if got := payoutConnectIDOf(t, completed.orderID); got != "" {
		t.Errorf("a completed payout was backfilled with connect id %q; terminal rows must not be rewritten", got)
	}
}

// An account.updated for an account that isn't ready must not backfill: the
// sweep would then try to transfer to an account Stripe will reject.
func TestIntegration_StripeWebhookDoesNotBackfillUnreadyAccounts(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	connect := uniqueConnectID()
	f := seedPayoutOrder(t, "pi_unready_"+connect, "pending", "")
	if _, err := h.db.Pool.Exec(t.Context(),
		`UPDATE courier_profiles SET stripe_connect_id = $1 WHERE user_id = $2`, connect, f.courierID); err != nil {
		t.Fatalf("attach connect id: %v", err)
	}

	body := stripeEventBody(uniqueEventID(), "account.updated", fmt.Sprintf(
		`{"id":%q,"payouts_enabled":false,"details_submitted":true}`, connect))
	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d, want 200", code)
	}
	if got := payoutConnectIDOf(t, f.orderID); got != "" {
		t.Errorf("backfilled connect id %q for an account that cannot receive payouts", got)
	}
}

// ---- refunds and disputes ------------------------------------------------

// The customer's money is going back, so the courier must not be paid out of a
// charge that no longer exists — but only a still-PENDING queue row may be
// stopped. 'processing' is mid-transfer and 'completed' has already moved money;
// flipping either to failed_permanent would misreport a transfer that happened
// (and, for 'processing', race the sweep that owns the row).
func TestIntegration_StripeWebhookHaltsOnlyPendingPayouts(t *testing.T) {
	tests := []struct {
		name       string
		eventType  string
		object     string
		startState string
		wantState  string
	}{
		{
			name: "full refund halts a pending payout", eventType: "charge.refunded",
			object:     `{"id":"ch_r1","payment_intent":%q,"amount":3532,"amount_refunded":3532}`,
			startState: "pending", wantState: "failed_permanent",
		},
		{
			name: "dispute halts a pending payout", eventType: "charge.dispute.created",
			object:     `{"id":"dp_r1","charge":"ch_r1","payment_intent":%q,"amount":3532,"currency":"usd","reason":"fraudulent","status":"needs_response"}`,
			startState: "pending", wantState: "failed_permanent",
		},
		{
			name: "full refund leaves an in-flight transfer alone", eventType: "charge.refunded",
			object:     `{"id":"ch_r2","payment_intent":%q,"amount":3532,"amount_refunded":3532}`,
			startState: "processing", wantState: "processing",
		},
		{
			name: "full refund leaves a completed transfer alone", eventType: "charge.refunded",
			object:     `{"id":"ch_r3","payment_intent":%q,"amount":3532,"amount_refunded":3532}`,
			startState: "completed", wantState: "completed",
		},
		{
			name: "dispute leaves a completed transfer alone", eventType: "charge.dispute.created",
			object:     `{"id":"dp_r2","charge":"ch_r3","payment_intent":%q,"amount":3532,"currency":"usd","reason":"duplicate","status":"needs_response"}`,
			startState: "completed", wantState: "completed",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
			pi := fmt.Sprintf("pi_halt_%d", time.Now().UnixNano())
			f := seedPayoutOrder(t, pi, tc.startState, uniqueConnectID())

			body := stripeEventBody(uniqueEventID(), tc.eventType, fmt.Sprintf(tc.object, pi))
			if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
				t.Fatalf("status %d, want 200", code)
			}
			if got := payoutStatusOf(t, f.orderID); got != tc.wantState {
				t.Errorf("payout status %q, want %q", got, tc.wantState)
			}
		})
	}
}

// A partial refund — a goodwill credit for a missing side, a price adjustment —
// still fires charge.refunded. The courier delivered the food and is still owed
// their fee, and failed_permanent is TERMINAL: payout.go refuses to resurrect
// such a row, and the sweep never retries it, so the courier is simply never
// paid. Only a refund of the FULL charge means the delivery isn't being paid for.
func TestIntegration_StripeWebhookPartialRefundKeepsCourierPayout(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	pi := fmt.Sprintf("pi_partial_%d", time.Now().UnixNano())
	f := seedPayoutOrder(t, pi, "pending", uniqueConnectID())

	// $5.00 goodwill credit against a $35.32 charge.
	body := stripeEventBody(uniqueEventID(), "charge.refunded", fmt.Sprintf(
		`{"id":"ch_partial","payment_intent":%q,"amount":3532,"amount_refunded":500}`, pi))
	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d, want 200", code)
	}

	if got := payoutStatusOf(t, f.orderID); got != "pending" {
		t.Errorf("a $5 partial refund on a $35.32 charge left the payout %q; "+
			"the courier who completed this delivery is now permanently unpaid", got)
	}
}

// A refund whose charge object carries no usable amount must still halt: we
// cannot tell a partial from a full refund, and paying out of a charge that may
// be fully reversed is the more expensive mistake to make silently.
func TestIntegration_StripeWebhookRefundWithoutAmountStillHalts(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	pi := fmt.Sprintf("pi_noamount_%d", time.Now().UnixNano())
	f := seedPayoutOrder(t, pi, "pending", uniqueConnectID())

	body := stripeEventBody(uniqueEventID(), "charge.refunded", fmt.Sprintf(
		`{"id":"ch_noamount","payment_intent":%q,"amount_refunded":3532}`, pi))
	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d, want 200", code)
	}
	if got := payoutStatusOf(t, f.orderID); got != "failed_permanent" {
		t.Errorf("payout status %q, want failed_permanent", got)
	}
}

// The halt is keyed on the order the PaymentIntent resolves to. A refund must
// never reach across to another order's courier.
func TestIntegration_StripeWebhookHaltIsScopedToTheRefundedOrder(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	refundedPI := fmt.Sprintf("pi_scoped_a_%d", time.Now().UnixNano())
	refunded := seedPayoutOrder(t, refundedPI, "pending", uniqueConnectID())
	bystander := seedPayoutOrder(t, fmt.Sprintf("pi_scoped_b_%d", time.Now().UnixNano()), "pending", uniqueConnectID())

	body := stripeEventBody(uniqueEventID(), "charge.refunded", fmt.Sprintf(
		`{"id":"ch_scoped","payment_intent":%q,"amount":3532,"amount_refunded":3532}`, refundedPI))
	if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d, want 200", code)
	}

	if got := payoutStatusOf(t, refunded.orderID); got != "failed_permanent" {
		t.Errorf("refunded order payout status %q, want failed_permanent", got)
	}
	if got := payoutStatusOf(t, bystander.orderID); got != "pending" {
		t.Errorf("an unrelated order's payout was halted: status %q, want pending", got)
	}
}

// A refund/dispute for a PaymentIntent we don't know (a charge made outside
// this system, or an order deleted since) must ACK rather than 500 — Stripe
// retries 5xx, and an unmatched PI can never start matching.
func TestIntegration_StripeWebhookUnknownPaymentIntentAcks(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)

	for _, body := range []string{
		stripeEventBody(uniqueEventID(), "charge.refunded",
			`{"id":"ch_unknown","payment_intent":"pi_does_not_exist","amount":100,"amount_refunded":100}`),
		stripeEventBody(uniqueEventID(), "charge.refunded",
			`{"id":"ch_nopi","amount":100,"amount_refunded":100}`),
		stripeEventBody(uniqueEventID(), "charge.dispute.created",
			`{"id":"dp_unknown","charge":"ch_x","payment_intent":"pi_does_not_exist","amount":100,"currency":"usd","reason":"fraudulent","status":"needs_response"}`),
	} {
		if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
			t.Errorf("status %d, want 200 for %s", code, body)
		}
	}
}

// Everything Stripe sends that we don't act on must ACK and be recorded, so the
// endpoint never accumulates retries for events it will never handle.
func TestIntegration_StripeWebhookUnhandledEventsAreRecordedNoOps(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	pi := fmt.Sprintf("pi_unhandled_%d", time.Now().UnixNano())
	f := seedPayoutOrder(t, pi, "pending", uniqueConnectID())

	for _, eventType := range []string{
		"payment_intent.succeeded", "payment_intent.payment_failed",
		"charge.succeeded", "customer.created", "payout.paid",
	} {
		eventID := uniqueEventID()
		body := stripeEventBody(eventID, eventType, fmt.Sprintf(`{"id":"obj_1","payment_intent":%q}`, pi))
		if code := postStripeWebhook(t, h, body).Code; code != http.StatusOK {
			t.Errorf("%s: status %d, want 200", eventType, code)
		}
		if !ledgerHasEvent(t, eventID) {
			t.Errorf("%s: event was not recorded in the idempotency ledger", eventType)
		}
	}
	if got := payoutStatusOf(t, f.orderID); got != "pending" {
		t.Errorf("an unhandled event changed the payout status to %q", got)
	}
}

// A malformed data.object on a handled event type is unparseable forever, so it
// must be rejected rather than retried — but it must also leave no ledger row,
// or a corrected redelivery of the same event id would be deduped away.
func TestIntegration_StripeWebhookMalformedObjectIsRejectedWithoutClaimingTheEvent(t *testing.T) {
	h := withStripeWebhookSecret(t, fakeStripeWebhookSecret)
	eventID := uniqueEventID()
	body := stripeEventBody(eventID, "account.updated", `{"id":123,"payouts_enabled":"yes"}`)

	if code := postStripeWebhook(t, h, body).Code; code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400", code)
	}
	if ledgerHasEvent(t, eventID) {
		t.Error("a rejected malformed event claimed its idempotency key; a corrected redelivery would be dropped")
	}
}

// ---- alert bodies --------------------------------------------------------

// The dispute/refund alert emails are the only human-visible signal that money
// moved. They must always carry the identifiers an operator needs to find the
// charge in the Stripe dashboard, and must degrade cleanly when the PaymentIntent
// resolved to no order of ours.
func TestStripeAlertBodiesCarryTheIdentifiers(t *testing.T) {
	tests := []struct {
		name    string
		body    string
		want    []string
		notWant []string
	}{
		{
			name: "dispute with a matched order",
			body: disputeAlertBody("dp_1", "ch_1", "pi_1", "ord-9", 3532, "fraudulent"),
			want: []string{"dp_1", "ch_1", "pi_1", "ord-9", "3532", "fraudulent", "evidence"},
		},
		{
			name:    "dispute with no matched order",
			body:    disputeAlertBody("dp_2", "ch_2", "pi_2", "", 100, "duplicate"),
			want:    []string{"dp_2", "ch_2", "pi_2", "100", "duplicate"},
			notWant: []string{"Order: "},
		},
		{
			name: "refund with a matched order",
			body: refundAlertBody("ch_3", "pi_3", "ord-7", 500),
			want: []string{"ch_3", "pi_3", "ord-7", "500"},
		},
		{
			name:    "refund with no matched order",
			body:    refundAlertBody("ch_4", "pi_4", "", 500),
			want:    []string{"ch_4", "pi_4", "500"},
			notWant: []string{"Order: "},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			for _, want := range tc.want {
				if !bytes.Contains([]byte(tc.body), []byte(want)) {
					t.Errorf("alert body is missing %q:\n%s", want, tc.body)
				}
			}
			for _, notWant := range tc.notWant {
				if bytes.Contains([]byte(tc.body), []byte(notWant)) {
					t.Errorf("alert body unexpectedly contains %q:\n%s", notWant, tc.body)
				}
			}
		})
	}
}
