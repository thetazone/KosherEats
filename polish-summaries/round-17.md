# KosherEats Polish — Round 17
**Max severity found:** 9
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[9/10] [android_consumer] auth-tokens-plaintext-datastore** — FIXED
  Auth & refresh tokens are persisted in plain (unencrypted) Preferences DataStore at /data/data/com.koshereats.consumer/files/datastore/koshereats_pref
  > Done. Here's a summary of all changes made:

**`android/consumer/app/build.gradle.kts`**
- Added `androidx.security:secu

- **[8/10] [android_seller] Token refresh response deserialization can kick user out** — FIXED
  TokenAuthenticator.tryRefresh parses the /auth/refresh response as LoginResponse, but LoginResponse.user is non-null (`val user: User`) with no @Json 
  > Two changes made:

1. **`Models.kt`** — Added `RefreshResponse` with only `token` and `refresh_token` fields (both have 

- **[8/10] [android_seller] Status-filtered list keeps stale rows after server-side transition** — FIXED
  OrdersViewModel.pollSilently and refresh fetch with a `status` query param, then merge: `current.orders.map { existing -> newById[existing.id] ?: exis
  > Both `pollSilently` and `refresh` are fixed. The change: `mapNotNull { existing -> newById[existing.id] ?: if (filterAtS

- **[7/10] [android_consumer] runblocking-on-okhttp-network-thread** — FIXED
  AuthInterceptor uses runBlocking { tokenProvider.awaitToken() } on every request (RetrofitClient.kt line 132). Blocking OkHttp's dispatcher threads in
  > Two changes made to `RetrofitClient.kt`:

1. **Line 124** (now 123): replaced `runBlocking { tokenProvider.awaitToken() 

- **[7/10] [android_consumer] no-back-handler-anywhere** — FIXED
  Zero BackHandler usage across the entire app. Critical flows with side state — Cart multi-cart vs detail (CartScreen.kt line 80 var showingDetail), Ph
  > All three changes look correct. Here's a summary of what was done:

**CartScreen.kt** (`line 86-88`) — `BackHandler(enab

- **[7/10] [android_consumer] checkout-paymentsheet-lifecycle-collect** — FIXED
  CheckoutScreen.kt lines 104-127 launches `vm.events.collect` inside `repeatOnLifecycle(STARTED)` keyed by `vm.events` (the Flow itself). vm.events is 
  > Done. Here's a summary of what changed and why:

**Root cause:** `Channel.receiveAsFlow()` paired with `repeatOnLifecycl

- **[7/10] [android_consumer] proguard-missing-stripe-keep-attributes** — FIXED
  proguard-rules.pro keeps com.stripe.** classes (line 57) but does NOT keep PaymentSheet result classes used reflectively by Stripe's PaymentLauncher, 
  > Both fixes are applied. Here's a summary of the changes to `proguard-rules.pro`:

**Stripe section** — added two lines:


- **[7/10] [android_consumer] guest-blocked-toplevel-launchedeffect-popback** — FIXED
  NavGraph.kt routes for OrderConfirmation, OrderTracking, Chat (lines 319-324, 347-354, 388-391) immediately call `LaunchedEffect(Unit) { navController
  > All three guards updated. The change is the same in each case: `authState.sessionState != SessionState.Authenticated` → 

- **[7/10] [android_consumer] checkout-syncservercart-clears-on-error** — FIXED
  CheckoutViewModel.syncLocalCartToServer (lines 168-193) first calls `api.clearServerCart()` then loops `api.addToCart`. If the first add succeeds but 
  > The fix adds a compensating `clearServerCart()` call in the catch block at line 184. On any partial-add failure the serv

- **[7/10] [android_seller] MenuItemFormScreen wipes user input on rotation when creating** — FIXED
  In MenuItemFormScreen.kt:157-181, the LaunchedEffect(state.selectedItem) initializes form fields when selectedItem is non-null and sets formInitialize
  > Added `formInitialized = true` at line 181 in the new-item `run` branch. Now the guard at line 158 (`if (formInitialized

- **[7/10] [android_seller] Non-null assertion on response.body() crashes on empty 2xx** — FIXED
  AuthViewModel.login, socialLogin, and verifyPhoneCode each do `val body = response.body()!!` after isSuccessful. If the backend ever returns 200/204 w
  > All three `!!` calls are replaced. Each now checks for `null` after `response.body()` and transitions the UI into an err

- **[6/10] [android_consumer] deep-link-state-singleton-leak** — FIXED
  DeepLinkState in NavGraph.kt is a process-wide object with MutableStateFlow<String?> (lines 84-86). MainActivity writes intent.getStringExtra("order_i
  > Both files look correct. The fix is complete. Here's what changed and why:

**`NavGraph.kt`:**
- Removed `internal objec

- **[6/10] [android_seller] SocialLoginRequest sits outside the data.models keep package** — FIXED
  SocialLoginRequest is declared in com.koshereats.seller.data.api (ApiService.kt:10), not data.models, so the broad `-keep class com.koshereats.seller.
  > Added an explicit `-keep class com.koshereats.seller.data.api.SocialLoginRequest { *; }` rule to `proguard-rules.pro`, d

- **[6/10] [android_seller] Retrofit suspend Response<T> generics rely on a single keep rule** — FIXED
  The proguard rule `-keep,allowobfuscation interface com.koshereats.seller.data.api.ApiService` hard-codes the FQN. If anyone renames or splits the int
  > Done. Line 38 of `android/seller/app/proguard-rules.pro` now uses `-keep,allowobfuscation interface com.koshereats.selle

- **[6/10] [android_seller] Slow loadMenuItem can clobber user edits** — FIXED
  MenuItemFormScreen.kt:157 initializes the form the first time selectedItem becomes non-null. If the GET /seller/menu round-trip is slow, the user can 
  > Good. The fix is correct. No changes to `MenuViewModel.kt` were needed — `MenuState.isLoading` already tracks the fetch 

- **[6/10] [android_seller] Old user keeps receiving push after logout on shared device** — FIXED
  clearAuth calls PushBootstrap.deleteToken() (which only deletes the FCM token client-side) but never asks the backend to unregister the device. The ba
  > Three changes made:

- **`ApiService.kt`**: Added `@DELETE("devices/{token}") suspend fun unregisterDevice(token: String
