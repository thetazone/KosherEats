package com.koshereats.seller.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.PrefsKeys
import com.koshereats.seller.data.api.SocialLoginRequest
import com.koshereats.seller.data.api.dataStore
import com.koshereats.seller.data.models.LoginRequest
import com.koshereats.seller.data.models.Restaurant
import com.koshereats.seller.push.PushBootstrap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = true,
    val restaurant: Restaurant? = null,
    val error: String? = null,
    val isTogglingOpen: Boolean = false,
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
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            val token = context.dataStore.data.map { it[PrefsKeys.AUTH_TOKEN] }.first()
            if (token != null) {
                _state.value = AuthState(isLoggedIn = true, isLoading = false)
                // Resumed session — re-register the current FCM token in
                // case it rotated or never uploaded on first login.
                PushBootstrap.registerCurrentToken(apiService)
                loadRestaurant()
            } else {
                _state.value = AuthState(isLoggedIn = false, isLoading = false)
            }
        }
    }

    private suspend fun loadRestaurant() {
        try {
            val response = apiService.getRestaurant()
            if (response.isSuccessful) {
                _state.value = _state.value.copy(restaurant = response.body())
            }
        } catch (_: Exception) {
            // Non-fatal: restaurant info will remain null
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.login(LoginRequest(email, password))
                if (response.isSuccessful) {
                    val body = response.body()!!
                    // Check role is seller
                    if (body.user.role != "seller" && body.user.role != "admin") {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "This account is not a seller account.",
                        )
                        return@launch
                    }
                    context.dataStore.edit { prefs ->
                        prefs[PrefsKeys.AUTH_TOKEN] = body.token
                    }
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
                    }
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

    fun signInWithGoogle() {
        // TODO: Integrate Google Sign-In SDK, obtain ID token, then call:
        // socialLogin("google", idToken, firstName, lastName)
    }

    fun signInWithApple() {
        // TODO: Integrate Apple Sign-In (via Android credentials API), obtain token, then call:
        // socialLogin("apple", identityToken, firstName, lastName)
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

    fun logout() {
        viewModelScope.launch {
            clearAuth()
        }
    }

    private suspend fun clearAuth() {
        PushBootstrap.deleteToken()
        context.dataStore.edit { it.clear() }
        _state.value = AuthState(isLoggedIn = false, isLoading = false)
    }
}
