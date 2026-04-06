package com.koshereats.seller.data.api

import com.koshereats.seller.data.models.*
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class SocialLoginRequest(
    val provider: String,
    val token: String,
    val firstName: String,
    val lastName: String,
)

interface ApiService {

    // --- Auth ---

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    // --- Dashboard ---

    @GET("seller/dashboard/stats")
    suspend fun getDashboardStats(): Response<DashboardStats>

    @GET("seller/dashboard/active-orders")
    suspend fun getActiveOrders(): Response<List<Order>>

    // --- Orders ---

    @GET("seller/orders")
    suspend fun getOrders(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): Response<List<Order>>

    @GET("seller/orders/{orderId}")
    suspend fun getOrderDetail(
        @Path("orderId") orderId: String,
    ): Response<Order>

    @PUT("seller/orders/{orderId}/status")
    suspend fun updateOrderStatus(
        @Path("orderId") orderId: String,
        @Body request: UpdateOrderStatusRequest,
    ): Response<Order>

    // --- Menu ---

    @GET("seller/menu")
    suspend fun getMenuItems(
        @Query("category") category: String? = null,
    ): Response<List<MenuItem>>

    @GET("seller/menu/{itemId}")
    suspend fun getMenuItem(
        @Path("itemId") itemId: String,
    ): Response<MenuItem>

    @POST("seller/menu")
    suspend fun createMenuItem(
        @Body item: UpdateMenuItemRequest,
    ): Response<MenuItem>

    @PUT("seller/menu/{itemId}")
    suspend fun updateMenuItem(
        @Path("itemId") itemId: String,
        @Body item: UpdateMenuItemRequest,
    ): Response<MenuItem>

    @DELETE("seller/menu/{itemId}")
    suspend fun deleteMenuItem(
        @Path("itemId") itemId: String,
    ): Response<Unit>

    @PUT("seller/menu/{itemId}/availability")
    suspend fun toggleMenuItemAvailability(
        @Path("itemId") itemId: String,
        @Body body: Map<String, Boolean>,
    ): Response<MenuItem>

    // --- Restaurant Settings ---

    /**
     * Returns every restaurant this seller owns. Drives the picker sheet.
     * This path intentionally bypasses the `sellerRestaurantInterceptor`
     * (which matches it by exact path) so it returns the full set regardless
     * of which one is currently "active".
     */
    @GET("seller/restaurants")
    suspend fun listRestaurants(): Response<List<Restaurant>>

    @GET("seller/restaurant")
    suspend fun getRestaurant(): Response<Restaurant>

    @PUT("seller/restaurant")
    suspend fun updateRestaurant(
        @Body restaurant: Map<String, @JvmSuppressWildcards Any>,
    ): Response<Restaurant>

    @PUT("seller/restaurant/toggle-status")
    suspend fun toggleRestaurantStatus(): Response<Restaurant>

    // --- Devices (push notifications) ---

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>
}
