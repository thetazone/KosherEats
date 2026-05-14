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
    val error: String? = null,
)

@HiltViewModel
class LinkedProvidersViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

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
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load (${resp.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun linkProvider(provider: String, token: String, nonce: String? = null) {
        viewModelScope.launch {
            try {
                val resp = api.linkProvider(LinkProviderRequest(provider = provider, token = token, nonce = nonce))
                if (resp.isSuccessful) load()
                else _uiState.update { it.copy(error = "Couldn't link $provider (${resp.code()})") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun unlinkProvider(provider: String) {
        viewModelScope.launch {
            try {
                val resp = api.unlinkProvider(provider)
                if (resp.isSuccessful) load()
                else _uiState.update { it.copy(error = "Couldn't unlink (${resp.code()})") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }
}
