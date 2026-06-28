# Uber Direct — go-live & webhook runbook

Status as of 2026-06-28 (verified against prod `koshereats-db`):

- **Dispatch CREATE works.** Prod has dispatched exactly one real Uber Direct
  delivery (order `8b939d41`, Pizza Kids, 2026-06-25, delivery `del_eo4L64ey…`).
- **The webhook has NEVER reached prod.** `external_webhook_events` total = **0**
  (vs. `stripe_webhook_events` = 14, which work). So dispatched orders never
  advance past `ready` — no `picked_up`, no `delivered`, no consumer push.
- **Prod Uber Direct is the SANDBOX tenancy.** That one delivery's tracking URL
  is `…/orders/…?tenancyOverride=uber/testing/direct/5fe655a7…` — i.e. the prod
  `UBER_DIRECT_CUSTOMER_ID` (`5fe6…`) is a **test** customer. No real courier is
  dispatched even from prod today.

There are therefore **two** gaps to a real seller→consumer delivery:

1. **Production Uber Direct credentials** (live tenancy) — see §A.
2. **Webhook registration** so status flows back — see §B.

---

## A. Switch Uber Direct to production credentials

The current prod secrets point at Uber's *test* tenancy, so dispatch creates
sandbox deliveries that never move real food. To go live:

1. In the Uber Direct dashboard, obtain **production** credentials for the live
   customer/organization: `client_id`, `client_secret`, `customer_id`.
2. Set them in Fly (prod):
   ```bash
   fly secrets set -a koshereats-api \
     UBER_DIRECT_CLIENT_ID=<live> \
     UBER_DIRECT_CLIENT_SECRET=<live> \
     UBER_DIRECT_CUSTOMER_ID=<live>
   ```
   (This restarts the app.)
3. Confirm a live dispatch no longer carries `tenancyOverride=uber/testing/...`
   in `external_tracking_url`.

> Keep using the sandbox creds for staging/QA. Real `CreateDelivery` calls are
> billed by Uber, so only the live tenancy should be used for real orders.

---

## B. Register the status webhook (the current blocker)

The endpoint already exists and is signature-verified in code
(`backend/internal/handlers/uber_direct_webhook.go`):

- **URL:** `https://koshereats-api.fly.dev/api/v1/webhooks/uber-direct`
- **Method:** `POST`
- **Event:** delivery-status events (handler requires `kind == "event.delivery_status"`)
- **Signature:** HMAC-SHA256 of the **raw request body**, sent in the
  `X-Uber-Signature` header, keyed by the webhook **signing secret**
  (`uber.VerifyWebhook`, `client.go`).

### Steps

1. In the Uber Direct dashboard → Webhooks, add the URL above and subscribe to
   delivery-status events.
2. Set the webhook **signing secret** in the dashboard to **exactly** the value
   of the Fly secret `UBER_DIRECT_WEBHOOK_SECRET` (already deployed). They must
   byte-match or every webhook is rejected.

### Exact secret-matching check

Read the Fly secret value (handle it like a password — don't paste anywhere):

```bash
fly ssh console -a koshereats-api -C "printenv UBER_DIRECT_WEBHOOK_SECRET"
```

Paste that exact string into the dashboard's webhook signing-secret field. To
sanity-check the signature scheme our backend expects, this is what it computes
for a given body+secret (must equal Uber's `X-Uber-Signature`):

```bash
SECRET='<the UBER_DIRECT_WEBHOOK_SECRET value>'
BODY='{"kind":"event.delivery_status","data":{"status":"pending","external_id":"test"}}'
printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -r | awk '{print $1}'
```

If a real webhook arrives and the secret is wrong, the backend logs
`uber direct webhook signature verification failed` and returns **400** — and
nothing lands in `external_webhook_events`.

---

## C. Verify it works (watch the events land)

After registering, dispatch a test order on **Pizza Kids** (already in
`external` mode, so tapping **Ready** auto-dispatches to Uber), then watch prod.

Open a prod DB tunnel:

```bash
# host psql, or route through the local docker postgres container:
DBURL=$(fly ssh console -a koshereats-api -C "printenv DATABASE_URL" | tr -d '\r')
CURL=$(python3 -c "import urllib.parse,sys;u=urllib.parse.urlparse(sys.argv[1]);print(urllib.parse.urlunparse((u.scheme,f'{u.username}:{u.password}@host.docker.internal:16432',u.path,'','sslmode=disable','')))" "$DBURL")
fly proxy 16432:5432 -a koshereats-db &   # leave running
```

**Watch the webhook ledger populate** (run repeatedly):

```sql
SELECT provider, type, received_at
FROM external_webhook_events
ORDER BY received_at DESC
LIMIT 10;
```

(`type` holds the delivery status: `pending`, `pickup`, `pickup_complete`,
`delivered`, `canceled`.)

**Watch the order advance** (replace the id with your test order):

```sql
SELECT id, status, external_provider, external_delivery_id,
       picked_up_at, delivered_at, updated_at
FROM orders
WHERE id = '<order-id>';
```

Expected status→order mapping (`uber_direct_webhook.go`):

| Uber `data.status` | Effect on our order              | Consumer push   |
|--------------------|----------------------------------|-----------------|
| `pending`          | none (delivery created)          | —               |
| `pickup`           | none (courier assigned/en route) | `OrderClaimed`  |
| `pickup_complete`  | → `picked_up`, sets `picked_up_at` | `OrderPickedUp` |
| `delivered`        | → `delivered`, sets `delivered_at` | `OrderDelivered`|
| `canceled`         | clears provider linkage, `picked_up`→`ready` (re-arms dispatch) | — |

**Success =** events appear in `external_webhook_events` AND the test order walks
`ready → picked_up → delivered`, and both apps update (seller card flips to
"Out for delivery…", consumer tracking reaches "Delivered").

---

## Troubleshooting

- **0 events after a dispatch:** URL not registered, wrong URL, or wrong
  environment (sandbox webhook vs. the tenancy you dispatched from).
- **Backend logs `signature verification failed` / 400s:** dashboard signing
  secret ≠ `UBER_DIRECT_WEBHOOK_SECRET`. Re-do the secret-matching check (§B).
- **Events land but order doesn't move:** the webhook's `data.external_id` must
  equal our order id (we set it as the delivery's `external_id` at
  `CreateDelivery`). Confirm in the logged `order_id` field.
- **A dispatched order stuck in `ready` with an `external_delivery_id`:** the
  webhook never arrived for it. After §B is fixed, re-dispatch fresh orders;
  old stuck ones won't retroactively receive events.
