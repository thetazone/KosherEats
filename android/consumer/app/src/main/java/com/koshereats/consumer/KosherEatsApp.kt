package com.koshereats.consumer

import android.app.Application
import com.koshereats.consumer.push.KosherEatsMessagingService
import com.koshereats.consumer.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KosherEatsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Manual Firebase init from BuildConfig (see FIREBASE.md). Gracefully
        // skips when keys are blank so fresh clones still build and run.
        PushBootstrap.init(this)
        KosherEatsMessagingService.ensureChannel(this)
    }
}
