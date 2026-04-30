-- The AddToCart handler's atomic upsert query relies on a unique index on
-- (cart_id, menu_item_id, selected_modifiers) so the ON CONFLICT clause has
-- something to match. Without it, the INSERT fails with
-- "no unique or exclusion constraint matching the ON CONFLICT specification"
-- and every add-to-cart request returns 500.
--
-- jsonb is indexable directly; the natural key is "same item, same chosen
-- modifiers within the same cart" — bumping quantity rather than creating a
-- duplicate row when the user taps Add a second time with identical options.
CREATE UNIQUE INDEX IF NOT EXISTS idx_cart_items_cart_item_mods
    ON cart_items (cart_id, menu_item_id, selected_modifiers);
