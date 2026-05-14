package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.DashboardStats
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    private val orderEventBus: OrderEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        loadDashboard()
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

    fun loadDashboard() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val statsResponse = apiService.getDashboardStats()
                val ordersResponse = apiService.getOrders()

                if (!statsResponse.isSuccessful || !ordersResponse.isSuccessful) {
                    val code = if (!statsResponse.isSuccessful) statsResponse.code() else ordersResponse.code()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load dashboard (HTTP $code)",
                    )
                    return@launch
                }

                val activeOrders = ordersResponse.body().orEmpty()
                    .filter { it.status.isActive }
                _state.value = _state.value.copy(
                    stats = statsResponse.body() ?: DashboardStats(),
                    activeOrders = activeOrders,
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

    private suspend fun pollSilently() {
        try {
            val statsResponse = apiService.getDashboardStats()
            val ordersResponse = apiService.getOrders()
            if (statsResponse.isSuccessful && ordersResponse.isSuccessful) {
                val activeOrders = ordersResponse.body().orEmpty()
                    .filter { it.status.isActive }
                _state.value = _state.value.copy(
                    stats = statsResponse.body() ?: _state.value.stats,
                    activeOrders = activeOrders,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Silent — next poll or FCM delivery will recover.
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            try {
                val statsResponse = apiService.getDashboardStats()
                val ordersResponse = apiService.getOrders()

                if (!statsResponse.isSuccessful || !ordersResponse.isSuccessful) {
                    val code = if (!statsResponse.isSuccessful) statsResponse.code() else ordersResponse.code()
                    _state.value = _state.value.copy(
                        isRefreshing = false,
                        error = "Failed to refresh dashboard (HTTP $code)",
                    )
                    return@launch
                }

                val activeOrders = ordersResponse.body()
                    ?.filter { it.status.isActive }
                    ?: _state.value.activeOrders
                _state.value = _state.value.copy(
                    stats = statsResponse.body() ?: _state.value.stats,
                    activeOrders = activeOrders,
                    isRefreshing = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    error = "Failed to refresh dashboard: ${e.localizedMessage}",
                )
            }
        }
    }
}
