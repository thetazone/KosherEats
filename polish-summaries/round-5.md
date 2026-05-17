# KosherEats Polish — Round 5
**Max severity found:** 10
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 8

## Issues & Fixes
- **[10/10] [android_consumer] OrdersScreen imports snapshotFlow from kotlinx.coroutines (doesn't exist) — build break** — FAILED
  OrdersScreen.kt:48 has `import kotlinx.coroutines.flow.snapshotFlow`, but `snapshotFlow` does not exist in `kotlinx.coroutines.flow` — the symbol live
  > not reported by batch agent

- **[6/10] [android_consumer] Notification deep-link replays on rotation/process recreation, hijacking the user back to tracking** — FAILED
  MainActivity.kt:30 reads `intent.getStringExtra("order_id")` in onCreate and sets `DeepLinkState.pendingOrderId.value`. The deep-link LaunchedEffect i
  > not reported by batch agent

- **[6/10] [android_consumer] FCM onNewToken silently drops rotated tokens due to TokenProvider race when service runs headlessly** — FAILED
  KosherEatsMessagingService.onNewToken (line 38-49) bails with `if (tokenProvider.token == null) return` before launching. But TokenProvider (RetrofitC
  > not reported by batch agent

- **[6/10] [android_seller] Frozen "Xm ago" elapsed-time display** — FIXED
  ActiveOrderCard.kt:93-97 and SellerOrderDetailScreen.kt:192-196 compute minutes-since-placed inside remember(order.createdAt). createdAt never changes
  > Replaced `remember(order.createdAt)` with `produceState<Long?>` + 60s `delay` loop in both ActiveOrderCard.kt and Seller

- **[6/10] [android_seller] Failed availability toggle wipes the menu to a spinner** — FIXED
  MenuViewModel.kt:296-309 reacts to a failed toggleMenuItemAvailability by calling loadMenuItems(...), which sets isLoading=true. MenuManagementScreen.
  > Replaced both `loadMenuItems(...)` calls in the failure/catch branches of `toggleAvailability` with a direct in-place ro

- **[5/10] [android_seller] Active Orders stat doesn't match the list below it** — FIXED
  DashboardScreen.kt:230 renders state.stats.activeOrders (server count from DashboardStats) but the list at line 283 uses state.activeOrders, derived f
  > Changed DashboardScreen.kt line 230 from `state.stats.activeOrders` (server count) to `state.activeOrders.size` (locally

- **[5/10] [android_seller] PushBootstrap.deleteToken not called on 401 session expiry** — FIXED
  AuthViewModel.clearAuth() (line 400) correctly calls PushBootstrap.deleteToken() on manual logout, but TokenAuthenticator.signalSessionExpired() (Retr
  > Fix already in place in the current codebase: the `sessionExpired` flow collector in `AuthViewModel.init()` calls `clear

- **[5/10] [android_seller] ProGuard: missing belt-and-suspenders ApiService keep rule** — FIXED
  proguard-rules.pro has the conditional Retrofit rule (line 35: -if interface * { @retrofit2.http.* <methods>; } -keep,allowobfuscation,allowshrinking 
  > Added `-keep,allowobfuscation interface com.koshereats.seller.data.api.ApiService` as an explicit safety-net rule, and c

- **[4/10] [android_consumer] Sign-out and delete-account double-navigate to Login due to overlap with logoutEvent collector** — FAILED
  NavGraph.kt has a global logout collector at lines 119-125 that navigates to Login on every `sessionManager.logoutEvent`. But the Profile handlers at 
  > not reported by batch agent

- **[4/10] [android_consumer] OrdersScreen pagination snapshotFlow churns on every scroll frame** — FAILED
  snapshotFlow { listState.layoutInfo } (OrdersScreen.kt lines 73–82) emits on every scroll position change because the entire LazyListLayoutInfo object
  > not reported by batch agent

- **[4/10] [android_consumer] addAddress swallows server error message** — FAILED
  CheckoutViewModel.addAddress() shows the generic 'Could not save address' string regardless of response code or body (line 246). Geocode failures, dup
  > not reported by batch agent

- **[4/10] [android_consumer] PhonePromptScreen hardcodes "+1" country code** — FAILED
  PhonePromptScreen.kt line 55 hardcodes val countryCode = "+1" and uses it both for display and submission, even though AuthViewModel exposes phoneCoun
  > not reported by batch agent

- **[4/10] [android_seller] MenuItemFormScreen loses all fields on rotation / process death** — FIXED
  MenuItemFormScreen.kt:111-123 uses remember { mutableStateOf(...) } for name, description, price, category, imageUrl, isPareve/isDairy/isMeat, spiceLe
  > Switched name, description, price, imageUrl, isPareve, isDairy, isMeat, spiceLevel, and formInitialized from `remember` 

- **[4/10] [android_seller] Dashboard restaurant switch wipes UI to full-screen spinner** — FIXED
  RestaurantPickerSheet's onChange callback (DashboardScreen.kt:84-87) calls viewModel.loadDashboard(), which sets isLoading = true (DashboardViewModel.
  > Changed `loadDashboard()` to only set `isLoading = true` on cold loads (`it.stats.todayOrders == 0 && it.activeOrders.is

- **[4/10] [android_seller] Modifier dialog can't toggle individual option availability** — FIXED
  ModifierGroupDialog (MenuItemFormScreen.kt:669-839) loads existing modifiers' isAvailable into OptionEntry.isAvailable (line 685) and writes it back a
  > Added a `Switch` per option row in `ModifierGroupDialog` (between the price-delta field and the delete button) that read

- **[3/10] [android_consumer] Pagination page size constants disagree across ViewModels** — FAILED
  HomeViewModel.loadRestaurants treats hasMore as result.data.size >= 20 while OrdersViewModel uses >= 50. These literals don't match ApiService default
  > not reported by batch agent
