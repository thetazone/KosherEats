package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
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
                orderEventBus.events.collect { pollSilently() }
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

    // Returns true on success so the caller can reset the backoff counter.
    private suspend fun pollSilently(): Boolean {
        return try {
            val filterAtStart = _state.value.selectedFilter
            val statusStr = filterAtStart?.name?.lowercase()
            val response = apiService.getOrders(status = statusStr)
            var succeeded = response.isSuccessful
            if (response.isSuccessful) {
                _state.update { current ->
                    if (current.selectedFilter == filterAtStart) {
                        current.copy(orders = response.body() ?: current.orders)
                    } else {
                        current
                    }
                }
            }
            _state.value.selectedOrder?.id?.let { id ->
                val detailResponse = apiService.getOrderDetail(id)
                if (detailResponse.isSuccessful) {
                    _state.update { it.copy(selectedOrder = detailResponse.body()) }
                } else {
                    succeeded = false
                }
            }
            succeeded
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    fun loadOrders(status: OrderStatus? = null) {
        viewModelScope.launch {
            _state.update { it.copy(
                isLoading = true,
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
            _state.update { it.copy(isLoading = true, error = null) }
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
        OrderStatus.PENDING to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
        OrderStatus.ACCEPTED to setOf(OrderStatus.PREPARING),
        OrderStatus.PREPARING to setOf(OrderStatus.READY),
        OrderStatus.READY to setOf(OrderStatus.COMPLETED),
        OrderStatus.PICKED_UP to setOf(OrderStatus.COMPLETED),
    )

    fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        if (_state.value.pendingOrderIds.contains(orderId)) return

        val currentOrder = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (currentOrder != null) {
            val allowed = allowedTransitions[currentOrder.status] ?: emptySet()
            val blocked = newStatus !in allowed ||
                (newStatus == OrderStatus.COMPLETED && !currentOrder.isPickup &&
                    (currentOrder.status == OrderStatus.READY || currentOrder.status == OrderStatus.PICKED_UP))
            if (blocked) {
                _state.update { it.copy(error = "Cannot change order from ${currentOrder.status.name.lowercase()} to ${newStatus.name.lowercase()}") }
                return
            }
        }

        val snapshotOrder = _state.value.selectedOrder
        viewModelScope.launch {
            _state.update { it.copy(
                pendingOrderIds = it.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            ) }
            try {
                val response = when (newStatus) {
                    OrderStatus.ACCEPTED -> apiService.acceptOrder(orderId)
                    OrderStatus.PREPARING -> apiService.markOrderPreparing(orderId)
                    OrderStatus.READY -> apiService.markOrderReady(orderId)
                    OrderStatus.COMPLETED -> apiService.completeOrder(orderId)
                    OrderStatus.CANCELLED -> apiService.rejectOrder(orderId)
                    else -> null
                }

                if (response == null) {
                    _state.update { it.copy(
                        selectedOrder = if (it.selectedOrder == snapshotOrder) snapshotOrder else it.selectedOrder,
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        error = "This order transition is not available",
                    ) }
                    return@launch
                }

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
                    _state.update { it.copy(
                        selectedOrder = updatedOrder,
                        orders = it.orders.map {
                            if (it.id == orderId) updatedOrder else it
                        },
                        pendingOrderIds = it.pendingOrderIds - orderId,
                        updateSuccess = "Order updated to ${newStatus.name.lowercase().replace('_', ' ')}",
                    ) }
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
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val statusStr = _state.value.selectedFilter?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        orders = response.body() ?: it.orders,
                        isRefreshing = false,
                    ) }
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
