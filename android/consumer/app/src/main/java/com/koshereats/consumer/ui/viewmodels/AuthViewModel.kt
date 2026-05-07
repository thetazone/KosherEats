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
import com.koshereats.consumer.data.models.PhoneStartRequest
import com.koshereats.consumer.data.models.PhoneVerifyRequest
import com.koshereats.consumer.data.models.RegisterRequest
import com.koshereats.consumer.data.models.SocialLoginRequest
import com.koshereats.consumer.data.models.User
import com.koshereats.consumer.data.session.SessionManager
import com.koshereats.consumer.auth.GoogleSignInHelper
import com.koshereats.consumer.push.PushBootstrap
import android.content.Context
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
    val isGuest: Boolean = false,
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
    // Phone OTP flow
    val phoneCountryCode: String = "+1",
    val phoneNumber: String = "",
    val phoneE164: String = "",
    val otpSent: Boolean = false,
    val otpCode: String = "",
    val phoneIsSending: Boolean = false,
    val phoneIsVerifying: Boolean = false,
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
                            isGuest = false,
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
                            isGuest = false,
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
                            isGuest = false,
                            user = authData.user,
                            isLoading = false,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "unknown error"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Social login failed: $errorBody",
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

    // ── Phone OTP ─────────────────────────────────────────

    fun updatePhoneCountryCode(value: String) = _uiState.update { it.copy(phoneCountryCode = value) }
    fun updatePhoneNumber(value: String) = _uiState.update { it.copy(phoneNumber = value.filter { c -> c.isDigit() }) }
    fun updateOtpCode(value: String) = _uiState.update { it.copy(otpCode = value.filter { c -> c.isDigit() }.take(6)) }

    fun resetPhoneFlow() {
        _uiState.update {
            it.copy(
                phoneNumber = "",
                phoneE164 = "",
                otpSent = false,
                otpCode = "",
                phoneIsSending = false,
                phoneIsVerifying = false,
                error = null,
            )
        }
    }

    fun backToPhoneEntry() {
        _uiState.update { it.copy(otpSent = false, otpCode = "", error = null) }
    }

    fun startPhoneLogin() {
        val state = _uiState.value
        val e164 = "${state.phoneCountryCode}${state.phoneNumber}"
        if (state.phoneNumber.length < 7) {
            _uiState.update { it.copy(error = "Enter a valid phone number") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(phoneIsSending = true, error = null) }
            try {
                val response = apiService.phoneStart(PhoneStartRequest(phone = e164))
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            phoneE164 = e164,
                            otpSent = true,
                            otpCode = "",
                            phoneIsSending = false,
                        )
                    }
                } else {
                    val msg = when (response.code()) {
                        400 -> "Invalid phone number format"
                        502 -> "SMS service unavailable — try again"
                        else -> "Couldn't send code"
                    }
                    _uiState.update { it.copy(phoneIsSending = false, error = msg) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(phoneIsSending = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun verifyPhoneCode() {
        val state = _uiState.value
        if (state.otpCode.length != 6) {
            _uiState.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(phoneIsVerifying = true, error = null) }
            try {
                val response = apiService.phoneVerify(
                    PhoneVerifyRequest(
                        phone = state.phoneE164,
                        code = state.otpCode,
                        role = "consumer",
                    )
                )
                if (response.isSuccessful) {
                    val authData = response.body() ?: run {
                        _uiState.update { it.copy(phoneIsVerifying = false, error = "Unexpected server response") }
                        return@launch
                    }
                    saveAuth(authData.token, authData.refreshToken, authData.user.id)
                    _uiState.update {
                        AuthUiState(
                            isLoggedIn = true,
                            isGuest = false,
                            user = authData.user,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    val msg = when (response.code()) {
                        401 -> "Invalid or expired code"
                        429 -> "Too many failed attempts — try again in 10 minutes"
                        else -> "Verification failed"
                    }
                    _uiState.update { it.copy(phoneIsVerifying = false, error = msg) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(phoneIsVerifying = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            GoogleSignInHelper.signIn(context)
                .onSuccess { result ->
                    socialLogin("google", result.idToken, result.firstName, result.lastName)
                }
                .onFailure { e ->
                    val msg = e.localizedMessage ?: "Google Sign-In failed"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = if (msg == "cancelled") null else msg,
                        )
                    }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
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

    /**
     * Enter guest browsing mode. The user is treated as "logged in" for
     * navigation purposes but [isGuest] is true, no auth token is saved,
     * and restricted screens (checkout, orders, chat, profile settings)
     * will redirect to the login screen.
     */
    fun continueAsGuest() {
        _uiState.update {
            it.copy(
                isLoggedIn = true,
                isGuest = true,
                user = null,
                isLoading = false,
                error = null,
            )
        }
    }
}
