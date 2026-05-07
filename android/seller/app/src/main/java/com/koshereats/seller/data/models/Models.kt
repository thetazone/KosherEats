package com.koshereats.seller.data.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- Enums ---

enum class OrderStatus(val displayName: String) {
    @Json(name = "scheduled") SCHEDULED("Scheduled"),
    @Json(name = "pending") PENDING("Pending"),
    @Json(name = "accepted") ACCEPTED("Accepted"),
    @Json(name = "preparing") PREPARING("Preparing"),
    @Json(name = "ready") READY("Ready"),
    @Json(name = "picked_up") PICKED_UP("Picked Up"),
    @Json(name = "delivered") DELIVERED("Delivered"),
    @Json(name = "completed") COMPLETED("Completed"),
    @Json(name = "cancelled") CANCELLED("Cancelled"),
    @Json(name = "rejected") REJECTED("Rejected");

    val isActive: Boolean
        get() = when (this) {
            DELIVERED, COMPLETED, CANCELLED, REJECTED -> false
            else -> true
        }
}

enum class KosherCertification(val displayName: String) {
    @Json(name = "ou") OU("OU"),
    @Json(name = "ok") OK("OK"),
    @Json(name = "kof_k") KOF_K("Kof-K"),
    @Json(name = "star_k") STAR_K("Star-K"),
    @Json(name = "crc") CRC("cRc"),
    @Json(name = "badatz") BADATZ("Badatz"),
    @Json(name = "chof_k") CHOF_K("Chof-K"),
    @Json(name = "other") OTHER("Other"),
}

enum class MenuCategory {
    @Json(name = "appetizers") APPETIZERS,
    @Json(name = "soups") SOUPS,
    @Json(name = "salads") SALADS,
    @Json(name = "mains") MAINS,
    @Json(name = "sides") SIDES,
    @Json(name = "desserts") DESSERTS,
    @Json(name = "drinks") DRINKS,
    @Json(name = "shabbat_specials") SHABBAT_SPECIALS,
    @Json(name = "holiday_specials") HOLIDAY_SPECIALS,
}

// --- Data Classes ---

@JsonClass(generateAdapter = true)
data class Restaurant(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "kosher_certification") val kosherCertification: KosherCertification = KosherCertification.OU,
    @Json(name = "certifying_agency") val certificationDetails: String = "",
    @Json(name = "is_open") val isOpen: Boolean = false,
    @Json(name = "opening_hours") val openingHours: Map<String, String> = emptyMap(),
    @Json(name = "delivery_fee") val deliveryFee: Int = 0,
    @Json(name = "min_order") val minimumOrder: Int = 0,
    @Json(name = "est_delivery_min") val averagePrepTime: Int = 30,
    val rating: Double = 0.0,
    @Json(name = "review_count") val totalReviews: Int = 0,
    @Json(name = "created_at") val createdAt: String = "",
)

@JsonClass(generateAdapter = true)
data class MenuItem(
    val id: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    val name: String = "",
    val description: String = "",
    val price: Int = 0,
    val category: MenuCategory = MenuCategory.MAINS,
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "is_kosher_pareve") val isKosherPareve: Boolean = false,
    @Json(name = "is_dairy") val isDairy: Boolean = false,
    @Json(name = "is_meat") val isMeat: Boolean = false,
    @Json(name = "preparation_time") val preparationTime: Int = 15,
    val allergens: List<String> = emptyList(),
    @Json(name = "spice_level") val spiceLevel: Int = 0,
    @Json(name = "calories") val calories: Int? = null,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class OrderItem(
    val id: String = "",
    @Json(name = "menu_item_id") val menuItemId: String = "",
    @Json(name = "name") val menuItemName: String = "",
    val quantity: Int = 1,
    @Json(name = "price") val unitPrice: Int = 0,
    val totalPrice: Int = 0,
    @Json(name = "notes") val specialInstructions: String = "",
)

@JsonClass(generateAdapter = true)
data class Order(
    val id: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    @Json(name = "user_id") val customerId: String = "",
    @Json(name = "delivery_address") val deliveryAddress: String = "",
    val items: List<OrderItem> = emptyList(),
    val subtotal: Int = 0,
    @Json(name = "delivery_fee") val deliveryFee: Int = 0,
    val tax: Int = 0,
    @Json(name = "courier_tip") val courierTip: Int = 0,
    val total: Int = 0,
    val status: OrderStatus = OrderStatus.PENDING,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
)

// --- Auth ---

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String,
    val role: String = "seller",
)

@JsonClass(generateAdapter = true)
data class User(
    val id: String = "",
    val email: String = "",
    @Json(name = "first_name") val firstName: String = "",
    @Json(name = "last_name") val lastName: String = "",
    val phone: String = "",
    val role: String = "",
    @Json(name = "avatar_url") val avatarUrl: String = "",
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    @Json(name = "refresh_token") val refreshToken: String = "",
    val user: User,
)

// --- API Responses ---

@JsonClass(generateAdapter = true)
data class DashboardStats(
    @Json(name = "today_orders") val todayOrders: Int = 0,
    @Json(name = "today_revenue") val todayRevenue: Int = 0,
    @Json(name = "active_orders") val activeOrders: Int = 0,
    @Json(name = "avg_prep_time") val avgPrepTime: Double = 0.0,
)

// --- Device tokens (push notifications) ---

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    val token: String,
    val platform: String = "android",
    val app: String = "seller",
)

@JsonClass(generateAdapter = true)
data class SellerMenuCategory(
    val id: String,
    val name: String,
    val description: String? = null,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    val items: List<MenuItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class UpdateMenuItemRequest(
    val name: String? = null,
    val description: String? = null,
    val price: Int? = null,
    val category: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    @Json(name = "is_available") val isAvailable: Boolean? = null,
    @Json(name = "is_kosher_pareve") val isKosherPareve: Boolean? = null,
    @Json(name = "is_dairy") val isDairy: Boolean? = null,
    @Json(name = "is_meat") val isMeat: Boolean? = null,
    @Json(name = "preparation_time") val preparationTime: Int? = null,
    val allergens: List<String>? = null,
    @Json(name = "spice_level") val spiceLevel: Int? = null,
    val calories: Int? = null,
)

// --- Deals ---

enum class DiscountType {
    @Json(name = "percentage") PERCENTAGE,
    @Json(name = "fixed") FIXED,
    @Json(name = "bogo") BOGO;

    val displayName: String
        get() = when (this) {
            PERCENTAGE -> "Percentage Off"
            FIXED -> "Fixed Amount Off"
            BOGO -> "Buy One Get One"
        }
}

@JsonClass(generateAdapter = true)
data class Deal(
    val id: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    val title: String = "",
    val description: String = "",
    @Json(name = "discount_type") val discountType: DiscountType = DiscountType.PERCENTAGE,
    @Json(name = "discount_value") val discountValue: Int = 0,
    @Json(name = "min_order_amount") val minOrderAmount: Int? = null,
    @Json(name = "starts_at") val startsAt: String = "",
    @Json(name = "expires_at") val expiresAt: String = "",
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
) {
    val discountLabel: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "$discountValue% Off"
            DiscountType.FIXED -> "${discountValue.formatPrice()} Off"
            DiscountType.BOGO -> "BOGO"
        }
}

@JsonClass(generateAdapter = true)
data class CreateRestaurantRequest(
    val name: String,
    val description: String = "",
    val phone: String,
    val email: String,
    val street: String,
    val city: String,
    val state: String,
    @Json(name = "zip_code") val zipCode: String,
    @Json(name = "kosher_certification") val kosherCertification: String,
    @Json(name = "certifying_agency") val certifyingAgency: String = "",
    @Json(name = "cuisine_type") val cuisineType: List<String> = emptyList(),
    @Json(name = "is_cholov_yisroel") val isCholovYisroel: Boolean = false,
    @Json(name = "is_pas_yisroel") val isPasYisroel: Boolean = false,
    @Json(name = "is_glatt_kosher") val isGlattKosher: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class CreateDealRequest(
    val title: String,
    val description: String = "",
    @Json(name = "discount_type") val discountType: DiscountType,
    @Json(name = "discount_value") val discountValue: Int = 0,
    @Json(name = "min_order_amount") val minOrderAmount: Int? = null,
    @Json(name = "starts_at") val startsAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class CreateMenuItemBody(
    @Json(name = "category_id") val categoryId: String,
    val name: String,
    val description: String = "",
    val price: Int,
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "is_meat") val isMeat: Boolean = false,
    @Json(name = "is_dairy") val isDairy: Boolean = false,
    @Json(name = "is_pareve") val isPareve: Boolean = false,
    @Json(name = "is_available") val isAvailable: Boolean = true,
)

// --- Uploads ---

@JsonClass(generateAdapter = true)
data class PresignResponse(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "public_url") val publicUrl: String,
    val key: String = "",
    @Json(name = "expires_in") val expiresIn: Int = 0,
)

