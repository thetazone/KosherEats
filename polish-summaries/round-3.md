# KosherEats Polish — Round 3
**Max severity found:** 10
**Issues found:** 12
**Fixes attempted:** 7
**Fixes succeeded:** 0

## Issues & Fixes
- **[10/10] [android_seller] MenuViewModel references SellerMenuCategory without importing it** — FAILED
  MenuViewModel.kt line 25 declares `val categories: List<SellerMenuCategory> = emptyList()` and reuses the type at lines 69, 131-140, 163, but the impo
  > not reported by batch agent

- **[7/10] [android_seller] Restaurant switch races NetworkModule.cachedRestaurantId, dashboard reload sees stale id** — FAILED
  RestaurantPickerViewModel.select() (RestaurantPickerViewModel.kt:71-81) writes the new id to DataStore, awaits its OWN flow collection, then fires onD
  > not reported by batch agent

- **[6/10] [android_seller] Dashboard fetches 'active' orders client-side from a magic backend filter** — FAILED
  DashboardViewModel calls `apiService.getOrders(status = "active", limit = 200)` (lines 69, 100, 123) and *also* re-filters with `it.status.isActive` (
  > not reported by batch agent

- **[6/10] [android_seller] OrderStatus.SCHEDULED has no action UI and no explanation** — FAILED
  OrderActionButtons (lines 434-565) handles PENDING / ACCEPTED / PREPARING / READY / PICKED_UP and `else -> {}`. SCHEDULED falls through to the empty b
  > not reported by batch agent

- **[6/10] [android_seller] Image upload has no compression, no progress, and no size guard** — FAILED
  Both `uploadImage` (MenuItemFormScreen.kt:530-563) and `uploadDealImage` (CreateDealScreen.kt:711-744) PUT the raw user-picked file. A modern phone ph
  > not reported by batch agent

- **[6/10] [android_seller] Foregrounding the app cancels every notification including unread new-order alerts** — FAILED
  KosherEatsSellerApp.kt:24-28 wires `cancelAll()` on every ProcessLifecycleOwner.onStart. The intent (matching iOS badge clearing) is fine for stale ba
  > not reported by batch agent

- **[5/10] [android_consumer] First request races TokenProvider DataStore load** — FAILED
  RetrofitClient.provideAuthInterceptor reads `tokenProvider.token` synchronously. TokenProvider's init kicks off a coroutine on Dispatchers.IO to read 
  > not reported by batch agent

- **[4/10] [android_consumer] Expired deals show negative minutes left** — skipped
  RestaurantDetailScreen.RestaurantDealCard line 664-672 computes hours = HOURS.between(now, expiry). If expiry < now, hours is negative; the first bran

- **[4/10] [android_consumer] SSE retry toast flashes on screen-exit** — skipped
  OrderTrackingViewModel.launchLocationStream catches Exception including kotlin.coroutines.cancellation.CancellationException, then sets `errorMessage 

- **[4/10] [android_consumer] Register error path collapses every code to one string** — skipped
  AuthViewModel.register (line 224-231) on !isSuccessful just shows 'Registration failed'. Login uses a real when-block branching on 401/403/422/429/5xx

- **[4/10] [android_consumer] Delete-account failure is silent** — skipped
  AuthViewModel.deleteAccount sets uiState.error on failure, but ProfileScreen never renders state.error anywhere (only the alert dialogs and the static

- **[4/10] [android_consumer] Active cart selection is non-deterministic when no active id** — skipped
  CartUiState.cart falls back to `activeRestaurantId?.let { carts[it] } ?: carts.values.firstOrNull() ?: Cart()` (line 52). After clearCartForRestaurant
