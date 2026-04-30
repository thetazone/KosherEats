-- Restaurant favorites (consumer bookmarks)
CREATE TABLE IF NOT EXISTS restaurant_favorites (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, restaurant_id)
);

-- Delivery proof photo URL (courier uploads on drop-off)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_proof_url TEXT;
