-- Bounded retry for external (Uber Direct / DoorDash) dispatch. Mirrors the
-- refund_attempts pattern from 051: dispatch.Dispatch() increments this on
-- every failed attempt (quote or create) and, once the cap is crossed — or
-- immediately on a permanent 4xx validation rejection — flips the order's
-- delivery_mode override to 'platform' so the internal courier pool takes it.
-- Without the bound, an order with permanently-bad data (e.g. a restaurant
-- with no phone: Uber rejects pickup_phone_number="") re-dispatched every
-- sweep tick forever, burning real provider API calls and stranding the
-- order in 'ready' invisibly.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_dispatch_attempts INT NOT NULL DEFAULT 0;
