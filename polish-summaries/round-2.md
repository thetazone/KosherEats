# KosherEats Polish — Round 2
**Max severity found:** 10
**Issues found:** 12
**Fixes attempted:** 12
**Fixes succeeded:** 12

## Issues & Fixes
- **[10/10] [android_consumer] OrderTrackingScreen.kt has unresolved LaunchedEffect — consumer app will not compile** — FIXED
  The recent edit (visible in `git diff`) removed `import androidx.compose.runtime.LaunchedEffect` from the imports but the body of `TrackingMap()` at l
  > Added `import androidx.compose.runtime.LaunchedEffect` at line 40, between `DisposableEffect` and `getValue`. The `Launc

- **[8/10] [android_consumer] Order-tracking notifications collapse onto a single PendingIntent** — FIXED
  `KosherEatsMessagingService.showNotification` (lines 73–76) builds `PendingIntent.getActivity(context, 0, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
  > Each order now gets its own PendingIntent slot via a unique request code (`orderId.hashCode()`) and a distinct intent da

- **[7/10] [android_consumer] AuthViewModel marks user logged-in with null profile on transient errors** — FIXED
  `checkAuthStatus()` runs on cold start whenever a stored token is present. On 401 it correctly calls `clearAuth()`, but on any other non-success respo
  > Both changes look correct. The `else` branch (full profile rendering) is now only reachable when `state.user != null`, w

- **[7/10] [android_seller] loadMenuItem returns stale cached item after modifier-group changes** — FIXED
  MenuViewModel.loadMenuItem (MenuViewModel.kt:83-114) returns the locally cached item from _state.value.items whenever it's found, without ever re-fetc
  > `loadMenuItem` now always calls `getSellerMenu()` and updates both `items` and `selectedItem` from the fresh response. T

- **[7/10] [android_seller] Retrofit R8 full-mode rules incomplete (no EnclosingMethod, no -if interface rule for ApiService generics)** — FIXED
  proguard-rules.pro:18-35 has the three rules added on 2026-05-14 for Continuation/Call/Response, but is missing (a) -keepattributes EnclosingMethod, w
  > Two changes made to `android/seller/app/proguard-rules.pro`:

1. **Line 20** — `EnclosingMethod` appended to the existin

- **[7/10] [android_seller] Seller cannot cancel an order after accepting it** — FIXED
  OrdersViewModel.allowedTransitions (OrdersViewModel.kt:148-154) only allows PENDING → CANCELLED. ACCEPTED and PREPARING have no transition to CANCELLE
  > Done. Here's a summary of the two changes:

**`OrdersViewModel.kt` (line 150–151)** — `allowedTransitions` now permits `

- **[6/10] [android_consumer] HomeViewModel.loadMore advances currentPage before the network call, skipping pages on failure** — FIXED
  `loadMore()` (HomeViewModel.kt:117–123) does `_uiState.update { it.copy(isLoading = true, currentPage = nextPage) }` *before* invoking `loadRestaurant
  > The fix is in place. With `currentPage = nextPage` removed from `loadMore()`, the page counter now advances **only** ins

- **[6/10] [android_consumer] OrderStatus.stepIndex makes the tracking progress bar plateau for half the order lifecycle** — FIXED
  In `Models.kt:35–43`, READY, PICKED_UP, and DELIVERED all return `stepIndex = 4`; combined with the renderer in `OrderTrackingScreen.kt:248–262` (`rep
  > Two-line fix applied:

**Models.kt** — each status now maps to a unique `stepIndex`:
| Status | Old | New |
|---|---|---

- **[6/10] [android_seller] createMenuItem silently creates duplicate categories on network failure** — FIXED
  MenuViewModel.createMenuItem (MenuViewModel.kt:137-146) calls apiService.getSellerMenu() to find an existing category by display name, but never check
  > Fixed. Added a `!menuResponse.isSuccessful` check immediately after the `getSellerMenu()` call (line 127). If the GET re

- **[6/10] [android_seller] OrdersViewModel polls selected order detail forever, and is recreated per NavBackStackEntry** — FIXED
  Two related issues. (1) OrdersViewModel.pollSilently (OrdersViewModel.kt:76-83) refetches getOrderDetail(selectedOrder.id) on every poll tick if selec
  > All changes look correct. Here's a summary of what was done and why each part matters:

---

**3 files changed:**

**`Or

- **[5/10] [android_consumer] Deprecated Icons.Filled.ArrowBack + locale-less String.format across screens** — FIXED
  Compose Material 1.6+ deprecated `Icons.Filled.ArrowBack` (and several other LTR-only glyphs) in favor of `Icons.AutoMirrored.Filled.ArrowBack`, which
  > All 10 changes applied across 6 files:

**`Icons.Filled.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack`** (5 files):


- **[5/10] [android_seller] Settings shows hardcoded "v1.0.0" while app ships at 1.0.7** — FIXED
  RestaurantSettingsScreen.kt:475 hardcodes "KosherEats Seller v1.0.0". The actual versionName in build.gradle.kts is 1.0.7 / versionCode 11. Sellers se
  > Done. Added `import com.koshereats.seller.BuildConfig` at line 4 and replaced the hardcoded string at line 476 with `"Ko
