# Temporal courier-payout go-live runbook — self-hosted on Fly

**Status: PLAN ONLY.** This provisions a new billed Fly Postgres + Fly app and,
on the final step, changes how every courier payout in production moves money.
Do not run the provisioning or secrets steps until you've decided to do this.
Owner: Salto (Fly billing + the decision to flip the switch).

---

## Current state (verified against `main` and prod Fly config, 2026-07-02)

- The Temporal integration is **fully built and merged to `main`** (`f8dcab87`,
  `c0f1f5c3`, `30e7d75d`, `cd5e582e`) but **dormant** — gated behind
  `cfg.Temporal.HostPort` being empty ([config.go](../backend/internal/config/config.go),
  [main.go](../backend/cmd/api/main.go)). No `TEMPORAL_*` secret exists on
  `koshereats-api` today (`fly secrets list` confirms), so prod is unaffected.
- `internal/payout/payout.go` implements `PayoutWorkflow`: claim (processing) →
  Stripe transfer (idempotent, retried 6×) → complete. The Stripe idempotency
  key is resolved from the same `courier_payout_queue` row id the legacy sweep
  uses, so a payout re-attempted across an on/off cutover dedupes at Stripe
  instead of double-paying. Now covered by `internal/payout/payout_test.go`
  (workflow-level tests against mocked activities — happy path, claim-failure
  short-circuit, transfer-failure-after-6-retries → MarkFailed).
- The worker runs **in-process inside the API binary** — no separate worker
  deploy needed. When `TEMPORAL_HOSTPORT` is set, `cmd/api/main.go` dials the
  client, starts a worker on the payout task queue, and injects a
  `*payout.Starter` into the handler + scheduler. `dispatcher.go`'s
  `sweepCourierPayouts` then starts (or dedups into) a workflow per due row
  instead of transferring directly.
- Code already supports Temporal Cloud auth (API key or mTLS) **or** a plain
  insecure connection for a local/self-hosted server — you chose self-hosted,
  so we use the insecure path (no `TEMPORAL_API_KEY`/`TEMPORAL_TLS_CERT` needed).
- **The self-hosted server is now PROVISIONED and verified (2026-07-02).**
  Apps `koshereats-temporal` (the server) + `koshereats-temporal-db` (its own
  Postgres) are deployed in `iad`, healthy, reachable at
  `koshereats-temporal.internal:7233` over 6PN. The payout demo ran against it
  and both workflow executions persisted server-side. Config lives in
  [`infra/temporal-server/`](../infra/temporal-server/). Phases 1–2 done.

**Net:** the only gate left is Phase 3 — flipping the `TEMPORAL_HOSTPORT`
secret on `koshereats-api` so prod payouts route through the (already running)
server. That's the real-money step.

---

## The three phases

| Phase | Goal | Creates billed infra? | Touches real money? |
|---|---|---|---|
| 1 | Provision the Temporal server on Fly | yes | no |
| 2 | Verify it end-to-end (no prod traffic) | no | no |
| 3 | Cut prod payouts over | no | **yes** |

---

## Phase 1 — Provision the Temporal server on Fly

Two new Fly resources, isolated from `koshereats-db` on purpose (Temporal's
schema/load shouldn't contend with the app DB, and a bad Temporal migration
should never be able to touch app tables).

> ⚠️ **Two Fly-specific gotchas hit the first time through — both baked into the
> committed config, but you'll re-learn them if you rebuild from scratch:**
> 1. **256MB Postgres OOM-crash-loops.** `fly postgres create --vm-size
>    shared-cpu-1x` defaults to **256MB**, which postgres-flex can't hold up
>    under Temporal's schema-setup + connection load — it goes unhealthy and
>    Temporal crash-loops on `[EOF EOF]` DB errors. Bump to **1GB** (same fix as
>    `koshereats-db`). Do it right after create.
> 2. **auto-setup binds to the wrong interface for Fly.** Its entrypoint defaults
>    `BIND_ON_IP` to the eth0 IPv4 (`172.19.x.x`), but Fly's private network is
>    **IPv6-only** — so nothing (not the backend, not `fly proxy`) can reach the
>    frontend and you get connection resets. Fixed by the wrapper image in
>    `infra/temporal-server/` that pins `BIND_ON_IP=$FLY_PRIVATE_IP`.

### 1a. Dedicated Postgres for Temporal

```bash
fly postgres create --name koshereats-temporal-db --org personal --region iad \
  --vm-size shared-cpu-1x --volume-size 3 --initial-cluster-size 1
# ⚠️ then immediately bump RAM off the 256MB default (see gotcha #1):
fly machine list -a koshereats-temporal-db          # grab the machine id
fly machine update <machine-id> -a koshereats-temporal-db --vm-memory 1024 --yes
```

**Save the `postgres` admin username/password from the create output — Fly shows
it once.** auto-setup needs `CREATEDB` rights to create its own `temporal` and
`temporal_visibility` databases and run schema migrations, so use the admin
role directly — **not** a `fly postgres attach` app user (scoped to one existing
DB, can't create new ones).

### 1b. Temporal server app

`temporalio/auto-setup` bundles the server + automatic schema setup — no manual
migration step. Pinned tag `1.29.7` (no `1.30.x` image exists yet; minor
client/server skew is supported, fine against `go.temporal.io/sdk v1.45.0`).

The deploy config is committed at [`infra/temporal-server/`](../infra/temporal-server/):
`fly.toml`, a thin `Dockerfile` wrapping the stock image, and `fly-entrypoint.sh`
(the IPv6-bind fix, gotcha #2). Deploy from that directory so the Docker build
context includes the wrapper files:

```bash
fly apps create koshereats-temporal --org personal
cd infra/temporal-server
fly secrets set -a koshereats-temporal POSTGRES_PWD='<the admin password from 1a>'
fly deploy -a koshereats-temporal
```

There is deliberately no `[http_service]` block — the app is reached only over
6PN at `koshereats-temporal.internal:7233`, never internet-exposed.

### 1c. Confirm it's healthy

```bash
fly status -a koshereats-temporal
fly logs -a koshereats-temporal | grep -iE "Started Worker|Acquired shard|unable to connect to DB"
```

Healthy = machine `started` and NOT reboot-looping, `Started Worker` /
`Acquired shard` present, and **no** `unable to connect to DB` lines. The
`TEMPORAL_ADDRESS ... setting it to fdaa:...:7233` line confirms the IPv6 bind
fix is active (an eth0 `172.19.x.x` there means the wrapper didn't run).

---

## Phase 2 — Verify end-to-end (still zero prod impact)

Tunnel to the private app from your laptop and drive it with the `temporal`
CLI already installed locally (v1.6.2):

```bash
fly proxy 7233:7233 -a koshereats-temporal
# in another terminal:
temporal operator namespace describe --address localhost:7233 --namespace default
temporal workflow list --address localhost:7233
```

Then run the SAME payout demo you already proved locally against this
self-hosted server instead of `temporal server start-dev`:

```bash
cd /Users/samma/projects/Mamiye-Eats/temporal
# poc/payout/campaign demos default to 127.0.0.1:7233, which the fly proxy above
# now forwards to the real self-hosted server — no code change needed.
go run ./payout/demo
```

Confirm the same `PASS ✅` output as before, but this time the workflow
history is durable on Fly Postgres, not an ephemeral dev-server DB. Check it
in `temporal workflow list --address localhost:7233`.

---

## Phase 3 — Cut prod payouts over

This is the money step. Do it in a low-order-volume window so you can watch
the first few real payouts land.

```bash
fly secrets set -a koshereats-api \
  TEMPORAL_HOSTPORT=koshereats-temporal.internal:7233 \
  TEMPORAL_NAMESPACE=default \
  TEMPORAL_TASK_QUEUE=payout-task-queue
fly deploy -a koshereats-api
```

On boot, `main.go` logs `temporal payouts enabled` — confirm it:

```bash
fly logs -a koshereats-api | grep "temporal payouts enabled"
```

Watch the next real courier delivery reach `sweepCourierPayouts`:

```bash
fly logs -a koshereats-api | grep -i payout
```

and cross-check the workflow completed in Temporal:

```bash
fly proxy 7233:7233 -a koshereats-temporal &
temporal workflow list --address localhost:7233 --query 'WorkflowType="PayoutWorkflow"'
```

### Rollback

Zero code changes, zero DB changes — the queue rows are shared by both paths:

```bash
fly secrets unset -a koshereats-api TEMPORAL_HOSTPORT
fly deploy -a koshereats-api
```

The next deploy skips the Temporal dial entirely and `sweepCourierPayouts`
falls straight back to direct Stripe transfers, unchanged.

---

## Known trade-off — no app-level auth on the Temporal frontend

Self-hosting without `TEMPORAL_API_KEY`/mTLS means any other Fly app in this
org that can resolve `koshereats-temporal.internal` can dial the frontend and
start/query workflows on the `payout-task-queue`. This mirrors how
`koshereats-db` is already reached — internal-6PN-only, not internet-exposed —
but there's no per-app credential the way `DATABASE_URL` has one. Acceptable
for now; if it ever matters, add mTLS client certs (`TEMPORAL_TLS_CERT`/
`TEMPORAL_TLS_KEY` — the code already supports it) or move to Temporal Cloud.

---

## Reference

- Workflow code: [`backend/internal/payout/payout.go`](../backend/internal/payout/payout.go)
- Workflow tests: [`backend/internal/payout/payout_test.go`](../backend/internal/payout/payout_test.go)
- Wiring: [`backend/cmd/api/main.go`](../backend/cmd/api/main.go) (search `Temporal durable payouts`)
- Config: [`backend/internal/config/config.go`](../backend/internal/config/config.go) (`TemporalConfig`)
- Local demos proving the same workflow logic against `temporal server start-dev`:
  [`temporal/payout/demo/main.go`](../temporal/payout/demo/main.go)

### Status snapshot (checkboxes = done)

- [x] `PayoutWorkflow` built, code-reviewed, merged to `main`
- [x] Idempotency-key parity between legacy sweep and Temporal path verified
      (stale TODO in `dispatcher.go` warning about a mismatch — removed 2026-07-02,
      the mismatch doesn't exist in the shipped code)
- [x] Workflow unit tests (`payout_test.go`, 2026-07-02)
- [x] Temporal server provisioned on Fly (Phase 1, 2026-07-02) — `koshereats-temporal`
      + `koshereats-temporal-db` (1GB), config in `infra/temporal-server/`
- [x] End-to-end verified against the self-hosted server (Phase 2, 2026-07-02) —
      payout demo PASS, both executions persisted server-side
- [ ] Prod cutover (Phase 3) — **money step, deliberately not done**
