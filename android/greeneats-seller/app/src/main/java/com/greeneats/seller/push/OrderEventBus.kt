package com.greeneats.seller.push

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Simple event bus that lets the push-notification layer tell the UI
 * "something changed -- reload orders". Uses [BufferOverflow.DROP_OLDEST]
 * so a burst of rapid pushes won't OOM, but the UI always sees the most
 * recent signal. 64 slots is generous enough that normal traffic never
 * drops, while still bounding memory during pathological bursts.
 */
@Singleton
class OrderEventBus @Inject constructor() {

    companion object {
        private const val TAG = "OrderEventBus"
    }

    private val _events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifyOrderChanged() {
        val emitted = _events.tryEmit(Unit)
        if (!emitted) {
            Log.w(TAG, "OrderEventBus buffer full -- oldest event dropped")
        }
    }
}
