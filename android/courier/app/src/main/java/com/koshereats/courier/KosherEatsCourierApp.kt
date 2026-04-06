package com.koshereats.courier

import android.app.Application
import com.koshereats.courier.push.KosherEatsMessagingService
import com.koshereats.courier.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KosherEatsCourierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase manually from BuildConfig values (see
        // FIREBASE.md). Skipped when keys aren't set — the app still runs.
        PushBootstrap.init(this)
        // Notification channel has to exist before the first push, so
        // create it up front rather than lazily on message receipt.
        KosherEatsMessagingService.ensureChannel(this)
    }
}
