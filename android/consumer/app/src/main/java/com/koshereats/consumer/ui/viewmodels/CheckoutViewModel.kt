package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.Address
import com.koshereats.consumer.data.models.DeliveryQuoteRequest
import com.koshereats.consumer.data.models.formatted
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.AddToCartRequest
import com.koshereats.consumer.data.models.CartItem
import com.koshereats.consumer.data.models.CreateOrderRequest
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.PaymentSheetBundle
import com.koshereats.consumer.data.models.PaymentSheetRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

sealed interface TipChoice {
    data object None : TipChoice
    data class Percent(val fraction: Double) : TipChoice
    data object Custom : TipChoice

    companion object {
        val presets: List<TipChoice> = listOf(None, Percent(0.15), Percent(0.18), Percent(0.20), Custom)
    }

    fun label(subtotalCents: Int): String = when (this) {
        None -> "None"
        is Percent -> {
            val cents = (subtotalCents * fraction).toInt()
            "${(fraction * 100).toInt()}%\n${cents.formatPrice()}"
        }
        Custom -> "Custom"
    }
}

data class CheckoutUiState(
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    /** "delivery" or "pickup". Pickup skips address, courier tip, and delivery fee. */
    val fulfillmentType: String = "delivery",
    val tipChoice: TipChoice = TipChoice.Percent(0.18),
    val customTipText: String = "",
    val scheduledFor: LocalDateTime? = null,
    val bundle: PaymentSheetBundle? = null,
    val isLoadingBundle: Boolean = false,
    /** Quick delivery-fee preview from /delivery-quote — shown while bundle is loading. */
    val deliveryQuoteCents: Int? = null,
    val deliveryQuoteMinutes: Int? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val placedOrder: Order? = null,
)

sealed interface CheckoutEvent {
    data class PresentPaymentSheet(val bundle: PaymentSheetBundle) : CheckoutEvent
}

/**
 * Owns checkout state: address, tip, scheduled time, Stripe bundle.
 *
 * Strategy: Android's cart is local-only state, but `/payments/intent` reads
 * the authoritative cart from the DB. On init we sync local cart → server
 * (clear, then re-add each item) so totals match what Stripe will charge.
 *
 * PaymentSheet presentation lives in the composable (needs Activity context);
 * VM emits a `PresentPaymentSheet` event and the composable calls into the
 * Stripe SDK, then funnels the result back via `onPaymentResult`.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val api: ApiService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private companion object {
        const val KEY_RESTAURANT_ID = "checkout_restaurant_id"
        const val KEY_DEAL_ID = "checkout_deal_id"
    }

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private val _events = Channel<CheckoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var refreshBundleJob: Job? = null
    private var _bootstrapped = false
    private var _restaurantId: String = ""
    private var _appliedDealId: String? = null
    private var _localSubtotalCents: Int = 0

    fun bootstrap(localCart: List<CartItem>, restaurantId: String, appliedDealId: String? = null) {
        // Recover IDs from SavedStateHandle when the live args are empty (process-death recovery).
        val effectiveRestaurantId = restaurantId.ifEmpty {
            savedStateHandle.get<String>(KEY_RESTAURANT_ID).orEmpty()
        }
        val effectiveDealId = if (restaurantId.isNotEmpty()) appliedDealId
                              else savedStateHandle.get<String>(KEY_DEAL_ID)?.ifEmpty { null }

        // Idempotent: skip if already bootstrapped with the same valid restaurant.
        // Allow re-bootstrap when upgrading from empty restaurantId (cart rehydrated after process death).
        if (_bootstrapped) {
            if (_uiState.value.isProcessing || effectiveRestaurantId == _restaurantId) return
            if (effectiveRestaurantId.isEmpty()) return
        }
        _bootstrapped = true
        _localSubtotalCents = localCart.sumOf { it.totalPrice }

        // Always persist so process-death recovery can restore the IDs on the next launch.
        savedStateHandle[KEY_RESTAURANT_ID] = effectiveRestaurantId
        savedStateHandle[KEY_DEAL_ID] = effectiveDealId ?: ""

        _restaurantId = effectiveRestaurantId
        _appliedDealId = effectiveDealId
        viewModelScope.launch {
            loadAddresses()
            if (localCart.isNotEmpty()) {
                syncLocalCartToServer(localCart, effectiveRestaurantId)
            }
            // When localCart is empty (process-death recovery), the server cart
            // is already in the right state — skip sync and load the bundle directly.
            refreshBundle()
        }
    }

    private suspend fun loadAddresses() {
        try {
            val resp = api.getAddresses()
            if (resp.isSuccessful) {
                val list = resp.body().orEmpty()
                _uiState.update { state ->
                    state.copy(
                        addresses = list,
                        selectedAddress = state.selectedAddress
                            ?: list.firstOrNull { it.isDefault }
                            ?: list.firstOrNull(),
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = e.localizedMessage) }
        }
    }

    /**
     * Push local cart state up to the server so /payments/intent reads the
     * right subtotal. Clears first to avoid merging into whatever was there
     * from a previous session.
     */
    private suspend fun syncLocalCartToServer(localCart: List<CartItem>, restaurantId: String) {
        if (localCart.isEmpty()) return
        try {
            api.clearServerCart()
            for (item in localCart) {
                val modifierIds = item.selectedCustomizations
                    .flatMap { it.selectedOptions }
                    .map { it.id }
                    .filter { it.isNotEmpty() }
                val resp = api.addToCart(
                    AddToCartRequest(
                        menuItemId = item.menuItem.id,
                        restaurantId = restaurantId,
                        quantity = item.quantity,
                        notes = item.specialInstructions.orEmpty(),
                        modifierIds = modifierIds,
                    ),
                )
                if (!resp.isSuccessful) {
                    throw Exception("Failed to add item to cart: ${resp.code()} ${resp.message()}")
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to prepare cart: ${e.localizedMessage}") }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
        viewModelScope.launch { fetchDeliveryQuote(address) }
        launchRefreshBundle()
    }

    private suspend fun fetchDeliveryQuote(address: Address) {
        if (_restaurantId.isEmpty()) return
        if (_uiState.value.fulfillmentType == "pickup") {
            _uiState.update { it.copy(deliveryQuoteCents = 0, deliveryQuoteMinutes = null) }
            return
        }
        try {
            val resp = api.getDeliveryQuote(
                DeliveryQuoteRequest(
                    restaurantId = _restaurantId,
                    deliveryLat = address.latitude,
                    deliveryLng = address.longitude,
                    deliveryAddress = address.formatted,
                )
            )
            if (resp.isSuccessful) {
                val q = resp.body()
                _uiState.update {
                    it.copy(
                        deliveryQuoteCents = q?.deliveryFeeCents,
                        deliveryQuoteMinutes = q?.estMinutes,
                    )
                }
            }
        } catch (_: Exception) {
            // Preview is best-effort — bundle has authoritative fees.
        }
    }

    fun setFulfillmentType(type: String) {
        if (type != "delivery" && type != "pickup") return
        if (_uiState.value.fulfillmentType == type) return
        _uiState.update { it.copy(fulfillmentType = type) }
        launchRefreshBundle()
    }

    fun addAddress(address: Address) {
        viewModelScope.launch {
            try {
                val resp = api.addAddress(address)
                if (resp.isSuccessful) {
                    val saved = resp.body() ?: return@launch
                    _uiState.update { state ->
                        state.copy(
                            addresses = state.addresses + saved,
                            selectedAddress = saved,
                        )
                    }
                    launch { fetchDeliveryQuote(saved) }
                    launchRefreshBundle()
                } else {
                    val serverMsg = try {
                        val body = resp.errorBody()?.string().orEmpty()
                        com.google.gson.JsonParser.parseString(body).asJsonObject
                            .get("error")?.asString
                    } catch (_: Exception) { null }
                    val msg = when {
                        resp.code() == 409 -> serverMsg ?: "That address already exists"
                        resp.code() == 422 -> serverMsg ?: "Invalid address — please check the details"
                        else -> serverMsg ?: "Could not save address"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage) }
            }
        }
    }

    fun selectTip(choice: TipChoice) {
        _uiState.update { state ->
            state.copy(
                tipChoice = choice,
                customTipText = if (choice is TipChoice.Custom) state.customTipText else "",
            )
        }
        launchRefreshBundle()
    }

    fun updateCustomTip(value: String) {
        _uiState.update { it.copy(customTipText = value) }
        if (_uiState.value.tipChoice is TipChoice.Custom) {
            refreshBundleJob?.cancel()
            refreshBundleJob = viewModelScope.launch {
                delay(400)
                refreshBundle()
            }
        }
    }

    fun updateScheduledFor(value: LocalDateTime?) {
        _uiState.update { it.copy(scheduledFor = value) }
    }

    private fun currentTipCents(): Int {
        val state = _uiState.value
        val subtotal = state.bundle?.subtotal ?: _localSubtotalCents
        return when (val choice = state.tipChoice) {
            TipChoice.None -> 0
            is TipChoice.Percent -> (subtotal * choice.fraction).roundToInt()
            TipChoice.Custom -> {
                val dollars = state.customTipText.toDoubleOrNull() ?: 0.0
                (dollars * 100).roundToInt()
            }
        }
    }

    private fun launchRefreshBundle() {
        refreshBundleJob?.cancel()
        refreshBundleJob = viewModelScope.launch { refreshBundle() }
    }

    private suspend fun refreshBundle() {
        _uiState.update { it.copy(isLoadingBundle = true, bundle = null, errorMessage = null) }
        try {
            val address = _uiState.value.selectedAddress
            val state = _uiState.value
            val resp = api.createPaymentSheet(
                PaymentSheetRequest(
                    tip = if (state.fulfillmentType == "pickup") 0 else currentTipCents(),
                    restaurantId = _restaurantId,
                    deliveryAddress = if (state.fulfillmentType == "pickup") "" else address?.formatted.orEmpty(),
                    fulfillmentType = state.fulfillmentType,
                    appliedDealId = _appliedDealId,
                ),
            )
            if (resp.isSuccessful) {
                _uiState.update { it.copy(bundle = resp.body(), isLoadingBundle = false) }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingBundle = false,
                        errorMessage = "Failed to price order (${resp.code()})",
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoadingBundle = false, errorMessage = e.localizedMessage) }
        }
    }

    /** Called by the composable when the user taps Pay. Asks the composable
     *  (via [events]) to present Stripe's PaymentSheet. If backend is in stub
     *  mode (no Stripe key), skip the sheet and finalize directly. */
    fun onPayTapped() {
        if (_uiState.value.isProcessing) return
        val bundle = _uiState.value.bundle ?: return
        _uiState.update { it.copy(isProcessing = true) }
        if (bundle.isStub) {
            finalizeOrder(paymentIntentId = "stub_intent")
            return
        }
        viewModelScope.launch { _events.send(CheckoutEvent.PresentPaymentSheet(bundle)) }
    }

    /** Called by the composable after PaymentSheet closes. `success` means the
     *  user completed payment; false means cancel or failure. */
    fun onPaymentResult(success: Boolean, error: String? = null) {
        if (!success) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = error) }
            return
        }
        val bundle = _uiState.value.bundle ?: return
        val intentId = extractIntentId(bundle.paymentIntentSecret)
        if (intentId == null) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Unexpected Stripe response — please try again") }
            return
        }
        finalizeOrder(intentId)
    }

    private fun finalizeOrder(paymentIntentId: String) {
        val state = _uiState.value
        val isPickup = state.fulfillmentType == "pickup"
        val address = state.selectedAddress
        if (!isPickup && address == null) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Select a delivery address") }
            return
        }
        if (!isPickup && address!!.latitude == 0.0 && address.longitude == 0.0) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Address location could not be verified. Please remove and re-add this address.") }
            return
        }
        val bundle = state.bundle ?: run { _uiState.update { it.copy(isProcessing = false) }; return }

        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
        viewModelScope.launch {
            val scheduledFor = state.scheduledFor?.let { local ->
                local.atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            try {
                val resp = api.createOrder(
                    CreateOrderRequest(
                        restaurantId = _restaurantId,
                        deliveryAddress = if (isPickup) "" else "${address!!.streetAddress}, ${address.city}, ${address.state} ${address.zipCode}",
                        deliveryLat = if (isPickup) 0.0 else address!!.latitude,
                        deliveryLng = if (isPickup) 0.0 else address!!.longitude,
                        paymentIntentId = paymentIntentId,
                        tip = bundle.tip,
                        scheduledFor = scheduledFor,
                        fulfillmentType = state.fulfillmentType,
                        appliedDealId = _appliedDealId,
                    ),
                )
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(isProcessing = false, placedOrder = resp.body())
                    }
                } else if (resp.code() == 409) {
                    // Duplicate payment_intent_id — order already exists; fetch it
                    val existing = fetchMostRecentOrder()
                    if (existing != null) {
                        _uiState.update { it.copy(isProcessing = false, placedOrder = existing) }
                    } else {
                        _uiState.update { it.copy(isProcessing = false, errorMessage = "Order failed (409)") }
                    }
                } else {
                    _uiState.update {
                        it.copy(isProcessing = false, errorMessage = "Order failed (${resp.code()})")
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    private suspend fun fetchMostRecentOrder(): Order? = try {
        val r = api.getOrders(page = 1)
        if (r.isSuccessful) r.body()?.firstOrNull() else null
    } catch (e: Exception) {
        null
    }

    // PaymentIntent client secrets look like `pi_xxxxxx_secret_yyyy`. We only
    // want the `pi_xxxxxx` portion for our DB. Returns null if the delimiter is
    // absent so callers can surface a clear error instead of posting a garbage ID.
    private fun extractIntentId(clientSecret: String): String? =
        if (clientSecret.contains("_secret_")) clientSecret.substringBefore("_secret_") else null

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
