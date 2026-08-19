# Shipday — courier aggregator go-live runbook

Why Shipday: as of 2026-08, the Uber Direct production account is disabled by
Uber (account-level risk/state action, not billing — see
`koshereats-uber-direct-accounts` memory) and DoorDash Drive production access
is restricted to existing partners. Shipday is a courier **aggregator**: one
self-serve account dispatches DoorDash/Uber/local fleets under Shipday's own
master agreements. Published rates ~$6.49 base + 3% payment fee; software plan
(~$39/mo Professional — third-party dispatch may require a higher plan, verify
at signup).

## Integration summary (code-complete on `feat/shipday-provider`)

- Client: `backend/internal/shipday/client.go` — quote via
  `POST /on-demand/availability`, dispatch via `POST /orders` (insert) then
  `POST /on-demand/assign`, cancel via `PUT /orders/unassign/{id}`.
- Provider name in DB: `external_provider = 'shipday'`;
  `external_delivery_id` = Shipday's numeric order id.
- Webhook: `POST /api/v1/webhooks/shipday`
  (`backend/internal/handlers/shipday_webhook.go`), verified by the `token`
  header against `SHIPDAY_WEBHOOK_TOKEN` (constant-time; fails closed when
  unset). Idempotent via `external_webhook_events` (provider `shipday`).
- Money: Shipday speaks dollars; conversion to cents happens only inside the
  client. The availability `regulatoryFee` is included in the quoted fee.
- Fail-closed: a disabled client returns errors (no stub-success like the
  older provider clients), and checkout already refuses delivery orders when
  every provider fails to quote (503, `payments.go` flat_rate guard).

Status → order mapping (`shipday_webhook.go`):

| Shipday `event`                          | Order effect                          | Consumer push    |
|------------------------------------------|---------------------------------------|------------------|
| `ORDER_ASSIGNED`                          | none                                  | `OrderClaimed`   |
| `ORDER_PIKEDUP` (their spelling) / `ORDER_ONTHEWAY` | → `picked_up` (first one wins) | `OrderPickedUp`  |
| `ORDER_COMPLETED`                         | → `delivered`                         | `OrderDelivered` |
| `ORDER_FAILED` / `ORDER_INCOMPLETE` / `ORDER_UNASSIGNED` | clear linkage, `picked_up`→`ready`, re-arm dispatch | — |

## Go-live steps

1. **Create the Shipday account** (self-serve, shipday.com) as the business —
   use the KosherEats business identity, not a personal profile. Add a payment
   method for third-party dispatch billing.
2. **Get the API key**: Dispatch Dashboard → My Account → API key.
3. **Register the webhook**: dashboard webhook settings → URL
   `https://koshereats-api.fly.dev/api/v1/webhooks/shipday`, and set a
   validation token (max 32 chars — generate randomly, e.g. `openssl rand -hex 16`).
4. **Set Fly secrets** (one command, one restart):
   ```bash
   fly secrets set -a koshereats-api \
     SHIPDAY_API_KEY='<dashboard API key>' \
     SHIPDAY_WEBHOOK_TOKEN='<the token from step 3>'
   ```
5. **Verify quote flow before any order**: the checkout quote for a delivery
   restaurant should now return `provider: "shipday"` (not `flat_rate`, which
   checkout rejects with 503).
6. **One low-value live E2E order** on `external` mode: watch
   `ready → (ORDER_ASSIGNED) → picked_up → delivered` in `orders` +
   `external_webhook_events`, confirm the consumer pushes fire, and reconcile
   the Shipday invoice against `provider_fee_cents`.
7. **Watch the first week's billing**: Shipday bills the card weekly for
   third-party fees; compare against `SUM(provider_fee_cents)` for
   `external_provider='shipday'`.

## Caveats

- Shipday availability/assign responses were coded from their public docs;
  the first sandbox-less live test is the real contract check (their API has
  no sandbox mode — start with one cheap order).
- `ORDER_POD_UPLOAD` (proof of delivery) is currently a no-op; podUrls could
  populate `delivery_proof_url` later.
- No alcohol via third-party dispatch (Shipday doesn't support age
  verification on on-demand) — irrelevant today, relevant if wine is added.
- Dispatch retry after a failed assign leaves an unassigned Shipday order
  behind (harmless, unbilled); webhook scoping by delivery id ignores it.
