package com.koshereats.consumer.data.session

import com.koshereats.consumer.data.api.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _logoutEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logoutEvent: SharedFlow<Unit> = _logoutEvent.asSharedFlow()

    fun signalLogout() {
        // Push token deletion is handled by the caller (AuthViewModel.logout /
        // deleteAccount) — doing it here as well creates a double-delete race
        // where two coroutines concurrently hit FirebaseMessaging.deleteToken().
        appScope.launch { _logoutEvent.emit(Unit) }
    }
}
