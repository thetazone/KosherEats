package handlers

// Dispatch now sends DoorDash "<order uuid>-g<generation>" as the
// external_delivery_id (so a post-cancel re-dispatch is not a duplicate id at
// DoorDash). DoorDash echoes that string back on every webhook, so the handler
// must resolve it to the order — otherwise every DoorDash order would strand
// at the uuid.Parse guard as "not one of our order ids".

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/koshereats/backend/internal/dispatch"
)

func TestIntegration_DoorDashWebhookResolvesGenerationSuffixedExternalID(t *testing.T) {
	h := withProviderClients(t)
	clearWebhookLedger(t)

	ord := seedDispatchedOrder(t, "ready", "doordash_drive", "placeholder")
	extID := dispatch.DoorDashExternalDeliveryID(ord.id, 1)
	if _, err := harness.h.db.Pool.Exec(t.Context(),
		`UPDATE orders SET external_delivery_id = $1 WHERE id = $2`, extID, ord.id); err != nil {
		t.Fatalf("stamp delivery id: %v", err)
	}

	if code := postDoorDashWebhook(t, h, fmt.Sprintf(
		`{"external_delivery_id":%q,"event_name":"DASHER_PICKED_UP"}`, extID)).Code; code != http.StatusOK {
		t.Fatalf("pickup webhook: status %d", code)
	}
	if st := readWebhookOrder(t, ord.id); st.status != "picked_up" || !st.pickedUp {
		t.Fatalf("after DASHER_PICKED_UP with a suffixed id: %+v, want picked_up", st)
	}

	if code := postDoorDashWebhook(t, h, fmt.Sprintf(
		`{"external_delivery_id":%q,"event_name":"DELIVERY_CANCELLED"}`, extID)).Code; code != http.StatusOK {
		t.Fatalf("cancel webhook: status %d", code)
	}
	st := readWebhookOrder(t, ord.id)
	if st.status != "ready" || st.provider != "" || st.deliveryID != "" {
		t.Fatalf("after DELIVERY_CANCELLED with a suffixed id: %+v, want the linkage cleared and status ready", st)
	}
}
