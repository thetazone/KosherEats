package com.koshereats.seller

import android.app.Application
import com.koshereats.seller.push.KosherEatsMessagingService
import com.koshereats.seller.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KosherEatsSellerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize Firebase manually from BuildConfig (see FIREBASE.md).
        // Safe to call even when keys are blank — init skips gracefully.
        PushBootstrap.init(this)
        KosherEatsMessagingService.ensureChannel(this)
    }

    companion object {
        lateinit var instance: KosherEatsSellerApp
            private set
    }
}
