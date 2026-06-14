package com.koshereats.seller.data.api

import com.koshereats.seller.data.models.*
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class SocialLoginRequest(
    val provider: String,
    val token: String,
    @Json(name = "first_name") val firstName: String,
    @Json(name = "last_name") val lastName: String,
    val role: String = "seller",
)

interface ApiService {

    // --- Auth ---

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/social")
    suspend fun socialLogin(@Body request: SocialLoginRequest): Response<LoginResponse>

    @POST("auth/phone/start")
    suspend fun phoneStart(@Body request: PhoneStartRequest): Response<PhoneStartResponse>

    @POST("auth/phone/verify")
    suspend fun phoneVerify(@Body request: PhoneVerifyRequest): Response<LoginResponse>

    @POST("auth/reviewer/seller")
    suspend fun reviewerLogin(@Header("X-Reviewer-Secret") secret: String): Response<LoginResponse>

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
        @Body body: Map<String, String?>,
    ): Response<Order>

    // NOTE: There is intentionally no seller `cancel` endpoint. The backend only
    // exposes /cancel under the consumer order group (CancelOrder), not under
    // /seller/orders. Sellers terminate an order via /reject (PENDING only). The
    // Cancel-in-progress affordance was removed from the UI because it 404'd.

    @PATCH("seller/orders/{orderId}/pickup")
    suspend fun sellerPickupOrder(
        @Path("orderId") orderId: String,
    ): Response<Unit>

    @PATCH("seller/orders/{orderId}/deliver")
    suspend fun sellerDeliverOrder(
        @Path("orderId") orderId: String,
    ): Response<Unit>

    // --- Menu ---

    @GET("seller/menu")
    suspend fun getSellerMenu(): Response<List<SellerMenuCategory>>

    @POST("seller/menu/categories")
    suspend fun createCategory(
        @Body body: Map<String, String>,
    ): Response<SellerMenuCategory>

    @DELETE("seller/menu/categories/{id}")
    suspend fun deleteCategory(@Path("id") categoryId: String): Response<Unit>

    @POST("seller/menu/items")
    suspend fun createMenuItem(
        @Body item: UpdateMenuItemRequest,
    ): Response<MenuItem>

    @POST("seller/menu/items")
    suspend fun createMenuItemWithCategory(
        @Body item: CreateMenuItemBody,
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

    @POST("seller/menu/items/{itemId}/modifier-groups")
    suspend fun createModifierGroup(
        @Path("itemId") itemId: String,
        @Body request: CreateModifierGroupRequest,
    ): Response<ModifierGroup>

    @PUT("seller/menu/modifier-groups/{groupId}")
    suspend fun updateModifierGroup(
        @Path("groupId") groupId: String,
        @Body request: CreateModifierGroupRequest,
    ): Response<ModifierGroup>

    @DELETE("seller/menu/modifier-groups/{groupId}")
    suspend fun deleteModifierGroup(
        @Path("groupId") groupId: String,
    ): Response<Unit>

    // --- Restaurant Settings ---

    /**
     * Returns every restaurant this seller owns. Drives the picker sheet.
     * This path intentionally bypasses the `sellerRestaurantInterceptor`
     * (which matches it by exact path) so it returns the full set regardless
     * of which one is currently "active".
     */
    @GET("seller/restaurants")
    suspend fun listRestaurants(): Response<List<Restaurant>>

    @POST("seller/restaurants")
    suspend fun createRestaurant(
        @Body request: CreateRestaurantRequest,
    ): Response<Restaurant>

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

    // --- Deals ---

    @GET("seller/deals")
    suspend fun getDeals(): Response<List<Deal>>

    @POST("seller/deals")
    suspend fun createDeal(@Body request: CreateDealRequest): Response<Deal>

    @DELETE("seller/deals/{dealId}")
    suspend fun deactivateDeal(@Path("dealId") dealId: String): Response<Map<String, String>>

    // --- Uploads ---

    @POST("uploads/presign")
    suspend fun presignUpload(@Body body: Map<String, String>): Response<PresignResponse>

    // --- Devices (push notifications) ---

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>

    @DELETE("devices/{token}")
    suspend fun unregisterDevice(@Path("token") token: String): Response<Unit>

    // --- POS Integrations ---

    @GET("seller/integrations")
    suspend fun listIntegrations(): Response<List<POSIntegration>>

    @GET("seller/integrations/clover/connect-url")
    suspend fun cloverConnectURL(): Response<CloverConnectURLResponse>

    @POST("seller/integrations/{id}/test")
    suspend fun testIntegration(@Path("id") id: String): Response<Map<String, String>>

    @DELETE("seller/integrations/{id}")
    suspend fun disconnectIntegration(@Path("id") id: String): Response<Map<String, String>>
}
