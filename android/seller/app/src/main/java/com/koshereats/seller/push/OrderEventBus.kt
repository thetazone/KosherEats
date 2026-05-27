package com.koshereats.seller.push

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class OrderEvent(val orderId: String?, val type: String?)

@Singleton
class OrderEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<OrderEvent>(
        replay = 0,
        // 64 covers bursty FCM delivery (e.g. on Friday-night reopen after WiFi outage)
        // without holding events forever; consumers always re-poll on event so dropping
        // an oldest event in a burst still produces the correct final list state.
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<OrderEvent> = _events.asSharedFlow()

    fun notifyOrderChanged(orderId: String? = null, type: String? = null) {
        _events.tryEmit(OrderEvent(orderId, type))
    }
}
