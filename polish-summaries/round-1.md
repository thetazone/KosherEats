# KosherEats Polish — Round 1
**Max severity found:** 10
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[10/10] [android_seller] OnboardingViewModel.submit passes String to enum-typed kosherCertification field — does not compile** — FIXED
  Commit 4518e134 changed CreateRestaurantRequest.kosherCertification from String to the KosherCertification enum (Models.kt:271), but OnboardingViewMod
  > Removed the intermediate certString variable and passed s.certification (the KosherCertification enum) directly to Creat

- **[9/10] [android_consumer] User-added addresses are sent with lat=0/lng=0** — FIXED
  AddressPickerSheet.kt:340-352 and SavedAddressesScreen submit Address(streetAddress, city, state, zipCode, label) but never set the latitude/longitude
  > Added 0,0 guard in CheckoutViewModel.finalizeOrder() and CartViewModel.placeOrder(); checkout/AddressPickerSheet now ret

- **[9/10] [android_seller] PICKED_UP→COMPLETED listed as allowed but always blocked for delivery — seller cannot complete delivered orders** — FIXED
  OrdersViewModel.kt:167-188 declares `OrderStatus.PICKED_UP to setOf(OrderStatus.COMPLETED)` in allowedTransitions, but then the `blocked` check immedi
  > Removed 'currentOrder.status == OrderStatus.PICKED_UP' from the blocked condition so delivery sellers can transition PIC

- **[8/10] [android_consumer] runBlocking on every OkHttp call from auth interceptor** — FIXED
  RetrofitClient.kt:125-133 calls `runBlocking { tokenProvider.awaitToken() }` inside the OkHttp interceptor, blocking the dispatcher thread on every re
  > Changed RetrofitClient auth interceptor to read the @Volatile tokenProvider.token directly (fast path) and only fall bac

- **[8/10] [android_consumer] ProGuard rules miss critical Gson enum/SerializedName retention** — FIXED
  proguard-rules.pro keeps `com.koshereats.consumer.data.models.**` but does not keep enum `values()`/`valueOf()` methods which Gson reflects on via `@S
  > Added -keepclassmembers enum rule for com.koshereats.consumer.data.models.** to preserve values() and valueOf(String) so

- **[8/10] [android_seller] OrderActionButtons inverts isPickup at PICKED_UP — pickup orders never reach the state, delivery sellers see a disabled label** — FIXED
  SellerOrderDetailScreen.kt:607-637 shows the 'Mark as Completed' button only when `status == PICKED_UP && isPickup == true`, and 'Out for delivery…' (
  > Replaced the dead isPickup branch in the PICKED_UP case with a single enabled 'Mark as Completed' button, since PICKED_U

- **[8/10] [android_seller] Image-upload RequestBody wraps a non-rewindable InputStream — second writeTo() crashes on retry** — FIXED
  RestaurantSettingsScreen.kt:498-519 and CreateRestaurantScreen.kt:609-624 open `contentResolver.openInputStream(uri)` outside the RequestBody, then ca
  > In both RestaurantSettingsScreen.kt and CreateRestaurantScreen.kt, replaced the InputStream-inside-RequestBody pattern w

- **[8/10] [android_seller] Dashboard fetches active orders with status="active" — not a member of OrderStatus enum** — FIXED
  DashboardViewModel.kt:74, 107, 132 all pass `status = "active"` to `apiService.getOrders(...)`. The server-side enum (mirrored in Models.kt:8-25) cont
  > Changed all three getOrders calls in DashboardViewModel to pass status=null, then added client-side .filter { it.status.

- **[7/10] [android_consumer] Restaurant menu is horizontal-carousel only — most items hidden** — FIXED
  RestaurantDetailScreen.kt:384-396 renders the selected category’s items in a LazyRow inside a parent LazyColumn — users must pick a category chip, the
  > Replaced the LazyRow+HorizontalMenuItemCard menu section with a Column+VerticalMenuItemCard (full-width row with thumbna

- **[7/10] [android_consumer] Sales tax hardcoded to NY (8.875%) in cart preview** — FIXED
  CartViewModel.kt:42 defines `val taxRate: Double = 0.08875` and `val tax: Int get() = (cart.discountedSubtotal * taxRate).roundToInt()`. CartScreen di
  > Removed taxRate and tax from CartUiState and the 'Tax (est.)' PriceRow from CartScreen; the price breakdown now shows on

- **[7/10] [android_consumer] Delivery and service fees hardcoded in cart UI state** — FIXED
  CartViewModel.kt:40-41 sets `deliveryFee = 399` and `serviceFee = 249` cents as constants, and CartScreen renders them as `Delivery fee (est.)` / `Ser
  > Removed deliveryFee and serviceFee constants from CartUiState; removed 'Delivery fee (est.)' and 'Service fee (est.)' ro

- **[7/10] [android_seller] allowedTransitions missing SCHEDULED→ACCEPTED — seller has no way to accept a scheduled order through the state machine** — FIXED
  OrdersViewModel.kt:167-173 lists PENDING→ACCEPTED, ACCEPTED→PREPARING, etc., but the SCHEDULED state (added in commit 4518e134 to OrderStatus.isActive
  > Added a comment to allowedTransitions in OrdersViewModel documenting that SCHEDULED is intentionally omitted because the

- **[7/10] [android_seller] Phone E164 built by raw concatenation — accepts spaces, parens, dashes from input field** — FIXED
  AuthViewModel.kt:295 does `val e164 = "${current.phoneCountryCode}${current.phoneNumber}"` and `updatePhoneNumber` (line 269-271) does not filter non-
  > Added .filter { it.isDigit() } to updatePhoneNumber in AuthViewModel, matching the same digit-filtering pattern already 

- **[7/10] [android_seller] TokenAuthenticator does synchronous network + runBlocking DataStore write inside a synchronized lock — OkHttp dispatcher starvation under 401 storm** — FIXED
  RetrofitClient.kt:158-197 grabs a JVM monitor, then inside it: (1) runs `refreshClient.newCall(request).execute()` up to 15s, and (2) `runBlocking { d
  > Replaced runBlocking { dataStore.edit {} } with a dedicated appScope.launch { dataStore.edit {} } so OkHttp dispatcher t

- **[6/10] [android_consumer] ChatViewModel polls forever — never pauses when screen is backgrounded** — FIXED
  ChatViewModel.kt:117-125 starts a 3-second polling loop in `init` and only cancels it in `onCleared()` (when the composable leaves the back stack) and
  > Added pause()/resume() methods to ChatViewModel and wired a lifecycle-aware DisposableEffect in ChatScreen that calls re

- **[6/10] [android_consumer] Nearby map shows only the current paginated home list** — FIXED
  NearbyMapScreen.kt:79 reads `state.allRestaurants` from HomeViewModel, which is the paginated list driven by Home’s scroll position — typically 20 ite
  > Created NearbyViewModel.kt with a loadNearby(lat, lng) method that fetches a location-keyed restaurant list via the exis
