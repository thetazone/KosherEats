package com.koshereats.consumer.data.models

import com.google.gson.annotations.SerializedName

// ── Kosher Enums ──────────────────────────────────────────

enum class KosherCertification(val displayName: String, val abbreviation: String) {
    @SerializedName("OU") OU("Orthodox Union", "OU"),
    @SerializedName("OK") OK("OK Kosher", "OK"),
    @SerializedName("STAR_K") STAR_K("Star-K", "★K"),
    @SerializedName("KOF_K") KOF_K("Kof-K", "KF"),
    @SerializedName("CRC") CRC("Chicago Rabbinical Council", "cRc"),
    @SerializedName("BADATZ") BADATZ("Badatz", "BD"),
    @SerializedName("CHABAD") CHABAD("Chabad", "CH"),
    @SerializedName("LOCAL") LOCAL("Local Rabbinical", "LR"),
    @SerializedName("OTHER") OTHER("Other", "K"),
}

enum class DietaryType(val displayName: String) {
    @SerializedName("meat") MEAT("Meat"),
    @SerializedName("dairy") DAIRY("Dairy"),
    @SerializedName("pareve") PAREVE("Pareve"),
}

enum class OrderStatus(val displayName: String) {
    @SerializedName("pending") PENDING("Pending"),
    @SerializedName("confirmed") CONFIRMED("Confirmed"),
    @SerializedName("preparing") PREPARING("Preparing"),
    @SerializedName("ready_for_pickup") READY_FOR_PICKUP("Ready for Pickup"),
    @SerializedName("out_for_delivery") OUT_FOR_DELIVERY("Out for Delivery"),
    @SerializedName("delivered") DELIVERED("Delivered"),
    @SerializedName("cancelled") CANCELLED("Cancelled"),
}

enum class CuisineType(val displayName: String) {
    @SerializedName("israeli") ISRAELI("Israeli"),
    @SerializedName("middle_eastern") MIDDLE_EASTERN("Middle Eastern"),
    @SerializedName("american") AMERICAN("American"),
    @SerializedName("italian") ITALIAN("Italian"),
    @SerializedName("asian") ASIAN("Asian"),
    @SerializedName("mexican") MEXICAN("Mexican"),
    @SerializedName("sushi") SUSHI("Sushi"),
    @SerializedName("pizza") PIZZA("Pizza"),
    @SerializedName("deli") DELI("Deli"),
    @SerializedName("bakery") BAKERY("Bakery"),
    @SerializedName("bbq") BBQ("BBQ"),
    @SerializedName("falafel") FALAFEL("Falafel"),
    @SerializedName("indian") INDIAN("Indian"),
    @SerializedName("other") OTHER("Other"),
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
    @SerializedName("street_address") val streetAddress: String = "",
    val city: String = "",
    val state: String = "",
    @SerializedName("zip_code") val zipCode: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerializedName("delivery_instructions") val deliveryInstructions: String? = null,
)

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

// ── Restaurant ────────────────────────────────────────────

data class Restaurant(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerializedName("image_url") val imageUrl: String = "",
    @SerializedName("logo_url") val logoUrl: String? = null,
    @SerializedName("cover_image_url") val coverImageUrl: String? = null,
    val address: Address = Address(),
    val phone: String = "",
    val rating: Double = 0.0,
    @SerializedName("review_count") val reviewCount: Int = 0,
    @SerializedName("cuisine_types") val cuisineTypes: List<CuisineType> = emptyList(),
    @SerializedName("kosher_certification") val kosherCertification: KosherCertification = KosherCertification.OTHER,
    @SerializedName("certifying_authority") val certifyingAuthority: String = "",
    @SerializedName("mashgiach_name") val mashgiachName: String? = null,
    @SerializedName("is_cholov_yisroel") val isCholovYisroel: Boolean = false,
    @SerializedName("is_pas_yisroel") val isPasYisroel: Boolean = false,
    @SerializedName("is_glatt_kosher") val isGlattKosher: Boolean = false,
    @SerializedName("is_yoshon") val isYoshon: Boolean = false,
    @SerializedName("dietary_type") val dietaryType: DietaryType = DietaryType.MEAT,
    @SerializedName("is_open") val isOpen: Boolean = true,
    @SerializedName("delivery_fee") val deliveryFee: Int = 0,
    @SerializedName("delivery_time_min") val deliveryTimeMin: Int = 0,
    @SerializedName("delivery_time_max") val deliveryTimeMax: Int = 0,
    @SerializedName("minimum_order") val minimumOrder: Int = 0,
    @SerializedName("operating_hours") val operatingHours: List<OperatingHour> = emptyList(),
    @SerializedName("is_shabbat_closed") val isShabbatClosed: Boolean = true,
    val distance: Double? = null,
)

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
)

data class OrderItem(
    val id: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val price: Int = 0,
    @SerializedName("special_instructions") val specialInstructions: String? = null,
)

data class CreateOrderRequest(
    @SerializedName("restaurant_id") val restaurantId: String,
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
