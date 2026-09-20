# KosherEats Polish — Round 2
**Max severity found:** 8
**Issues found:** 10
**Fixes attempted:** 2
**Fixes succeeded:** 2

## Issues & Fixes
- **[8/10] [android_seller] Restaurant picker switch leaves AuthViewModel.restaurant stale — toggle shows wrong restaurant** — FIXED
  DashboardScreen.kt:78 passes `onChange = { viewModel.loadDashboard() }` to RestaurantPickerSheet. That refreshes DashboardViewModel's stats/orders, bu
  > Done. `DashboardScreen.kt:78` now calls `authViewModel.refreshRestaurants()` before `viewModel.loadDashboard()` in the `

- **[8/10] [android_seller] POST_NOTIFICATIONS never requested at runtime — push silently broken on Android 13+** — FIXED
  AndroidManifest.xml:8 declares `POST_NOTIFICATIONS`, but nothing in the app calls `ActivityResultContracts.RequestPermission`. minSdk=26, targetSdk=35
  > The fix adds:

1. **`registerForActivityResult`** with `ActivityResultContracts.RequestPermission()` — registered before

- **[7/10] [android_seller] runBlocking on the main thread during Hilt DI bootstrap** — skipped
  RetrofitClient.kt:68 does `runBlocking { context.dataStore.data.first() }[PrefsKeys.RESTAURANT_ID]` inside `provideOkHttpClient`. OkHttpClient is a Si

- **[6/10] [android_seller] createMenuItem silently drops spice_level, allergens, preparation_time, calories** — skipped
  MenuViewModel.kt:152-161 builds a `CreateMenuItemBody` that only has categoryId, name, description, price, imageUrl, is_meat/is_dairy/is_pareve, is_av

- **[6/10] [android_seller] Duplicate server categories created when last item in a category is deleted** — skipped
  MenuViewModel.kt:134-145: `existingCategory = menuResponse.body()?.firstOrNull { serverCat -> serverCat.items.any { it.category == targetCategory } }`

- **[5/10] [android_seller] Foreground push fires a heads-up notification while user is already in the app** — skipped
  KosherEatsMessagingService.onMessageReceived (line 45-54) unconditionally calls `showNotification(this, title, body)` and then `orderEventBus.notifyOr

- **[5/10] [android_seller] Price input breaks for users whose Decimal keyboard returns comma** — skipped
  MenuItemFormScreen.kt:316-319 filters the price input to `c.isDigit() || c == '.'`, and line 468 parses with `price.toDoubleOrNull()`. On many locales

- **[5/10] [android_seller] SellerOrderDetailScreen never shows updateSuccess feedback** — skipped
  SellerOrderDetailScreen.kt:81-86 sets up a `LaunchedEffect(state.error)` that toasts errors and calls `viewModel.clearMessages()` — but there is no an

- **[5/10] [android_seller] clearAuth doesn't synchronously null cachedRestaurantId — next request after logout uses stale id** — skipped
  AuthViewModel.kt:395-401's `clearAuth` sets cachedToken/cachedRefreshToken to null and clears DataStore, but never sets `NetworkModule.cachedRestauran

- **[5/10] [android_seller] Orders screen omits SCHEDULED from filter chips — scheduled orders only visible under 'All'** — skipped
  SellerOrdersScreen.kt:51-61's filter list is `[All, Pending, Accepted, Preparing, Ready, Picked Up, Delivered, Completed, Cancelled]` — no SCHEDULED c
