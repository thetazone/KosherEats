package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            orderEventBus.events.collect {
                _state.value.selectedOrder?.let { loadOrderDetail(it.id) }
                loadOrders(status = _state.value.selectedFilter)
            }
        }
    }

    fun loadOrders(status: OrderStatus? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                error = null,
                selectedFilter = status,
            )
            try {
                val statusStr = status?.name?.lowercase()
                val response = apiService.getOrders(status = statusStr)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        orders = response.body() ?: emptyList(),
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load orders",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun loadOrderDetail(orderId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.getOrderDetail(orderId)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        selectedOrder = response.body(),
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load order details",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    private val allowedTransitions = mapOf(
        OrderStatus.PENDING to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
        OrderStatus.ACCEPTED to setOf(OrderStatus.PREPARING, OrderStatus.CANCELLED),
        OrderStatus.PREPARING to setOf(OrderStatus.READY),
        OrderStatus.READY to setOf(OrderStatus.COMPLETED),
        OrderStatus.PICKED_UP to setOf(OrderStatus.COMPLETED),
    )

    fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        if (_state.value.pendingOrderIds.contains(orderId)) return

        val currentOrder = _state.value.selectedOrder?.takeIf { it.id == orderId }
            ?: _state.value.orders.find { it.id == orderId }
        if (currentOrder != null && newStatus !in (allowedTransitions[currentOrder.status] ?: emptySet())) {
            _state.value = _state.value.copy(error = "Cannot change order from ${currentOrder.status.name.lowercase()} to ${newStatus.name.lowercase()}")
            return
        }

        val snapshotOrder = _state.value.selectedOrder
        viewModelScope.launch {
            _state.value = _state.value.copy(
                pendingOrderIds = _state.value.pendingOrderIds + orderId,
                error = null,
                updateSuccess = null,
            )
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
                    _state.value = _state.value.copy(
                        selectedOrder = if (_state.value.selectedOrder == snapshotOrder) snapshotOrder else _state.value.selectedOrder,
                        pendingOrderIds = _state.value.pendingOrderIds - orderId,
                        error = "This order transition is not available",
                    )
                    return@launch
                }

                if (response.isSuccessful) {
                    val updatedOrder = response.body()
                    if (updatedOrder == null) {
                        _state.value = _state.value.copy(
                            selectedOrder = if (_state.value.selectedOrder == snapshotOrder) snapshotOrder else _state.value.selectedOrder,
                            pendingOrderIds = _state.value.pendingOrderIds - orderId,
                            error = "Failed to update order status",
                        )
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        selectedOrder = updatedOrder,
                        orders = _state.value.orders.map {
                            if (it.id == orderId) updatedOrder else it
                        },
                        pendingOrderIds = _state.value.pendingOrderIds - orderId,
                        updateSuccess = "Order updated to ${newStatus.name.lowercase().replace('_', ' ')}",
                    )
                } else {
                    _state.value = _state.value.copy(
                        selectedOrder = if (_state.value.selectedOrder == snapshotOrder) snapshotOrder else _state.value.selectedOrder,
                        pendingOrderIds = _state.value.pendingOrderIds - orderId,
                        error = "Failed to update order status",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    selectedOrder = if (_state.value.selectedOrder == snapshotOrder) snapshotOrder else _state.value.selectedOrder,
                    pendingOrderIds = _state.value.pendingOrderIds - orderId,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, updateSuccess = null)
    }
}
