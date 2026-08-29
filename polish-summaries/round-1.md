# KosherEats Polish — Round 1
**Max severity found:** 8
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[8/10] [ke_tests_backend] Uber cancel webhook can un-dispatch the live delivery, buying a second paid courier** — FIXED
  In internal/handlers/uber_direct_webhook.go the `canceled` branch scoped its UPDATE only by `external_provider = 'uber_direct'`, never by the webhook'
  > Whole backend builds and every package passes.

## Summary

The fix was already staged in the working tree; I verified i

- **[7/10] [ke_tests_backend] Uber cancel webhook welded accepted/preparing orders to a dead delivery forever** — FIXED
  The `canceled` branch in internal/handlers/uber_direct_webhook.go guarded on `status IN ('ready','picked_up')`. Both the DoorDash and Shipday handlers
  > The fix was already present in the working tree, and I verified it is correct and genuinely pinned rather than taking it

- **[6/10] [ke_tests_backend] All-providers-failed error contradicted its own permanence verdict, misreporting outages as unfixable to sellers** — FIXED
  internal/dispatch/external.go computed the permanence of an all-quotes-failed batch correctly and per-error (`permanent only when EVERY provider retur
  > Done. The fix is in place and verified.

## What the bug was

`Dispatch` computed the batch verdict correctly per-error 
