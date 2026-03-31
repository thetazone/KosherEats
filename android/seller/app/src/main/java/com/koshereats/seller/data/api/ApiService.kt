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

    @POST("api/seller/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/seller/auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<LoginResponse>

    @POST("api/seller/auth/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>

    // --- Dashboard ---

    @GET("api/seller/dashboard/stats")
    suspend fun getDashboardStats(): Response<ApiResponse<DashboardStats>>

    @GET("api/seller/dashboard/active-orders")
    suspend fun getActiveOrders(): Response<ApiResponse<List<Order>>>

    // --- Orders ---

    @GET("api/seller/orders")
    suspend fun getOrders(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): Response<ApiResponse<List<Order>>>

    @GET("api/seller/orders/{orderId}")
    suspend fun getOrderDetail(
        @Path("orderId") orderId: String,
    ): Response<ApiResponse<Order>>

    @PUT("api/seller/orders/{orderId}/status")
    suspend fun updateOrderStatus(
        @Path("orderId") orderId: String,
        @Body request: UpdateOrderStatusRequest,
    ): Response<ApiResponse<Order>>

    // --- Menu ---

    @GET("api/seller/menu")
    suspend fun getMenuItems(
        @Query("category") category: String? = null,
    ): Response<ApiResponse<List<MenuItem>>>

    @GET("api/seller/menu/{itemId}")
    suspend fun getMenuItem(
        @Path("itemId") itemId: String,
    ): Response<ApiResponse<MenuItem>>

    @POST("api/seller/menu")
    suspend fun createMenuItem(
        @Body item: UpdateMenuItemRequest,
    ): Response<ApiResponse<MenuItem>>

    @PUT("api/seller/menu/{itemId}")
    suspend fun updateMenuItem(
        @Path("itemId") itemId: String,
        @Body item: UpdateMenuItemRequest,
    ): Response<ApiResponse<MenuItem>>

    @DELETE("api/seller/menu/{itemId}")
    suspend fun deleteMenuItem(
        @Path("itemId") itemId: String,
    ): Response<ApiResponse<Unit>>

    @PUT("api/seller/menu/{itemId}/availability")
    suspend fun toggleMenuItemAvailability(
        @Path("itemId") itemId: String,
        @Body body: Map<String, Boolean>,
    ): Response<ApiResponse<MenuItem>>

    // --- Restaurant Settings ---

    @GET("api/seller/restaurant")
    suspend fun getRestaurant(): Response<ApiResponse<Restaurant>>

    @PUT("api/seller/restaurant")
    suspend fun updateRestaurant(
        @Body restaurant: Map<String, @JvmSuppressWildcards Any>,
    ): Response<ApiResponse<Restaurant>>

    @PUT("api/seller/restaurant/toggle-status")
    suspend fun toggleRestaurantStatus(): Response<ApiResponse<Restaurant>>
}
