package com.koshereats.consumer.ui.viewmodels

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import com.koshereats.consumer.data.util.Money
import com.koshereats.consumer.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

sealed interface TipChoice {
    data object None : TipChoice
    data class Percent(val bps: Int) : TipChoice
    data object Custom : TipChoice

    companion object {
        val presets: List<TipChoice> = listOf(None, Percent(1500), Percent(1800), Percent(2000), Custom)
    }

    fun label(subtotalCents: Int): String = when (this) {
        None -> "None"
        is Percent -> {
            val cents = (subtotalCents * bps) / 10_000
            "${bps / 100}%\n${cents.formatPrice()}"
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
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    private companion object {
        const val KEY_RESTAURANT_ID = "checkout_restaurant_id"
        const val KEY_DEAL_ID = "checkout_deal_id"
        // Mirror AddressViewModel's persisted selection so checkout honors the
        // address the user picked in the Home "Deliver to" header.
        val SELECTED_ADDRESS_ID = stringPreferencesKey("selected_address_id")
        // Holds the PaymentIntent id the client has confirmed (card charged) but
        // for which it has not yet confirmed an order exists server-side. Set
        // immediately after the charge, BEFORE createOrder, and cleared once an
        // order is known to exist (createOrder returned, recovery found it, or
        // reconcile resolved it). While set, the charge path refuses to confirm a
        // NEW PaymentIntent — charging again would double-charge the customer for
        // an order we never recovered. Survives process death (DataStore).
        val INFLIGHT_PAYMENT_INTENT = stringPreferencesKey("inflight_payment_intent")
        // Post-charge recovery backoff: 0.5s / 1s / 2s, riding out brief
        // replication / commit lag before surfacing an error to the user.
        val RECOVERY_DELAYS_MS = longArrayOf(500L, 1000L, 2000L)
    }

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var refreshBundleJob: Job? = null
    private var deliveryQuoteJob: Job? = null
    // The exact bundle handed to Stripe when the user tapped Pay. onPaymentResult must
    // finalize against THIS bundle's intent — not _uiState.value.bundle, which a debounced
    // refresh can swap to a different intent (or null) while the PaymentSheet is open.
    private var presentedBundle: PaymentSheetBundle? = null
    private var _bootstrapped = false
    private var _restaurantId: String = ""
    private var _appliedDealId: String? = null
    private var _localSubtotalCents: Int = 0
    // The local cart handed to bootstrap(), kept so retry() can re-run the sync.
    private var _localCart: List<CartItem> = emptyList()
    // Set when syncLocalCartToServer() failed; retry() must re-sync before refreshing
    // the bundle, otherwise /payments/intent prices a cleared/partial server cart.
    private var _syncFailed = false
    // Set when the post-failure rollback (clearServerCart) ALSO failed, leaving the
    // server cart holding a partial item set. Pricing/paying in this state could
    // charge for the wrong items, so block Pay until a clean re-sync succeeds.
    private var _serverCartCorrupted = false

    // Idempotency: track which payment intent IDs have already been finalized
    // so a duplicate PaymentSheetResult.Completed cannot create a second order.
    private val finalizedIntentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun bootstrap(
        localCart: List<CartItem>,
        restaurantId: String,
        appliedDealId: String? = null,
        scheduledFor: LocalDateTime? = null,
    ) {
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
        _localCart = localCart
        _localSubtotalCents = localCart.sumOf { it.totalPrice }

        // Seed the scheduled delivery time chosen on the cart screen. Only carry it
        // over if it is still comfortably in the future (>5 min, matching
        // updateScheduledFor's guard); a slot that lapsed while the user lingered
        // falls back to ASAP rather than failing the place-order time check later.
        if (scheduledFor != null && scheduledFor.isAfter(LocalDateTime.now().plusMinutes(5))) {
            _uiState.update { it.copy(scheduledFor = scheduledFor) }
        }

        // Always persist so process-death recovery can restore the IDs on the next launch.
        savedStateHandle[KEY_RESTAURANT_ID] = effectiveRestaurantId
        savedStateHandle[KEY_DEAL_ID] = effectiveDealId ?: ""

        _restaurantId = effectiveRestaurantId
        _appliedDealId = effectiveDealId
        viewModelScope.launch {
            // If a prior PaymentIntent was charged but never confirmed as an order (app
            // died between the charge and a known-good order), reconcile it now. A recovered
            // order surfaces via placedOrder so the screen can route the user to it instead
            // of letting them re-pay.
            reconcileInflightOrder()
            loadAddresses()
            if (localCart.isNotEmpty()) {
                // If the sync fails the server cart is empty (rollback) or partial
                // (rollback also failed). Either way, pricing/charging it would be
                // wrong — abort before launchRefreshBundle() so the "Failed to prepare
                // cart" error stays visible and Retry can re-sync.
                if (!syncLocalCartToServer(localCart, effectiveRestaurantId)) {
                    return@launch
                }
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
                val savedId = try {
                    dataStore.data.first()[SELECTED_ADDRESS_ID]
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        addresses = list,
                        // Honor the user's Home-screen "Deliver to" choice first, then
                        // fall back to default / first — matching AddressViewModel.
                        selectedAddress = state.selectedAddress
                            ?: list.firstOrNull { it.id == savedId }
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
     *
     * Returns true on a clean sync; false if any add (or the rollback) failed, in
     * which case the caller must NOT proceed to price/charge the server cart.
     */
    private suspend fun syncLocalCartToServer(localCart: List<CartItem>, restaurantId: String): Boolean {
        if (localCart.isEmpty()) return true
        try {
            api.clearServerCart()
            coroutineScope {
                localCart.map { item ->
                    async {
                        val modifierIds = item.selectedModifiers
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
            // Clean sync — clear any prior failure state.
            _syncFailed = false
            _serverCartCorrupted = false
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _syncFailed = true
            // Roll back: clear the server cart so /payments/intent cannot read a partial subtotal.
            try {
                api.clearServerCart()
                _serverCartCorrupted = false
            } catch (rollbackErr: Exception) {
                if (rollbackErr is CancellationException) throw rollbackErr
                // Rollback failed too: the server cart may hold a partial item set.
                // Mark it corrupted so onPayTapped() refuses to charge until a clean re-sync.
                _serverCartCorrupted = true
                android.util.Log.w("CheckoutViewModel", "Server cart rollback failed after partial sync", rollbackErr)
            }
            _uiState.update {
                it.copy(
                    isLoadingBundle = false,
                    bundle = null,
                    errorMessage = "Failed to prepare cart. Please try again.",
                )
            }
            return false
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
        // Never reprice mid-payment: a debounced refresh that fired while the PaymentSheet
        // is open would swap the bundle out from under the intent the user is paying.
        if (_uiState.value.isProcessing) return
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

    private fun currentTipCents(subtotalCents: Int): Int {
        val state = _uiState.value
        val subtotal = subtotalCents
        val tip = when (val choice = state.tipChoice) {
            TipChoice.None -> 0
            is TipChoice.Percent -> (subtotal * choice.bps) / 10_000
            TipChoice.Custom -> {
                // Route user-entered tip text through Money.parseCents so a comma
                // decimal ("12,50") parses as 1250¢ instead of 0. Cap mirrors iOS
                // CheckoutViewModel.maxTipCents (0..$500 -> 0..50000¢).
                val cents = Money.parseCents(state.customTipText) ?: 0
                cents.coerceIn(0, 50_000)
            }
        }
        return tip.coerceAtLeast(0)
    }

    private fun launchRefreshBundle() {
        refreshBundleJob?.cancel()
        refreshBundleJob = viewModelScope.launch { refreshBundle() }
    }

    private suspend fun refreshBundle() {
        // Never reprice while a payment is in flight. Nulling the bundle / fetching a new
        // intent here would let onPaymentResult finalize against the wrong intent once the
        // PaymentSheet (presenting the previous bundle) returns.
        if (_uiState.value.isProcessing) return
        // Always discard the in-flight payment sheet bundle here; otherwise an old bundle
        // could be presented to Stripe right after the user changed tip/address.
        // Capture the authoritative subtotal (prior bundle if present, else the synced
        // local cart) BEFORE nulling the bundle — otherwise currentTipCents() would see
        // bundle == null and always fall back to _localSubtotalCents, making the server
        // subtotal a dead branch (and zeroing percent tips on process-death recovery
        // where _localSubtotalCents is 0).
        val tipBase = _uiState.value.bundle?.subtotal ?: _localSubtotalCents
        // On process-death recovery the tip base is 0 (no prior bundle, local cart
        // not yet synced), so a percent tip prices to $0 here. Capture that we'll
        // need a second pass once the server returns the real subtotal (mirrors iOS).
        val needsReRefresh = _uiState.value.tipChoice is TipChoice.Percent && tipBase == 0
        _uiState.update { it.copy(isLoadingBundle = true, bundle = null, errorMessage = null, pendingPaymentSheet = null) }
        try {
            val address = _uiState.value.selectedAddress
            val state = _uiState.value
            val resp = api.createPaymentSheet(
                PaymentSheetRequest(
                    tip = if (state.fulfillmentType == "pickup") 0 else currentTipCents(tipBase),
                    restaurantId = _restaurantId,
                    deliveryAddress = if (state.fulfillmentType == "pickup") "" else address?.formatted.orEmpty(),
                    fulfillmentType = state.fulfillmentType,
                    appliedDealId = _appliedDealId,
                ),
            )
            if (resp.isSuccessful) {
                val body = resp.body()
                _uiState.update { it.copy(bundle = body, isLoadingBundle = false) }
                // If recovery priced a percent tip against a 0 subtotal, the server
                // now knows the real subtotal — re-refresh once so the percent applies.
                if (needsReRefresh &&
                    _uiState.value.tipChoice is TipChoice.Percent &&
                    (body?.tip ?: 0) == 0 &&
                    (body?.subtotal ?: 0) > 0
                ) {
                    refreshBundle()
                }
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
        // Hard block: if a failed sync left the server cart in a partial/unknown state
        // and the rollback couldn't clean it up, refuse to charge until a successful
        // re-sync (via Retry) clears this flag — the priced bundle may not match the cart.
        if (_serverCartCorrupted) {
            _uiState.update { it.copy(errorMessage = "Failed to prepare cart. Please try again.") }
            return
        }
        val bundle = _uiState.value.bundle ?: return
        // Hard guard against zero-cost orders slipping through the UI gate.
        if (bundle.subtotal <= 0 || bundle.total <= 0) {
            _uiState.update { it.copy(errorMessage = "Cart is empty or invalid. Please add items and try again.") }
            return
        }
        // Lock in the bundle the user is paying and stop any in-flight/debounced reprice so
        // refreshBundle() can't swap _uiState.value.bundle while the PaymentSheet is open.
        refreshBundleJob?.cancel()
        deliveryQuoteJob?.cancel()
        presentedBundle = bundle
        // Stub orders must use a unique stub intent so the idempotency guard doesn't
        // collapse multiple stub orders placed in the same VM session into one.
        _uiState.update { it.copy(isProcessing = true) }
        // Never confirm a NEW PaymentIntent while a prior charged-but-unrecovered one is
        // still pending. passesInflightGuard() first tries to reconcile it (the order may
        // exist now); if it still can't be resolved it blocks (and resets isProcessing)
        // so we don't double-charge for an order we never recovered. This is async, so the
        // rest of the present/finalize flow runs inside the launched coroutine.
        viewModelScope.launch {
            if (!passesInflightGuard()) {
                presentedBundle = null
                return@launch
            }
            if (bundle.isStub) {
                presentedBundle = null
                finalizeOrder(paymentIntentId = "stub_intent_${java.util.UUID.randomUUID()}", paidBundle = bundle)
                return@launch
            }
            // Non-stub orders need a valid Stripe payment intent secret to present the sheet.
            if (!bundle.paymentIntentSecret.contains("_secret_")) {
                presentedBundle = null
                _uiState.update { it.copy(isProcessing = false, errorMessage = "Payment is not configured. Please refresh and try again.") }
                return@launch
            }
            _uiState.update { it.copy(pendingPaymentSheet = bundle) }
        }
    }

    fun consumePendingPaymentSheet() {
        _uiState.update { it.copy(pendingPaymentSheet = null) }
    }

    /** Called by the composable after PaymentSheet closes. `success` means the
     *  user completed payment; false means cancel or failure. */
    fun onPaymentResult(success: Boolean, error: String? = null) {
        // Use the bundle that was actually presented to Stripe, not _uiState.value.bundle —
        // the latter may have been swapped by a refresh that raced the open PaymentSheet.
        val paid = presentedBundle
        if (!success) {
            presentedBundle = null
            // Force a fresh bundle (and new payment intent) on the next attempt so the user
            // never gets stuck retrying against a stale intent the server already saw fail.
            _uiState.update { it.copy(isProcessing = false, errorMessage = error, bundle = null) }
            launchRefreshBundle()
            return
        }
        val bundle = paid ?: run {
            presentedBundle = null
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Payment recorded but order summary was lost. Please check your orders.") }
            return
        }
        val intentId = extractIntentId(bundle.paymentIntentSecret)
        if (intentId == null) {
            presentedBundle = null
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Unexpected Stripe response — please try again") }
            return
        }
        presentedBundle = null
        finalizeOrder(intentId, paidBundle = bundle)
    }

    private fun finalizeOrder(paymentIntentId: String, paidBundle: PaymentSheetBundle) {
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
        // Use the bundle the user actually paid for. The server cross-checks that the
        // client-sent tip matches the amount baked into the paid intent (VerifyPaymentSucceeded),
        // so sending _uiState.value.bundle.tip after a mid-payment reprice would fail verification.
        val bundle = paidBundle

        // Final guard: a scheduledFor that drifted into the past while the user lingered
        // would be silently rejected by the server with a generic error. Catch it here.
        if (state.scheduledFor != null && state.scheduledFor.isBefore(LocalDateTime.now())) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Selected time has passed. Please pick a new time.", scheduledFor = null) }
            return
        }
        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
        // Stub orders carry a synthetic intent and never charge a real card, so there
        // is nothing to recover and nothing to persist for them.
        val isStubIntent = paymentIntentId.startsWith("stub_intent")
        viewModelScope.launch {
            // The card was just charged for THIS PaymentIntent. Persist it BEFORE
            // createOrder so that if the app dies (crash / kill) between the charge and a
            // known-good order, the next launch can reconcile it via reconcileInflightOrder()
            // instead of silently dropping a paid order.
            if (!isStubIntent) {
                persistInflightPI(paymentIntentId)
            }
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
                        // createOrder is idempotent on a duplicate payment_intent_id, so a
                        // successful return always means the order exists — clear the marker.
                        if (!isStubIntent) clearInflightPI()
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
                            if (!isStubIntent) clearInflightPI()
                            _uiState.update { it.copy(isProcessing = false, placedOrder = existing) }
                        } else {
                            // The order exists server-side (409) but we couldn't confirm the
                            // match. recoverOrder() below (in the failure path) is by-PI scoped,
                            // so prefer it: clear only once we positively recover it.
                            _uiState.update { it.copy(isProcessing = false, errorMessage = "Order may have been placed — check My Orders or contact support") }
                            if (!isStubIntent) {
                                val recovered = recoverOrder(paymentIntentId)
                                if (recovered != null) {
                                    clearInflightPI()
                                    _uiState.update { it.copy(errorMessage = null, placedOrder = recovered) }
                                }
                            }
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
            // createOrder failed AFTER the charge. The order may still have been created
            // (request reached the DB but the response was lost, or a transient failure on a
            // later replay). Recover by looking it up directly by PaymentIntent id with a few
            // backoff retries before surfacing an error. Stub mode has no real PI to recover.
            if (!isStubIntent) {
                val recovered = recoverOrder(paymentIntentId)
                if (recovered != null) {
                    clearInflightPI()
                    _uiState.update { it.copy(isProcessing = false, placedOrder = recovered) }
                    return@launch
                }
                // No order found. Leave the in-flight marker set so a later reconcile / next
                // launch can still recover it once the backend catches up.
            }
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    errorMessage = lastError?.localizedMessage ?: "Network error. Please check your connection.",
                )
            }
        }
    }

    // ── In-flight PaymentIntent idempotency recovery ──────────────────────

    /** Reads the persisted charged-but-unrecovered PaymentIntent id, or null. */
    private suspend fun readInflightPI(): String? = try {
        dataStore.data.first()[INFLIGHT_PAYMENT_INTENT]
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e("CheckoutViewModel", "readInflightPI failed", e)
        null
    }

    /** Persists [pi] as the in-flight PaymentIntent BEFORE createOrder so a
     *  crash/kill between the charge and a known-good order can be reconciled
     *  on the next launch instead of silently dropping a paid order. */
    private suspend fun persistInflightPI(pi: String) {
        try {
            dataStore.edit { it[INFLIGHT_PAYMENT_INTENT] = pi }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("CheckoutViewModel", "persistInflightPI failed", e)
        }
    }

    /** Clears the in-flight marker once the order is known to exist. */
    private suspend fun clearInflightPI() {
        try {
            dataStore.edit { it.remove(INFLIGHT_PAYMENT_INTENT) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("CheckoutViewModel", "clearInflightPI failed", e)
        }
    }

    /**
     * Post-charge recovery: look the order up by the PaymentIntent id the client
     * just confirmed, retrying with backoff (0.5s / 1s / 2s) to ride out a brief
     * replication / commit lag. Returns the order if found, or null if every
     * attempt 404s or errors. A 404 means "no order for this user yet"; any other
     * error is also treated as "not yet recovered" and retried.
     */
    private suspend fun recoverOrder(paymentIntentId: String): Order? {
        for ((attempt, delayMs) in RECOVERY_DELAYS_MS.withIndex()) {
            if (attempt > 0) delay(delayMs)
            try {
                val resp = api.getOrderByPaymentIntent(paymentIntentId)
                if (resp.isSuccessful) {
                    val order = resp.body()
                    if (order != null) return order
                }
                // Non-2xx (e.g. 404 = not created yet) — fall through and retry.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CheckoutViewModel", "recoverOrder attempt ${attempt + 1} failed", e)
            }
        }
        return null
    }

    /**
     * Called on checkout-screen resume and bootstrap. If a PaymentIntent was
     * charged but never confirmed as an order (the app died between the charge
     * and a known-good order), look it up and, if it now exists server-side,
     * clear the marker and surface the recovered order. Leaves the marker in
     * place when the lookup 404s so a later attempt can still recover it.
     * Returns the recovered order, if any, so a caller can route the user to it.
     */
    suspend fun reconcileInflightOrder(): Order? {
        val pi = readInflightPI() ?: return null
        try {
            val resp = api.getOrderByPaymentIntent(pi)
            if (resp.isSuccessful) {
                val order = resp.body()
                if (order != null) {
                    clearInflightPI()
                    _uiState.update { it.copy(placedOrder = order) }
                    return order
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CheckoutViewModel", "reconcileInflightOrder lookup failed", e)
        }
        return null
    }

    /** Public entry point for the composable to reconcile on screen resume. */
    fun reconcileOnResume() {
        viewModelScope.launch { reconcileInflightOrder() }
    }

    /**
     * Top-of-charge-path guard. Returns true when it is safe to confirm a NEW
     * PaymentIntent. When a prior charged-but-unrecovered PaymentIntent is still
     * persisted it first tries to reconcile it (clearing the marker if the order
     * now exists); if it still can't be resolved it blocks the new charge and
     * surfaces an error so we never double-charge the customer.
     */
    private suspend fun passesInflightGuard(): Boolean {
        if (readInflightPI() == null) return true
        reconcileInflightOrder()
        if (readInflightPI() == null) return true
        _uiState.update {
            it.copy(
                isProcessing = false,
                pendingPaymentSheet = null,
                errorMessage = "Your previous payment is still being confirmed. Please check your orders before trying again.",
            )
        }
        return false
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
        // If the cart sync failed (server cart is empty after rollback, or partial if
        // rollback also failed), re-run the full bootstrap sequence: re-sync, then price.
        // Otherwise /payments/intent would price a cleared/partial cart and Retry is
        // guaranteed to fail or charge the wrong items.
        if (_syncFailed && _localCart.isNotEmpty()) {
            viewModelScope.launch {
                loadAddresses()
                if (syncLocalCartToServer(_localCart, _restaurantId)) {
                    launchRefreshBundle()
                }
            }
            return
        }
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
