package com.koshereats.courier.data.models

import com.google.gson.annotations.SerializedName

// NOTE: The Go backend returns response bodies directly (no wrapper envelope).
// This differs from the older consumer Android code, which expected an
// ApiResponse<T> wrapper that the backend never sent. The courier app talks
// to the real endpoints unwrapped.

// ── User / Auth ─────────────────────────────────────────────

data class User(
    val id: String,
    val email: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val phone: String,
    val role: String,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
)

data class AuthResponse(
    val token: String,
    @SerializedName("refresh_token") val refreshToken: String,
    val user: User,
)

data class CourierRegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val phone: String,
)

data class LoginRequest(
    val email: String,
    val password: String,
)

data class ApiError(val error: String)

// ── Courier profile ─────────────────────────────────────────

enum class OnboardingStatus(val displayName: String) {
    @SerializedName("pending_info") PENDING_INFO("Pending info"),
    @SerializedName("pending_documents") PENDING_DOCUMENTS("Pending documents"),
    @SerializedName("pending_background") PENDING_BACKGROUND("Background check"),
    @SerializedName("approved") APPROVED("Approved"),
    @SerializedName("rejected") REJECTED("Rejected"),
    @SerializedName("suspended") SUSPENDED("Suspended"),
}

enum class VehicleType(val displayName: String) {
    @SerializedName("car") CAR("Car"),
    @SerializedName("bike") BIKE("Bike"),
    @SerializedName("scooter") SCOOTER("Scooter"),
    @SerializedName("motorcycle") MOTORCYCLE("Motorcycle"),
    @SerializedName("walk") WALK("On foot"),
}

data class CourierProfile(
    val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("onboarding_status") val onboardingStatus: OnboardingStatus,
    @SerializedName("phone_verified") val phoneVerified: Boolean,
    @SerializedName("vehicle_type") val vehicleType: String = "",
    @SerializedName("vehicle_make") val vehicleMake: String = "",
    @SerializedName("vehicle_model") val vehicleModel: String = "",
    @SerializedName("vehicle_year") val vehicleYear: Int = 0,
    @SerializedName("vehicle_color") val vehicleColor: String = "",
    @SerializedName("license_plate") val licensePlate: String = "",
    @SerializedName("background_check_status") val backgroundCheckStatus: String = "",
    @SerializedName("payout_ready") val payoutReady: Boolean = false,
    @SerializedName("is_online") val isOnline: Boolean = false,
    @SerializedName("last_lat") val lastLat: Double = 0.0,
    @SerializedName("last_lng") val lastLng: Double = 0.0,
    @SerializedName("total_deliveries") val totalDeliveries: Int = 0,
    val rating: Double = 5.0,
)

data class UpdateVehicleRequest(
    @SerializedName("vehicle_type") val vehicleType: String,
    @SerializedName("vehicle_make") val vehicleMake: String,
    @SerializedName("vehicle_model") val vehicleModel: String,
    @SerializedName("vehicle_year") val vehicleYear: Int,
    @SerializedName("vehicle_color") val vehicleColor: String,
    @SerializedName("license_plate") val licensePlate: String,
)

data class UpdateDocumentsRequest(
    @SerializedName("drivers_license_url") val driversLicenseUrl: String,
    @SerializedName("drivers_license_number") val driversLicenseNumber: String,
    @SerializedName("insurance_url") val insuranceUrl: String,
    @SerializedName("vehicle_registration_url") val vehicleRegistrationUrl: String,
    @SerializedName("profile_photo_url") val profilePhotoUrl: String,
)

// ── Orders ──────────────────────────────────────────────────

data class AvailableDelivery(
    val id: String,
    @SerializedName("restaurant_id") val restaurantId: String,
    @SerializedName("restaurant_name") val restaurantName: String,
    val status: String,
    val total: Int,
    @SerializedName("delivery_fee") val deliveryFee: Int,
    @SerializedName("delivery_address") val deliveryAddress: String,
    @SerializedName("delivery_lat") val deliveryLat: Double,
    @SerializedName("delivery_lng") val deliveryLng: Double,
    @SerializedName("restaurant_lat") val restaurantLat: Double,
    @SerializedName("restaurant_lng") val restaurantLng: Double,
)

data class AvailableDeliveriesResponse(val deliveries: List<AvailableDelivery>)

data class CourierOrder(
    val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("restaurant_id") val restaurantId: String,
    @SerializedName("restaurant_name") val restaurantName: String,
    val status: String,
    val total: Int,
    @SerializedName("delivery_fee") val deliveryFee: Int,
    @SerializedName("delivery_address") val deliveryAddress: String,
    @SerializedName("delivery_lat") val deliveryLat: Double,
    @SerializedName("delivery_lng") val deliveryLng: Double,
    @SerializedName("claimed_at") val claimedAt: String? = null,
    @SerializedName("picked_up_at") val pickedUpAt: String? = null,
)

data class CourierOrdersResponse(val orders: List<CourierOrder>)

data class HistoryOrder(
    val id: String,
    @SerializedName("restaurant_name") val restaurantName: String,
    val total: Int,
    @SerializedName("delivery_fee") val deliveryFee: Int,
    @SerializedName("courier_tip") val courierTip: Int,
    @SerializedName("courier_payout") val courierPayout: Int,
    @SerializedName("delivered_at") val deliveredAt: String? = null,
)

data class HistoryResponse(val orders: List<HistoryOrder>)

// ── Live state ──────────────────────────────────────────────

data class OnlineRequest(
    val online: Boolean,
    val lat: Double,
    val lng: Double,
)

data class LocationPing(
    val lat: Double,
    val lng: Double,
    val heading: Double = 0.0,
    val speed: Double = 0.0,
)

// ── Payouts (Stripe Connect) ────────────────────────────────

data class PayoutStatus(
    @SerializedName("payout_ready") val payoutReady: Boolean,
    @SerializedName("connect_id") val connectId: String? = null,
    @SerializedName("details_submitted") val detailsSubmitted: Boolean = false,
)

data class PayoutLink(val url: String)

// ── Uploads ─────────────────────────────────────────────────

data class PresignRequest(
    val kind: String,
    @SerializedName("content_type") val contentType: String,
)

data class PresignResponse(
    @SerializedName("upload_url") val uploadUrl: String,
    @SerializedName("public_url") val publicUrl: String,
    val key: String,
    @SerializedName("expires_in") val expiresIn: Int,
)

// ── Chat (order-scoped messaging) ───────────────────────────

/**
 * A single chat message on an order. All three parties (consumer, seller,
 * courier) share the same thread. `senderRole` drives bubble alignment in
 * the UI — mirrors iOS ChatMessage.swift and backend handlers/chat.go.
 */
data class ChatMessage(
    val id: String = "",
    @SerializedName("order_id") val orderId: String = "",
    @SerializedName("sender_user_id") val senderUserId: String = "",
    @SerializedName("sender_role") val senderRole: String = "",
    val text: String = "",
    @SerializedName("created_at") val createdAt: String = "",
)

data class SendChatMessageRequest(val text: String)

// ── Device tokens (push) ────────────────────────────────────

data class RegisterDeviceRequest(
    val token: String,
    val platform: String = "android",
    val app: String = "courier",
)
