package com.koshereats.consumer.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.api.PrefsKeys
import com.koshereats.consumer.data.api.TokenProvider
import com.koshereats.consumer.data.models.EmailStartRequest
import com.koshereats.consumer.data.models.EmailVerifyRequest
import com.koshereats.consumer.data.models.LoginRequest
import com.koshereats.consumer.data.models.PhoneChangeVerifyRequest
import com.koshereats.consumer.data.models.PhoneStartRequest
import com.koshereats.consumer.data.models.PhoneVerifyRequest
import com.koshereats.consumer.data.models.RegisterRequest
import com.koshereats.consumer.data.models.SocialLoginRequest
import com.koshereats.consumer.data.models.User
import com.koshereats.consumer.data.session.SessionManager
import com.koshereats.consumer.auth.GoogleSignInHelper
import com.koshereats.consumer.push.PushBootstrap
import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SessionState {
    object Authenticated : SessionState()
    object Guest : SessionState()
    object LoggedOut : SessionState()
    object Unknown : SessionState()
}

data class AuthUiState(
    val sessionState: SessionState = SessionState.Unknown,
    val user: User? = null,
    val isRehydrating: Boolean = true,
    val isSessionStale: Boolean = false,
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
    val needsPhone: Boolean = false,
    // ── Mandatory account verification (post sign-in) ──
    // Separate fields from the login phone flow above so the two never clash.
    val vEmail: String = "",
    val vEmailCode: String = "",
    val vEmailCodeSent: Boolean = false,
    val vCountryCode: String = "+1",
    val vPhoneNumber: String = "",
    val vPhoneE164: String = "",
    val vPhoneCode: String = "",
    val vPhoneCodeSent: Boolean = false,
    val vBusy: Boolean = false,
    val vError: String? = null,
) {
    val isLoggedIn: Boolean get() = (sessionState == SessionState.Authenticated && user != null && !isSessionStale) || sessionState == SessionState.Guest
    val isGuest: Boolean get() = sessionState == SessionState.Guest

    /**
     * True while the signed-in consumer still has an unverified phone or email.
     * Drives the mandatory verification gate and mirrors the backend, which
     * hard-gates order/payment creation on the same two flags. Existing accounts
     * were grandfathered to verified, so only fresh signups hit this.
     */
    val needsVerification: Boolean
        get() = sessionState == SessionState.Authenticated && user != null &&
            (!user.emailVerified || !user.phoneVerified)
}

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
        scrubLegacyPlaintextTokens()
        checkAuthStatus()
        viewModelScope.launch {
            sessionManager.logoutEvent.collect {
                // clearAuth() is NOT called here — the caller that triggered
                // signalLogout() (logout() / deleteAccount()) already cleared
                // tokens before emitting. Calling it again would race with the
                // first DataStore edit and double-clear preferences.
                _uiState.update { AuthUiState(sessionState = SessionState.LoggedOut) }
            }
        }
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRehydrating = true) }
            val token = tokenProvider.awaitToken()
            if (token != null) {
                try {
                    val response = apiService.getProfile()
                    when {
                        response.isSuccessful -> {
                            _uiState.update {
                                it.copy(sessionState = SessionState.Authenticated, isRehydrating = false, isSessionStale = false, user = response.body())
                            }
                            // Resumed session — refresh the FCM token on the
                            // backend in case it rotated since last launch.
                            PushBootstrap.registerCurrentToken(apiService)
                        }
                        response.code() == 401 -> {
                            clearAuth()
                            _uiState.update { it.copy(sessionState = SessionState.LoggedOut, isRehydrating = false, isSessionStale = false) }
                        }
                        // 5xx or other transient error: keep session alive but mark stale
                        // so the UI can surface a retry banner. user=null until refresh succeeds.
                        else -> _uiState.update { it.copy(sessionState = SessionState.Authenticated, isRehydrating = false, isSessionStale = true) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Network/IO error: transient, keep session alive but mark stale
                    // so the UI can surface a retry banner via retryAuth().
                    _uiState.update { it.copy(sessionState = SessionState.Authenticated, isRehydrating = false, isSessionStale = true) }
                }
            } else {
                _uiState.update { it.copy(sessionState = SessionState.LoggedOut, isRehydrating = false) }
            }
        }
    }

    fun retryAuth() {
        if (!_uiState.value.isRehydrating) checkAuthStatus()
    }

    fun updateLoginEmail(value: String) = _uiState.update { it.copy(loginEmail = value.trim().take(254)) }
    fun updateLoginPassword(value: String) = _uiState.update { it.copy(loginPassword = value.take(128)) }
    fun updateRegisterFirstName(value: String) = _uiState.update { it.copy(registerFirstName = value.take(100)) }
    fun updateRegisterLastName(value: String) = _uiState.update { it.copy(registerLastName = value.take(100)) }
    fun updateRegisterEmail(value: String) = _uiState.update { it.copy(registerEmail = value.trim().take(254)) }
    fun updateRegisterPhone(value: String) = _uiState.update { it.copy(registerPhone = value.filter { it.isDigit() || it == '+' }.take(20)) }
    fun updateRegisterPassword(value: String) = _uiState.update { it.copy(registerPassword = value.take(128)) }
    fun updateRegisterConfirmPassword(value: String) = _uiState.update { it.copy(registerConfirmPassword = value.take(128)) }

    fun login() {
        val state = _uiState.value
        if (state.loginEmail.isBlank() || state.loginPassword.isBlank()) {
            _uiState.update { it.copy(error = "Please fill in all fields") }
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(state.loginEmail.trim()).matches()) {
            _uiState.update { it.copy(error = "Please enter a valid email address") }
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
                            sessionState = SessionState.Authenticated,
                            user = authData.user,
                            isLoading = false,
                            loginEmail = "",
                            loginPassword = "",
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    val raw = response.errorBody()?.string().orEmpty()
                    android.util.Log.w("AuthViewModel", "login failed: ${response.code()}, len=${raw.length}")
                    val msg = when (response.code()) {
                        401 -> "Incorrect email or password"
                        403 -> "Your account has been locked — please contact support"
                        422 -> "Please check your details and try again"
                        429 -> "Too many attempts — please try again later"
                        in 500..599 -> "Server error — please try again later"
                        else -> "Login failed"
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = msg,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(state.registerEmail.trim()).matches()) {
            _uiState.update { it.copy(error = "Please enter a valid email address") }
            return
        }
        if (state.registerPassword.length < 8) {
            _uiState.update { it.copy(error = "Password must be at least 8 characters") }
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
                            sessionState = SessionState.Authenticated,
                            user = authData.user,
                            isLoading = false,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    val raw = response.errorBody()?.string().orEmpty()
                    android.util.Log.w("AuthViewModel", "register failed: ${response.code()}, len=${raw.length}")
                    val msg = when (response.code()) {
                        409 -> "Email already in use — try signing in instead"
                        422 -> "Please check your details and try again"
                        429 -> "Too many attempts — please try again later"
                        in 500..599 -> "Server error — please try again later"
                        else -> "Registration failed"
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = msg,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                            sessionState = SessionState.Authenticated,
                            user = authData.user,
                            isLoading = false,
                            // Phone collection is now handled by the mandatory
                            // verification gate (needsVerification), not the old
                            // best-effort PhonePrompt — leave needsPhone false.
                            needsPhone = false,
                        )
                    }
                    PushBootstrap.registerCurrentToken(apiService)
                } else {
                    val raw = response.errorBody()?.string().orEmpty()
                    android.util.Log.w("AuthViewModel", "socialLogin failed: ${response.code()}, len=${raw.length}")
                    val msg = when (response.code()) {
                        401 -> "Authentication failed — please try again"
                        409 -> "An account with this email already exists"
                        else -> "Sign-in failed — please try again"
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = msg,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(isLoading = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    // ── Phone OTP ─────────────────────────────────────────

    fun updatePhoneCountryCode(value: String) = _uiState.update { it.copy(phoneCountryCode = value) }
    fun updatePhoneNumber(value: String) = _uiState.update { it.copy(phoneNumber = value.filter { c -> c.isDigit() }) }
    fun updateOtpCode(value: String) = _uiState.update { it.copy(otpCode = value.filter { c -> c.isDigit() }.take(4)) }

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
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(phoneIsSending = false, error = e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    fun silentResend() {
        val state = _uiState.value
        if (!state.otpSent || state.phoneE164.isEmpty()) return
        viewModelScope.launch {
            try {
                apiService.phoneStart(PhoneStartRequest(phone = state.phoneE164))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.d("AuthViewModel", "silentResend failed (non-fatal): ${e.message}")
            }
        }
    }

    fun verifyPhoneCode() {
        val state = _uiState.value
        if (state.otpCode.length != 4) {
            _uiState.update { it.copy(error = "Enter the 4-digit code") }
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
                        it.copy(
                            sessionState = SessionState.Authenticated,
                            user = authData.user,
                            phoneIsVerifying = false,
                            error = null,
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
                if (e is CancellationException) throw e
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

    fun submitPhone(phone: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = _uiState.value.user
                val body = mapOf(
                    "first_name" to (user?.firstName.orEmpty()),
                    "last_name" to (user?.lastName.orEmpty()),
                    "phone" to phone,
                )
                val response = apiService.updateProfileFields(body)
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, needsPhone = false, user = response.body()) }
                } else {
                    val serverMsg = try {
                        val body = response.errorBody()?.string().orEmpty()
                        com.google.gson.JsonParser.parseString(body).asJsonObject
                            .get("error")?.asString
                    } catch (_: Exception) { null }
                    val msg = when {
                        response.code() == 409 -> serverMsg ?: "That phone number is already linked to another account"
                        else -> "Failed to save phone number"
                    }
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun skipPhone() {
        _uiState.update { it.copy(needsPhone = false) }
    }

    // ── Mandatory verification flow (post sign-in) ─────────
    //
    // Drives AccountVerificationScreen. Uses the authenticated add-email
    // (/user/email/*) and phone-change (/user/phone/change/*) endpoints; on
    // success it reloads the profile so the flags flip and the gate releases.

    fun updateVEmail(v: String) = _uiState.update { it.copy(vEmail = v.trim().take(254), vError = null) }
    fun updateVEmailCode(v: String) = _uiState.update { it.copy(vEmailCode = v.filter { c -> c.isDigit() }.take(6), vError = null) }
    fun updateVCountryCode(v: String) = _uiState.update { it.copy(vCountryCode = v) }
    fun updateVPhoneNumber(v: String) = _uiState.update { it.copy(vPhoneNumber = v.filter { c -> c.isDigit() }, vError = null) }
    fun updateVPhoneCode(v: String) = _uiState.update { it.copy(vPhoneCode = v.filter { c -> c.isDigit() }.take(4), vError = null) }
    fun backToVEmailEntry() = _uiState.update { it.copy(vEmailCodeSent = false, vEmailCode = "", vError = null) }
    fun backToVPhoneEntry() = _uiState.update { it.copy(vPhoneCodeSent = false, vPhoneCode = "", vError = null) }

    fun sendVEmailCode() {
        val email = _uiState.value.vEmail.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(vError = "Please enter a valid email address") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(vBusy = true, vError = null) }
            try {
                val r = apiService.emailChangeStart(EmailStartRequest(email))
                if (r.isSuccessful) _uiState.update { it.copy(vBusy = false, vEmailCodeSent = true, vEmailCode = "") }
                else _uiState.update { it.copy(vBusy = false, vError = serverError(r) ?: "Couldn't send code") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(vBusy = false, vError = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun confirmVEmail() {
        val s = _uiState.value
        if (s.vEmailCode.length != 6) { _uiState.update { it.copy(vError = "Enter the 6-digit code") }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(vBusy = true, vError = null) }
            try {
                val r = apiService.emailChangeVerify(EmailVerifyRequest(s.vEmail.trim(), s.vEmailCode))
                if (r.isSuccessful) {
                    val profile = runCatching { apiService.getProfile() }.getOrNull()
                    _uiState.update {
                        it.copy(vBusy = false, vEmail = "", vEmailCode = "", vEmailCodeSent = false,
                            user = profile?.body() ?: it.user)
                    }
                } else _uiState.update { it.copy(vBusy = false, vError = serverError(r) ?: "Invalid or expired code") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(vBusy = false, vError = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun sendVPhoneCode() {
        val s = _uiState.value
        if (s.vPhoneNumber.length < 7) { _uiState.update { it.copy(vError = "Enter a valid phone number") }; return }
        val e164 = "${s.vCountryCode}${s.vPhoneNumber}"
        viewModelScope.launch {
            _uiState.update { it.copy(vBusy = true, vError = null) }
            try {
                val r = apiService.phoneChangeStart(PhoneStartRequest(e164))
                if (r.isSuccessful) _uiState.update { it.copy(vBusy = false, vPhoneE164 = e164, vPhoneCodeSent = true, vPhoneCode = "") }
                else _uiState.update { it.copy(vBusy = false, vError = when (r.code()) {
                    400 -> "Invalid phone number format"; 502 -> "SMS service unavailable — try again"; else -> "Couldn't send code"
                }) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(vBusy = false, vError = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun confirmVPhoneCode() {
        val s = _uiState.value
        if (s.vPhoneCode.length != 4) { _uiState.update { it.copy(vError = "Enter the 4-digit code") }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(vBusy = true, vError = null) }
            try {
                val r = apiService.phoneChangeVerify(PhoneChangeVerifyRequest(s.vPhoneE164, s.vPhoneCode))
                if (r.isSuccessful) {
                    val profile = runCatching { apiService.getProfile() }.getOrNull()
                    _uiState.update { it.copy(vBusy = false, vPhoneCode = "", vPhoneCodeSent = false, vPhoneNumber = "", vPhoneE164 = "", user = profile?.body() ?: it.user) }
                } else _uiState.update { it.copy(vBusy = false, vError = when (r.code()) {
                    401 -> "Invalid or expired code"
                    409 -> serverError(r) ?: "That phone is already linked to another account"
                    429 -> "Too many failed attempts — try again in 10 minutes"
                    else -> "Verification failed"
                }) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(vBusy = false, vError = e.localizedMessage ?: "Network error") }
            }
        }
    }

    // ── Email-signup OTP (pre-register) — used by RegisterScreen ──
    // The email must be OTP-verified BEFORE register creates the account, so the
    // user picks a password only after proving the email.

    fun sendSignupEmailCode(email: String, onSent: () -> Unit) {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _uiState.update { it.copy(error = "Please enter a valid email address") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val r = apiService.emailSignupStart(EmailStartRequest(email.trim()))
                if (r.isSuccessful) { _uiState.update { it.copy(isLoading = false) }; onSent() }
                else _uiState.update { it.copy(isLoading = false, error = serverError(r) ?: "Couldn't send code") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun verifySignupEmailCode(email: String, code: String, onVerified: () -> Unit) {
        if (code.length != 6) { _uiState.update { it.copy(error = "Enter the 6-digit code") }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val r = apiService.emailSignupVerify(EmailVerifyRequest(email.trim(), code))
                if (r.isSuccessful) { _uiState.update { it.copy(isLoading = false) }; onVerified() }
                else _uiState.update { it.copy(isLoading = false, error = serverError(r) ?: "Invalid or expired code") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    private fun serverError(r: retrofit2.Response<*>): String? = try {
        com.google.gson.JsonParser.parseString(r.errorBody()?.string().orEmpty())
            .asJsonObject.get("error")?.asString
    } catch (_: Exception) { null }

    fun logout() {
        viewModelScope.launch {
            PushBootstrap.deleteToken()
            clearAuth()
            sessionManager.signalLogout()
            _uiState.update { AuthUiState(sessionState = SessionState.LoggedOut) }
        }
    }

    fun deleteAccount(onComplete: (success: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.deleteAccount()
                if (response.isSuccessful) {
                    PushBootstrap.deleteToken()
                    clearAuth()
                    sessionManager.signalLogout()
                    _uiState.update { AuthUiState(sessionState = SessionState.LoggedOut) }
                    onComplete(true)
                } else {
                    val msg = when (response.code()) {
                        401 -> "Session expired"
                        else -> "Couldn't delete account (${response.code()})"
                    }
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                    onComplete(false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
                onComplete(false)
            }
        }
    }

    private suspend fun saveAuth(token: String, refreshToken: String, userId: String) {
        tokenProvider.persistNewTokens(token, refreshToken)
        dataStore.edit { prefs ->
            prefs[PrefsKeys.USER_ID] = userId
        }
    }

    /**
     * One-time, unconditional cleanup of credentials left over from pre-migration
     * builds that stored the access/refresh tokens in cleartext in the unencrypted
     * "koshereats_prefs" DataStore. Tokens now live only in EncryptedSharedPreferences
     * (see [TokenProvider]); these legacy plaintext keys are never read anymore, but
     * an upgraded device would otherwise keep a valid refresh token in cleartext on
     * disk indefinitely (until an explicit logout/401 ran [clearAuth]). Runs on every
     * launch and is a no-op once the keys are gone. USER_ID is left intact — it is
     * non-sensitive and still in active use.
     */
    private fun scrubLegacyPlaintextTokens() {
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs.remove(PrefsKeys.AUTH_TOKEN)
                    prefs.remove(PrefsKeys.REFRESH_TOKEN)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("AuthViewModel", "legacy token scrub failed (non-fatal): ${e.message}")
            }
        }
    }

    private suspend fun clearAuth() {
        // Synchronously clear in-memory token so in-flight requests after logout
        // cannot use the revoked session.
        tokenProvider.clearTokens()
        dataStore.edit { prefs ->
            prefs.remove(PrefsKeys.AUTH_TOKEN)
            prefs.remove(PrefsKeys.REFRESH_TOKEN)
            prefs.remove(PrefsKeys.USER_ID)
        }
    }

    fun refreshUser() {
        viewModelScope.launch {
            try {
                val response = apiService.getProfile()
                if (response.isSuccessful) {
                    _uiState.update { it.copy(user = response.body()) }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    fun patchUser(firstName: String, lastName: String, phone: String) {
        _uiState.update { state ->
            state.copy(user = state.user?.copy(firstName = firstName, lastName = lastName, phone = phone))
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
                sessionState = SessionState.Guest,
                user = null,
                isLoading = false,
                error = null,
            )
        }
    }
}
