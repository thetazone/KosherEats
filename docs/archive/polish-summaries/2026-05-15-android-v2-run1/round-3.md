# KosherEats Polish — Round 3
**Max severity found:** 7
**Issues found:** 12
**Fixes attempted:** 11
**Fixes succeeded:** 11

## Issues & Fixes
- **[7/10] [android_seller] MenuItemFormScreen pops back AND wipes form data on every modifier-group operation** — FIXED
  The form has two `LaunchedEffect`s that both fire when a modifier-group op succeeds. (1) `LaunchedEffect(state.saveSuccess) { ... onSaved() }` at Menu
  > Added itemSaveSuccess flag to MenuState (set only by createMenuItem/updateMenuItem), gated LaunchedEffect(state.selected

- **[6/10] [android_consumer] Money.formatPriceWhole truncates via integer division** — FIXED
  Money.kt:11 calls `nf.format(this / 100)` — `Int / Int` integer-divides. 1099¢ ($10.99) becomes 10 and renders as "$10" instead of "$11". `nf.maximumF
  > Changed `this / 100` to `this / 100.0` in Money.kt:11 so NumberFormat receives a Double and rounds correctly instead of 

- **[6/10] [android_consumer] isLoggedIn=true for guests is a footgun** — FIXED
  AuthViewModel.continueAsGuest() (line 495) sets `isLoggedIn = true, isGuest = true`. NavGraph remembers to combine `authState.isLoggedIn && !authState
  > Added sealed class SessionState { Authenticated, Guest, LoggedOut }, replaced the two booleans in AuthUiState with a sin

- **[5/10] [android_consumer] HomeViewModel pagination breaks when filterCertifications is set** — FIXED
  HomeViewModel.kt:88-107 applies `filterCertifications` *client-side* on each page (line 90-94) but computes `hasMore = result.data.size >= 20` from th
  > Removed client-side certification filtering in loadRestaurants(); added a private toApiString() extension to map each Ko

- **[5/10] [android_consumer] Login error always reads "Login failed" regardless of cause** — FIXED
  AuthViewModel.kt:147-153 sets a single hard-coded error string for every non-2xx response. 401 (bad credentials), 403 (account locked), 422 (validatio
  > Replaced the hard-coded 'Login failed' string in the login() error branch with a when switch on response.code() mapping 

- **[5/10] [android_consumer] FCM notification IDs collide via orderId.hashCode()** — FIXED
  KosherEatsMessagingService.kt:93 uses `orderId.hashCode()` as both the PendingIntent requestCode and the NotificationManager id. Java `String.hashCode
  > Replaced nm.notify(orderId.hashCode(), notification) with nm.notify(orderId, 0, notification) (tag-based) so same-order 

- **[5/10] [android_seller] Editing any modifier group resets its sort_order to 0 (and its description to empty)** — FIXED
  `ModifierGroupDialog` builds a `CreateModifierGroupRequest` (MenuItemFormScreen.kt:776-799) but never passes `sortOrder` or `description`. `CreateModi
  > Added groupDescription state to ModifierGroupDialog with a UI text field, and passed description=groupDescription.trim()

- **[5/10] [android_seller] AuthViewModel is instantiated per-NavBackStackEntry, so restaurant state forks across screens** — FIXED
  `hiltViewModel()` inside a `composable {}` block scopes the ViewModel to the current NavBackStackEntry. NavGraph.kt:55 grabs an AuthViewModel at the r
  > Passed authViewModel = authViewModel explicitly to DashboardScreen and RestaurantSettingsScreen in NavGraph.kt so both s

- **[5/10] [android_seller] Order detail screen early-return hides the reject confirmation dialog** — FIXED
  SellerOrderDetailScreen.kt line 129-137 does `if (state.isLoading || order == null) { CircularProgressIndicator … return }` — the function-level `retu
  > Moved the AlertDialog block above the early-return Column so polling can't dismiss it mid-confirmation, and added viewMo

- **[5/10] [android_seller] OrdersViewModel.pollSilently can race with filter changes** — FIXED
  OrdersViewModel.pollSilently reads `_state.value.selectedFilter` once, fires apiService.getOrders, then writes `state.update { it.copy(orders = respon
  > Captured selectedFilter in a local val before the API call and wrapped the state update in a conditional that discards t

- **[5/10] [android_seller] ActiveOrderCard hides delivery-vs-pickup, customer name, and elapsed time** — FIXED
  ActiveOrderCard.kt shows only `#xxxxxxxx` + status badge + items summary + total. A seller triaging the dashboard can't tell pickup vs delivery (Order
  > Added a PICKUP/DELIVERY chip, customer name text, and a minutesAgo elapsed-time stamp row between the header and items s

- **[4/10] [android_consumer] DealsViewModel mutates StateFlow non-atomically** — skipped
  DealsViewModel.kt:34-53 uses `_uiState.value = _uiState.value.copy(...)` rather than `_uiState.update { it.copy(...) }`. The `refresh()` method (line 
