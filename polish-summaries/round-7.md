# KosherEats Polish — Round 7
**Max severity found:** 8
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 8

## Issues & Fixes
- **[8/10] [android_consumer] SavedAddressesScreen creates addresses with lat=0/lng=0; checkout later refuses to use them** — FAILED
  SavedAddressesScreen.kt:262-282 builds `Address(streetAddress=..., city=..., state=..., zipCode=...)` with no geocoding step, so latitude/longitude de
  > not reported by batch agent

- **[8/10] [android_consumer] Successful EditProfile save never refreshes AuthViewModel.user — ProfileScreen shows stale name/phone** — FAILED
  EditProfileViewModel.saveProfile (EditProfileViewModel.kt:71-90) calls updateProfileFields() and sets `saved = true`, but AuthViewModel holds its own 
  > not reported by batch agent

- **[8/10] [android_seller] Phone-OTP success leaves user stranded on PhoneLoginScreen** — FIXED
  NavGraph.kt:99-109 only redirects to Dashboard/Onboarding when `currentDestination.route == Screen.Login.route`. After a successful phone verification
  > Extended the redirect predicate in NavGraph.kt to include Screen.PhoneLogin.route, so the LaunchedEffect navigates to Da

- **[8/10] [android_seller] Cold-start race: cachedToken populated asynchronously, early requests fly without Authorization** — FIXED
  RetrofitClient.kt:66-76 spins up two `appScope.launch { dataStore.data.collect { ... } }` blocks to seed `cachedToken`/`cachedRestaurantId`. ViewModel
  > Added a runBlocking { dataStore.data.first() } synchronous prime at the top of provideOkHttpClient to seed cachedToken/c

- **[7/10] [android_consumer] TrackingMap camera re-animates to bounds on every SSE courier-position update — user can't pan/zoom** — FAILED
  OrderTrackingScreen.kt:200-211 `LaunchedEffect(restaurant, courier, delivery)` calls `cameraPositionState.animate(newLatLngBounds(...))` whenever the 
  > not reported by batch agent

- **[7/10] [android_consumer] Pay button stays enabled while bundle is reloading after tip/address change — user can pay against stale total** — FAILED
  CheckoutScreen.kt:213-215: `canPay = ui.bundle != null && (...) && !ui.isProcessing`. Missing: `!ui.isLoadingBundle`. After selectTip(), updateCustomT
  > not reported by batch agent

- **[7/10] [android_consumer] MenuItemSheet ignores `required` and `maxSelections` on customization groups** — FAILED
  MenuItemSheet.kt:126-378 renders all options as toggleable checkboxes. The `MenuItemCustomization.required` flag is never read — user can Add to cart 
  > not reported by batch agent

- **[7/10] [android_consumer] CheckoutViewModel.refreshBundle has no per-call cancellation — concurrent calls race; last-writer wins** — FAILED
  selectTip, setFulfillmentType, selectAddress, addAddress, and updateCustomTip (debounced) all call refreshBundle() (CheckoutViewModel.kt:306-333). Eac
  > not reported by batch agent

- **[7/10] [android_consumer] Debug build BASE_URL points at production fly.dev — developer builds hit real users' data** — FAILED
  app/build.gradle.kts:81 redefines BASE_URL in `debug { }` to the production URL `https://koshereats-api.fly.dev/api/v1/`, identical to the release blo
  > not reported by batch agent

- **[7/10] [android_consumer] NotificationPreferencesViewModel.save rollback drops concurrent toggles when one request fails** — FAILED
  NotificationPreferencesViewModel.kt:55-68: each toggle captures `previous = _uiState.value.prefs` at call time. If the user flips A on (save({A=true,B
  > not reported by batch agent

- **[6/10] [android_seller] Brittle JSONObject parsing in token refresh; any backend field rename → forced logout** — FIXED
  TokenAuthenticator.tryRefresh (RetrofitClient.kt:202-220) parses the refresh response with `JSONObject.getString("token")`/`getString("refresh_token")
  > Added Moshi parameter to provideOkHttpClient and TokenAuthenticator; rewrote tryRefresh to parse the response body via m

- **[6/10] [android_seller] updateRestaurantField swallows network/HTTP errors silently** — FIXED
  AuthViewModel.kt:226-236: every settings field write goes through this method with no error reporting. On 4xx/5xx or IOException, the state.restaurant
  > Added updateFieldError: String? = null to AuthState and clearUpdateFieldError() to AuthViewModel; updateRestaurantField 

- **[6/10] [android_seller] OrderEventBus drops FCM events emitted before any VM subscribes** — FIXED
  OrderEventBus.kt uses `MutableSharedFlow(extraBufferCapacity = 10, onBufferOverflow = DROP_OLDEST)` with `replay = 0`. On cold launch from a 'new orde
  > Changed MutableSharedFlow to replay = 1 in OrderEventBus so the most-recent event is cached and replayed to late subscri

- **[6/10] [android_seller] Polling backoff has no NetworkCallback recovery — up to 5 min delay after connectivity returns** — FIXED
  DashboardViewModel.kt:165-167 and OrdersViewModel.kt:382-385 use `BACKOFF_DELAYS` capped at 5 min. When Wi-Fi drops and reconnects, the loop is still 
  > Added @ApplicationContext Context to both DashboardViewModel and OrdersViewModel; startPolling now registers a Connectiv

- **[5/10] [android_seller] Dashboard counts SCHEDULED orders as 'active' — clutters dashboard with future orders** — FIXED
  OrderStatus.isActive (Models.kt:20-24) returns true for SCHEDULED. DashboardViewModel.loadDashboard/pollSilently/refresh all filter `it.status.isActiv
  > Added SCHEDULED to the false cases of OrderStatus.isActive in Models.kt; the existing filter { it.status.isActive } in D

- **[5/10] [android_seller] Orders list has no pagination — sellers limited to the most-recent 200 orders forever** — FIXED
  ApiService.getOrders takes page+limit but every caller (DashboardViewModel.loadDashboard/pollSilently/refresh and OrdersViewModel.loadOrders/refresh/p
  > Added isLoadingMore/hasMorePages/currentPage to OrdersState; loadOrders resets to page 1, new loadMoreOrders() appends t
