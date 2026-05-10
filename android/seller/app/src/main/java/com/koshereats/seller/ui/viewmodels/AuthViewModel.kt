package com.koshereats.seller.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                return
            }

            val response = apiService.getRestaurant()
            if (response.isSuccessful) {
                _state.value = _state.value.copy(restaurant = response.body())
            }
        } catch (_: java.io.IOException) {
        }
    }

    fun refreshRestaurants() {
        viewModelScope.launch {
            loadRestaurant()
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.login(LoginRequest(email, password))
                if (response.isSuccessful) {
                    val body = response.body()!!
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
                        error = "Invalid credentials. Please try again.",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Connection error: ${e.message}",
                )
            }
        }
    }

    fun socialLogin(provider: String, token: String, firstName: String, lastName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.socialLogin(
                    SocialLoginRequest(provider, token, firstName, lastName)
                )
                if (response.isSuccessful) {
                    val body = response.body()!!
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
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Connection error. Please check your network.",
                )
            }
        }
    }

    fun signInWithGoogle(activityContext: android.content.Context) {
        viewModelScope.launch {
            android.util.Log.d("GoogleSignIn", "signInWithGoogle called")
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = GoogleSignInHelper.signIn(activityContext)
            android.util.Log.d("GoogleSignIn", "signIn returned: isSuccess=${result.isSuccess}")
            result.fold(
                onSuccess = { googleResult ->
                    android.util.Log.d("GoogleSignIn", "success, calling socialLogin")
                    socialLogin("google", googleResult.idToken, googleResult.firstName, googleResult.lastName)
                },
                onFailure = { e ->
                    android.util.Log.e("GoogleSignIn", "failure: ${e.javaClass.name} — ${e.message}")
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Google Sign-In failed",
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
                    _state.value = _state.value.copy(restaurant = response.body())
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        val response = apiService.presignUpload(
            mapOf("kind" to kind, "content_type" to contentType),
        )
        return if (response.isSuccessful) response.body() else null
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
                    _state.value = _state.value.copy(isTogglingOpen = false)
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(isTogglingOpen = false)
            }
        }
    }

    fun updatePhoneCountryCode(value: String) {
        _state.value = _state.value.copy(phoneCountryCode = value)
    }

    fun updatePhoneNumber(value: String) {
        _state.value = _state.value.copy(phoneNumber = value)
    }

    fun updateOtpCode(value: String) {
        _state.value = _state.value.copy(otpCode = value.filter { it.isDigit() }.take(4))
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
        val e164 = "${current.phoneCountryCode}${current.phoneNumber}"
        if (current.phoneNumber.length < 7) {
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

    fun verifyPhoneCode() {
        val current = _state.value
        if (current.otpCode.length != 4) {
            _state.value = current.copy(error = "Enter the 4-digit code")
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
                    val body = response.body()!!
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

    private suspend fun clearAuth() {
        PushBootstrap.deleteToken()
        NetworkModule.cachedToken = null
        NetworkModule.cachedRefreshToken = null
        context.dataStore.edit { it.clear() }
        _state.value = AuthState(isLoggedIn = false, isLoading = false)
    }
}
