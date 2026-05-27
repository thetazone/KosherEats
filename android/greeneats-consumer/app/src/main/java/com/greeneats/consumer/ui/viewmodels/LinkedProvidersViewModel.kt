package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.LinkProviderRequest
import com.greeneats.consumer.data.models.LinkedProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkedProvidersUiState(
    val providers: List<LinkedProvider> = emptyList(),
    val isLoading: Boolean = true,
    val isUnlinking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LinkedProvidersViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    companion object {
        const val ERROR_LOAD_PROVIDERS = "Couldn't load"
        const val ERROR_LINK_PROVIDER = "Couldn't link"
        const val ERROR_UNLINK_PROVIDER = "Couldn't unlink"
        const val ERROR_NETWORK = "Network error"
    }

    private val _uiState = MutableStateFlow(LinkedProvidersUiState())
    val uiState: StateFlow<LinkedProvidersUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val resp = api.getLinkedProviders()
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(providers = resp.body().orEmpty(), isLoading = false)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "$ERROR_LOAD_PROVIDERS (${resp.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: ERROR_NETWORK) }
            }
        }
    }

    fun linkProvider(provider: String, token: String, nonce: String? = null) {
        viewModelScope.launch {
            try {
                val resp = api.linkProvider(LinkProviderRequest(provider = provider, token = token, nonce = nonce))
                if (resp.isSuccessful) load()
                else _uiState.update { it.copy(error = "$ERROR_LINK_PROVIDER $provider (${resp.code()})") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: ERROR_NETWORK) }
            }
        }
    }

    fun unlinkProvider(provider: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUnlinking = true, error = null) }
            try {
                val resp = api.unlinkProvider(provider)
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(isUnlinking = false) }
                    load()
                } else {
                    _uiState.update { it.copy(isUnlinking = false, error = "$ERROR_UNLINK_PROVIDER (${resp.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isUnlinking = false, error = e.localizedMessage ?: ERROR_NETWORK) }
            }
        }
    }
}
