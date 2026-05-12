// Package pos abstracts third-party POS integrations (Clover, eventually
// Square + Toast) behind a single Adapter interface. The order-accept handler
// just calls PushToConnectedPOS — it doesn't know or care which POS is wired
// up for a given restaurant.
//
// Adding a new POS = implement Adapter in a subpackage, register it in
// registry.go, expose its OAuth endpoints alongside Clover's. No call sites
// change in the rest of the codebase.
package pos

import (
	"context"
	"errors"
	"time"

	"github.com/koshereats/backend/internal/models"
)

// Provider identifies which POS a given integration row points at. Matches
// the CHECK constraint on restaurant_pos_integrations.provider.
type Provider string

const (
	ProviderClover Provider = "clover"
	ProviderSquare Provider = "square"
	ProviderToast  Provider = "toast"
)

// Integration is the in-memory shape of a row from
// restaurant_pos_integrations after the access/refresh tokens have been
// decrypted. Adapters operate on this, never the raw DB row.
type Integration struct {
	ID           string
	RestaurantID string
	Provider     Provider
	MerchantID   string
	AccessToken  string
	RefreshToken string
	ExpiresAt    *time.Time
	IsActive     bool
	LastUsedAt   *time.Time
}

// Adapter is what each POS integration implements. PushOrder is the
// fire-and-forget call made from the order-accept handler; TestConnection
// is used by the "Test print" button in the seller app to verify creds.
type Adapter interface {
	Provider() Provider
	PushOrder(ctx context.Context, integ Integration, order *models.Order) error
	TestConnection(ctx context.Context, integ Integration) error
}

// ErrUnknownProvider is returned by Get when no adapter is registered for
// the requested provider. Callers should log + skip (the integration row
// likely predates a since-removed adapter).
var ErrUnknownProvider = errors.New("pos: no adapter registered for provider")

// ErrNoIntegration means the restaurant has no active POS integration. The
// order-accept hook treats this as a no-op, not an error.
var ErrNoIntegration = errors.New("pos: no active integration for restaurant")
