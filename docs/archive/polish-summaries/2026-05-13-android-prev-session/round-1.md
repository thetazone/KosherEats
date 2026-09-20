# KosherEats Polish — Round 1
**Max severity found:** 8
**Issues found:** 10
**Fixes attempted:** 2
**Fixes succeeded:** 2

## Issues & Fixes
- **[8/10] [android_seller] Category filter chips silently return zero items (server vs. enum name mismatch)** — FIXED
  MenuViewModel.loadMenuItems(category) filters server categories with `it.name.equals(category.name.lowercase(), ignoreCase = true)` — so it asks the s
  > All three fixes look correct. Here's a summary of what changed and why:

---

**`MenuViewModel.kt` — 3 sites fixed:**

*

- **[8/10] [android_seller] Cold-start race: dashboard loads wrong restaurant for multi-restaurant sellers** — FIXED
  In `NetworkModule.provideOkHttpClient`, `cachedRestaurantId` is populated by an asynchronous `scope.launch { context.dataStore.data.collect { ... } }`
  > Both changes look correct. Here's a summary of what was done:

**`RetrofitClient.kt` (3 changes):**
- Added `import kotl

- **[7/10] [android_seller] Seller can't cancel an order after accepting it** — skipped
  `OrdersViewModel.allowedTransitions` maps `ACCEPTED -> setOf(PREPARING)` only — no path to CANCELLED/REJECTED from ACCEPTED. The OrderActionButtons fo

- **[6/10] [android_seller] Orders list is silently capped at 20 with no pagination** — skipped
  `ApiService.getOrders(status, page=1, limit=20)` is called once on init and once per filter chip change with `page = 1, limit = 20` hard-coded — there

- **[6/10] [android_seller] OrderStatus enum lacks an unknown fallback — any new server status crashes order parsing** — skipped
  `OrderStatus` is a Moshi enum with `@JsonClass`-style `@Json(name = ...)` entries for the 10 known statuses. Moshi (and `KotlinJsonAdapterFactory`) th

- **[6/10] [android_seller] Missing canonical Retrofit interface `-if/-keep,allowobfuscation` rule** — skipped
  proguard-rules.pro already has the correct Retrofit+Continuation generics rules (lines 21-30) that fixed the Google Sign-In ClassCastException. What's

- **[5/10] [android_seller] Dashboard and Orders VMs duplicate every poll/push refresh** — skipped
  DashboardViewModel and OrdersViewModel each (a) subscribe to `OrderEventBus.events` and call `pollSilently()` on every push, and (b) run a `repeatOnLi

- **[5/10] [android_seller] pollSilently() refetches and overwrites selectedOrder long after user has navigated away** — skipped
  In `OrdersViewModel.pollSilently`, after polling the list it does `_state.value.selectedOrder?.id?.let { id -> apiService.getOrderDetail(id); _state.u

- **[5/10] [android_seller] TokenAuthenticator runs DataStore.edit inside runBlocking on the OkHttp network thread** — skipped
  `TokenAuthenticator.authenticate` (RetrofitClient.kt lines 186-191) wraps `context.dataStore.edit { ... }` in `runBlocking { ... }`. `authenticate` is

- **[4/10] [android_seller] Direct-to-S3 uploads allocate a fresh OkHttpClient every time** — skipped
  Both `MenuItemFormScreen.uploadImage` and `RestaurantSettingsScreen.uploadCertificateSettings` (and almost certainly `CreateRestaurantScreen` too) do 
