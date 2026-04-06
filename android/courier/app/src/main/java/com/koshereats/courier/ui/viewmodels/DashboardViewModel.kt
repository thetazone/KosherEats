package com.koshereats.courier.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.courier.data.models.AvailableDelivery
import com.koshereats.courier.data.models.CourierOrder
import com.koshereats.courier.data.repository.CourierRepository
import com.koshereats.courier.services.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

    data class State(
        val isOnline: Boolean = false,
        val available: List<AvailableDelivery> = emptyList(),
        val active: List<CourierOrder> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun toggleOnline() = viewModelScope.launch {
        val target = !_state.value.isOnline
        val loc = locationTracker.lastKnown()
        repo.setOnline(target, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
            .onSuccess {
                _state.update { it.copy(isOnline = target) }
                if (target) {
                    locationTracker.start(onLocation = { lat, lng, heading, speed ->
                        viewModelScope.launch {
                            repo.sendLocation(lat, lng, heading, speed)
                        }
                    })
                    startPolling()
                } else {
                    locationTracker.stop()
                    stopPolling()
                    _state.update { it.copy(available = emptyList()) }
                }
            }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        val availableResult = repo.listAvailable()
        val activeResult = repo.listActive()
        _state.update { st ->
            st.copy(
                isLoading = false,
                available = availableResult.getOrNull() ?: st.available,
                active = activeResult.getOrNull() ?: st.active,
            )
        }
    }

    fun claim(delivery: AvailableDelivery) = viewModelScope.launch {
        repo.claim(delivery.id).onSuccess { refresh() }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
    }

    fun pickup(order: CourierOrder) = viewModelScope.launch {
        repo.pickup(order.id).onSuccess { refresh() }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
    }

    fun deliver(order: CourierOrder) = viewModelScope.launch {
        repo.deliver(order.id).onSuccess { refresh() }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                refresh()
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
        locationTracker.stop()
        super.onCleared()
    }
}
