# KosherEats Polish — Round 2
**Max severity found:** 8
**Issues found:** 12
**Fixes attempted:** 12
**Fixes succeeded:** 6

## Issues & Fixes
- **[8/10] [android_consumer] allowBackup="true" leaks consumer auth tokens via adb backup** — FAILED
  AndroidManifest.xml line 12 leaves `android:allowBackup="true"` with no `android:dataExtractionRules` or `android:fullBackupContent` rule excluding th
  > not reported by batch agent

- **[8/10] [android_seller] Cancel Order button on accepted/preparing orders silently fails** — FIXED
  SellerOrderDetailScreen.OrderActionButtons renders a 'Cancel Order' button for ACCEPTED and PREPARING states (lines 481-525). OrdersViewModel.allowedT
  > Removed CANCELLED from allowedTransitions for ACCEPTED and PREPARING in OrdersViewModel.kt, and removed the Cancel Order

- **[7/10] [android_consumer] Anonymous (not-logged-in, not-guest) users can reach Checkout and hit a dead-end 401** — FAILED
  NavGraph.kt:263 only gates checkout with `if (authState.isGuest) { requireAuth(...) }`. On a fresh launch with no saved token, AuthViewModel.checkAuth
  > not reported by batch agent

- **[7/10] [android_consumer] AuthViewModel saveAuth/clearAuth race with TokenProvider's async DataStore observer drops the Authorization header on the very next request** — FAILED
  After login/register/socialLogin/phoneVerify (AuthViewModel.kt:144, 202, 245, 369), saveAuth() writes tokens via `dataStore.edit { ... }` (line 483–48
  > not reported by batch agent

- **[7/10] [android_consumer] EditProfile sends a full User PUT that clobbers id/email/profileImage** — FAILED
  EditProfileViewModel.kt:75-81 calls `apiService.updateProfile(User(firstName, lastName, phone))` with every other User field defaulted (id="", email="
  > not reported by batch agent

- **[7/10] [android_seller] Scheduled orders are invisible to sellers** — FIXED
  OrderStatus.SCHEDULED is the initial state for future-scheduled orders, but Models.kt:22 marks it as isActive=false. DashboardViewModel.kt:81, 103 the
  > Changed SCHEDULED's isActive from false to true in Models.kt (so the dashboard filter includes them), and added a 'Sched

- **[6/10] [android_consumer] MainActivity has no launchMode — push tap can stack instances** — FAILED
  AndroidManifest.xml:22-30 declares MainActivity with no launchMode (defaults to `standard`). The push intent in KosherEatsMessagingService.kt:70-81 us
  > not reported by batch agent

- **[6/10] [android_consumer] Checkout snapshot loses local cart across process death** — FAILED
  NavGraph.kt:271-289 captures `cartState.cart.items` in plain `remember { … }` and passes it to CheckoutScreen. Stripe's PaymentSheet can re-create the
  > not reported by batch agent

- **[6/10] [android_seller] Fresh OkHttpClient instantiated per image upload leaks pools** — FIXED
  Both image-upload helpers do val client = OkHttpClient() inside the suspend function (MenuItemFormScreen.kt:538, CreateDealScreen.kt:722). Each OkHttp
  > Added a file-level private val uploadClient = OkHttpClient() singleton in MenuItemFormScreen.kt and CreateDealScreen.kt,

- **[6/10] [android_seller] Image uploads buffer entire file into memory (OOM risk)** — FIXED
  Every upload helper (MenuItemFormScreen.uploadImage:535, CreateDealScreen.uploadDealImage:717, RestaurantSettingsScreen.uploadCertificateSettings:496,
  > Replaced readBytes() + toRequestBody() with a streaming RequestBody in all four upload helpers (MenuItemFormScreen, Crea

- **[6/10] [android_seller] Category lookup by display name causes spurious category creation** — FIXED
  MenuViewModel.createMenuItem builds `displayName` via `targetCategory.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}`, then case-i
  > In MenuViewModel.createMenuItem, replaced display-name matching with slug-based matching: server category names are norm

- **[5/10] [android_seller] formatPriceWhole truncates revenue with integer division** — FIXED
  Money.kt line 11 uses `nf.format(this / 100)` — integer division, not `/100.0`. So $99.99 (9999 cents) shows as `$99` on the dashboard's Revenue tile,
  > Changed this / 100 to this / 100.0 in Money.kt so integer cents are divided as a Double before formatting, letting maxim
