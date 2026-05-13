package com.koshereats.courier.data.api

import com.koshereats.courier.data.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * All courier app endpoints. Unwrapped response bodies — the Go backend
 * returns data directly (no { success, data, error } envelope).
 */
interface ApiService {

    // ── Auth ─────────────────────────────────────────────────

    @POST("courier/auth/register")
    suspend fun registerCourier(@Body body: CourierRegisterRequest): Response<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): Response<AuthResponse>

    @POST("auth/email/check")
    suspend fun checkEmail(@Body body: EmailCheckRequest): Response<EmailCheckResponse>

    @POST("auth/phone/start")
    suspend fun phoneStart(@Body body: PhoneStartRequest): Response<Map<String, String>>

    @POST("auth/phone/verify")
    suspend fun phoneVerify(@Body body: PhoneVerifyRequest): Response<AuthResponse>

    @POST("auth/social")
    suspend fun socialLogin(@Body body: SocialLoginRequest): Response<AuthResponse>

    @DELETE("user/account")
    suspend fun deleteAccount(): Response<Unit>

    // ── Onboarding / profile ─────────────────────────────────

    @GET("courier/profile")
    suspend fun getProfile(): Response<CourierProfile>

    @POST("courier/onboarding/phone/verify")
    suspend fun verifyPhone(): Response<Map<String, Boolean>>

    @PUT("courier/onboarding/vehicle")
    suspend fun updateVehicle(@Body body: UpdateVehicleRequest): Response<CourierProfile>

    @PUT("courier/onboarding/documents")
    suspend fun updateDocuments(@Body body: UpdateDocumentsRequest): Response<CourierProfile>

    // ── Live state ──────────────────────────────────────────

    @POST("courier/online")
    suspend fun setOnline(@Body body: OnlineRequest): Response<Map<String, Boolean>>

    @POST("courier/location")
    suspend fun sendLocation(@Body body: LocationPing): Response<Map<String, String>>

    // ── Marketplace ─────────────────────────────────────────

    @GET("courier/deliveries/available")
    suspend fun listAvailable(): Response<AvailableDeliveriesResponse>

    @GET("courier/orders/active")
    suspend fun listActive(): Response<CourierOrdersResponse>

    @GET("courier/orders/history")
    suspend fun listHistory(): Response<HistoryResponse>

    @GET("courier/deliveries/upcoming")
    suspend fun listUpcoming(): Response<AvailableDeliveriesResponse>

    @POST("courier/orders/{id}/claim")
    suspend fun claim(@Path("id") id: String): Response<Map<String, String>>

    @POST("courier/orders/{id}/pickup")
    suspend fun pickup(@Path("id") id: String): Response<Map<String, String>>

    @POST("courier/orders/{id}/deliver")
    suspend fun deliver(@Path("id") id: String): Response<Map<String, String>>

    // ── Order chat ──────────────────────────────────────────

    @GET("orders/{id}/chat")
    suspend fun listChatMessages(@Path("id") orderId: String): Response<List<ChatMessage>>

    @POST("orders/{id}/chat")
    suspend fun sendChatMessage(
        @Path("id") orderId: String,
        @Body body: SendChatMessageRequest,
    ): Response<ChatMessage>

    // ── Payouts (Stripe Connect) ────────────────────────────

    @POST("courier/payouts/account")
    suspend fun createPayoutAccount(): Response<PayoutStatus>

    @GET("courier/payouts/link")
    suspend fun getPayoutLink(): Response<PayoutLink>

    @GET("courier/payouts/status")
    suspend fun getPayoutStatus(): Response<PayoutStatus>

    // ── Uploads ─────────────────────────────────────────────

    @POST("uploads/presign")
    suspend fun presignUpload(@Body body: PresignRequest): Response<PresignResponse>

    // ── Devices (push) ──────────────────────────────────────

    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Map<String, String>>
}
