package com.greeneats.courier.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.courier.data.models.AvailableDelivery
import com.greeneats.courier.data.models.CourierOrder
import com.greeneats.courier.data.repository.CourierRepository
import com.greeneats.courier.services.LocationForegroundService
import com.greeneats.courier.services.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

/**
 * DashboardViewModel drives the main working experience: online/offline
 * toggle, available deliveries feed, active delivery card. Polls every 10s
 * while online — the courier app exchanges freshness for battery by not
 * holding a websocket.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: CourierRepository,
    private val locationTracker: LocationTracker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class State(
        val isOnline: Boolean = false,
        val available: List<AvailableDelivery> = emptyList(),
        val upcoming: List<AvailableDelivery> = emptyList(),
        val active: List<CourierOrder> = emptyList(),
        val isLoading: Boolean = false,
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val connectionLost: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("courier_state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var consecutiveFailures = 0
    private val isTogglingOnline = AtomicBoolean(false)
    private val isPickingUp = AtomicBoolean(false)
    private val isDelivering = AtomicBoolean(false)

    fun toggleOnline() = viewModelScope.launch {
        if (!isTogglingOnline.compareAndSet(false, true)) return@launch
        val target = !_state.value.isOnline
        if (target && !locationTracker.hasPermission()) {
            isTogglingOnline.set(false)
            _state.update { it.copy(errorMessage = "Location permission is required to go online") }
            return@launch
        }
        val loc = if (target) locationTracker.lastKnown() else null
        if (target && loc == null) {
            isTogglingOnline.set(false)
            _state.update { it.copy(errorMessage = "Could not get your location. Please try again.") }
            return@launch
        }
        // loc is non-null here when target==true (both guards above ensure it)
        repo.setOnline(target, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
            .onSuccess {
                isTogglingOnline.set(false)
                _state.update { it.copy(isOnline = target) }
                prefs.edit().putBoolean("was_online", target).apply()
                if (target) {
                    context.startForegroundService(
                        Intent(context, LocationForegroundService::class.java)
                    )
                    startPolling()
                } else {
                    context.stopService(Intent(context, LocationForegroundService::class.java))
                    stopPolling()
                    consecutiveFailures = 0
                    _state.update { it.copy(available = emptyList(), connectionLost = false) }
                }
            }
            .onFailure { e ->
                isTogglingOnline.set(false)
                _state.update { it.copy(errorMessage = e.message) }
            }
    }

    fun resumeIfActive() = viewModelScope.launch {
        if (!prefs.getBoolean("was_online", false)) return@launch
        if (!locationTracker.hasPermission()) return@launch
        val loc = locationTracker.lastKnown()
        repo.setOnline(true, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
            .onSuccess {
                _state.update { it.copy(isOnline = true) }
                context.startForegroundService(Intent(context, LocationForegroundService::class.java))
                startPolling()
            }
            .onFailure { prefs.edit().remove("was_online").apply() }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        val availableResult = repo.listAvailable()
        val activeResult = repo.listActive()
        val upcomingResult = repo.listUpcoming()
        _state.update { st ->
            st.copy(
                isLoading = false,
                available = availableResult.getOrNull() ?: st.available,
                active = activeResult.getOrNull() ?: st.active,
                upcoming = upcomingResult.getOrNull() ?: st.upcoming,
            )
        }
    }

    fun claim(delivery: AvailableDelivery) = viewModelScope.launch {
        if (_state.value.isSubmitting) return@launch
        _state.update { it.copy(isSubmitting = true) }
        try {
            repo.claim(delivery.id).onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        } finally {
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    fun pickup(order: CourierOrder) = viewModelScope.launch {
        if (!isPickingUp.compareAndSet(false, true)) return@launch
        _state.update { it.copy(isSubmitting = true) }
        try {
            repo.pickup(order.id).onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        } finally {
            isPickingUp.set(false)
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    fun deliver(order: CourierOrder) = viewModelScope.launch {
        if (!isDelivering.compareAndSet(false, true)) return@launch
        _state.update { it.copy(isSubmitting = true) }
        try {
            repo.deliver(order.id).onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        } finally {
            isDelivering.set(false)
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    private suspend fun fetchOnce() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        val availableResult = repo.listAvailable()
        val activeResult = repo.listActive()
        val upcomingResult = repo.listUpcoming()
        val bothFailed = availableResult.isFailure && activeResult.isFailure
        if (bothFailed) {
            consecutiveFailures++
        } else {
            consecutiveFailures = 0
        }
        _state.update { st ->
            st.copy(
                isLoading = false,
                available = availableResult.getOrNull() ?: st.available,
                active = activeResult.getOrNull() ?: st.active,
                upcoming = upcomingResult.getOrNull() ?: st.upcoming,
                connectionLost = consecutiveFailures >= 3,
            )
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                fetchOnce()
                delay(10_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        context.stopService(Intent(context, LocationForegroundService::class.java))
        if (_state.value.isOnline) {
            prefs.edit().remove("was_online").apply()
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                withContext(NonCancellable) {
                    try { withTimeout(3_000) { repo.setOnline(false, 0.0, 0.0) } } catch (_: Exception) {}
                }
            }
        }
        super.onCleared()
    }
}
