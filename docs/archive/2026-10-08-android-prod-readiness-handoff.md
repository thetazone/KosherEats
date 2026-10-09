# Android production-readiness fixes — handoff (2026-10-08)

Worktree: /Users/samma/projects/Mamiye-Eats-fix (branch fix/android-prod-readiness, clean, based on main d962ac3b).
Main checkout: /Users/samma/projects/Mamiye-Eats (do not edit there until merge).
Gitignored build files already copied into the worktree: android/{consumer,seller}/local.properties, consumer/app/google-services.json, both app/release-upload.jks. Seller has no google-services.json by design.

## Rules for this work (Salto, 2026-10-08)
- ONE agent at a time, or do it by hand. No parallel fan-out, no re-running the review. The 140-agent review + 4 parallel fixers exhausted the 5-hour session limit twice.
- "Fix everything": pick the fix path yourself, no option questions; present completed fixes as a list with commits.
- Keep RESTAURANT_PAYOUTS_ENABLED off. Don't touch iOS here.

## Findings source
Full reviewer output (6 of 8 reviewers finished; seller correctness + consumer contract never ran):
/private/tmp/claude-501/-Users-samma/7629557c-9f4b-4380-bebf-ad73f6728419/scratchpad/raw_findings.txt
Sections: find:consumer:security (7), find:consumer:stale (15), find:consumer:correctness (16), find:seller:security (6), find:seller:stale (12), find:seller:contract (9). 21 consumer items passed adversarial verification; seller items unverified but have file:line evidence.

## Fix plan (decided)
### Backend (worktree backend/, go test uses docker Postgres :5433, openapi lint: npx --yes @redocly/cli@latest lint openapi.yaml)
B1 GET /restaurants: implement page + per_page (default 50, cap 100, LIMIT/OFFSET), doc in openapi, test.
B2 Add DELETE /user/addresses/{id}/default (ClearDefaultAddress, owner-scoped), route in cmd/api/main.go ~514, openapi, test.
B3 GET /seller/orders: add `status` (single or comma list, validated, 400 on unknown), keep cursor; doc honestly (limit, cursor, status), test.
B4 Migration 065: restaurants.delivery_mode default 'external', update rows 'platform'→'external'; CreateRestaurant INSERT sets 'external'. Grep dispatcher/orders.go/delivery_quote/dev_seed/tests for 'platform'.
B5 fcm.go: set android.notification.channel_id per device role (consumer "koshereats_consumer_default"; seller id from android/seller push service) if cheaply derivable.

### Consumer app (android/consumer)
C1 CRITICAL CartViewModel TypeToken → CartSnapshot::class.java + proguard Gson TypeToken keep rules (+ keep CartSnapshot). Grep other TypeToken uses.
C2 CRITICAL CheckoutViewModel.onPayTapped isGeocoded gate → coords check (lat!=0||lng!=0); delete is_geocoded from Models.
C3 Shared Geocoder helper used by home/AddressPickerSheet, checkout/AddressPickerSheet, SavedAddressesScreen; refuse save on failure.
C4 Forgot password: real flow (POST auth/password/forgot, auth/password/reset; shapes in openapi ~233-270 + handlers/password_reset.go); two-step screen; remove koshereats.dev.
C5 ProfileScreen: Help → mailto support@koshereats.shop; add Privacy (https://koshereats.shop/privacy) + Terms (https://koshereats.shop/terms) rows; LegalUrls object.
C6 EditProfile: remove phone field (backend ignores); add "Change phone" via existing OTP flow (AuthViewModel sendVPhoneCode/confirmVPhoneCode).
C7 Delete account: show state.error / 409 message in ProfileScreen; loading state.
C8 AuthViewModel.checkAuthStatus 401 → clear auth only if refresh token is gone; else mark stale.
C9 Delete PhonePrompt feature (screen, route, NavGraph, needsPhone/submitPhone/skipPhone).
C10 Stripe process death: persist in-flight PI id + totals to DataStore before presenting PaymentSheet; recover in onPaymentResult.
C11 Stub payments gated on BuildConfig.DEBUG; release → "Payment is not configured".
C12 DeliveryQuoteResponse.delivery_unavailable → message + disable Pay for delivery; map 503 from /payments/intent.
C13 Checkout add-address: notes → order delivery instructions (if CreateOrderRequest has it) else remove; call setDefaultAddress after add when checked; apt in own field.
C14 409 from POST /orders: parse `error` body and show it.
C15 OrderTrackingScreen phase copy by fulfillment type (pickup: "ready for pickup"; delivery: "Finding your driver"/"Uber driver"); no "courier will claim".
C16 READY label by fulfillment type in OrdersScreen + OrderDetailScreen.
C17 ChatScreen "Send a note to the restaurant."; NotificationPreferences copy.
C18 One schedule picker: cart Schedule control opens checkout/SchedulePickerSheet; delete cart dialog; "ASAP 30-45 min" → restaurant est range or "ASAP".
C19 cuisineTypes → List<String> for display; enum only for chip filter (case-insensitive match).
C20 HomeScreen LaunchedEffect(selectedAddress) → viewModel.setLocation.
C21 createdAt.take(10) → parse RFC3339, local zone, localized format.
C22 external_tracking_url: https only.
C23 proguard -assumenosideeffects Log d/v/i/w; strip payload values from remaining Log calls.
C24 PushBootstrap: gate on FirebaseApp.getApps(context) not BuildConfig keys.
C25 Manifest: FCM default_notification_channel_id/icon/color meta-data; dataExtractionRules xml + attribute.
C26 Models.User avatar_url (alternate profile_image_url).
C27 Cleanup: unused ApiService methods/models/composables; unused strings (3 locales); kotlinx-serialization plugin/dep/proguard + lifecycle-process; git rm --cached playstore-assets/testers.csv + .gitignore.
C28 HomeViewModel loadMore honest once B1 lands: hasMore = size == pageSize.

### Seller app (android/seller)
S1 CRITICAL NavGraph agreement gate only when hasRestaurants == true; refresh agreement status after first restaurant created; accept carries restaurant_id.
S2 LegalUrls: privacy/terms → koshereats.shop; partner terms from SellerAgreement.termsUrl; remove koshereats.com.
S3 Forgot password real flow (same endpoints as consumer); remove koshereats.dev.
S4 Orders: cursor pagination (oldest createdAt), status param sent from chips, hasMore = size == limit, page size 50.
S5 Remove vestigial deliveryMode state in RestaurantSettingsScreen; 'platform' legacy displays as external, never written back implicitly; parse {"error"} body in AuthViewModel.updateRestaurantFields/setRestaurantDeliveryMode.
S6 Order detail: "Track with Uber" button (CustomTabs) when externalTrackingUrl https; ExternalCourierLocation model + "last seen".
S7 Delete account via ApiService @DELETE("user/account"); show backend error.
S8 Remove presigned uploadUrl/publicUrl Log.d (OnboardingScreen ~1804, ~1870); remove givenName from GoogleSignInHelper log.
S9 MerchantAgreementScreen fee/tax sentences from PartnerTermsCopy.
S10 Menu-import failure copy → "Add items from the Menu tab, or email partners@koshereats.shop and we'll re-run the import."
S11 RestaurantPickerViewModel: honour persisted id only if in list; clear on 404.
S12 Dashboard active list excludes SCHEDULED.
S13 Cleanup: delete CreateRestaurantScreen/VM/route; unused strings (3 locales); phantom MenuItem fields (category, preparation_time, allergens, spice_level, calories, is_available in create) + any UI inputs for them; dead ApiService methods (createMenuItem dup, deleteCategory, getMenuImport) + formatPriceWhole/dollarsToCents/vehicleSummary/StatusCompleted; unused deps core-splashscreen, ui-tooling-preview; add app/src/debug/res/xml/network_security_config.xml allowing cleartext for 10.0.2.2 only; proguard -assumenosideeffects Log.

## Verification round (decided 2026-10-08, "middle path")
After all fixes compile: run exactly TWO review agents (not more) over the final diff of the branch — one adversarial correctness reviewer, one security/stale-content reviewer — and fix what they confirm. No further fan-out.

## After fixes
1. backend: go build/vet/test + openapi lint. consumer+seller: ./gradlew bundleRelease (seller KEYSTORE_PASSWORD is in local.properties, correct now).
2. Commit per area on the branch, merge into main (ff), push, `fly deploy` backend (from backend/), rebuild AABs → ~/Desktop/KosherEats-customer-1.2.0.aab (15 MB, Salto uploads) and KosherEats-restaurant-1.1.0.aab (I upload via Chrome). Bump versionCode again (consumer 15, seller 14) since bundles 14/13 were already uploaded to Play drafts.
3. Then Play Console → Publishing overview → Send changes for review (Salto said we proceed to production after fixes).

## Progress (2026-10-08 ~21:20)
Done and committed on fix/android-prod-readiness (not yet merged/pushed):
- a0a75378 backend B1–B5 (go test ./... green, openapi lint valid)
- a93046d6 consumer C1–C28 (compileDebugKotlin + bundleRelease green)
- 26c4f944 seller S1–S13 (compileDebugKotlin + bundleRelease green)
- 42390a69 versionCode bumps (consumer 15, seller 14)
Verification round: two review agents (adversarial correctness; security+stale) launched over `git diff main..HEAD`. Next: fix confirmed findings, re-run backend tests + both bundleRelease, commit, merge ff into main, push, `fly deploy` from backend/, copy AABs to ~/Desktop (KosherEats-customer-1.2.0.aab, KosherEats-restaurant-1.1.0.aab), upload seller via Chrome, Salto uploads consumer, then Play Console → Send changes for review.
