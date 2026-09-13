# KosherEats Polish — Round 3
**Max severity found:** 8
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[8/10] [web] Stuck pending_order permanently locks checkout on the browser (and leaks across users)** — FIXED
  The PendingOrder recovery loop has no terminal failure path. `submitPendingOrder` only clears `pending_order` on 200/409/deal-refund; every other erro
  > Typecheck and lint pass (`tsc` exit 0, `eslint` exit 0). The diff stat includes pre-existing uncommitted work on this br

- **[6/10] [web] Post-capture exception is reported as "Payment failed" with Pay re-enabled → double-charge path** — FIXED
  In `CheckoutForm.handleSubmit` (`CheckoutPanel.tsx:1252-1256`) `await onSuccess(paymentIntent.id)` sits inside the same try/catch as `stripe.confirmPa
  > Done. `tsc --noEmit` and `eslint` both pass on the two changed files (no test files cover this module).

## What changed

- **[5/10] [web] Closed restaurants are fully orderable on web — charge, then auto-reject/refund 10 min later** — FIXED
  `restaurant/[id]/page.tsx` never reads `restaurant.is_open` (the only "Closed" treatment is the browse card in `RestaurantCard.tsx:63`), and neither `
  > Done. `tsc --noEmit` and `eslint` are both clean on all three files. (Note: the diff on these files also includes pre-ex
