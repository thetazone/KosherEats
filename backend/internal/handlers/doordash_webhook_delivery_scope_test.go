package handlers

// DoorDash's external_delivery_id now rolls per dispatch cycle
// ("<order>-g<n>", dispatch.DoorDashExternalDeliveryID), so an order that was
// cancelled and re-dispatched is out with "-g1" while late or retried events
// for "-g0" can still arrive. Those are not replays (different bodies hash
// differently), so the idempotency ledger lets them through — exactly the
// exposure the Uber and Shipday handlers already close by scoping every
// mutating statement on the webhook's own delivery id. With provider-only
// scoping a stale DASHER_PICKED_UP advanced the live delivery (and pushed
// "your driver has your food" before its Dasher had been anywhere), and a
// stale DELIVERY_CANCELLED cleared the live linkage and re-armed the sweep to
// buy a second paid courier. These tests hold DoorDash to the same rule.
//
// SAFETY: DB + a static token only. No provider client makes an outbound call.

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/koshereats/backend/internal/dispatch"
)

func ddEvent(deliveryID, event string) string {
	return fmt.Sprintf(`{"external_delivery_id":%q,"event_name":%q}`, deliveryID, event)
}

// seedDoorDashCycle seeds an order out with dispatch cycle `gen` and returns
// it plus the ids of that cycle and the superseded one before it.
func seedDoorDashCycle(t *testing.T, status string, gen int) (ord webhookOrder, live, stale string) {
	t.Helper()
	ord = seedDispatchedOrder(t, status, "doordash_drive", "placeholder")
	live = dispatch.DoorDashExternalDeliveryID(ord.id, gen)
	stale = dispatch.DoorDashExternalDeliveryID(ord.id, gen-1)
	if _, err := harness.h.db.Pool.Exec(t.Context(),
		`UPDATE orders SET external_delivery_id = $1 WHERE id = $2`, live, ord.id); err != nil {
		t.Fatalf("stamp live delivery id: %v", err)
	}
	return ord, live, stale
}

// A late event for a superseded DoorDash delivery must be a no-op on the order
// that is out with a different, live delivery.
func TestIntegration_DoorDashLifecycleIsScopedToItsOwnDelivery(t *testing.T) {
	h := withProviderClients(t)

	cases := []struct {
		name       string
		seedStatus string
		event      string
		wantStatus string
	}{
		{"stale DASHER_PICKED_UP must not advance the live delivery", "ready", "DASHER_PICKED_UP", "ready"},
		{"stale DASHER_DROPPED_OFF must not close out the live delivery", "picked_up", "DASHER_DROPPED_OFF", "picked_up"},
		{"stale DELIVERY_CANCELLED must not un-dispatch the live delivery", "ready", "DELIVERY_CANCELLED", "ready"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			ord, live, stale := seedDoorDashCycle(t, tc.seedStatus, 1)

			rec := postDoorDashWebhook(t, h, ddEvent(stale, tc.event))
			if rec.Code != http.StatusOK {
				t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
			}

			st := readWebhookOrder(t, ord.id)
			if st.status != tc.wantStatus {
				t.Errorf("order status = %q, want %q — a %s for superseded delivery %q moved an order "+
					"that is out with live delivery %q (%+v)", st.status, tc.wantStatus, tc.event, stale, live, st)
			}
			if tc.event == "DASHER_DROPPED_OFF" && st.delivered {
				t.Error("delivered_at was stamped by an event belonging to a delivery this order is no longer out with")
			}
			if st.provider != "doordash_drive" || st.deliveryID != live {
				t.Errorf("live linkage changed: %+v — for a cancel this re-arms the sweep to buy a SECOND paid courier", st)
			}
		})
	}
}

// The matching event for the delivery the order IS out with must still work —
// the scope must not turn into a blanket refusal.
func TestIntegration_DoorDashLifecycleAdvancesItsOwnDelivery(t *testing.T) {
	h := withProviderClients(t)

	t.Run("DASHER_PICKED_UP", func(t *testing.T) {
		clearWebhookLedger(t)
		ord, live, _ := seedDoorDashCycle(t, "ready", 1)
		if rec := postDoorDashWebhook(t, h, ddEvent(live, "DASHER_PICKED_UP")); rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		if st := readWebhookOrder(t, ord.id); st.status != "picked_up" || !st.pickedUp {
			t.Errorf("order = %+v, want picked_up with a pickup timestamp", st)
		}
	})

	t.Run("DASHER_DROPPED_OFF", func(t *testing.T) {
		clearWebhookLedger(t)
		ord, live, _ := seedDoorDashCycle(t, "picked_up", 1)
		if rec := postDoorDashWebhook(t, h, ddEvent(live, "DASHER_DROPPED_OFF")); rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		if st := readWebhookOrder(t, ord.id); st.status != "delivered" || !st.delivered {
			t.Errorf("order = %+v, want delivered with a delivered timestamp", st)
		}
	})

	t.Run("DELIVERY_CANCELLED", func(t *testing.T) {
		clearWebhookLedger(t)
		ord, live, _ := seedDoorDashCycle(t, "picked_up", 1)
		if rec := postDoorDashWebhook(t, h, ddEvent(live, "DELIVERY_CANCELLED")); rec.Code != http.StatusOK {
			t.Fatalf("status %d", rec.Code)
		}
		if st := readWebhookOrder(t, ord.id); st.status != "ready" || st.provider != "" || st.deliveryID != "" {
			t.Errorf("order = %+v, want the linkage cleared and status ready so the sweep re-dispatches", st)
		}
	})
}

// The DASHER_CONFIRMED push is scoped the same way: a stale "courier on the
// way" for the superseded delivery must not reach the consumer.
func TestIntegration_DoorDashConfirmedPushIsScopedToItsOwnDelivery(t *testing.T) {
	withProviderClients(t)
	h, tracer := withPushTracer(t)

	for _, tc := range []struct {
		name       string
		useLive    bool
		wantPushes int
	}{
		{"own delivery notifies once", true, 1},
		{"superseded delivery notifies nobody", false, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			clearWebhookLedger(t)
			_, live, stale := seedDoorDashCycle(t, "ready", 1)
			id := stale
			if tc.useLive {
				id = live
			}
			tracer.reset()
			if code := postDoorDashWebhook(t, h, ddEvent(id, "DASHER_CONFIRMED")).Code; code != http.StatusOK {
				t.Fatalf("status %d", code)
			}
			if got := tracer.attempts(); got != tc.wantPushes {
				t.Errorf("%d consumer push(es) attempted, want %d", got, tc.wantPushes)
			}
		})
	}
}
