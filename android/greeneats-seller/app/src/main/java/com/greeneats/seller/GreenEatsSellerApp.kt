package com.greeneats.seller

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.greeneats.seller.push.GreenEatsMessagingService
import com.greeneats.seller.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GreenEatsSellerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize Firebase manually from BuildConfig (see FIREBASE.md).
        // Safe to call even when keys are blank — init skips gracefully.
        PushBootstrap.init(this)
        GreenEatsMessagingService.ensureChannel(this)
        // Clear all notifications when the app comes to foreground so stale
        // pushes don't persist after the user opens the app (mirrors iOS badge clearing).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
            }
        })
    }

    companion object {
        lateinit var instance: GreenEatsSellerApp
            private set
    }
}
