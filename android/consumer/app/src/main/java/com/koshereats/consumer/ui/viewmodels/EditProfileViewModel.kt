package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditProfileUiState(
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.getProfile()
                if (response.isSuccessful) {
                    val user = response.body()
                    _uiState.update {
                        it.copy(
                            firstName = user?.firstName ?: "",
                            lastName = user?.lastName ?: "",
                            phone = user?.phone ?: "",
                            isLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load profile") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun updateFirstName(value: String) = _uiState.update { it.copy(firstName = value, saved = false) }
    fun updateLastName(value: String) = _uiState.update { it.copy(lastName = value, saved = false) }
    fun updatePhone(value: String) = _uiState.update { it.copy(phone = value, saved = false) }

    fun saveProfile() {
        val state = _uiState.value
        if (state.firstName.isBlank() || state.lastName.isBlank()) {
            _uiState.update { it.copy(error = "First and last name are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.updateProfile(
                    User(
                        firstName = state.firstName.trim(),
                        lastName = state.lastName.trim(),
                        phone = state.phone.trim(),
                    )
                )
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isSaving = false, saved = true) }
                } else {
                    _uiState.update { it.copy(isSaving = false, error = "Couldn't save profile") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
