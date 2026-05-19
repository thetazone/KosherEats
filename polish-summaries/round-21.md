# KosherEats Polish — Round 21
**Max severity found:** 8
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[8/10] [android_consumer] FCM showNotification() skips POST_NOTIFICATIONS check on Android 13+** — FIXED
  KosherEatsMessagingService.showNotification() calls NotificationManagerCompat.notify() unconditionally. On API 33+ this can throw SecurityException (o
  > Done. Added three things to `KosherEatsMessagingService.kt`:

1. `import android.Manifest` and `import android.content.p

- **[7/10] [android_consumer] multiDexEnabled not declared in defaultConfig** — FIXED
  App has Firebase + Stripe + Compose + Maps + Retrofit and minSdk 26, so dex method count likely exceeds 65k. multiDexEnabled is not explicitly set, so
  > Added `multiDexEnabled = true` to `defaultConfig` in `android/consumer/app/build.gradle.kts` (line 60). No `androidx.mul

- **[7/10] [android_consumer] AuthViewModel.verifyPhoneCode() replaces state with new AuthUiState()** — FIXED
  On successful phone verification (~line 396-401 of AuthViewModel.kt), the success branch assigns _uiState.value = AuthUiState(...) instead of _uiState
  > Fixed. Changed `AuthUiState(...)` to `it.copy(...)` so `pendingGuestReturn` and other transient fields are preserved acr

- **[7/10] [android_seller] FCM-driven refreshSingleOrder can overwrite optimistic updates** — FIXED
  OrdersViewModel.refreshSingleOrder (OrdersViewModel.kt:153-170) does not consult pendingOrderIds. When the seller taps Accept/Mark Ready/etc., updateO
  > Added `if (orderId in _state.value.pendingOrderIds) return` at line 154, before the mutex lock. FCM-triggered `refreshSi

- **[7/10] [android_seller] Order detail polling can't keep selectedOrder fresh when it falls outside the recent window** — FIXED
  pollSilently fetches the seller's order list with `limit = PAGE_SIZE * currentPage` and merges by id (OrdersViewModel.kt:182-209). When the seller ope
  > The structure is correct now. The orphan refresh (lines 215–231) is inside `if (response.isSuccessful)` (186–232), after

- **[7/10] [android_seller] DataStore runBlocking in provideOkHttpClient blocks the injection thread on cold start** — FIXED
  NetworkModule.provideOkHttpClient (RetrofitClient.kt:73-78) calls `runBlocking { context.dataStore.data.first() }` to prime cachedToken/refreshToken/r
  > The fix is clean. Here's what changed:

**`RetrofitClient.kt`**
- Removed `import kotlinx.coroutines.runBlocking` (line 

- **[7/10] [android_seller] MenuItemFormScreen form populates with stale prior item when editing a new one** — FIXED
  MenuItemFormScreen.kt:158-194 initializes form fields from state.selectedItem and uses `rememberSaveable { mutableStateOf(false) }` for formInitialize
  > Two changes made:

- **`MenuViewModel.kt:91`** — `loadMenuItem` now clears `selectedItem = null` alongside `isLoading = 

- **[6/10] [android_consumer] Notification tap intent missing FLAG_ACTIVITY_NEW_TASK** — FIXED
  KosherEatsMessagingService builds the content intent with FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP only. When the app is fully backgrounded/
  > Added `Intent.FLAG_ACTIVITY_NEW_TASK` to the intent flags in `KosherEatsMessagingService.kt:91`. The intent now uses all

- **[6/10] [android_consumer] runBlocking inside OkHttp auth interceptor blocks dispatcher thread** — FIXED
  RetrofitClient.kt (~line 148) uses runBlocking to fetch the token from EncryptedSharedPreferences inside the interceptor chain. On cold start the Encr
  > Done. Here's a summary of the changes:

**`RetrofitClient.kt`**
- Removed `import kotlinx.coroutines.runBlocking` (no lo

- **[6/10] [android_consumer] HomeViewModel filter toggles fire concurrent loadRestaurants() calls** — FIXED
  toggleGlattFilter() and the other filter mutators in HomeViewModel update state then call loadRestaurants(page = 1). Rapidly toggling chips spawns ove
  > The fix replaces the manual `currentJob?.cancel()` + `launch` pattern with `flatMapLatest` on a `MutableStateFlow<LoadTr

- **[6/10] [android_consumer] HomeScreen search field is not wrapped with imePadding()** — FIXED
  HomeScreen.kt's search TextField (~line 212) lacks Modifier.imePadding() on its scaffold/column. When the soft keyboard opens, it overlaps the search 
  > Added `import androidx.compose.foundation.layout.imePadding` and `.imePadding()` to the `LazyColumn` modifier at line 12

- **[6/10] [android_consumer] Unknown enum values for CuisineType / KosherCertification silently deserialize to null** — FIXED
  Models.kt declares cuisineTypes: List<CuisineType?> and kosherCertification: KosherCertification?. Gson maps unknown enum strings to null with no logg
  > Everything looks correct. Here's a summary of all changes made:

**Models.kt**
- `KosherCertification`: added `UNKNOWN("

- **[6/10] [android_seller] Image upload state stuck on "Uploading…" after a configuration change** — FIXED
  MenuItemFormScreen.kt:131 declares `isUploadingImage by rememberSaveable { mutableStateOf(false) }` and the upload coroutine is launched on `rememberC
  > One-line fix at `MenuItemFormScreen.kt:131`: changed `rememberSaveable` → `remember` for `isUploadingImage`.

After a co

- **[6/10] [android_seller] OrderEventBus replay=1 fires stale events at every re-subscribe and on cold start** — FIXED
  OrderEventBus.kt:13-19 uses `MutableSharedFlow(replay = 1, …)`. Every new subscriber receives the most recent emitted event — so each time SellerOrder
  > That's the complete fix. `OrderEventBus.kt:15` now reads `replay = 0`, so:

- New subscribers (`collect` calls in `start

- **[5/10] [android_seller] Filtered list keeps orders that no longer match after FCM-driven status change** — FIXED
  When the seller has the chips filtered to e.g. "Pending" and FCM emits order_status_changed for an order, OrdersViewModel.refreshSingleOrder (OrdersVi
  > Two minimal changes applied to `OrdersViewModel.kt`:

- **`refreshSingleOrder` (line ~161)**: After `map`-updating the o

- **[5/10] [android_seller] loadDashboard discards partial success when either stats or orders fails** — FIXED
  DashboardViewModel.loadDashboard (DashboardViewModel.kt:148-168) requires BOTH statsResponse and ordersResponse to succeed; otherwise it surfaces "Fai
  > All three fixes applied. Here's a summary of what changed in each function:

**`loadDashboard`** — replaced the all-or-n
