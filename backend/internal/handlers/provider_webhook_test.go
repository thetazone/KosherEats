package handlers

// DB-backed tests for the three external-courier webhooks (Uber Direct,
// DoorDash Drive, Shipday). These are the endpoints that move an order through
// picked_up → delivered and, on a cancel, decide whether the platform buys a
// SECOND paid courier — so the tests focus on authentication, the poison-pill
// guards, idempotency, and the provider/delivery-id scoping of every mutation.
//
// SAFETY: nothing here touches a provider network. The handlers only read
// request bodies and the local Postgres from the shared harness (TestMain in
// integration_test.go); the provider clients are constructed with obviously
// fake secrets purely so VerifyWebhook has something to compare against.

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/shipday"
	"github.com/koshereats/backend/internal/uberdirect"
)

const (
	fakeUberWebhookSecret     = "fake-uber-webhook-secret"
	fakeDoorDashWebhookSecret = "fake-doordash-webhook-secret"
	fakeShipdayWebhookToken   = "fake-shipday-token"
)

// withProviderClients points the harness handler at provider clients that have
// webhook credentials, restoring the originals afterwards. The clients are
// never used for outbound calls in these tests.
func withProviderClients(t *testing.T) *Handler {
	t.Helper()
	h := harness.h
	origU, origD, origS := h.uber, h.doordash, h.shipday
	t.Cleanup(func() { h.uber, h.doordash, h.shipday = origU, origD, origS })

	h.uber = uberdirect.New(uberdirect.Config{WebhookSec: fakeUberWebhookSecret})
	h.doordash = doordash.New(doordash.Config{WebhookSec: fakeDoorDashWebhookSecret})
	h.shipday = shipday.New(shipday.Config{APIKey: "fake", WebhookToken: fakeShipdayWebhookToken})
	return h
}

// ---- request builders ----------------------------------------------------

func postUberWebhook(t *testing.T, h *Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	mac := hmac.New(sha256.New, []byte(fakeUberWebhookSecret))
	mac.Write([]byte(body))
	req := httptest.NewRequest(http.MethodPost, "/webhooks/uber", bytes.NewReader([]byte(body)))
	req.Header.Set("X-Uber-Signature", hex.EncodeToString(mac.Sum(nil)))
	rec := httptest.NewRecorder()
	h.UberDirectWebhook(rec, req)
	return rec
}

func postDoorDashWebhook(t *testing.T, h *Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/doordash", bytes.NewReader([]byte(body)))
	req.Header.Set("Authorization", "Bearer "+fakeDoorDashWebhookSecret)
	rec := httptest.NewRecorder()
	h.DoorDashWebhook(rec, req)
	return rec
}

func postShipdayWebhook(t *testing.T, h *Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/webhooks/shipday", bytes.NewReader([]byte(body)))
	req.Header.Set("token", fakeShipdayWebhookToken)
	rec := httptest.NewRecorder()
	h.ShipdayWebhook(rec, req)
	return rec
}

// ---- fixtures ------------------------------------------------------------

// uniquePhone returns a per-call-unique E.164 number. users carries a unique
// index on (phone, role, vertical), so every seeded consumer needs its own.
func uniquePhone() string {
	return fmt.Sprintf("+1555%07d", atomic.AddInt64(&phoneSeq, 1)%10000000)
}

var phoneSeq = time.Now().UnixNano() % 1000000

type webhookOrder struct {
	id       string
	consumer string
}

// seedDispatchedOrder creates a paid delivery order already out with a provider.
func seedDispatchedOrder(t *testing.T, status, provider, deliveryID string) webhookOrder {
	t.Helper()
	e := harness
	var consumerID string
	if err := e.h.db.Pool.QueryRow(t.Context(),
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Web', 'Hook', $2, 'consumer', 'kosher') RETURNING id`,
		uniqueEmail("webhook"), uniquePhone(),
	).Scan(&consumerID); err != nil {
		t.Fatalf("seed consumer: %v", err)
	}

	var provArg, delArg any
	if provider != "" {
		provArg = provider
	}
	if deliveryID != "" {
		delArg = deliveryID
	}
	var orderID string
	if err := e.h.db.Pool.QueryRow(t.Context(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, courier_tip, fulfillment_type, delivery_mode,
		   external_provider, external_delivery_id, external_tracking_url)
		 VALUES ($1, $2, $3, 2599, 699, 0, 234, 3532, '2 Oak St', $4, 500, 'delivery', 'external',
		   $5, $6, 'https://track/existing') RETURNING id`,
		consumerID, e.approvedRestID, status, uniqueEmail("pi"), provArg, delArg,
	).Scan(&orderID); err != nil {
		t.Fatalf("seed order: %v", err)
	}
	t.Cleanup(func() {
		_, _ = e.h.db.Pool.Exec(t.Context(), `DELETE FROM orders WHERE id = $1`, orderID)
		_, _ = e.h.db.Pool.Exec(t.Context(), `DELETE FROM users WHERE id = $1`, consumerID)
	})
	return webhookOrder{id: orderID, consumer: consumerID}
}

type webhookOrderState struct {
	status      string
	provider    string
	deliveryID  string
	trackingURL string
	pickedUp    bool
	delivered   bool
}

func readWebhookOrder(t *testing.T, id string) webhookOrderState {
	t.Helper()
	var s webhookOrderState
	if err := harness.h.db.Pool.QueryRow(t.Context(),
		`SELECT status, COALESCE(external_provider,''), COALESCE(external_delivery_id,''),
		        COALESCE(external_tracking_url,''), picked_up_at IS NOT NULL, delivered_at IS NOT NULL
		   FROM orders WHERE id = $1`, id).Scan(
		&s.status, &s.provider, &s.deliveryID, &s.trackingURL, &s.pickedUp, &s.delivered); err != nil {
		t.Fatalf("read order: %v", err)
	}
	return s
}

// clearWebhookLedger drops the idempotency rows so a test can replay bodies.
func clearWebhookLedger(t *testing.T) {
	t.Helper()
	if _, err := harness.h.db.Pool.Exec(t.Context(), `DELETE FROM external_webhook_events`); err != nil {
		t.Fatalf("clear webhook ledger: %v", err)
	}
}

// ---- authentication ------------------------------------------------------

// All three webhooks mutate order state and clear provider linkage, so an
// unauthenticated caller could cancel a live delivery or mark an order
// delivered. Each must fail closed.
func TestIntegration_ProviderWebhooksRejectBadCredentials(t *testing.T) {
	h := withProviderClients(t)
	ord := seedDispatchedOrder(t, "ready", "uber_direct", "del_1")

	uberBody := fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"del_1","data":{"status":"delivered","external_id":%q}}`, ord.id)
	ddBody := fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, ord.id)
	sdBody := fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":1,"order_number":%q}}`, ord.id)

	cases := []struct {
		name       string
		call       func() *httptest.ResponseRecorder
		wantStatus int
	}{
		{"uber: wrong signature", func() *httptest.ResponseRecorder {
			req := httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(uberBody)))
			req.Header.Set("X-Uber-Signature", "deadbeef")
			rec := httptest.NewRecorder()
			h.UberDirectWebhook(rec, req)
			return rec
		}, http.StatusBadRequest},
		{"uber: no signature", func() *httptest.ResponseRecorder {
			rec := httptest.NewRecorder()
			h.UberDirectWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(uberBody))))
			return rec
		}, http.StatusBadRequest},
		{"uber: signature over a DIFFERENT body", func() *httptest.ResponseRecorder {
			mac := hmac.New(sha256.New, []byte(fakeUberWebhookSecret))
			mac.Write([]byte(`{"kind":"event.delivery_status"}`))
			req := httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(uberBody)))
			req.Header.Set("X-Uber-Signature", hex.EncodeToString(mac.Sum(nil)))
			rec := httptest.NewRecorder()
			h.UberDirectWebhook(rec, req)
			return rec
		}, http.StatusBadRequest},
		{"doordash: wrong bearer", func() *httptest.ResponseRecorder {
			req := httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(ddBody)))
			req.Header.Set("Authorization", "Bearer not-the-secret")
			rec := httptest.NewRecorder()
			h.DoorDashWebhook(rec, req)
			return rec
		}, http.StatusUnauthorized},
		{"doordash: no header", func() *httptest.ResponseRecorder {
			rec := httptest.NewRecorder()
			h.DoorDashWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(ddBody))))
			return rec
		}, http.StatusUnauthorized},
		{"shipday: wrong token", func() *httptest.ResponseRecorder {
			req := httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(sdBody)))
			req.Header.Set("token", "nope")
			rec := httptest.NewRecorder()
			h.ShipdayWebhook(rec, req)
			return rec
		}, http.StatusUnauthorized},
		{"shipday: no token", func() *httptest.ResponseRecorder {
			rec := httptest.NewRecorder()
			h.ShipdayWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(sdBody))))
			return rec
		}, http.StatusUnauthorized},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.call().Code; got != tc.wantStatus {
				t.Errorf("status = %d, want %d", got, tc.wantStatus)
			}
			if st := readWebhookOrder(t, ord.id); st.status != "ready" {
				t.Fatalf("an unauthenticated webhook mutated the order to %q", st.status)
			}
		})
	}
}

// ---- poison pills --------------------------------------------------------

// orders.id is a uuid column, so a non-UUID external id makes every query fail
// SQLSTATE 22P02 → 500 → the provider retries forever. Real ids from the
// DoorDash Delivery Simulator and Shipday-dashboard-created orders land here,
// so each must ACK 200 and drop.
func TestIntegration_ProviderWebhooksAckNonUUIDIDs(t *testing.T) {
	h := withProviderClients(t)
	// uuid.Parse accepts urn:uuid: but Postgres does not — the guard has to
	// normalize, not merely validate.
	poison := []string{
		"ke_whtest_20260729213222",
		"not-a-uuid",
		"6ba7b810-9dad-11d1-80b4",
		"urn:uuid:6ba7b810-9dad-11d1-80b4-00c04fd430c8",
		"{6ba7b810-9dad-11d1-80b4-00c04fd430c8}",
	}
	for _, id := range poison {
		t.Run(id, func(t *testing.T) {
			clearWebhookLedger(t)
			if code := postUberWebhook(t, h, fmt.Sprintf(
				`{"kind":"event.delivery_status","delivery_id":"d","data":{"status":"delivered","external_id":%q}}`, id)).Code; code != http.StatusOK {
				t.Errorf("uber: status %d, want 200 (a 500 makes this a retry loop)", code)
			}
			if code := postDoorDashWebhook(t, h, fmt.Sprintf(
				`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, id)).Code; code != http.StatusOK {
				t.Errorf("doordash: status %d, want 200", code)
			}
			if code := postShipdayWebhook(t, h, fmt.Sprintf(
				`{"event":"ORDER_COMPLETED","order":{"id":9,"order_number":%q}}`, id)).Code; code != http.StatusOK {
				t.Errorf("shipday: status %d, want 200", code)
			}
		})
	}
}

// A malformed body can't be fixed by a retry — ACK it rather than 500-looping.
func TestIntegration_ProviderWebhooksAckUnparseableBodies(t *testing.T) {
	h := withProviderClients(t)
	for _, body := range []string{`{"kind":`, `<html>gateway timeout</html>`, ``} {
		clearWebhookLedger(t)
		if code := postUberWebhook(t, h, body).Code; code != http.StatusOK {
			t.Errorf("uber body %q: status %d, want 200", body, code)
		}
		if code := postDoorDashWebhook(t, h, body).Code; code != http.StatusOK {
			t.Errorf("doordash body %q: status %d, want 200", body, code)
		}
		if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
			t.Errorf("shipday body %q: status %d, want 200", body, code)
		}
	}
}

// ---- happy-path lifecycle ------------------------------------------------

// Each provider's pickup and delivered events must advance the order and stamp
// the timestamps analytics and courier-time metrics divide by.
func TestIntegration_ProviderWebhooksAdvanceLifecycle(t *testing.T) {
	h := withProviderClients(t)

	cases := []struct {
		name       string
		provider   string
		deliveryID string
		pickup     func(o webhookOrder) *httptest.ResponseRecorder
		delivered  func(o webhookOrder) *httptest.ResponseRecorder
	}{
		{
			name: "uber_direct", provider: "uber_direct", deliveryID: "del_uber_1",
			pickup: func(o webhookOrder) *httptest.ResponseRecorder {
				return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"del_uber_1","data":{"status":"pickup_complete","external_id":%q}}`, o.id))
			},
			delivered: func(o webhookOrder) *httptest.ResponseRecorder {
				return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"del_uber_1","data":{"status":"delivered","external_id":%q}}`, o.id))
			},
		},
		{
			name: "doordash_drive", provider: "doordash_drive", deliveryID: "unused",
			pickup: func(o webhookOrder) *httptest.ResponseRecorder {
				return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_PICKED_UP"}`, o.id))
			},
			delivered: func(o webhookOrder) *httptest.ResponseRecorder {
				return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, o.id))
			},
		},
		{
			name: "shipday", provider: "shipday", deliveryID: "90210",
			pickup: func(o webhookOrder) *httptest.ResponseRecorder {
				return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_PIKEDUP","order":{"id":90210,"order_number":%q}}`, o.id))
			},
			delivered: func(o webhookOrder) *httptest.ResponseRecorder {
				return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":90210,"order_number":%q}}`, o.id))
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "ready", tc.provider, tc.deliveryID)

			if code := tc.pickup(ord).Code; code != http.StatusOK {
				t.Fatalf("pickup: status %d", code)
			}
			st := readWebhookOrder(t, ord.id)
			if st.status != "picked_up" || !st.pickedUp {
				t.Fatalf("after pickup: %+v, want status picked_up with picked_up_at stamped", st)
			}

			if code := tc.delivered(ord).Code; code != http.StatusOK {
				t.Fatalf("delivered: status %d", code)
			}
			st = readWebhookOrder(t, ord.id)
			if st.status != "delivered" || !st.delivered {
				t.Fatalf("after delivered: %+v, want status delivered with delivered_at stamped", st)
			}
		})
	}
}

// A dropped pickup webhook must not strand an order: 'delivered' is
// authoritative and has to be accepted from any pre-delivered state, leaving a
// plausible picked_up_at behind.
func TestIntegration_DeliveredAcceptedWithoutAPriorPickup(t *testing.T) {
	h := withProviderClients(t)
	for _, tc := range []struct {
		name     string
		provider string
		delID    string
		post     func(o webhookOrder) *httptest.ResponseRecorder
	}{
		{"uber", "uber_direct", "d1", func(o webhookOrder) *httptest.ResponseRecorder {
			return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"d1","data":{"status":"delivered","external_id":%q}}`, o.id))
		}},
		{"doordash", "doordash_drive", "x", func(o webhookOrder) *httptest.ResponseRecorder {
			return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, o.id))
		}},
		{"shipday", "shipday", "77", func(o webhookOrder) *httptest.ResponseRecorder {
			return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":77,"order_number":%q}}`, o.id))
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			// 'accepted' — the order was escalated to a provider before the kitchen
			// finished, and the pickup event never arrived.
			ord := seedDispatchedOrder(t, "accepted", tc.provider, tc.delID)
			if code := tc.post(ord).Code; code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			st := readWebhookOrder(t, ord.id)
			if st.status != "delivered" {
				t.Errorf("status = %q, want delivered", st.status)
			}
		})
	}
}

// ---- idempotency ---------------------------------------------------------

// Providers deliver at-least-once. A byte-identical replay must run NO side
// effects — most importantly it must not re-clear a cancel's provider linkage
// and re-arm a paid re-dispatch.
func TestIntegration_ProviderWebhooksDedupeReplays(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "uber_direct", "del_replay")

	body := fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"del_replay","data":{"status":"delivered","external_id":%q}}`, ord.id)
	if code := postUberWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("first delivery: %d", code)
	}
	var count int
	if err := h.db.Pool.QueryRow(t.Context(),
		`SELECT COUNT(*) FROM external_webhook_events WHERE provider = 'uber_direct'`).Scan(&count); err != nil {
		t.Fatalf("count events: %v", err)
	}
	if count != 1 {
		t.Fatalf("ledger has %d rows after one event, want 1", count)
	}

	for i := 0; i < 3; i++ {
		if code := postUberWebhook(t, h, body).Code; code != http.StatusOK {
			t.Fatalf("replay %d: %d", i, code)
		}
	}
	if err := h.db.Pool.QueryRow(t.Context(),
		`SELECT COUNT(*) FROM external_webhook_events WHERE provider = 'uber_direct'`).Scan(&count); err != nil {
		t.Fatalf("count events: %v", err)
	}
	if count != 1 {
		t.Errorf("ledger has %d rows after 3 replays, want 1", count)
	}
}

// The ledger is keyed per provider, so two providers sending the same bytes
// must not silently swallow one another's event.
func TestIntegration_WebhookLedgerIsPerProvider(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ordU := seedDispatchedOrder(t, "ready", "uber_direct", "dupe")
	ordD := seedDispatchedOrder(t, "ready", "doordash_drive", "dupe")

	// Deliberately identical bodies except the order they name.
	postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","data":{"status":"delivered","external_id":%q}}`, ordU.id))
	postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, ordD.id))

	if st := readWebhookOrder(t, ordU.id); st.status != "delivered" {
		t.Errorf("uber order = %q, want delivered", st.status)
	}
	if st := readWebhookOrder(t, ordD.id); st.status != "delivered" {
		t.Errorf("doordash order = %q, want delivered", st.status)
	}
}

// ---- cross-provider scoping ---------------------------------------------

// Every mutating branch is scoped to its own provider. Without that, a webhook
// authenticated by ONE provider can advance (or, on a cancel, un-dispatch) an
// order that is out with ANOTHER — the latter buys a second paid courier for
// food already in flight.
func TestIntegration_WebhooksCannotTouchAnotherProvidersOrder(t *testing.T) {
	h := withProviderClients(t)

	cases := []struct {
		name      string
		owned     string // provider the order is actually out with
		ownedDel  string
		intruder  func(o webhookOrder) *httptest.ResponseRecorder
		wantEvent string
	}{
		{"uber delivered on a doordash order", "doordash_drive", "x",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","data":{"status":"delivered","external_id":%q}}`, o.id))
			}, "delivered"},
		{"uber cancel on a shipday order", "shipday", "1",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","data":{"status":"canceled","external_id":%q}}`, o.id))
			}, "canceled"},
		{"doordash cancel on an uber order", "uber_direct", "d1",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DELIVERY_CANCELLED"}`, o.id))
			}, "cancelled"},
		{"doordash pickup on a shipday order", "shipday", "1",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_PICKED_UP"}`, o.id))
			}, "picked up"},
		{"shipday failure on an uber order", "uber_direct", "d1",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_FAILED","order":{"id":1,"order_number":%q}}`, o.id))
			}, "failed"},
		{"shipday completed on a doordash order", "doordash_drive", "x",
			func(o webhookOrder) *httptest.ResponseRecorder {
				return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":1,"order_number":%q}}`, o.id))
			}, "completed"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "ready", tc.owned, tc.ownedDel)
			before := readWebhookOrder(t, ord.id)

			if code := tc.intruder(ord).Code; code != http.StatusOK {
				t.Fatalf("status %d, want 200 (the event is recorded, just not acted on)", code)
			}
			after := readWebhookOrder(t, ord.id)
			if after != before {
				t.Errorf("a %s event mutated an order owned by %s:\n before %+v\n after  %+v",
					tc.wantEvent, tc.owned, before, after)
			}
		})
	}
}

// ---- cancel: re-arming a paid dispatch ----------------------------------

// A provider cancel must clear the linkage so the order can be re-dispatched.
// The status set matters: an order escalated to a provider while still
// 'accepted'/'preparing' has to be cleared too, or the claim CAS (which
// requires NULL linkage) can never re-arm it and the event — already deduped in
// the ledger — never reprocesses. The order is then welded to a dead delivery
// forever, paid for and undeliverable.
func TestIntegration_CancelClearsLinkageFromEveryDispatchableStatus(t *testing.T) {
	h := withProviderClients(t)

	providers := []struct {
		name     string
		provider string
		delID    string
		cancel   func(o webhookOrder) *httptest.ResponseRecorder
	}{
		{"uber_direct", "uber_direct", "d1", func(o webhookOrder) *httptest.ResponseRecorder {
			return postUberWebhook(t, h, fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"d1","data":{"status":"canceled","external_id":%q}}`, o.id))
		}},
		{"doordash_drive", "doordash_drive", "x", func(o webhookOrder) *httptest.ResponseRecorder {
			return postDoorDashWebhook(t, h, fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DELIVERY_CANCELLED"}`, o.id))
		}},
		{"shipday", "shipday", "55", func(o webhookOrder) *httptest.ResponseRecorder {
			return postShipdayWebhook(t, h, fmt.Sprintf(`{"event":"ORDER_FAILED","order":{"id":55,"order_number":%q}}`, o.id))
		}},
	}

	// Every status the dispatch claim CAS can dispatch from, plus picked_up
	// (which must also reset to 'ready' so the sweep can see it again).
	for _, p := range providers {
		for _, status := range []string{"accepted", "preparing", "ready", "picked_up"} {
			t.Run(p.name+"/"+status, func(t *testing.T) {
				clearWebhookLedger(t)
				ord := seedDispatchedOrder(t, status, p.provider, p.delID)

				if code := p.cancel(ord).Code; code != http.StatusOK {
					t.Fatalf("status %d", code)
				}
				st := readWebhookOrder(t, ord.id)
				if st.provider != "" || st.deliveryID != "" || st.trackingURL != "" {
					t.Errorf("linkage not cleared for a %s order: %+v — the claim CAS requires NULL "+
						"linkage, so this order can never be re-dispatched", status, st)
				}
				wantStatus := status
				if status == "picked_up" {
					wantStatus = "ready"
				}
				if st.status != wantStatus {
					t.Errorf("status = %q, want %q", st.status, wantStatus)
				}
			})
		}
	}
}

// A cancel naming a SUPERSEDED delivery must not un-dispatch the live one.
//
// Uber mints a fresh delivery_id on every dispatch, so after a cancel-then-
// re-dispatch the order is out with delivery #2 while a late webhook for
// delivery #1 is still in flight. That event is not a replay (different body,
// so the idempotency ledger lets it through), and if the cancel branch is
// scoped only by provider it clears delivery #2's linkage — the next sweep then
// buys a SECOND paid courier for food already on its way.
func TestIntegration_StaleCancelMustNotUnDispatchTheLiveDelivery(t *testing.T) {
	h := withProviderClients(t)

	t.Run("uber_direct", func(t *testing.T) {
		clearWebhookLedger(t)
		// The order is out with delivery #2 (the re-dispatch).
		ord := seedDispatchedOrder(t, "ready", "uber_direct", "uber_delivery_2")
		// A late 'canceled' for the superseded delivery #1 arrives.
		rec := postUberWebhook(t, h, fmt.Sprintf(
			`{"kind":"event.delivery_status","delivery_id":"uber_delivery_1","data":{"status":"canceled","external_id":%q}}`, ord.id))
		if rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		st := readWebhookOrder(t, ord.id)
		if st.deliveryID != "uber_delivery_2" || st.provider != "uber_direct" {
			t.Errorf("a cancel for superseded delivery #1 un-dispatched live delivery #2: %+v\n"+
				"the next sweep will buy a SECOND paid courier for food already in flight", st)
		}
	})

	// Shipday scopes its failure branch on the webhook's own order id, so the
	// same stale event is correctly ignored. This is the shape the others need.
	t.Run("shipday", func(t *testing.T) {
		clearWebhookLedger(t)
		ord := seedDispatchedOrder(t, "ready", "shipday", "222")
		rec := postShipdayWebhook(t, h, fmt.Sprintf(
			`{"event":"ORDER_FAILED","order":{"id":111,"order_number":%q}}`, ord.id))
		if rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		st := readWebhookOrder(t, ord.id)
		if st.deliveryID != "222" || st.provider != "shipday" {
			t.Errorf("a stale Shipday failure un-dispatched the live delivery: %+v", st)
		}
	})
}

// Unhandled event names are intentional no-ops, not errors — the providers send
// many tracking/return-flow events we don't model, and 500ing on them would
// create a retry storm.
func TestIntegration_UnhandledEventsAreQuietNoOps(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "uber_direct", "d1")
	before := readWebhookOrder(t, ord.id)

	for _, ev := range []string{"courier_update", "en_route_to_pickup", "returned"} {
		if code := postUberWebhook(t, h, fmt.Sprintf(
			`{"kind":"event.delivery_status","data":{"status":%q,"external_id":%q}}`, ev, ord.id)).Code; code != http.StatusOK {
			t.Errorf("uber %s: status %d, want 200", ev, code)
		}
	}
	for _, ev := range []string{"DASHER_CONFIRMED_PICKUP_ARRIVAL", "dasher_enroute_to_pickup"} {
		if code := postDoorDashWebhook(t, h, fmt.Sprintf(
			`{"external_delivery_id":%q,"event_name":%q}`, ord.id, ev)).Code; code != http.StatusOK {
			t.Errorf("doordash %s: status %d, want 200", ev, code)
		}
	}
	for _, ev := range []string{"ORDER_INSERTED", "ORDER_POD_UPLOAD", "ORDER_DELETE"} {
		if code := postShipdayWebhook(t, h, fmt.Sprintf(
			`{"event":%q,"order":{"id":1,"order_number":%q}}`, ev, ord.id)).Code; code != http.StatusOK {
			t.Errorf("shipday %s: status %d, want 200", ev, code)
		}
	}
	if after := readWebhookOrder(t, ord.id); after != before {
		t.Errorf("an unhandled event mutated the order:\n before %+v\n after  %+v", before, after)
	}
}

// Uber sends non-delivery_status kinds too; those must be dropped before any
// order lookup so they never touch state or the ledger.
func TestIntegration_UberIgnoresOtherEventKinds(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "uber_direct", "d1")

	if code := postUberWebhook(t, h, fmt.Sprintf(
		`{"kind":"event.courier_update","data":{"status":"canceled","external_id":%q}}`, ord.id)).Code; code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if st := readWebhookOrder(t, ord.id); st.provider != "uber_direct" || st.deliveryID != "d1" {
		t.Errorf("a courier_update event was processed as a delivery status: %+v", st)
	}
	var count int
	if err := h.db.Pool.QueryRow(t.Context(),
		`SELECT COUNT(*) FROM external_webhook_events`).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 0 {
		t.Errorf("ledger has %d rows; a non-status event should not consume an idempotency slot", count)
	}
}

// A late event on an already-terminal order is benign: ACK, don't 500, and
// don't resurrect the order.
func TestIntegration_LateEventOnTerminalOrderIsBenign(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "delivered", "uber_direct", "d1")

	if code := postUberWebhook(t, h, fmt.Sprintf(
		`{"kind":"event.delivery_status","delivery_id":"d1","data":{"status":"pickup_complete","external_id":%q}}`, ord.id)).Code; code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if st := readWebhookOrder(t, ord.id); st.status != "delivered" {
		t.Errorf("status = %q, want delivered (a late pickup must not walk the order backwards)", st.status)
	}
}

// A webhook for an order we have never heard of must ACK, not 500.
func TestIntegration_WebhookForUnknownOrderAcks(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	const ghost = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

	if code := postUberWebhook(t, h, fmt.Sprintf(
		`{"kind":"event.delivery_status","data":{"status":"delivered","external_id":%q}}`, ghost)).Code; code != http.StatusOK {
		t.Errorf("uber: status %d, want 200", code)
	}
	if code := postDoorDashWebhook(t, h, fmt.Sprintf(
		`{"external_delivery_id":%q,"event_name":"DELIVERY_CANCELLED"}`, ghost)).Code; code != http.StatusOK {
		t.Errorf("doordash: status %d, want 200", code)
	}
	if code := postShipdayWebhook(t, h, fmt.Sprintf(
		`{"event":"ORDER_COMPLETED","order":{"id":1,"order_number":%q}}`, ghost)).Code; code != http.StatusOK {
		t.Errorf("shipday: status %d, want 200", code)
	}
}

// A handler with no client configured for that provider must ACK and do
// nothing — never authenticate against an empty secret.
func TestIntegration_WebhooksWithNoClientConfiguredAck(t *testing.T) {
	h := harness.h
	origU, origD, origS := h.uber, h.doordash, h.shipday
	defer func() { h.uber, h.doordash, h.shipday = origU, origD, origS }()
	h.uber, h.doordash, h.shipday = nil, nil, nil

	rec := httptest.NewRecorder()
	h.UberDirectWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(`{}`))))
	if rec.Code != http.StatusOK {
		t.Errorf("uber: status %d, want 200", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.DoorDashWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(`{}`))))
	if rec.Code != http.StatusOK {
		t.Errorf("doordash: status %d, want 200", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.ShipdayWebhook(rec, httptest.NewRequest(http.MethodPost, "/w", bytes.NewReader([]byte(`{}`))))
	if rec.Code != http.StatusOK {
		t.Errorf("shipday: status %d, want 200", rec.Code)
	}
}
