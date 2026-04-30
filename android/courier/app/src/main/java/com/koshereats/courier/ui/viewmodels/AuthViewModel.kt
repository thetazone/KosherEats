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
}
