package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.Address
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
    val tipChoice: TipChoice = TipChoice.Percent(0.18),
    val customTipText: String = "",
    val scheduledFor: LocalDateTime? = null,
    val bundle: PaymentSheetBundle? = null,
    val isLoadingBundle: Boolean = false,
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

    fun bootstrap(localCart: List<CartItem>, restaurantId: String) {
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
                    throw Exception("Failed to add item to cart: ${resp.code()} ${resp.message()}")
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to prepare cart: ${e.localizedMessage}") }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
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
                    _uiState.update { it.copy(errorMessage = "Could not save address") }
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

    fun updateScheduledFor(value: LocalDateTime?) {
        _uiState.update { it.copy(scheduledFor = value) }
    }

    private fun currentTipCents(): Int {
        val state = _uiState.value
        val subtotal = state.bundle?.subtotal ?: 0
        return when (val choice = state.tipChoice) {
            TipChoice.None -> 0
            is TipChoice.Percent -> (subtotal * choice.fraction).toInt()
            TipChoice.Custom -> {
                val dollars = state.customTipText.toDoubleOrNull() ?: 0.0
                (dollars * 100).toInt()
            }
        }
    }

    private suspend fun refreshBundle() {
        _uiState.update { it.copy(isLoadingBundle = true, errorMessage = null) }
        try {
            val resp = api.createPaymentSheet(PaymentSheetRequest(tip = currentTipCents()))
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
        finalizeOrder(intentId)
    }

    private fun finalizeOrder(paymentIntentId: String) {
        val state = _uiState.value
        val address = state.selectedAddress ?: run {
            _uiState.update { it.copy(errorMessage = "Select a delivery address") }
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
            try {
                val resp = api.createOrder(
                    CreateOrderRequest(
                        deliveryAddress = "${address.streetAddress}, ${address.city}, ${address.state} ${address.zipCode}",
                        deliveryLat = address.latitude,
                        deliveryLng = address.longitude,
                        paymentIntentId = paymentIntentId,
                        tip = bundle.tip,
                        scheduledFor = scheduledFor,
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
    // want the `pi_xxxxxx` portion for our DB.
    private fun extractIntentId(clientSecret: String): String =
        clientSecret.substringBefore("_secret_", clientSecret)

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
