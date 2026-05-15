package com.koshereats.seller

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        // Clear notifications only on the first foreground entry per process
        // (cold launch / task restore). Subsequent onStart events (e.g. the user
        // briefly switches apps and returns) must not cancel notifications that
        // arrived while the app was already running and haven't been acted on yet.
        var clearedOnLaunch = false
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!clearedOnLaunch) {
                    clearedOnLaunch = true
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
                }
            }
        })
    }

    companion object {
        lateinit var instance: KosherEatsSellerApp
            private set
    }
}
