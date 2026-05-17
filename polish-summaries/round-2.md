# KosherEats Polish — Round 2
**Max severity found:** 9
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 16

## Issues & Fixes
- **[9/10] [android_consumer] Blocking runBlocking in OkHttp Auth Interceptor** — FIXED
  RetrofitClient.kt's provideAuthInterceptor uses `runBlocking { tokenProvider.awaitToken() }` to fetch the auth token synchronously. OkHttp interceptor
  > Removed runBlocking fallback entirely — interceptor now uses tokenProvider.token (the @Volatile cache) directly, proceed

- **[8/10] [android_consumer] CheckoutViewModel loses cart on process death after payment redirect** — FIXED
  CheckoutViewModel guards bootstrap() with `if (_bootstrapped) return` (~line 114) and persists cart JSON into SavedStateHandle via Gson (~lines 128-13
  > Dropped KEY_CART_JSON and full-cart SavedStateHandle persistence; bootstrap() now only saves restaurantId/dealId and ski

- **[7/10] [android_consumer] Insufficient keep rules for Gson reflection / enums in release builds** — FIXED
  proguard-rules.pro keeps `com.google.gson.**` and the ApiService interface (`-keep,allowobfuscation`), but does not preserve model field names or enum
  > Added @SerializedName keepclassmembers rule, broadened enum keepnames to com.koshereats.consumer.**, and added TypeAdapt

- **[7/10] [android_seller] Onboarding price-to-cents truncates instead of rounds (silent undercharges)** — FIXED
  OnboardingViewModel.submit at line 204 computes priceCents as ((priceDollars.toDoubleOrNull() ?: 0.0) * 100).toInt(). Because IEEE-754 cannot represen
  > Added `import kotlin.math.roundToInt` and changed `.toInt()` to `.roundToInt()` on OnboardingViewModel.kt:211.

- **[7/10] [android_seller] Session-expired path leaves stale refresh tokens persisted in DataStore** — FIXED
  TokenAuthenticator.signalSessionExpired (RetrofitClient.kt:221-225) nulls the in-memory cachedToken/cachedRefreshToken and emits sessionExpired = true
  > In `signalSessionExpired()` (RetrofitClient.kt), added `appScope.launch { context.dataStore.edit { it.clear() } }` so Da

- **[6/10] [android_consumer] Chat message ordering breaks for optimistic sends** — FIXED
  ChatViewModel merges optimistic local messages with server-fetched ones by `associateBy { it.id }` and then `sortedBy { it.createdAt }` (~lines 99-101
  > Fixed sort to push empty-createdAt messages to the tail; added optimistic insert with Instant.now() timestamp, 30s withT

- **[6/10] [android_consumer] TokenAuthenticator can dispatch multiple concurrent refreshes / logouts** — FIXED
  RetrofitClient.kt:189-225: the `synchronized(lock)` block guards a single instance but the lock instance is a fresh `Any()` per Authenticator — and Au
  > Added @Volatile logoutDispatched flag; concurrent threads entering after a failed refresh return null without re-calling

- **[6/10] [android_consumer] Cart is not persisted across process death** — FIXED
  CartViewModel only holds state in `MutableStateFlow` — no SavedStateHandle, no DataStore. When the app is killed (low memory, Stripe 3DS bounce, user 
  > Injected DataStore<Preferences> into CartViewModel; restores CartSnapshot (carts + activeRestaurantId) on init and persi

- **[6/10] [android_seller] Certificate uploads OOM on large photos and bypass the singleton OkHttp client** — FIXED
  uploadCertificate (CreateRestaurantScreen.kt:599-630) and uploadCertificateSettings (RestaurantSettingsScreen.kt:488-519) both call contentResolver.op
  > Removed `readBytes()` in both upload functions; replaced with `openFileDescriptor` for content length and `openInputStre

- **[6/10] [android_seller] MenuViewModel.createMenuItem rollback deletes server-side category but leaves it in state.categories** — FIXED
  MenuViewModel.kt:198-200: when the item POST fails after auto-creating a category, it calls `apiService.deleteCategory(createdCategoryId)` server-side
  > After the server-side `deleteCategory` rollback call, added `_state.update { it.copy(categories = it.categories.filter {

- **[6/10] [android_seller] OTP length hardcoded to 4 digits — silently wrong if backend issues 6-digit codes** — FIXED
  AuthViewModel.kt:274 caps OTP entry at 4 digits and AuthViewModel.kt:340 hard-rejects anything other than length 4, plus PhoneLoginScreen.kt:67 auto-v
  > Added `companion object { const val OTP_CODE_LENGTH = 6 }` to AuthViewModel; replaced all magic `4` literals in `updateO

- **[6/10] [android_seller] DealsViewModel and IntegrationsViewModel swallow CancellationException — breaks structured concurrency** — FIXED
  DealsViewModel.kt:57-62, 125-131, 145-150 and IntegrationsViewModel.kt:38-40, 53-56, 65-67, 79-81 all catch generic Exception without `if (e is Cancel
  > Added `if (e is CancellationException) throw e` in all 3 catch blocks in DealsViewModel, all 4 catch blocks in Integrati

- **[6/10] [android_seller] OnboardingViewModel.submit posts categories+items without pinning the new restaurant_id** — FIXED
  OnboardingViewModel.kt:190-220: after createRestaurant succeeds, the code loops `createCategory(...)` and `createMenuItemWithCategory(...)` immediatel
  > After successful `createRestaurant`, added `restResponse.body()?.id?.let { NetworkModule.cachedRestaurantId = it }` so t

- **[5/10] [android_consumer] Push deep link declared in code but no manifest intent-filter** — FIXED
  KosherEatsMessagingService.kt:74 builds `data = Uri.parse("koshereats://order/$it")` on the notification PendingIntent, but AndroidManifest.xml has ze
  > Removed the misleading data = Uri.parse("koshereats://order/$it") line; the order_id String extra that actually drives n

- **[5/10] [android_consumer] Phone-call and add-account intents are not guarded against ActivityNotFoundException** — FIXED
  OrderTrackingScreen.kt:359-362 calls `context.startActivity(Intent(ACTION_DIAL, tel:...))` and LoginScreen.kt:246-250 launches `Intent(Settings.ACTION
  > Wrapped both startActivity calls in try/catch(ActivityNotFoundException); added SnackbarHostState + SnackbarHost (via Bo

- **[5/10] [android_seller] DashboardViewModel races between FCM and timer polls via non-atomic _state.value = _state.value.copy(...)** — FIXED
  DashboardViewModel.startPolling (line 47-60) launches the FCM events collector and the backoff timer loop as two children of pollingJob, both calling 
  > Added `import kotlinx.coroutines.flow.update` and replaced every `_state.value = _state.value.copy(...)` in loadDashboar
