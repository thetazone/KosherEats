# KosherEats Polish — Round 8
**Max severity found:** 7
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 8

## Issues & Fixes
- **[7/10] [android_consumer] HomeViewModel.loadSuggested runs once at init and never refreshes when filters change** — FIXED
  HomeViewModel.kt:51-73: loadSuggested() is called from init { } only. selectCuisine, applyKosherFilters, toggleGlattFilter all call loadRestaurants(pa
  > Added loadSuggested() calls to selectCuisine, toggleGlattFilter, toggleCholovYisroelFilter, and applyKosherFilters so th

- **[7/10] [android_seller] Dashboard 'Active Orders' tile ignores server stat, shows client-filtered paginated count** — FAILED
  DashboardScreen.kt:230 renders `${state.activeOrders.size}` instead of `state.stats.activeOrders`. The `activeOrders` list is built in DashboardViewMo
  > not reported by batch agent

- **[6/10] [android_consumer] ChatViewModel.fetch cancels polling on 401/403/404 but resume() restarts it against the dead endpoint** — FIXED
  ChatViewModel.kt:157-159: on 401/403/404 we `pollJob?.cancel()` but leave `_state.error` as 'Couldn't refresh chat' and no permanent state flag. ChatS
  > Added terminalError: Boolean to ChatUiState, set it on 401/403/404 in fetch(), and added an early return in resume() whe

- **[6/10] [android_consumer] ChatViewModel.retrySend reorders the conversation — failed message jumps to the end on retry** — FIXED
  ChatViewModel.kt:125-136: retrySend removes the failed message from `messages`, then send() (line 68-123) creates a new optimistic message with a fres
  > Rewrote retrySend to keep the same clientId and update the message in-place instead of removing it and calling send(), p

- **[6/10] [android_consumer] OrdersViewModel.loadOrders never cancels prior coroutines — refresh + auto-pagination race** — FIXED
  OrdersViewModel.kt:38-63: every loadOrders call launches a fresh viewModelScope.launch with no `loadJob?.cancel()`. If the user pulls-to-refresh (refr
  > Added private var loadJob: Job? and loadJob?.cancel() at the start of loadOrders() to cancel any in-flight request befor

- **[6/10] [android_consumer] extractIntentId silently falls back to the full client secret if the `_secret_` delimiter is missing** — FIXED
  CheckoutViewModel.kt:427-428: `clientSecret.substringBefore("_secret_", clientSecret)` returns the entire client secret when the delimiter isn't prese
  > Changed extractIntentId to return String? (null when delimiter absent) and added a null-check in onPaymentResult that su

- **[6/10] [android_consumer] OrderTrackingViewModel SSE: a single 401 in the stream forces global logout — no token refresh attempt** — FIXED
  OrderTrackingViewModel.kt:126-131: on `response.code == 401` from the SSE stream we immediately `sessionManager.signalLogout()`. The SSE call uses `ss
  > Added a consecutiveSseUnauthorized counter that only signals logout after two consecutive SSE 401s, treating the first a

- **[6/10] [android_consumer] OrdersViewModel never resets `hasMore` on error — load-more breaks silently after a transient 5xx** — FIXED
  OrdersViewModel.kt:57-59: Resource.Error sets isLoading/isRefreshing=false but leaves currentPage and hasMore at their previous values. After a succes
  > Added hasMore = false to the Resource.Error branch in loadOrders() so the auto-scroll trigger stops firing after a faile

- **[6/10] [android_seller] pollSilently/refresh races loadMoreOrders and silently drops loaded pages** — FAILED
  OrdersViewModel.kt:152-178 (pollSilently) and OrdersViewModel.kt:432-460 (refresh) capture `currentPage` at call start, compute `limit = PAGE_SIZE * c
  > not reported by batch agent

- **[6/10] [android_seller] Network callback race orphans polling job past stopPolling()** — FAILED
  DashboardViewModel.kt:79-92 and OrdersViewModel.kt:90-107 register a ConnectivityManager callback whose `onAvailable` calls `pollingJob?.cancel()` the
  > not reported by batch agent

- **[5/10] [android_consumer] ProfileScreen briefly shows the guest 'Unlock your full experience' CTA for returning authenticated users on cold start** — FIXED
  ProfileScreen.kt:102: `if (!state.isLoggedIn || state.isGuest)`. AuthUiState defaults to sessionState=Unknown → isLoggedIn=false. AuthViewModel.checkA
  > Defaulted isRehydrating = true in AuthUiState and moved the explicit update before awaitToken() in checkAuthStatus(); ad

- **[4/10] [android_seller] deleteMenuItem uses screen-wide isLoading, blanking entire menu during a single-item delete** — FAILED
  MenuViewModel.kt:245-269 sets `isLoading = true` when deleting a single item, which causes MenuManagementScreen.kt:160 to swap the entire item list fo
  > not reported by batch agent

- **[4/10] [android_seller] Both Dashboard and Orders polling fire an immediate request on top of the init load → 2x bandwidth on every screen entry** — FAILED
  DashboardViewModel.init runs `loadDashboard()` (line 43) and then DashboardScreen.kt:73 calls `startPolling()` whose first iteration is an uncondition
  > not reported by batch agent

- **[4/10] [android_seller] OrdersViewModel.refresh skips the filter consistency rollback / orders not re-checked** — FAILED
  OrdersViewModel.refresh (line 346-372) takes a snapshot of `selectedFilter` but if the filter changes between the snapshot and response, only `orders`
  > not reported by batch agent

- **[4/10] [android_seller] MenuViewModel.deleteMenuItem toggles the full-screen `isLoading` spinner** — FAILED
  MenuViewModel.kt:245-270 sets `isLoading = true` for a single-item delete, which in MenuManagementScreen (line 160) replaces the entire list with a ce
  > not reported by batch agent

- **[4/10] [android_seller] Pareve field name diverges between create body and read model: is_pareve vs is_kosher_pareve** — FAILED
  CreateMenuItemBody (Models.kt:294-308) sends `is_pareve` on create, but MenuItem.isKosherPareve (Models.kt:86) reads `is_kosher_pareve`. UpdateMenuIte
  > not reported by batch agent
