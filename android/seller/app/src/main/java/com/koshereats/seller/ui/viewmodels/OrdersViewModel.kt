package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

    init {
        loadOrders()
        viewModelScope.launch {
            orderEventBus.events.collect { pollSilently() }
        }
        // Polling fallback: FCM is unreliable on OEM devices with aggressive doze/battery savers.
        // repeatOnLifecycle(STARTED) suspends while the app is backgrounded, so polling only
        // runs when the user has the app in the foreground.
        viewModelScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(30_000)
                    pollSilently()
                }
            }
        }
    }

    private suspend fun pollSilently() {
        try {
            val statusStr = _state.value.selectedFilter?.name?.lowercase()
            val response = apiService.getOrders(status = statusStr)
            if (response.isSuccessful) {
                _state.update { it.copy(orders = response.body() ?: it.orders) }
            }
            _state.value.selectedOrder?.id?.let { id ->
                val detailResponse = apiService.getOrderDetail(id)
                if (detailResponse.isSuccessful) {
                    _state.update { it.copy(selectedOrder = detailResponse.body()) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Silent — next poll or FCM delivery will recover.
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
                (currentOrder.status == OrderStatus.READY && newStatus == OrderStatus.COMPLETED && !currentOrder.isPickup)
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

    fun clearMessages() {
        _state.update { it.copy(error = null, updateSuccess = null) }
    }
}
