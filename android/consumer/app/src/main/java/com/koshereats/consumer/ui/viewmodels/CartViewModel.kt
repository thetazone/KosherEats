package com.koshereats.consumer.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.koshereats.consumer.data.models.*
import com.koshereats.consumer.data.session.SessionManager
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val tip: Int = 0,
    /**
     * Scheduled delivery time. `null` = deliver ASAP (the default). Any
     * future value gets serialized to RFC-3339 and sent on CreateOrderRequest;
     * the backend flags the order `scheduled` and the dispatcher promotes it
     * to `pending` as the window approaches.
     */
    val scheduledFor: java.time.LocalDateTime? = null,
    val pendingDealItem: Deal? = null,
    val error: String? = null,
) {
    /** The currently-active single restaurant cart (for checkout / detail view). */
    val cart: Cart get() = activeRestaurantId?.let { carts[it] } ?: carts.values.firstOrNull() ?: Cart()

    val subtotal: Int get() = cart.subtotal
    val discount: Int get() = cart.discount
    val total: Int get() = cart.discountedSubtotal + tip
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

private data class CartSnapshot(
    val carts: Map<String, Cart>,
    val activeRestaurantId: String?,
)

@HiltViewModel
class CartViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    private companion object {
        val KEY_CART_SNAPSHOT = stringPreferencesKey("cart_vm_snapshot")
    }

    private val gson = Gson()
    private val snapshotType = object : TypeToken<CartSnapshot>() {}.type

    private val _uiState = MutableStateFlow(CartUiState())
    val uiState: StateFlow<CartUiState> = _uiState.asStateFlow()

    private val logoutJob: Job = viewModelScope.launch {
        sessionManager.logoutEvent.collect {
            _uiState.value = CartUiState()
            try { dataStore.edit { it.remove(KEY_CART_SNAPSHOT) } } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    init {
        viewModelScope.launch {
            try {
                val json = dataStore.data.first()[KEY_CART_SNAPSHOT]
                if (!json.isNullOrEmpty()) {
                    val snap: CartSnapshot? = try {
                        gson.fromJson(json, snapshotType)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("CartViewModel", "Failed to parse persisted cart snapshot", e)
                        null
                    }
                    snap?.let { s ->
                        // Filter out any restaurant carts with zero items (e.g. empty shells
                        // left by applyDeal()) so the restored cart only contains real items.
                        val nonEmptyCarts = s.carts.filterValues { it.items.isNotEmpty() }
                        // Merge: snapshot is the base; any in-memory carts (added before
                        // restore finished) win for the same restaurant key so they are
                        // never silently overwritten by a stale snapshot.
                        _uiState.update { current ->
                            current.copy(
                                carts = nonEmptyCarts + current.carts,
                                activeRestaurantId = current.activeRestaurantId
                                    ?: s.activeRestaurantId?.takeIf { it in nonEmptyCarts },
                            )
                        }
                    }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    private val persistMutex = Mutex()

    private fun persistSnapshot() {
        viewModelScope.launch {
            try {
                persistMutex.withLock {
                    val s = _uiState.value
                    // Exclude empty carts (e.g. deal-only shells) so stale deals
                    // don't survive across app sessions.
                    val cartsToSave = s.carts.filterValues { it.items.isNotEmpty() }
                    val json = gson.toJson(CartSnapshot(cartsToSave, s.activeRestaurantId))
                    dataStore.edit { it[KEY_CART_SNAPSHOT] = json }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
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
                specialInstructions = specialInstructions?.trim()?.take(500)?.takeIf { it.isNotBlank() },
            )

            val existingIndex = if (selectedCustomizations.isEmpty()) {
                currentCart.items.indexOfFirst {
                    it.menuItem.id == menuItem.id && it.selectedCustomizations.isEmpty()
                }
            } else -1

            val updatedItems = if (existingIndex >= 0) {
                currentCart.items.toMutableList().apply {
                    val existing = this[existingIndex]
                    this[existingIndex] = existing.copy(quantity = minOf(existing.quantity + quantity, 99))
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
        persistSnapshot()
    }

    fun removeItem(cartItemId: String) {
        _uiState.update { state ->
            val updatedCarts = state.carts.mapValues { (_, cart) ->
                val updatedItems = cart.items.filter { it.id != cartItemId }
                cart.copy(items = updatedItems)
            }.filterValues { it.items.isNotEmpty() }
            val newActiveId = state.activeRestaurantId?.takeIf { updatedCarts.containsKey(it) }
            state.copy(carts = updatedCarts, activeRestaurantId = newActiveId)
        }
        persistSnapshot()
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
        persistSnapshot()
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
        persistSnapshot()
    }

    /** Clear a specific restaurant's cart. */
    fun clearCartForRestaurant(restaurantId: String) {
        _uiState.update { state ->
            val newActiveId = state.activeRestaurantId?.takeIf { it != restaurantId }
            // Reset tip and scheduled time when the active cart is removed so stale
            // checkout context from this restaurant doesn't bleed into the next one.
            val resetCheckout = state.activeRestaurantId == restaurantId
            state.copy(
                carts = state.carts - restaurantId,
                activeRestaurantId = newActiveId,
                tip = if (resetCheckout) 0 else state.tip,
                scheduledFor = if (resetCheckout) null else state.scheduledFor,
            )
        }
        persistSnapshot()
    }

    /** Set which restaurant cart is active for checkout / detail viewing. */
    fun setActiveRestaurant(restaurantId: String) {
        _uiState.update { it.copy(activeRestaurantId = restaurantId) }
        persistSnapshot()
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
        persistSnapshot()
    }

    /** Remove the deal from the given restaurant's cart (keeps the items). */
    fun removeDeal(restaurantId: String) {
        _uiState.update { state ->
            val cart = state.carts[restaurantId] ?: return@update state
            state.copy(carts = state.carts + (restaurantId to cart.copy(appliedDeal = null)))
        }
        persistSnapshot()
    }

    fun setPendingDealItem(deal: Deal) {
        _uiState.update { it.copy(pendingDealItem = deal) }
    }

    fun clearPendingDealItem() {
        _uiState.update { it.copy(pendingDealItem = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

}
