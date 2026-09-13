# KosherEats Polish — Round 1
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_bugs_backend] DeleteAccount strands paid orders when a courier or seller deletes their account** — FIXED
  The live-order guard only counted orders where orders.user_id = the caller, so a courier holding a claimed/picked_up delivery, or a seller whose resta
  > Done — the fix was already present in the working tree, and I verified it end-to-end rather than re-applying it.

## Wha

- **[7/10] [ke_bugs_backend] Uber Direct CreateDelivery sends no idempotency_key — a retry after a lost response buys a second paid courier** — FIXED
  A transport error, client-side timeout, or 5xx on the Uber create is classified transient and retried by the sweep or by the seller's next 'Dispatch t
  > The fix described in the issue is already fully applied in the working tree (uncommitted, on `feat/web-hunt-20260913`) —

- **[5/10] [ke_bugs_backend] CreateOrder re-adjudicates the deal after the card is charged → charged-but-no-order on an expiring or deactivated promo** — FIXED
  CreatePaymentIntent validates the deal and charges the discounted total; CreateOrder then re-ran resolveDealDiscount, which re-checks is_active and ex
  > The fix was already applied in the working tree on `feat/web-hunt-20260913` (uncommitted); I verified it's complete and 
