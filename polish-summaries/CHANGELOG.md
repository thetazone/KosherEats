# Mamiye-Eats Android Polish — Master Changelog

Cumulative inventory of orchestrator-driven fixes on the Android consumer + seller (+ courier in earlier runs) apps. Two sessions: the prior 2026-05-13/14 session shipped 33 fixes plus the production R8 hotfix; this session (2026-05-14 evening) added 33 more fixes scoped to consumer + seller only.

**Combined total: 70 distinct fixes shipped + 1 production hotfix.**

---

## Session 2 — 2026-05-14 evening (THIS SESSION)

Workflow: `polish-android-consumer-seller` (CodebasePolishLoop), areas = `[android_consumer, android_seller]`, `issues_per_area=6`, `stop_threshold=4` (sev 5+), `max_rounds=3`. 33 fixes succeeded out of 33 attempted (3 sev-4 / sev-3 issues filtered out — workflow asked for top-12 per round but only fixes those above threshold).

**Round-by-round summary files:** `polish-summaries/round-{1,2,3}.md`.

### Round 1 — 11 fixes (max sev 9)
| Sev | Area | Fix |
|---|---|---|
| 9 | consumer | Race + guest 401s trigger spurious global logout (clears cart, deletes FCM token) |
| 7 | consumer | Home feed pagination dead code — `hasMore` compared against wrong page size (50 vs 20) |
| 7 | consumer | Sign Out doesn't clear nav back stack — stale authed screens reachable |
| 7 | seller | Reject Order missing confirmation dialog |
| 7 | seller | Settings screen lets unapproved sellers toggle Open |
| 7 | seller | `createMenuItem` creates duplicate server-side categories |
| 6 | consumer | OrderTracking polling/SSE never pauses when screen off-screen |
| 6 | seller | Process-wide 30s polling runs on every screen across multiple VMs |
| 6 | seller | FCM notifications stack instead of replacing in place |
| 6 | seller | `provideOkHttpClient` runBlocking DataStore read on main thread |
| 5 | consumer | Push notification taps drop `order_id` — no deep link |

### Round 2 — 12 fixes (max sev 10)
| Sev | Area | Fix |
|---|---|---|
| 10 | consumer | `OrderTrackingScreen.kt` unresolved `LaunchedEffect` — consumer wouldn't compile (collateral from R1) |
| 8 | consumer | Order-tracking notifications collapse onto single PendingIntent |
| 7 | consumer | AuthViewModel marks user logged-in with null profile on transient errors |
| 7 | seller | `loadMenuItem` returns stale cached item after modifier-group changes |
| 7 | seller | Retrofit R8 rules incomplete — missing `EnclosingMethod` + `-if interface` for ApiService generics |
| 7 | seller | Cannot cancel an order after accepting it (only PENDING→CANCELLED was allowed) |
| 6 | consumer | `loadMore` advances `currentPage` before network call → skipped pages on failure |
| 6 | consumer | `OrderStatus.stepIndex` plateau — 3 statuses all returned step=4 |
| 6 | seller | `createMenuItem` silently creates duplicate categories on network failure |
| 6 | seller | `OrdersViewModel` polls selected order forever, recreated per NavBackStackEntry |
| 5 | consumer | Deprecated `Icons.Filled.ArrowBack` (5 files) + locale-less `String.format` |
| 5 | seller | Settings shows hardcoded "v1.0.0" while app ships at 1.0.7 |

### Round 3 — 10 fixes (max sev 8)
| Sev | Area | Fix |
|---|---|---|
| 8 | consumer | `POST_NOTIFICATIONS` runtime permission never requested (Android 13+ silent push drop) |
| 8 | seller | OrderDetail polling skipped when entered from Dashboard (status changes invisible until refresh) |
| 8 | seller | Active orders silently capped at 20 by `getOrders` default limit |
| 7 | consumer | Delete-account navigates to Login before API call fires (Play Store compliance — account never deleted server-side) |
| 7 | seller | New-order push has no distinctive salience + no deep-link to order |
| 7 | seller | Order detail omits customer name, phone, and order placement time |
| 6 | consumer | Tip picked in Cart silently discarded on the way to Checkout (couriers losing tips) |
| 6 | seller | Polling sleeps 30s before first poll + Orders list has no pull-to-refresh |
| 5 | consumer | FCM order deep-link can stack duplicate OrderTracking screens + bypass auth |
| 5 | seller | `PICKED_UP → COMPLETED` transition exposed to seller for delivery orders (should be courier-only) |

**Skipped this session (sev ≤ 4):**
- consumer: deprecated `Icons.Filled.ArrowBack` RTL — *re-surfaced & fixed in R2*
- consumer: non-`data.models` request DTOs not covered by R8 keep rule (sev 4)
- consumer: `CheckoutScreen` imports deprecated platform `LocalLifecycleOwner` (sev 3)

---

## Session 1 — 2026-05-13/14 (PRIOR SESSION)

### Production hotfix (hand-coded)
- **`5d385962`** — Seller social-login catch block surfaces real exception class+message instead of generic "Connection error". `-keepnames class * extends java.lang.Throwable` added so class name survives obfuscation. Diagnostic patch that exposed the actual ClassCastException.
- **`73087790`** / **`d87c0c40`** — Seller bumped 1.0.4→1.0.5→1.0.7 (versionCode 8→9→11). The R8 keep-rule fix (`d87c0c40`) added 3 mandatory rules to seller, courier, greeneats-seller, greeneats-courier `proguard-rules.pro`:
  ```
  -keep,allowobfuscation,allowshrinking interface retrofit2.Call
  -keep,allowobfuscation,allowshrinking class retrofit2.Response
  -keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
  ```
  Root cause: R8 stripped the generic `Signature` attribute on `Continuation<Response<LoginResponse>>`, breaking Retrofit's reflection. Surfaced as `ClassCastException: Class cannot be cast to ParameterizedType` only in release builds. Consumer + greeneats-consumer already had these rules — that's why consumer worked.
- **`4130e97a`** — Seller onboarding escape hatch (Android + iOS). Logout icon in TopAppBar `actions` slot on every step of `OnboardingScreen.kt` with AlertDialog confirm; iOS got matching `rectangle.portrait.and.arrow.right` toolbar icon with `.confirmationDialog`. Reason: user signs in with wrong Google account → was stuck in 5-step wizard with no way out.

### Polish loop run 1 — `polish-android` (commit `c630865a`)

3 rounds × 9 fixes = **27 fixes** across consumer/seller/courier (sev 4-10). All compiled green on `:app:compileDebugKotlin`. Per-round summary files were overwritten by the subsequent seller-sev8plus run, so only sev-8+ highlights are recoverable from commit body:

**Sev 8+ highlights:**
- seller: missing `kotlinx.coroutines.isActive` import broke release build (sev 10 — pure build break)
- courier: `LocationForegroundService.startForeground()` skipped in `EXTRA_DELIVERY_ACTIVE` branch — invalid FG service state on Android 14+ (sev 9)
- seller: no polling fallback when FCM drops → silently miss orders (sev 8)
- seller: Cancel-from-ACCEPTED hit the PENDING-only `/reject` endpoint (sev 8)
- seller: FCM order pushes flickering UI to spinner/empty (sev 8)
- courier: back-press silently signed courier offline + killed GPS (sev 8)
- courier: Repository list methods swallowed HTTP errors as `Result.success(emptyList())` (sev 8)
- consumer: `runBlocking` inside `TokenAuthenticator`'s synchronized block (sev 7)
- consumer: `collectAsState()` (not `collectAsStateWithLifecycle()`) draining OrderTracking SSE while backgrounded (sev 7)

Plus 19 more sev 4-7 fixes touching: consumer auth screens, chat, checkout, profile, ratings, restaurant detail, order tracking, courier dashboard/earnings/viewmodel, seller dashboard/orders/menu/viewmodels — see `c630865a` diff for the full file set (38 files, +342/-167 LOC).

### Polish loop run 2 — `polish-seller-sev8plus` (commit `ce8f5648`)

3 rounds × 2 sev-8 fixes = **6 fixes**, scoped to `android_seller` only with `stop_threshold=7` (sev 8+):

| Sev | Round | Fix |
|---|---|---|
| 8 | R1 | Category filter chips silently return zero items (server vs. enum name mismatch) |
| 8 | R1 | Cold-start race: dashboard loads wrong restaurant for multi-restaurant sellers |
| 8 | R2 | Restaurant picker switch leaves `AuthViewModel.restaurant` stale (toggle mutates wrong restaurant) |
| 8 | R2 | `POST_NOTIFICATIONS` never requested at runtime — push silently dropped on Android 13+ (seller side) |
| 8 | R3 | `OrderItem.totalPrice` never deserializes — every line item renders $0.00 on tickets (`@Json(name = "total_price")`) |
| 8 | R3 | Moshi adapters outside `data.models` not kept by R8 — added `-keep class com.koshereats.seller.**JsonAdapter { *; }` |

24 sev 7 / sev 6 / sev 5 issues were surfaced in this run's per-round reviews but skipped per the sev-8+ filter — full list in `polish-summaries/2026-05-13-android-prev-session/round-{1,2,3}.md`.

---

## Cross-session totals

| Category | Count |
|---|---|
| Hand-coded production hotfixes (sev 9-10 release-blockers) | 4 |
| Polish-loop fixes — Session 1 (`c630865a` + `ce8f5648`) | 33 |
| Polish-loop fixes — Session 2 (R1+R2+R3) | 33 |
| **Total** | **70** |

By severity (combined):
- **Sev 10** (build breaks / critical compile): 2
- **Sev 9** (production crashes / data loss): 2
- **Sev 8** (significant regression risks): ~14
- **Sev 7** (meaningful bugs): ~16
- **Sev 6** (UX / state correctness): ~12
- **Sev 5** (polish / smaller bugs): ~10
- **Sev 4 and below** (cosmetic): residual, mostly skipped

By area (combined):
- `android_consumer`: ~25 fixes
- `android_seller`: ~38 fixes
- `android_courier`: ~7 fixes (Session 1 only)
