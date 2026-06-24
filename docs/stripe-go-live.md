# Stripe go-live runbook — test → live keys

**Status: PLAN ONLY.** This swaps the backend from Stripe **test mode** to **live
mode** (real charges). Do not run any step until you've decided to take real
money. Owner: Salto (real-money decision + Stripe account access).

Current prod is on **test keys** (the PaymentSheet shows a "TEST" badge and logs
show `acct_…/test/…`). Everything below makes real payments work.

---

## ⚠️ The one step everyone forgets — clear cached test customers

`users.stripe_customer_id` currently holds **test-mode** `cus_…` ids (created by
`GetOrCreateCustomer`). Those ids **do not exist** under the live key. On the
next checkout the code returns the cached id without re-checking it, so the live
Stripe API rejects it with **"No such customer"** and checkout 500s for every
returning user. (This is exactly the "stale stripe_customer_id from key
rotation" failure called out in `payments.go`.)

**On cutover you MUST null them out so each user gets a fresh live customer:**

```sql
UPDATE users SET stripe_customer_id = NULL;
```

Run this in the **same window** as setting the live keys (proxy + psql, same as
the email fix). New live customers are created lazily on first checkout.

---

## Prerequisites (do before touching Fly)

1. **Activate the Stripe account** for live payments (business details, bank
   account, identity). Until activated, live charges are rejected.
2. **Live API keys** in hand: `sk_live_…` (secret) and `pk_live_…` (publishable),
   from Dashboard → Developers → API keys (toggle **View test data → off**).
3. **Live webhook endpoint**: Dashboard (live mode) → Developers → Webhooks → add
   `https://koshereats-api.fly.dev/api/v1/webhooks/stripe`. Subscribe to at least
   `payment_intent.succeeded`, `charge.dispute.created`, and the Connect/payout
   events the backend handles. Copy the **live** signing secret (`whsec_…`).
4. **Stripe Connect (courier payouts)**: connected accounts (`acct_…`) created in
   test **don't exist live** — couriers must re-onboard through the live Express
   flow. Confirm the live Connect platform settings (branding, payout schedule)
   are configured. Plan the courier re-onboarding before cutover, or payouts fail.
5. **Apple Pay**: native app Apple Pay is keyed off the merchant id in the app
   entitlements, so it should carry over — but verify a real Apple Pay charge in
   live during the smoke test.
6. **Disable Link** (Dashboard → Settings → Payment methods → Link → off) so the
   "Pay with Link" button stops appearing in the PaymentSheet (the #4 follow-up).

## Cutover steps

1. Set the live secrets (each `fly secrets set` restarts the machine):
   ```bash
   cd backend
   fly secrets set \
     STRIPE_SECRET_KEY='sk_live_…' \
     STRIPE_PUBLISHABLE_KEY='pk_live_…' \
     STRIPE_WEBHOOK_SECRET='whsec_…live…' \
     -a koshereats-api
   ```
   (`looksLikeRealStripeKey` flips the backend out of stub mode automatically; a
   `sk_live_` key is "real", so this is the same code path as test — no flag.)
2. **Clear cached test customers** (the SQL above) — do not skip.
3. Wait for the machine to come back green: `fly status -a koshereats-api`,
   `curl -s -o /dev/null -w '%{http_code}' https://koshereats-api.fly.dev/health`.

## Verify (real money — keep it tiny)

1. Place a **real** order for the cheapest possible item with a **real card**.
   Confirm: PaymentSheet shows **no "TEST" badge**, the charge appears in the
   **live** Dashboard, and the order is created (no "Order Failed").
2. Confirm the **delivery total matches** the charge (the #1 fix) on a delivery
   order — should "just work" now that the fee is stamped + reused.
3. **Refund** that test charge from the live Dashboard.
4. Confirm a `payment_intent.succeeded` webhook was delivered (Dashboard →
   Webhooks → recent deliveries = 200).
5. If using couriers: run one live Express onboarding end-to-end and a $0/small
   payout to confirm Connect is live.

## Rollback

Set the test keys back and re-null `stripe_customer_id` (live customers won't
exist under the test key either):
```bash
fly secrets set STRIPE_SECRET_KEY='sk_test_…' STRIPE_PUBLISHABLE_KEY='pk_test_…' STRIPE_WEBHOOK_SECRET='whsec_…test…' -a koshereats-api
# then: UPDATE users SET stripe_customer_id = NULL;
```

## Notes

- Test data (customers, PaymentIntents, in-flight orders) does **not** migrate to
  live. That's expected — live starts clean.
- `STRIPE_TAX_ENABLED` is still off (flat `TaxRatePercent`). Going live does not
  change tax behavior; wiring real Stripe Tax is a separate task.
- Keep the test keys somewhere safe — staging/QA should stay on test mode.
