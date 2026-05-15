# KosherEats Polish — Round 1
**Max severity found:** 9
**Issues found:** 12
**Fixes attempted:** 11
**Fixes succeeded:** 11

## Issues & Fixes
- **[9/10] [android_consumer] Race + guest 401s trigger spurious global logout (clears cart, deletes FCM token)** — FIXED
  TokenProvider loads the persisted auth/refresh tokens asynchronously in its `init` coroutine, but the OkHttp `authInterceptor` reads `tokenProvider.to
  > Both changes look correct. Here's a summary of what was done and why:

---

**Two changes in `RetrofitClient.kt`:**

**1

- **[7/10] [android_consumer] Home feed pagination is dead code — hasMore compares against wrong page size** — FIXED
  HomeViewModel.loadRestaurants() sets `hasMore = result.data.size >= 50` (HomeViewModel.kt:105), but the API endpoint defaults to `perPage = 20` (ApiSe
  > Two changes made to `HomeViewModel.kt`:

- **Line 105**: `>= 50` → `>= 20` — threshold now matches the actual API page s

- **[7/10] [android_consumer] Sign Out doesn't clear the nav back stack — stale authed screens remain reachable** — FIXED
  ProfileScreen renders a Sign Out card that does `clickable { viewModel.logout() }` directly (ProfileScreen.kt:301-330). There's no callback into the N
  > Everything looks correct. Here's a summary of all changes made:

**ProfileScreen.kt:**
- Added `onSignOutClick: () -> Un

- **[7/10] [android_seller] Reject Order has no confirmation dialog** — FIXED
  SellerOrderDetailScreen wires the "Reject Order" button (status=PENDING) straight to viewModel.updateOrderStatus(orderId, CANCELLED) with no AlertDial
  > The fix is complete. Here's what changed in `SellerOrderDetailScreen.kt`:

**Changes:**
- Added imports: `AlertDialog`, 

- **[7/10] [android_seller] Settings screen lets unapproved sellers toggle Open** — FIXED
  RestaurantSettingsScreen's Open/Closed Switch is enabled = !authState.isTogglingOpen and lacks the isApproved gate that DashboardScreen correctly appl
  > The fix is clean and correct. Here's what changed in `RestaurantSettingsScreen.kt`:

- **Line 94**: Computed `isApproved

- **[7/10] [android_seller] createMenuItem creates duplicate server-side categories** — FIXED
  When adding a new menu item, MenuViewModel resolves the target category via `categories.firstOrNull { serverCat -> serverCat.items.any { it.category =
  > The fix moves `displayName` computation before the server lookup, then matches `serverCat.name` (case-insensitively) ins

- **[6/10] [android_consumer] OrderTracking polling/SSE never pauses when the screen is off-screen** — FIXED
  OrderTrackingViewModel.start() launches a `while (isActive) { delay(8_000); loadOnce(orderId) }` poll loop (OrderTrackingViewModel.kt:95-102) plus a l
  > Both files look correct. Here's a summary of what changed and why:

---

**`OrderTrackingViewModel.kt`**

- **`fun pause

- **[6/10] [android_seller] Process-wide 30s polling runs on every screen, in multiple VMs** — FIXED
  DashboardViewModel and OrdersViewModel both attach ProcessLifecycleOwner.repeatOnLifecycle(STARTED) loops that fire getDashboardStats+getOrders every 
  > All four files are updated. Here's what changed and why:

---

**`DashboardViewModel.kt` / `OrdersViewModel.kt`**

- **R

- **[6/10] [android_seller] FCM notifications stack instead of replacing in place** — FIXED
  KosherEatsMessagingService.showNotification calls nm.notify(System.currentTimeMillis().toInt(), notification), so every push gets a unique ID. One ord
  > Done. Three changes made to `KosherEatsMessagingService.kt`:

1. **Deterministic notification ID** (line 92): `notifId =

- **[6/10] [android_seller] provideOkHttpClient does runBlocking DataStore read on the main thread** — FIXED
  RetrofitClient.provideOkHttpClient runs `cachedRestaurantId = runBlocking { context.dataStore.data.first() }[...]` synchronously during Hilt resolutio
  > Done. The two changes:

1. **Removed the `runBlocking` prime** (lines 66–68 in the original) — the `DataStore.data.colle

- **[5/10] [android_consumer] Push notification taps drop the order_id — no deep link to the relevant order** — FIXED
  KosherEatsMessagingService.onMessageReceived parses `order_id` from the FCM payload (line 47) but `showNotification` never threads it into the Pending
  > All four files look correct. Here's a summary of every change made:

---

**KosherEatsMessagingService.kt** — Added `put

- **[4/10] [android_consumer] Deprecated `Icons.Filled.ArrowBack` — wrong direction in RTL locales (Hebrew/Arabic)** — skipped
  The manifest declares `android:supportsRtl="true"` and the audience (kosher food) skews toward Hebrew/Arabic speakers, but five screens still import `
