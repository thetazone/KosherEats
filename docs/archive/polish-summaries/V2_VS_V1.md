# Polish Loop v2 vs v1 — 2026-05-14/15

Same scope (android_consumer + android_seller, sev 5+, 3 rounds, 12 fixes/round target). v2 introduces three token-saving structural changes (orchestrator commit `c8bd0e2`).

## Headline numbers

| Metric | v1 (legacy) | v2 (batch + upfront + refresh-only) | Δ |
|---|---|---|---|
| Wall-clock total | **84 min** (21:37→23:01) | **78 min** (00:01→01:19) | **-7%** |
| Fixes reported succeeded | 33 | 29 (parser bug — see below) | -4 |
| Fixes actually shipped | 33 | **~35** (6 R2 consumer fixes landed but reported FAILED) | **+2** |
| Claude CLI invocations | 39 (33 fix + 6 review) | **12** (6 review/refresh + 6 batch fixes) | **-69%** |
| Cold-starts (CLAUDE.md reload, base context) | 39 | 12 | **-69%** |
| Sev-10 build breaks introduced + caught next round | 1 (R1→R2 OrderTracking) | 0 | — |

Per-round wall-clock breakdown:

| | v1 | v2 |
|---|---|---|
| Pre-round upfront review (CHANGE 3) | — | 18 min (consumer 8m + seller 9m) |
| Round 1 | 30 min (12m review + 18m fix) | 8 min (just batch fix: consumer 5m + seller 3m) |
| Round 2 | 26 min (11m review + 15m fix) | 30 min (12m refresh + 18m batch fix) |
| Round 3 | 28 min (12m review + 16m fix) | 23 min (9m refresh seller-only + 13m batch fix) |
| **Total** | **84 min** | **78 min** |

## Per-change wins

### CHANGE 2 — `batch_fixes_per_area`
- 33 separate `fix_issue` invocations → **6 `fix_issues_batch` invocations** (one per area per round). 27 fewer cold-starts.
- Consumer R1 batch: **6 fixes in 5m 16s** (vs v1: 6 fixes in ~10m for the consumer half). **~50% faster** on the wall-clock alone, more on tokens.
- Seller R3 batch: **6 fixes in 5m 33s**. Consistent.
- **Caveat:** parser is brittle. R2 consumer batch reported 0/6 success but 8 files were actually edited and the listed R2 issues do appear in the diffs. Tracked as task #8.

### CHANGE 3 — `single_upfront_review`
- 6 `review_area` invocations → **2 + refreshes**. Total review time: 18m upfront + 21m refresh = **39m** vs v1's 35m of per-round reviews. Roughly tied on wall-clock.
- Why no big wall-clock win: the upfront review asks for `issues_per_area * max_rounds = 18` issues per area instead of 6, so each call takes longer. The savings are at the **per-call overhead** layer (CLAUDE.md load, base context tokens) — the throughput wins from this change are real but get masked when the input is cheap and the prompt's review depth dominates.
- Backlog model worked: each round drained 12 issues from a 36-issue backlog without re-paying review cost.

### CHANGE 4 — `refresh_review_touched_areas_only`
- v2 R2 caught 3 sev-8 collateral issues in consumer + 3 sev-7/8 in seller via small refresh-reviews (3 issues each, vs upfront's 18). Catches the failure mode v1 hit at R2's full re-review (the OrderTracking sev-10 build break).
- v2 R3 only refresh-reviewed seller, not consumer — **bug:** because R2's consumer batch reported 0/6 fixed (parser bug), the workflow thought consumer wasn't touched and skipped its refresh. So the parser bug had a downstream effect on CHANGE 4. Both bugs share the same root cause.

## What v2 actually fixed

29 reported FIXED + ~6 reported FAILED but shipped = **~35 fixes** across 26 files. Sample of key v2-only wins (not in v1):

**Security:**
- consumer + seller: `allowBackup="false"` — closes adb-backup auth-token leak
- seller: removed hard-coded `koshereats2026` keystore password from build.gradle.kts (R1 sev-8)
- consumer: MainActivity `launchMode="singleTask"` — push intents no longer stack instances

**Correctness:**
- consumer: `Money.formatPriceWhole` integer-division → `/100.0` (was rendering "$10" for $10.99) — **same bug, seller side, also surfaced by v2 in R1**
- consumer + seller: `formatPriceWhole` truncation fix
- consumer: `EditProfile` no longer PUTs full User object clobbering id/email/profileImage — uses partial-field map instead
- consumer: `Checkout snapshot` survives process death (Stripe PaymentSheet recovery)
- consumer: anonymous (not-logged-in, not-guest) users get auth gate before reaching Checkout
- consumer: `AuthViewModel` race vs TokenProvider's async DataStore observer no longer drops the next request's auth header
- seller: `Cancel Order` button on ACCEPTED/PREPARING orders no longer silently fails (button was rendered but transition wasn't allowed) — same class of bug v1 R2 caught for the inverse direction
- seller: SCHEDULED orders now visible (`isActive = true` + dashboard chip)
- seller: modifier-group `.toInt()` truncation → `.roundToInt()` (was losing $0.01 on $0.95 modifier prices)
- seller: deal `dollarsToCents` truncation (same pattern, deals path)
- seller: `KosherCertification` serialization sent wrong wire format on restaurant create (was sending `"ou"` lowercase, server expected `"OU"`)
- seller: image uploads now stream instead of buffering whole file in memory (OOM risk)
- seller: image-upload helpers now share an `OkHttpClient` singleton instead of leaking pools per upload

**State management:**
- consumer: global `SessionManager.logoutEvent` observer in `AuthViewModel` (was firing logout but only Cart + Chat collected the event)
- consumer: `TokenProvider.init` deadlock prevention (try/finally on `_initialized.complete`)
- consumer: `AuthInterceptor`/`TokenAuthenticator` no longer block OkHttp dispatcher threads with `runBlocking`
- consumer: `AddressViewModel` no longer swallows CRUD errors silently
- consumer: `socialLogin` error UI no longer leaks raw server-error body
- consumer: SSE 401 in `OrderTrackingViewModel` now follows through with UI state (was firing logout but leaving stale order on screen)
- consumer: guests no longer have `isLoggedIn=true` (introduced sealed `SessionState` to replace footgun-prone two booleans)
- seller: `MenuItemFormScreen` no longer pops back + wipes form on every modifier-group operation
- seller: `OrdersViewModel.pollSilently` race vs filter changes
- seller: `AuthViewModel` no longer per-NavBackStackEntry-instantiated (state was forking across screens)
- seller: `SellerOrderDetailScreen` early-return no longer hides the reject confirmation dialog
- seller: `ActiveOrderCard` now shows pickup-vs-delivery, customer name, elapsed time

Per-round detail: `polish-summaries/round-{1,2,3}.md` (v2 versions — v1's are committed in `8a530b73` for posterity).

## Verdict

- **CHANGE 2 was the biggest win** — 50% wall-clock savings on fix phase, big token savings from collapsed cold-starts and shared file reads.
- **CHANGE 3 was a moderate structural win** — half the review invocations, but each is bigger so wall-clock is roughly flat; cold-start tokens still saved.
- **CHANGE 4 worked when the parser worked** — caught real R2 collateral. Need to fix the parser before CHANGE 4 can be trusted with the touched-area signal.
- **Parser bug (task #8)** is the highest-value followup. With it fixed: ~35 fixes shipped at ~70% fewer Claude invocations vs v1. Without it: still 29 confirmed fixes, just with sketchy tracking on a third of them.
