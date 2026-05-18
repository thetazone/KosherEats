# KosherEats Polish — Round 12
**Max severity found:** 9
**Issues found:** 10
**Fixes attempted:** 10
**Fixes succeeded:** 0

## Issues & Fixes
- **[9/10] [android_consumer] clearTokens() does not clear DataStore — logout silently reverts** — FAILED
  TokenProvider.clearTokens() (RetrofitClient.kt:100-103) only nulls the in-memory @Volatile fields and never edits DataStore. Critically, the init bloc
  > not reported by batch agent

- **[8/10] [android_seller] OrderStatusBadge will crash on UNKNOWN status, defeating the UnknownFallbackEnumAdapterFactory safety net** — FAILED
  Models.kt:14-25 declares OrderStatus with an UNKNOWN sentinel, and UnknownFallbackEnumAdapterFactory (Models.kt:63-88) is explicitly built to map any 
  > not reported by batch agent

- **[7/10] [android_consumer] OrderTrackingViewModel — SSE-401 counter persists across order sessions, causing mass-logout on token rotation** — FAILED
  OrderTrackingViewModel.kt:50,129-138 — `@Volatile var consecutiveSseUnauthorized = 0` is a VM-scoped field never reset on `start()`. Across start→stop
  > not reported by batch agent

- **[7/10] [android_consumer] OrderTrackingScreen — nested verticalScroll Column lacks weight(1f), content clipped on small screens** — FAILED
  OrderTrackingScreen.kt:138-180 — outer Column children: TopAppBar + TrackingMap(340.dp) + StatusHeader + optional error Row + inner Column.verticalScr
  > not reported by batch agent

- **[7/10] [android_consumer] First request after cold start races token rehydration and never retries with a token** — FAILED
  RetrofitClient.kt:129-135 — the auth interceptor intentionally doesn't block on the DataStore-backed token cache, so a request can fire with no Author
  > not reported by batch agent

- **[7/10] [android_seller] Newly-arrived orders are appended to the BOTTOM of the orders list on every poll/refresh instead of the top** — FAILED
  OrdersViewModel.pollSilently (OrdersViewModel.kt:167-187) and OrdersViewModel.refresh (OrdersViewModel.kt:466-480) both do `val finalList = merged + t
  > not reported by batch agent

- **[6/10] [android_consumer] Menu items rendered with .forEach inside a single LazyColumn item — defeats laziness, no stable keys** — FAILED
  In RestaurantDetailScreen.kt:371-400, the selected category's menu items are placed inside a single `item { … Column { selectedCategory.items.forEach 
  > not reported by batch agent

- **[6/10] [android_seller] Image upload client uses default 10s OkHttp timeouts and fails silently** — FAILED
  MenuItemFormScreen defines `private val uploadClient = OkHttpClient()` at file scope with no timeouts overridden, so all three of connect/read/write d
  > not reported by batch agent

- **[5/10] [android_seller] isUploadingImage uses `remember` (not rememberSaveable) so rotation re-enables the Save button mid-upload** — FAILED
  `var isUploadingImage by remember { mutableStateOf(false) }` is reset on configuration change. Rotation while a photo is uploading clears the spinner,
  > not reported by batch agent

- **[5/10] [android_seller] OrderEventBus replay=1 triggers spurious polls on every collector re-attach** — FAILED
  OrderEventBus is a MutableSharedFlow with `replay = 1`. DashboardViewModel.launchPollingJob and OrdersViewModel.launchPollingJob each `launch { orderE
  > not reported by batch agent
