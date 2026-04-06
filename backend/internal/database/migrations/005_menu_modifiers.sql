-- Migration 005: Menu item modifiers.
--
-- Modifiers are the "extras, options, and instructions" that turn a generic
-- menu item into a specific order: "large size", "no onions", "extra cheese",
-- "choose your sauce". This lets us model real restaurant menus accurately.
--
-- Shape: each menu item can have N modifier_groups. Each group is either
-- required or optional, single- or multi-select, with min/max counts. Each
-- group has N modifier rows (the actual choices). Modifiers carry a
-- price_delta in cents which is ADDED to the base item price.
--
-- Selections made by the consumer are stored as a JSONB snapshot on
-- cart_items + order_items. We snapshot rather than referencing ids so
-- historical orders show the exact prices the customer saw at checkout,
-- even if the seller later edits modifier prices.

CREATE TABLE menu_item_modifier_groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    menu_item_id UUID NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT DEFAULT '',

    -- Selection rules
    is_required BOOLEAN NOT NULL DEFAULT false,
    min_selections INTEGER NOT NULL DEFAULT 0,
    max_selections INTEGER NOT NULL DEFAULT 1,

    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT modifier_group_max_gte_min CHECK (max_selections >= min_selections),
    CONSTRAINT modifier_group_required_has_min CHECK (NOT is_required OR min_selections >= 1)
);

CREATE INDEX idx_modifier_groups_item ON menu_item_modifier_groups(menu_item_id, sort_order);

CREATE TABLE menu_item_modifiers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID NOT NULL REFERENCES menu_item_modifier_groups(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    price_delta INTEGER NOT NULL DEFAULT 0, -- cents, can be 0 or negative
    is_default BOOLEAN NOT NULL DEFAULT false,
    is_available BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_modifiers_group ON menu_item_modifiers(group_id, sort_order);

-- Selected modifiers on a cart item / order item, stored as a JSONB snapshot:
--   [{"id": "...", "group_id": "...", "name": "Large", "price_delta": 300}, ...]
-- JSONB keeps the schema simple and queryable without extra joins.
ALTER TABLE cart_items ADD COLUMN selected_modifiers JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE order_items ADD COLUMN selected_modifiers JSONB NOT NULL DEFAULT '[]'::jsonb;

-- unit_price snapshot on cart_items: base price + modifier deltas, locked at
-- add-to-cart time so later modifier price edits don't retroactively change
-- what the user sees in their cart. Existing cart_items rows get the current
-- menu_item price as a backfill.
ALTER TABLE cart_items ADD COLUMN unit_price INTEGER NOT NULL DEFAULT 0;
UPDATE cart_items ci SET unit_price = mi.price
  FROM menu_items mi WHERE ci.menu_item_id = mi.id AND ci.unit_price = 0;
