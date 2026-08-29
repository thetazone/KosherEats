# KosherEats Polish — Round 2
**Max severity found:** 8
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[8/10] [ke_tests_backend] Persist after a paid create runs on the caller's context and fails silently, letting the stale-claim reaper buy a second courier** — FIXED
  In ExternalDispatcher.Dispatch, the final statement that records the provider delivery id runs on the caller's ctx, and its error branch only logged b
  > Whole backend suite is green. Here's where things stood and what I changed.

**Already in the working tree** (`backend/i

- **[6/10] [ke_tests_backend] Checkout quoted DoorDash under a hardcoded external_delivery_id, silently removing it from the consumer-facing auction** — FIXED
  quoteDeliveryFee sent the literal constant "quote_check" as DoorDash's external_delivery_id on every checkout quote. DoorDash Drive records a quote un
  > The fix is in place and verified.

**`backend/internal/handlers/delivery_quote.go`** — `quoteDeliveryFee` no longer send

- **[5/10] [ke_tests_backend] Self-delivery seller payout re-derives a historical charge from live markup config** — FIXED
  SellerDeliverOrder credits the restaurant with `deliveryFee - h.deliveryMarkupCents(subtotal)`, where deliveryFee is frozen on the order row at checko
  > Fixed. The self-delivery payout no longer re-derives a historical charge from live config.

**What changed**

1. **`inte
