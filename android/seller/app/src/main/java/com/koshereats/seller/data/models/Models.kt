package com.koshereats.seller.data.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- Enums ---

enum class OrderStatus {
    @Json(name = "pending") PENDING,
    @Json(name = "accepted") ACCEPTED,
    @Json(name = "preparing") PREPARING,
    @Json(name = "ready") READY,
    @Json(name = "picked_up") PICKED_UP,
    @Json(name = "delivered") DELIVERED,
    @Json(name = "completed") COMPLETED,
    @Json(name = "cancelled") CANCELLED,
}

enum class KosherCertification {
    @Json(name = "ou") OU,
    @Json(name = "ok") OK,
    @Json(name = "star_k") STAR_K,
    @Json(name = "kof_k") KOF_K,
    @Json(name = "crc") CRC,
    @Json(name = "other") OTHER,
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
    @Json(name = "certification_details") val certificationDetails: String = "",
    @Json(name = "is_open") val isOpen: Boolean = false,
    @Json(name = "opening_hours") val openingHours: Map<String, String> = emptyMap(),
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    @Json(name = "minimum_order") val minimumOrder: Double = 0.0,
    @Json(name = "average_prep_time") val averagePrepTime: Int = 30,
    val rating: Double = 0.0,
    @Json(name = "total_reviews") val totalReviews: Int = 0,
    @Json(name = "created_at") val createdAt: String = "",
)

@JsonClass(generateAdapter = true)
data class MenuItem(
    val id: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
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
    @Json(name = "menu_item_name") val menuItemName: String = "",
    val quantity: Int = 1,
    @Json(name = "unit_price") val unitPrice: Double = 0.0,
    @Json(name = "total_price") val totalPrice: Double = 0.0,
    @Json(name = "special_instructions") val specialInstructions: String = "",
)

@JsonClass(generateAdapter = true)
data class Order(
    val id: String = "",
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "customer_name") val customerName: String = "",
    @Json(name = "customer_phone") val customerPhone: String = "",
    @Json(name = "delivery_address") val deliveryAddress: String = "",
    val items: List<OrderItem> = emptyList(),
    val subtotal: Double = 0.0,
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val status: OrderStatus = OrderStatus.PENDING,
    @Json(name = "special_instructions") val specialInstructions: String = "",
    @Json(name = "estimated_prep_time") val estimatedPrepTime: Int = 30,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
    @Json(name = "accepted_at") val acceptedAt: String? = null,
    @Json(name = "ready_at") val readyAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
)

// --- Auth ---

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String,
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    val restaurant: Restaurant,
)

// --- API Responses ---

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class DashboardStats(
    @Json(name = "total_orders_today") val totalOrdersToday: Int = 0,
    @Json(name = "revenue_today") val revenueToday: Double = 0.0,
    @Json(name = "active_orders") val activeOrders: Int = 0,
    @Json(name = "average_prep_time") val averagePrepTime: Int = 0,
    @Json(name = "total_orders_week") val totalOrdersWeek: Int = 0,
    @Json(name = "revenue_week") val revenueWeek: Double = 0.0,
)

@JsonClass(generateAdapter = true)
data class UpdateOrderStatusRequest(
    val status: String,
    @Json(name = "estimated_prep_time") val estimatedPrepTime: Int? = null,
)

@JsonClass(generateAdapter = true)
data class UpdateMenuItemRequest(
    val name: String? = null,
    val description: String? = null,
    val price: Double? = null,
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
