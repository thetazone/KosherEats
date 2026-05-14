package com.koshereats.consumer.data.session

import com.koshereats.consumer.data.api.ApplicationScope
import com.koshereats.consumer.push.PushBootstrap
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
        appScope.launch { PushBootstrap.deleteToken() }
        _logoutEvent.tryEmit(Unit)
    }
}
