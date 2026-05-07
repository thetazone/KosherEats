CREATE TABLE IF NOT EXISTS deals (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    discount_type TEXT NOT NULL CHECK (discount_type IN ('percentage', 'fixed', 'bogo')),
    discount_value INTEGER NOT NULL DEFAULT 0,
    min_order_amount INTEGER,
    starts_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_deals_restaurant_id ON deals(restaurant_id);
CREATE INDEX IF NOT EXISTS idx_deals_active_expires ON deals(is_active, expires_at) WHERE is_active = true;

COMMENT ON TABLE deals IS 'Seller-posted limited-time deals. Consumer app shows active, non-expired deals.';
COMMENT ON COLUMN deals.discount_type IS 'percentage = % off, fixed = cents off, bogo = buy-one-get-one';
COMMENT ON COLUMN deals.discount_value IS 'For percentage: 1-100. For fixed: amount in cents. Ignored for bogo.';
COMMENT ON COLUMN deals.min_order_amount IS 'Minimum order total (cents) to qualify. NULL = no minimum.';
