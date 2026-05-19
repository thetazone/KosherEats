# KosherEats Polish — Round 19
**Max severity found:** 8
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[8/10] [android_consumer] TokenProvider startup race: API calls fire before encrypted token loads, triggering spurious 401s and unexpected logouts** — FIXED
  RetrofitClient.kt:62-68 loads tokens off-thread via `Thread { token = prefs.getString(...) }.start()`, but `awaitToken()` (line 70) just returns the @
  > Both changes look correct. Here's what was done:

**`RetrofitClient.kt`**
- Added a `CompletableDeferred<Unit> loaded` f

- **[8/10] [android_seller] Menu form cannot target seller's existing custom categories; edits silently can't re-categorize** — FIXED
  The category picker on the form (MenuItemFormScreen.kt:122-124, 384-398) is hard-bound to the fixed MenuCategory enum (APPETIZERS/MAINS/SIDES/etc.) pl
  > The only remaining `MenuCategory` enum references are in `OnboardingScreen.kt` and `OnboardingViewModel.kt` — those are 

- **[7/10] [android_consumer] Silent exception swallowing in ViewModels leaves users stuck in loading states with no error UX** — FIXED
  Several ViewModels catch exceptions and either return null or do nothing, never surfacing the failure to UiState. Examples: CheckoutViewModel.kt:429-4
  > All changes are correct. Here's a summary of every change made:

**CheckoutViewModel.kt:**
- `loadAddresses()`: Added `e

- **[7/10] [android_seller] Menu list trash icon deletes immediately with no confirmation** — FIXED
  In MenuManagementScreen.kt:332-342, the per-row trash icon wires `onClick = onDelete` → `viewModel.deleteMenuItem(item.id)` (line 185), which fires th
  > Done. Three changes to `MenuManagementScreen.kt` only:

1. **Imports** — added `AlertDialog`, `TextButton`, `mutableStat

- **[6/10] [android_consumer] Stub-address detection uses both-zero heuristic — false positives at the equator and silent acceptance of partial stubs** — FIXED
  CheckoutViewModel.kt:379 guards against placeholder addresses with `address!!.latitude == 0.0 && address.longitude == 0.0`. This has two problems: (1)
  > Both changes are correct. Here's a summary:

**`Models.kt`** — added `isGeocoded` to `Address` (line 100), mapped from t

- **[6/10] [android_consumer] login-error-double-bang** — FIXED
  Login/Register/EmailLogin/PhoneAuth/PhonePrompt render errors with `Text(state.error!!, ...)` after a non-null check on the prior line (LoginScreen.kt
  > All four TOCTOU races fixed. Replaced `if (state.error != null) { ... state.error!! }` with `state.error?.let { msg -> .

- **[6/10] [android_seller] OrdersViewModel.refresh/pollSilently can produce duplicate rows after pagination and silently drop already-loaded pages when server caps `limit`** — FIXED
  Two related defects in the orders state machine:

1) Pagination duplication after refresh (lines 479-518, 257-289). `refresh()` re-fetches with `limit
  > Both fixes are applied. Here's a summary of what changed and why:

**Fix 1 — `refresh()` (lines 479-521):**
- Added `loa

- **[5/10] [android_consumer] ProGuard rules miss Compose Navigation type-safe args and have an over-broad `data.models.**` keep that obscures truly-needed rules** — FIXED
  proguard-rules.pro:4 uses `-keep class com.koshereats.consumer.data.models.** { *; }` which is correct for Gson reflection but blanket-keeps every fie
  > The fix widens the `-keepclassmembers enum` rule from `data.models.**` to `com.koshereats.consumer.**`, matching the sco

- **[5/10] [android_consumer] Tracking ignores STOP race** — FIXED
  OrderTrackingViewModel.pause() calls stopInternal(), which cancels only pollJob and streamJob. But setupJob is the coroutine that creates pollJob and 
  > `stopInternal()` now cancels `setupJob` first, so any in-flight `loadOnce` is interrupted before it can spawn `pollJob`/

- **[5/10] [android_consumer] permission-no-rationale-ui** — FIXED
  Two runtime-permission asks ship without rationale UI or denial recovery: MainActivity.kt POST_NOTIFICATIONS (RequestNotificationPermissionIfNeeded, l
  > Both files look correct. Here's a summary of what changed:

**`MainActivity.kt` — `RequestNotificationPermissionIfNeeded

- **[5/10] [android_consumer] loadmore-pagination-totalitems-zero** — FIXED
  HomeScreen.kt loadMore detection (lines 100-108) uses `lastVisibleItem >= totalItems - 3`. On first composition before any items load, totalItems=0 an
  > Fixed. Added `totalItems > 0 &&` guard to HomeScreen.kt line 104, making it match the pattern already used in OrdersScre

- **[5/10] [android_seller] Dashboard "Active Orders" list silently capped at 50 with no overflow indicator; stat counter can disagree with the list** — FIXED
  The dashboard list shows `state.activeOrders` (DashboardViewModel.kt:146, 181, 210), computed as `getOrders(status = null, limit = 50).filter { it.sta
  > Everything looks correct. Here's a summary of all changes made:

**`DashboardViewModel.kt`** (3 changes):
- `loadDashboa

- **[5/10] [android_seller] FCM events lost while app is backgrounded** — FIXED
  OrderEventBus uses MutableSharedFlow(replay = 0). When KosherEatsMessagingService.onMessageReceived fires while the dashboard/orders VMs are not activ
  > That's the entire fix. `replay = 1` means the flow retains the most recent event. When `startPolling()` launches `eventJ

- **[5/10] [android_seller] No proactive refresh on dashboard re-entry** — FIXED
  DashboardViewModel.init runs once per VM lifetime; startPolling() only schedules the next backoff iteration in 30s (BACKOFF_DELAYS[0]) on first call. 
  > The fix is correct. Here's a summary of what changed:

**`DashboardViewModel.kt` — 4 changes:**

1. **New field** `@Vola

- **[5/10] [android_seller] loadOrderDetail does not set isLoading, screen shows spinner forever on error** — FIXED
  OrdersViewModel.loadOrderDetail (lines 288-312) sets `error = null` but never sets `isLoading = true`. SellerOrderDetailScreen.kt:197-205 shows Circul
  > Both changes look correct. Here's what was done:

**`OrdersViewModel.kt`**:
- Added `isLoadingDetail: Boolean = false` t

- **[5/10] [android_seller] RestaurantPickerViewModel.load silently swallows HTTP failures** — FIXED
  RestaurantPickerViewModel.kt:46-69 calls `apiService.listRestaurants()` then unconditionally `response.body().orEmpty()` without checking isSuccessful
  > Fixed. The change adds an `isSuccessful` check immediately after the API call. On any non-2xx response, it sets `error` 
