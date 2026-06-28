# Uber Direct — complete integration runbook

How to take Uber Direct courier dispatch from "code-complete but never run for
real" to a working, monitored seller→consumer delivery in production.

---

## Current state (verified against prod + the Uber dashboard, 2026-06-28)

- **Account structure:** two Uber Direct accounts under `sammymamiye@gmail.com`:
  - **"Kosher Shop"** — production (account `54c8a18a-…`)
  - **"Kosher Shop — Testing"** — sandbox (account `5fe655a7-…`)
- **Prod backend points at the SANDBOX account.** `UBER_DIRECT_CUSTOMER_ID`
  (`5fe655a7-…`) is the test app ("Kosher Shop's Test App"). Every prod dispatch
  so far created a *sandbox* delivery (tracking URL contains
  `uber/testing/direct/…`).
- **Webhook is NOT registered.** Test account → Developer → Webhooks shows
  "No endpoints have been set up yet." → `external_webhook_events` in prod = **0**
  (vs. 14 Stripe webhooks, which work). This is why dispatched orders never
  advance past `ready`.
- **Dispatch CREATE works** — one sandbox delivery was created (order
  `8b939d41`, 6/25); it shows **"Failed"** in the test dashboard (no real courier
  + no webhook back).
- **Production account has no API access.** The production "Kosher Shop" account's
  nav is only Home/Deliveries/Billing/Users/Reports — **no Developer / API Keys /
  Webhooks**. Production Direct API credentials are not provisioned.
- **Stripe is LIVE in prod and the refund path works** (verified by a real
  cancel+refund). So the money side is already live.

**Net:** the only gates left are Uber-side integration — sandbox validation,
production API access, and webhook registration.

---

## The four phases

| Phase | Goal | Needs prod API? | Real money? |
|---|---|---|---|
| 1 | Prove the full flow in **sandbox** | no | no |
| 2 | Get **production API access** from Uber | — | — |
| 3 | **Go live** (prod creds + prod webhook) | yes | yes |
| 4 | **Operational readiness** at scale | — | — |

---

## Phase 1 — Validate end-to-end in SANDBOX (no money, no prod access)

Proves dispatch → webhook → order status → consumer notification, entirely on the
test account.

### 1a. Register the webhook on the **Testing** account
`direct.uber.com` → account selector → **"Kosher Shop — Testing"** → **Developer**
→ **Webhooks** → **Create Webhook**:

- **Webhook URL:** `https://koshereats-api.fly.dev/api/v1/webhooks/uber-direct`
- **Event types:** check **`event.delivery_status`** (the only kind the backend
  processes — `uber_direct_webhook.go` early-returns on anything else;
  `event.courier_update` / `event.refund_request` are ignored today).
- **Save.** The form has **no secret field** — Uber **auto-generates** the
  signing secret and shows it on creation.

### 1b. Align the Fly secret to Uber's generated secret
The backend verifies `X-Uber-Signature = HMAC-SHA256(raw_body, UBER_DIRECT_WEBHOOK_SECRET)`.
The existing Fly value won't match the freshly-generated one, so update it:

```bash
fly secrets set -a koshereats-api UBER_DIRECT_WEBHOOK_SECRET='<secret Uber showed>'
```

(Restarts the app. Treat the secret like a password — don't paste it anywhere
else.) If they don't match, every webhook is rejected with **400** and the
backend logs `uber direct webhook signature verification failed`.

### 1c. Fire a dispatch and watch it
Pizza Kids is already `external` mode, so tapping **Ready** auto-dispatches.

Open a prod DB tunnel:
```bash
DBURL=$(fly ssh console -a koshereats-api -C "printenv DATABASE_URL" | tr -d '\r')
CURL=$(python3 -c "import urllib.parse,sys;u=urllib.parse.urlparse(sys.argv[1]);print(urllib.parse.urlunparse((u.scheme,f'{u.username}:{u.password}@host.docker.internal:16432',u.path,'','sslmode=disable','')))" "$DBURL")
fly proxy 16432:5432 -a koshereats-db &   # leave running; query via docker psql
```

Webhook ledger (should start populating):
```sql
SELECT provider, type, received_at FROM external_webhook_events ORDER BY received_at DESC LIMIT 10;
```
Order progression:
```sql
SELECT id, status, external_delivery_id, picked_up_at, delivered_at, updated_at
FROM orders WHERE id = '<order-id>';
```

**Sandbox caveat:** Uber's test environment may not run a real courier through
to `delivered` (the 6/25 test deliveries show "Failed"). The thing Phase 1 must
prove is that **`event.delivery_status` webhooks ARRIVE and signature-verify**
(rows land in `external_webhook_events`, no 400s in logs). Full
`ready → picked_up → delivered` with a live courier is validated in Phase 3.

---

## Phase 2 — Get production Uber Direct API access

The production account has no self-serve Developer section, so this is a request
to Uber, not a dashboard toggle:

- Contact your Uber Direct account manager / Direct onboarding / support and ask
  to **enable production API access** for the production "Kosher Shop" account
  (`54c8a18a-…`).
- You need production: **`client_id`, `client_secret`, `customer_id`** (distinct
  from the `oE-dcK…` / `5fe655a7…` test values).
- Confirm the production account gains a **Developer → API Keys / Webhooks**
  section once enabled.

---

## Phase 3 — Go live

1. Set production credentials in Fly (restarts the app):
   ```bash
   fly secrets set -a koshereats-api \
     UBER_DIRECT_CLIENT_ID=<live> \
     UBER_DIRECT_CLIENT_SECRET=<live> \
     UBER_DIRECT_CUSTOMER_ID=<live>
   ```
2. Register the webhook on the **production** account (same URL as 1a) and align
   `UBER_DIRECT_WEBHOOK_SECRET` to *that* account's generated secret.
3. Sanity check: a dispatched delivery's `external_tracking_url` no longer
   contains `uber/testing/direct/…`.
4. Place one real low-value order on a live restaurant, dispatch, and watch it
   walk `ready → picked_up → delivered` with a real courier — confirm the seller
   card flips to "Out for delivery…" and the consumer reaches "Delivered".

---

## Phase 4 — Operational readiness (smooth at scale)

- **Restaurants:** set live restaurants to `external` mode for immediate dispatch
  on Ready (there's no in-house courier fleet — no `couriers` table in prod — so
  `platform` mode just adds grace-window latency before falling back to Uber).
- **Ship app builds:** the seller/consumer dispatch UX fixes are on PR #3
  (`feat/uber-direct-dispatch-ux`), not in the current TestFlight builds — merge +
  cut new builds.
- **Alerting:** wire the loud log lines to a human —
  `external-dispatch: ORPHANED PAID DELIVERY` (paid but unrecorded) and
  `pending-refund: refund EXHAUSTED retries` (customer charged on a cancelled
  order). Both require manual reconciliation today.
- **Monitoring:** alert if a dispatched order sits in `ready`/`picked_up` beyond
  a threshold — that's the signature that the webhook has stopped flowing (the
  exact failure mode we just found).
- **Verify the cancel paths:** a `canceled` webhook resets `picked_up → ready`
  and re-arms dispatch; confirm that and the commit-then-refund flow behave on a
  live order.

---

## Reference

- **Endpoint:** `POST /api/v1/webhooks/uber-direct` (`backend/internal/handlers/uber_direct_webhook.go`)
- **Signature:** `X-Uber-Signature` = `HMAC-SHA256(raw body, UBER_DIRECT_WEBHOOK_SECRET)`
- **Required event kind:** `event.delivery_status`
- **Idempotency:** `external_webhook_events (provider, event_id)` — `event_id` is a
  SHA-256 of the raw body, so replays dedupe (migration 052).

Status → order mapping (`uber_direct_webhook.go`):

| Uber `data.status` | Order effect                       | Consumer push    |
|--------------------|------------------------------------|------------------|
| `pending`          | none (delivery created)            | —                |
| `pickup`           | none (courier assigned/en route)   | `OrderClaimed`   |
| `pickup_complete`  | → `picked_up`, sets `picked_up_at` | `OrderPickedUp`  |
| `delivered`        | → `delivered`, sets `delivered_at` | `OrderDelivered` |
| `canceled`         | clears linkage, `picked_up`→`ready`, re-arms dispatch | — |

### Status snapshot (checkboxes = done)
- [ ] Phase 1a — webhook registered on Test account
- [ ] Phase 1b — `UBER_DIRECT_WEBHOOK_SECRET` aligned in Fly
- [ ] Phase 1c — `delivery_status` webhooks land + verify in sandbox
- [ ] Phase 2 — production API access granted by Uber
- [ ] Phase 3 — prod creds + prod webhook + one real delivery completes
- [ ] Phase 4 — external mode, new app builds, alerting, monitoring
- [x] Stripe live + refund path working (verified 2026-06-28)
