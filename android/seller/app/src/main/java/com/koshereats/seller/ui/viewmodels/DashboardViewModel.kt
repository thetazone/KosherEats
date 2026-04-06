package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.DashboardStats
import com.koshereats.seller.data.models.Order
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(
    val stats: DashboardStats = DashboardStats(),
    val activeOrders: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val statsResponse = apiService.getDashboardStats()
                val ordersResponse = apiService.getActiveOrders()

                _state.value = _state.value.copy(
                    stats = statsResponse.body() ?: DashboardStats(),
                    activeOrders = ordersResponse.body() ?: emptyList(),
                    isLoading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load dashboard: ${e.localizedMessage}",
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            try {
                val statsResponse = apiService.getDashboardStats()
                val ordersResponse = apiService.getActiveOrders()

                _state.value = _state.value.copy(
                    stats = statsResponse.body() ?: _state.value.stats,
                    activeOrders = ordersResponse.body() ?: _state.value.activeOrders,
                    isRefreshing = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isRefreshing = false)
            }
        }
    }
}
