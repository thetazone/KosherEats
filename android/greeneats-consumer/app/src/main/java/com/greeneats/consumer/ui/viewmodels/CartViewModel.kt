package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.models.*
import com.greeneats.consumer.data.repository.Resource
import com.greeneats.consumer.data.repository.RestaurantRepository
import com.greeneats.consumer.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the cart system. Supports multiple restaurant carts simultaneously.
 *
 * [carts] holds every active restaurant cart keyed by restaurant ID.
 * [activeRestaurantId] tracks which single-restaurant cart the user is
 * currently viewing / checking out (set when they tap "View cart" from the
 * multi-cart list).
 *
 * Backwards-compatible properties ([cart], [subtotal], [isEmpty], etc.) are
 * derived from [activeRestaurantId] so existing checkout / single-cart UI
 * continues to work without changes.
 */
data class CartUiState(
    val carts: Map<String, Cart> = emptyMap(),
    val activeRestaurantId: String? = null,
    val deliveryFee: Int = 399,
    val serviceFee: Int = 249,
    /** Tax rate as parts-per-million (8.875 % = 88 750 ppm) for integer-only math. */
    val taxRatePpm: Int = 88_750,
    val tip: Int = 0,
    /**
     * Scheduled delivery time. `null` = deliver ASAP (the default). Any
     * future value gets serialized to RFC-3339 and sent on CreateOrderRequest;
     * the backend flags the order `scheduled` and the dispatcher promotes it
     * to `pending` as the window approaches.
     */
    val scheduledFor: java.time.LocalDateTime? = null,
    val pendingDealItem: Deal? = null,
    val isPlacingOrder: Boolean = false,
    val orderPlaced: Order? = null,
    val error: String? = null,
) {
    /** The currently-active single restaurant cart (for checkout / detail view). */
    val cart: Cart get() = activeRestaurantId?.let { carts[it] } ?: carts.values.firstOrNull() ?: Cart()

    val subtotal: Int get() = cart.subtotal
    val discount: Int get() = cart.discount
    val tax: Int get() = ((cart.discountedSubtotal.toLong() * taxRatePpm + 500_000) / 1_000_000).toInt()
    val total: Int get() = cart.discountedSubtotal + deliveryFee + serviceFee + tax + tip
    val isEmpty: Boolean get() = cart.items.isEmpty()
    val itemCount: Int get() = cart.itemCount
    val appliedDeal: Deal? get() = cart.appliedDeal

    /** Total item count across ALL restaurant carts. */
    val totalItemCount: Int get() = carts.values.sumOf { it.itemCount }

    /** All non-empty carts as a list, ordered by restaurant name. */
    val allCarts: List<Cart> get() = carts.values.filter { it.items.isNotEmpty() }.sortedBy { it.restaurantName }

    /** True when there are items in more than one restaurant cart. */
    val hasMultipleCarts: Boolean get() = allCarts.size > 1
}

@HiltViewModel
class CartViewModel @Inject constructor(
    private val repository: RestaurantRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    private val logoutJob: Job = viewModelScope.launch {
        sessionManager.logoutEvent.collect {
            _uiState.value = CartUiState()
        }
    }

    override fun onCleared() {
        super.onCleared()
        logoutJob.cancel()
    }

    fun addItem(
        menuItem: MenuItem,
        restaurantId: String,
        restaurantName: String,
        restaurantImageUrl: String? = null,
        quantity: Int = 1,
        selectedCustomizations: List<SelectedCustomization> = emptyList(),
        specialInstructions: String? = null,
    ) {
        _uiState.update { state ->
            val currentCart = state.carts[restaurantId] ?: Cart(
                restaurantId = restaurantId,
                restaurantName = restaurantName,
                restaurantImageUrl = restaurantImageUrl,
            )

            val newCartItem = CartItem(
                id = UUID.randomUUID().toString(),
                menuItem = menuItem,
                quantity = quantity.coerceIn(1, 99),
                selectedCustomizations = selectedCustomizations,
                specialInstructions = specialInstructions?.takeIf { it.isNotBlank() },
            )

            val existingIndex = if (selectedCustomizations.isEmpty()) {
                currentCart.items.indexOfFirst {
                    it.menuItem.id == menuItem.id && it.selectedCustomizations.isEmpty()
                }
            } else -1

            val updatedItems = if (existingIndex >= 0) {
                currentCart.items.toMutableList().apply {
                    val existing = this[existingIndex]
                    this[existingIndex] = existing.copy(quantity = (existing.quantity + quantity).coerceAtMost(99))
                }
            } else {
                currentCart.items + newCartItem
            }

            val updatedCart = currentCart.copy(
                restaurantId = restaurantId,
                restaurantName = restaurantName,
                restaurantImageUrl = restaurantImageUrl ?: currentCart.restaurantImageUrl,
                items = updatedItems,
            )

            state.copy(
                carts = state.carts + (restaurantId to updatedCart),
            )
        }
    }

    fun removeItem(cartItemId: String) {
        _uiState.update { state ->
            val updatedCarts = state.carts.mapValues { (_, cart) ->
                val updatedItems = cart.items.filter { it.id != cartItemId }
                cart.copy(items = updatedItems)
            }.filterValues { it.items.isNotEmpty() }
            state.copy(carts = updatedCarts)
        }
    }

    fun updateQuantity(cartItemId: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(cartItemId)
            return
        }
        val capped = newQuantity.coerceAtMost(99)
        _uiState.update { state ->
            val updatedCarts = state.carts.mapValues { (_, cart) ->
                val updatedItems = cart.items.map { item ->
                    if (item.id == cartItemId) item.copy(quantity = capped) else item
                }
                cart.copy(items = updatedItems)
            }
            state.copy(carts = updatedCarts)
        }
    }

    fun updateTip(amount: Int) {
        _uiState.update { it.copy(tip = amount.coerceAtMost(it.subtotal)) }
    }

    /** Pass `null` for ASAP, or a local-time instant for a scheduled delivery. */
    fun updateScheduledFor(value: java.time.LocalDateTime?) {
        _uiState.update { it.copy(scheduledFor = value) }
    }

    /** Clear all carts. */
    fun clearCart() {
        _uiState.value = CartUiState()
    }

    /** Clear a specific restaurant's cart. */
    fun clearCartForRestaurant(restaurantId: String) {
        _uiState.update { state ->
            state.copy(carts = state.carts - restaurantId)
        }
    }

    /** Set which restaurant cart is active for checkout / detail viewing. */
    fun setActiveRestaurant(restaurantId: String) {
        _uiState.update { it.copy(activeRestaurantId = restaurantId) }
    }

    /**
     * Attach a deal to the cart for the deal's restaurant. Creates an empty
     * cart if none exists yet so the banner shows on the restaurant page
     * even before any items are added. One deal per cart — replaces any
     * previously-applied deal for that restaurant.
     */
    fun applyDeal(deal: Deal) {
        if (deal.restaurantId.isBlank()) return
        _uiState.update { state ->
            val existing = state.carts[deal.restaurantId] ?: Cart(
                restaurantId = deal.restaurantId,
                restaurantName = deal.restaurantName,
                restaurantImageUrl = deal.restaurantImageUrl.takeIf { it.isNotBlank() },
            )
            state.copy(
                carts = state.carts + (deal.restaurantId to existing.copy(appliedDeal = deal)),
            )
        }
    }

    /** Remove the deal from the given restaurant's cart (keeps the items). */
    fun removeDeal(restaurantId: String) {
        _uiState.update { state ->
            val cart = state.carts[restaurantId] ?: return@update state
            state.copy(carts = state.carts + (restaurantId to cart.copy(appliedDeal = null)))
        }
    }

    fun setPendingDealItem(deal: Deal) {
        _uiState.update { it.copy(pendingDealItem = deal) }
    }

    fun clearPendingDealItem() {
        _uiState.update { it.copy(pendingDealItem = null) }
    }

    fun placeOrder(
        deliveryAddress: String,
        deliveryLat: Double,
        deliveryLng: Double,
        paymentIntentId: String,
    ) {
        val state = _uiState.value
        if (state.isEmpty) return

        // Scheduled deliveries need a timezone-aware RFC-3339 string; the
        // LocalDateTime the user picked is in their local zone, so attach the
        // system ZoneOffset before formatting. Backend's CreateOrderRequest
        // decodes it as `time.Time`.
        val scheduledFor = state.scheduledFor?.let { local ->
            val zone = java.time.ZoneId.systemDefault()
            local.atZone(zone).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        val request = CreateOrderRequest(
            restaurantId = state.cart.restaurantId,
            deliveryAddress = deliveryAddress,
            deliveryLat = deliveryLat,
            deliveryLng = deliveryLng,
            paymentIntentId = paymentIntentId,
            tip = state.tip,
            scheduledFor = scheduledFor,
            appliedDealId = state.cart.appliedDeal?.id,
        )

        viewModelScope.launch {
            repository.createOrder(request).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isPlacingOrder = true, error = null) }
                    }
                    is Resource.Success -> {
                        val placedRestaurantId = state.cart.restaurantId
                        _uiState.update {
                            it.copy(
                                isPlacingOrder = false,
                                orderPlaced = result.data,
                                carts = it.carts - placedRestaurantId,
                                activeRestaurantId = null,
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(isPlacingOrder = false, error = result.message) }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearOrderPlaced() {
        _uiState.update { it.copy(orderPlaced = null) }
    }
}
