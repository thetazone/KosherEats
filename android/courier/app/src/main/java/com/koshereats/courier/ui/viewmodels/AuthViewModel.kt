package com.koshereats.courier.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.courier.data.models.CourierProfile
import com.koshereats.courier.data.repository.AuthRepository
import com.koshereats.courier.data.repository.CourierRepository
import com.koshereats.courier.data.repository.RoleMismatchException
import com.koshereats.courier.push.PushBootstrap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Top-level auth + profile state. The nav host observes this to decide
 * between auth / onboarding / main experience.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val courierRepository: CourierRepository,
) : ViewModel() {

    data class State(
        val isAuthenticated: Boolean = false,
        val profile: CourierProfile? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        // Phone OTP flow
        val phoneE164: String = "",
        val otpSent: Boolean = false,
        val phoneIsSending: Boolean = false,
        val phoneIsVerifying: Boolean = false,
        // Email-check unified flow
        val emailExists: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val authed = authRepository.isAuthenticated()
            _state.update { it.copy(isAuthenticated = authed) }
            if (authed) {
                loadProfile()
                // Resumed session — push our current FCM token in case it
                // rotated or never made it up the first time.
                PushBootstrap.registerCurrentToken(courierRepository)
            }
        }
    }

    fun signup(email: String, password: String, firstName: String, lastName: String, phone: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.signup(email, password, firstName, lastName, phone)
                .onSuccess {
                    _state.update { s -> s.copy(isAuthenticated = true) }
                    loadProfile()
                    PushBootstrap.registerCurrentToken(courierRepository)
                }
                .onFailure { e ->
                    _state.update { s -> s.copy(errorMessage = e.message ?: "Signup failed") }
                }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.login(email, password)
                .onSuccess {
                    _state.update { s -> s.copy(isAuthenticated = true) }
                    loadProfile()
                    PushBootstrap.registerCurrentToken(courierRepository)
                }
                .onFailure { e ->
                    _state.update { s -> s.copy(errorMessage = e.message ?: "Login failed") }
                }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            courierRepository.profile()
                .onSuccess { p -> _state.update { it.copy(profile = p) } }
                .onFailure { e ->
                    if (e is RoleMismatchException) {
                        PushBootstrap.deleteToken()
                        authRepository.logout()
                        _state.update { State(errorMessage = e.message) }
                    } else {
                        _state.update { it.copy(profile = null) }
                    }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            PushBootstrap.deleteToken()
            authRepository.logout()
            _state.update { State() } // reset everything
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.deleteAccount()
                .onSuccess {
                    PushBootstrap.deleteToken()
                    _state.update { State() }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Couldn't delete account") }
                }
        }
    }

    fun checkEmail(email: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            authRepository.checkEmail(email)
                .onSuccess { resp ->
                    _state.update { it.copy(emailExists = resp.exists) }
                    onResult(resp.exists)
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = e.message ?: "Couldn't check email") }
                    onResult(false)
                }
        }
    }

    fun startPhoneLogin(phoneE164: String) {
        viewModelScope.launch {
            _state.update { it.copy(phoneIsSending = true, errorMessage = null) }
            authRepository.startPhoneLogin(phoneE164)
                .onSuccess {
                    _state.update {
                        it.copy(
                            phoneE164 = phoneE164,
                            otpSent = true,
                            phoneIsSending = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(phoneIsSending = false, errorMessage = e.message ?: "Couldn't send code")
                    }
                }
        }
    }

    fun verifyPhoneLogin(code: String, firstName: String? = null, lastName: String? = null) {
        val phone = _state.value.phoneE164
        if (phone.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(phoneIsVerifying = true, errorMessage = null) }
            authRepository.verifyPhoneLogin(phone, code, firstName, lastName)
                .onSuccess {
                    _state.update { s -> s.copy(isAuthenticated = true, phoneIsVerifying = false, otpSent = false) }
                    loadProfile()
                    PushBootstrap.registerCurrentToken(courierRepository)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(phoneIsVerifying = false, errorMessage = e.message ?: "Verification failed")
                    }
                }
        }
    }

    fun socialLogin(provider: String, token: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.socialLogin(provider, token, firstName, lastName)
                .onSuccess {
                    _state.update { s -> s.copy(isAuthenticated = true, isLoading = false) }
                    loadProfile()
                    PushBootstrap.registerCurrentToken(courierRepository)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isLoading = false, errorMessage = e.message ?: "Social login failed")
                    }
                }
        }
    }

    /** Run the device-side Google Sign-In sheet, then exchange the id_token with our backend. */
    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            com.koshereats.courier.auth.GoogleSignInHelper.signIn(context)
                .onSuccess { result ->
                    socialLogin("google", result.idToken, result.firstName, result.lastName)
                }
                .onFailure { e ->
                    val msg = e.message ?: "Google Sign-In failed"
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (msg == "cancelled") null else msg,
                        )
                    }
                }
        }
    }

    fun resetPhoneFlow() {
        _state.update { it.copy(phoneE164 = "", otpSent = false, errorMessage = null) }
    }
}
