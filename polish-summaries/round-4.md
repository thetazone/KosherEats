# KosherEats Polish — Round 4
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [web] Stale PaymentIntent window lets Place Order charge a PI priced against the previous address/tip/deal** — FIXED
  In CheckoutPanel.tsx the debounced re-quote effect (lines 359-369) sets previewPending(true) and schedules refreshIntent() 450ms later, but intentGen 
  > Both fixes are in `web/src/components/checkout/CheckoutPanel.tsx`; `tsc --noEmit` and `eslint` on the file are clean.

*

- **[6/10] [web] Sign-out deletes the captured-charge PendingOrder recovery record, reopening the double-pay path** — FIXED
  The uncommitted diff adds clearPendingOrder() to Header.tsx handleSignOut (line 87) and account/page.tsx signOut (line 703) to stop a shared device ha
  > Done. `tsc` and `eslint` both pass clean.

## What changed (all under `web/`)

**`web/src/components/layout/Header.tsx`*

- **[4/10] [web] Server cart can be mutated from other pages while a captured charge is still in recovery, making recovery unconvergeable** — FIXED
  cart/page.tsx hides the item steppers and CheckoutPanel while recovering is true, but restaurant/[id]/page.tsx addToCart (334-395) and orders/page.tsx
  > Typecheck and lint both pass (exit 0). The diff stat includes pre-existing uncommitted edits on this branch; my change i
