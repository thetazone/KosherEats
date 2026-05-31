package com.koshereats.consumer.ui.viewmodels

import android.util.Log
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
import com.koshereats.consumer.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** Non-null when the composable should present Stripe's PaymentSheet. Cleared via consumePendingPaymentSheet(). */
    val pendingPaymentSheet: PaymentSheetBundle? = null,
)

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

    private var refreshBundleJob: Job? = null
    private var deliveryQuoteJob: Job? = null
    private var _bootstrapped = false
    private var _restaurantId: String = ""
    private var _appliedDealId: String? = null
    private var _localSubtotalCents: Int = 0

    // Idempotency: track which payment intent IDs have already been finalized
    // so a duplicate PaymentSheetResult.Completed cannot create a second order.
    private val finalizedIntentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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
        // Hard guard: a blank restaurant ID would let the user place an order against the
        // backend's "first owned restaurant" fallback, which is not what they were viewing.
        if (effectiveRestaurantId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Could not identify the restaurant. Please return to the cart and try again.") }
            return
        }
        // Each new bootstrap is a fresh checkout session — reset idempotency tracking so the
        // user can retry after an error without being blocked by stale finalized-intent IDs.
        finalizedIntentIds.clear()
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
            // Use launchRefreshBundle so any concurrent user-triggered refresh can
            // cancel this one (same pattern as selectTip / selectAddress / etc.).
            launchRefreshBundle()
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
            } else {
                Log.e("CheckoutViewModel", "loadAddresses failed: HTTP ${resp.code()}")
                _uiState.update { it.copy(errorMessage = "Could not load saved addresses (${resp.code()}) — you can still add one below") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("CheckoutViewModel", "loadAddresses exception", e)
            _uiState.update { it.copy(errorMessage = "Network error. Please check your connection.") }
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
            coroutineScope {
                localCart.map { item ->
                    async {
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
                }.awaitAll()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Roll back: clear the server cart so /payments/intent cannot read a partial subtotal.
            try {
                api.clearServerCart()
            } catch (rollbackErr: Exception) {
                if (rollbackErr is CancellationException) throw rollbackErr
                android.util.Log.w("CheckoutViewModel", "Server cart rollback failed after partial sync", rollbackErr)
            }
            _uiState.update { it.copy(errorMessage = "Failed to prepare cart. Please try again.") }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
        deliveryQuoteJob?.cancel()
        deliveryQuoteJob = viewModelScope.launch { fetchDeliveryQuote(address) }
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        // Local guard: a blank street/city/state/zip combo would fail server-side with a
        // generic 422; surface the issue immediately instead of doing a round-trip.
        if (address.streetAddress.isBlank() || address.city.isBlank() ||
            address.state.length != 2 || address.zipCode.length !in 5..10) {
            _uiState.update { it.copy(errorMessage = "Please complete all address fields") }
            return
        }
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
                    } catch (parseEx: Exception) {
                        if (parseEx is CancellationException) throw parseEx
                        null
                    }
                    val msg = when {
                        resp.code() == 409 -> serverMsg ?: "That address already exists"
                        resp.code() == 422 -> serverMsg ?: "Invalid address — please check the details"
                        else -> serverMsg ?: "Could not save address"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(errorMessage = "Network error. Please check your connection.") }
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
        if (value != null && !value.isAfter(LocalDateTime.now().plusMinutes(5))) {
            _uiState.update { it.copy(errorMessage = "Please select a time at least 5 minutes from now") }
            return
        }
        _uiState.update { it.copy(scheduledFor = value, errorMessage = null) }
    }

    private fun currentTipCents(): Int {
        val state = _uiState.value
        val subtotal = state.bundle?.subtotal ?: _localSubtotalCents
        val tip = when (val choice = state.tipChoice) {
            TipChoice.None -> 0
            is TipChoice.Percent -> (subtotal * choice.fraction).roundToInt()
            TipChoice.Custom -> {
                val dollars = state.customTipText.toDoubleOrNull() ?: 0.0
                (dollars.coerceIn(0.0, 1000.0) * 100).roundToInt()
            }
        }
        return tip.coerceAtLeast(0)
    }

    private fun launchRefreshBundle() {
        refreshBundleJob?.cancel()
        refreshBundleJob = viewModelScope.launch { refreshBundle() }
    }

    private suspend fun refreshBundle() {
        // Always discard the in-flight payment sheet bundle here; otherwise an old bundle
        // could be presented to Stripe right after the user changed tip/address.
        _uiState.update { it.copy(isLoadingBundle = true, bundle = null, errorMessage = null, pendingPaymentSheet = null) }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (job replaced or VM cleared) — still clear the spinner
            // so a follow-up bootstrap doesn't see a stuck isLoadingBundle=true.
            _uiState.update { it.copy(isLoadingBundle = false) }
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update { it.copy(isLoadingBundle = false, errorMessage = "Network error. Please check your connection.") }
        }
    }

    /** Called by the composable when the user taps Pay. Sets [CheckoutUiState.pendingPaymentSheet]
     *  to trigger PaymentSheet presentation. If backend is in stub mode (no Stripe key),
     *  skip the sheet and finalize directly. */
    fun onPayTapped() {
        if (_uiState.value.isProcessing) return
        val bundle = _uiState.value.bundle ?: return
        // Hard guard against zero-cost orders slipping through the UI gate.
        if (bundle.subtotal <= 0 || bundle.total <= 0) {
            _uiState.update { it.copy(errorMessage = "Cart is empty or invalid. Please add items and try again.") }
            return
        }
        // Stub orders must use a unique stub intent so the idempotency guard doesn't
        // collapse multiple stub orders placed in the same VM session into one.
        _uiState.update { it.copy(isProcessing = true) }
        if (bundle.isStub) {
            finalizeOrder(paymentIntentId = "stub_intent_${java.util.UUID.randomUUID()}")
            return
        }
        // Non-stub orders need a valid Stripe payment intent secret to present the sheet.
        if (!bundle.paymentIntentSecret.contains("_secret_")) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Payment is not configured. Please refresh and try again.") }
            return
        }
        _uiState.update { it.copy(pendingPaymentSheet = bundle) }
    }

    fun consumePendingPaymentSheet() {
        _uiState.update { it.copy(pendingPaymentSheet = null) }
    }

    /** Called by the composable after PaymentSheet closes. `success` means the
     *  user completed payment; false means cancel or failure. */
    fun onPaymentResult(success: Boolean, error: String? = null) {
        if (!success) {
            // Force a fresh bundle (and new payment intent) on the next attempt so the user
            // never gets stuck retrying against a stale intent the server already saw fail.
            _uiState.update { it.copy(isProcessing = false, errorMessage = error, bundle = null) }
            launchRefreshBundle()
            return
        }
        val bundle = _uiState.value.bundle ?: run {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Payment recorded but order summary was lost. Please check your orders.") }
            return
        }
        val intentId = extractIntentId(bundle.paymentIntentSecret)
        if (intentId == null) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Unexpected Stripe response — please try again") }
            return
        }
        finalizeOrder(intentId)
    }

    private fun finalizeOrder(paymentIntentId: String) {
        // Idempotency guard: if Stripe (or our own retry) calls onPaymentResult twice with
        // the same intent, only the first call may proceed to /orders. Otherwise duplicate
        // orders bypass server-side dedupe whenever it has any race window.
        if (!finalizedIntentIds.add(paymentIntentId)) {
            android.util.Log.w("CheckoutViewModel", "finalizeOrder ignored duplicate intent=$paymentIntentId")
            _uiState.update { it.copy(isProcessing = false) }
            return
        }
        val state = _uiState.value
        val isPickup = state.fulfillmentType == "pickup"
        val address = state.selectedAddress
        if (!isPickup && address == null) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Select a delivery address") }
            return
        }
        if (!isPickup && address != null && !address.isGeocoded) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Address location could not be verified. Please remove and re-add this address.") }
            return
        }
        val bundle = state.bundle ?: run {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Could not load order summary. Please try again.") }
            return
        }

        // Final guard: a scheduledFor that drifted into the past while the user lingered
        // would be silently rejected by the server with a generic error. Catch it here.
        if (state.scheduledFor != null && state.scheduledFor.isBefore(LocalDateTime.now())) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Selected time has passed. Please pick a new time.", scheduledFor = null) }
            return
        }
        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
        viewModelScope.launch {
            val scheduledFor = state.scheduledFor?.let { local ->
                local.atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val resp = api.createOrder(
                        CreateOrderRequest(
                            restaurantId = _restaurantId,
                            deliveryAddress = if (isPickup || address == null) "" else "${address.streetAddress}, ${address.city}, ${address.state} ${address.zipCode}",
                            deliveryLat = if (isPickup || address == null) 0.0 else address.latitude,
                            deliveryLng = if (isPickup || address == null) 0.0 else address.longitude,
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
                        return@launch
                    } else if (resp.code() == 409) {
                        // Duplicate payment_intent_id — order already exists. Fetch the latest
                        // order and require it to belong to the same restaurant the user just
                        // tried to check out from; otherwise an unrelated historical order
                        // could surface to the user.
                        val existing = fetchMostRecentOrder()
                        val matches = existing != null && existing.restaurantId == _restaurantId
                        if (matches) {
                            _uiState.update { it.copy(isProcessing = false, placedOrder = existing) }
                        } else {
                            _uiState.update { it.copy(isProcessing = false, errorMessage = "Order may have been placed — check My Orders or contact support") }
                        }
                        return@launch
                    } else {
                        lastError = Exception("Order failed (${resp.code()})")
                        if (attempt < 3) {
                            delay(1000L * attempt)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 3) {
                        delay(1000L * attempt)
                    }
                }
            }
            // All 3 attempts failed
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    errorMessage = lastError?.localizedMessage ?: "Network error. Please check your connection.",
                )
            }
        }
    }

    private suspend fun fetchMostRecentOrder(): Order? = try {
        val r = api.getOrders(page = 1)
        if (r.isSuccessful) r.body()?.firstOrNull() else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("CheckoutViewModel", "fetchMostRecentOrder failed during 409 recovery", e)
        null
    }

    fun retry() {
        viewModelScope.launch { loadAddresses() }
        launchRefreshBundle()
    }

    // PaymentIntent client secrets look like `pi_xxxxxx_secret_yyyy`. We only
    // want the `pi_xxxxxx` portion for our DB. Returns null if the delimiter is
    // absent so callers can surface a clear error instead of posting a garbage ID.
    private fun extractIntentId(clientSecret: String): String? {
        if (!clientSecret.contains("_secret_")) {
            if (BuildConfig.DEBUG) Log.w("CheckoutViewModel", "extractIntentId: client_secret missing '_secret_' delimiter")
            return null
        }
        val candidate = clientSecret.substringBefore("_secret_")
        // Stripe PaymentIntent IDs start with "pi_" (or "seti_" for SetupIntents)
        // and are 20+ chars; guard against malformed/empty values.
        if (candidate.length < 5) {
            if (BuildConfig.DEBUG) Log.w("CheckoutViewModel", "extractIntentId: candidate '${candidate}' too short")
            return null
        }
        if (!candidate.startsWith("pi_") && !candidate.startsWith("seti_")) {
            if (BuildConfig.DEBUG) Log.w("CheckoutViewModel", "extractIntentId: candidate '${candidate}' has unexpected prefix")
            return null
        }
        return candidate
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
