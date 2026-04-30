package com.koshereats.seller.push

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class OrderEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifyOrderChanged() {
        _events.tryEmit(Unit)
    }
}
