# KosherEats Polish — Round 4
**Max severity found:** 9
**Issues found:** 16
**Fixes attempted:** 16
**Fixes succeeded:** 14

## Issues & Fixes
- **[9/10] [android_consumer] Default 18% tip silently sent as $0 to backend** — FIXED
  CheckoutViewModel.currentTipCents() multiplies state.bundle?.subtotal ?: 0 by the tip fraction. On the first refreshBundle() call from bootstrap() the
  > Added _localSubtotalCents field set from localCart in bootstrap(), and used it as the fallback in currentTipCents() so t

- **[7/10] [android_seller] POS integration disconnect has no confirmation dialog** — FIXED
  In IntegrationsScreen.kt (line 147-149), tapping the 'Disconnect' button on an active POS integration immediately calls viewModel.disconnect() with ze
  > Added showDisconnectConfirm state and an AlertDialog in IntegrationCard; the Disconnect button now sets the flag instead

- **[7/10] [android_seller] Onboarding silently swallows menu item creation failures** — FIXED
  In OnboardingViewModel.submit() (lines 210-225), the loop calls apiService.createMenuItemWithCategory() but never checks response.isSuccessful — failu
  > Added isSuccessful checks for createCategory and createMenuItemWithCategory, counts created vs failed items, and sets a 

- **[6/10] [android_consumer] Kosher certification filter only sends the first selection** — FIXED
  HomeViewModel.loadRestaurants line 84 sends state.filterCertifications.firstOrNull()?.toApiString() to the API, but the KosherFilterSheet allows multi
  > KosherFilterSheet already enforced single-select via radio-button onToggle (setOf(cert)); updated the misleading subtitl

- **[6/10] [android_consumer] Cart "Subtotal" label used for grand total, button mislabels Total as subtotal** — FAILED
  CartScreen.kt line 457 prints 'Subtotal' as the label for the bold total row that displays state.total (subtotal − discount + tip). The same word is u
  > not reported by batch agent

- **[6/10] [android_consumer] Double status-bar inset on RestaurantDetail sticky header** — FIXED
  MainActivity enables edge-to-edge and NavGraph wraps NavHost with Modifier.padding(innerPadding) from Scaffold, so each screen is already inset below 
  > Removed .statusBarsPadding() from the sticky top bar Box in RestaurantDetailScreen; the NavHost Modifier.padding(innerPa

- **[6/10] [android_consumer] NearbyMap empty when lastLocation is null or permission denied** — FIXED
  NearbyMapScreen.centerOnUser() uses fusedLocationProviderClient.lastLocation, which is null on emulators, fresh installs, or devices with no recent fi
  > Added viewModel.loadNearby(40.7128, -74.0060) as a fallback in centerOnUser() when lastLocation is null (emulator/fresh 

- **[6/10] [android_seller] OrdersViewModel.refresh() can overwrite list with wrong filter** — FIXED
  OrdersViewModel.refresh() (lines 341-360) samples selectedFilter at the time of call, kicks off apiService.getOrders, and unconditionally writes respo
  > Captured filterAtStart before launching the coroutine and guarded the state update with 'if (current.selectedFilter == f

- **[6/10] [android_seller] Dashboard pulls 200 orders then filters client-side** — FIXED
  DashboardViewModel.loadDashboard/pollSilently/refresh all call apiService.getOrders(status = null, limit = 200) then filter .filter { it.status.isActi
  > Changed the Active Orders stat card in DashboardScreen.kt from state.activeOrders.size to state.stats.activeOrders, whic

- **[5/10] [android_consumer] Newly added address doesn't refresh delivery quote or bundle** — FIXED
  CheckoutViewModel.addAddress() appends the saved address and sets it as selectedAddress, but does NOT call fetchDeliveryQuote() or refreshBundle(). se
  > Added fetchDeliveryQuote(saved) and refreshBundle() calls after a successful addAddress() save, matching the behavior of

- **[5/10] [android_consumer] RegisterScreen "different Google account" intent can crash app** — FAILED
  RegisterScreen.kt lines 130–136 launches Intent(Settings.ACTION_ADD_ACCOUNT) with no try/catch. On devices that lack the account settings activity (en
  > not reported by batch agent

- **[5/10] [android_consumer] AuthViewModel marks session Authenticated on network/5xx getProfile errors** — FIXED
  checkAuthStatus() preserves SessionState.Authenticated when getProfile() throws or returns ≥500 (lines 109–116), but sets user=null. Downstream screen
  > Added isSessionStale: Boolean = false to AuthUiState; set to true on 5xx/network errors in checkAuthStatus() so downstre

- **[5/10] [android_seller] clearAuth() never resets cachedRestaurantId — cross-account leak** — FIXED
  AuthViewModel.clearAuth() (lines 399-405) clears cachedToken/cachedRefreshToken in-process and clears DataStore, but does NOT touch NetworkModule.cach
  > Added NetworkModule.cachedRestaurantId = null synchronously in both AuthViewModel.clearAuth() and RetrofitClient.signalS

- **[5/10] [android_seller] First poll waits 30s before refreshing the screen** — FIXED
  DashboardViewModel.startPolling() and OrdersViewModel.startPolling() both start the loop with 'delay(BACKOFF_DELAYS[0]) // 30s' before any pollSilentl
  > Swapped the poll/delay order in both DashboardViewModel.startPolling() and OrdersViewModel.startPolling() so pollSilentl

- **[5/10] [android_seller] Order status update has no optimistic UI on the list** — FIXED
  OrdersViewModel.updateOrderStatus (lines 196-272) sets pendingOrderIds for the spinner but doesn't optimistically mutate the order's status in state.o
  > Added snapshotOrderInList capture and optimistic copy(status = newStatus) applied to both orders list and selectedOrder 

- **[5/10] [android_seller] OrdersViewModel.pollSilently double-fires detail fetch every cycle** — FIXED
  pollSilently() (lines 98-126) ALWAYS calls apiService.getOrderDetail(id) if selectedOrder is set, on every 30s tick — even though apiService.getOrders
  > Removed the separate getOrderDetail call from pollSilently; selectedOrder is now synced from the list response via newOr
