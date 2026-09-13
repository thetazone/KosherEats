# KosherEats Polish — Round 2
**Max severity found:** 6
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[6/10] [ke_bugs_backend] Closed restaurants accept charges server-side — charged, then auto-rejected + refunded 10 minutes later** — FIXED
  The seller dashboard 'closed' toggle (restaurants.is_open) is never read at pay time: restaurantOrderable deliberately excludes it, CreatePaymentInten
  > ## Verified: fix is complete and green

The fix described in the issue was already applied in the working tree (uncommit

- **[5/10] [ke_bugs_backend] DoorDash re-dispatch after a cancel is a guaranteed 409 — the order burns its attempt budget and leaves the external path** — FIXED
  dispatch.Dispatch sent DoorDash CreateDelivery with external_delivery_id = the bare order UUID. DoorDash never allows an external_delivery_id to be re
  > The fix was already applied in the working tree (uncommitted); I verified it rather than re-implementing it. Everything 

- **[4/10] [ke_bugs_backend] Restaurant minimum order is decorative — rendered to every consumer, editable by sellers, enforced nowhere** — FIXED
  restaurants.min_order is a seller setting ('Minimum order ($)' in seller settings on web/iOS) and every consumer client renders it ('Min. order: $15' 
  > The fix was already present, uncommitted, in the working tree on `feat/web-hunt-20260913` — my work here was verifying i
