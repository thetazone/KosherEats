package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.OrderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderDetailUiState(
    val order: Order? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isCancelling: Boolean = false,
    val cancelError: String? = null,
)

/**
 * Backs the non-active OrderDetailScreen receipt. Fetches a single order via
 * GET /orders/{id} and exposes a cancel path (PATCH /orders/{id}/cancel) gated
 * by the same cancellable statuses the rest of the app uses (pending/accepted).
 * Mirrors iOS OrderViewModel.loadOrder / cancelOrder.
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    fun load(orderId: String) {
        if (_uiState.value.order?.id == orderId) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val resp = api.getOrder(orderId)
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(order = resp.body(), isLoading = false) }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Couldn't load order (${resp.code()})")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Something went wrong. Please try again.")
                }
            }
        }
    }

    fun retry(orderId: String) {
        _uiState.update { it.copy(order = null) }
        load(orderId)
    }

    /**
     * Cancels the order. The backend orders.cancel route only accepts orders in
     * pending/accepted state; the UI hides the button otherwise, but we surface a
     * failure gracefully if the server still rejects (e.g. a status race).
     */
    fun cancelOrder(orderId: String) {
        if (_uiState.value.isCancelling) return
        _uiState.update { it.copy(isCancelling = true, cancelError = null) }
        viewModelScope.launch {
            try {
                val resp = api.cancelOrder(orderId)
                if (resp.isSuccessful) {
                    val updated = resp.body()
                    _uiState.update {
                        it.copy(
                            isCancelling = false,
                            order = updated ?: it.order?.copy(status = OrderStatus.CANCELLED),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isCancelling = false, cancelError = "Couldn't cancel order (${resp.code()})")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(isCancelling = false, cancelError = "Network error. Please try again.")
                }
            }
        }
    }

    fun dismissCancelError() {
        _uiState.update { it.copy(cancelError = null) }
    }
}
