package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.DashboardStats
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private var pollingJob: Job? = null
    private var loadJob: Job? = null

    init {
        loadDashboard()
    }

    // Called by the screen composable via DisposableEffect so polling (and FCM-triggered
    // refreshes) only run while that screen is in composition.
    fun startPolling() {
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
        pollingJob?.cancel()
        pollingJob = null
    }

    fun loadDashboard() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.stats.todayOrders == 0 && it.activeOrders.isEmpty(), error = null) }
            try {
                coroutineScope {
                    val statsDeferred = async { apiService.getDashboardStats() }
                    val ordersDeferred = async { apiService.getOrders(status = null, limit = 200) }
                    val statsResponse = statsDeferred.await()
                    val ordersResponse = ordersDeferred.await()

                    if (!statsResponse.isSuccessful || !ordersResponse.isSuccessful) {
                        val code = if (!statsResponse.isSuccessful) statsResponse.code() else ordersResponse.code()
                        _state.update { it.copy(
                            isLoading = false,
                            error = "Failed to load dashboard (HTTP $code)",
                        ) }
                    } else {
                        _state.update { it.copy(
                            stats = statsResponse.body() ?: DashboardStats(),
                            activeOrders = ordersResponse.body().orEmpty().filter { it.status.isActive },
                            isLoading = false,
                        ) }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "Failed to load dashboard: ${e.localizedMessage}",
                ) }
            }
        }
    }

    // Returns true on success so the caller can reset the backoff counter.
    private suspend fun pollSilently(): Boolean {
        return try {
            coroutineScope {
                val statsDeferred = async { apiService.getDashboardStats() }
                val ordersDeferred = async { apiService.getOrders(status = null, limit = 200) }
                val statsResponse = statsDeferred.await()
                val ordersResponse = ordersDeferred.await()
                if (statsResponse.isSuccessful && ordersResponse.isSuccessful) {
                    _state.update { s ->
                        s.copy(
                            stats = statsResponse.body() ?: s.stats,
                            activeOrders = ordersResponse.body().orEmpty().filter { it.status.isActive },
                        )
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                coroutineScope {
                    val statsDeferred = async { apiService.getDashboardStats() }
                    val ordersDeferred = async { apiService.getOrders(status = null, limit = 200) }
                    val statsResponse = statsDeferred.await()
                    val ordersResponse = ordersDeferred.await()

                    if (!statsResponse.isSuccessful || !ordersResponse.isSuccessful) {
                        val code = if (!statsResponse.isSuccessful) statsResponse.code() else ordersResponse.code()
                        _state.update { it.copy(
                            isRefreshing = false,
                            error = "Failed to refresh dashboard (HTTP $code)",
                        ) }
                    } else {
                        _state.update { s ->
                            s.copy(
                                stats = statsResponse.body() ?: s.stats,
                                activeOrders = (ordersResponse.body() ?: s.activeOrders).filter { it.status.isActive },
                                isRefreshing = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isRefreshing = false,
                    error = "Failed to refresh dashboard: ${e.localizedMessage}",
                ) }
            }
        }
    }

    companion object {
        // Backoff delays for consecutive poll failures: 30s → 1m → 2m → 4m → 5m (cap).
        private val BACKOFF_DELAYS = longArrayOf(30_000, 60_000, 120_000, 240_000, 300_000)
    }
}
