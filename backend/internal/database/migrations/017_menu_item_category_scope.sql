-- Enforce that every menu item points at a category from the same restaurant.
-- Added as NOT VALID so deployment doesn't fail if legacy rows need cleanup;
-- PostgreSQL still enforces the constraint for all new writes immediately.
ALTER TABLE menu_categories
    ADD CONSTRAINT menu_categories_restaurant_id_id_key UNIQUE (restaurant_id, id);

ALTER TABLE menu_items
    ADD CONSTRAINT menu_items_restaurant_category_fkey
    FOREIGN KEY (restaurant_id, category_id)
    REFERENCES menu_categories(restaurant_id, id)
    ON DELETE CASCADE
    NOT VALID;
