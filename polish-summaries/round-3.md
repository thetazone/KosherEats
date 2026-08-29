# KosherEats Polish — Round 3
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_tests_backend] A partial refund permanently kills the courier's payout** — FIXED
  StripeWebhook's `charge.refunded` branch set `haltPayoutOrderID = orderID` for ANY refund, then flipped the courier_payout_queue row to `failed_perman
  > The fix was already present in the working tree; I verified it end to end rather than duplicating it.

**What's in place

- **[6/10] [ke_tests_backend] Pickup webhooks push to the consumer on a zero-row (cross-provider) match** — FIXED
  In `uber_direct_webhook.go` (`pickup_complete`) and `doordash_webhook.go` (`DASHER_PICKED_UP`), the 0-row branch called `logProviderScopeMiss` and the
  > The fix was already present in the working tree; I verified it rather than re-applying it.

**What's in place**

- `back

- **[5/10] [ke_tests_backend] The quote endpoint advertises a $5.99 delivery that checkout then refuses with a 503** — FIXED
  When every courier provider fails to quote, `quoteDeliveryFee` returns the `flat_rate` sentinel with the $5.99 fallback. `DeliveryQuote` (delivery_quo
  > Fixed. The consumer no longer has to reach the payment step to find out delivery was never available — the quote itself 
