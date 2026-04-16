package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.models.*
import com.koshereats.consumer.data.repository.Resource
import com.koshereats.consumer.data.repository.RestaurantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject

data class CartUiState(
    val cart: Cart = Cart(),
    val deliveryFee: Int = 399,
    val serviceFee: Int = 249,
    val taxRate: Double = 0.08875,
    val tip: Int = 0,
    /**
     * Scheduled delivery time. `null` = deliver ASAP (the default). Any
     * future value gets serialized to RFC-3339 and sent on CreateOrderRequest;
     * the backend flags the order `scheduled` and the dispatcher promotes it
     * to `pending` as the window approaches.
     */
    val scheduledFor: java.time.LocalDateTime? = null,
    val isPlacingOrder: Boolean = false,
    val orderPlaced: Order? = null,
    val error: String? = null,
) {
    val subtotal: Int get() = cart.subtotal
    val tax: Int get() = (subtotal * taxRate).roundToInt()
    val total: Int get() = subtotal + deliveryFee + serviceFee + tax + tip
    val isEmpty: Boolean get() = cart.items.isEmpty()
    val itemCount: Int get() = cart.itemCount
}

@HiltViewModel
class CartViewModel @Inject constructor(
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    fun addItem(menuItem: MenuItem, restaurantId: String, restaurantName: String, quantity: Int = 1) {
        _uiState.update { state ->
            val currentCart = state.cart

            // If adding from a different restaurant, clear the cart first
            if (currentCart.items.isNotEmpty() && currentCart.restaurantId != restaurantId) {
                val newCart = Cart(
                    restaurantId = restaurantId,
                    restaurantName = restaurantName,
                    items = listOf(
                        CartItem(
                            id = UUID.randomUUID().toString(),
                            menuItem = menuItem,
                            quantity = quantity,
                        )
                    ),
                )
                state.copy(cart = newCart)
            } else {
                // Check if item already exists in cart
                val existingIndex = currentCart.items.indexOfFirst { it.menuItem.id == menuItem.id }
                val updatedItems = if (existingIndex >= 0) {
                    currentCart.items.toMutableList().apply {
                        val existing = this[existingIndex]
                        this[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
                    }
                } else {
                    currentCart.items + CartItem(
                        id = UUID.randomUUID().toString(),
                        menuItem = menuItem,
                        quantity = quantity,
                    )
                }

                state.copy(
                    cart = currentCart.copy(
                        restaurantId = restaurantId,
                        restaurantName = restaurantName,
                        items = updatedItems,
                    )
                )
            }
        }
    }

    fun removeItem(cartItemId: String) {
        _uiState.update { state ->
            val updatedItems = state.cart.items.filter { it.id != cartItemId }
            state.copy(cart = state.cart.copy(items = updatedItems))
        }
    }

    fun updateQuantity(cartItemId: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(cartItemId)
            return
        }
        _uiState.update { state ->
            val updatedItems = state.cart.items.map { item ->
                if (item.id == cartItemId) item.copy(quantity = newQuantity) else item
            }
            state.copy(cart = state.cart.copy(items = updatedItems))
        }
    }

    fun updateTip(amount: Int) {
        _uiState.update { it.copy(tip = amount) }
    }

    /** Pass `null` for ASAP, or a local-time instant for a scheduled delivery. */
    fun updateScheduledFor(value: java.time.LocalDateTime?) {
        _uiState.update { it.copy(scheduledFor = value) }
    }

    fun clearCart() {
        _uiState.update { it.copy(cart = Cart()) }
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
        )

        viewModelScope.launch {
            repository.createOrder(request).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isPlacingOrder = true, error = null) }
                    }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isPlacingOrder = false,
                                orderPlaced = result.data,
                                cart = Cart(),
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
