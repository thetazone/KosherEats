-- Consumer ratings of their courier after a delivery. One per order so a
-- consumer can't spam a single delivery, and the courier row's aggregate
-- rating column gets recomputed on each insert.

CREATE TABLE IF NOT EXISTS courier_ratings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    consumer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    courier_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stars       SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_courier_ratings_courier_id ON courier_ratings(courier_id);
