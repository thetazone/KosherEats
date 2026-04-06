package com.koshereats.seller.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.koshereats.seller.BuildConfig
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.RegisterDeviceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manual Firebase bootstrap for the seller app. Reads four config values
 * from BuildConfig (sourced from local.properties — see FIREBASE.md). Skips
 * init with a warning when any value is blank so the app still builds and
 * runs on a machine that hasn't set up Firebase yet.
 */
object PushBootstrap {
    private const val TAG = "PushBootstrap"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized: Boolean = false

    fun init(context: Context) {
        if (BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_APP_ID.isBlank()
        ) {
            Log.w(TAG, "Firebase keys missing in local.properties — skipping FCM init (push disabled).")
            return
        }
        if (FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            initialized = true
            return
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
        initialized = true
        Log.i(TAG, "Firebase initialized for project=${BuildConfig.FIREBASE_PROJECT_ID}")
    }

    /**
     * Fetch the current FCM registration token and push it to the backend.
     * Call after login / session restore. Silent on failure — push never
     * blocks the UI.
     */
    fun registerCurrentToken(api: ApiService) {
        if (!initialized) return
        scope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                api.registerDevice(RegisterDeviceRequest(token = token))
                Log.i(TAG, "FCM token registered")
            } catch (t: Throwable) {
                Log.w(TAG, "FCM token registration failed: ${t.message}")
            }
        }
    }
}
