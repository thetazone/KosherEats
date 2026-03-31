package com.koshereats.consumer.data.api

import com.koshereats.consumer.data.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<AuthResponse>>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<ApiResponse<AuthResponse>>

    @POST("auth/refresh")
    suspend fun refreshToken(@Header("Authorization") refreshToken: String): Response<ApiResponse<AuthResponse>>

    @POST("auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<ApiResponse<AuthResponse>>

    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>

    // ── User ──────────────────────────────────────────────

    @GET("user/profile")
    suspend fun getProfile(): Response<ApiResponse<User>>

    @PUT("user/profile")
    suspend fun updateProfile(@Body user: User): Response<ApiResponse<User>>

    @GET("user/addresses")
    suspend fun getAddresses(): Response<ApiResponse<List<Address>>>

    @POST("user/addresses")
    suspend fun addAddress(@Body address: Address): Response<ApiResponse<Address>>

    @DELETE("user/addresses/{id}")
    suspend fun deleteAddress(@Path("id") addressId: String): Response<ApiResponse<Unit>>

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
    ): Response<ApiResponse<PaginatedResponse<Restaurant>>>

    @GET("restaurants/search")
    suspend fun searchRestaurants(
        @Query("q") query: String,
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
    ): Response<ApiResponse<List<Restaurant>>>

    @GET("restaurants/featured")
    suspend fun getFeaturedRestaurants(
        @Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null,
    ): Response<ApiResponse<List<Restaurant>>>

    @GET("restaurants/nearby")
    suspend fun getNearbyRestaurants(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("radius") radiusMiles: Double = 5.0,
    ): Response<ApiResponse<List<Restaurant>>>

    @GET("restaurants/{id}")
    suspend fun getRestaurant(@Path("id") restaurantId: String): Response<ApiResponse<Restaurant>>

    @GET("restaurants/{id}/menu")
    suspend fun getRestaurantMenu(@Path("id") restaurantId: String): Response<ApiResponse<List<MenuCategory>>>

    @GET("restaurants/{id}/reviews")
    suspend fun getRestaurantReviews(
        @Path("id") restaurantId: String,
        @Query("page") page: Int = 1,
    ): Response<ApiResponse<PaginatedResponse<Review>>>

    // ── Orders ────────────────────────────────────────────

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderRequest): Response<ApiResponse<Order>>

    @GET("orders")
    suspend fun getOrders(
        @Query("page") page: Int = 1,
        @Query("status") status: String? = null,
    ): Response<ApiResponse<PaginatedResponse<Order>>>

    @GET("orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): Response<ApiResponse<Order>>

    @POST("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): Response<ApiResponse<Order>>

    @POST("orders/{id}/reorder")
    suspend fun reorder(@Path("id") orderId: String): Response<ApiResponse<Order>>
}
