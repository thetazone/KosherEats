# KosherEats Polish — Round 6 (focused retry of R5 unfixed consumer batch)
**Mode:** manual verification + targeted fix
**Issues retried:** 7
**Already-fixed (by R1-R4):** 6
**Newly fixed:** 1
**Still-present:** 0

## Outcome per issue

- **[6/10] Notification deep-link replays on rotation/process recreation** — ALREADY-FIXED
  `MainActivity.kt:30-32` calls `intent.removeExtra("order_id")` immediately after reading the extra;
  `onNewIntent` does the same. No replay possible.

- **[6/10] FCM onNewToken silently drops rotated tokens (TokenProvider race)** — ALREADY-FIXED
  `KosherEatsMessagingService.kt:38-49` now launches first, then awaits `tokenProvider.awaitToken()`
  before calling `registerDevice`. No early-null bail.

- **[4/10] Sign-out / delete-account double-navigate to Login** — ALREADY-FIXED
  ProfileScreen handlers in `NavGraph.kt:516-521` now only call `authViewModel.logout()` /
  `deleteAccount()`. Navigation happens once via the global `logoutEvent` collector at lines 119-125.

- **[4/10] OrdersScreen pagination snapshotFlow churns on every scroll frame** — ALREADY-FIXED
  `OrdersScreen.kt:74-82` now computes the boolean inside the snapshot block and chains
  `.distinctUntilChanged()` before collect — matches HomeScreen pattern.

- **[4/10] addAddress swallows server error message** — ALREADY-FIXED
  `CheckoutViewModel.kt:250-260` now parses error body and surfaces code-specific messages
  for 409 (conflict) and 422 (validation).

- **[4/10] PhonePromptScreen hardcodes "+1"** — ALREADY-FIXED
  `PhonePromptScreen.kt:55` reads `val countryCode = state.phoneCountryCode`.

- **[3/10] Pagination page size constants disagree across ViewModels** — FIXED THIS ROUND
  Added top-level `object ApiPaging` in `ApiService.kt` with `RESTAURANTS_PAGE_SIZE` and
  `ORDERS_PAGE_SIZE` constants. `getRestaurants` and `getOrders` defaults now reference them.
  Removed the duplicated `PAGE_SIZE = 20` companion-object constants in `HomeViewModel.kt`
  and `OrdersViewModel.kt`; both pagination checks now reference the centralized constant.

## Why R5 and the R6 orchestrator-batch both failed

The R5 reviewer's backlog was built UPFRONT (single_upfront_review=True) at the start of round 4.
It enumerated bugs that the intervening R4/R5 fix batches independently closed — so by the time
R5 (and the R6 retry) tried to "fix" them, the bugs were already gone. The batch agent saw nothing
to edit and returned no structured rows, which the orchestrator surfaced as "not reported by batch agent."

This is a workflow design issue, not a code issue: single_upfront_review trades freshness for token
savings. The mitigation already in the code is `refresh_review_touched_areas_only`, but that only
catches NEW collateral — it doesn't re-validate the upfront-discovered backlog against current state.
