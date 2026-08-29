package handlers

// Consumer pushes fired by the three provider webhooks.
//
// Every mutating statement in these handlers is scoped to
// `external_provider = '<this provider>'` so an event authenticated as one
// provider cannot touch an order out with another. The PUSH that follows the
// mutation has to obey the same scoping, and it is easy to get wrong because
// the consumer lookup ("who do I notify?") is keyed on the order id alone.
// Firing it on a 0-row update sends "your driver just picked up your food" to
// the wrong customer, or a second time to the right one.
//
// Observing a push needs a seam: notify.Notifier takes pushes no further than
// APNs/FCM, both of which are inert in the harness. But it reaches every
// recipient through its *pgxpool.Pool — OrderPickedUp/OrderDelivered start by
// reading notification_preferences — so a pool with a pgx QueryTracer records
// exactly which pushes were attempted.
//
// SAFETY: no provider network. The tracer pool is a second connection to the
// same local test Postgres; APNs/FCM stay in stub mode.

import (
	"context"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/notify"
)

// pushTracer records every SQL statement the Notifier issues.
type pushTracer struct {
	mu  sync.Mutex
	sql []string
}

func (p *pushTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, data pgx.TraceQueryStartData) context.Context {
	p.mu.Lock()
	p.sql = append(p.sql, data.SQL)
	p.mu.Unlock()
	return ctx
}

func (p *pushTracer) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// attempts counts the pushes aimed at consumers: every consumer-facing
// Notifier method gates on the notification_preferences read first, so one
// occurrence is one attempted push.
func (p *pushTracer) attempts() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	var n int
	for _, s := range p.sql {
		if strings.Contains(s, "FROM notification_preferences") {
			n++
		}
	}
	return n
}

func (p *pushTracer) reset() {
	p.mu.Lock()
	p.sql = nil
	p.mu.Unlock()
}

// withPushTracer swaps the handler's Notifier for one whose pool is traced,
// restoring the original afterwards.
func withPushTracer(t *testing.T) (*Handler, *pushTracer) {
	t.Helper()
	cfg, err := pgxpool.ParseConfig(testDatabaseURL())
	if err != nil {
		t.Fatalf("parse test database url: %v", err)
	}
	tracer := &pushTracer{}
	cfg.ConnConfig.Tracer = tracer
	cfg.MaxConns = 2
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("open traced pool: %v", err)
	}
	t.Cleanup(pool.Close)

	h := harness.h
	orig := h.notify
	t.Cleanup(func() { h.notify = orig })
	// APNs/FCM are unconfigured in the harness, so nothing leaves the process
	// even when a push IS (correctly) attempted.
	h.notify = notify.NewNotifier(pool, notify.New(h.cfg), notify.NewFCM(h.cfg))
	return h, tracer
}

// A pickup event must push only when it actually moved the order to picked_up.
// The 0-row cases are the dangerous ones: the consumer lookup behind the push is
// keyed on the order id alone, so an event that the provider scoping or the
// status guard rejected would otherwise notify the wrong consumer — or the right
// one twice.
func TestIntegration_PickupPushOnlyFiresWhenThePickupLands(t *testing.T) {
	tests := []struct {
		name string
		// how the order is already dispatched
		orderProvider string
		orderDelivery string
		orderStatus   string
		post          func(t *testing.T, h *Handler, o webhookOrder) int
		wantPushes    int
		why           string
	}{
		{
			name:          "uber pickup on its own order notifies once",
			orderProvider: "uber_direct", orderDelivery: "d-uber-1", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"pickup_complete","external_id":"`+o.id+`"}}`).Code
			},
			wantPushes: 1,
			why:        "the order really was picked up",
		},
		{
			name:          "doordash pickup on its own order notifies once",
			orderProvider: "doordash_drive", orderDelivery: "d-dd-1", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_PICKED_UP","external_delivery_id":"`+o.id+`"}`).Code
			},
			wantPushes: 1,
			why:        "the order really was picked up",
		},
		{
			name:          "shipday pickup on its own order notifies once",
			orderProvider: "shipday", orderDelivery: "4242", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postShipdayWebhook(t, h, `{"event":"ORDER_PIKEDUP","order":{"id":4242,"order_number":"`+o.id+`"}}`).Code
			},
			wantPushes: 1,
			why:        "the order really was picked up",
		},
		{
			name:          "uber pickup naming a shipday order notifies nobody",
			orderProvider: "shipday", orderDelivery: "4242", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-9",`+
					`"data":{"status":"pickup_complete","external_id":"`+o.id+`"}}`).Code
			},
			wantPushes: 0,
			why:        "an Uber-authenticated event must not push about an order out with Shipday",
		},
		{
			name:          "doordash pickup naming an uber order notifies nobody",
			orderProvider: "uber_direct", orderDelivery: "d-uber-1", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_PICKED_UP","external_delivery_id":"`+o.id+`"}`).Code
			},
			wantPushes: 0,
			why:        "a DoorDash-authenticated event must not push about an order out with Uber",
		},
		{
			name:          "shipday pickup naming a doordash order notifies nobody",
			orderProvider: "doordash_drive", orderDelivery: "d-dd-1", orderStatus: "ready",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postShipdayWebhook(t, h, `{"event":"ORDER_PIKEDUP","order":{"id":4242,"order_number":"`+o.id+`"}}`).Code
			},
			wantPushes: 0,
			why:        "a Shipday-authenticated event must not push about an order out with DoorDash",
		},
		{
			name:          "uber pickup on an already-delivered order notifies nobody",
			orderProvider: "uber_direct", orderDelivery: "d-uber-1", orderStatus: "delivered",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"pickup_complete","external_id":"`+o.id+`"}}`).Code
			},
			wantPushes: 0,
			why:        "a late pickup event must not re-push on a terminal order",
		},
		{
			name:          "doordash pickup on an already-delivered order notifies nobody",
			orderProvider: "doordash_drive", orderDelivery: "d-dd-1", orderStatus: "delivered",
			post: func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_PICKED_UP","external_delivery_id":"`+o.id+`"}`).Code
			},
			wantPushes: 0,
			why:        "a late pickup event must not re-push on a terminal order",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			withProviderClients(t)
			h, tracer := withPushTracer(t)
			clearWebhookLedger(t)
			o := seedDispatchedOrder(t, tc.orderStatus, tc.orderProvider, tc.orderDelivery)

			tracer.reset()
			if code := tc.post(t, h, o); code != 200 {
				t.Fatalf("status %d, want 200", code)
			}
			if got := tracer.attempts(); got != tc.wantPushes {
				t.Errorf("%d consumer push(es) attempted, want %d — %s", got, tc.wantPushes, tc.why)
			}
		})
	}
}

// The same rule for the terminal event: a 'delivered' that changed nothing must
// not tell the customer their food arrived.
func TestIntegration_DeliveredPushOnlyFiresWhenTheDeliveryLands(t *testing.T) {
	tests := []struct {
		name          string
		orderProvider string
		orderDelivery string
		orderStatus   string
		post          func(t *testing.T, h *Handler, o webhookOrder) int
		wantPushes    int
	}{
		{
			"uber delivered on its own order", "uber_direct", "d-uber-1", "picked_up",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"delivered","external_id":"`+o.id+`"}}`).Code
			}, 1,
		},
		{
			"uber delivered naming a doordash order", "doordash_drive", "d-dd-1", "picked_up",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"delivered","external_id":"`+o.id+`"}}`).Code
			}, 0,
		},
		{
			"doordash delivered naming a shipday order", "shipday", "4242", "picked_up",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_DROPPED_OFF","external_delivery_id":"`+o.id+`"}`).Code
			}, 0,
		},
		{
			"shipday completed naming an uber order", "uber_direct", "d-uber-1", "picked_up",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postShipdayWebhook(t, h, `{"event":"ORDER_COMPLETED","order":{"id":4242,"order_number":"`+o.id+`"}}`).Code
			}, 0,
		},
		{
			"uber delivered on an already-delivered order", "uber_direct", "d-uber-1", "delivered",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"delivered","external_id":"`+o.id+`"}}`).Code
			}, 0,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			withProviderClients(t)
			h, tracer := withPushTracer(t)
			clearWebhookLedger(t)
			o := seedDispatchedOrder(t, tc.orderStatus, tc.orderProvider, tc.orderDelivery)

			tracer.reset()
			if code := tc.post(t, h, o); code != 200 {
				t.Fatalf("status %d, want 200", code)
			}
			if got := tracer.attempts(); got != tc.wantPushes {
				t.Errorf("%d consumer push(es) attempted, want %d", got, tc.wantPushes)
			}
		})
	}
}

// The "a courier is assigned" push is a pure notification with no state change,
// so its ONLY guard is the provider-scoped consumer lookup. Pin it: a
// cross-provider assignment event must reach nobody.
func TestIntegration_CourierAssignedPushIsProviderScoped(t *testing.T) {
	tests := []struct {
		name          string
		orderProvider string
		orderDelivery string
		post          func(t *testing.T, h *Handler, o webhookOrder) int
		wantPushes    int
	}{
		{
			"uber pickup event on its own order", "uber_direct", "d-uber-1",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"pickup","external_id":"`+o.id+`","courier":{"name":"Dan"}}}`).Code
			}, 1,
		},
		{
			"uber pickup event on a shipday order", "shipday", "4242",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postUberWebhook(t, h, `{"kind":"event.delivery_status","delivery_id":"d-uber-1",`+
					`"data":{"status":"pickup","external_id":"`+o.id+`","courier":{"name":"Dan"}}}`).Code
			}, 0,
		},
		{
			"doordash dasher-confirmed on its own order", "doordash_drive", "d-dd-1",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_CONFIRMED","external_delivery_id":"`+o.id+`"}`).Code
			}, 1,
		},
		{
			"doordash dasher-confirmed on an uber order", "uber_direct", "d-uber-1",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postDoorDashWebhook(t, h, `{"event_name":"DASHER_CONFIRMED","external_delivery_id":"`+o.id+`"}`).Code
			}, 0,
		},
		{
			"shipday assigned on its own order", "shipday", "4242",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postShipdayWebhook(t, h,
					`{"event":"ORDER_ASSIGNED","order":{"id":4242,"order_number":"`+o.id+`"},"carrier":{"name":"Dan"}}`).Code
			}, 1,
		},
		{
			"shipday assigned on a doordash order", "doordash_drive", "d-dd-1",
			func(t *testing.T, h *Handler, o webhookOrder) int {
				return postShipdayWebhook(t, h,
					`{"event":"ORDER_ASSIGNED","order":{"id":4242,"order_number":"`+o.id+`"},"carrier":{"name":"Dan"}}`).Code
			}, 0,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			withProviderClients(t)
			h, tracer := withPushTracer(t)
			clearWebhookLedger(t)
			o := seedDispatchedOrder(t, "ready", tc.orderProvider, tc.orderDelivery)

			tracer.reset()
			if code := tc.post(t, h, o); code != 200 {
				t.Fatalf("status %d, want 200", code)
			}
			if got := tracer.attempts(); got != tc.wantPushes {
				t.Errorf("%d consumer push(es) attempted, want %d", got, tc.wantPushes)
			}
		})
	}
}
