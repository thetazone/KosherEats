package com.greeneats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.POSIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
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
                    _state.value = _state.value.copy(isLoading = false, error = "Failed to load (${response.code()})")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Connection error")
            }
        }
    }

    suspend fun cloverConnectURL(): String? {
        return try {
            val response = apiService.cloverConnectURL()
            if (response.isSuccessful) {
                response.body()?.connectUrl
            } else {
                _state.value = _state.value.copy(error = "Couldn't start Clover connect (${response.code()})")
                null
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Connection error: ${e.message}")
            null
        }
    }

    /** Returns null on success, error message on failure. */
    suspend fun test(id: String): String? {
        return try {
            val response = apiService.testIntegration(id)
            if (response.isSuccessful) null
            else "HTTP ${response.code()}"
        } catch (e: Exception) {
            e.message ?: "Connection error"
        }
    }

    fun disconnect(id: String) {
        viewModelScope.launch {
            try {
                val response = apiService.disconnectIntegration(id)
                if (response.isSuccessful) {
                    load()
                } else {
                    _state.value = _state.value.copy(error = "Disconnect failed (${response.code()})")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Disconnect failed: ${e.message}")
            }
        }
    }
}
