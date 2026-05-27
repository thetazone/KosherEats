package com.greeneats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.POSIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IntegrationsState(
    val integrations: List<POSIntegration> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    companion object {
        private const val ERR_LOAD = "Failed to load integrations"
        private const val ERR_CONNECT = "Couldn't start Clover connect"
        private const val ERR_TEST = "Integration test failed"
        private const val ERR_DISCONNECT = "Disconnect failed"
        private const val ERR_CONNECTION = "Connection error"
    }

    private val _state = MutableStateFlow(IntegrationsState())
    val state: StateFlow<IntegrationsState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.listIntegrations()
                if (response.isSuccessful) {
                    _state.value = IntegrationsState(integrations = response.body() ?: emptyList(), isLoading = false)
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = "$ERR_LOAD (${response.code()})")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(isLoading = false, error = "$ERR_CONNECTION: ${e.message}")
            }
        }
    }

    suspend fun cloverConnectURL(): String? {
        return try {
            val response = apiService.cloverConnectURL()
            if (response.isSuccessful) {
                response.body()?.connectUrl
            } else {
                _state.value = _state.value.copy(error = "$ERR_CONNECT (${response.code()})")
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = _state.value.copy(error = "$ERR_CONNECTION: ${e.message}")
            null
        }
    }

    /** Returns null on success, error message on failure. */
    suspend fun test(id: String): String? {
        return try {
            val response = apiService.testIntegration(id)
            if (response.isSuccessful) {
                null
            } else {
                val msg = "$ERR_TEST: HTTP ${response.code()}"
                _state.value = _state.value.copy(error = msg)
                msg
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = "$ERR_CONNECTION: ${e.message}"
            _state.value = _state.value.copy(error = msg)
            msg
        }
    }

    fun disconnect(id: String) {
        viewModelScope.launch {
            try {
                val response = apiService.disconnectIntegration(id)
                if (response.isSuccessful) {
                    load()
                } else {
                    _state.value = _state.value.copy(error = "$ERR_DISCONNECT (${response.code()})")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = "$ERR_DISCONNECT: ${e.message}")
            }
        }
    }
}
