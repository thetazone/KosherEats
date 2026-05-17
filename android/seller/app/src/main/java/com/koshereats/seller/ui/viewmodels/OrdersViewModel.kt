package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import retrofit2.Response
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrdersState(
    val orders: List<Order> = emptyList(),
    val selectedOrder: Order? = null,
    val selectedFilter: OrderStatus? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val pendingOrderIds: Set<String> = emptySet(),
    val error: String? = null,
    val updateSuccess: String? = null,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val apiService: ApiService,
    private val orderEventBus: OrderEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var pollerRefCount = 0
    private var loadJob: Job? = null

    init {
        loadOrders()
    }

    // Called by screen composables via DisposableEffect. Reference-counted so that
    // SellerOrderDetailScreen and SellerOrdersScreen can each hold a ref without
    // one's disposal cancelling the other's poll.
    fun startPolling() {
        pollerRefCount++
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            launch {
                orderEventBus.events.collect { event ->
                    if (event.type == "new_order" || event.orderId == null) {
                        pollSilently()
                    } else {
                        refreshSingleOrder(event.orderId)
                    }
                }
            }
            var consecutiveFailures = 0
            while (true) {
                val succeeded = pollSilently()
                consecutiveFailures = if (succeeded) 0 else consecutiveFailures + 1
                delay(BACKOFF_DELAYS[consecutiveFailures.coerceAtMost(BACKOFF_DELAYS.lastIndex)])
            }
        }
    }

    fun stopPolling() {
        pollerRefCount = (pollerRefCount - 1).coerceAtLeast(0)
        if (pollerRefCount == 0) {
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    private suspend fun refreshSingleOrder(orderId: String) {
        try {
            val response = apiService.getOrderDetail(orderId)
            if (response.isSuccessful) {
                val updated = response.body() ?: return
                _state.update { s ->
                    s.copy(
                        orders = s.orders.map { if (it.id == orderId) updated else it },
                        selectedOrder = if (s.selectedOrder?.id == orderId) updated else s.selectedOrder,
                    )
                }
            }
        } catch (_: Exception) { }
    }

    // Returns true on success so the caller can reset the backoff counter.
    private suspend fun pollSilently(): Boolean {
        return try {
            val filterAtStart = _state.value.selectedFilter
            val statusStr = filterAtStart?.name?.lowercase()
            val response = apiService.getOrders(status = statusStr)
            val succeeded = response.isSuccessful
            if (response.isSuccessful) {
                _state.update { current ->
                    if (current.selectedFilter == filterAtStart) {
                        val newOrders = response.body() ?: current.orders
                        val updatedSelected = current.selectedOrder?.id
                            ?.let { id -> newOrders.find { it.id == id } }
                        current.copy(
                            orders = newOrders,
                            selectedOrder = updatedSelected ?: current.selectedOrder,
                        )
                    } else {
                        current
                    }
                }
            }
            succeeded
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    fun loadOrders(status: OrderStatus? = null) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(
                isLoading = it.orders.isEmpty(),
                error = null,
                selectedFilter = status,
            ) }
            try {
                val statusStr = status?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        orders = response.body() ?: emptyList(),
                        isLoading = false,
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

    fun loadOrderDetail(orderId: String) {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                val response = apiService.getOrderDetail(orderId)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        selectedOrder = response.body(),
                        isLoading = false,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        error = "Failed to load order details",
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

    fun rejectPending(orderId: String) {
        val order = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (order?.status != OrderStatus.PENDING) {
            _state.update { it.copy(error = "Can only reject a pending order") }
            return
        }
        doOrderApiCall(orderId) { apiService.rejectOrder(orderId) }
    }

    fun cancelInProgress(orderId: String) {
        val order = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (order?.status != OrderStatus.ACCEPTED && order?.status != OrderStatus.PREPARING) {
            _state.update { it.copy(error = "Can only cancel an accepted or preparing order") }
            return
        }
        doOrderApiCall(orderId) { apiService.cancelOrder(orderId) }
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

    fun refresh() {
        val filterAtStart = _state.value.selectedFilter
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val statusStr = filterAtStart?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr)
                if (response.isSuccessful) {
                    _state.update { current ->
                        if (current.selectedFilter == filterAtStart) {
                            current.copy(
                                orders = response.body() ?: current.orders,
                                isRefreshing = false,
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
    }
}
