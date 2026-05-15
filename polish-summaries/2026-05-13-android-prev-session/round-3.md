# KosherEats Polish — Round 3
**Max severity found:** 8
**Issues found:** 10
**Fixes attempted:** 2
**Fixes succeeded:** 2

## Issues & Fixes
- **[8/10] [android_seller] OrderItem.totalPrice never deserializes — always $0.00 per line item** — FIXED
  In data/models/Models.kt:104, OrderItem.totalPrice has no @Json annotation, so Moshi's codegen looks up the JSON key 'totalPrice' verbatim. The backen
  > Done. Added `@Json(name = "total_price")` to `OrderItem.totalPrice` at line 104 in `Models.kt`. The `unitPrice` field's 

- **[8/10] [android_seller] Moshi adapters outside data.models are not kept — SocialLoginRequestJsonAdapter at risk** — FIXED
  proguard-rules.pro line 4 keeps `com.koshereats.seller.data.models.**` (which covers the kapt-generated `*JsonAdapter` classes there), but `SocialLogi
  > That's the complete fix. One new rule added to proguard-rules.pro:

```
-keep class com.koshereats.seller.**JsonAdapter 

- **[7/10] [android_seller] Orders & Menu screens don't reload when the seller picks a different restaurant** — skipped
  RestaurantPickerSheet's `onChange` callback in DashboardScreen.kt:78-82 only reloads the dashboard. The bottom-nav tabs use `saveState/restoreState`, 

- **[6/10] [android_seller] provideOkHttpClient does runBlocking DataStore read on the main thread** — skipped
  RetrofitClient.kt:68 calls `runBlocking { context.dataStore.data.first() }` inside the Hilt `@Singleton` provider. The first injection point on cold s

- **[6/10] [android_seller] signalSessionExpired() doesn't clear DataStore tokens** — skipped
  TokenAuthenticator (RetrofitClient.kt:224-228) nulls `NetworkModule.cachedToken`/`cachedRefreshToken` and flips the in-memory `sessionExpired` flag, e

- **[5/10] [android_seller] formatPriceWhole() truncates revenue via integer division** — skipped
  data/models/Money.kt:11 returns `nf.format(this / 100)` where `this` is an `Int` cents amount, so `this / 100` is integer division. Today's revenue of

- **[5/10] [android_seller] Seller-side 'Mark as Completed' shown for PICKED_UP delivery orders** — skipped
  OrdersViewModel.kt:140 allows `PICKED_UP → COMPLETED` and SellerOrderDetailScreen.kt:425-441 surfaces a 'Mark as Completed' button when status == PICK

- **[5/10] [android_seller] createMenuItem fan-out: 2-3 round trips + fragile enum→category mapping** — skipped
  MenuViewModel.kt:134-162 calls getSellerMenu() to find a server-side category, optionally createCategory() if none matches, then createMenuItemWithCat

- **[4/10] [android_seller] Foreground notifications + cancelAll() race produces flicker/lost pushes** — skipped
  KosherEatsMessagingService.onMessageReceived (line 45-54) calls showNotification() unconditionally — including when the app is in the foreground, wher

- **[4/10] [android_seller] updateOrderStatus has no optimistic UI, accept feels slow on flaky networks** — skipped
  OrdersViewModel.kt:143-218 sets `pendingOrderIds += orderId` then awaits the round trip; the order's displayed status doesn't change until the server 
