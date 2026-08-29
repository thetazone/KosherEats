# KosherEats Polish — Round 4
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_tests_backend] Dispatch reuses a constant DoorDash quote id, dropping DoorDash from every retry** — FIXED
  internal/dispatch/external.go quoted DoorDash with ExternalDeliveryID = in.OrderID + "_quote", a per-order constant. DoorDash files a quote under that
  > ## Done — verified, plus one stale-comment correction

**The fix was already applied in the uncommitted working tree** w

- **[7/10] [ke_tests_backend] Courier payout hands the courier KosherEats' own marketplace markup** — FIXED
  internal/handlers/courier_orders.go:434 computes payout := deliveryFee + tip, where orders.delivery_fee is the CONSUMER-facing fee — the courier cost 
  > Done. All changes stayed under `backend/`.

**What was already in the tree** (from the same work stream): `orders.go` st

- **[6/10] [ke_tests_backend] Uber webhook lifecycle branches lack the delivery-id scope its cancel branch has** — FIXED
  internal/handlers/uber_direct_webhook.go scoped the 'pickup', 'pickup_complete' and 'delivered' branches to external_provider = 'uber_direct' AND exte
  > Done — the fix and its test were already present in the working tree; I verified them end to end rather than rewriting t
