# KosherEats Polish — Round 22
**Max severity found:** 8
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[8/10] [android_seller] Restaurant switch does not refresh OrdersViewModel; Dashboard flashes prior restaurant's data** — FIXED
  RestaurantPickerSheet.onChange (DashboardScreen.kt:86-89) only triggers AuthViewModel.refreshRestaurants() and DashboardViewModel.loadDashboard(). Ord
  > All changes are in place. Here's a summary of what was changed and why:

**`RetrofitClient.kt`** — Added `restaurantChan

- **[7/10] [android_seller] pollSilently leaves orders stuck after they transition out of the active filter** — FIXED
  OrdersViewModel.kt:178-244. With a status filter active (e.g. PENDING), the server omits transitioned orders. The merge uses `serverMightBeCapped = ne
  > The fix is a single-line change to `OrdersViewModel.kt:208`. The condition `serverMightBeCapped = newOrders.size >= limi

- **[6/10] [android_consumer] POST_NOTIFICATIONS permission requested on cold-start, before any user value is established** — FIXED
  MainActivity.kt:50 mounts `RequestNotificationPermissionIfNeeded()` inside `setContent`, and the `LaunchedEffect(Unit)` at MainActivity.kt:125-135 fir
  > Everything looks correct. Here's a summary of what changed and why:

---

**`CheckoutScreen.kt`** — The notification per

- **[6/10] [android_consumer] Duplicate `Content-Type: application/json` header on every POST/PUT due to `addHeader` instead of `header`** — FIXED
  RetrofitClient.kt:162-166 (`provideAuthInterceptor`) uses `addHeader("Content-Type", "application/json")` and `addHeader("Accept", "application/json")
  > Two changes made:
- Removed the `Content-Type: application/json` line entirely — Retrofit's `GsonConverterFactory` alrea

- **[6/10] [android_consumer] Two concurrent collectors on `sessionManager.logoutEvent` race with the pending-order-id LaunchedEffect** — FIXED
  `AuthViewModel.init` (AuthViewModel.kt:82-87) launches a `viewModelScope.launch { sessionManager.logoutEvent.collect { ... _uiState.update { LoggedOut
  > Good — `authViewModel.logoutEvent` is no longer referenced anywhere. The other VMs (`AddressViewModel`, `ChatViewModel`,

- **[6/10] [android_seller] pollSilently fetches PAGE_SIZE * currentPage rows every tick** — FIXED
  OrdersViewModel.kt:185-187. `val limit = PAGE_SIZE * pageAtStart.coerceAtLeast(1)` — every 30s poll (and every FCM event, every reconnect kick) re-dow
  > The fix is correct. Here's a summary of what changed and why:

**3 lines changed in `pollSilently()` (~:194-213):**

1. 

- **[5/10] [android_consumer] `window.statusBarColor` / `navigationBarColor` setters are no-ops on Android 15 (targetSdk=35) — use `enableEdgeToEdge(SystemBarStyle, SystemBarStyle)` instead** — FIXED
  build.gradle.kts:55 sets `targetSdk = 35`. Theme.kt:90-95 calls `window.statusBarColor = barColor` and `window.navigationBarColor = barColor`. Both se
  > Both files look correct. Here's a summary of what changed and why:

**Theme.kt**
- Removed `import androidx.compose.ui.g

- **[5/10] [android_consumer] OrderTracking SSE 401 sets errorMessage after signalLogout** — FIXED
  OrderTrackingViewModel (~line 140-150) calls SessionManager.signalLogout() and then updates _uiState with errorMessage = 'Session expired'. The logout
  > Done. The `_uiState.update` now runs before `sessionManager.signalLogout()` at line 146-147 of `OrderTrackingViewModel.k

- **[5/10] [android_consumer] PushBootstrap can register the same FCM token multiple times** — FIXED
  PushBootstrap.registerCurrentToken() (~line 61-77) reads pending_fcm_token from SharedPreferences but does not de-duplicate against the last-successfu
  > Two changes made to `PushBootstrap.kt`:

1. **`registerCurrentToken()`** — reads `last_registered_fcm_token` from Shared

- **[5/10] [android_consumer] CartViewModel.cart silently returns empty Cart() when activeRestaurantId is missing from map** — FIXED
  The cart getter (~line 54 in CartViewModel.kt) does carts[activeRestaurantId] ?: Cart(). If activeRestaurantId is set but the map entry was evicted/cl
  > Two minimal changes, both in `CartViewModel.kt`:

- **`removeItem`** (line ~208): after filtering out empty carts, nulls

- **[5/10] [android_consumer] Token refresh swallows JSONException, returning null without logging** — FIXED
  RetrofitClient.tryRefresh() (~line 282-286) parses the refresh response with JSONObject.getString() inside a broad catch (e: Exception) that returns n
  > The fix is complete. Here's what changed:

**`RetrofitClient.kt`** — three targeted changes:

1. **Added `import org.jso

- **[5/10] [android_seller] Menu mutations update state.items but not state.categories, briefly reverting on category switch** — FIXED
  MenuViewModel.kt:198-264. deleteMenuItem, toggleAvailability, and create/updateMenuItem success paths mutate state.items but leave state.categories un
  > The changes look correct. All five mutation sites now keep `state.categories` in sync:

1. **`deleteMenuItem` success** 

- **[4/10] [android_seller] Dashboard refresh failures are silent — state.error is never surfaced** — FIXED
  DashboardViewModel writes `error = "Failed to refresh dashboard …"` on refresh failure (DashboardViewModel.kt:222-225, 239-242) but DashboardScreen ne
  > Done. The fix adds three things to `DashboardScreen.kt`:

1. `import android.widget.Toast` and `import androidx.compose.

- **[4/10] [android_seller] DealsScreen.isExpired silently returns false on parse failure** — FIXED
  DealsScreen.kt:165-169 wraps `ZonedDateTime.parse(deal.expiresAt)` in `runCatching` with a default of `false`. If the backend ever returns expiresAt i
  > The fix looks correct. Here's a summary of the changes made to `DealsScreen.kt:165-173`:

**Before:** Two separate `try/

- **[4/10] [android_seller] CreateDealScreen defaults to today at 11:30 PM with no past-time validation** — FIXED
  CreateDealScreen.kt:121-128 defaults expiresAtMillis to today (UTC midnight) and selectedHalfHour to slot 47 (11:30 PM). On a phone in PST after 8 AM,
  > All changes look correct. Here's a summary of what was fixed:

**Changes made to `CreateDealScreen.kt`:**

1. **Default 

- **[4/10] [android_seller] Modifier-group save reloads the entire item with a full-screen loading flash** — FIXED
  After createModifierGroup / updateModifierGroup / deleteModifierGroup succeeds, MenuViewModel calls loadMenuItem(itemId) (MenuViewModel.kt:282, 300, 3
  > Both changes look correct. The fix is complete.

**What changed:**

**`MenuViewModel.kt`:**
- Added `modifierGroupsLoadi
