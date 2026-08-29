package handlers

// The cancel/failure branches of the three provider webhooks. These are the
// only webhook paths that CLEAR provider linkage, and clearing linkage re-arms
// auto-dispatch — so every one of them is one wrong predicate away from buying
// a second paid courier, or from welding an order to a dead delivery forever.
//
// SAFETY: DB only, same as provider_webhook_test.go. No provider network.

import (
	"fmt"
	"net/http"
	"testing"
)

// cancelBody builds the cancel/failure webhook body for each provider.
func cancelBody(provider, orderID, deliveryID string) string {
	switch provider {
	case "uber_direct":
		return fmt.Sprintf(
			`{"kind":"event.delivery_status","delivery_id":%q,"data":{"status":"canceled","external_id":%q}}`,
			deliveryID, orderID)
	case "doordash_drive":
		return fmt.Sprintf(`{"external_delivery_id":%q,"event_name":"DELIVERY_CANCELLED"}`, orderID)
	default:
		return fmt.Sprintf(`{"event":"ORDER_FAILED","order":{"id":%s,"order_number":%q}}`, deliveryID, orderID)
	}
}

func postCancel(t *testing.T, h *Handler, provider, orderID, deliveryID string) *http.Response {
	t.Helper()
	body := cancelBody(provider, orderID, deliveryID)
	switch provider {
	case "uber_direct":
		return postUberWebhook(t, h, body).Result()
	case "doordash_drive":
		return postDoorDashWebhook(t, h, body).Result()
	default:
		return postShipdayWebhook(t, h, body).Result()
	}
}

// A cancel from the provider that OWNS the order must clear the linkage and
// hand the order back to dispatch, from every status the claim CAS can dispatch
// from. An order left welded to a cancelled delivery can never re-arm: the
// event is already deduped in the ledger so it never reprocesses, and the claim
// CAS requires NULL linkage.
func TestIntegration_CancelReArmsDispatchAcrossProviders(t *testing.T) {
	h := withProviderClients(t)

	for _, provider := range []string{"uber_direct", "doordash_drive", "shipday"} {
		for _, status := range []string{"accepted", "preparing", "ready", "picked_up"} {
			t.Run(provider+"/"+status, func(t *testing.T) {
				clearWebhookLedger(t)
				deliveryID := "555"
				if provider != "shipday" {
					deliveryID = provider + "_del_1"
				}
				if provider == "doordash_drive" {
					// DoorDash's external_delivery_id IS our order id.
					deliveryID = ""
				}
				ord := seedDispatchedOrder(t, status, provider, deliveryID)
				if provider == "doordash_drive" {
					if _, err := harness.h.db.Pool.Exec(t.Context(),
						`UPDATE orders SET external_delivery_id = id::text WHERE id = $1`, ord.id); err != nil {
						t.Fatalf("set dd delivery id: %v", err)
					}
				}

				resp := postCancel(t, h, provider, ord.id, deliveryID)
				if resp.StatusCode != http.StatusOK {
					t.Fatalf("status %d", resp.StatusCode)
				}

				st := readWebhookOrder(t, ord.id)
				if st.provider != "" || st.deliveryID != "" || st.trackingURL != "" {
					t.Errorf("linkage not cleared: %+v — the order can never be re-dispatched, "+
						"the claim CAS requires NULL linkage and this event will never reprocess", st)
				}
				// picked_up must fall back to ready so the food is re-dispatchable;
				// the earlier states stay put (the kitchen is still cooking).
				want := status
				if status == "picked_up" {
					want = "ready"
				}
				if st.status != want {
					t.Errorf("status = %q, want %q", st.status, want)
				}
			})
		}
	}
}

// A cancel must never clear linkage on an order that is out with a DIFFERENT
// provider — that is the direct route to a second paid courier for food already
// in flight. Each provider's cancel is scoped to its own external_provider.
func TestIntegration_CancelCannotUnDispatchAnotherProvidersOrder(t *testing.T) {
	h := withProviderClients(t)

	cases := []struct{ sender, owner string }{
		{"uber_direct", "doordash_drive"},
		{"doordash_drive", "shipday"},
		{"shipday", "uber_direct"},
	}
	for _, tc := range cases {
		t.Run(tc.sender+"_cancels_"+tc.owner, func(t *testing.T) {
			clearWebhookLedger(t)
			ord := seedDispatchedOrder(t, "picked_up", tc.owner, "live_delivery")

			resp := postCancel(t, h, tc.sender, ord.id, "999")
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("status %d", resp.StatusCode)
			}

			st := readWebhookOrder(t, ord.id)
			if st.provider != tc.owner || st.deliveryID != "live_delivery" || st.status != "picked_up" {
				t.Errorf("a %s cancel disturbed an order owned by %s: %+v\n"+
					"the next sweep would buy a SECOND paid courier for a delivery already in flight",
					tc.sender, tc.owner, st)
			}
		})
	}
}

// A cancel for an order that has already reached a terminal state is a benign
// late event: ACK it, change nothing, and above all do not resurrect a
// delivered order into a re-dispatchable one.
func TestIntegration_CancelOnTerminalOrderChangesNothing(t *testing.T) {
	h := withProviderClients(t)

	for _, provider := range []string{"uber_direct", "doordash_drive", "shipday"} {
		t.Run(provider, func(t *testing.T) {
			clearWebhookLedger(t)
			deliveryID := "555"
			if provider != "shipday" {
				deliveryID = "del_terminal"
			}
			ord := seedDispatchedOrder(t, "delivered", provider, deliveryID)
			before := readWebhookOrder(t, ord.id)

			resp := postCancel(t, h, provider, ord.id, deliveryID)
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("status %d — a late cancel must ACK, not trigger a provider retry storm",
					resp.StatusCode)
			}
			if after := readWebhookOrder(t, ord.id); after != before {
				t.Errorf("a cancel on a delivered order mutated it: %+v -> %+v", before, after)
			}
		})
	}
}
