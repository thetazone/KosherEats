# KosherEats Polish — Round 18
**Max severity found:** 7
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[7/10] [android_consumer] Cart restore race drops new items** — FIXED
  CartViewModel.init launches a coroutine that reads the persisted snapshot from DataStore and then runs `_uiState.update { it.copy(carts = s.carts, act
  > The fix replaces the wholesale `carts = s.carts` overwrite with `carts = s.carts + current.carts`. In Kotlin, `map1 + ma

- **[7/10] [android_consumer] Keystore prefs init on main thread** — FIXED
  NetworkModule.provideTokenPrefs creates EncryptedSharedPreferences synchronously, and TokenProvider.init then calls prefs.getString(...) twice — both 
  > Two changes made to `RetrofitClient.kt`:

1. **`provideTokenPrefs` (line ~106)**: Wrapped the entire `MasterKey.Builder.

- **[7/10] [android_seller] Unfiltered orders list grows unbounded across polls** — FIXED
  In `OrdersViewModel.pollSilently()` (lines 184–207) and `refresh()` (lines 488–505), the merge keeps any existing order that is not in the new poll re
  > Both occurrences are fixed. The change in both `pollSilently()` and `refresh()` is identical: drop `?: if (filterAtStart

- **[7/10] [android_seller] Dashboard shows previous restaurant's stats after picker switch and on failed refresh** — FIXED
  `DashboardViewModel.loadDashboard()` (line 137) uses `isLoading = it.stats.todayOrders == 0 && it.activeOrders.isEmpty()` and doesn't reset `stats`/`a
  > The change unconditionally clears `stats`/`activeOrders` and sets `isLoading = true` at the top of `loadDashboard()`. No

- **[7/10] [android_seller] TokenAuthenticator can re-persist tokens AFTER logout clears DataStore (ghost re-login)** — FIXED
  `TokenAuthenticator.authenticate()` (RetrofitClient.kt lines 203–208) persists the refreshed token via fire-and-forget `appScope.launch { context.data
  > The fix is in place. Here's what changed and why it works:

**Root cause:** `authenticate()` sets `NetworkModule.cachedT

- **[6/10] [android_consumer] Logout wipes start destination** — FIXED
  In NavGraph.kt the logout-event collector navigates to Login with `popUpTo(0) { inclusive = true }`, which pops every entry including the start destin
  > Done. The change is on lines 131–139. `popUpTo(0) { inclusive = true }` was wiping the entire back stack including the s

- **[6/10] [android_consumer] missing-ime-actions-textfields** — FIXED
  No TextField in the entire app sets `imeAction` / KeyboardActions. All multi-field forms — Login phone, Email Login (email→password), Register (5 fiel
  > All four files are correct. Here's a summary of what was changed:

**EmailLoginScreen.kt** — 2-field chain:
- Email → `I

- **[6/10] [android_consumer] i18n-strings-hardcoded-everywhere** — FIXED
  values/strings.xml defines 32 keys with Hebrew (values-iw) and Yiddish (values-yi) translations but only 9 stringResource() call sites exist in the co
  > No hardcoded UI strings remain. All changes are clean. Here's a summary of what was done:

**strings.xml (base)** — 22 n

- **[6/10] [android_consumer] light-theme-broken-background** — FIXED
  Color.kt lines 56-66 defines LightKosherEatsColors with `backgroundBlack = white` etc., but the entire UI bakes in dark-mode contrast assumptions: har
  > All three changes applied. Here's a summary of what was fixed and why each change is correct:

---

**`RegisterScreen.kt

- **[6/10] [android_consumer] fcm-token-no-onnewtoken-backend-bridge** — FIXED
  KosherEatsMessagingService.onNewToken (lines 38-49) only registers if `tokenProvider.awaitToken() != null`. When the user is signed-out and FCM rotate
  > Both files look correct. Here's a summary of what changed and why:

**`KosherEatsMessagingService.kt`** — `onNewToken` n

- **[6/10] [android_consumer] sse-stream-no-auth-header** — FIXED
  OrderTrackingViewModel.kt lines 121-172 builds the SSE Request directly with `Request.Builder().url(url).header("Accept", "text/event-stream")` — no A
  > The fix looks correct. Here's a summary of what was changed and why:

**Changes** (`OrderTrackingViewModel.kt`):
1. Adde

- **[6/10] [android_seller] updateMenuItem cannot clear image_url or other nullable fields** — FIXED
  UpdateMenuItemRequest uses `null` defaults to mean 'leave unchanged', but the form in MenuItemFormScreen.kt:498 sends `imageUrl = imageUrl.trim().ifBl
  > That's the complete fix. Sending `""` (empty string) instead of `null` when the image URL field is blank means the PUT r

- **[6/10] [android_seller] Polling can overwrite optimistic status update with stale server data** — FIXED
  updateOrderStatus does an optimistic status mutation then awaits the PATCH. A pollSilently that started before the PATCH but lands after it merges the
  > Both merge sites now guard against the race. In `pollSilently()` (line 192) and `refresh()` (line 493), any order whose 

- **[5/10] [android_seller] Menu item form never lets the seller set prep time, calories, or allergens — every item defaults to 15 min prep** — FIXED
  `MenuItemFormScreen.kt` builds the `UpdateMenuItemRequest` at lines 501–512 with `calories = null` hardcoded and omits `preparationTime` and `allergen
  > All changes look correct. The fix is complete:

- **State variables** (`prepTime`, `caloriesInput`, `allergensStr`) adde

- **[5/10] [android_seller] SCHEDULED orders never appear in dashboard active list** — FIXED
  DashboardViewModel filters with `it.status.isActive`, and OrderStatus.isActive (Models.kt:27-32) returns false for SCHEDULED. ActiveOrderCard explicit
  > Done. Removed `SCHEDULED` from the `false` branch in `OrderStatus.isActive` (Models.kt:29). It now falls into `else -> t

- **[5/10] [android_seller] Orders nested graph state preservation lands user on OrderDetail** — FIXED
  NavGraph.kt:144-150 uses `popUpTo(findStartDestination) { saveState = true } ... restoreState = true` for bottom nav clicks. The orders_graph nested N
  > The fix is a single-expression change at line 149. `restoreState` is now `false` only when the tapped tab is Orders — al
