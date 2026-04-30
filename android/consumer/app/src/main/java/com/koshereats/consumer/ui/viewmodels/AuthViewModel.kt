package com.koshereats.consumer.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.api.PrefsKeys
import com.koshereats.consumer.data.api.TokenProvider
import com.koshereats.consumer.data.models.LoginRequest
import com.koshereats.consumer.data.models.RegisterRequest
import com.koshereats.consumer.data.models.SocialLoginRequest
import com.koshereats.consumer.data.models.User
import com.koshereats.consumer.data.session.SessionManager
import com.koshereats.consumer.push.PushBootstrap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoggedIn: Boolean = false,
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginEmail: String = "",
    val loginPassword: String = "",
    val registerFirstName: String = "",
    val registerLastName: String = "",
    val registerEmail: String = "",
    val registerPhone: String = "",
    val registerPassword: String = "",
    val registerConfirmPassword: String = "",
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val apiService: ApiService,
    private val dataStore: DataStore<Preferences>,
    private val sessionManager: SessionManager,
    private val tokenProvider: TokenProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkAuthStatus()
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            val token = tokenProvider.awaitToken()
            if (token != null) {
                try {
                    val response = apiService.getProfile()
                    when {
                        response.isSuccessful -> {
                            _uiState.update { it.copy(isLoggedIn = true, user = response.body()) }
                            // Resumed session — refresh the FCM token on the
                            // backend in case it rotated since last launch.
                            PushBootstrap.registerCurrentToken(apiService)
                        }
                        response.code() == 401 -> clearAuth()
                        // 5xx or other transient error: keep session alive.
                        else -> _uiState.update { it.copy(isLoggedIn = true) }
                    }
                } catch (e: Exception) {
                    // Network/IO error: transient, keep session alive.
                    _uiState.update { it.copy(isLoggedIn = true) }
                }
            }
        }
    }

    fun updateLoginEmail(value: String) = _uiState.update { it.copy(loginEmail = value) }
    fun updateLoginPassword(value: String) = _uiState.update { it.copy(loginPassword = value) }
    fun updateRegisterFirstName(value: String) = _uiState.update { it.copy(registerFirstName = value) }
    fun updateRegisterLastName(value: String) = _uiState.update { it.copy(registerLastName = value) }
    fun updateRegisterEmail(value: String) = _uiState.update { it.copy(registerEmail = value) }
    fun updateRegisterPhone(value: String) = _uiState.update { it.copy(registerPhone = value) }
    fun updateRegisterPassword(value: String) = _uiState.update { it.copy(registerPassword = value) }
    fun updateRegisterConfirmPassword(value: String) = _uiState.update { it.copy(registerConfirmPassword = value) }

    fun login() {
        val state = _uiState.value
        if (state.loginEmail.isBlank() || state.loginPassword.isBlank()) {
            _uiState.update { it.copy(error = "Please fill in all fields") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.login(
                    LoginRequest(email = state.loginEmail, password = state.loginPassword)
                )
                if (response.isSuccessful) {
                    val authData = response.body() ?: run {
                        _uiState.update { it.copy(isLoading = false, error = "Unexpected server response") }
                        return@launch
                    }
                    saveAuth(authData.token, authData.refreshToken, authData.user.id)
                    _uiState.update {
                        it.copy(
                            isLoggedIn = true,
                            user = authData.user,
                            isLoading = false,
                            loginEmail = "",
                            loginPassword = "",
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Login failed",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun register() {
        val state = _uiState.value
        if (state.registerEmail.isBlank() || state.registerPassword.isBlank() ||
            state.registerFirstName.isBlank() || state.registerLastName.isBlank()
        ) {
            _uiState.update { it.copy(error = "Please fill in all required fields") }
            return
        }
        if (state.registerPassword != state.registerConfirmPassword) {
            _uiState.update { it.copy(error = "Passwords do not match") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.register(
                    RegisterRequest(
                        email = state.registerEmail,
                        password = state.registerPassword,
                        firstName = state.registerFirstName,
                        lastName = state.registerLastName,
                        phone = state.registerPhone,
                    )
                )
                if (response.isSuccessful) {
                    val authData = response.body() ?: run {
                        _uiState.update { it.copy(isLoading = false, error = "Unexpected server response") }
                        return@launch
                    }
                    saveAuth(authData.token, authData.refreshToken, authData.user.id)
                    _uiState.update {
                        it.copy(
                            isLoggedIn = true,
                            user = authData.user,
                            isLoading = false,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Registration failed",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun socialLogin(provider: String, token: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.socialLogin(
                    SocialLoginRequest(
                        provider = provider,
                        token = token,
                        firstName = firstName,
                        lastName = lastName,
                    )
                )
                if (response.isSuccessful) {
                    val authData = response.body() ?: run {
                        _uiState.update { it.copy(isLoading = false, error = "Unexpected server response") }
                        return@launch
                    }
                    saveAuth(authData.token, authData.refreshToken, authData.user.id)
                    _uiState.update {
                        it.copy(
                            isLoggedIn = true,
                            user = authData.user,
                            isLoading = false,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Social login failed",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun signInWithGoogle() {
        _uiState.update { it.copy(error = "Google Sign-In not yet configured") }
    }

    fun signInWithApple() {
        _uiState.update { it.copy(error = "Apple Sign-In not yet configured") }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                apiService.logout()
            } catch (_: Exception) {}
            PushBootstrap.deleteToken()
            clearAuth()
            sessionManager.signalLogout()
            _uiState.update { AuthUiState() }
        }
    }

    private suspend fun saveAuth(token: String, refreshToken: String, userId: String) {
        dataStore.edit { prefs ->
            prefs[PrefsKeys.AUTH_TOKEN] = token
            prefs[PrefsKeys.REFRESH_TOKEN] = refreshToken
            prefs[PrefsKeys.USER_ID] = userId
        }
    }

    private suspend fun clearAuth() {
        dataStore.edit { prefs ->
            prefs.remove(PrefsKeys.AUTH_TOKEN)
            prefs.remove(PrefsKeys.REFRESH_TOKEN)
            prefs.remove(PrefsKeys.USER_ID)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
