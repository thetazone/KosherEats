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

    // --- Dashboard ---

    @GET("seller/dashboard/stats")
    suspend fun getDashboardStats(): Response<DashboardStats>

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

    @PATCH("seller/orders/{orderId}/accept")
    suspend fun acceptOrder(
        @Path("orderId") orderId: String,
    ): Response<Order>

    @PATCH("seller/orders/{orderId}/preparing")
    suspend fun markOrderPreparing(
        @Path("orderId") orderId: String,
    ): Response<Order>

    @PATCH("seller/orders/{orderId}/ready")
    suspend fun markOrderReady(
        @Path("orderId") orderId: String,
    ): Response<Order>

    @PATCH("seller/orders/{orderId}/complete")
    suspend fun completeOrder(
        @Path("orderId") orderId: String,
    ): Response<Order>

    @PATCH("seller/orders/{orderId}/reject")
    suspend fun rejectOrder(
        @Path("orderId") orderId: String,
    ): Response<Order>

    // --- Menu ---

    @GET("seller/menu")
    suspend fun getSellerMenu(): Response<List<SellerMenuCategory>>

    @POST("seller/menu/items")
    suspend fun createMenuItem(
        @Body item: UpdateMenuItemRequest,
    ): Response<MenuItem>

    @PUT("seller/menu/items/{itemId}")
    suspend fun updateMenuItem(
        @Path("itemId") itemId: String,
        @Body item: UpdateMenuItemRequest,
    ): Response<MenuItem>

    @DELETE("seller/menu/items/{itemId}")
    suspend fun deleteMenuItem(
        @Path("itemId") itemId: String,
    ): Response<Unit>

    @PATCH("seller/menu/items/{itemId}/availability")
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

    @PATCH("seller/restaurant/status")
    suspend fun updateRestaurantStatus(
        @Body body: Map<String, Boolean>,
    ): Response<Restaurant>

    // --- Devices (push notifications) ---

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>
}
