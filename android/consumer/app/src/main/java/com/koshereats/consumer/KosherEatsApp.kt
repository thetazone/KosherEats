package com.koshereats.consumer

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        // Clear all notifications when the app comes to foreground so stale
        // pushes don't persist after the user opens the app (mirrors iOS badge clearing).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
            }
        })
    }
}
