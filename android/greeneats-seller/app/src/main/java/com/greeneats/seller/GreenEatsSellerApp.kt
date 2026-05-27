package com.greeneats.seller

import android.app.Application
import android.app.NotificationManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.greeneats.seller.push.GreenEatsMessagingService
import com.greeneats.seller.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GreenEatsSellerApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install a global crash handler so unhandled exceptions are logged
        // before the process dies. Preserves the default handler so the
        // system still shows the crash dialog / generates a tombstone.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GreenEatsSeller", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

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

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: GreenEatsSellerApp
            private set
    }
}
