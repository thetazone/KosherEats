package pos

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/models"
)

// Registry is a per-process map of provider → adapter. Populated at startup
// by NewRegistry(); read-only after that, so no mutex.
type Registry struct {
	adapters map[Provider]Adapter
	db       *pgxpool.Pool
}

// NewRegistry builds the dispatch map. Pass in the adapters you've
// constructed (typically one per supported POS). The Clover adapter is the
// only one wired today; Square/Toast slot in here when their packages exist.
func NewRegistry(db *pgxpool.Pool, adapters ...Adapter) *Registry {
	m := make(map[Provider]Adapter, len(adapters))
	for _, a := range adapters {
		m[a.Provider()] = a
	}
	return &Registry{adapters: m, db: db}
}

// PushToConnectedPOS is the top-level entry point used by handlers. Loads
// the restaurant's active POS integration (if any), decrypts the token,
// finds the right adapter, and pushes the order. Designed to be called
// inside a `go ...` so callers don't block on a third-party API.
//
// Returns ErrNoIntegration when the restaurant hasn't connected anything —
// callers should treat this as a no-op, not an error.
func (r *Registry) PushToConnectedPOS(ctx context.Context, restaurantID string, order *models.Order) error {
	integ, err := r.loadActiveIntegration(ctx, restaurantID)
	if err != nil {
		return err
	}
	adapter, ok := r.adapters[integ.Provider]
	if !ok {
		slog.Warn("pos: unknown provider on integration row",
			slog.String("restaurant_id", restaurantID),
			slog.String("provider", string(integ.Provider)))
		return ErrUnknownProvider
	}
	if err := adapter.PushOrder(ctx, integ, order); err != nil {
		return fmt.Errorf("%s push: %w", integ.Provider, err)
	}
	if _, err := r.db.Exec(ctx,
		`UPDATE restaurant_pos_integrations SET last_used_at = NOW(), updated_at = NOW() WHERE id = $1`,
		integ.ID,
	); err != nil {
		slog.Warn("pos: last_used_at bump failed",
			slog.String("integration_id", integ.ID), slog.String("error", err.Error()))
	}
	return nil
}

// AdapterFor returns the adapter for a specific provider, used by the
// OAuth callback handlers and the test-print endpoint.
func (r *Registry) AdapterFor(p Provider) (Adapter, bool) {
	a, ok := r.adapters[p]
	return a, ok
}

func (r *Registry) loadActiveIntegration(ctx context.Context, restaurantID string) (Integration, error) {
	var (
		integ                 Integration
		accessEnc, refreshEnc []byte
	)
	row := r.db.QueryRow(ctx,
		`SELECT id, restaurant_id, provider, merchant_id,
		        access_token, refresh_token, expires_at, is_active, last_used_at
		   FROM restaurant_pos_integrations
		  WHERE restaurant_id = $1 AND is_active = true
		  LIMIT 1`, restaurantID)
	if err := row.Scan(
		&integ.ID, &integ.RestaurantID, &integ.Provider, &integ.MerchantID,
		&accessEnc, &refreshEnc, &integ.ExpiresAt, &integ.IsActive, &integ.LastUsedAt,
	); err != nil {
		return integ, ErrNoIntegration
	}
	at, err := Decrypt(accessEnc)
	if err != nil {
		return integ, fmt.Errorf("decrypt access token: %w", err)
	}
	integ.AccessToken = string(at)
	if len(refreshEnc) > 0 {
		rt, err := Decrypt(refreshEnc)
		if err != nil {
			return integ, fmt.Errorf("decrypt refresh token: %w", err)
		}
		integ.RefreshToken = string(rt)
	}
	return integ, nil
}
