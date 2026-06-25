package com.koshereats.consumer.data.api

import com.koshereats.consumer.data.models.*
import retrofit2.Response
import retrofit2.http.*

data class RefreshRequest(
    @com.google.gson.annotations.SerializedName("refresh_token") val refreshToken: String,
)

object ApiPaging {
    const val RESTAURANTS_PAGE_SIZE = 20
    const val ORDERS_PAGE_SIZE = 20
}

interface ApiService {

    // ── Auth ──────────────────────────────────────────────

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<AuthResponse>

    @POST("auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<AuthResponse>

    @POST("auth/phone/start")
    suspend fun phoneStart(@Body request: PhoneStartRequest): Response<PhoneStartResponse>

    @POST("auth/phone/verify")
    suspend fun phoneVerify(@Body request: PhoneVerifyRequest): Response<AuthResponse>

    @POST("auth/email/check")
    suspend fun checkEmail(@Body request: EmailCheckRequest): Response<EmailCheckResponse>

    // ── User ──────────────────────────────────────────────

    @GET("user/profile")
    suspend fun getProfile(): Response<User>

    @PUT("user/profile")
    suspend fun updateProfile(@Body user: User): Response<User>

    @PUT("user/profile")
    suspend fun updateProfileFields(@Body body: Map<String, String>): Response<User>

    @GET("user/addresses")
    suspend fun getAddresses(): Response<List<Address>>

    @POST("user/addresses")
    suspend fun addAddress(@Body address: Address): Response<Address>

    @DELETE("user/addresses/{id}")
    suspend fun deleteAddress(@Path("id") addressId: String): Response<Unit>

    @PATCH("user/addresses/{id}/default")
    suspend fun setDefaultAddress(@Path("id") addressId: String): Response<Unit>

    @DELETE("user/addresses/{id}/default")
    suspend fun clearDefaultAddress(@Path("id") addressId: String): Response<Unit>

    @DELETE("user/account")
    suspend fun deleteAccount(): Response<Unit>

    @GET("user/notification-preferences")
    suspend fun getNotificationPreferences(): Response<NotificationPreferences>

    @PUT("user/notification-preferences")
    suspend fun updateNotificationPreferences(@Body prefs: NotificationPreferences): Response<NotificationPreferences>

    @GET("user/linked-providers")
    suspend fun getLinkedProviders(): Response<List<LinkedProvider>>

    @POST("user/linked-providers")
    suspend fun linkProvider(@Body request: LinkProviderRequest): Response<Unit>

    @DELETE("user/linked-providers/{provider}")
    suspend fun unlinkProvider(@Path("provider") provider: String): Response<Unit>

    // ── Restaurants ───────────────────────────────────────

    @GET("restaurants")
    suspend fun getRestaurants(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = ApiPaging.RESTAURANTS_PAGE_SIZE,
        @Query("lat") latitude: Double? = null,
        @Query("lng") longitude: Double? = null,
        @Query("cuisine") cuisine: String? = null,
        @Query("dietary_type") dietaryType: String? = null,
        @Query("kosher_certification") kosherCertification: String? = null,
        @Query("is_cholov_yisroel") isCholovYisroel: Boolean? = null,
        @Query("is_pas_yisroel") isPasYisroel: Boolean? = null,
        @Query("is_glatt_kosher") isGlattKosher: Boolean? = null,
        @Query("sort_by") sortBy: String? = null,
    ): Response<List<Restaurant>>

    @GET("restaurants/search")
    suspend fun searchRestaurants(
        @Query("q") query: String,
        @Query("lat") latitude: Double? = null,
        @Query("lng") longitude: Double? = null,
    ): Response<List<Restaurant>>

    @GET("restaurants/suggested")
    suspend fun getSuggestedRestaurants(
        @Query("limit") limit: Int = 10,
    ): Response<List<Restaurant>>

    @GET("restaurants/{id}")
    suspend fun getRestaurant(@Path("id") restaurantId: String): Response<Restaurant>

    @GET("restaurants/{id}/menu")
    suspend fun getRestaurantMenu(@Path("id") restaurantId: String): Response<List<MenuCategory>>

    // ── Cart (server-backed, used during checkout sync) ──

    @GET("cart")
    suspend fun getCart(): Response<ServerCart>

    @POST("cart/items")
    suspend fun addToCart(@Body request: AddToCartRequest): Response<ServerCart>

    @PATCH("cart/items/{id}")
    suspend fun updateCartItem(
        @Path("id") itemId: String,
        @Body request: UpdateCartItemRequest,
    ): Response<ServerCart>

    @DELETE("cart/items/{id}")
    suspend fun removeCartItem(@Path("id") itemId: String): Response<ServerCart>

    @DELETE("cart")
    suspend fun clearServerCart(): Response<Unit>

    // ── Favorites ─────────────────────────────────────────

    @GET("favorites")
    suspend fun getFavorites(): Response<List<Restaurant>>

    @GET("favorites/ids")
    suspend fun getFavoriteIds(): Response<List<String>>

    @POST("favorites/{restaurant_id}")
    suspend fun addFavorite(@Path("restaurant_id") restaurantId: String): Response<Map<String, String>>

    @DELETE("favorites/{restaurant_id}")
    suspend fun removeFavorite(@Path("restaurant_id") restaurantId: String): Response<Map<String, String>>

    // ── Payments ──────────────────────────────────────────

    @POST("payments/intent")
    suspend fun createPaymentSheet(@Body request: PaymentSheetRequest): Response<PaymentSheetBundle>

    @GET("payments/customer")
    suspend fun getPaymentCustomer(): Response<CustomerBundle>

    @POST("payments/setup-intent")
    suspend fun createSetupIntent(@Body body: Map<String, String> = emptyMap()): Response<SetupIntentResponse>

    // ── Delivery quote ────────────────────────────────────

    @POST("delivery-quote/")
    suspend fun getDeliveryQuote(@Body request: DeliveryQuoteRequest): Response<DeliveryQuoteResponse>

    // ── Orders ────────────────────────────────────────────

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderRequest): Response<Order>

    @GET("orders")
    suspend fun getOrders(
        // Backend paginates by RFC3339Nano `cursor` (the created_at of the last
        // order already held) + `limit`; it ignores page/per_page. null cursor
        // loads the first page.
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = ApiPaging.ORDERS_PAGE_SIZE,
        @Query("status") status: String? = null,
    ): Response<List<Order>>

    @GET("orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): Response<Order>

    /**
     * Recover the order created for a given Stripe PaymentIntent. Idempotent
     * fallback when [createOrder] fails to return after a confirmed payment
     * (e.g. network drop): the client retries with the PaymentIntent id it just
     * confirmed. Backend scopes the lookup to the calling user and returns the
     * full Order (with order_items) or 404 if none exists yet.
     */
    @GET("orders/by-payment-intent/{pi}")
    suspend fun getOrderByPaymentIntent(@Path("pi") paymentIntentId: String): Response<Order>

    @PATCH("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): Response<Order>

    @POST("orders/{id}/rating")
    suspend fun rateOrder(
        @Path("id") orderId: String,
        @Body request: RateOrderRequest,
    ): Response<Unit>

    // ── Order chat ──────────────────────────────────────────

    @GET("orders/{id}/chat")
    suspend fun listChatMessages(@Path("id") orderId: String): Response<List<ChatMessage>>

    @POST("orders/{id}/chat")
    suspend fun sendChatMessage(
        @Path("id") orderId: String,
        @Body body: SendChatMessageRequest,
    ): Response<ChatMessage>

    // ── Deals ────────────────────────────────────────────

    @GET("deals/nearby")
    suspend fun getNearbyDeals(): Response<List<Deal>>

    @GET("restaurants/{id}/deals")
    suspend fun getRestaurantDeals(@Path("id") restaurantId: String): Response<List<Deal>>

    // ── Devices (push notifications) ─────────────────────

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>
}
