# KosherEats Polish — Round 3
**Max severity found:** 9
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 15

## Issues & Fixes
- **[9/10] [android_seller] Certificate upload leaks JWT to S3 and sets wrong Content-Type** — FIXED
  RestaurantSettingsScreen.uploadCertificateSettings (line 500) and CreateRestaurantScreen.uploadCertificate (line 611) PUT the kosher certificate to th
  > Replaced `viewModel.okHttpClient` with a bare `OkHttpClient()` in both `uploadCertificateSettings` and `uploadCertificat

- **[7/10] [android_consumer] AuthUiState.isLoggedIn treats Unknown as logged-in, bouncing users off LoginScreen** — FIXED
  AuthViewModel.kt:62 defines `val isLoggedIn: Boolean get() = sessionState != SessionState.LoggedOut`. The initial sessionState is `Unknown` (line 39) 
  > Changed isLoggedIn to require Authenticated||Guest (not !=LoggedOut), and updated LoginScreen's LaunchedEffect to key on

- **[7/10] [android_consumer] Dead placeOrder path in CartViewModel + persistSnapshot race** — FIXED
  CartViewModel.kt:283-341 defines `placeOrder(...)` plus `orderPlaced: Order?` (line 51) and `clearOrderPlaced()` (line 347). Grepping the codebase sho
  > Deleted placeOrder, clearOrderPlaced, and removed isPlacingOrder/orderPlaced from CartUiState; removed the dead isPlacin

- **[7/10] [android_seller] Seller can self-complete a PICKED_UP delivery order before the courier delivers** — FIXED
  OrdersViewModel.allowedTransitions (line 176) permits `PICKED_UP → COMPLETED` and SellerOrderDetailScreen (lines 607-624) renders a green "Mark as Com
  > Removed `OrderStatus.PICKED_UP to setOf(OrderStatus.COMPLETED)` from `allowedTransitions` in `OrdersViewModel`, updated 

- **[6/10] [android_consumer] KosherEatsMessagingService leaks CoroutineScope and calls authenticated endpoint anonymously** — FIXED
  KosherEatsMessagingService.kt:32 creates `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` and uses it in `onNewToken` (lines 34-
  > Injected TokenProvider, gated onNewToken on tokenProvider.token!=null, added onDestroy() to cancel the scope, and replac

- **[6/10] [android_seller] Dashboard active-orders count and active-orders list disagree** — FIXED
  DashboardViewModel.loadDashboard/pollSilently/refresh all call `getOrders(status = null, limit = 200)` and then locally filter with `it.status.isActiv
  > Changed the 'Active Orders' `StatCard` in `DashboardScreen` to display `state.activeOrders.size` instead of `state.stats

- **[5/10] [android_consumer] NavGraph forward/back transitions for non-bottom-nav routes are wrong** — FIXED
  NavGraph.kt:191-218: `enterTransition`/`exitTransition` look up route indices in `BottomNavItem.entries`. For pushes like Home→Restaurant→Cart→Checkou
  > Non-bottom-nav enter/exit transitions now use fadeIn+scaleIn / fadeOut+scaleOut instead of the same-direction slide; bot

- **[5/10] [android_consumer] OrdersScreen has no refresh and no pagination** — FIXED
  OrdersScreen.kt fetches only page 1 (OrdersViewModel default) and offers no way to refresh — no PullToRefreshBox, no FAB, not even a tap-to-retry on e
  > Added PullToRefreshBox with isRefreshing state, a loadMore() method in OrdersViewModel triggered by snapshotFlow when th

- **[5/10] [android_consumer] CartViewModel.placeOrder is dead code that diverges from real checkout** — FIXED
  CartViewModel.kt:233-286 contains a full createOrder flow (no Stripe, no server-cart sync, no fulfillmentType, no isPickup handling) but nothing in th
  > placeOrder was already removed under Issue 2; pendingDealItem/clearPendingDealItem are kept because RestaurantDetailScre

- **[5/10] [android_seller] Tapping a filter chip blanks the orders list with a full-screen spinner** — FIXED
  OrdersViewModel.loadOrders sets `isLoading = true` for every filter switch (line 112), and SellerOrdersScreen (line 154) reacts to `state.isLoading` b
  > In `loadOrders`, `isLoading` is now only set to `true` when the orders list is empty (initial load), not on filter switc

- **[5/10] [android_seller] OrderEventBus push-triggered re-poll re-fetches list+detail even when push payload contains the new state** — FIXED
  KosherEatsMessagingService.kt:52-55 emits an OrderEventBus event on new_order/courier_assigned/order_status_changed/order_cancelled/payment_update. Th
  > Added `OrderEvent(orderId, type)` to `OrderEventBus`, updated `KosherEatsMessagingService` to pass `order_id` and `type`

- **[5/10] [android_seller] Polling races initial load — dashboard fetches the same data twice on every screen entry** — FIXED
  DashboardViewModel.kt:42-58: `init { loadDashboard() }` fires immediately on VM creation. The DashboardScreen DisposableEffect (DashboardScreen.kt:73)
  > Moved the `delay(...)` call to the top of the `while (true)` loop in both `DashboardViewModel.startPolling` and `OrdersV

- **[5/10] [android_seller] OrdersViewModel.pollSilently couples list+detail success — a transient detail-404 throws the list poll into backoff** — FIXED
  OrdersViewModel.kt:77-107: `succeeded` starts as `response.isSuccessful` (the list response), then is overwritten to `false` when the detail call fail
  > Removed the `succeeded = false` assignment in the detail-fetch failure branch of `pollSilently`, so a detail-fetch hiccu

- **[5/10] [android_seller] Hilt-singleton OkHttp module launches DataStore collectors on an unscoped CoroutineScope** — FIXED
  RetrofitClient.kt:65-76 creates `CoroutineScope(SupervisorJob() + Dispatchers.IO)` inside the Hilt provider and launches two `dataStore.data.collect {
  > Promoted the `CoroutineScope(SupervisorJob() + Dispatchers.IO)` from a local variable inside `provideOkHttpClient` to a 

- **[4/10] [android_consumer] Deprecated material3 Divider in RegisterScreen (rest of codebase already on HorizontalDivider)** — FIXED
  RegisterScreen.kt:31 imports `androidx.compose.material3.Divider` and calls `Divider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)` at li
  > Replaced both Divider(...) calls with HorizontalDivider(...) and updated the import from material3.Divider to material3.

- **[4/10] [android_consumer] Country-code chip looks tappable but isn’t — no non-US numbers possible** — FAILED
  LoginScreen.kt:140-163 and PhonePromptScreen.kt:94-117 render a `🇺🇸 +1 ▾` chip styled like a dropdown, but there’s no clickable modifier and no countr
  > not reported by batch agent
