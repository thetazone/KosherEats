# Turning on Clover POS — activation runbook

The Clover integration is **already built end-to-end** and parked behind a feature
gate. Nothing here is new development — this is the last-mile checklist to switch
it on for a merchant who runs Clover. Treat it as a fast-follow, **not** a launch
blocker: the seller Orders app is the universal order pipeline; Clover only mirrors
accepted orders into a merchant's existing register so they don't double-key.

## What already exists (don't rebuild)

| Layer | Where | State |
|---|---|---|
| Adapter interface + registry + AES-GCM token crypto | `backend/internal/pos/{adapter,registry,crypto}.go` | ✅ |
| Clover adapter (OAuth + order push) | `backend/internal/pos/clover/{clover,oauth}.go` | ✅ |
| Routes: list / connect-url / test / disconnect / callback | `backend/cmd/api/main.go` (`/seller/integrations/*`, `/api/v1/integrations/clover/callback`) | ✅ |
| Order push on accept (fire-and-forget) | `handlers/orders.go:892` → `PushOrderToPOS` | ✅ |
| Seller iOS "Connect Clover" UI | `ios/seller/.../Views/Settings/IntegrationsView.swift` | ✅ |
| Seller Android Integrations screen | commit `5caf694c` | ✅ |
| Square / Toast adapters | — | ⏳ future (same shape) |

**Feature gate:** `clover.Configured()` returns false unless `CLOVER_CLIENT_ID`
and `CLOVER_CLIENT_SECRET` are both set, and the seller app hides the Connect
button when unconfigured. So setting the env vars below is literally the switch.

## Environment variables

Set on the `koshereats-api` Fly app (prod) and in local `.env` for sandbox tests.

| Var | Who issues it | Notes |
|---|---|---|
| `CLOVER_CLIENT_ID` | Clover dev dashboard | "App ID" of your Clover app |
| `CLOVER_CLIENT_SECRET` | Clover dev dashboard | "App Secret" |
| `CLOVER_OAUTH_BASE` | you (optional) | omit for prod (`https://www.clover.com`); set to the sandbox host for testing — **confirm the exact sandbox host in current Clover docs** (e.g. `https://sandbox.dev.clover.com`) |
| `POS_ENCRYPTION_KEY` | **you generate** | base64-encoded 32 bytes; encrypts stored tokens at rest. **This is the only "secret data" that is ours to generate** — Clover's client id/secret come from registering the app, not from us. |
| `PUBLIC_BASE_URL` | already set in prod | the OAuth redirect is derived from it; falls back to `https://koshereats-api.fly.dev` if unset |

> As of this writing, **none** of `CLOVER_*` or `POS_ENCRYPTION_KEY` is set in any
> local env file. Check prod with `fly secrets list -a koshereats-api` (shows names,
> never values). If `POS_ENCRYPTION_KEY` isn't there, generate a fresh one — it was
> never committed anywhere, so any earlier one is effectively lost and regenerating
> is harmless **until** a token is stored (rotating the key after a merchant connects
> invalidates their stored tokens → they must reconnect).

### Generate the encryption key
```bash
openssl rand -base64 32      # -> paste as POS_ENCRYPTION_KEY (32 raw bytes, base64)
```

## The redirect URI to register on Clover

Clover requires the redirect to match **exactly** what the backend sends:
```
https://koshereats-api.fly.dev/api/v1/integrations/clover/callback
```
(If `PUBLIC_BASE_URL` differs, it's `<PUBLIC_BASE_URL>/api/v1/integrations/clover/callback`.)

The callback is intentionally **outside** the authenticated `/seller` middleware —
the browser, not the app, hits it. A signed state-token JWT (`signPOSState` /
`verifyPOSState`) is the auth + CSRF + replay gate. Clover passes `code`,
`merchant_id`, and our `state` back on the redirect.

## Setup steps

### 1. Register a Clover app (external — Clover dev dashboard)
1. Create a developer account / app on the Clover developer dashboard
   (use **sandbox** first). Verify the current URLs/flow against Clover's docs.
2. Set the app's OAuth **redirect/site URL** to the exact callback above.
3. Grant the app the **read merchant + create/print orders** permissions it needs
   to push an order (scope names are Clover-side — confirm against their current
   permissions list).
4. Copy the **App ID → `CLOVER_CLIENT_ID`** and **App Secret → `CLOVER_CLIENT_SECRET`**.

### 2. Set the secrets
Local sandbox (`.env`):
```bash
CLOVER_CLIENT_ID=...
CLOVER_CLIENT_SECRET=...
CLOVER_OAUTH_BASE=https://sandbox.dev.clover.com   # sandbox only — confirm host
POS_ENCRYPTION_KEY=<openssl rand -base64 32 output>
PUBLIC_BASE_URL=http://localhost:8080              # so the redirect points local
```
Prod (Fly):
```bash
fly secrets set -a koshereats-api \
  CLOVER_CLIENT_ID=... \
  CLOVER_CLIENT_SECRET=... \
  POS_ENCRYPTION_KEY="$(openssl rand -base64 32)"
# PUBLIC_BASE_URL should already be https://koshereats-api.fly.dev — verify.
# Omit CLOVER_OAUTH_BASE in prod (defaults to https://www.clover.com).
```
Setting secrets restarts the app; `Configured()` flips true and the Connect button
appears in the seller app.

### 3. Sandbox end-to-end test
1. Create a **Clover sandbox merchant** (+ a test device/register) in the dashboard.
2. In the seller app (pointed at the backend that has the creds): **Settings →
   Integrations → Connect Clover** → complete OAuth in the in-app browser → expect
   the "Connected!" page → a `restaurant_pos_integrations` row is written (tokens
   stored encrypted).
3. Hit **Test** in Integrations → calls `TestPOSIntegration` (auth-only health
   check) → expect success.
4. Place a consumer order for that restaurant and **accept it** in the seller app →
   `PushOrderToPOS` fires → confirm the order lands on the Clover sandbox merchant.
   Failures are logged (`slog`), never block the accept — check API logs if it
   doesn't appear.
5. **Disconnect** → soft-deletes (`is_active=false`); the audit row survives.

### 4. Go-live
- Swap sandbox app → Clover **production** app (new client id/secret), drop
  `CLOVER_OAUTH_BASE`, re-register the prod redirect URI.
- Clover production apps go through Clover's **app-approval review** — budget lead
  time; this is the main external gating item.

## Guardrails / facts to remember
- **Per-merchant, opt-in.** A restaurant only gets POS push after *it* connects.
  Restaurants with no Clover just use the app — nothing changes for them.
- **Never a blocker.** `PushOrderToPOS` is fire-and-forget; a POS outage cannot
  break order acceptance.
- **Key rotation = forced reconnect.** Rotating `POS_ENCRYPTION_KEY` after merchants
  have connected invalidates stored tokens. Rotate only with a reconnect plan.
- **Square / Toast** are net-new adapters (implement `pos.Adapter`, register in
  `registry.go`, add OAuth endpoints) — only build when a merchant asks.
