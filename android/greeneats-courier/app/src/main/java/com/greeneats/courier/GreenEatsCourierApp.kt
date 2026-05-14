package com.greeneats.courier

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.greeneats.courier.push.GreenEatsMessagingService
import com.greeneats.courier.push.PushBootstrap
import com.greeneats.courier.services.LocationForegroundService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GreenEatsCourierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase manually from BuildConfig values (see
        // FIREBASE.md). Skipped when keys aren't set — the app still runs.
        PushBootstrap.init(this)
        // Notification channel has to exist before the first push, so
        // create it up front rather than lazily on message receipt.
        GreenEatsMessagingService.ensureChannel(this)
        LocationForegroundService.ensureChannel(this)
        // Clear all notifications when the app comes to foreground so stale
        // pushes don't persist after the user opens the app (mirrors iOS badge clearing).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
            }
        })
    }
}
