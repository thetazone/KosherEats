package com.koshereats.seller.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.BuildConfig
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.api.PrefsKeys
import com.koshereats.seller.data.api.SocialLoginRequest
import com.koshereats.seller.data.api.dataStore
import com.koshereats.seller.data.models.LoginRequest
import com.koshereats.seller.data.models.PhoneStartRequest
import com.koshereats.seller.data.models.PhoneVerifyRequest
import com.koshereats.seller.data.models.PresignResponse
import com.koshereats.seller.data.models.Restaurant
import com.koshereats.seller.push.PushBootstrap
import com.koshereats.seller.auth.GoogleSignInHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = true,
    val restaurant: Restaurant? = null,
    val error: String? = null,
    val updateFieldError: String? = null,
    val isTogglingOpen: Boolean = false,
    /** null = not yet checked, true = seller owns at least one restaurant. */
    val hasRestaurants: Boolean? = null,
    // Phone auth
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        checkAuthStatus()
        viewModelScope.launch {
            NetworkModule.sessionExpired.collect { expired ->
                if (expired) {
                    NetworkModule.sessionExpired.value = false
                    clearAuth()
                }
            }
        }
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            val token = prefs[PrefsKeys.AUTH_TOKEN]
            val refresh = prefs[PrefsKeys.REFRESH_TOKEN]
            // Re-prime restaurant ID here in case provideOkHttpClient's runBlocking
            // read raced with a restaurant switch that completed just before this read.
            NetworkModule.cachedRestaurantId = prefs[PrefsKeys.RESTAURANT_ID]
            if (token != null) {
                NetworkModule.cachedToken = token
                NetworkModule.cachedRefreshToken = refresh
                _state.value = AuthState(isLoggedIn = true, isLoading = false)
                PushBootstrap.registerCurrentToken(apiService)
                loadRestaurant()
            } else {
                _state.value = AuthState(isLoggedIn = false, isLoading = false)
            }
        }
    }

    private suspend fun loadRestaurant() {
        try {
            val listResponse = apiService.listRestaurants()
            if (listResponse.isSuccessful) {
                val restaurants = listResponse.body().orEmpty()
                _state.value = _state.value.copy(hasRestaurants = restaurants.isNotEmpty())
                if (restaurants.isEmpty()) return
            } else if (listResponse.code() == 401) {
                clearAuth()
                return
            } else {
                // 5xx or unexpected error — unblock navigation rather than stalling forever.
                if (_state.value.hasRestaurants == null) {
                    _state.value = _state.value.copy(hasRestaurants = true)
                }
                return
            }

            val response = apiService.getRestaurant()
            if (response.isSuccessful) {
                _state.value = _state.value.copy(restaurant = response.body())
            }
        } catch (_: java.io.IOException) {
            // Network error — unblock navigation if still pending.
            if (_state.value.hasRestaurants == null) {
                _state.value = _state.value.copy(hasRestaurants = true)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Non-IO error — unblock navigation if still pending.
            if (_state.value.hasRestaurants == null) {
                _state.value = _state.value.copy(hasRestaurants = true)
            }
        }
    }

    fun refreshRestaurants() {
        viewModelScope.launch {
            loadRestaurant()
        }
    }

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun login(email: String, password: String) {
        val trimmedEmail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _state.value = _state.value.copy(error = "Please enter a valid email address")
            return
        }
        if (password.isBlank()) {
            _state.value = _state.value.copy(error = "Please enter your password")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.login(LoginRequest(trimmedEmail, password))
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "Login failed: empty server response.",
                        )
                        return@launch
                    }
                    if (body.user.role != "seller" && body.user.role != "admin") {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "This account is not a seller account.",
                        )
                        return@launch
                    }
                    context.dataStore.edit { prefs ->
                        prefs[PrefsKeys.AUTH_TOKEN] = body.token
                        prefs[PrefsKeys.REFRESH_TOKEN] = body.refreshToken
                    }
                    NetworkModule.cachedToken = body.token
                    NetworkModule.cachedRefreshToken = body.refreshToken
                    _state.value = AuthState(
                        isLoggedIn = true,
                        isLoading = false,
                    )
                    PushBootstrap.registerCurrentToken(apiService)
                    loadRestaurant()
                } else {
                    android.util.Log.w("SellerAuth", "login ${response.code()}")
                    val msg = when (response.code()) {
                        401 -> "Incorrect email or password"
                        403 -> "Your account has been locked — please contact support"
                        422 -> "Please check your details and try again"
                        429 -> "Too many attempts — please try again later"
                        in 500..599 -> "Server error — please try again later"
                        else -> "Login failed"
                    }
                    _state.value = _state.value.copy(isLoading = false, error = msg)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = friendlyNetworkError(e),
                )
            }
        }
    }

    private fun friendlyNetworkError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is java.io.IOException -> "Network error — please try again"
        else -> e.localizedMessage ?: "Something went wrong"
    }

    fun socialLogin(provider: String, token: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.socialLogin(
                    SocialLoginRequest(provider, token, firstName, lastName)
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "Login failed: empty server response.",
                        )
                        return@launch
                    }
                    if (body.user.role != "seller" && body.user.role != "admin") {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "This account is not a seller account.",
                        )
                        return@launch
                    }
                    context.dataStore.edit { prefs ->
                        prefs[PrefsKeys.AUTH_TOKEN] = body.token
                        prefs[PrefsKeys.REFRESH_TOKEN] = body.refreshToken
                    }
                    NetworkModule.cachedToken = body.token
                    NetworkModule.cachedRefreshToken = body.refreshToken
                    _state.value = AuthState(
                        isLoggedIn = true,
                        isLoading = false,
                    )
                    PushBootstrap.registerCurrentToken(apiService)
                    loadRestaurant()
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Social login failed. Please try again.",
                    )
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(
                        "SellerAuth",
                        "socialLogin threw: ${e.javaClass.simpleName} — ${e.message}",
                        e,
                    )
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = friendlyNetworkError(e),
                )
            }
        }
    }

    fun signInWithGoogle(activityContext: android.content.Context) {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) android.util.Log.d("GoogleSignIn", "signInWithGoogle called")
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = GoogleSignInHelper.signIn(activityContext)
            if (BuildConfig.DEBUG) android.util.Log.d("GoogleSignIn", "signIn returned: isSuccess=${result.isSuccess}")
            result.fold(
                onSuccess = { googleResult ->
                    if (BuildConfig.DEBUG) android.util.Log.d("GoogleSignIn", "success, calling socialLogin")
                    socialLogin("google", googleResult.idToken, googleResult.firstName, googleResult.lastName)
                },
                onFailure = { e ->
                    if (BuildConfig.DEBUG) android.util.Log.e("GoogleSignIn", "failure: ${e.javaClass.name} — ${e.message}")
                    val raw = e.message.orEmpty()
                    // Suppress the user-initiated cancel — backing out of the picker shouldn't
                    // surface as a red error string.
                    val isCancel = raw.equals("cancelled", ignoreCase = true) ||
                        raw.contains("cancel", ignoreCase = true)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = if (isCancel) null else (raw.ifBlank { "Google Sign-In failed" }),
                    )
                },
            )
        }
    }

    fun updateRestaurantField(key: String, value: Any) {
        viewModelScope.launch {
            try {
                val response = apiService.updateRestaurant(mapOf(key to value))
                if (response.isSuccessful) {
                    val updated = response.body()
                    if (updated != null) {
                        _state.value = _state.value.copy(restaurant = updated)
                    } else {
                        _state.value = _state.value.copy(updateFieldError = "Server returned empty response.")
                    }
                } else {
                    _state.value = _state.value.copy(
                        updateFieldError = "Failed to save changes (HTTP ${response.code()})",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    updateFieldError = "Failed to save changes: ${e.localizedMessage}",
                )
            }
        }
    }

    fun clearUpdateFieldError() {
        _state.value = _state.value.copy(updateFieldError = null)
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        return try {
            val response = apiService.presignUpload(
                mapOf("kind" to kind, "content_type" to contentType),
            )
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    fun toggleOpen(targetIsOpen: Boolean) {
        if (_state.value.isTogglingOpen) return
        _state.value = _state.value.copy(isTogglingOpen = true)
        viewModelScope.launch {
            try {
                val response = apiService.updateRestaurantStatus(mapOf("is_open" to targetIsOpen))
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        restaurant = response.body(),
                        isTogglingOpen = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isTogglingOpen = false,
                        error = "Failed to update restaurant status",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isTogglingOpen = false,
                    error = e.localizedMessage ?: "Network error",
                )
            }
        }
    }

    fun updatePhoneCountryCode(value: String) {
        _state.value = _state.value.copy(phoneCountryCode = value)
    }

    fun updatePhoneNumber(value: String) {
        // E.164 max is 15 digits — cap input length so the value cannot grow without bound.
        _state.value = _state.value.copy(phoneNumber = value.filter { it.isDigit() }.take(15))
    }

    fun updateOtpCode(value: String) {
        _state.value = _state.value.copy(otpCode = value.filter { it.isDigit() }.take(OTP_CODE_LENGTH))
    }

    fun backToPhoneEntry() {
        _state.value = _state.value.copy(otpSent = false, otpCode = "", error = null)
    }

    fun resetPhoneFlow() {
        _state.value = _state.value.copy(
            phoneNumber = "",
            phoneE164 = "",
            otpSent = false,
            otpCode = "",
            phoneIsSending = false,
            phoneIsVerifying = false,
            error = null,
        )
    }

    fun startPhoneLogin() {
        val current = _state.value
        val countryCode = current.phoneCountryCode.trim().let { if (it.startsWith("+")) it else "+$it" }
        val e164 = "$countryCode${current.phoneNumber}"
        if (current.phoneNumber.length < 7 || countryCode.length < 2) {
            _state.value = current.copy(error = "Enter a valid phone number")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(phoneIsSending = true, error = null)
            try {
                val response = apiService.phoneStart(PhoneStartRequest(phone = e164))
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        phoneE164 = e164,
                        otpSent = true,
                        otpCode = "",
                        phoneIsSending = false,
                    )
                } else {
                    val msg = when (response.code()) {
                        400 -> "Invalid phone number format"
                        502 -> "SMS service unavailable — try again"
                        else -> "Couldn't send code"
                    }
                    _state.value = _state.value.copy(phoneIsSending = false, error = msg)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phoneIsSending = false,
                    error = e.localizedMessage ?: "Network error",
                )
            }
        }
    }

    fun silentResend() {
        val current = _state.value
        if (!current.otpSent || current.phoneE164.isEmpty()) return
        viewModelScope.launch {
            try {
                apiService.phoneStart(PhoneStartRequest(phone = current.phoneE164))
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    fun verifyPhoneCode() {
        val current = _state.value
        if (current.otpCode.length != OTP_CODE_LENGTH) {
            _state.value = current.copy(error = "Enter the $OTP_CODE_LENGTH-digit code")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(phoneIsVerifying = true, error = null)
            try {
                val response = apiService.phoneVerify(
                    PhoneVerifyRequest(
                        phone = current.phoneE164,
                        code = current.otpCode,
                        role = "seller",
                    )
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        _state.value = _state.value.copy(
                            phoneIsVerifying = false,
                            error = "Verification failed: empty server response.",
                        )
                        return@launch
                    }
                    if (body.user.role != "seller" && body.user.role != "admin") {
                        _state.value = _state.value.copy(
                            phoneIsVerifying = false,
                            error = "This account is not a seller account.",
                        )
                        return@launch
                    }
                    context.dataStore.edit { prefs ->
                        prefs[PrefsKeys.AUTH_TOKEN] = body.token
                        prefs[PrefsKeys.REFRESH_TOKEN] = body.refreshToken
                    }
                    NetworkModule.cachedToken = body.token
                    NetworkModule.cachedRefreshToken = body.refreshToken
                    _state.value = AuthState(isLoggedIn = true, isLoading = false)
                    PushBootstrap.registerCurrentToken(apiService)
                    loadRestaurant()
                } else {
                    val msg = when (response.code()) {
                        401 -> "Invalid or expired code"
                        429 -> "Too many failed attempts — try again in 10 minutes"
                        else -> "Verification failed"
                    }
                    _state.value = _state.value.copy(phoneIsVerifying = false, error = msg)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phoneIsVerifying = false,
                    error = e.localizedMessage ?: "Network error",
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            clearAuth()
        }
    }

    companion object {
        const val OTP_CODE_LENGTH = 6
    }

    private suspend fun clearAuth() {
        PushBootstrap.deleteToken(apiService)
        NetworkModule.cachedToken = null
        NetworkModule.cachedRefreshToken = null
        NetworkModule.cachedRestaurantId = null
        context.dataStore.edit { it.clear() }
        _state.value = AuthState(isLoggedIn = false, isLoading = false)
    }
}
