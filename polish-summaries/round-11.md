# KosherEats Polish — Round 11
**Max severity found:** 9
**Issues found:** 10
**Fixes attempted:** 10
**Fixes succeeded:** 10

## Issues & Fixes
- **[9/10] [android_consumer] FIXED-amount deal badge truncates cents via Int division** — FIXED
  Models.kt:604 — DiscountType.FIXED renders `"$${discountValue / 100} Off"` with Int math. A $2.50 deal (250¢) shows as "$2 Off" and a $0.99 deal (99¢)
  > Changed `discountValue / 100` to `"%.2f".format(discountValue / 100.0)` so $2.50 and $0.99 deals display correctly.

- **[9/10] [android_seller] Strict Moshi enum parsing breaks every list response on unknown values** — FIXED
  OrderStatus and MenuCategory are mapped with @Json(name="...") under KotlinJsonAdapterFactory. Moshi throws JsonDataException whenever the server retu
  > Added UNKNOWN fallback to OrderStatus and MenuCategory enums, implemented UnknownFallbackEnumAdapterFactory in Models.kt

- **[8/10] [android_consumer] CheckoutViewModel.finalizeOrder strands isProcessing=true on early returns** — FIXED
  CheckoutViewModel.kt:370-382 — `onPayTapped` sets `isProcessing = true`. In non-stub flow, Stripe returns and `finalizeOrder()` runs. The two pre-laun
  > Added `isProcessing = false` to all three early-return guards in `finalizeOrder` (address null, zero lat/lng, and missin

- **[8/10] [android_consumer] Guest session bypasses auth gate on OrderConfirmation / OrderTracking / Chat** — FIXED
  SessionState has 4 values (Authenticated, Guest, LoggedOut, Unknown — AuthViewModel.kt:31-36). NavGraph.kt:319, 347, 388 each guard with `sessionState
  > Changed all three `== SessionState.LoggedOut` guards in NavGraph.kt to `!= SessionState.Authenticated`, matching the exi

- **[8/10] [android_consumer] Transient profile-fetch error on bootstrap leaves user=null with sessionState=Authenticated** — FIXED
  AuthViewModel.kt:112-118 — on transient/5xx getProfile() errors during cold start, the VM sets `sessionState = Authenticated, isSessionStale = true` b
  > Gated `isLoggedIn` on `user != null && !isSessionStale` for the Authenticated state so screens that read `authState.user

- **[8/10] [android_seller] AuthViewModel.loadRestaurant strands the user on a blank screen for any non-401 failure** — FIXED
  loadRestaurant() only ever sets hasRestaurants on the success branch. The 401 branch clears auth, the IOException branch is swallowed silently, and th
  > In loadRestaurant(), the non-401 HTTP error branch and the IOException catch block both now set hasRestaurants = true (w

- **[7/10] [android_consumer] MenuItemSheet uses remember without keys — stale customizations across items** — FIXED
  MenuItemSheet.kt:135-136 — `remember { defaultCustomizationsFor(menuItem) }`, `remember { mutableStateMapOf() }`, `remember { quantity }`, `remember {
  > Added `menuItem.id` as a key to all four `remember` calls so quantity, instructions, customizations, and selections rese

- **[7/10] [android_seller] loadMoreOrders races with loadOrders / filter change and appends wrong-filter rows** — FIXED
  loadMoreOrders() captures `currentState` and `currentState.currentPage` before launching, then on completion does `it.copy(orders = it.orders + body, 
  > Added loadMoreJob tracking; loadOrders cancels it on each call; loadMoreOrders captures filterAtStart before the API cal

- **[7/10] [android_seller] MenuItem.category defaults to MAINS, silently breaking the menu category filter** — FIXED
  MenuItem.category is a strict MenuCategory enum with a default of `MAINS`. The seller endpoint returns items nested under SellerMenuCategory.items key
  > Changed MenuState.selectedCategory to SellerMenuCategory?, updated loadMenuItems and loadMenuItem to filter by server ca

- **[6/10] [android_seller] createMenuItem orphans the freshly-created category on a thrown exception** — FIXED
  The orphan-cleanup that calls deleteCategory(createdCategoryId) only runs inside `if (!response.isSuccessful)`. If the createMenuItemWithCategory call
  > Moved createdCategoryId declaration outside the try block and added the same deleteCategory cleanup call in the catch bl
