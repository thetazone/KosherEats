package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.NotificationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationPreferencesUiState(
    val prefs: NotificationPreferences = NotificationPreferences(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class NotificationPreferencesViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationPreferencesUiState())
    val uiState: StateFlow<NotificationPreferencesUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val resp = api.getNotificationPreferences()
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            prefs = resp.body() ?: NotificationPreferences(),
                            isLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load preferences") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun setOrderUpdates(value: Boolean) = save(_uiState.value.prefs.copy(orderUpdates = value))
    fun setChatMessages(value: Boolean) = save(_uiState.value.prefs.copy(chatMessages = value))
    fun setPromotions(value: Boolean) = save(_uiState.value.prefs.copy(promotions = value))

    private fun save(prefs: NotificationPreferences) {
        val previous = _uiState.value.prefs
        _uiState.update { it.copy(prefs = prefs) }
        viewModelScope.launch {
            try {
                val resp = api.updateNotificationPreferences(prefs)
                if (!resp.isSuccessful) {
                    _uiState.update { it.copy(prefs = previous, error = "Couldn't save (${resp.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(prefs = previous, error = e.localizedMessage ?: "Network error") }
            }
        }
    }
}
