package handlers

// Uber Direct mints a FRESH delivery_id on every dispatch, so an order that has
// been cancelled and re-dispatched is out with delivery #2 while late webhooks
// for the superseded delivery #1 can still arrive. Those are not replays — a
// different body hashes differently, so the idempotency ledger lets them
// through — which is why the 'canceled' branch scopes its UPDATE on
// external_delivery_id as well as external_provider (see
// TestIntegration_StaleCancelMustNotUnDispatchTheLiveDelivery).
//
// The lifecycle branches carry the same exposure and need the same scope: a
// stale 'pickup_complete' or 'delivered' for delivery #1 must not advance —
// still less terminate — an order that is out with delivery #2. Shipday already
// scopes every branch this way (TestIntegration_ShipdayCompletionIsScopedToIts
// OwnDelivery); these tests hold Uber to it.
//
// SAFETY: DB + local HMAC only. No provider client makes an outbound call here.

import (
	"fmt"
	"net/http"
	"testing"
)

// uberEvent builds a signed-body helper for one delivery id.
func uberLifecycleBody(orderID, deliveryID, status string) string {
	return fmt.Sprintf(
		`{"kind":"event.delivery_status","delivery_id":%q,"data":{"status":%q,"external_id":%q}}`,
		deliveryID, status, orderID)
}

// A late event for a superseded Uber delivery must be a no-op on the order that
// is out with a different, live delivery.
func TestIntegration_UberLifecycleIsScopedToItsOwnDelivery(t *testing.T) {
	h := withProviderClients(t)

	cases := []struct {
		name       string
		seedStatus string
		status     string // the stale event's status
		wantStatus string // the order's status afterwards
	}{
		{
			name: "stale pickup_complete must not advance the live delivery",
			// Delivery #2 has been dispatched but has not picked up yet.
			seedStatus: "ready", status: "pickup_complete", wantStatus: "ready",
		},
		{
			name: "stale delivered must not close out the live delivery",
			// Delivery #2 has the food in hand; a 'delivered' for #1 would end the
			// order early and push "your order was delivered" to the consumer while
			// the real courier is still driving.
			seedStatus: "picked_up", status: "delivered", wantStatus: "picked_up",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, tc.seedStatus, "uber_direct", "uber_delivery_2")

			rec := postUberWebhook(t, h, uberLifecycleBody(ord.id, "uber_delivery_1", tc.status))
			if rec.Code != http.StatusOK {
				t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
			}

			st := readWebhookOrder(t, ord.id)
			if st.status != tc.wantStatus {
				t.Errorf("order status = %q, want %q — a %q for superseded delivery #1 moved an order "+
					"that is out with live delivery #2 (%+v)", st.status, tc.wantStatus, tc.status, st)
			}
			if tc.status == "delivered" && st.delivered {
				t.Error("delivered_at was stamped by an event belonging to a delivery this order is no longer out with")
			}
			// The live linkage must survive untouched either way.
			if st.provider != "uber_direct" || st.deliveryID != "uber_delivery_2" {
				t.Errorf("live linkage changed: %+v", st)
			}
		})
	}
}

// The matching event for the delivery the order IS out with must still work —
// the scope must not turn into a blanket refusal.
func TestIntegration_UberLifecycleAdvancesItsOwnDelivery(t *testing.T) {
	h := withProviderClients(t)

	t.Run("pickup_complete", func(t *testing.T) {
		clearWebhookLedger(t)
		ord := seedDispatchedOrder(t, "ready", "uber_direct", "uber_delivery_2")
		if rec := postUberWebhook(t, h, uberLifecycleBody(ord.id, "uber_delivery_2", "pickup_complete")); rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		if st := readWebhookOrder(t, ord.id); st.status != "picked_up" || !st.pickedUp {
			t.Errorf("order = %+v, want picked_up with a pickup timestamp", st)
		}
	})

	t.Run("delivered", func(t *testing.T) {
		clearWebhookLedger(t)
		ord := seedDispatchedOrder(t, "picked_up", "uber_direct", "uber_delivery_2")
		if rec := postUberWebhook(t, h, uberLifecycleBody(ord.id, "uber_delivery_2", "delivered")); rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		if st := readWebhookOrder(t, ord.id); st.status != "delivered" || !st.delivered {
			t.Errorf("order = %+v, want delivered with a delivered timestamp", st)
		}
	})
}

// Uber's documented payloads carry delivery_id, but the handler must not start
// dropping events if a variant omits it: an empty id keeps the previous,
// provider-only scoping rather than silently stranding the order. Same escape
// hatch the 'canceled' branch already uses.
func TestIntegration_UberLifecycleWithoutADeliveryIDStillAdvances(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)
	ord := seedDispatchedOrder(t, "ready", "uber_direct", "uber_delivery_2")

	body := fmt.Sprintf(
		`{"kind":"event.delivery_status","data":{"status":"delivered","external_id":%q}}`, ord.id)
	if rec := postUberWebhook(t, h, body); rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if st := readWebhookOrder(t, ord.id); st.status != "delivered" {
		t.Errorf("order = %+v, want delivered — a payload with no delivery_id must behave as it did before", st)
	}
}
