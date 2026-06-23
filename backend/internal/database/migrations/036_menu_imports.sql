-- Self-serve menu import jobs. During onboarding a seller pastes their UberEats
-- store URL; one row here tracks the async fetch+import for their restaurant.
-- The scrape itself runs out-of-process on a residential browser node (UberEats
-- blocks datacenter IPs), which drains pending rows, writes menu_items, and
-- updates status/progress on this row. Fire-and-forget: a stuck 'running' row
-- can be reset to 'pending' to retry.
CREATE TABLE IF NOT EXISTS menu_imports (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    source        TEXT NOT NULL DEFAULT 'ubereats' CHECK (source IN ('ubereats')),
    source_url    TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','running','done','failed')),
    items_total   INTEGER NOT NULL DEFAULT 0,
    items_created INTEGER NOT NULL DEFAULT 0,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_menu_imports_restaurant
    ON menu_imports (restaurant_id);

-- The drain worker pulls oldest-pending first.
CREATE INDEX IF NOT EXISTS idx_menu_imports_status
    ON menu_imports (status, created_at);
