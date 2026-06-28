package com.koshereats.seller.data.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

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
    @Json(name = "rejected") REJECTED("Rejected"),
    UNKNOWN("Unknown");

    val isActive: Boolean
        get() = when (this) {
            DELIVERED, COMPLETED, CANCELLED, REJECTED, UNKNOWN -> false
            else -> true
        }
}

enum class KosherCertification(val displayName: String) {
    @Json(name = "OU") OU("OU"),
    @Json(name = "OK") OK("OK"),
    @Json(name = "Kof-K") KOF_K("Kof-K"),
    @Json(name = "Star-K") STAR_K("Star-K"),
    @Json(name = "cRc") CRC("cRc"),
    @Json(name = "Badatz") BADATZ("Badatz"),
    @Json(name = "Chof-K") CHOF_K("Chof-K"),
    @Json(name = "other") OTHER("Other"),
    UNKNOWN("Unknown"),
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
    UNKNOWN,
}

/**
 * Moshi adapter factory that returns UNKNOWN (if it exists) instead of throwing
 * JsonDataException when the JSON contains an unrecognised enum value. Only activates
 * for enums that declare an UNKNOWN constant; all other enums are delegated normally.
 */
class UnknownFallbackEnumAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        val rawType = Types.getRawType(type)
        if (!rawType.isEnum || annotations.isNotEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        val constants = (rawType as Class<Enum<*>>).enumConstants ?: return null
        val fallback = constants.firstOrNull { it.name == "UNKNOWN" } ?: return null
        val nameToConstant: Map<String, Enum<*>> = constants.associate { constant ->
            val jsonName = runCatching {
                rawType.getField(constant.name).getAnnotation(Json::class.java)?.name
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: constant.name
            jsonName to constant
        }
        val constantToName: Map<Enum<*>, String> = nameToConstant.entries.associate { (k, v) -> v to k }
        return object : JsonAdapter<Enum<*>>() {
            override fun fromJson(reader: JsonReader): Enum<*> {
                if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); return fallback }
                return nameToConstant[reader.nextString()] ?: fallback
            }
            override fun toJson(writer: JsonWriter, value: Enum<*>?) {
                if (value == null) { writer.nullValue(); return }
                writer.value(constantToName[value] ?: value.name)
            }
        }
    }
}

// --- Data Classes ---

@JsonClass(generateAdapter = true)
data class Restaurant(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val address: String = "",
    @Json(name = "street") val street: String = "",
    @Json(name = "city") val city: String = "",
    @Json(name = "state") val state: String = "",
    @Json(name = "zip_code") val zipCode: String = "",
    val phone: String = "",
    val email: String = "",
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "logo_url") val logoUrl: String = "",
    @Json(name = "kosher_certification") val kosherCertification: KosherCertification = KosherCertification.OU,
    @Json(name = "certifying_agency") val certificationDetails: String = "",
    @Json(name = "kosher_certificate_url") val kosherCertificateUrl: String = "",
    @Json(name = "is_open") val isOpen: Boolean = false,
    @Json(name = "approval_status") val approvalStatus: String = "pending",
    @Json(name = "delivery_fee") val deliveryFee: Int = 0,
    @Json(name = "min_order") val minimumOrder: Int = 0,
    @Json(name = "est_delivery_min") val averagePrepTime: Int = 30,
    @Json(name = "delivery_mode") val deliveryMode: String = "platform",
    @Json(name = "est_delivery_max") val estDeliveryMax: Int = 0,
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
    val category: MenuCategory = MenuCategory.UNKNOWN,
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "is_pareve") val isKosherPareve: Boolean = false,
    @Json(name = "is_dairy") val isDairy: Boolean = false,
    @Json(name = "is_meat") val isMeat: Boolean = false,
    @Json(name = "preparation_time") val preparationTime: Int = 15,
    val allergens: List<String> = emptyList(),
    @Json(name = "spice_level") val spiceLevel: Int = 0,
    @Json(name = "calories") val calories: Int? = null,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "modifier_groups") val modifierGroups: List<ModifierGroup> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class OrderItem(
    val id: String = "",
    @Json(name = "menu_item_id") val menuItemId: String = "",
    @Json(name = "name") val menuItemName: String = "",
    val quantity: Int = 1,
    @Json(name = "price") val unitPrice: Int = 0,
    @Json(name = "notes") val specialInstructions: String = "",
    @Json(name = "selected_modifiers") val selectedModifiers: List<SelectedModifier>? = null,
) {
    val totalPrice: Int get() = unitPrice * quantity
}

@JsonClass(generateAdapter = true)
data class CourierPublic(
    val id: String = "",
    @Json(name = "first_name") val firstName: String = "",
    val phone: String = "",
    @Json(name = "avatar_url") val avatarUrl: String = "",
    @Json(name = "vehicle_type") val vehicleType: String = "",
    @Json(name = "vehicle_make") val vehicleMake: String = "",
    @Json(name = "vehicle_model") val vehicleModel: String = "",
    @Json(name = "vehicle_color") val vehicleColor: String = "",
    @Json(name = "license_plate") val licensePlate: String = "",
    val rating: Double = 0.0,
    @Json(name = "total_deliveries") val totalDeliveries: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
) {
    val vehicleSummary: String
        get() {
            val parts = listOf(vehicleColor, vehicleMake, vehicleModel).filter { it.isNotBlank() }
            return if (parts.isEmpty()) vehicleType.replaceFirstChar { it.uppercase() } else parts.joinToString(" ")
        }
}

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
    @Json(name = "service_fee") val serviceFee: Int = 0,
    val discount: Int = 0,
    @Json(name = "courier_tip") val courierTip: Int = 0,
    val total: Int = 0,
    val status: OrderStatus = OrderStatus.PENDING,
    @Json(name = "fulfillment_type") val fulfillmentType: String = "delivery",
    @Json(name = "customer_name") val customerName: String = "",
    @Json(name = "customer_phone") val customerPhone: String = "",
    @Json(name = "courier") val courier: CourierPublic? = null,
    // Uber Direct / DoorDash delivery id once dispatched to an external provider
    // (null otherwise). Lets the UI hide "Dispatch to Uber" once a provider owns it.
    @Json(name = "external_delivery_id") val externalDeliveryId: String? = null,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
    @Json(name = "scheduled_for") val scheduledFor: String? = null,
    // Delivery mode for this order. Defaults from the restaurant, but can be
    // changed per order before courier handoff.
    @Json(name = "delivery_mode") val deliveryMode: String = "platform",
) {
    val isPickup: Boolean get() = fulfillmentType == "pickup"
    val isSelfDelivery: Boolean get() = deliveryMode == "restaurant"

    // Total quantity across all line items (matches iOS Order.itemCount), as opposed
    // to items.size which is the distinct line-item count.
    val itemCount: Int get() = items.sumOf { it.quantity }
}

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

@JsonClass(generateAdapter = true)
data class RefreshResponse(
    val token: String,
    @Json(name = "refresh_token") val refreshToken: String = "",
)

@JsonClass(generateAdapter = true)
data class PhoneStartRequest(
    val phone: String,
)

@JsonClass(generateAdapter = true)
data class PhoneStartResponse(
    val status: String,
)

@JsonClass(generateAdapter = true)
data class PhoneVerifyRequest(
    val phone: String,
    val code: String,
    val role: String = "seller",
)

// --- API Responses ---

@JsonClass(generateAdapter = true)
data class DashboardStats(
    @Json(name = "today_orders") val todayOrders: Int = 0,
    @Json(name = "today_revenue") val todayRevenue: Int = 0,
    @Json(name = "active_orders") val activeOrders: Int = 0,
    @Json(name = "avg_prep_time") val avgPrepTime: Double = 0.0,
    // Seller's 50% cut of delivery fees on orders they self-delivered today.
    @Json(name = "today_delivery_earnings") val todayDeliveryEarnings: Int = 0,
)

/**
 * Result of escalating a self-delivery order to Uber Direct
 * (PATCH /seller/orders/{id}/escalate). Mirrors iOS EscalateResponse and the
 * backend's writeJSON map (status/provider/delivery_id/tracking_url).
 */
@JsonClass(generateAdapter = true)
data class EscalateResponse(
    val status: String = "",
    val provider: String = "",
    @Json(name = "delivery_id") val deliveryId: String = "",
    @Json(name = "tracking_url") val trackingUrl: String = "",
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
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    @Json(name = "is_available") val isAvailable: Boolean? = null,
    @Json(name = "is_pareve") val isKosherPareve: Boolean? = null,
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
    @Json(name = "bogo") BOGO,
    UNKNOWN;

    val displayName: String
        get() = when (this) {
            PERCENTAGE -> "Percentage Off"
            FIXED -> "Fixed Amount Off"
            BOGO -> "Buy One Get One"
            UNKNOWN -> "Special Offer"
        }
}

@JsonClass(generateAdapter = true)
data class Deal(
    val id: String = "",
    @Json(name = "restaurant_id") val restaurantId: String = "",
    val title: String = "",
    val description: String = "",
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "menu_item_id") val menuItemId: String? = null,
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
            DiscountType.UNKNOWN -> title.ifBlank { "Special Offer" }
        }
}

@JsonClass(generateAdapter = true)
data class CreateRestaurantRequest(
    val name: String,
    val description: String = "",
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "logo_url") val logoUrl: String = "",
    val phone: String,
    val email: String,
    val street: String,
    val city: String,
    val state: String,
    @Json(name = "zip_code") val zipCode: String,
    @Json(name = "kosher_certification") val kosherCertification: KosherCertification,
    @Json(name = "certifying_agency") val certifyingAgency: String = "",
    @Json(name = "cuisine_type") val cuisineType: List<String> = emptyList(),
    @Json(name = "is_cholov_yisroel") val isCholovYisroel: Boolean = false,
    @Json(name = "is_pas_yisroel") val isPasYisroel: Boolean = false,
    @Json(name = "is_glatt_kosher") val isGlattKosher: Boolean = false,
    @Json(name = "kosher_certificate_url") val kosherCertificateUrl: String = "",
    // Relaxes backend validation of the manual detail fields when the seller
    // pasted an UberEats link on the import step — the import worker fills in
    // address/phone/cuisine/photo. Mirrors iOS CreateRestaurantBody.fromImport.
    @Json(name = "from_import") val fromImport: Boolean = false,
)

// --- Menu import (UberEats) ---

@JsonClass(generateAdapter = true)
data class CreateMenuImportBody(
    val source: String = "ubereats",
    @Json(name = "source_url") val sourceUrl: String,
)

/**
 * An async menu-import job. The scrape+import is drained server-side; the app
 * polls [ApiService.listMenuImports] and shows a banner while [isInProgress].
 * Mirrors iOS `MenuImport`.
 */
@JsonClass(generateAdapter = true)
data class MenuImport(
    val id: String,
    val status: String, // pending | running | done | failed
    @Json(name = "source_url") val sourceUrl: String? = null,
    @Json(name = "items_total") val itemsTotal: Int = 0,
    @Json(name = "items_created") val itemsCreated: Int = 0,
    val error: String? = null,
) {
    /** True while the import is still in flight (drives the "importing…" banner). */
    val isInProgress: Boolean get() = status == "pending" || status == "running"
}

@JsonClass(generateAdapter = true)
data class CreateDealRequest(
    val title: String,
    val description: String = "",
    @Json(name = "image_url") val imageUrl: String = "",
    @Json(name = "menu_item_id") val menuItemId: String? = null,
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
    @Json(name = "is_pareve") val isKosherPareve: Boolean = false,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "spice_level") val spiceLevel: Int? = null,
    @Json(name = "preparation_time") val preparationTime: Int? = null,
    val allergens: List<String>? = null,
    val calories: Int? = null,
)

// --- Modifiers ---

@JsonClass(generateAdapter = true)
data class SelectedModifier(
    val id: String = "",
    @Json(name = "group_id") val groupId: String = "",
    @Json(name = "group_name") val groupName: String = "",
    val name: String = "",
    @Json(name = "price_delta") val priceDelta: Int = 0,
)

@JsonClass(generateAdapter = true)
data class ModifierGroup(
    val id: String = "",
    @Json(name = "menu_item_id") val menuItemId: String = "",
    val name: String = "",
    val description: String = "",
    @Json(name = "is_required") val isRequired: Boolean = false,
    @Json(name = "min_selections") val minSelections: Int = 0,
    @Json(name = "max_selections") val maxSelections: Int = 1,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    val modifiers: List<Modifier> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class Modifier(
    val id: String = "",
    @Json(name = "group_id") val groupId: String = "",
    val name: String = "",
    @Json(name = "price_delta") val priceDelta: Int = 0,
    @Json(name = "is_default") val isDefault: Boolean = false,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class CreateModifierGroupRequest(
    val name: String,
    val description: String = "",
    @Json(name = "is_required") val isRequired: Boolean = false,
    @Json(name = "min_selections") val minSelections: Int = 0,
    @Json(name = "max_selections") val maxSelections: Int = 1,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    val modifiers: List<ModifierOptionRequest> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ModifierOptionRequest(
    val id: String? = null,
    val name: String,
    @Json(name = "price_delta") val priceDelta: Int = 0,
    @Json(name = "is_default") val isDefault: Boolean = false,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

// --- Uploads ---

@JsonClass(generateAdapter = true)
data class PresignResponse(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "public_url") val publicUrl: String,
    val key: String = "",
    @Json(name = "expires_in") val expiresIn: Int = 0,
)

// --- POS Integrations ---

@JsonClass(generateAdapter = true)
data class POSIntegration(
    val id: String,
    val provider: String,
    @Json(name = "merchant_id") val merchantId: String,
    @Json(name = "is_active") val isActive: Boolean,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "last_used_at") val lastUsedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CloverConnectURLResponse(
    @Json(name = "connect_url") val connectUrl: String,
)
