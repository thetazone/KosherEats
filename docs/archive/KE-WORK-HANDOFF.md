# KosherEats — Work Handoff / Task Board

> **For a new session agent:** You've been assigned **one task** (e.g. "do T3").
> Read **§ Shared Context** below, then your **task card**. Everything you need to
> execute that one task is in the card. Don't start other tasks. Gate every change
> (see § Gating) and **do not `git push` or deploy without Salto's explicit OK**
> unless the card says the work is additive + green.

Last updated: 2026-06-23 · Owner: Salto (sammymamiye@gmail.com)

---

## § Shared Context

**Product.** KosherEats / Mamiye-Eats — a kosher food-delivery marketplace.
Monorepo at `~/projects/Mamiye-Eats`. Apps: Go backend (`backend/`), 3 iOS apps
(`ios/{consumer,seller,courier}`, SwiftUI), 3 Android apps
(`android/{consumer,seller,courier}`, Kotlin/Compose), Next.js web (`web/`).

**THE KEY FACT.** ✅ **SHIPPED (2026-06-23).** The 43-commit session — including the
**HIGH-severity payment security fix** — was merged to `main` (merge `4bc667e5`)
and deployed to Fly prod (revision `deployment-01KVVAY54SFH6DCWGEKH0D9F37`). The
API is healthy on the new revision; `/health` returns 200. The critical path
(T1–T3) is **complete**. Migrations 038–043 applied cleanly on boot (the healthy
boot is hard proof migration 043's unique index found zero duplicate redemptions).

- Branch HEAD: `e15b5c1d` · ahead of `main` by 43 commits.
- `ccc9d621` — consolidated review: fixed 12 issues, **2 HIGH**:
  - **PaymentIntent IDOR** — order placement didn't bind the PI to the caller.
    Fix: `verifyPI` in `backend/internal/payments/stripe.go` rejects when
    `pi.Metadata["user_id"] != userID`; call site `backend/internal/handlers/orders.go:164`.
  - **Migrations were non-fatal** — a partial migration served a broken schema.
    Fix: `backend/cmd/api/main.go` (~L95) now `log.Fatalf` on migration error.
    ⚠️ **Consequence:** a bad/failing migration now **aborts boot** (outage), so
    the DB pre-flight in **T2** is mandatory before any deploy.
- `e15b5c1d` — `backend/internal/database/migrate_chain_test.go`: CI-gated tests
  proving the full 001→043 chain applies from scratch + that migration 043
  enforces the deal limit.

**Open PRs.** #2 = this branch (`feat/seller-menu-import`, has grown far beyond
its "import menu from UberEats" title — it carries all 43 commits). #1 =
`fix/koshereats-backend-review` (older, predates this work).

**Fly prod.** DB app `koshereats-db`, database name `koshereats_api`. Reach it via
`fly proxy 16432:5432 -a koshereats-db` then psql on `localhost:16432`. Migrations
auto-run on API startup via the `schema_migrations` table; 036/037 had drift
history (hand-applied once), so always verify the chain is in sync.

**Backend config knobs** (`backend/internal/config/config.go`, all safe-defaulted):
`REDIS_URL` (default `redis://localhost:6379`; fails open to in-memory rate
limiting — fine on a single Fly machine), `STRIPE_TAX_ENABLED` (`""`→off),
`ADMIN_ALERT_EMAIL` (`""`→no alerts), `SENTRY_DSN` (`""`→off), `TEMPORAL_HOSTPORT`
(`""`→Temporal off/gated), `ADMIN_DASHBOARD_KEY` (`""`→admin dashboard 404/off).

## § Gating (run before any commit; "compile-green ≠ correct-green")

- **Backend:** `cd backend && go build ./... && go vet ./... && go test ./...`
  — `go test` needs Postgres on `localhost:5433` (the docker-compose DB:
  `postgres://postgres:postgres@localhost:5433/koshereats_test`). The migration
  tests skip cleanly without it; the handler integration suite requires it.
- **iOS:** `xcodebuild -project ios/<app>/<App>.xcodeproj -scheme <Scheme> -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`
  (SourceKit cross-file errors are known false positives; trust `xcodebuild`).
- **Android:** `cd android/<app> && ./gradlew compileDebugKotlin`.
- **Web:** `cd web && npx tsc --noEmit`.

---

## § Task Status

| ID | Task | Owner | Priority | Status |
|----|------|-------|----------|--------|
| T1 | Land the branch (merge route decision + execute) | Salto decides / Agent executes | P0 | ✅ done (Route B — merged PR #2 → `main`, `4bc667e5`) |
| T2 | Fly DB pre-flight (043 dup-check + migration sync) | Salto (Fly access) | P0 | ✅ done (validated by clean boot — 043 unique index applied, zero dups) |
| T3 | Deploy backend to Fly + watch boot | Salto (Fly access) / Agent assists | P0 | ✅ done (2026-06-23, rev `…D9F37`, /health 200) |
| T4 | Set optional Fly secrets | Salto | P2 | ⬜ optional |
| T5 | PR #1 — merge or close | Salto decides / Agent executes | P2 | ⬜ not started |
| T6 | Firebase key rotation | Salto | P1 | ✅ done (2026-06-23) |
| T7 | Temporal Cloud provisioning + payout cutover | Salto (account) / Agent executes | P2 | ⬜ future |
| T8 | GreenEats white-label consolidation | Salto (Firebase+color) / Agent executes | P2 | ⬜ future |
| T9 | Clover POS go-live | Salto | P2 | ⬜ future |

---

## T1 — Land the branch (get the HIGH fix shippable)

**Owner:** Salto picks the route; an agent can execute it.
**Priority:** P0. **Blocks:** T3.

**Why.** 43 commits incl. the HIGH IDOR fix are stuck on `feat/seller-menu-import`.

**Decision Salto must make — pick one:**
- **Route A (fast):** deploy straight from a `feat/seller-menu-import` checkout
  (T3), merge to `main` afterward. Security fix live soonest.
- **Route B (clean):** merge PR #2 → `main` first, then deploy from `main`.

**Agent steps once route chosen:**
- Route A: nothing to merge first — go to T2/T3.
- Route B: rewrite the PR #2 description to reflect its real contents (it's no
  longer just the UberEats importer — it's the whole session: IDOR fix,
  cross-platform tip parity, payout hardening, Temporal-gated-off, D1–D5,
  migration tests). Then merge.

**Acceptance:** the 43 commits are on whatever ref will be deployed in T3.

---

## T2 — Fly DB pre-flight (MUST run before any deploy)

**Owner:** Salto (needs Fly auth; can run via `!` and paste output for an agent
to read). **Priority:** P0. **Blocks:** T3.

**Why.** Migrations are now fatal (see Shared Context). Migration 043 creates a
**unique** index; if prod already has duplicate active deal redemptions, the
index fails → **the API won't boot.**

**Steps:**
1. `fly proxy 16432:5432 -a koshereats-db` (leave running in one shell).
2. Connect: `psql "postgres://<user>:<pass>@localhost:16432/koshereats_api"`.
3. **Dup-check** (must return zero rows):
   ```sql
   SELECT user_id, applied_deal_id, count(*) FROM orders
   WHERE applied_deal_id IS NOT NULL AND status NOT IN ('rejected','cancelled')
   GROUP BY user_id, applied_deal_id HAVING count(*) > 1;
   ```
   Non-zero → resolve the dups (cancel/refund the extras) **before** deploying.
4. **Migration-sync check** — paste this output for the agent to diff vs the repo
   (`backend/internal/database/migrations/` should be 001→043):
   ```sql
   SELECT name FROM schema_migrations ORDER BY name;
   ```
   If 038–043 are missing they'll apply on boot; if 001–037 aren't all recorded,
   there's drift to reconcile first (a re-run of an already-applied migration can
   now fail the boot).

**Acceptance:** dup-check returns 0 rows AND `schema_migrations` 001–037 are all
recorded (so only 038–043 apply on the next boot, cleanly).

---

## T3 — Deploy backend to Fly + watch boot

**Owner:** Salto (Fly account); agent tails logs alongside.
**Priority:** P0. **Blocked by:** T1, T2.

**Steps:**
1. From the checkout that has the 43 commits (per T1): `cd backend && fly deploy`
   (or repo-root `fly deploy` if the fly.toml lives there — confirm first).
2. Watch boot: `fly logs -a <api-app>`. Confirm migrations 038→043 apply with no
   error and `/health` returns 200. **If boot aborts on a migration, that's the
   fatal-migration guard working** — capture the failing migration, fix forward,
   redeploy. Do NOT revert the fatal-migration behavior.
3. Smoke test: place a test order end-to-end; confirm the IDOR fix doesn't block
   legitimate checkout (a normal order should still succeed — the PI's
   `Metadata["user_id"]` matches the caller).

**Acceptance:** API healthy on the new revision; 038–043 recorded in
`schema_migrations`; a real checkout succeeds.

---

## T4 — Set optional Fly secrets (only what you want on)

**Owner:** Salto. **Priority:** P2 (none block deploy). `fly secrets set KEY=val`.

| Secret | Default behavior | Set to enable |
|--------|------------------|---------------|
| `ADMIN_DASHBOARD_KEY` | admin approval dashboard → 404 (off) | the no-login restaurant-approval dashboard |
| `SENTRY_DSN` | no error tracking | Sentry |
| `ADMIN_ALERT_EMAIL` | no dispute/refund alerts | admin webhook alerts |
| `STRIPE_TAX_ENABLED` | `false` (flat tax rate) | `"true"` for Stripe Tax |
| `REDIS_URL` | in-memory rate limiting | **leave default — fine on one machine** |

**Acceptance:** chosen secrets set; `fly secrets list` shows them; redeploy if Fly
doesn't auto-restart.

---

## T5 — PR #1 decision

**Owner:** Salto decides; agent executes. **Priority:** P2.
PR #1 = `fix/koshereats-backend-review` (older, predates this session). Decide
merge vs close; if merge, check for conflicts with the 43-commit branch first.

---

## T6 — Firebase key rotation ✅ DONE (2026-06-23)

**Owner:** Salto (Google/Firebase console). **Priority:** P1.

**What was actually exposed** (committed + pushed to the *private* repo, still
recoverable from history — history purge intentionally skipped since rotation
makes the old blobs useless):
1. **APNs auth key `AuthKey_6WNGDDW939.p8`** (Apple) — leaked in commit `abb8bc4b`.
2. **Firebase admin-SDK service-account private key** `…c484c84a3c.json` (Google,
   project `koshereats-c463e`) — full admin to the Firebase project.

**Resolution (verified live in the consoles):**
- **APNs `6WNGDDW939`:** was **already rotated** — Apple portal now shows only
  `77W7RLLZTB` ("APN v2", created 2026/04/20). Leaked key revoked. Exposure closed.
- **Firebase SA `c484c84a3c`:** was **NOT** rotated — found **Active, no expiry**
  on `firebase-adminsdk-fbsvc@koshereats-c463e`. **Deleted 2026-06-23** (Keys tab
  now "No rows to display"). Safe to delete: prod has no `FCM_SERVICE_ACCOUNT_JSON`
  secret → Android push runs in stub mode, nothing consumed this key.

**NOT a problem (no action needed):** the `AIzaSy…` keys in `*.plist` /
`google-services.json` are Firebase **client API keys** — identifiers, not secrets;
safe to commit (protected by Security Rules + App Check, not secrecy).

**Follow-ups (non-blocking):**
- Local dead copy `ios/koshereats-c463e-firebase-adminsdk-fbsvc-c484c84a3c.json`
  still on disk (gitignored, now a useless credential) — fine to delete for hygiene.
- When Android push goes live, **create a fresh SA key straight into a Fly secret**
  (`fly secrets set FCM_SERVICE_ACCOUNT_JSON=@<file>`) — never commit it.
- Optional functional check: confirm Fly `APNS_KEY_ID` == `77W7RLLZTB` (the live
  Apple key) so iOS push uses the rotated key, not a stale id.

---

## Checkout hardening — 2026-06-23 (found via live prod test)

A real-device checkout test surfaced four issues; all four addressed. **Backend
changes are DEPLOYED to Fly but were committed afterward — verify HEAD matches
the running image.**

1. **Delivery "Payment Received — Order Failed" (critical).** `/payments/intent`
   priced delivery at the flat $5.99 fallback (iOS sent no address) while
   `CreateOrder` re-quoted the live courier fee (~$11.99) → $6 total mismatch →
   charge taken, order rejected. **Fix:** PI now stamps the delivery fee into
   metadata (`deliveryFeeMetaKey`) and `CreateOrder` reuses it (`StampedDeliveryFee`)
   instead of re-quoting; `/payments/intent` quotes against the cart's restaurant;
   iOS now sends `delivery_address`. Backend deploy fixes it for all clients;
   the iOS bit (accurate live fee vs flat fallback) needs a **new TestFlight build**.
2. **Card + Apple Pay only.** Checkout PI was already `["card"]`; the add-card
   SetupIntent was switched from automatic methods to `["card"]` too. **Link** is
   account-level for mobile → **Salto: Stripe Dashboard → Settings → Payment
   methods → Link → off.**
3. **Doubled-email 500.** One account had `…@gmail.com@gmail.com` → Stripe
   `email_invalid` 500. Added a `mail.ParseAddress` guard (create customer w/o
   email rather than 500) **and** corrected the row in prod (now
   `mamiyesammy@gmail.com`; 0 malformed of 82 users).
4. ~~**Stripe still in TEST mode.**~~ **OUTDATED — Stripe went LIVE in the 6/23
   cutover** (`sk_live_` verified in prod 6/25; a real-CC order succeeded). The
   `docs/stripe-go-live.md` runbook this pointed at has been **deleted**: it still
   announced "PLAN ONLY / prod is on test keys" and its step 2 was
   `UPDATE users SET stripe_customer_id = NULL`, so anyone trusting the banner
   would have nulled *live* customer IDs. That cutover step was already performed
   (8 rows cleared) — see `KE-TODO.md` § "Stripe / payments — LIVE KEYS SET".

## T7 — Temporal Cloud provisioning + payout cutover

**Owner:** Salto (Temporal Cloud account); agent executes the cutover.
**Priority:** P2 (future). The courier-payout Temporal integration is **built and
gated off** (empty `TEMPORAL_HOSTPORT` = no behavior change; commits `f8dcab87`,
`c0f1f5c3`, `95a797e1`). To go live: provision a Temporal Cloud namespace, set
`TEMPORAL_HOSTPORT` + auth (API key / mTLS) as Fly secrets, then cut payouts over.
Local dev server: `temporal server start-dev` (:7233/:8233). Temporal CLI v1.6.2
is installed locally; no SDK/server provisioned in prod yet.

---

## T8 — GreenEats white-label consolidation

**Owner:** Salto (must provide a real GreenEats Firebase project + an actual brand
color — it's still orange) ; agent executes the plan. **Priority:** P2 (future).
Full plan: `docs/white-label-consolidation.md`. ~70k duplicated client LOC across
forked `greeneats-*` apps collapses to one source per role via Gradle product
flavors (Android) + xcconfig/asset-catalog brand targets (iOS). Do the **Android
consumer flavor pilot first** (lowest risk). Backend already shared (one API,
`vertical` kosher/vegan scoping) — no backend work.

---

## T9 — Clover POS go-live

**Owner:** Salto (business decision + credentials). **Priority:** P2 (future).
Integration code + activation runbook exist (it's a turn-on checklist, not a build
task). Needs `CLOVER_CLIENT_ID` / `CLOVER_CLIENT_SECRET` / `CLOVER_API_BASE` /
`CLOVER_OAUTH_BASE` + `POS_ENCRYPTION_KEY` as Fly secrets when activating.
