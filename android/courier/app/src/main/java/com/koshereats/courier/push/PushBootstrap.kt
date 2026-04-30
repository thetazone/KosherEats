package com.koshereats.courier.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.koshereats.courier.BuildConfig
import com.koshereats.courier.data.repository.CourierRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manual Firebase bootstrap so builds work without a google-services.json
 * file sitting in the project. Values come from BuildConfig, which reads
 * local.properties — see FIREBASE.md for the four keys the courier app needs.
 *
 * If any value is blank we log a warning and skip init; the app still runs,
 * FCM just stays off. Once Salto drops real keys into local.properties the
 * next build will enable push end-to-end.
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

        // Guard against double-init when the Application is recreated.
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
     * Call after login. Fetches the current FCM registration token and
     * pushes it to the backend so notifier.go can reach this device.
     * Silent on failure — a missing token is never worth blocking the UI for.
     */
    fun registerCurrentToken(repository: CourierRepository) {
        if (!initialized) return

        scope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                repository.registerDevice(token)
                Log.i(TAG, "FCM token registered")
            } catch (t: Throwable) {
                Log.w(TAG, "FCM token registration failed: ${t.message}")
            }
        }
    }

    /**
     * Delete the FCM registration token on logout so a new user on this
     * device won't receive the previous user's push notifications. Firebase
     * will issue a fresh token on the next getToken() call (i.e. after login).
     */
    suspend fun deleteToken() {
        if (!initialized) return
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            Log.i(TAG, "FCM token deleted")
        } catch (t: Throwable) {
            Log.w(TAG, "FCM token deletion failed: ${t.message}")
        }
    }
}
