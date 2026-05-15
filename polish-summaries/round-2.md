# KosherEats Polish — Round 2
**Max severity found:** 7
**Issues found:** 12
**Fixes attempted:** 12
**Fixes succeeded:** 6

## Issues & Fixes
- **[7/10] [android_consumer] SchedulePickerSheet reads DatePicker millis as local-time → wrong day in Americas** — FAILED
  SchedulePickerSheet.kt:144-149 builds the scheduled LocalDateTime via `Instant.ofEpochMilli(chosenMillis).atZone(ZoneId.systemDefault()).toLocalDate()
  > not reported by batch agent

- **[7/10] [android_seller] Order action success is never surfaced to the user** — FIXED
  OrdersViewModel.updateOrderStatus (line 230) and doOrderApiCall (line 296) set state.updateSuccess with user-facing text like "Order accepted", "Order
  > Added LaunchedEffect(state.updateSuccess) toast in SellerOrderDetailScreen, and LaunchedEffect(state.error) + LaunchedEf

- **[7/10] [android_seller] pollSilently overwrites cleared selectedOrder after back-navigation** — FIXED
  OrdersViewModel.pollSilently (lines 92-99) captures selectedOrder.id before the getOrderDetail call but does not re-validate before writing the respon
  > Changed the _state.update in pollSilently to guard with 'if (current.selectedOrder?.id == id)' so an in-flight detail re

- **[7/10] [android_seller] pollSilently can repopulate selectedOrder after the detail screen disposes** — FIXED
  In OrdersViewModel.pollSilently (lines 90-97), the in-flight `apiService.getOrderDetail(id)` doesn't re-check that the detail screen is still mounted.
  > Same fix as Issue 2 — the id-equality guard in pollSilently's _state.update block prevents the in-flight response from r

- **[7/10] [android_seller] Deal expiry mixes UTC calendar date with system-zone time-of-day → wrong-day expiries** — FIXED
  CreateDealScreen.kt:118-124 stores `expiresAtMillis` as today's *UTC* midnight; the DatePicker's `selectableDates` lower bound is also UTC (line 511).
  > Changed both 'today' computations (initial expiresAtMillis and todayMillis for selectableDates lower bound) to derive th

- **[6/10] [android_consumer] Auth-gated composables pop the user during rehydration on process death** — FAILED
  NavGraph.kt:316-319, 344-347, and 385-388 (OrderConfirmation, OrderTracking, Chat) guard with `if (authState.sessionState != SessionState.Authenticate
  > not reported by batch agent

- **[6/10] [android_seller] createMenuItem leaks orphan empty categories on failure and swallows createCategory errors** — FIXED
  MenuViewModel.createMenuItem (lines 147-160) calls apiService.createCategory(...) but never checks catResp.isSuccessful — it only reads catResp.body()
  > Added catResp.isSuccessful check that surfaces the HTTP code; added createdCategoryId tracking so a newly created catego

- **[6/10] [android_seller] toggleAvailability rollback can leave UI desynced under concurrent updates** — FIXED
  MenuViewModel.toggleAvailability rollback (lines 268-272 / 277-282) only reverts an item if `it.isAvailable == newAvailability`. If a quick second tap
  > Replaced the conditional rollback in both the error and exception paths with a loadMenuItems() call that reloads server 

- **[5/10] [android_consumer] register() collapses every server error to 'Registration failed'** — FAILED
  AuthViewModel.kt:228-233 sets a single hard-coded `error = "Registration failed"` for any non-2xx response from `/auth/register`. The login() path was
  > not reported by batch agent

- **[5/10] [android_consumer] Multi-cert filter UI lies: only first certification is sent** — FAILED
  HomeScreen.kt:178-179 builds the filter badge as `+ uiState.filterCertifications.size`, implying multi-select. KosherFilterSheet returns a Set<KosherC
  > not reported by batch agent

- **[5/10] [android_consumer] Pagination dies permanently after any error** — FAILED
  HomeViewModel.loadRestaurants sets `hasMore = false` on Resource.Error (line 103). Any transient 5xx/timeout permanently disables 'load more' until th
  > not reported by batch agent

- **[5/10] [android_consumer] FCM PendingIntent shared across notifications** — FAILED
  KosherEatsMessagingService.showNotification uses requestCode=0 and FLAG_UPDATE_CURRENT on every PendingIntent.getActivity call. The system keys Pendin
  > not reported by batch agent
