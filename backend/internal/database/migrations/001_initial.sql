-- KosherEats Database Schema
-- Migration 001: Initial schema

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "cube";
CREATE EXTENSION IF NOT EXISTS "earthdistance";

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) DEFAULT '',
    phone VARCHAR(20) DEFAULT '',
    role VARCHAR(20) NOT NULL DEFAULT 'consumer' CHECK (role IN ('consumer', 'seller', 'admin')),
    avatar_url TEXT DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);

-- Addresses
CREATE TABLE addresses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(50) NOT NULL DEFAULT 'Home',
    street VARCHAR(255) NOT NULL,
    apt VARCHAR(50) DEFAULT '',
    city VARCHAR(100) NOT NULL,
    state VARCHAR(50) NOT NULL,
    zip_code VARCHAR(20) NOT NULL,
    lat DOUBLE PRECISION NOT NULL DEFAULT 0,
    lng DOUBLE PRECISION NOT NULL DEFAULT 0,
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_addresses_user_id ON addresses(user_id);

-- Restaurants
CREATE TABLE restaurants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    description TEXT DEFAULT '',
    image_url TEXT DEFAULT '',
    cover_image_url TEXT DEFAULT '',
    phone VARCHAR(20) DEFAULT '',
    email VARCHAR(255) DEFAULT '',
    street VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(50) NOT NULL,
    zip_code VARCHAR(20) NOT NULL,
    lat DOUBLE PRECISION NOT NULL DEFAULT 0,
    lng DOUBLE PRECISION NOT NULL DEFAULT 0,
    kosher_certification VARCHAR(50) NOT NULL DEFAULT 'OU',
    certifying_agency VARCHAR(255) DEFAULT '',
    is_cholov_yisroel BOOLEAN NOT NULL DEFAULT false,
    is_pas_yisroel BOOLEAN NOT NULL DEFAULT false,
    is_glatt_kosher BOOLEAN NOT NULL DEFAULT false,
    cuisine_type TEXT[] DEFAULT '{}',
    rating DOUBLE PRECISION NOT NULL DEFAULT 0,
    review_count INTEGER NOT NULL DEFAULT 0,
    delivery_fee INTEGER NOT NULL DEFAULT 399,
    min_order INTEGER NOT NULL DEFAULT 0,
    est_delivery_min INTEGER NOT NULL DEFAULT 30,
    est_delivery_max INTEGER NOT NULL DEFAULT 50,
    is_open BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_restaurants_owner_id ON restaurants(owner_id);
CREATE INDEX idx_restaurants_location ON restaurants USING gist (point(lng, lat));
CREATE INDEX idx_restaurants_active ON restaurants(is_active, is_open);
CREATE INDEX idx_restaurants_certification ON restaurants(kosher_certification);

-- Menu Categories
CREATE TABLE menu_categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_menu_categories_restaurant ON menu_categories(restaurant_id);

-- Menu Items
CREATE TABLE menu_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES menu_categories(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT DEFAULT '',
    image_url TEXT DEFAULT '',
    price INTEGER NOT NULL,
    is_meat BOOLEAN NOT NULL DEFAULT false,
    is_dairy BOOLEAN NOT NULL DEFAULT false,
    is_pareve BOOLEAN NOT NULL DEFAULT false,
    is_available BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_menu_items_restaurant ON menu_items(restaurant_id);
CREATE INDEX idx_menu_items_category ON menu_items(category_id);

-- Carts
CREATE TABLE carts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Cart Items
CREATE TABLE cart_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    menu_item_id UUID NOT NULL REFERENCES menu_items(id),
    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    notes TEXT DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);

-- Orders
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'preparing', 'ready', 'picked_up', 'delivered', 'cancelled', 'rejected')),
    subtotal INTEGER NOT NULL,
    delivery_fee INTEGER NOT NULL DEFAULT 0,
    service_fee INTEGER NOT NULL DEFAULT 0,
    tax INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL,
    delivery_address TEXT NOT NULL,
    delivery_lat DOUBLE PRECISION NOT NULL DEFAULT 0,
    delivery_lng DOUBLE PRECISION NOT NULL DEFAULT 0,
    stripe_payment_id VARCHAR(255) DEFAULT '',
    est_delivery_time TIMESTAMPTZ DEFAULT NOW() + INTERVAL '45 minutes',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_restaurant ON orders(restaurant_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at DESC);

-- Order Items
CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    menu_item_id UUID NOT NULL REFERENCES menu_items(id),
    name VARCHAR(255) NOT NULL,
    price INTEGER NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    notes TEXT DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
