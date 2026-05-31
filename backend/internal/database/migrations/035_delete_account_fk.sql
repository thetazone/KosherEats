-- Fix FK violations when deleting a user account.
--
-- restaurants.owner_id: make nullable, add ON DELETE SET NULL, and
-- deactivate the restaurant when the owner deletes their account.
ALTER TABLE restaurants ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE restaurants DROP CONSTRAINT IF EXISTS restaurants_owner_id_fkey;
ALTER TABLE restaurants ADD CONSTRAINT restaurants_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE SET NULL;

-- orders.courier_id: already nullable; just add ON DELETE SET NULL.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_courier_id_fkey;
ALTER TABLE orders ADD CONSTRAINT orders_courier_id_fkey
    FOREIGN KEY (courier_id) REFERENCES users(id) ON DELETE SET NULL;
