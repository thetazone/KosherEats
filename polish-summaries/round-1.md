# KosherEats Polish — Round 1
**Max severity found:** 8
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[8/10] [ke_bugs_backend] PaymentIntent is not bound to the restaurant — free long-haul delivery** — FIXED
  CreateOrder binds the charge to payer, amount, fulfillment type, delivery fee and the DROPOFF address, but never to the restaurant: it derives the res
  > The fix is complete and verified. Summary of what's in place across the four files:

**`backend/internal/payments/stripe

- **[7/10] [ke_bugs_backend] Uber cancel reconciler silently strands orders escalated before 'ready'** — FIXED
  reconcileUberDeliveryStatus's uberDeliveryCanceled branch matched status IN ('ready','picked_up'), while the same switch's picked-up and delivered bra
  > The fix is in place in `backend/internal/scheduler/dispatcher.go` and verified.

**What was wrong:** `sweepExternalDeliv

- **[7/10] [ke_bugs_backend] Approved restaurant can rewrite its kosher certification with no admin re-review** — FIXED
  UpdateRestaurant (seller.go:477-515) COALESCE-updates kosher_certification, certifying_agency, is_cholov_yisroel, is_pas_yisroel, is_glatt_kosher and 
  > Fixed in `backend/internal/handlers/seller.go` — build, vet, gofmt, and test compilation are clean.

**What I chose and 
