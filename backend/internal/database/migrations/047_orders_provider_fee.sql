-- Records the external provider's (Uber Direct / DoorDash) delivery fee that the
-- platform actually pays, so delivery economics are unambiguous: the platform's
-- margin on an external delivery is delivery_fee (charged to the customer) minus
-- provider_fee_cents (paid to the provider). 0 for platform/self-delivery orders
-- (no external provider). Additive; can't abort boot.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS provider_fee_cents INTEGER NOT NULL DEFAULT 0;
