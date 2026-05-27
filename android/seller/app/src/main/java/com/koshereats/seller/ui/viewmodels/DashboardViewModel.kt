package com.koshereats.seller.ui.viewmodels

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.models.DashboardStats
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.push.OrderEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.sync.Mutex
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var eventJob: Job? = null
    private val pollMutex = Mutex()
    private var loadJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isFirstPoll = true
    @Volatile private var isPollingActive = false
    @Volatile private var lastSuccessfulFetchMs = 0L
    private var lastFetchedRestaurantId: String? = null

    init {
        loadDashboard()
        viewModelScope.launch {
            NetworkModule.restaurantChanged.collect {
                // Cancel any in-flight dashboard load so it cannot apply the previous
                // restaurant's stats to the freshly-cleared state.
                loadJob?.cancel()
                loadJob = null
                _state.value = _state.value.copy(stats = DashboardStats(), activeOrders = emptyList())
                lastSuccessfulFetchMs = 0L
                loadDashboard()
            }
        }
    }

    // Called by the screen composable via DisposableEffect so polling (and FCM-triggered
    // refreshes) only run while that screen is in composition.
    fun startPolling() {
        if (pollingJob?.isActive == true) {
            // VM survived navigation; proactively refresh if data is stale.
            if (System.currentTimeMillis() - lastSuccessfulFetchMs > STALE_THRESHOLD_MS) {
                viewModelScope.launch { pollSilently() }
            }
            return
        }
        isPollingActive = true
        launchPollingJob()
        if (eventJob?.isActive != true) {
            eventJob = viewModelScope.launch {
                orderEventBus.events.collect { pollSilently() }
            }
        }
        registerNetworkCallback()
    }

    private fun launchPollingJob() {
        pollingJob = viewModelScope.launch {
            if (!isPollingActive) return@launch
            // Delay the very first periodic poll so it doesn't race the init loadDashboard().
            // Network-reconnect re-launches skip this because isFirstPoll is already false.
            if (isFirstPoll) {
                isFirstPoll = false
                delay(BACKOFF_DELAYS[0])
            }
            var consecutiveFailures = 0
            while (true) {
                val succeeded = pollSilently()
                consecutiveFailures = if (succeeded) 0 else consecutiveFailures + 1
                delay(BACKOFF_DELAYS[consecutiveFailures.coerceAtMost(BACKOFF_DELAYS.lastIndex)])
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Cancel the sleeping backoff and restart immediately on reconnect.
                pollingJob?.cancel()
                launchPollingJob()
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
        isPollingActive = false
        unregisterNetworkCallback()
        pollingJob?.cancel()
        pollingJob = null
        eventJob?.cancel()
        eventJob = null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterNetworkCallback()
    }

    fun loadDashboard() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val currentRestaurantId = NetworkModule.cachedRestaurantId
            // When the active restaurant differs from the last successful fetch, blank
            // stats and activeOrders immediately so the UI does not flash the prior
            // restaurant's data under the newly selected restaurant's name.
            if (currentRestaurantId != lastFetchedRestaurantId) {
                _state.update { it.copy(stats = DashboardStats(), activeOrders = emptyList(), isLoading = true, error = null) }
            } else {
                // On same-restaurant reloads (poll, pull-to-refresh) keep existing data
                // visible so the UI does not flash to zero.
                _state.update { it.copy(isLoading = true, error = null) }
            }
            try {
                kotlinx.coroutines.withTimeout(45_000L) {
                    coroutineScope {
                        val statsDeferred = async { apiService.getDashboardStats() }
                        val ordersDeferred = async { apiService.getOrders(status = null, limit = 100) }
                        val statsResponse = statsDeferred.await()
                        val ordersResponse = ordersDeferred.await()

                        val statsOk = statsResponse.isSuccessful
                        val ordersOk = ordersResponse.isSuccessful
                        if (statsOk || ordersOk) {
                            lastSuccessfulFetchMs = System.currentTimeMillis()
                            lastFetchedRestaurantId = currentRestaurantId
                        }
                        _state.update { s ->
                            s.copy(
                                stats = if (statsOk) statsResponse.body() ?: DashboardStats() else s.stats,
                                activeOrders = if (ordersOk) ordersResponse.body().orEmpty().filter { it.status.isActive } else s.activeOrders,
                                isLoading = false,
                                error = if (!statsOk && !ordersOk) "Failed to load dashboard (HTTP ${statsResponse.code()})" else null,
                            )
                        }
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
    // tryLock ensures at most one poll is in flight at a time (FCM vs. periodic timer).
    private suspend fun pollSilently(): Boolean {
        if (!pollMutex.tryLock()) return true
        return try {
            coroutineScope {
                val statsDeferred = async { apiService.getDashboardStats() }
                val ordersDeferred = async { apiService.getOrders(status = null, limit = 100) }
                val statsResponse = statsDeferred.await()
                val ordersResponse = ordersDeferred.await()
                val statsOk = statsResponse.isSuccessful
                val ordersOk = ordersResponse.isSuccessful
                if (statsOk || ordersOk) {
                    lastSuccessfulFetchMs = System.currentTimeMillis()
                    _state.update { s ->
                        s.copy(
                            stats = if (statsOk) statsResponse.body() ?: s.stats else s.stats,
                            activeOrders = if (ordersOk) ordersResponse.body().orEmpty().filter { it.status.isActive } else s.activeOrders,
                        )
                    }
                }
                statsOk && ordersOk
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        } finally {
            pollMutex.unlock()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                coroutineScope {
                    val statsDeferred = async { apiService.getDashboardStats() }
                    val ordersDeferred = async { apiService.getOrders(status = null, limit = 100) }
                    val statsResponse = statsDeferred.await()
                    val ordersResponse = ordersDeferred.await()

                    val statsOk = statsResponse.isSuccessful
                    val ordersOk = ordersResponse.isSuccessful
                    _state.update { s ->
                        s.copy(
                            stats = if (statsOk) statsResponse.body() ?: s.stats else s.stats,
                            activeOrders = if (ordersOk) (ordersResponse.body() ?: s.activeOrders).filter { it.status.isActive } else s.activeOrders,
                            isRefreshing = false,
                            error = if (!statsOk && !ordersOk) "Failed to refresh dashboard (HTTP ${statsResponse.code()})" else null,
                        )
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
        private const val STALE_THRESHOLD_MS = 30_000L
    }
}
