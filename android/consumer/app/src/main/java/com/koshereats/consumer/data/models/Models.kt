package com.koshereats.consumer.data.models

import com.google.gson.annotations.SerializedName

// ── Kosher Enums ──────────────────────────────────────────

enum class KosherCertification(val displayName: String, val abbreviation: String) {
    @SerializedName("OU") OU("Orthodox Union", "OU"),
    @SerializedName("OK") OK("OK Kosher", "OK"),
    @SerializedName("Star-K") STAR_K("Star-K", "★K"),
    @SerializedName("Kof-K") KOF_K("Kof-K", "KF"),
    @SerializedName(value = "CRC", alternate = ["cRc"]) CRC("Chicago Rabbinical Council", "cRc"),
    @SerializedName("Badatz") BADATZ("Badatz", "BD"),
    @SerializedName("CHABAD") CHABAD("Chabad", "CH"),
    @SerializedName("LOCAL") LOCAL("Local Rabbinical", "LR"),
    @SerializedName("OTHER") OTHER("Other", "K"),
}

enum class DietaryType(val displayName: String) {
    @SerializedName(value = "meat", alternate = ["Meat"]) MEAT("Meat"),
    @SerializedName(value = "dairy", alternate = ["Dairy"]) DAIRY("Dairy"),
    @SerializedName(value = "pareve", alternate = ["Pareve"]) PAREVE("Pareve"),
}

enum class OrderStatus(val displayName: String) {
    @SerializedName("pending") PENDING("Pending"),
    @SerializedName("confirmed") CONFIRMED("Confirmed"),
    @SerializedName("preparing") PREPARING("Preparing"),
    @SerializedName("ready") READY("Ready for Pickup"),
    @SerializedName("picked_up") PICKED_UP("Out for Delivery"),
    @SerializedName("delivered") DELIVERED("Delivered"),
    @SerializedName("cancelled") CANCELLED("Cancelled"),
    @SerializedName("completed") COMPLETED("Completed");

    val stepIndex: Int
        get() = when (this) {
            PENDING -> 1
            CONFIRMED -> 2
            PREPARING -> 3
            READY, PICKED_UP, DELIVERED -> 4
            COMPLETED -> 5
            CANCELLED -> -1
        }

    val isActive: Boolean
        get() = when (this) {
            DELIVERED, CANCELLED, COMPLETED -> false
            else -> true
        }
}

enum class CuisineType(val displayName: String) {
    @SerializedName(value = "israeli", alternate = ["Israeli"]) ISRAELI("Israeli"),
    @SerializedName(value = "middle_eastern", alternate = ["Middle Eastern"]) MIDDLE_EASTERN("Middle Eastern"),
    @SerializedName(value = "american", alternate = ["American"]) AMERICAN("American"),
    @SerializedName(value = "italian", alternate = ["Italian"]) ITALIAN("Italian"),
    @SerializedName(value = "asian", alternate = ["Asian"]) ASIAN("Asian"),
    @SerializedName(value = "mexican", alternate = ["Mexican"]) MEXICAN("Mexican"),
    @SerializedName(value = "sushi", alternate = ["Sushi"]) SUSHI("Sushi"),
    @SerializedName(value = "pizza", alternate = ["Pizza"]) PIZZA("Pizza"),
    @SerializedName(value = "deli", alternate = ["Deli"]) DELI("Deli"),
    @SerializedName(value = "bakery", alternate = ["Bakery"]) BAKERY("Bakery"),
    @SerializedName(value = "bbq", alternate = ["BBQ"]) BBQ("BBQ"),
    @SerializedName(value = "falafel", alternate = ["Falafel"]) FALAFEL("Falafel"),
    @SerializedName(value = "indian", alternate = ["Indian"]) INDIAN("Indian"),
    @SerializedName(value = "grill", alternate = ["Grill"]) GRILL("Grill"),
    @SerializedName(value = "dairy", alternate = ["Dairy"]) DAIRY("Dairy"),
    @SerializedName(value = "eastern_european", alternate = ["Eastern European"]) EASTERN_EUROPEAN("Eastern European"),
    @SerializedName(value = "comfort", alternate = ["Comfort"]) COMFORT("Comfort"),
    @SerializedName(value = "mediterranean", alternate = ["Mediterranean"]) MEDITERRANEAN("Mediterranean"),
    @SerializedName(value = "other", alternate = ["Other"]) OTHER("Other"),
}

// ── User ──────────────────────────────────────────────────

data class User(
    val id: String = "",
    val email: String = "",
    @SerializedName("first_name") val firstName: String = "",
    @SerializedName("last_name") val lastName: String = "",
    val phone: String = "",
    @SerializedName("profile_image_url") val profileImageUrl: String? = null,
    @SerializedName("default_address") val defaultAddress: Address? = null,
    val addresses: List<Address> = emptyList(),
    @SerializedName("created_at") val createdAt: String = "",
)

data class Address(
    val id: String = "",
    val label: String = "",
    @SerializedName("street") val streetAddress: String = "",
    val city: String = "",
    val state: String = "",
    @SerializedName("zip_code") val zipCode: String = "",
    @SerializedName("lat") val latitude: Double = 0.0,
    @SerializedName("lng") val longitude: Double = 0.0,
    @SerializedName("delivery_instructions") val deliveryInstructions: String? = null,
    @SerializedName("is_default") val isDefault: Boolean = false,
)

val Address.formatted: String get() = "$streetAddress, $city, $state $zipCode"

// ── Auth ──────────────────────────────────────────────────

data class LoginRequest(
    val email: String,
    val password: String,
)

data class RegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val phone: String,
)

data class SocialLoginRequest(
    val provider: String,
    val token: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
)

data class AuthResponse(
    val token: String,
    @SerializedName("refresh_token") val refreshToken: String,
    val user: User,
)

data class PhoneStartRequest(
    val phone: String,
)

data class PhoneVerifyRequest(
    val phone: String,
    val code: String,
    val role: String = "consumer",
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    val email: String? = null,
)

// ── Restaurant ────────────────────────────────────────────

data class Restaurant(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    @SerializedName("logo_url") val logoUrl: String? = null,
    @SerializedName("cover_image_url") val coverImageUrl: String? = null,
    val phone: String = "",
    val rating: Double = 0.0,
    @SerializedName("review_count") val reviewCount: Int = 0,
    // Element type is nullable: Gson deserializes any cuisine string not in
    // the CuisineType @SerializedName set to null (e.g. "Japanese"), and
    // those nulls land in the list at runtime regardless of Kotlin's
    // declared type. UI sites must filter or null-safe access.
    @SerializedName(value = "cuisine_types", alternate = ["cuisine_type"]) val cuisineTypes: List<CuisineType?> = emptyList(),
    // Nullable because Gson does NOT honor Kotlin defaults — when the API
    // sends null or omits the field, this lands as null at runtime regardless
    // of the declared default. Call sites coalesce to OTHER.
    @SerializedName("kosher_certification") val kosherCertification: KosherCertification? = null,
    @SerializedName("certifying_agency") val certifyingAgency: String = "",
    @SerializedName("mashgiach_name") val mashgiachName: String? = null,
    @SerializedName("is_cholov_yisroel") val isCholovYisroel: Boolean = false,
    @SerializedName("is_pas_yisroel") val isPasYisroel: Boolean = false,
    @SerializedName("is_glatt_kosher") val isGlattKosher: Boolean = false,
    @SerializedName("is_yoshon") val isYoshon: Boolean = false,
    @SerializedName("dietary_type") val dietaryType: DietaryType? = null,
    @SerializedName("is_open") val isOpen: Boolean = true,
    @SerializedName("delivery_fee") val deliveryFee: Int = 0,
    @SerializedName("est_delivery_min") val deliveryTimeMin: Int = 0,
    @SerializedName("est_delivery_max") val deliveryTimeMax: Int = 0,
    @SerializedName("min_order") val minimumOrder: Int = 0,
    // Backend returns either {} (empty) or an array — declared as Any? so Gson
    // accepts both shapes without throwing JsonSyntaxException. Not used in consumer UI.
    @SerializedName("operating_hours") val operatingHours: Any? = null,
    @SerializedName("is_shabbat_closed") val isShabbatClosed: Boolean = true,
    val distance: Double? = null,
    // Flat address fields from backend (not a nested Address object)
    val street: String = "",
    val city: String = "",
    val state: String = "",
    @SerializedName("zip_code") val zipCode: String = "",
    @SerializedName("lat") val latitude: Double = 0.0,
    @SerializedName("lng") val longitude: Double = 0.0,
) {
    /** Convenience: build an [Address] from the flat fields for UI code that expects one. */
    val address: Address
        get() = Address(
            streetAddress = street,
            city = city,
            state = state,
            zipCode = zipCode,
            latitude = latitude,
            longitude = longitude,
        )

}

data class OperatingHour(
    @SerializedName("day_of_week") val dayOfWeek: Int = 0,
    @SerializedName("open_time") val openTime: String = "",
    @SerializedName("close_time") val closeTime: String = "",
    @SerializedName("is_closed") val isClosed: Boolean = false,
)

// ── Menu ──────────────────────────────────────────────────

data class MenuCategory(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerializedName("sort_order") val sortOrder: Int = 0,
    val items: List<MenuItem> = emptyList(),
)

data class MenuItem(
    val id: String = "",
    @SerializedName("restaurant_id") val restaurantId: String = "",
    @SerializedName("category_id") val categoryId: String = "",
    val name: String = "",
    val description: String = "",
    val price: Int = 0,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("is_available") val isAvailable: Boolean = true,
    @SerializedName("is_popular") val isPopular: Boolean = false,
    @SerializedName("is_meat") val isMeat: Boolean = false,
    @SerializedName("is_dairy") val isDairy: Boolean = false,
    @SerializedName("is_pareve") val isPareve: Boolean = false,
    @SerializedName("is_cholov_yisroel") val isCholovYisroel: Boolean = false,
    @SerializedName("is_pas_yisroel") val isPasYisroel: Boolean = false,
    @SerializedName("is_glatt_kosher") val isGlattKosher: Boolean = false,
    @SerializedName("is_spicy") val isSpicy: Boolean = false,
    @SerializedName("is_vegetarian") val isVegetarian: Boolean = false,
    @SerializedName("is_vegan") val isVegan: Boolean = false,
    @SerializedName("is_gluten_free") val isGlutenFree: Boolean = false,
    val allergens: List<String> = emptyList(),
    val customizations: List<MenuItemCustomization> = emptyList(),
)

data class MenuItemCustomization(
    val id: String = "",
    val name: String = "",
    val required: Boolean = false,
    @SerializedName("max_selections") val maxSelections: Int = 1,
    val options: List<CustomizationOption> = emptyList(),
)

data class CustomizationOption(
    val id: String = "",
    val name: String = "",
    @SerializedName("price_modifier") val priceModifier: Int = 0,
)

// ── Cart ──────────────────────────────────────────────────

data class Cart(
    @SerializedName("restaurant_id") val restaurantId: String = "",
    @SerializedName("restaurant_name") val restaurantName: String = "",
    @SerializedName("restaurant_image_url") val restaurantImageUrl: String? = null,
    val items: List<CartItem> = emptyList(),
) {
    val subtotal: Int get() = items.sumOf { it.totalPrice }
    val itemCount: Int get() = items.sumOf { it.quantity }
}

data class CartItem(
    val id: String = "",
    @SerializedName("menu_item") val menuItem: MenuItem = MenuItem(),
    val quantity: Int = 1,
    @SerializedName("special_instructions") val specialInstructions: String? = null,
    @SerializedName("selected_customizations") val selectedCustomizations: List<SelectedCustomization> = emptyList(),
) {
    val totalPrice: Int
        get() {
            val basePrice = menuItem.price
            val customizationPrice = selectedCustomizations
                .flatMap { it.selectedOptions }
                .sumOf { it.priceModifier }
            return (basePrice + customizationPrice) * quantity
        }
}

data class SelectedCustomization(
    @SerializedName("customization_id") val customizationId: String = "",
    @SerializedName("selected_options") val selectedOptions: List<CustomizationOption> = emptyList(),
)

// ── Order ─────────────────────────────────────────────────

data class Order(
    val id: String = "",
    @SerializedName("order_number") val orderNumber: String = "",
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("restaurant_id") val restaurantId: String = "",
    @SerializedName("restaurant_name") val restaurantName: String = "",
    @SerializedName("restaurant_image_url") val restaurantImageUrl: String? = null,
    val status: OrderStatus = OrderStatus.PENDING,
    val items: List<OrderItem> = emptyList(),
    val subtotal: Int = 0,
    @SerializedName("delivery_fee") val deliveryFee: Int = 0,
    @SerializedName("service_fee") val serviceFee: Int = 0,
    val tax: Int = 0,
    val tip: Int = 0,
    val total: Int = 0,
    @SerializedName("delivery_address") val deliveryAddress: Address = Address(),
    @SerializedName("estimated_delivery_time") val estimatedDeliveryTime: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    val courier: CourierPublic? = null,
    @SerializedName("restaurant_lat") val restaurantLat: Double? = null,
    @SerializedName("restaurant_lng") val restaurantLng: Double? = null,
    @SerializedName("courier_tip") val courierTip: Int = 0,
    @SerializedName("delivery_proof_url") val deliveryProofUrl: String? = null,
    @SerializedName("claimed_at") val claimedAt: String? = null,
    @SerializedName("picked_up_at") val pickedUpAt: String? = null,
    @SerializedName("delivered_at") val deliveredAt: String? = null,
)

data class OrderItem(
    val id: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val price: Int = 0,
    @SerializedName("special_instructions") val specialInstructions: String? = null,
)

data class CreateOrderRequest(
    @SerializedName("restaurant_id") val restaurantId: String = "",
    @SerializedName("delivery_address") val deliveryAddress: String,
    @SerializedName("delivery_lat") val deliveryLat: Double,
    @SerializedName("delivery_lng") val deliveryLng: Double,
    @SerializedName("payment_intent_id") val paymentIntentId: String,
    val tip: Int = 0,
    /**
     * Scheduled delivery time as RFC-3339. `null` means deliver ASAP. When
     * set more than 30 minutes in the future the backend flags the order
     * `scheduled` and the in-process dispatcher promotes it to `pending` as
     * the delivery window approaches.
     */
    @SerializedName("scheduled_for") val scheduledFor: String? = null,
)

// ── Cart (server-backed) ──────────────────────────────────

data class AddToCartRequest(
    @SerializedName("menu_item_id") val menuItemId: String,
    @SerializedName("restaurant_id") val restaurantId: String,
    val quantity: Int,
    val notes: String = "",
    @SerializedName("modifier_ids") val modifierIds: List<String> = emptyList(),
)

data class ServerCart(
    @SerializedName("restaurant_id") val restaurantId: String = "",
    val items: List<CartItem> = emptyList(),
    val subtotal: Int = 0,
)

// ── Payments ──────────────────────────────────────────────

data class PaymentSheetRequest(
    val tip: Int = 0,
    @SerializedName("restaurant_id") val restaurantId: String = "",
    @SerializedName("delivery_address") val deliveryAddress: String = "",
)

data class PaymentSheetBundle(
    @SerializedName("publishable_key") val publishableKey: String = "",
    @SerializedName("customer_id") val customerId: String = "",
    @SerializedName("ephemeral_key_secret") val ephemeralKeySecret: String = "",
    @SerializedName("payment_intent_secret") val paymentIntentSecret: String = "",
    val subtotal: Int = 0,
    @SerializedName("delivery_fee") val deliveryFee: Int = 0,
    @SerializedName("service_fee") val serviceFee: Int = 0,
    val tax: Int = 0,
    val tip: Int = 0,
    val total: Int = 0,
    @SerializedName("is_stub") val isStub: Boolean = false,
)

// ── Courier ───────────────────────────────────────────────

data class CourierPublic(
    val id: String = "",
    @SerializedName("first_name") val firstName: String = "",
    val rating: Double = 0.0,
    @SerializedName("vehicle_summary") val vehicleSummary: String = "",
    val phone: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class CourierLocationEvent(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
)

// ── API Responses ─────────────────────────────────────────

data class PaginatedResponse<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerializedName("per_page") val perPage: Int = 20,
    @SerializedName("total_pages") val totalPages: Int = 1,
)

// ── Chat (order-scoped messaging) ────────────────────────

/**
 * A single chat message on an order. Mirrors backend handlers/chat.go.
 * All three parties (consumer, seller, courier) see the same thread.
 * `senderRole` drives bubble alignment + label in the UI.
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

// ── Device tokens (push notifications) ───────────────────

data class RegisterDeviceRequest(
    val token: String,
    val platform: String = "android",
    val app: String = "consumer",
)

// ── Reviews ───────────────────────────────────────────────

data class Review(
    val id: String = "",
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("user_name") val userName: String = "",
    @SerializedName("restaurant_id") val restaurantId: String = "",
    val rating: Int = 0,
    val comment: String = "",
    @SerializedName("created_at") val createdAt: String = "",
)

// ── Deals ─────────────────────────────────────────────────

enum class DiscountType(val displayName: String) {
    @SerializedName("percentage") PERCENTAGE("% Off"),
    @SerializedName("fixed") FIXED("Off"),
    @SerializedName("bogo") BOGO("BOGO"),
}

data class Deal(
    val id: String = "",
    @SerializedName("restaurant_id") val restaurantId: String = "",
    val title: String = "",
    val description: String = "",
    @SerializedName("discount_type") val discountType: DiscountType = DiscountType.PERCENTAGE,
    @SerializedName("discount_value") val discountValue: Int = 0,
    @SerializedName("min_order_amount") val minOrderAmount: Int? = null,
    @SerializedName("starts_at") val startsAt: String = "",
    @SerializedName("expires_at") val expiresAt: String = "",
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("restaurant_name") val restaurantName: String = "",
    @SerializedName("restaurant_image_url") val restaurantImageUrl: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
) {
    val discountBadge: String
        get() = when (discountType) {
            DiscountType.PERCENTAGE -> "$discountValue% Off"
            DiscountType.FIXED -> "$${discountValue / 100} Off"
            DiscountType.BOGO -> "BOGO"
        }
}
