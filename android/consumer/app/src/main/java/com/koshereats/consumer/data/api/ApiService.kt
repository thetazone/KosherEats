package com.koshereats.consumer.data.api

import com.koshereats.consumer.data.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Header("Authorization") refreshToken: String): Response<AuthResponse>

    @POST("auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<AuthResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    // ── User ──────────────────────────────────────────────

    @GET("user/profile")
    suspend fun getProfile(): Response<User>

    @PUT("user/profile")
    suspend fun updateProfile(@Body user: User): Response<User>

    @GET("user/addresses")
    suspend fun getAddresses(): Response<List<Address>>

    @POST("user/addresses")
    suspend fun addAddress(@Body address: Address): Response<Address>

    @DELETE("user/addresses/{id}")
    suspend fun deleteAddress(@Path("id") addressId: String): Response<Unit>

    // ── Restaurants ───────────────────────────────────────

    @GET("restaurants")
    suspend fun getRestaurants(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 20,
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
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
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
    ): Response<List<Restaurant>>

    @GET("restaurants/featured")
    suspend fun getFeaturedRestaurants(
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
    ): Response<List<Restaurant>>

    @GET("restaurants/nearby")
    suspend fun getNearbyRestaurants(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("radius") radiusMiles: Double = 5.0,
    ): Response<List<Restaurant>>

    @GET("restaurants/{id}")
    suspend fun getRestaurant(@Path("id") restaurantId: String): Response<Restaurant>

    @GET("restaurants/{id}/menu")
    suspend fun getRestaurantMenu(@Path("id") restaurantId: String): Response<List<MenuCategory>>

    @GET("restaurants/{id}/reviews")
    suspend fun getRestaurantReviews(
        @Path("id") restaurantId: String,
        @Query("page") page: Int = 1,
    ): Response<PaginatedResponse<Review>>

    // ── Cart (server-backed, used during checkout sync) ──

    @GET("cart")
    suspend fun getCart(): Response<ServerCart>

    @POST("cart/items")
    suspend fun addToCart(@Body request: AddToCartRequest): Response<ServerCart>

    @DELETE("cart")
    suspend fun clearServerCart(): Response<Unit>

    // ── Payments ──────────────────────────────────────────

    @POST("payments/intent")
    suspend fun createPaymentSheet(@Body request: PaymentSheetRequest): Response<PaymentSheetBundle>

    // ── Orders ────────────────────────────────────────────

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderRequest): Response<Order>

    @GET("orders")
    suspend fun getOrders(
        @Query("page") page: Int = 1,
        @Query("status") status: String? = null,
    ): Response<List<Order>>

    @GET("orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): Response<Order>

    @POST("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): Response<Order>

    @POST("orders/{id}/reorder")
    suspend fun reorder(@Path("id") orderId: String): Response<Order>

    // ── Order chat ──────────────────────────────────────────

    @GET("orders/{id}/chat")
    suspend fun listChatMessages(@Path("id") orderId: String): Response<List<ChatMessage>>

    @POST("orders/{id}/chat")
    suspend fun sendChatMessage(
        @Path("id") orderId: String,
        @Body body: SendChatMessageRequest,
    ): Response<ChatMessage>

    // ── Devices (push notifications) ─────────────────────

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>
}
