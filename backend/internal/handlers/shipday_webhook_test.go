package handlers

// Shipday's order-status webhook, which had no dedicated coverage of its own —
// the shared provider table in provider_webhook_test.go exercises the auth,
// poison-pill and scoping behavior all three handlers share, but not the events
// that are Shipday-specific: the two different spellings that both mean "the
// food left the restaurant", the assignment event that carries the third-party
// carrier's name, and the three distinct failure events that all have to
// re-arm dispatch.
//
// Shipday is the aggregator, so its delivery id is a NUMBER minted by Shipday
// rather than our order UUID, and every mutation is scoped to that number as
// well as the provider — an abandoned unassigned Shipday order can carry the
// same order_number as the live one, and its late webhooks must not touch the
// order that is actually out.
//
// SAFETY: no request leaves the machine. These handlers only read the request
// body and the local Postgres; the Shipday client is constructed with an
// obviously fake token purely so VerifyWebhook has something to compare.

import (
	"context"
	"fmt"
	"net/http"
	"testing"
)

// pickedUpAt reports whether the order carries a pickup timestamp.
func pickedUpAt(t *testing.T, orderID string) bool {
	t.Helper()
	var set bool
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT picked_up_at IS NOT NULL FROM orders WHERE id = $1`, orderID).Scan(&set); err != nil {
		t.Fatalf("read picked_up_at: %v", err)
	}
	return set
}

// Shipday spells the pickup event two ways and fires whichever its integration
// with the underlying carrier produces. Both mean the same thing, both must
// advance the order, and the second to arrive must be a no-op rather than a
// second push or a rewritten timestamp.
func TestIntegration_ShipdayPickupAcceptsBothSpellings(t *testing.T) {
	for _, event := range []string{"ORDER_PIKEDUP", "ORDER_ONTHEWAY"} {
		t.Run(event, func(t *testing.T) {
			h := withProviderClients(t)
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "ready", "shipday", "4242")

			body := fmt.Sprintf(`{"event":%q,"order":{"id":4242,"order_number":%q}}`, event, ord.id)
			if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			st := readWebhookOrder(t, ord.id)
			if st.status != "picked_up" {
				t.Fatalf("status = %q, want picked_up", st.status)
			}
			if !pickedUpAt(t, ord.id) {
				t.Error("picked_up_at not stamped")
			}
		})
	}
}

// The other spelling arriving second finds the order already picked_up and must
// change nothing — the status guard, not the idempotency ledger, is what stops
// it (the two events have different bodies, so they hash differently and both
// claim the ledger).
func TestIntegration_ShipdayBothPickupSpellingsAdvanceOnlyOnce(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "shipday", "4243")

	first := fmt.Sprintf(`{"event":"ORDER_PIKEDUP","order":{"id":4243,"order_number":%q}}`, ord.id)
	second := fmt.Sprintf(`{"event":"ORDER_ONTHEWAY","order":{"id":4243,"order_number":%q}}`, ord.id)
	if code := postShipdayWebhook(t, h, first).Code; code != http.StatusOK {
		t.Fatalf("first: status %d", code)
	}
	var firstStamp any
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT picked_up_at FROM orders WHERE id = $1`, ord.id).Scan(&firstStamp); err != nil {
		t.Fatalf("read stamp: %v", err)
	}
	if code := postShipdayWebhook(t, h, second).Code; code != http.StatusOK {
		t.Fatalf("second: status %d", code)
	}
	var secondStamp any
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT picked_up_at FROM orders WHERE id = $1`, ord.id).Scan(&secondStamp); err != nil {
		t.Fatalf("read stamp: %v", err)
	}
	if fmt.Sprint(firstStamp) != fmt.Sprint(secondStamp) {
		t.Errorf("picked_up_at moved on the duplicate pickup event: %v -> %v", firstStamp, secondStamp)
	}
	if st := readWebhookOrder(t, ord.id); st.status != "picked_up" {
		t.Errorf("status = %q, want picked_up", st.status)
	}
}

// Every Shipday event that means "this delivery is not happening" must clear the
// provider linkage so the order can be dispatched again. Missing any one of them
// welds the order to a dead delivery forever: the event is already recorded in
// the idempotency ledger so it never reprocesses, and the dispatch claim CAS
// requires NULL linkage to re-arm.
func TestIntegration_ShipdayFailureEventsAllReArmDispatch(t *testing.T) {
	for _, event := range []string{"ORDER_FAILED", "ORDER_INCOMPLETE", "ORDER_UNASSIGNED"} {
		t.Run(event, func(t *testing.T) {
			h := withProviderClients(t)
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "picked_up", "shipday", "5150")

			body := fmt.Sprintf(`{"event":%q,"order":{"id":5150,"order_number":%q}}`, event, ord.id)
			if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			st := readWebhookOrder(t, ord.id)
			if st.provider != "" || st.deliveryID != "" || st.trackingURL != "" {
				t.Errorf("linkage not cleared: provider=%q delivery=%q tracking=%q",
					st.provider, st.deliveryID, st.trackingURL)
			}
			// picked_up would sit outside sweepAutoDispatch's 'ready' filter, so
			// the order could never be re-dispatched without this reset.
			if st.status != "ready" {
				t.Errorf("status = %q, want ready (reset so the sweep can re-dispatch)", st.status)
			}
		})
	}
}

// A failure event naming a DIFFERENT Shipday order id must not touch the live
// one. This is the concrete reason the scoping is on the numeric id and not
// merely on the provider: a retried dispatch leaves abandoned unassigned Shipday
// orders behind that carry OUR order_number, and their eventual ORDER_UNASSIGNED
// would otherwise clear the linkage of the delivery that is genuinely in flight
// — and the next sweep would buy a second courier for food already moving.
func TestIntegration_ShipdayStaleFailureCannotUnDispatchTheLiveDelivery(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "shipday", "999") // live delivery = 999

	// The abandoned first attempt was Shipday order 111.
	body := fmt.Sprintf(`{"event":"ORDER_UNASSIGNED","order":{"id":111,"order_number":%q}}`, ord.id)
	if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	st := readWebhookOrder(t, ord.id)
	if st.provider != "shipday" || st.deliveryID != "999" {
		t.Errorf("a stale delivery's failure un-dispatched the live one: provider=%q delivery=%q",
			st.provider, st.deliveryID)
	}
	if st.status != "ready" {
		t.Errorf("status = %q, want ready (untouched)", st.status)
	}
}

// The completion event likewise has to name the delivery that is actually out.
func TestIntegration_ShipdayCompletionIsScopedToItsOwnDelivery(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "picked_up", "shipday", "8080")

	body := fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":1,"order_number":%q}}`, ord.id)
	if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
		t.Fatalf("status %d", code)
	}
	if st := readWebhookOrder(t, ord.id); st.status != "picked_up" || st.delivered {
		t.Errorf("a foreign delivery marked the order delivered: status=%q delivered=%v",
			st.status, st.delivered)
	}
}

// Shipday events we don't act on must be quiet 200s that change nothing —
// Shipday fires a good number of them (ORDER_INSERTED, ORDER_POD_UPLOAD, the
// *_REMOVED corrections) and any of them mutating state would be a surprise.
func TestIntegration_ShipdayUnhandledEventsChangeNothing(t *testing.T) {
	h := withProviderClients(t)
	for _, event := range []string{"ORDER_INSERTED", "ORDER_ACCEPTED_AND_STARTED", "ORDER_POD_UPLOAD", "ORDER_DELETE"} {
		t.Run(event, func(t *testing.T) {
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "ready", "shipday", "6060")
			before := readWebhookOrder(t, ord.id)

			body := fmt.Sprintf(`{"event":%q,"order":{"id":6060,"order_number":%q}}`, event, ord.id)
			if code := postShipdayWebhook(t, h, body).Code; code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			if after := readWebhookOrder(t, ord.id); after != before {
				t.Errorf("unhandled event %s mutated the order: %+v -> %+v", event, before, after)
			}
		})
	}
}

// A delivery that completes without its pickup event having arrived must still
// leave a plausible pickup timestamp. Webhook outages and late registrations
// both drop the pickup event, and courier-time analytics divide by picked_up_at
// — a delivered order with a NULL pickup is a hole in every duration metric and
// renders as a blank in the order-detail screens that surface it.
//
// Applies to all three providers: the same outage drops the same event whoever
// is carrying the food, so the repair belongs in each handler's terminal event.
func TestIntegration_DeliveredWithoutAPickupStillStampsPickedUpAt(t *testing.T) {
	h := withProviderClients(t)
	for _, tc := range []struct {
		name     string
		provider string
		delID    string
		body     func(orderID string) string
		post     func(body string) int
	}{
		{
			name: "uber", provider: "uber_direct", delID: "d_pu",
			body: func(id string) string {
				return fmt.Sprintf(`{"kind":"event.delivery_status","delivery_id":"d_pu","data":{"status":"delivered","external_id":%q}}`, id)
			},
			post: func(b string) int { return postUberWebhook(t, h, b).Code },
		},
		{
			name: "doordash", provider: "doordash_drive", delID: "dd_pu",
			body: func(id string) string {
				return fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DASHER_DROPPED_OFF"}`, id)
			},
			post: func(b string) int { return postDoorDashWebhook(t, h, b).Code },
		},
		{
			name: "shipday", provider: "shipday", delID: "3131",
			body: func(id string) string {
				return fmt.Sprintf(`{"event":"ORDER_COMPLETED","order":{"id":3131,"order_number":%q}}`, id)
			},
			post: func(b string) int { return postShipdayWebhook(t, h, b).Code },
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			// 'ready': the food was never reported picked up.
			ord := seedDispatchedOrder(t, "ready", tc.provider, tc.delID)
			if pickedUpAt(t, ord.id) {
				t.Fatal("fixture already has a pickup stamp")
			}

			if code := tc.post(tc.body(ord.id)); code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			st := readWebhookOrder(t, ord.id)
			if st.status != "delivered" {
				t.Fatalf("status = %q, want delivered", st.status)
			}
			if !pickedUpAt(t, ord.id) {
				t.Error("delivered order has no picked_up_at — every courier-duration metric " +
					"divides by it, and the order-detail screens render it blank")
			}
		})
	}
}
