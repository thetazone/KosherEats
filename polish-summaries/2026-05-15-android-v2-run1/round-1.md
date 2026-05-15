# KosherEats Polish — Round 1
**Max severity found:** 8
**Issues found:** 12
**Fixes attempted:** 12
**Fixes succeeded:** 12

## Issues & Fixes
- **[8/10] [android_consumer] No global observer for SessionManager.logoutEvent** — FIXED
  SessionManager.signalLogout() is fired by TokenAuthenticator (refresh failure) and OrderTrackingViewModel (SSE 401), but only CartViewModel and ChatVi
  > Added logoutEvent collector in AuthViewModel.init (calls clearAuth + resets state), exposed logoutEvent as a public prop

- **[8/10] [android_consumer] TokenProvider init can deadlock every HTTP call forever** — FIXED
  RetrofitClient.kt:63-74 launches a single coroutine that calls `dataStore.data.first()` and only then `_initialized.complete(Unit)`. The AuthIntercept
  > Wrapped the init coroutine body in try/finally that checks _initialized.isCompleted and calls complete(Unit) + logs an e

- **[8/10] [android_seller] Release keystore password committed in build.gradle.kts** — FIXED
  android/seller/app/build.gradle.kts:35-37 falls back to a hard-coded `koshereats2026` keystore password (and key password) when local.properties is mi
  > Removed the 'koshereats2026' hardcoded fallback; release signing now calls check() and fails the build immediately if KE

- **[8/10] [android_seller] KosherCertification serialized to wrong wire format on restaurant create** — FIXED
  CreateRestaurantViewModel.submit (line 169) sends `s.kosherCertification.name.lowercase()` — produces "ou", "ok", "kof_k", "star_k", "crc", "badatz", 
  > Changed CreateRestaurantRequest.kosherCertification from String to KosherCertification so Moshi serializes via @Json nam

- **[7/10] [android_consumer] AddressViewModel silently swallows every CRUD error** — FIXED
  addAddress, deleteAddress, setDefault, and clearDefault each end in `catch (_: Exception) {}` with no state mutation (AddressViewModel.kt:74-139). A 4
  > Replaced all empty catch(_: Exception){} blocks in addAddress, deleteAddress, setDefault, and clearDefault with _uiState

- **[7/10] [android_consumer] socialLogin error path leaks raw server error body to the UI** — FIXED
  AuthViewModel.kt:247-254 builds the user-facing error as `"Social login failed: $errorBody"`, where errorBody is the verbatim JSON/HTML from the backe
  > Replaced the raw errorBody string in socialLogin with a response.code()-mapped friendly message (401/409/else) and moved

- **[7/10] [android_consumer] AuthInterceptor + TokenAuthenticator block OkHttp dispatcher threads** — FIXED
  RetrofitClient.kt:112 runs `runBlocking { tokenProvider.awaitToken() }` on every request and TokenAuthenticator.kt:205 runs `runBlocking { tokenProvid
  > Changed AuthInterceptor to read tokenProvider.token (@Volatile field) directly with no runBlocking; made persistNewToken

- **[7/10] [android_consumer] Order-tracking SSE 401 has no UI follow-through** — FIXED
  OrderTrackingViewModel.kt:126-129 calls `sessionManager.signalLogout()` and returns when SSE returns 401, but with no global logout observer (see issu
  > After signalLogout() on SSE 401, added pollJob?.cancel() and _uiState.update { it.copy(order = null, errorMessage = "Ses

- **[7/10] [android_seller] createMenuItem silently drops spice level, prep time, allergens, calories, isAvailable** — FIXED
  MenuViewModel.createMenuItem (lines 146-155) builds CreateMenuItemBody but only forwards name, description, price, imageUrl, isMeat/isDairy/isPareve. 
  > Added spiceLevel, preparationTime, allergens, and calories fields to CreateMenuItemBody, then forwarded all of them (plu

- **[7/10] [android_seller] Modifier option price uses .toInt() — FP truncation loses cents** — FIXED
  MenuItemFormScreen.kt:777 computes `((opt.priceDelta.toDoubleOrNull() ?: 0.0) * 100).toInt()`. Because of double imprecision, $0.95 → 94.99999999... →
  > Changed the priceDelta conversion in MenuItemFormScreen's ModifierGroupDialog from .toInt() to .roundToInt(), using the 

- **[7/10] [android_seller] Deal fixed-amount discount + min-order use truncating .toInt()** — FIXED
  CreateDealScreen.kt `dollarsToCents` at line 701-705 is `(amount * 100).toInt()`. A seller creating a $9.95 fixed-amount discount or $14.95 min-order 
  > Replaced .toInt() with .roundToInt() in dollarsToCents (imported kotlin.math.roundToInt), and clamped percentage discoun

- **[7/10] [android_seller] Auto-backup default leaks auth tokens via adb backup** — FIXED
  AndroidManifest.xml line 22 leaves `android:allowBackup="true"` and there's no `android:dataExtractionRules` or `android:fullBackupContent` rule exclu
  > Set android:allowBackup="false" in AndroidManifest.xml, preventing adb backup from extracting the seller_prefs DataStore
