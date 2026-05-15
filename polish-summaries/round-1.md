# KosherEats Polish — Round 1
**Max severity found:** 10
**Issues found:** 12
**Fixes attempted:** 12
**Fixes succeeded:** 12

## Issues & Fixes
- **[10/10] [android_consumer] AuthUiState refactor leaves auth screens uncompilable** — FIXED
  AuthViewModel was refactored to expose sessionState: SessionState (Authenticated/Guest/LoggedOut) and dropped the isLoggedIn/isGuest booleans. But six
  > Added derived val isLoggedIn and val isGuest getters to AuthUiState in AuthViewModel.kt, restoring the properties all si

- **[9/10] [android_consumer] R8 keep rules miss the ApiService interface and Retrofit annotations** — FIXED
  proguard-rules.pro keeps retrofit2 itself but doesn't keep com.koshereats.consumer.data.api.ApiService, nor does it preserve RuntimeVisibleAnnotations
  > Added -keep,allowobfuscation interface com.koshereats.consumer.data.api.ApiService and -keepattributes RuntimeVisibleAnn

- **[9/10] [android_seller] Sellers cannot cancel an ACCEPTED or PREPARING order** — FIXED
  OrdersViewModel.kt:162-168 `allowedTransitions` only maps PENDING → {ACCEPTED, CANCELLED}. ACCEPTED/PREPARING have no path to CANCELLED, and `OrderAct
  > Added cancelInProgress() VM function (PATCH /cancel endpoint), Cancel Order OutlinedButton on ACCEPTED and PREPARING sta

- **[8/10] [android_consumer] Prices format with device locale currency, not USD** — FIXED
  Int.formatPrice() in data/models/Money.kt uses NumberFormat.getCurrencyInstance() with no locale, so it picks the device default. A user with Locale=f
  > Changed both formatPrice() and formatPriceWhole() in Money.kt to use NumberFormat.getCurrencyInstance(Locale.US) instead

- **[8/10] [android_seller] Reject collapses two distinct server states into OrderStatus.CANCELLED** — FIXED
  OrdersViewModel.kt:199 maps `OrderStatus.CANCELLED -> apiService.rejectOrder(orderId)`, but the server returns the order with status `REJECTED` (separ
  > Split into rejectPending() (calls rejectOrder, valid only from PENDING) and cancelInProgress() (calls cancelOrder), remo

- **[8/10] [android_seller] loadOrders/loadDashboard don't cancel prior coroutines — last-writer-wins races** — FIXED
  OrdersViewModel.loadOrders (line 105) launches a fresh coroutine on every filter chip tap. Spam-clicking through 'Pending → Accepted → Ready' fires 3 
  > Added private var loadJob: Job? to OrdersViewModel, DashboardViewModel, and MenuViewModel; each load function now does l

- **[8/10] [android_seller] Menu item save makes an extra round-trip for category, then duplicates on fuzzy mismatch** — FIXED
  MenuViewModel.createMenuItem (lines 115-186) calls `apiService.getSellerMenu()` *every* save just to look up the category id, nearly doubling save lat
  > Added categories: List<SellerMenuCategory> to MenuState, populated in loadMenuItems; createMenuItem now reads from the c

- **[7/10] [android_seller] formatPrice uses device-locale currency for amounts that are always USD cents** — FIXED
  Money.kt:5 calls `NumberFormat.getCurrencyInstance()` with no Locale argument. On a phone set to en-GB / de-DE / he-IL, prices render as '£4.99', '4,9
  > Pinned both formatPrice and formatPriceWhole in Money.kt to Locale.US.

- **[7/10] [android_seller] Dashboard runs stats + orders fetches sequentially instead of in parallel** — FIXED
  Both DashboardViewModel.loadDashboard (lines 64-94), refresh (lines 118-149), and pollSilently (lines 97-116) call `getDashboardStats()` then `getOrde
  > Wrapped the two API calls in coroutineScope { async/await } in loadDashboard, pollSilently, and refresh so both requests

- **[6/10] [android_consumer] pendingGuestReturn lost on process death during sign-in** — FIXED
  NavGraph.kt:97 uses `remember { mutableStateOf<String?>(null) }` to stash the guest's return route. Stripe 3DS recreates the activity and any deep-lin
  > Changed the pendingGuestReturn state holder in NavGraph.kt from remember to rememberSaveable so the return route survive

- **[6/10] [android_consumer] Address list never reloads after guest → authenticated** — FIXED
  AddressViewModel.init { loadAddresses() } fires once when the app starts. A user who opens as guest gets a 401 (no token) and the state holds an empty
  > Added a LaunchedEffect(authState.sessionState) in NavGraph.kt that calls addressViewModel.loadAddresses() whenever the s

- **[6/10] [android_consumer] Cart totals are guesses; checkout shows a different number** — FIXED
  CartUiState ships hardcoded deliveryFee=399, serviceFee=249, taxRate=0.08875. CartScreen prominently shows 'Checkout - $XX.XX' built from state.total.
  > Labeled delivery fee, service fee, and tax rows as (est.) in CartScreen.kt, changed the Total row to Est. Total, and pre
