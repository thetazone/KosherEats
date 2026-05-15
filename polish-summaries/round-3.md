# KosherEats Polish — Round 3
**Max severity found:** 8
**Issues found:** 12
**Fixes attempted:** 10
**Fixes succeeded:** 10

## Issues & Fixes
- **[8/10] [android_consumer] POST_NOTIFICATIONS runtime permission never requested** — FIXED
  AndroidManifest.xml:9 declares the POST_NOTIFICATIONS permission, but no code anywhere in the app actually requests it at runtime (grep across all of 
  > Done. Here's what changed in `MainActivity.kt`:

- Added `RequestNotificationPermissionIfNeeded()` — a private Composabl

- **[8/10] [android_seller] OrderDetail polling skipped when entered from Dashboard** — FIXED
  OrdersViewModel.startPolling() is wired only into SellerOrdersScreen's DisposableEffect. When a seller taps an order card on the dashboard, navigation
  > Done. Here's what changed and why each piece matters:

**`OrdersViewModel.kt`** — reference-counted `startPolling`/`stop

- **[8/10] [android_seller] Active orders silently capped at 20 by getOrders default limit** — FIXED
  DashboardViewModel.loadDashboard / pollSilently / refresh call apiService.getOrders() with no arguments, picking up ApiService's default page=1, limit
  > All three call sites now pass `status = "active", limit = 200`. No changes were needed in `ApiService.kt` since the `sta

- **[7/10] [android_consumer] Delete-account navigates to Login before API call even fires** — FIXED
  NavGraph.kt:481-487 wires onDeleteAccountClick as `authViewModel.deleteAccount(); BottomNavItem.entries.forEach { clearBackStack(...) }; navController
  > The fix is in place. Here's what changed:

**`NavGraph.kt:481-490`** — `onDeleteAccountClick` now passes the `onComplete

- **[7/10] [android_seller] New-order push has no distinctive salience and no deep-link to order** — FIXED
  KosherEatsMessagingService routes every type (new_order, order_status_changed, payment_update, courier_assigned, order_cancelled) through one 'koshere
  > All three files look correct. Here's a summary of every change made:

---

**`KosherEatsMessagingService.kt`**
- Added `

- **[7/10] [android_seller] Order detail omits customer name, phone, and order placement time** — FIXED
  Order model has only customerId (no name/phone); SellerOrderDetailScreen renders nothing for the customer beyond the delivery address. There's also no
  > Both changes are correct. Here's what was done:

**`Models.kt`** — added two fields to `Order`:
- `customerName` mapped 

- **[6/10] [android_consumer] Tip picked in Cart is silently discarded on the way to Checkout** — FIXED
  CartScreen.kt:430-479 shows a flat-cents tip selector (None / $2 / $3 / $5 / $8) that writes to CartUiState.tip via cartViewModel.updateTip. CheckoutS
  > All imports (`Box`, `background`, `border`, `clickable`, `TextMuted`) are still used elsewhere in CartScreen — no import

- **[6/10] [android_seller] Polling sleeps 30s before first poll and Orders list has no pull-to-refresh** — FIXED
  Both DashboardViewModel.startPolling and OrdersViewModel.startPolling enter a while(true) loop that does delay(BACKOFF_DELAYS[0]) = 30_000 BEFORE the 
  > All three changes are done. Here's a summary:

**DashboardViewModel** (`startPolling`): swapped `delay` and `pollSilentl

- **[5/10] [android_consumer] FCM order deep-link can stack duplicate OrderTracking screens and bypass auth** — FIXED
  NavGraph.kt:99-105: `LaunchedEffect(pendingOrderId) { navController.navigate(Screen.OrderTracking.createRoute(id)) { popUpTo(Screen.Home.route) } }`. 
  > Three problems fixed with a minimal change to `NavGraph.kt` (no changes needed to `MainActivity.kt`):

1. **`launchSingl

- **[5/10] [android_seller] PICKED_UP → COMPLETED transition exposed to seller for delivery orders** — FIXED
  OrdersViewModel.allowedTransitions includes PICKED_UP → COMPLETED unconditionally, and SellerOrderDetailScreen.OrderActionButtons renders a green 'Mar
  > Both edits applied. Here's what changed:

**`OrdersViewModel.kt` (line 170–172):** The blocked-transition guard was exte

- **[4/10] [android_consumer] Non-data.models request DTOs not covered by keep rule** — skipped
  proguard-rules.pro:4 only keeps `com.koshereats.consumer.data.models.**`, but several @Body / @Response DTOs live elsewhere — most notably `com.kosher

- **[3/10] [android_consumer] CheckoutScreen still imports the deprecated platform LocalLifecycleOwner** — skipped
  CheckoutScreen.kt:51 imports `androidx.compose.ui.platform.LocalLifecycleOwner`, which has been deprecated since lifecycle-runtime-compose 2.7.0 in fa
