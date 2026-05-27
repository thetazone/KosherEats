package com.greeneats.consumer

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
import com.greeneats.consumer.push.GreenEatsMessagingService
import com.greeneats.consumer.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GreenEatsApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // Install a global crash handler so uncaught exceptions are logged
        // before the default handler terminates the process.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GreenEatsApp", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Manual Firebase init from BuildConfig (see FIREBASE.md). Gracefully
        // skips when keys are blank so fresh clones still build and run.
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

    /**
     * Coil [ImageLoaderFactory] -- configures a shared image loader with
     * memory and disk caches so restaurant/menu images load faster and
     * survive process recreation.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.20) // 20% of available app memory
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(50L * 1024 * 1024) // 50 MB
                .build()
        }
        .crossfade(true)
        .build()
}
