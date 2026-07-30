# DoorDash Drive — complete integration runbook

How to take DoorDash Drive from "sandbox-verified" to a live second courier
provider alongside Uber Direct. Companion to `uber-direct-go-live.md`.

---

## Current state (verified against prod + the DoorDash portal, 2026-07-29)

- **Account:** `sammymamiye@gmail.com`, org **KosherEats**, Developer ID
  `d222d9aa-d476-4601-ab7f-c74a50d1f33f`. Drive shows status **Building**.
- **Sandbox credential exists and works.** Credential "KosherEats API (backend)"
  (Sandbox, Drive). The signing secret is displayed **exactly once at creation** —
  it is in Fly secrets, not in this repo and not recoverable from the portal. If
  lost, create a new credential.
- **All four `DOORDASH_*` secrets are set on `koshereats-api`**, so the client is
  live (not stub) in prod against **sandbox**.
- **Outbound verified live:** `/drive/v2/quotes` and `/drive/v2/deliveries` both
  succeed through our own client, including local DD-JWT-V1 minting. A sandbox
  quote returns `fee: 975` with geocoded addresses and a real tracking URL.
  (Coincidence worth knowing: `975` is also the stub fallback value in
  `internal/doordash/client.go` — don't read it as proof of a live call.)
- **Webhook endpoint registered and verified end-to-end.** Sandbox endpoint →
  `https://koshereats-api.fly.dev/api/v1/webhooks/doordash`, auth = static bearer
  token. Verified by driving a sandbox delivery through the Delivery Simulator:
  `DASHER_CONFIRMED` → `DASHER_CONFIRMED_PICKUP_ARRIVAL` → `DASHER_PICKED_UP` →
  `DASHER_DROPPED_OFF`, each reaching the right handler branch.
- **Dispatch already treats Drive as a peer.** `internal/dispatch/external.go`
  quotes every enabled provider and picks the cheapest, so no dispatch changes are
  needed at go-live.
- **Production access request submitted 2026-07-29** via portal → Support →
  category *Demo & production access*. DoorDash replies by email in 24–72h.

**Net: the only gate is DoorDash-side approval.** Nothing in our code blocks
go-live.

---

## The gate: production access is restricted

The portal banner reads: *"Production access to the Drive API is currently
restricted, and we cannot provide a timeline for certification."* Production
credentials **cannot be self-created** — the Environment dropdown offers
Production, but the request must be approved first. Until then Uber Direct stays
the sole live provider.

Do **not** confuse two separate tracks:

| Track | URL | What it is |
|---|---|---|
| **Drive API** (ours) | `developer.doordash.com` | The developer portal + API access we need |
| Drive On-Demand merchant funnel | `drive-onboarding.doordash.com` | Sales/lead form → Storefront or manual-form plans, **$6.99–$10.99 flat per delivery**. Bounces to `merchants.doordash.com` sales page. Not the API. |

If self-serve API access stays closed, that merchant funnel (or
+1-855-222-8111) is the human path — but it sells a different product, so treat
it as an escalation route, not a substitute.

---

## Go-live steps once access is granted

Entirely mechanical — no code changes.

1. **Portal → Credentials → Create credential**, Environment = **Production**,
   API = Drive. **Copy the signing secret immediately** (shown once).
2. **Portal → Webhooks → Production tab → Add endpoint**:
   `https://koshereats-api.fly.dev/api/v1/webhooks/doordash`, auth type Basic,
   header `Authorization`, **Generate** a token and copy it.
3. **Swap the Fly secrets** (single command, triggers one rolling deploy):
   ```
   fly secrets set -a koshereats-api \
     DOORDASH_DEVELOPER_ID=<prod developer id> \
     DOORDASH_KEY_ID=<prod key id> \
     DOORDASH_SIGNING_KEY=<prod signing secret> \
     DOORDASH_WEBHOOK_SECRET=<prod webhook bearer token>
   ```
4. **Add a billing credit card** — Portal → Organization → Business details. Every
   other field there is already filled (go-live date 09/01/2026). This is the one
   item nobody but Salto can do.
5. **Verify** before trusting it with a customer order:
   ```
   curl -s -o /dev/null -w "%{http_code}\n" -X POST \
     https://koshereats-api.fly.dev/api/v1/webhooks/doordash \
     -H "Authorization: Bearer <prod webhook token>" \
     -H 'Content-Type: application/json' \
     -d '{"external_delivery_id":"00000000-0000-4000-8000-000000000001","event_name":"DASHER_DROPPED_OFF"}'
   ```
   Expect `200`. A wrong token must give `401`. Then watch
   `fly logs -a koshereats-api | grep doordash` on the first real dispatch.
6. **Confirm pricing** against `delivery-model-b`'s 3-tier marketplace fee
   ($1/$2/$3). Sandbox quoted $9.75 and the merchant funnel advertises
   $6.99–$10.99 flat; if real Drive quotes land above the Uber quote regularly,
   the cheapest-provider selection just won't pick Drive — which is correct
   behavior, not a bug.

---

## Gotchas (each one cost real debugging)

- **Webhooks are NOT body-signed.** Unlike Uber Direct's `X-Uber-Signature`
  HMAC, Drive echoes a **static bearer token** in a header you choose. An HMAC
  check can never match. See `doordash.Client.VerifyWebhook`.
- **Webhooks carry `event_name`, not `delivery_status`,** with UPPER_SNAKE values
  (`DASHER_DROPPED_OFF`). `delivery_status` exists only on quote/create API
  *responses*. Reading the wrong field made every webhook a silent no-op that
  still returned 200 — orders stranded at `ready` forever with nothing in the
  logs. The opt-in tracking events (`dasher_enroute_to_pickup`) are lowercase, so
  normalize case.
- **`dasher_location` is a nested `{lat,lng}` object,** not flat
  `dasher_location_lat`/`_lng`.
- **Quote time fields are `pickup_time_estimated` / `dropoff_time_estimated`,**
  not `estimated_*_time`.
- **A non-UUID `external_delivery_id` used to 500 forever.** `orders.id` is a uuid
  column, so it fails with SQLSTATE 22P02 instead of matching zero rows, and
  DoorDash retries 500s. The handler now ACKs those. Note `uuid.Parse` is laxer
  than Postgres (it accepts `urn:uuid:…`), so the handler queries on the
  canonical `parsedID.String()` — validate *and* normalize.
- **Sandbox deliveries don't auto-progress.** Advance them in the Delivery
  Simulator; that is what fires webhooks.
- **The Simulator's own "Create delivery" button mints non-UUID ids,** which is
  exactly how the poison pill above gets triggered.
- **`internal/handlers` tests need Postgres on :5433** (`TestMain` exits without
  it). With no Docker/local PG, verify handler behavior by curling the deployed
  endpoint instead.
