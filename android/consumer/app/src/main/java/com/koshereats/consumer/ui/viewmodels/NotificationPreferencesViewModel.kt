package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.NotificationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** Last server-confirmed preferences, used as the rollback target on save failure. */
    private var lastConfirmedPrefs: NotificationPreferences = NotificationPreferences()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val resp = api.getNotificationPreferences()
                if (resp.isSuccessful) {
                    val loaded = resp.body() ?: NotificationPreferences()
                    lastConfirmedPrefs = loaded
                    _uiState.update {
                        it.copy(
                            prefs = loaded,
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

    private var saveJob: Job? = null

    private fun save(prefs: NotificationPreferences) {
        // Apply the optimistic update immediately for responsive UI.
        _uiState.update { it.copy(prefs = prefs) }
        // Cancel the in-flight save so its rollback can't clobber subsequent toggles.
        // The new request carries all pending changes (prefs already reflects them).
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try {
                val resp = api.updateNotificationPreferences(prefs)
                if (resp.isSuccessful) {
                    lastConfirmedPrefs = prefs
                } else {
                    _uiState.update { it.copy(prefs = lastConfirmedPrefs, error = "Couldn't save (${resp.code()})") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(prefs = lastConfirmedPrefs, error = e.localizedMessage ?: "Network error") }
            }
        }
    }
}
