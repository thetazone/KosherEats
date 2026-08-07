-- Preview listings: restaurants that are browsable but not orderable.
--
-- The catalog-growth strategy lists agency-certified restaurants before their
-- owners have onboarded: the consumer can open the page and tap "Request
-- restaurant", but there is no cart, no checkout, and no payment path. The
-- consumer-visible state mirrors a closed restaurant.
--
--   listing_visibility:
--     'standard' - governed entirely by the existing flags
--                  (is_active + approval_status), i.e. today's behavior.
--     'preview'  - visible to consumers who opt in (?include_previews=1),
--                  NEVER orderable regardless of the other flags. Orderability
--                  is enforced server-side in AddToCart / CreateOrder /
--                  CreatePaymentIntent, not just hidden in the UI.
--
--   listing_priority: sort weight within previews. The operator-approved
--   subset (APPROVALS.md ticks) gets 1 so it surfaces above the long tail.
--
-- Preview rows are seeded with owner_id NULL, approval_status 'pending',
-- is_active false, and an EMPTY kosher_certification: a hechsher claim is a
-- kashrut assertion and is only displayed once verified at activation.
--
-- restaurant_requests is the demand signal: one row per (restaurant, user),
-- toggled by POST /restaurants/{id}/request. Counts are shown in-app and are
-- the outreach lever ("N people near you requested this restaurant").

ALTER TABLE restaurants
    ADD COLUMN IF NOT EXISTS listing_visibility TEXT NOT NULL DEFAULT 'standard'
        CHECK (listing_visibility IN ('standard', 'preview')),
    ADD COLUMN IF NOT EXISTS listing_priority INT NOT NULL DEFAULT 0;

-- The consumer feed now sorts orderable-first and filters previews by flag.
CREATE INDEX IF NOT EXISTS idx_restaurants_listing
    ON restaurants (vertical, listing_visibility);

CREATE TABLE IF NOT EXISTS restaurant_requests (
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (restaurant_id, user_id)
);

-- Count-per-restaurant is the hot read (feed decoration + outreach reporting).
CREATE INDEX IF NOT EXISTS idx_restaurant_requests_restaurant
    ON restaurant_requests (restaurant_id);
