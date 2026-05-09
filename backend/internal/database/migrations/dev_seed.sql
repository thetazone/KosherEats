-- Dev seed data: 1 seller user + 5 kosher restaurants + menus.
-- NOT a real migration — run this manually against local dev:
--   docker exec -i koshereats-postgres-1 psql -U postgres -d koshereats < backend/internal/database/migrations/dev_seed.sql
--
-- Idempotent: checks existence before inserting so re-running is safe.

-- ── Demo consumer (for App Store Review) ───────────────────
-- Login: review@koshereats.dev / KosherEats2026!
-- Provide these credentials to Apple during app submission.
INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role)
SELECT
    '00000000-0000-0000-0000-000000000001',
    'review@koshereats.dev',
    '$2y$10$6ELYu5F5vYyWO1Bn/6728Or/CoWxpoyt.gftrSgdga94ogmYIBNES',
    'App',
    'Review',
    '+15559990001',
    'consumer'
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- ── Seller user (owns all 5 restaurants) ────────────────────
-- Login: seller@koshereats.dev / sellerpass
-- Hash generated with bcrypt cost 12.
INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role)
SELECT
    '11111111-1111-1111-1111-111111111111',
    'seller@koshereats.dev',
    '$2b$12$v4JzdwDdomSAwASXhU1wj..0wT5Ysz5aMY/ImeweCIBg.syRYlvOm',
    'Dev',
    'Seller',
    '+15550000000',
    'seller'
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- ── Restaurants ─────────────────────────────────────────────
-- Mix of certifications, cuisines, and dietary markers.

INSERT INTO restaurants (id, owner_id, name, description, image_url, phone, email, street, city, state, zip_code, lat, lng, kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel, is_glatt_kosher, cuisine_type, rating, review_count, delivery_fee, min_order, est_delivery_min, est_delivery_max, is_open, is_active)
VALUES
    ('22222222-2222-2222-2222-222222222201',
     '11111111-1111-1111-1111-111111111111',
     'Shalom Grill',
     'Glatt kosher grill house serving classic Israeli street food, shawarma, and mezze platters.',
     '', '+15551112233', 'shalomgrill@koshereats.dev',
     '123 Kings Highway', 'Brooklyn', 'NY', '11223',
     40.6040, -73.9595,
     'OU', 'Orthodox Union',
     false, true, true,
     ARRAY['Israeli', 'Middle Eastern', 'Grill'],
     4.8, 1247, 399, 1500, 25, 40, true, true),

    ('22222222-2222-2222-2222-222222222202',
     '11111111-1111-1111-1111-111111111111',
     'Milk & Honey Cafe',
     'Dairy bistro with wood-fired pizzas, fresh pastas, and a rotating dessert menu. Cholov Yisroel.',
     '', '+15552223344', 'milkandhoney@koshereats.dev',
     '456 Avenue J', 'Brooklyn', 'NY', '11230',
     40.6254, -73.9626,
     'Star-K', 'Star-K Kosher',
     true, true, false,
     ARRAY['Italian', 'Dairy', 'Pizza'],
     4.7, 892, 299, 1200, 20, 35, true, true),

    ('22222222-2222-2222-2222-222222222203',
     '11111111-1111-1111-1111-111111111111',
     'The Sushi Rebbe',
     'Modern glatt kosher sushi bar. Fresh cuts, creative rolls, omakase available.',
     '', '+15553334455', 'sushirebbe@koshereats.dev',
     '789 Cedar Lane', 'Teaneck', 'NJ', '07666',
     40.8976, -74.0162,
     'Kof-K', 'Kof-K Kosher Supervision',
     false, true, true,
     ARRAY['Japanese', 'Sushi', 'Asian'],
     4.9, 2103, 499, 2000, 30, 45, true, true),

    ('22222222-2222-2222-2222-222222222204',
     '11111111-1111-1111-1111-111111111111',
     'Pita Express',
     'Quick-serve falafel, sabich, and schwarma. Everything made fresh to order.',
     '', '+15554445566', 'pitaexpress@koshereats.dev',
     '321 Main Street', 'Monsey', 'NY', '10952',
     41.1126, -74.0681,
     'OU', 'Orthodox Union',
     false, true, true,
     ARRAY['Israeli', 'Middle Eastern', 'Falafel'],
     4.6, 634, 299, 800, 15, 25, true, true),

    ('22222222-2222-2222-2222-222222222205',
     '11111111-1111-1111-1111-111111111111',
     'Bubbe''s Kitchen',
     'Eastern European kosher comfort food. Cholent, kugel, matzah ball soup, and Shabbat takeout.',
     '', '+15555556677', 'bubbes@koshereats.dev',
     '55 Central Avenue', 'Lakewood', 'NJ', '08701',
     40.0979, -74.2179,
     'cRc', 'Chicago Rabbinical Council',
     false, true, true,
     ARRAY['Eastern European', 'Comfort', 'Deli'],
     4.5, 1578, 399, 1800, 35, 55, true, true)
ON CONFLICT (id) DO NOTHING;

-- ── Menu categories + items ─────────────────────────────────
-- One category per restaurant for simplicity. Real app would have many.

-- Shalom Grill
INSERT INTO menu_categories (id, restaurant_id, name, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333301', '22222222-2222-2222-2222-222222222201', 'Grill Favorites', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (restaurant_id, category_id, name, description, price, is_meat, is_dairy, is_pareve, is_available, sort_order) VALUES
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Chicken Shawarma Platter', 'Marinated chicken shawarma with rice, Israeli salad, hummus, and pita.', 1895, true, false, false, true, 0),
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Lamb Kebab', 'Two skewers of seasoned ground lamb, charcoal grilled. Served with tahini and fries.', 2195, true, false, false, true, 1),
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Mixed Grill Combo', 'Chicken shawarma, lamb kebab, and beef kebab. Serves one hungry person.', 2895, true, false, false, true, 2),
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Falafel Wrap', 'Crispy chickpea falafel in a warm laffa with tahini, pickles, and Israeli salad.', 1195, false, false, true, true, 3),
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Baba Ganoush Mezze', 'Smoky roasted eggplant dip with warm pita.', 895, false, false, true, true, 4),
    ('22222222-2222-2222-2222-222222222201', '33333333-3333-3333-3333-333333333301', 'Fresh Hummus', 'House-made hummus, tahini drizzle, warm pita.', 795, false, false, true, true, 5)
ON CONFLICT DO NOTHING;

-- Milk & Honey Cafe
INSERT INTO menu_categories (id, restaurant_id, name, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333302', '22222222-2222-2222-2222-222222222202', 'Pizza & Pasta', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (restaurant_id, category_id, name, description, price, is_meat, is_dairy, is_pareve, is_available, sort_order) VALUES
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Margherita Pizza', 'Wood-fired, fresh mozzarella, basil, San Marzano tomato.', 1695, false, true, false, true, 0),
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Truffle Mushroom Pizza', 'Cremini and oyster mushrooms, truffle oil, fontina, arugula.', 2195, false, true, false, true, 1),
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Penne Vodka', 'Pink cream sauce, fresh basil, parmesan.', 1795, false, true, false, true, 2),
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Cacio e Pepe', 'Hand-rolled bucatini with pecorino and cracked black pepper.', 1995, false, true, false, true, 3),
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Burrata Plate', 'Creamy burrata, heirloom tomatoes, basil oil, sea salt.', 1595, false, true, false, true, 4),
    ('22222222-2222-2222-2222-222222222202', '33333333-3333-3333-3333-333333333302', 'Tiramisu', 'Espresso-soaked ladyfingers, mascarpone, cocoa.', 895, false, true, false, true, 5)
ON CONFLICT DO NOTHING;

-- The Sushi Rebbe
INSERT INTO menu_categories (id, restaurant_id, name, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333303', '22222222-2222-2222-2222-222222222203', 'Sushi & Rolls', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (restaurant_id, category_id, name, description, price, is_meat, is_dairy, is_pareve, is_available, sort_order) VALUES
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Salmon Sashimi (8 pc)', 'Fresh cuts of Atlantic salmon. Pareve.', 1895, false, false, true, true, 0),
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Spicy Tuna Roll', 'Tuna, sriracha, cucumber, scallion, sesame.', 1395, false, false, true, true, 1),
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Rainbow Roll', 'California base topped with salmon, tuna, and yellowtail.', 1995, false, false, true, true, 2),
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Dragon Roll', 'Shrimp tempura, avocado, eel sauce, tobiko.', 2195, false, false, true, true, 3),
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Chef''s Omakase (12 pc)', 'Daily chef selection of nigiri and sashimi.', 4595, false, false, true, true, 4),
    ('22222222-2222-2222-2222-222222222203', '33333333-3333-3333-3333-333333333303', 'Miso Soup', 'Classic soybean broth, tofu, seaweed, scallions.', 595, false, false, true, true, 5)
ON CONFLICT DO NOTHING;

-- Pita Express
INSERT INTO menu_categories (id, restaurant_id, name, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333304', '22222222-2222-2222-2222-222222222204', 'Pita & Wraps', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (restaurant_id, category_id, name, description, price, is_meat, is_dairy, is_pareve, is_available, sort_order) VALUES
    ('22222222-2222-2222-2222-222222222204', '33333333-3333-3333-3333-333333333304', 'Classic Falafel Pita', 'Five falafel balls, hummus, tahini, Israeli salad, pickles.', 995, false, false, true, true, 0),
    ('22222222-2222-2222-2222-222222222204', '33333333-3333-3333-3333-333333333304', 'Sabich', 'Fried eggplant, hard-boiled egg, hummus, amba, and salads in pita.', 1195, false, false, true, true, 1),
    ('22222222-2222-2222-2222-222222222204', '33333333-3333-3333-3333-333333333304', 'Chicken Shawarma Pita', 'Marinated chicken, hummus, tahini, pickles.', 1295, true, false, false, true, 2),
    ('22222222-2222-2222-2222-222222222204', '33333333-3333-3333-3333-333333333304', 'Beef Schnitzel Pita', 'Crispy beef schnitzel, Israeli salad, amba, fries inside.', 1495, true, false, false, true, 3),
    ('22222222-2222-2222-2222-222222222204', '33333333-3333-3333-3333-333333333304', 'Loaded Fries', 'Fries with tahini, schug, and pickled cabbage.', 695, false, false, true, true, 4)
ON CONFLICT DO NOTHING;

-- Bubbe's Kitchen
INSERT INTO menu_categories (id, restaurant_id, name, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333305', '22222222-2222-2222-2222-222222222205', 'Shabbat Favorites', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (restaurant_id, category_id, name, description, price, is_meat, is_dairy, is_pareve, is_available, sort_order) VALUES
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Matzah Ball Soup', 'Rich chicken broth with two fluffy matzah balls, carrots, and dill.', 995, true, false, false, true, 0),
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Brisket Platter', 'Slow-cooked beef brisket, mashed potatoes, carrots, and gravy.', 2495, true, false, false, true, 1),
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Cholent', 'Classic beef and bean stew, slow-cooked overnight. Sunday only.', 1895, true, false, false, true, 2),
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Potato Kugel', 'Crispy-edged potato kugel, house recipe.', 795, false, false, true, true, 3),
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Stuffed Cabbage', 'Beef and rice in tomato-braised cabbage leaves. Three pieces.', 1695, true, false, false, true, 4),
    ('22222222-2222-2222-2222-222222222205', '33333333-3333-3333-3333-333333333305', 'Apple Cake', 'Grandma''s recipe. Cinnamon apples in a sweet crumb cake.', 795, false, false, true, true, 5)
ON CONFLICT DO NOTHING;

-- ── Image URLs ──────────────────────────────────────────────
-- Unsplash-hosted food photography. Applied as UPDATE so re-running the seed
-- refreshes images without duplicating rows.

UPDATE restaurants SET image_url = 'https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=800&auto=format', cover_image_url = 'https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=1200&auto=format' WHERE id = '22222222-2222-2222-2222-222222222201';
UPDATE restaurants SET image_url = 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=800&auto=format', cover_image_url = 'https://images.unsplash.com/photo-1513104890138-7c749659a591?w=1200&auto=format' WHERE id = '22222222-2222-2222-2222-222222222202';
UPDATE restaurants SET image_url = 'https://images.unsplash.com/photo-1579584425555-c3ce17fd4351?w=800&auto=format', cover_image_url = 'https://images.unsplash.com/photo-1553621042-f6e147245754?w=1200&auto=format' WHERE id = '22222222-2222-2222-2222-222222222203';
UPDATE restaurants SET image_url = 'https://images.unsplash.com/photo-1540914124281-342587941389?w=800&auto=format', cover_image_url = 'https://images.unsplash.com/photo-1561651823-34feb02250e4?w=1200&auto=format' WHERE id = '22222222-2222-2222-2222-222222222204';
UPDATE restaurants SET image_url = 'https://images.unsplash.com/photo-1547592180-85f173990554?w=800&auto=format', cover_image_url = 'https://images.unsplash.com/photo-1495147466023-ac5c588e2e94?w=1200&auto=format' WHERE id = '22222222-2222-2222-2222-222222222205';

-- Kosher certificate images (placeholder document photos for dev).
UPDATE restaurants SET kosher_certificate_url = 'https://images.unsplash.com/photo-1568667256549-094345857637?w=800&auto=format' WHERE id = '22222222-2222-2222-2222-222222222201';
UPDATE restaurants SET kosher_certificate_url = 'https://images.unsplash.com/photo-1586282391129-76a6df230234?w=800&auto=format' WHERE id = '22222222-2222-2222-2222-222222222202';
UPDATE restaurants SET kosher_certificate_url = 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?w=800&auto=format' WHERE id = '22222222-2222-2222-2222-222222222203';
UPDATE restaurants SET kosher_certificate_url = 'https://images.unsplash.com/photo-1554224155-6726b3ff858f?w=800&auto=format' WHERE id = '22222222-2222-2222-2222-222222222204';
UPDATE restaurants SET kosher_certificate_url = 'https://images.unsplash.com/photo-1507925921958-8a62f3d1a50d?w=800&auto=format' WHERE id = '22222222-2222-2222-2222-222222222205';

-- Menu items keyed by name (IDs are auto-generated).
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1529006557810-274b9b2fc783?w=600&auto=format' WHERE name = 'Chicken Shawarma Platter';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1555939594-58d7cb561ad1?w=600&auto=format' WHERE name = 'Lamb Kebab';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600&auto=format' WHERE name = 'Mixed Grill Combo';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1535400875775-0bfeaa5db55e?w=600&auto=format' WHERE name = 'Falafel Wrap';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1595295333158-4742f28fbd85?w=600&auto=format' WHERE name = 'Baba Ganoush Mezze';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1571197119282-7c4e2c2fa7a3?w=600&auto=format' WHERE name = 'Fresh Hummus';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1604068549290-dea0e4a305ca?w=600&auto=format' WHERE name = 'Margherita Pizza';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=600&auto=format' WHERE name = 'Truffle Mushroom Pizza';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1608756687911-aa1599ab3bd9?w=600&auto=format' WHERE name = 'Penne Vodka';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1645112411341-6c4fd023714a?w=600&auto=format' WHERE name = 'Cacio e Pepe';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1608897013039-887f21d8c804?w=600&auto=format' WHERE name = 'Burrata Plate';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1571877227200-a0d98ea607e9?w=600&auto=format' WHERE name = 'Tiramisu';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1583623025817-d180a2221d0a?w=600&auto=format' WHERE name = 'Salmon Sashimi (8 pc)';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1579871494447-9811cf80d66c?w=600&auto=format' WHERE name = 'Spicy Tuna Roll';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1553621042-f6e147245754?w=600&auto=format' WHERE name = 'Rainbow Roll';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1534482421-64566f976cfa?w=600&auto=format' WHERE name = 'Dragon Roll';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1617196034796-73dfa7b1fd56?w=600&auto=format' WHERE name = 'Chef''s Omakase (12 pc)';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1607301405390-d831c242f59b?w=600&auto=format' WHERE name = 'Miso Soup';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1615937722923-67f6deaf2cc9?w=600&auto=format' WHERE name = 'Classic Falafel Pita';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1529006557810-274b9b2fc783?w=600&auto=format' WHERE name = 'Sabich';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1561651823-34feb02250e4?w=600&auto=format' WHERE name = 'Chicken Shawarma Pita';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1599921841143-819065a55cc6?w=600&auto=format' WHERE name = 'Beef Schnitzel Pita';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1573080496219-bb080dd4f877?w=600&auto=format' WHERE name = 'Loaded Fries';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1547592180-85f173990554?w=600&auto=format' WHERE name = 'Matzah Ball Soup';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1544025162-d76694265947?w=600&auto=format' WHERE name = 'Brisket Platter';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1613844237701-8f3664fc2eff?w=600&auto=format' WHERE name = 'Cholent';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1598511729763-bf8a6d88c30f?w=600&auto=format' WHERE name = 'Potato Kugel';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1625938145312-c141ec3ae563?w=600&auto=format' WHERE name = 'Stuffed Cabbage';
UPDATE menu_items SET image_url = 'https://images.unsplash.com/photo-1568571780765-9276ac8b75a2?w=600&auto=format' WHERE name = 'Apple Cake';

-- ── Modifier groups (4 representative items) ────────────────
-- Uses WITH + RETURNING chains, runs only if the item has no groups yet
-- so re-running the seed stays idempotent.

-- Chicken Shawarma Platter (Shalom Grill)
DO $$
DECLARE
  item_id UUID;
  group_id UUID;
BEGIN
  SELECT id INTO item_id FROM menu_items WHERE name = 'Chicken Shawarma Platter' LIMIT 1;
  IF item_id IS NULL THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM menu_item_modifier_groups WHERE menu_item_id = item_id) THEN RETURN; END IF;

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Choose your size', true, 1, 1, 0) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, is_default, sort_order) VALUES
    (group_id, 'Regular', 0, true, 0),
    (group_id, 'Large (+$4)', 400, false, 1),
    (group_id, 'Family size (+$10)', 1000, false, 2);

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Add-ons', false, 0, 5, 1) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, sort_order) VALUES
    (group_id, 'Extra pickles', 100, 0),
    (group_id, 'Extra hummus', 150, 1),
    (group_id, 'Extra tahini', 100, 2),
    (group_id, 'Extra falafel (+2)', 200, 3),
    (group_id, 'Grilled vegetables', 250, 4);
END $$;

-- Margherita Pizza (Milk & Honey)
DO $$
DECLARE
  item_id UUID;
  group_id UUID;
BEGIN
  SELECT id INTO item_id FROM menu_items WHERE name = 'Margherita Pizza' LIMIT 1;
  IF item_id IS NULL THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM menu_item_modifier_groups WHERE menu_item_id = item_id) THEN RETURN; END IF;

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Pizza size', true, 1, 1, 0) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, is_default, sort_order) VALUES
    (group_id, 'Personal 10"', 0, true, 0),
    (group_id, 'Medium 14" (+$5)', 500, false, 1),
    (group_id, 'Large 18" (+$10)', 1000, false, 2);

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Crust', true, 1, 1, 1) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, is_default, sort_order) VALUES
    (group_id, 'Classic', 0, true, 0),
    (group_id, 'Thin', 0, false, 1),
    (group_id, 'Gluten-free (+$3)', 300, false, 2);

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Extra toppings', false, 0, 8, 2) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, sort_order) VALUES
    (group_id, 'Extra mozzarella', 200, 0),
    (group_id, 'Olives', 150, 1),
    (group_id, 'Mushrooms', 200, 2),
    (group_id, 'Fresh basil', 100, 3),
    (group_id, 'Roasted garlic', 150, 4);
END $$;

-- Rainbow Roll (Sushi Rebbe)
DO $$
DECLARE
  item_id UUID;
  group_id UUID;
BEGIN
  SELECT id INTO item_id FROM menu_items WHERE name = 'Rainbow Roll' LIMIT 1;
  IF item_id IS NULL THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM menu_item_modifier_groups WHERE menu_item_id = item_id) THEN RETURN; END IF;

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Spice level', false, 0, 1, 0) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, sort_order) VALUES
    (group_id, 'No spice', 0, 0),
    (group_id, 'Light sriracha', 0, 1),
    (group_id, 'Extra spicy', 0, 2);

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Extras', false, 0, 4, 1) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, sort_order) VALUES
    (group_id, 'Extra ginger', 50, 0),
    (group_id, 'Extra wasabi', 50, 1),
    (group_id, 'Soy sauce (low sodium)', 0, 2),
    (group_id, 'Spicy mayo', 75, 3);
END $$;

-- Classic Falafel Pita (Pita Express)
DO $$
DECLARE
  item_id UUID;
  group_id UUID;
BEGIN
  SELECT id INTO item_id FROM menu_items WHERE name = 'Classic Falafel Pita' LIMIT 1;
  IF item_id IS NULL THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM menu_item_modifier_groups WHERE menu_item_id = item_id) THEN RETURN; END IF;

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, description, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Choose your salads', 'Pick up to 3 free', false, 0, 3, 0) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, sort_order) VALUES
    (group_id, 'Israeli salad', 0, 0),
    (group_id, 'Cabbage salad', 0, 1),
    (group_id, 'Tabouleh', 0, 2),
    (group_id, 'Matbucha', 0, 3),
    (group_id, 'Turkish salad', 0, 4);

  INSERT INTO menu_item_modifier_groups (menu_item_id, name, is_required, min_selections, max_selections, sort_order)
  VALUES (item_id, 'Sauces', false, 0, 4, 1) RETURNING id INTO group_id;
  INSERT INTO menu_item_modifiers (group_id, name, price_delta, is_default, sort_order) VALUES
    (group_id, 'Tahini', 0, true, 0),
    (group_id, 'Amba', 0, false, 1),
    (group_id, 'Schug (spicy)', 0, false, 2),
    (group_id, 'Garlic sauce', 0, false, 3);
END $$;

-- ── Test accounts for all 3 iOS apps ────────────────────────
-- Logins:
--   consumer@koshereats.dev / consumerpass
--   seller@koshereats.dev   / sellerpass  (owns all 5 seeded restaurants)
--   courier@koshereats.dev  / courierpass (approved, payouts ready)

INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'consumer@koshereats.dev',
        '$2b$12$HGt37Cj2osV0NE7hbhFjouNhZDv.SSWa.15FH9kHlYZWBfQOKh1Q6',
        'Dev', 'Consumer', '+15551110000', 'consumer')
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- Reset seller hash too (in case earlier seeds wrote a bad one)
UPDATE users SET password_hash = '$2b$12$mijlwtroKCHmhIfVYFREE.g71f1a3tsVykG9Q9CwfkOsD/kBuI24e'
 WHERE email = 'seller@koshereats.dev';

INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role)
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'courier@koshereats.dev',
        '$2b$12$is/A57QsIgDoUlF/NccgGe8n5bIpoRjPIBoW4Y2YVBleTcu2ZTlpm',
        'Dev', 'Courier', '+15552220000', 'courier')
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- Fully approved courier profile so the test courier can go online immediately.
INSERT INTO courier_profiles (user_id, onboarding_status, phone_verified,
    vehicle_type, vehicle_make, vehicle_model, vehicle_year, vehicle_color,
    license_plate, background_check_status, payout_ready)
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'approved', true,
        'car', 'Toyota', 'Camry', 2022, 'Silver',
        'KE-DEV-1', 'passed', true)
ON CONFLICT (user_id) DO UPDATE SET
    onboarding_status = EXCLUDED.onboarding_status,
    phone_verified = EXCLUDED.phone_verified;

-- ── Admin account for the web admin dashboard ───────────────
-- Login: admin@koshereats.dev / adminpass
INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role)
VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'admin@koshereats.dev',
        '$2b$12$5hX4CZDucMnwBEmFL7HWGuvF9reUSI9Gi/iHDk9s1iKQ4xJJ67rQ.',
        'Dev', 'Admin', '+15553330000', 'admin')
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- ── Deals ───────────────────────────────────────────────────
-- Active limited-time promotions. Re-running refreshes expires_at so
-- deals don't silently disappear after the original window passes.

INSERT INTO deals (id, restaurant_id, title, description, discount_type, discount_value, min_order_amount, expires_at)
VALUES
    -- Shalom Grill — Lunch Special
    ('44444444-4444-4444-4444-444444444401',
     '22222222-2222-2222-2222-222222222201',
     'Lunch Special — $5 Off',
     'Save $5 on any order weekdays 11am–3pm. Min order $20.',
     'fixed', 500, 2000,
     NOW() + INTERVAL '14 days'),

    -- The Sushi Rebbe — Buy 1 Get 1 Free
    ('44444444-4444-4444-4444-444444444402',
     '22222222-2222-2222-2222-222222222203',
     'Buy 1 Roll, Get 1 Free',
     'Order any signature roll and get a second one on us. While supplies last.',
     'bogo', 0, NULL,
     NOW() + INTERVAL '7 days'),

    -- Milk & Honey Cafe — 25% Off
    ('44444444-4444-4444-4444-444444444403',
     '22222222-2222-2222-2222-222222222202',
     '25% Off Pizza & Pasta',
     'Save 25% on your full order this week. Min order $15.',
     'percentage', 25, 1500,
     NOW() + INTERVAL '7 days'),

    -- Pita Express — Lunch Combo
    ('44444444-4444-4444-4444-444444444404',
     '22222222-2222-2222-2222-222222222204',
     'Lunch Combo — 15% Off',
     '15% off all wraps and pitas, weekdays 11am–2pm. Min order $10.',
     'percentage', 15, 1000,
     NOW() + INTERVAL '14 days')
ON CONFLICT (id) DO UPDATE SET
    title           = EXCLUDED.title,
    description     = EXCLUDED.description,
    discount_type   = EXCLUDED.discount_type,
    discount_value  = EXCLUDED.discount_value,
    min_order_amount = EXCLUDED.min_order_amount,
    expires_at      = EXCLUDED.expires_at,
    is_active       = true,
    updated_at      = NOW();
