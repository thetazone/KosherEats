package com.greeneats.consumer.data.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor() {
    private val _logoutEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logoutEvent: SharedFlow<Unit> = _logoutEvent.asSharedFlow()

    /** Observable flag that downstream collectors can check before doing
     *  authenticated work. Set to `true` once [signalLogout] fires so
     *  in-flight network calls can bail out early instead of racing the
     *  navigation reset. Reset to `false` on next sign-in via [markActive]. */
    private val _isLoggedOut = MutableStateFlow(false)
    val isLoggedOut: StateFlow<Boolean> = _isLoggedOut.asStateFlow()

    /**
     * Emit the logout event and flip [isLoggedOut] so downstream subscribers
     * (repositories, ViewModels) can cancel in-flight work immediately.
     */
    fun signalLogout() {
        _isLoggedOut.value = true
        _logoutEvent.tryEmit(Unit)
    }

    /** Call after a successful sign-in to clear the logged-out flag. */
    fun markActive() {
        _isLoggedOut.value = false
    }
}
