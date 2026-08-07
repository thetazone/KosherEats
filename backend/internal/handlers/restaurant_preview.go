package handlers

import (
	"context"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/koshereats/backend/internal/models"
)

// Preview listings: agency-certified restaurants seeded before their owners
// onboard. Browsable (behind ?include_previews=1, sent only by client builds
// that know how to render them), never orderable. The consumer-facing state
// deliberately mirrors a closed restaurant, with "Request restaurant" instead
// of a cart.
//
// Orderability is a server property, not a UI one. AddToCart, CreateOrder and
// CreatePaymentIntent each re-check restaurantOrderable — a stale client, a
// replayed request, or curl gets a 4xx, not a paid order at a restaurant that
// has never heard of us.

// restaurantOrderableSQL is the single definition of "money may move against
// this restaurant". Standard visibility + active + approved; a preview row can
// never satisfy it no matter what its other flags say.
const restaurantOrderableSQL = `
	SELECT (is_active AND approval_status = 'approved' AND listing_visibility = 'standard')
	  FROM restaurants WHERE id = $1`

// restaurantOrderable reports whether orders/payments may proceed against the
// restaurant. Unknown id → (false, nil): callers uniformly reject.
func (h *Handler) restaurantOrderable(ctx context.Context, restaurantID string) (bool, error) {
	var ok bool
	err := h.db.Pool.QueryRow(ctx, restaurantOrderableSQL, restaurantID).Scan(&ok)
	if err != nil {
		if err.Error() == "no rows in result set" {
			return false, nil
		}
		return false, err
	}
	return ok, nil
}

// includePreviews reports whether the client asked to see preview listings.
// Opt-in by query param on purpose: already-shipped app builds don't send it,
// so they keep seeing exactly today's orderable-only feed and can never render
// a preview restaurant as a normal one.
func includePreviews(r *http.Request) bool {
	switch r.URL.Query().Get("include_previews") {
	case "1", "true", "yes":
		return true
	}
	return false
}

// optionalUserID returns the authenticated user's id or nil under
// OptionalAuthMiddleware. Feeding nil into `rr.user_id = $2` makes the
// requested_by_me EXISTS evaluate false, which is the right answer for
// anonymous browsers.
func optionalUserID(r *http.Request) *string {
	user, err := getUserFromContext(r)
	if err != nil {
		return nil
	}
	if id, ok := user["user_id"]; ok && id != "" {
		return &id
	}
	return nil
}

// decorateRestaurantListings stamps the preview fields (orderable,
// listing_visibility, request_count, requested_by_me) onto already-scanned
// restaurants in ONE extra query, keyed by id.
//
// Deliberately a second query rather than wider SELECTs: scanRestaurants'
// 34-column list is shared by eight consumer/seller/admin call sites, and
// widening it means touching every one. Decoration keeps the change local to
// the consumer handlers that need it. ≤50 rows per response makes the extra
// round trip irrelevant.
func (h *Handler) decorateRestaurantListings(ctx context.Context, rs []models.Restaurant, userID *string) []models.Restaurant {
	if len(rs) == 0 {
		return rs
	}
	ids := make([]string, len(rs))
	for i := range rs {
		ids[i] = rs[i].ID
	}
	rows, err := h.db.Pool.Query(ctx, `
		SELECT r.id, r.listing_visibility,
		       (r.is_active AND r.approval_status = 'approved' AND r.listing_visibility = 'standard') AS orderable,
		       (SELECT COUNT(*) FROM restaurant_requests rr WHERE rr.restaurant_id = r.id) AS request_count,
		       EXISTS (SELECT 1 FROM restaurant_requests rr2
		                WHERE rr2.restaurant_id = r.id AND rr2.user_id = $2) AS requested
		  FROM restaurants r WHERE r.id = ANY($1)`, ids, userID)
	if err != nil {
		// Decoration is additive metadata; a failure here must not take down the
		// feed. Orderable=false is the safe default for every row.
		return rs
	}
	defer rows.Close()

	type deco struct {
		visibility string
		orderable  bool
		count      int
		requested  bool
	}
	byID := make(map[string]deco, len(rs))
	for rows.Next() {
		var id string
		var d deco
		if err := rows.Scan(&id, &d.visibility, &d.orderable, &d.count, &d.requested); err != nil {
			continue
		}
		byID[id] = d
	}
	for i := range rs {
		if d, ok := byID[rs[i].ID]; ok {
			rs[i].ListingVisibility = d.visibility
			rs[i].Orderable = d.orderable
			rs[i].RequestCount = d.count
			rs[i].RequestedByMe = d.requested
		}
	}
	return rs
}

// ToggleRestaurantRequest flips the calling user's "Request restaurant" state
// on a PREVIEW listing and returns the new state + total count. It is a
// toggle so the heart behaves like a like button; the (restaurant_id, user_id)
// primary key makes double-taps idempotent.
//
// Restricted to previews: a request against a live restaurant is meaningless
// (you can just order), and keeping the table preview-only keeps its meaning
// crisp — it is the demand signal for restaurants we don't have yet.
func (h *Handler) ToggleRestaurantRequest(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "authentication required")
		return
	}
	id := chi.URLParam(r, "id")
	vertical := verticalFromRequest(r)

	var isPreview bool
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT listing_visibility = 'preview' FROM restaurants
		  WHERE id = $1 AND vertical = $2`, id, vertical).Scan(&isPreview); err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	if !isPreview {
		writeError(w, http.StatusBadRequest, "restaurant is already live on KosherEats — you can order from it")
		return
	}

	tag, err := h.db.Pool.Exec(r.Context(),
		`DELETE FROM restaurant_requests WHERE restaurant_id = $1 AND user_id = $2`,
		id, user["user_id"])
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update request")
		return
	}
	requested := false
	if tag.RowsAffected() == 0 {
		if _, err := h.db.Pool.Exec(r.Context(),
			`INSERT INTO restaurant_requests (restaurant_id, user_id) VALUES ($1, $2)
			 ON CONFLICT DO NOTHING`, id, user["user_id"]); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to update request")
			return
		}
		requested = true
	}

	var count int
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT COUNT(*) FROM restaurant_requests WHERE restaurant_id = $1`, id).Scan(&count); err != nil {
		count = 0
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"requested":     requested,
		"request_count": count,
	})
}
