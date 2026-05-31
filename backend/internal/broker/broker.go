// Package broker is an in-memory pub/sub for order-scoped courier location
// events. Consumer SSE streams subscribe per order id; the courier location
// handler publishes each ping. Single-node only — if we ever scale horizontally
// this needs to move to Redis pub/sub.
package broker

import (
	"sync"
	"time"
)

type LocationEvent struct {
	OrderID string    `json:"order_id"`
	Lat     float64   `json:"lat"`
	Lng     float64   `json:"lng"`
	Heading float64   `json:"heading"`
	Speed   float64   `json:"speed"`
	At      time.Time `json:"at"`
}

type Broker struct {
	mu   sync.RWMutex
	subs map[string]map[chan LocationEvent]struct{}
}

func New() *Broker {
	return &Broker{subs: make(map[string]map[chan LocationEvent]struct{})}
}

// Subscribe returns a receive channel for the given order id plus an
// unsubscribe func the caller must defer. Channel is buffered so a slow
// consumer drops older events instead of blocking the publisher.
func (b *Broker) Subscribe(orderID string) (<-chan LocationEvent, func()) {
	ch := make(chan LocationEvent, 8)
	b.mu.Lock()
	if _, ok := b.subs[orderID]; !ok {
		b.subs[orderID] = map[chan LocationEvent]struct{}{}
	}
	b.subs[orderID][ch] = struct{}{}
	b.mu.Unlock()

	return ch, func() {
		b.mu.Lock()
		if m, ok := b.subs[orderID]; ok {
			delete(m, ch)
			if len(m) == 0 {
				delete(b.subs, orderID)
			}
		}
		b.mu.Unlock()
		// Don't close(ch) — Publish may still hold a reference obtained
		// before the lock. Subscribers use select (not range), so closing
		// is unnecessary; GC will collect the unreferenced channel.
	}
}

// Publish fans out an event to every subscriber of the order. Non-blocking:
// if a subscriber's buffer is full we drop the event for that subscriber so
// a stalled consumer can never block the courier location write path.
func (b *Broker) Publish(e LocationEvent) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	for ch := range b.subs[e.OrderID] {
		select {
		case ch <- e:
		default:
		}
	}
}
