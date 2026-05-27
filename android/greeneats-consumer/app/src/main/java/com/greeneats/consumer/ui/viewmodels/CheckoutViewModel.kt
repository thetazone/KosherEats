package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.Address
import com.greeneats.consumer.data.models.DeliveryQuoteRequest
import com.greeneats.consumer.data.models.formatted
import com.greeneats.consumer.data.models.formatPrice
import com.greeneats.consumer.data.models.AddToCartRequest
import com.greeneats.consumer.data.models.CartItem
import com.greeneats.consumer.data.models.CreateOrderRequest
import com.greeneats.consumer.data.models.Order
import com.greeneats.consumer.data.models.PaymentSheetBundle
import com.greeneats.consumer.data.models.PaymentSheetRequest
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

sealed interface TipChoice {
    data object None : TipChoice
    /** Tip percentage stored as basis points (e.g. 1500 = 15%). */
    data class Percent(val basisPoints: Int) : TipChoice
    data object Custom : TipChoice

    companion object {
        val presets: List<TipChoice> = listOf(None, Percent(1500), Percent(1800), Percent(2000), Custom)
    }

    fun label(subtotalCents: Int): String = when (this) {
        None -> "None"
        is Percent -> {
            val cents = (subtotalCents.toLong() * basisPoints / 10_000).toInt()
            "${basisPoints / 100}%\n${cents.formatPrice()}"
        }
        Custom -> "Custom"
    }
}

data class CheckoutUiState(
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    /** "delivery" or "pickup". Pickup skips address, courier tip, and delivery fee. */
    val fulfillmentType: String = "delivery",
    val tipChoice: TipChoice = TipChoice.Percent(1800),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private val _events = Channel<CheckoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var refreshBundleJob: Job? = null
    private var _bootstrapped = false
    private var _restaurantId: String = ""
    private var _appliedDealId: String? = null

    fun bootstrap(localCart: List<CartItem>, restaurantId: String, appliedDealId: String? = null) {
        if (_bootstrapped) return
        _bootstrapped = true
        _restaurantId = restaurantId
        _appliedDealId = appliedDealId
        viewModelScope.launch {
            loadAddresses()
            syncLocalCartToServer(localCart, restaurantId)
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
                    throw Exception("${ERR_CART_ADD_ITEM_FAILED}: ${resp.code()} ${resp.message()}")
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "$ERR_PREPARE_CART_FAILED: ${e.localizedMessage}") }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
        viewModelScope.launch {
            fetchDeliveryQuote(address)
            refreshBundle()
        }
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
        viewModelScope.launch { refreshBundle() }
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
                } else {
                    _uiState.update { it.copy(errorMessage = ERR_SAVE_ADDRESS_FAILED) }
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
        viewModelScope.launch { refreshBundle() }
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

    /**
     * Stores a scheduled delivery/pickup time. The [LocalDateTime] is assumed
     * to be in the device's system default timezone — [finalizeOrder] converts
     * it to an ISO offset string via [ZoneId.systemDefault] before sending to
     * the backend. Null = ASAP.
     *
     * Validates that the chosen time is in the future (by at least 5 minutes)
     * using system-default zone so daylight-saving transitions don't produce
     * a time that's already in the past.
     */
    fun updateScheduledFor(value: LocalDateTime?) {
        if (value != null) {
            val zoned = value.atZone(ZoneId.systemDefault())
            val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
            if (zoned.isBefore(now.plusMinutes(5))) {
                _uiState.update { it.copy(errorMessage = ERR_SCHEDULE_TOO_SOON) }
                return
            }
        }
        _uiState.update { it.copy(scheduledFor = value, errorMessage = null) }
    }

    private fun currentTipCents(): Int {
        val state = _uiState.value
        val subtotal = state.bundle?.subtotal ?: 0
        return when (val choice = state.tipChoice) {
            TipChoice.None -> 0
            is TipChoice.Percent -> (subtotal.toLong() * choice.basisPoints / 10_000).toInt()
            TipChoice.Custom -> {
                val dollars = (state.customTipText.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                (dollars * 100).toInt().coerceAtMost(MAX_TIP_CENTS)
            }
        }
    }

    private suspend fun refreshBundle() {
        _uiState.update { it.copy(isLoadingBundle = true, errorMessage = null) }
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
                        errorMessage = "$ERR_PRICE_ORDER_FAILED (${resp.code()})",
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
        val bundle = _uiState.value.bundle ?: return
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
            _uiState.update { it.copy(errorMessage = error) }
            return
        }
        val bundle = _uiState.value.bundle ?: return
        val intentId = extractIntentId(bundle.paymentIntentSecret)
        if (intentId == null) {
            _uiState.update { it.copy(errorMessage = ERR_MALFORMED_INTENT) }
            return
        }
        finalizeOrder(intentId)
    }

    private fun finalizeOrder(paymentIntentId: String) {
        val state = _uiState.value
        val isPickup = state.fulfillmentType == "pickup"
        val address = state.selectedAddress
        if (!isPickup && address == null) {
            _uiState.update { it.copy(errorMessage = ERR_SELECT_ADDRESS) }
            return
        }
        val bundle = state.bundle ?: return

        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
        viewModelScope.launch {
            val scheduledFor = state.scheduledFor?.let { local ->
                local.atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            // Bind a non-null local so we never force-unwrap. For delivery
            // the null guard already returned above; for pickup we supply a
            // dummy that is never read because the `if(isPickup)` branches
            // short-circuit to defaults.
            val addr = address ?: if (isPickup) {
                Address()
            } else {
                _uiState.update { it.copy(isProcessing = false, errorMessage = ERR_SELECT_ADDRESS) }
                return@launch
            }
            try {
                val resp = api.createOrder(
                    CreateOrderRequest(
                        restaurantId = _restaurantId,
                        deliveryAddress = if (isPickup) "" else "${addr.streetAddress}, ${addr.city}, ${addr.state} ${addr.zipCode}",
                        deliveryLat = if (isPickup) 0.0 else addr.latitude,
                        deliveryLng = if (isPickup) 0.0 else addr.longitude,
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
                        _uiState.update { it.copy(isProcessing = false, errorMessage = "$ERR_ORDER_FAILED (409)") }
                    }
                } else {
                    _uiState.update {
                        it.copy(isProcessing = false, errorMessage = "$ERR_ORDER_FAILED (${resp.code()})")
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
    // want the `pi_xxxxxx` portion for our DB. Returns null on malformed input
    // so the caller can surface an error rather than send garbage to the backend.
    // Mirrors iOS CheckoutViewModel.extractIntentId(from:) which throws on bad format.
    private fun extractIntentId(clientSecret: String): String? {
        if (!clientSecret.startsWith("pi_") || !clientSecret.contains("_secret_")) {
            android.util.Log.e(
                "CheckoutViewModel",
                "extractIntentId: malformed client secret – expected pi_<id>_secret_<key>, got: ${clientSecret.take(12)}..."
            )
            return null
        }
        val id = clientSecret.substringBefore("_secret_")
        if (id.isEmpty()) return null
        return id
    }

    override fun onCleared() {
        refreshBundleJob?.cancel()
        refreshBundleJob = null
        super.onCleared()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        /** Maximum custom tip in cents ($500). Matches iOS CheckoutViewModel.maxTipCents
         *  and the backend cap in orders.go / payments.go (tip <= subtotal). */
        const val MAX_TIP_CENTS = 50_000

        const val ERR_PRICE_ORDER_FAILED = "Failed to price order"
        const val ERR_SELECT_ADDRESS = "Select a delivery address"
        const val ERR_ORDER_FAILED = "Order failed"
        const val ERR_SAVE_ADDRESS_FAILED = "Could not save address"
        const val ERR_PREPARE_CART_FAILED = "Failed to prepare cart"
        const val ERR_CART_ADD_ITEM_FAILED = "Failed to add item to cart"
        const val ERR_SCHEDULE_TOO_SOON = "Scheduled time must be at least 5 minutes from now"
        const val ERR_MALFORMED_INTENT = "Malformed payment intent secret"
    }
}
