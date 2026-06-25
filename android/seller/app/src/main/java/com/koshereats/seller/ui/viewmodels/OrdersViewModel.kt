package com.koshereats.seller.ui.viewmodels

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import retrofit2.Response
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

data class OrdersState(
    val orders: List<Order> = emptyList(),
    val selectedOrder: Order? = null,
    val selectedFilter: OrderStatus? = null,
    val isLoading: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMorePages: Boolean = false,
    val currentPage: Int = 1,
    val pendingOrderIds: Set<String> = emptySet(),
    val error: String? = null,
    val updateSuccess: String? = null,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val apiService: ApiService,
    private val orderEventBus: OrderEventBus,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var eventJob: Job? = null
    private val pollMutex = Mutex()
    private val pollerRefCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isFirstPoll = true
    @Volatile private var isPollingActive = false

    // New-order alert state (mirrors iOS playNewOrderAlert). Pending IDs already seen, so we
    // only ring on genuinely new tickets; seeded on the first successful poll without alerting.
    private var knownPendingIds: Set<String> = emptySet()
    private var hasSeededPending = false
    // Timestamp of the last audible alert; debounces multiple new pendings in one poll tick.
    private var lastAlertElapsedMs = 0L

    init {
        loadOrders()
        viewModelScope.launch {
            NetworkModule.restaurantChanged.collect {
                // Cancel any in-flight poll so its response (carrying stale-restaurant orders)
                // cannot land into the freshly-cleared state.
                pollingJob?.cancel()
                pollingJob = null
                // Reset new-order alert tracking: the next poll re-seeds against the new
                // restaurant's pending tickets so switching restaurants never rings on
                // pre-existing orders.
                knownPendingIds = emptySet()
                hasSeededPending = false
                _state.value = OrdersState()
                loadOrders()
                if (isPollingActive) launchPollingJob()
            }
        }
    }

    // Called by screen composables via DisposableEffect. Reference-counted so that
    // SellerOrderDetailScreen and SellerOrdersScreen can each hold a ref without
    // one's disposal cancelling the other's poll.
    fun startPolling() {
        pollerRefCount.incrementAndGet()
        if (pollingJob?.isActive == true) return
        isPollingActive = true
        launchPollingJob()
        if (eventJob?.isActive != true) {
            eventJob = viewModelScope.launch {
                orderEventBus.events.collect { event ->
                    if (event.type == "new_order" || event.orderId == null) {
                        pollSilently()
                    } else {
                        refreshSingleOrder(event.orderId)
                    }
                }
            }
        }
        registerNetworkCallback()
    }

    private fun launchPollingJob() {
        pollingJob = viewModelScope.launch {
            if (!isPollingActive) return@launch
            // Delay the very first periodic poll so it doesn't race the init loadOrders().
            // Network-reconnect re-launches skip this because isFirstPoll is already false.
            if (isFirstPoll) {
                isFirstPoll = false
                delay(BACKOFF_DELAYS[0])
            }
            var consecutiveFailures = 0
            while (true) {
                val succeeded = pollSilently()
                consecutiveFailures = if (succeeded) 0 else consecutiveFailures + 1
                val baseDelay = BACKOFF_DELAYS[consecutiveFailures.coerceAtMost(BACKOFF_DELAYS.lastIndex)]
                // ±20% jitter prevents thundering herd when many sellers reconnect at once.
                val jitter = (baseDelay * (kotlin.random.Random.nextDouble() * 0.4 - 0.2)).toLong()
                delay(baseDelay + jitter)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // onAvailable fires on a ConnectivityManager binder/handler thread, but every
                // other pollingJob mutation (start/stop/restaurant-switch) runs on Main. Hop to
                // viewModelScope (Main.immediate) so all reads/writes of pollingJob serialize on
                // one thread — otherwise a cross-thread cancel/relaunch can orphan a poll loop.
                viewModelScope.launch {
                    if (!isPollingActive) return@launch
                    // Cancel the sleeping backoff and restart immediately on reconnect.
                    pollingJob?.cancel()
                    launchPollingJob()
                }
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb,
        )
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    fun stopPolling() {
        pollerRefCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (pollerRefCount.get() == 0) {
            isPollingActive = false
            unregisterNetworkCallback()
            pollingJob?.cancel()
            pollingJob = null
            eventJob?.cancel()
            eventJob = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterNetworkCallback()
    }

    private suspend fun refreshSingleOrder(orderId: String) {
        if (orderId in _state.value.pendingOrderIds) return
        if (!pollMutex.tryLock()) return
        // Set when the pushed order isn't on the current list and a full page-1 poll is
        // needed to surface it at the correct position (mirrors iOS updateOrder's
        // append-if-missing). Run after releasing the lock to avoid re-entrancy.
        var needsListPoll = false
        try {
            val restaurantAtStart = NetworkModule.cachedRestaurantId
            val response = apiService.getOrderDetail(orderId)
            if (NetworkModule.cachedRestaurantId != restaurantAtStart) return
            if (response.isSuccessful) {
                val updated = response.body() ?: return
                needsListPoll = _state.value.orders.none { it.id == orderId }
                _state.update { s ->
                    val filter = s.selectedFilter
                    val newOrders = s.orders.map { if (it.id == orderId) updated else it }
                        .let { list -> if (filter != null) list.filter { it.status == filter } else list }
                    s.copy(
                        orders = newOrders,
                        selectedOrder = if (s.selectedOrder?.id == orderId) updated else s.selectedOrder,
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("OrdersViewModel", "refreshSingleOrder($orderId) failed", e)
        } finally {
            pollMutex.unlock()
        }
        if (needsListPoll) pollSilently()
    }

    // Returns true on success so the caller can reset the backoff counter.
    // tryLock ensures at most one poll is in flight at a time (FCM vs. periodic timer).
    private suspend fun pollSilently(): Boolean {
        if (!pollMutex.tryLock()) return true
        return try {
            val currentState = _state.value
            val filterAtStart = currentState.selectedFilter
            val pageAtStart = currentState.currentPage
            val restaurantAtStart = NetworkModule.cachedRestaurantId
            val statusStr = filterAtStart?.name?.lowercase()
            // Always poll page 1 only — top-of-list orders are the only time-sensitive ones.
            // Pull-to-refresh (refresh()) provides a full page-1 deep reload when needed.
            val response = apiService.getOrders(status = statusStr, limit = PAGE_SIZE)
            val succeeded = response.isSuccessful
            // Discard the response if the active restaurant changed mid-request: those
            // orders belong to the previous restaurant and would briefly leak in.
            val restaurantStillMatches = NetworkModule.cachedRestaurantId == restaurantAtStart
            if (response.isSuccessful && restaurantStillMatches) {
                // Brand-new pending detection mirrors iOS: compare the freshly fetched page-1
                // pending IDs against the set we've already seen. On the very first poll after
                // launch/restaurant-switch we only seed the set (no alert) so pre-existing
                // tickets don't ring. Computed outside _state.update so the update lambda stays
                // side-effect-free and re-runnable.
                val freshPending = (response.body() ?: emptyList())
                    .filter { it.status == OrderStatus.PENDING }
                    .map { it.id }
                    .toSet()
                val brandNewPending = if (hasSeededPending) freshPending - knownPendingIds else emptySet()
                knownPendingIds = freshPending
                hasSeededPending = true
                if (brandNewPending.isNotEmpty()) alertNewOrder()
                _state.update { current ->
                    if (current.selectedFilter == filterAtStart && current.currentPage >= pageAtStart) {
                        val newOrders = response.body() ?: current.orders
                        // Merge by ID: update existing rows in-place and keep any rows from
                        // pages loaded by loadMoreOrders after this request started.
                        val newById = newOrders.associateBy { it.id }
                        // Preserve existing rows not covered by the page-1 poll: they either live
                        // on deeper pages the user scrolled into, or the server capped the response.
                        // Exception: when a status filter is active and the user is on page 1, a
                        // missing order has genuinely transitioned out and must be dropped.
                        val preserveExisting = pageAtStart > 1 || (filterAtStart == null && newOrders.size >= PAGE_SIZE)
                        val merged = current.orders.mapNotNull { existing ->
                            // Don't overwrite optimistic status with stale server data.
                            if (existing.id in current.pendingOrderIds) existing
                            else newById[existing.id] ?: if (preserveExisting) existing else null
                        }
                        val existingIds = current.orders.map { it.id }.toSet()
                        val trulyNew = newOrders.filter { it.id !in existingIds }
                        val finalList = (trulyNew + merged)
                            .let { list -> if (filterAtStart != null) list.filter { it.status == filterAtStart } else list }
                        val updatedSelected = current.selectedOrder?.id
                            ?.let { id -> finalList.find { it.id == id } }
                        current.copy(
                            orders = finalList,
                            selectedOrder = updatedSelected ?: current.selectedOrder,
                        )
                    } else {
                        current
                    }
                }
                // selectedOrder may belong to an older/deep-linked order absent from the
                // paginated window. Refresh it directly so the detail screen stays current.
                val s = _state.value
                val orphanId = s.selectedOrder?.id
                    ?.takeIf { id -> s.orders.none { it.id == id } && id !in s.pendingOrderIds }
                if (orphanId != null) {
                    runCatching {
                        val dr = apiService.getOrderDetail(orphanId)
                        if (dr.isSuccessful) {
                            dr.body()?.let { updated ->
                                _state.update { cur ->
                                    if (cur.selectedOrder?.id == orphanId) cur.copy(selectedOrder = updated) else cur
                                }
                            }
                        }
                    }
                }
            }
            succeeded
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        } finally {
            pollMutex.unlock()
        }
    }

    // Audible + haptic alert on a brand-new pending ticket, the Android counterpart of iOS's
    // playNewOrderAlert. Counter phones are often on silent, so this is independent of the FCM
    // notification path (which can be permission-denied or backgrounded). Debounced to 2s so
    // several new pendings landing in one poll tick don't stack overlapping rings.
    private fun alertNewOrder() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAlertElapsedMs < 2_000L) return
        lastAlertElapsedMs = now
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            // Distinctive triple buzz, echoing iOS's triple-ping cadence (minSdk 26 → always O+).
            val pattern = longArrayOf(0, 200, 150, 200, 150, 200)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun loadOrders(status: OrderStatus? = null) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(
                isLoading = it.orders.isEmpty(),
                isRefreshing = false,
                error = null,
                selectedFilter = status,
                currentPage = 1,
                hasMorePages = false,
            ) }
            try {
                val statusStr = status?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr, page = 1, limit = PAGE_SIZE)
                if (response.isSuccessful) {
                    val body = response.body() ?: emptyList()
                    // The backend ignores the `status` query param, so it returns the newest
                    // PAGE_SIZE orders of ALL statuses. Filter client-side so the selected chip
                    // shows only matching orders immediately (matching pollSilently's merge).
                    val filtered = if (status != null) body.filter { it.status == status } else body
                    _state.update { it.copy(
                        orders = filtered,
                        isLoading = false,
                        // hasMorePages keys on the raw (unfiltered) page size: a full page means
                        // the server may hold older rows beyond this window worth paging into.
                        hasMorePages = body.size == PAGE_SIZE,
                        currentPage = 1,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        error = "Failed to load orders",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun loadMoreOrders() {
        val currentState = _state.value
        if (!currentState.hasMorePages || currentState.isLoadingMore || currentState.isLoading) return
        val filterAtStart = currentState.selectedFilter
        val pageToLoad = currentState.currentPage + 1
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val statusStr = filterAtStart?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr, page = pageToLoad, limit = PAGE_SIZE)
                if (response.isSuccessful) {
                    val body = response.body() ?: emptyList()
                    _state.update { current ->
                        if (current.selectedFilter != filterAtStart) {
                            current.copy(isLoadingMore = false)
                        } else {
                            // De-dup by id before appending: SellerOrdersScreen renders with
                            // items(key = { it.id }) and Compose crashes on duplicate keys. Dupes
                            // arise because the backend pagination ignores `page` (it only honors
                            // `cursor`), so each load-more returns the same newest window, and even
                            // with real cursor paging the OFFSET window shifts as new orders arrive.
                            val existingIds = current.orders.map { it.id }.toSet()
                            val unseen = body.filter { it.id !in existingIds }
                            // Apply the status filter client-side — the backend ignores `status`.
                            val unseenFiltered = if (filterAtStart != null) {
                                unseen.filter { it.status == filterAtStart }
                            } else unseen
                            current.copy(
                                orders = current.orders + unseenFiltered,
                                currentPage = pageToLoad,
                                // Terminate pagination when the page brought no unseen ids: the
                                // server returned a window we already hold, so paging further would
                                // loop forever fetching duplicates. A full page of new ids means
                                // more may remain.
                                hasMorePages = unseen.isNotEmpty() && body.size == PAGE_SIZE,
                                isLoadingMore = false,
                            )
                        }
                    }
                } else {
                    _state.update { it.copy(isLoadingMore = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun loadOrderDetail(orderId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingDetail = true, error = null) }
            try {
                val response = apiService.getOrderDetail(orderId)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        selectedOrder = response.body(),
                        isLoadingDetail = false,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoadingDetail = false,
                        error = "Failed to load order details",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoadingDetail = false,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    private val allowedTransitions = mapOf(
        OrderStatus.PENDING to setOf(OrderStatus.ACCEPTED),
        OrderStatus.ACCEPTED to setOf(OrderStatus.PREPARING),
        OrderStatus.PREPARING to setOf(OrderStatus.READY),
        // Pickup orders: seller marks READY→COMPLETED when customer collects.
        // Delivery orders: READY is awaiting courier pickup; seller cannot skip to COMPLETED here.
        OrderStatus.READY to setOf(OrderStatus.COMPLETED),
        // SCHEDULED is intentionally omitted: the backend cron auto-transitions to PENDING at the scheduled time.
    )

    fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        if (_state.value.pendingOrderIds.contains(orderId)) return

        val currentOrder = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (currentOrder != null) {
            val allowed = allowedTransitions[currentOrder.status] ?: emptySet()
            // Block delivery sellers from completing at READY — the courier must pick up first.
            val blocked = newStatus !in allowed ||
                (newStatus == OrderStatus.COMPLETED && !currentOrder.isPickup &&
                    currentOrder.status == OrderStatus.READY)
            if (blocked) {
                _state.update { it.copy(error = "Cannot change order from ${currentOrder.status.name.lowercase()} to ${newStatus.name.lowercase()}") }
                return
            }
        }

        val snapshotOrder = _state.value.selectedOrder
        val snapshotOrderInList = _state.value.orders.find { it.id == orderId }
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                orders = it.orders.map { o -> if (o.id == orderId) o.copy(status = newStatus) else o },
                selectedOrder = if (it.selectedOrder?.id == orderId) it.selectedOrder?.copy(status = newStatus) else it.selectedOrder,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = when (newStatus) {
                    OrderStatus.ACCEPTED -> apiService.acceptOrder(orderId)
                    OrderStatus.PREPARING -> apiService.markOrderPreparing(orderId)
                    OrderStatus.READY -> apiService.markOrderReady(orderId)
                    OrderStatus.COMPLETED -> apiService.completeOrder(orderId)
                    else -> null
                }

                if (response == null) {
                    _state.update { it.copy(
                        orders = if (snapshotOrderInList != null) it.orders.map { o -> if (o.id == orderId) snapshotOrderInList else o } else it.orders,
                        selectedOrder = if (it.selectedOrder?.id == orderId) snapshotOrder else it.selectedOrder,
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "This order transition is not available",
                    ) }
                    return@launch
                }

                if (response.isSuccessful) {
                    val updatedOrder = response.body()
                    if (updatedOrder == null) {
                        _state.update { it.copy(
                            orders = if (snapshotOrderInList != null) it.orders.map { o -> if (o.id == orderId) snapshotOrderInList else o } else it.orders,
                            selectedOrder = if (it.selectedOrder?.id == orderId) snapshotOrder else it.selectedOrder,
                            pendingOrderIds = it.pendingOrderIds - orderId,
                            error = "Failed to update order status",
                        ) }
                        return@launch
                    }
                    _state.update { it.copy(
                        selectedOrder = updatedOrder,
                        orders = it.orders.map { o ->
                            if (o.id == orderId) updatedOrder else o
                        },
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        updateSuccess = "Order ${updatedOrder.status.displayName.lowercase()}",
                    ) }
                } else {
                    _state.update { it.copy(
                        orders = if (snapshotOrderInList != null) it.orders.map { o -> if (o.id == orderId) snapshotOrderInList else o } else it.orders,
                        selectedOrder = if (it.selectedOrder?.id == orderId) snapshotOrder else it.selectedOrder,
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "Failed to update order status",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    orders = if (snapshotOrderInList != null) it.orders.map { o -> if (o.id == orderId) snapshotOrderInList else o } else it.orders,
                    selectedOrder = if (it.selectedOrder?.id == orderId) snapshotOrder else it.selectedOrder,
                    pendingOrderIds = it.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun rejectPending(orderId: String, reason: String? = null) {
        val order = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (order?.status != OrderStatus.PENDING) {
            _state.update { it.copy(error = "Can only reject a pending order") }
            return
        }
        doOrderApiCall(orderId) { apiService.rejectOrder(orderId, mapOf("reason" to reason)) }
    }

    private fun doOrderApiCall(orderId: String, call: suspend () -> Response<Order>) {
        if (_state.value.pendingOrderIds.contains(orderId)) return
        val snapshotOrder = _state.value.selectedOrder
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = call()
                if (response.isSuccessful) {
                    val updatedOrder = response.body()
                    if (updatedOrder == null) {
                        _state.update { it.copy(
                            selectedOrder = if (it.selectedOrder == snapshotOrder) snapshotOrder else it.selectedOrder,
                            pendingOrderIds = it.pendingOrderIds - orderId,
                            error = "Failed to update order status",
                        ) }
                        return@launch
                    }
                    _state.update { st ->
                        st.copy(
                            selectedOrder = updatedOrder,
                            orders = st.orders.map { if (it.id == orderId) updatedOrder else it },
                            pendingOrderIds = st.pendingOrderIds - orderId,
                            updateSuccess = "Order ${updatedOrder.status.displayName.lowercase()}",
                        )
                    }
                } else {
                    _state.update { it.copy(
                        selectedOrder = if (it.selectedOrder == snapshotOrder) snapshotOrder else it.selectedOrder,
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "Failed to update order status",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    selectedOrder = if (it.selectedOrder == snapshotOrder) snapshotOrder else it.selectedOrder,
                    pendingOrderIds = it.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun sellerPickupOrder(orderId: String) {
        if (_state.value.pendingOrderIds.contains(orderId)) return
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = apiService.sellerPickupOrder(orderId)
                if (response.isSuccessful) {
                    // Backend returns a status map, not the full order — re-fetch to
                    // get the updated Order object for the UI.
                    val detail = apiService.getOrderDetail(orderId)
                    val updatedOrder = detail.body()
                    _state.update { st ->
                        st.copy(
                            selectedOrder = updatedOrder ?: st.selectedOrder,
                            orders = if (updatedOrder != null) st.orders.map { if (it.id == orderId) updatedOrder else it } else st.orders,
                            pendingOrderIds = st.pendingOrderIds - orderId,
                            updateSuccess = "Order picked up",
                        )
                    }
                } else {
                    _state.update { it.copy(
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "Failed to mark order as picked up",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingOrderIds = it.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun sellerDeliverOrder(orderId: String) {
        if (_state.value.pendingOrderIds.contains(orderId)) return
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = apiService.sellerDeliverOrder(orderId)
                if (response.isSuccessful) {
                    val detail = apiService.getOrderDetail(orderId)
                    val updatedOrder = detail.body()
                    _state.update { st ->
                        st.copy(
                            selectedOrder = updatedOrder ?: st.selectedOrder,
                            orders = if (updatedOrder != null) st.orders.map { if (it.id == orderId) updatedOrder else it } else st.orders,
                            pendingOrderIds = st.pendingOrderIds - orderId,
                            updateSuccess = "Order delivered",
                        )
                    }
                } else {
                    _state.update { it.copy(
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "Failed to mark order as delivered",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingOrderIds = it.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    /**
     * Escalate an open self-delivery order to Uber Direct. One-way — the backend
     * rejects orders already on a courier/provider (409), surfaced as an error
     * Toast. On success the order is re-fetched so the UI reflects the handoff.
     */
    fun escalateOrderToUber(orderId: String) {
        if (_state.value.pendingOrderIds.contains(orderId)) return
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = apiService.escalateOrderToUber(orderId)
                if (response.isSuccessful) {
                    val detail = apiService.getOrderDetail(orderId)
                    val updatedOrder = detail.body()
                    _state.update { st ->
                        st.copy(
                            selectedOrder = updatedOrder ?: st.selectedOrder,
                            orders = if (updatedOrder != null) st.orders.map { if (it.id == orderId) updatedOrder else it } else st.orders,
                            pendingOrderIds = st.pendingOrderIds - orderId,
                            updateSuccess = "Sent to Uber — a courier is on the way.",
                        )
                    }
                } else {
                    _state.update { it.copy(
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "Couldn't send to Uber — it may already be dispatched.",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingOrderIds = it.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val filterAtStart = _state.value.selectedFilter
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val statusStr = filterAtStart?.name?.lowercase()
                // Treat refresh as a clean page-1 reload so currentPage stays in sync
                // with the visible list. Requesting more than PAGE_SIZE without resetting
                // currentPage shifts pagination windows and causes duplicate rows on the
                // next loadMoreOrders call.
                val response = apiService.getOrders(status = statusStr, page = 1, limit = PAGE_SIZE)
                if (response.isSuccessful) {
                    _state.update { current ->
                        if (current.selectedFilter == filterAtStart) {
                            val body = response.body() ?: emptyList()
                            // Preserve optimistic status for any in-flight updates.
                            val orders = body.map { order ->
                                if (order.id in current.pendingOrderIds)
                                    current.orders.find { it.id == order.id } ?: order
                                else order
                            // The backend ignores `status`; filter client-side so a refreshed
                            // filtered view shows only matching orders (consistent with loadOrders).
                            }.let { list ->
                                if (filterAtStart != null) list.filter { it.status == filterAtStart } else list
                            }
                            current.copy(
                                orders = orders,
                                isRefreshing = false,
                                currentPage = 1,
                                hasMorePages = body.size == PAGE_SIZE,
                            )
                        } else {
                            current.copy(isRefreshing = false)
                        }
                    }
                } else {
                    _state.update { it.copy(isRefreshing = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isRefreshing = false) }
            } finally {
                _state.update { if (it.isRefreshing) it.copy(isRefreshing = false) else it }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, updateSuccess = null) }
    }

    fun clearSelectedOrder() {
        _state.update { it.copy(selectedOrder = null) }
    }

    companion object {
        // Backoff delays for consecutive poll failures: 30s → 1m → 2m → 4m → 5m (cap).
        private val BACKOFF_DELAYS = longArrayOf(30_000, 60_000, 120_000, 240_000, 300_000)
        private const val PAGE_SIZE = 20
    }
}
