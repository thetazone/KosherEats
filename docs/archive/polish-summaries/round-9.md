# KosherEats Polish — Round 9
**Max severity found:** 8
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[8/10] [android_consumer] MenuItemSheet customization checkboxes don't repaint** — FIXED
  MenuItemSheet.kt:136-149 uses `mutableStateMapOf<String, MutableSet<String>>()`. Snapshot only observes key insert/remove on the map, NOT mutations of
  > Changed selections map value type from MutableSet<String> to immutable Set<String> and rewrote toggle() to reassign the 

- **[6/10] [android_consumer] OrderTrackingViewModel.start() guard ignores streamJob + races on reentry** — FIXED
  OrderTrackingViewModel.kt:55-66. Guard `if (currentOrderId == orderId && pollJob?.isActive == true) return` only looks at pollJob. Once `launchLocatio
  > Added setupJob field, cancel it at the top of start() to prevent rapid-reentry races, and updated the early-return guard

- **[6/10] [android_consumer] CheckoutViewModel: lost restaurantId after process death + no double-tap guard on pay** — FIXED
  CheckoutViewModel.kt has two bugs. (a) Process-death recovery: `bootstrap` only writes KEY_RESTAURANT_ID to savedState when the live `restaurantId` ar
  > Always persist effectiveRestaurantId/effectiveDealId to SavedStateHandle (removed the non-empty write-guard), and added 

- **[5/10] [android_consumer] CheckoutScreen bootstrap LaunchedEffect keyed on Unit silently drops late-arriving cart** — FIXED
  CheckoutScreen.kt:90-92 uses `LaunchedEffect(Unit) { vm.bootstrap(localCart, restaurantId, appliedDealId) }` paired with the `_bootstrapped` short-cir
  > Re-keyed LaunchedEffect on (restaurantId, localCart.firstOrNull()?.id) so it re-fires when the rehydrated cart arrives, 

- **[5/10] [android_consumer] CartViewModel persists deal-only carts (zero items) — orphan deals survive across app sessions** — FIXED
  CartViewModel.kt:252-265 applyDeal() creates an empty Cart shell if none exists yet (so the deal banner renders before items are added), then persistS
  > Added filterValues { it.items.isNotEmpty() } in persistSnapshot() so empty deal-shell carts are excluded from DataStore 

- **[5/10] [android_consumer] AsyncImage call sites have no placeholder or error fallback — broken/slow URLs leave blank holes** — FIXED
  Bare `AsyncImage(model = url, ...)` is used everywhere: RestaurantDetailScreen.kt (hero, menu items, deals), CartScreen.kt (restaurant thumbnails), De
  > Added ColorPainter(SurfaceDark) as placeholder and error painter to all bare AsyncImage calls in RestaurantDetailScreen 

- **[5/10] [android_consumer] SELECTED_ADDRESS_ID not cleared on logout — possible carryover between user accounts on a shared device** — FIXED
  AddressViewModel.kt writes SELECTED_ADDRESS_ID into the shared koshereats_prefs DataStore (line 70) and reads it back during loadAddresses (line 47). 
  > Injected SessionManager into AddressViewModel and added a logoutEvent collector in init that resets UI state and removes

- **[5/10] [android_consumer] AddressViewModel.init { loadAddresses() } fires before auth — guaranteed 401 on cold start for guests, sets a stale error** — FIXED
  AddressViewModel.kt:36-38 calls loadAddresses() inside init. The VM is constructed when NavGraph spins up (line 95) — before any auth check has resolv
  > Removed init { loadAddresses() } from AddressViewModel; auth-gated reload is already handled by NavGraph's existing Laun

- **[4/10] [android_seller] Order model has no `scheduled_for` field — Scheduled-status UI cannot show when the order activates** — FIXED
  Order data class (Models.kt:108-128) only has createdAt/updatedAt. SCHEDULED orders are shown via OrderStatus.SCHEDULED, but neither ActiveOrderCard (
  > Added `@Json(name = "scheduled_for") val scheduledFor: String? = null` to Order data class, added activation-time displa

- **[3/10] [android_seller] SellerOrdersScreen filter chips omit REJECTED status** — FIXED
  Models.kt:18 defines OrderStatus.REJECTED with display name 'Rejected'. SellerOrdersScreen.kt:76-87 enumerates filter chips for every other status (in
  > Added `OrderStatus.REJECTED to "Rejected"` to the filters list in SellerOrdersScreen.kt after CANCELLED.

- **[3/10] [android_seller] Delivered orders render with no contextual UI in the detail action area** — FIXED
  SellerOrderDetailScreen.OrderActionButtons (line 472-638) handles SCHEDULED/PENDING/ACCEPTED/PREPARING/READY/PICKED_UP, but falls through `else -> { /
  > Replaced the empty `else -> { /* No actions */ }` branch in OrderActionButtons with an info Card that shows a status-app

- **[3/10] [android_seller] Notification ID collision risk from orderId.hashCode()** — FIXED
  KosherEatsMessagingService.kt:110 derives the notification ID as `orderId?.hashCode() ?: type?.hashCode() ?: CHANNEL_ID.hashCode()`. Java String hashC
  > Replaced `hashCode()` with a ConcurrentHashMap<String, Int> + AtomicInteger counter in the companion object; each unique

- **[3/10] [android_seller] Instant.parse(createdAt) silently swallowed — 'X min ago' disappears permanently if backend changes format** — FIXED
  ActiveOrderCard.kt:96-100 and SellerOrderDetailScreen.kt:187-189 wrap `Instant.parse(order.createdAt)` in `runCatching{}.getOrNull()`. `Instant.parse`
  > Added an OffsetDateTime.parse fallback via recoverCatching in both ActiveOrderCard and SellerOrderDetailScreen so zone-o

- **[3/10] [android_seller] Orphan category cleanup on item create failure is best-effort and easy to miss** — FIXED
  MenuViewModel.createMenuItem (MenuViewModel.kt:121-215) auto-creates a missing category, then attempts the item POST. On failure it `runCatching { api
  > Changed the cleanup call to capture the result; on success the category is removed from local state as before, but on cl

- **[3/10] [android_seller] RestaurantSettings.uploadCertificateSettings instantiates a fresh OkHttpClient per upload** — FIXED
  RestaurantSettingsScreen.kt:501 `val client = OkHttpClient()` inside the upload helper. Each cert upload spins up a new dispatcher + connection pool. 
  > Hoisted OkHttpClient to a file-level `certUploadClient` val and replaced the per-call `val client = OkHttpClient()` with

- **[3/10] [android_seller] MenuManagementScreen and DealsScreen reload on every ON_RESUME** — FIXED
  Both screens attach a LifecycleEventObserver that triggers `loadMenuItems` / `loadDeals` on every Lifecycle.Event.ON_RESUME (MenuManagementScreen.kt:7
  > Removed the DisposableEffect/LifecycleEventObserver block from MenuManagementScreen entirely (ViewModel.init already loa
