-- KosherEats Database Schema
-- Migration 003: Courier support (couriers, onboarding, GPS, order handoff)

-- Allow 'courier' as a user role
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('consumer', 'seller', 'admin', 'courier'));

-- Courier onboarding / profile
-- Mirrors the multi-step onboarding of Uber Driver / DoorDash Dasher:
-- signup -> phone verify -> vehicle info -> document uploads -> background check -> approved
CREATE TABLE courier_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Onboarding state
    onboarding_status VARCHAR(30) NOT NULL DEFAULT 'pending_info'
        CHECK (onboarding_status IN (
            'pending_info',       -- just signed up, needs to fill info
            'pending_documents',  -- info filled, needs license / insurance uploads
            'pending_background', -- documents uploaded, background check running
            'approved',           -- ready to drive
            'rejected',           -- failed background check or manual rejection
            'suspended'           -- temporarily disabled
        )),
    phone_verified BOOLEAN NOT NULL DEFAULT false,

    -- Vehicle
    vehicle_type VARCHAR(20) DEFAULT ''
        CHECK (vehicle_type IN ('', 'car', 'bike', 'scooter', 'motorcycle', 'walk')),
    vehicle_make VARCHAR(100) DEFAULT '',
    vehicle_model VARCHAR(100) DEFAULT '',
    vehicle_year INTEGER DEFAULT 0,
    vehicle_color VARCHAR(50) DEFAULT '',
    license_plate VARCHAR(20) DEFAULT '',

    -- Documents (URLs to uploaded images — stubbed for dev)
    drivers_license_url TEXT DEFAULT '',
    drivers_license_number VARCHAR(50) DEFAULT '',
    insurance_url TEXT DEFAULT '',
    vehicle_registration_url TEXT DEFAULT '',
    profile_photo_url TEXT DEFAULT '',

    -- Background check (stub in dev — auto-approve)
    background_check_status VARCHAR(20) NOT NULL DEFAULT 'not_started'
        CHECK (background_check_status IN ('not_started', 'in_progress', 'passed', 'failed')),
    background_check_ref VARCHAR(100) DEFAULT '', -- Checkr / third-party reference id

    -- Payout (Stripe Connect stub)
    stripe_connect_id VARCHAR(255) DEFAULT '',
    payout_ready BOOLEAN NOT NULL DEFAULT false,

    -- Live state
    is_online BOOLEAN NOT NULL DEFAULT false,
    last_lat DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_lng DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_location_at TIMESTAMPTZ,

    -- Stats
    total_deliveries INTEGER NOT NULL DEFAULT 0,
    rating DOUBLE PRECISION NOT NULL DEFAULT 5.0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_courier_profiles_user ON courier_profiles(user_id);
CREATE INDEX idx_courier_profiles_online ON courier_profiles(is_online) WHERE is_online = true;
CREATE INDEX idx_courier_profiles_status ON courier_profiles(onboarding_status);
CREATE INDEX idx_courier_profiles_location ON courier_profiles USING gist (point(last_lng, last_lat))
    WHERE is_online = true;

-- Add courier fields to orders
ALTER TABLE orders ADD COLUMN courier_id UUID REFERENCES users(id);
ALTER TABLE orders ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN picked_up_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN delivered_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN courier_payout INTEGER NOT NULL DEFAULT 0; -- cents
ALTER TABLE orders ADD COLUMN courier_tip INTEGER NOT NULL DEFAULT 0;    -- cents

CREATE INDEX idx_orders_courier ON orders(courier_id) WHERE courier_id IS NOT NULL;

-- Historical GPS breadcrumbs (for receipts / disputes / live map trail).
-- Hot "current location" lives in courier_profiles.last_lat/lng; this table is append-only.
CREATE TABLE courier_locations (
    id BIGSERIAL PRIMARY KEY,
    courier_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    order_id UUID REFERENCES orders(id) ON DELETE SET NULL,
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    heading DOUBLE PRECISION DEFAULT 0,
    speed DOUBLE PRECISION DEFAULT 0,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_courier_locations_courier_time ON courier_locations(courier_id, recorded_at DESC);
CREATE INDEX idx_courier_locations_order ON courier_locations(order_id) WHERE order_id IS NOT NULL;
