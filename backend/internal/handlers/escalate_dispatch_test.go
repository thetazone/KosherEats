package handlers

// The seller escalation ("I'm swamped — send this one to a courier"), end to
// end through the real provider auction. This is the one handler that makes a
// PAID CreateDelivery call synchronously inside a request, so it needs the
// auction, the linkage it persists, and the way it classifies a failure back to
// the seller all pinned.
//
// SAFETY: installFakeProviders swaps http.DefaultTransport for one that rewrites
// every provider host to a local httptest server and REFUSES any other host, so
// no request can reach Uber, DoorDash or Shipday. All credentials are fakes.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/koshereats/backend/internal/dispatch"
)

const (
	uberCreatePath    = "/v1/customers/fake-customer/deliveries"
	ddCreatePath      = "/drive/v2/deliveries"
	shipdayInsertPath = "/orders"
	shipdayAssignPath = "/on-demand/assign"
)

// withDispatcher rebuilds the harness handler's ExternalDispatcher over the
// fake provider clients installFakeProviders just wired, restoring the original
// afterwards. The handler builds its dispatcher once at New(), so swapping the
// clients alone would leave the escalation talking to the real ones.
func withDispatcher(t *testing.T, uber, dd, sd bool) (*Handler, *fakeCourierNet) {
	t.Helper()
	net := installFakeProviders(t, uber, dd, sd)
	h := harness.h
	orig := h.dispatcher
	t.Cleanup(func() { h.dispatcher = orig })
	h.dispatcher = dispatch.New(h.db.Pool, h.uber, h.doordash, h.shipday, h.notify, nil)
	return h, net
}

// dispatchableSeller is a seller whose restaurant has the pickup phone every
// provider requires on create — without it Dispatch fails fast before the
// auction (missingRequiredPhone), which is a different code path from the one
// these tests are about.
func dispatchableSeller(t *testing.T) *sellerEnv {
	t.Helper()
	s := newSellerEnv(t)
	mustExec(t, `UPDATE restaurants SET phone = '+17185551212' WHERE id = $1`, s.restID)
	return s
}

type escalateResponse struct {
	Status      string `json:"status"`
	Provider    string `json:"provider"`
	DeliveryID  string `json:"delivery_id"`
	TrackingURL string `json:"tracking_url"`
}

func escalate(t *testing.T, s *sellerEnv, h *Handler, orderID string) (int, escalateResponse, string) {
	t.Helper()
	rec := doRequest(sellerRouter(h), http.MethodPatch,
		"/api/v1/seller/orders/"+orderID+"/escalate", s.token, nil)
	var out escalateResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out, rec.Body.String()
}

func dispatchLinkage(t *testing.T, orderID string) (provider, deliveryID, tracking string, fee int) {
	t.Helper()
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COALESCE(external_provider,''), COALESCE(external_delivery_id,''),
		        COALESCE(external_tracking_url,''), COALESCE(provider_fee_cents,0)
		   FROM orders WHERE id = $1`, orderID).Scan(&provider, &deliveryID, &tracking, &fee); err != nil {
		t.Fatalf("read linkage: %v", err)
	}
	return
}

// An escalation runs the same cheapest-wins auction checkout ran, buys from the
// winner, and records the linkage the webhooks and the customer's tracking both
// key on. A response that reports a provider without a persisted linkage is the
// orphaned-paid-delivery failure, so both are asserted together.
func TestIntegration_EscalateBuysFromTheCheapestProvider(t *testing.T) {
	cases := []struct {
		name         string
		uberFee      int
		ddFee        int
		shipdayFee   float64
		wantProvider string
		wantFee      int
		wantDelivery string
	}{
		{"uber cheapest", 599, 975, 12.99, "uber_direct", 599, "del_uber_1"},
		{"doordash cheapest", 1299, 875, 12.99, "doordash_drive", 875, ""}, // dd echoes our order id
		{"shipday cheapest", 1299, 975, 6.25, "shipday", 625, "9911"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h, net := withDispatcher(t, true, true, true)
			s := dispatchableSeller(t)
			id := s.order(t, "ready", func(id string) {
				mustExec(t, `UPDATE orders SET delivery_mode = 'restaurant' WHERE id = $1`, id)
			})

			net.on(uberQuotePath, 200, fmt.Sprintf(`{"id":"q","fee":%d,"duration":25}`, tc.uberFee))
			net.on(ddQuotePath, 200, fmt.Sprintf(`{"external_delivery_id":"q","fee":%d}`, tc.ddFee))
			net.on(shipdayQuotePath, 200, fmt.Sprintf(
				`[{"id":"e","name":"DoorDash","fee":%v,"pickupDuration":10,"deliveryDuration":15}]`, tc.shipdayFee))

			net.on(uberCreatePath, 200, fmt.Sprintf(
				`{"id":"del_uber_1","tracking_url":"https://track/uber","fee":%d,"status":"pending"}`, tc.uberFee))
			net.on(ddCreatePath, 200, fmt.Sprintf(
				`{"external_delivery_id":%q,"tracking_url":"https://track/dd","fee":%d,"delivery_status":"created"}`, id, tc.ddFee))
			net.on(shipdayInsertPath, 200, `{"success":true,"orderId":9911}`)
			net.on(shipdayAssignPath, 200, fmt.Sprintf(
				`{"orderId":9911,"thirdPartyName":"DoorDash","trackingUrl":"https://track/sd","totalBillableAmount":%v,"status":"ASSIGNED"}`,
				tc.shipdayFee))

			code, body, raw := escalate(t, s, h, id)
			if code != http.StatusOK {
				t.Fatalf("status %d, want 200 (body %s)", code, raw)
			}
			if body.Provider != tc.wantProvider {
				t.Errorf("response provider = %q, want %q — the escalation must buy from the auction winner",
					body.Provider, tc.wantProvider)
			}

			provider, deliveryID, tracking, fee := dispatchLinkage(t, id)
			if provider != tc.wantProvider {
				t.Errorf("orders.external_provider = %q, want %q", provider, tc.wantProvider)
			}
			wantDelivery := tc.wantDelivery
			if wantDelivery == "" {
				wantDelivery = id
			}
			if deliveryID != wantDelivery {
				t.Errorf("orders.external_delivery_id = %q, want %q — without it the webhooks cannot "+
					"bind and the paid delivery is orphaned", deliveryID, wantDelivery)
			}
			if deliveryID != body.DeliveryID {
				t.Errorf("response delivery_id %q disagrees with the stored %q", body.DeliveryID, deliveryID)
			}
			if tracking == "" || body.TrackingURL != tracking {
				t.Errorf("tracking url: stored %q, response %q — the consumer sees the response", tracking, body.TrackingURL)
			}
			if fee != tc.wantFee {
				t.Errorf("provider_fee_cents = %d, want %d — this is the cost accounting reconciles against", fee, tc.wantFee)
			}
		})
	}
}

// How a failed escalation is reported back decides what the seller does next.
// A permanent provider rejection can never succeed, so it must not look
// retryable; an outage must.
func TestIntegration_EscalateFailureClassification(t *testing.T) {
	cases := []struct {
		name       string
		quoteCode  int
		quoteBody  string
		wantStatus int
		why        string
	}{
		{
			name: "a validation 4xx is permanent", quoteCode: 400,
			quoteBody:  `{"code":"invalid_params","message":"pickup address is not serviceable"}`,
			wantStatus: http.StatusUnprocessableEntity,
			why:        "retrying cannot fix bad order data — the seller needs the actionable cause, not 'try again'",
		},
		{
			name: "a 5xx outage is transient", quoteCode: 503,
			quoteBody:  `{"message":"service unavailable"}`,
			wantStatus: http.StatusBadGateway,
			why:        "a retry seconds later may well succeed, so this must not be reported as permanent",
		},
		{
			name: "a 429 rate limit is transient", quoteCode: 429,
			quoteBody:  `{"message":"slow down"}`,
			wantStatus: http.StatusBadGateway,
			why:        "rate limiting says nothing about this order's data",
		},
		{
			name: "a 401 credential problem is transient", quoteCode: 401,
			quoteBody:  `{"message":"unauthorized"}`,
			wantStatus: http.StatusBadGateway,
			why:        "an account-level hiccup must not be reported to the seller as an undeliverable order",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h, net := withDispatcher(t, true, false, false)
			s := dispatchableSeller(t)
			id := s.order(t, "ready", func(id string) {
				mustExec(t, `UPDATE orders SET delivery_mode = 'restaurant' WHERE id = $1`, id)
			})
			net.on(uberQuotePath, tc.quoteCode, tc.quoteBody)

			code, _, raw := escalate(t, s, h, id)
			if code != tc.wantStatus {
				t.Fatalf("status %d, want %d — %s (body %s)", code, tc.wantStatus, tc.why, raw)
			}

			// However it failed, the claim must be released and the order left
			// exactly as the seller sees it, or the sentinel strands the row until
			// the 10-minute reaper.
			provider, deliveryID, _, _ := dispatchLinkage(t, id)
			if provider != "" || deliveryID != "" {
				t.Errorf("linkage after a failed escalation = (%q, %q), want empty", provider, deliveryID)
			}
			if got := statusOf(t, id); got != "ready" {
				t.Errorf("status = %q, want ready", got)
			}
			// A human-gated one-shot must not spend the shared retry budget: each
			// failed tap counting an attempt would cap-block the order out of the
			// automatic sweep with no fallback left.
			if got := attemptsOf(t, id); got != 0 {
				t.Errorf("external_dispatch_attempts = %d after a seller escalation, want 0", got)
			}
		})
	}
}

// A second tap while the first is still in flight must not buy a second paid
// courier. The claim CAS answers "already claimed", which the handler reports
// as 409 rather than dispatching again.
func TestIntegration_EscalateOnAClaimedOrderIsAConflict(t *testing.T) {
	h, net := withDispatcher(t, true, false, false)
	s := dispatchableSeller(t)
	id := s.order(t, "ready", func(id string) {
		mustExec(t, `UPDATE orders SET delivery_mode = 'restaurant', external_provider = 'dispatching' WHERE id = $1`, id)
	})
	quotes := net.counter(uberQuotePath)
	net.on(uberQuotePath, 200, `{"id":"q","fee":599,"duration":25}`)
	net.on(uberCreatePath, 200, `{"id":"del_x","tracking_url":"https://t","fee":599,"status":"pending"}`)

	code, _, raw := escalate(t, s, h, id)
	if code != http.StatusConflict {
		t.Fatalf("status %d, want 409 (body %s)", code, raw)
	}
	if *quotes != 0 {
		t.Errorf("the provider was quoted %d time(s) for an order already being dispatched — "+
			"the claim must be lost before any provider call", *quotes)
	}
	if provider, deliveryID, _, _ := dispatchLinkage(t, id); provider != "dispatching" || deliveryID != "" {
		t.Errorf("the in-flight claim was disturbed: provider=%q delivery=%q", provider, deliveryID)
	}
}
