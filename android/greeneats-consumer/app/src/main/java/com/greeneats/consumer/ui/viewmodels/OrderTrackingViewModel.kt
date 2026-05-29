package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.greeneats.consumer.BuildConfig
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.api.TokenProvider
import com.greeneats.consumer.data.models.CourierLocationEvent
import com.greeneats.consumer.data.models.Order
import com.greeneats.consumer.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class OrderTrackingUiState(
    val order: Order? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

// Mirrors iOS OrderTrackingView: polls GET /orders/{id} every 8s and layers a
// courier-location SSE stream on top so the pin moves in near-real time.
@HiltViewModel
class OrderTrackingViewModel @Inject constructor(
    private val api: ApiService,
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager,
    private val tokenProvider: TokenProvider,
) : ViewModel() {

    companion object {
        /** Order poll interval in milliseconds. Matches iOS pollIntervalNanos (8s). */
        const val POLL_INTERVAL_MS = 8_000L
        const val ERROR_LIVE_TRACKING = "Live tracking unavailable. Retrying…"
        const val ERROR_SSE_STATUS = "SSE status"
    }

    private val _uiState = MutableStateFlow(OrderTrackingUiState())
    val uiState: StateFlow<OrderTrackingUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var streamJob: Job? = null
    private var currentOrderId: String? = null
    @Volatile private var completedNormally = false
    @Volatile private var consecutiveSseUnauthorized = 0
    private val gson = Gson()
    private val sseClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun start(orderId: String) {
        if (orderId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Invalid order ID") }
            return
        }
        if (currentOrderId == orderId && (pollJob?.isActive == true || completedNormally)) return
        completedNormally = false
        consecutiveSseUnauthorized = 0
        currentOrderId = orderId
        stopInternal()

        viewModelScope.launch {
            loadOnce(orderId)
            pollJob = launchPollLoop(orderId)
            streamJob = launchLocationStream(orderId)
        }
    }

    fun refresh() {
        val id = currentOrderId ?: return
        viewModelScope.launch { loadOnce(id) }
    }

    /** Stop polling and SSE streaming. Called when the screen leaves the
     *  foreground so we don't drain battery with background network. */
    fun stop() {
        stopInternal()
    }

    /** Resume polling/SSE after a stop(). Safe to call if already running. */
    fun resume() {
        val id = currentOrderId ?: return
        if (completedNormally) return
        if (pollJob?.isActive == true) return
        pollJob = launchPollLoop(id)
        streamJob = launchLocationStream(id)
    }

    override fun onCleared() {
        stopInternal()
        super.onCleared()
    }

    private fun stopInternal() {
        pollJob?.cancel(); pollJob = null
        streamJob?.cancel(); streamJob = null
    }

    private suspend fun loadOnce(orderId: String) {
        try {
            val resp = api.getOrder(orderId)
            if (resp.isSuccessful) {
                _uiState.update { it.copy(order = resp.body(), isLoading = false, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Couldn't load order (${resp.code()})") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
        }
    }

    private fun launchPollLoop(orderId: String): Job = viewModelScope.launch {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            loadOnce(orderId)
            val status = _uiState.value.order?.status ?: continue
            if (!status.isActive) { completedNormally = true; break }
        }
    }

    fun retryStream() {
        val id = currentOrderId ?: return
        consecutiveSseUnauthorized = 0
        streamJob?.cancel()
        streamJob = launchLocationStream(id)
    }

    // SSE: hold a GET open on /orders/{id}/location/stream, parse `data:` lines,
    // splice lat/lng into the in-memory courier whenever a location event arrives.
    // Reconnects with exponential backoff (3s..30s) on any error.
    private fun launchLocationStream(orderId: String): Job = viewModelScope.launch(Dispatchers.IO) {
        val url = BuildConfig.BASE_URL.trimEnd('/') + "/orders/$orderId/location/stream"
        var backoffMs = 3_000L

        // Ensure the encrypted-prefs load has finished before opening the SSE
        // connection. AuthInterceptor now does the same via runBlocking, so this
        // suspends rather than polling with a deadline.
        var currentToken = tokenProvider.awaitToken()

        while (isActive) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                // Set Authorization explicitly so the first SSE connection doesn't
                // rely solely on the interceptor (which may race with token refresh).
                currentToken?.let { requestBuilder.header("Authorization", "Bearer $it") }
                val request = requestBuilder.build()
                sseClient.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        consecutiveSseUnauthorized++
                        if (consecutiveSseUnauthorized >= 2) {
                            pollJob?.cancel(); pollJob = null
                            streamJob = null
                            _uiState.update { it.copy(order = null, errorMessage = "Session expired", isLoading = false) }
                            sessionManager.signalLogout()
                            return@launch
                        }
                        throw RuntimeException("SSE 401 — will retry after backoff")
                    }
                    consecutiveSseUnauthorized = 0
                    if (!response.isSuccessful) throw RuntimeException("$ERROR_SSE_STATUS ${response.code}")
                    val source = response.body?.source() ?: throw RuntimeException("no SSE body")
                    _uiState.update { it.copy(errorMessage = null) }
                    backoffMs = 3_000L
                    val dataBuf = StringBuilder()
                    while (isActive && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (dataBuf.isNotEmpty()) {
                                val json = dataBuf.toString()
                                dataBuf.setLength(0)
                                handleLocationEvent(json)
                            }
                        } else if (line.startsWith("data:")) {
                            if (dataBuf.length > 65536) dataBuf.clear()
                            dataBuf.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(errorMessage = ERROR_LIVE_TRACKING) }
            }
            if (!isActive) break
            val status = _uiState.value.order?.status
            if (status != null && !status.isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            // After backoff, refresh the token so the next SSE attempt carries a
            // valid credential. If the refresh succeeds, reset the 401 counter so a
            // transient token-rotation 401 doesn't snowball into a forced logout.
            val refreshed = tokenProvider.awaitToken()
            if (refreshed != null) {
                currentToken = refreshed
                consecutiveSseUnauthorized = 0
            }
        }
    }

    private suspend fun handleLocationEvent(json: String) = withContext(Dispatchers.Default) {
        val event = runCatching { gson.fromJson(json, CourierLocationEvent::class.java) }.getOrNull()
            ?: return@withContext
        // Validate coordinate bounds (mirrors iOS OrderTrackingViewModel):
        // lat must be in [-90, 90], lng in [-180, 180], and neither can be 0/0.
        if (event.lat < -90 || event.lat > 90 ||
            event.lng < -180 || event.lng > 180 ||
            (event.lat == 0.0 && event.lng == 0.0)
        ) return@withContext
        _uiState.update { state ->
            val current = state.order ?: return@update state
            val courier = current.courier?.copy(lat = event.lat, lng = event.lng) ?: return@update state
            state.copy(order = current.copy(courier = courier))
        }
    }
}
