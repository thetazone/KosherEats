package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
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
    val isUpdating: Boolean = false,
    val error: String? = null,
    val updateSuccess: String? = null,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    init {
        loadOrders()
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

    fun updateOrderStatus(orderId: String, newStatus: OrderStatus) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isUpdating = true, error = null, updateSuccess = null)
            try {
                if (newStatus == OrderStatus.COMPLETED) {
                    _state.value = _state.value.copy(isUpdating = false)
                    return@launch
                }

                val response = when (newStatus) {
                    OrderStatus.ACCEPTED -> apiService.acceptOrder(orderId)
                    OrderStatus.PREPARING -> apiService.markOrderPreparing(orderId)
                    OrderStatus.READY -> apiService.markOrderReady(orderId)
                    OrderStatus.CANCELLED -> apiService.rejectOrder(orderId)
                    else -> null
                }

                if (response == null) {
                    _state.value = _state.value.copy(
                        isUpdating = false,
                        error = "This order transition is not available",
                    )
                    return@launch
                }

                if (response.isSuccessful) {
                    val updatedOrder = response.body()
                    _state.value = _state.value.copy(
                        selectedOrder = updatedOrder,
                        orders = _state.value.orders.map {
                            if (it.id == orderId) updatedOrder ?: it else it
                        },
                        isUpdating = false,
                        updateSuccess = "Order updated to ${newStatus.name.lowercase().replace('_', ' ')}",
                    )
                } else {
                    _state.value = _state.value.copy(
                        isUpdating = false,
                        error = "Failed to update order status",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isUpdating = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, updateSuccess = null)
    }
}
