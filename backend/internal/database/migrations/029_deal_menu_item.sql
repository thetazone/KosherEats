ALTER TABLE deals ADD COLUMN IF NOT EXISTS menu_item_id UUID;

DO $$ BEGIN
    ALTER TABLE deals
        ADD CONSTRAINT deals_menu_item_fk
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE SET NULL;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_deals_menu_item ON deals(menu_item_id) WHERE menu_item_id IS NOT NULL;
