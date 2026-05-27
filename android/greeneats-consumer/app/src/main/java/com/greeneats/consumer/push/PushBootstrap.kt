package com.greeneats.consumer.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.greeneats.consumer.BuildConfig
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.RegisterDeviceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Firebase bootstrap for the consumer app. The google-services Gradle plugin
 * now auto-initializes the default FirebaseApp from app/google-services.json
 * via FirebaseInitProvider, so init() typically just records that the default
 * app exists. The BuildConfig fallback path remains for cases where the
 * google-services config is absent and FCM is opted out.
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
        try {
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

            // Ensure the default notification channel exists early so the system
            // can route notifications even if onMessageReceived never fires
            // (e.g., notification-only payloads while the app is backgrounded).
            GreenEatsMessagingService.ensureChannel(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Firebase initialization failed", t)
            initialized = false
        }
    }

    /**
     * Whether the POST_NOTIFICATIONS runtime permission has been granted.
     * On Android 12 (API 32) and below, notifications are allowed by default.
     * On Android 13+ (API 33, TIRAMISU), the user must grant the permission
     * explicitly at runtime.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-13 devices don't require runtime permission
        }
    }

    /**
     * Fetch the current FCM registration token and push it to the backend.
     * Call after login / session restore. Silent on failure.
     *
     * On Android 13+ this checks the POST_NOTIFICATIONS permission first.
     * Token registration still proceeds even without the permission (the
     * backend can use it for data-only pushes), but a warning is logged so
     * callers know visible notifications won't display.
     */
    fun registerCurrentToken(api: ApiService, context: Context? = null) {
        if (!initialized) return
        if (context != null && !hasNotificationPermission(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted — notifications will be silent")
        }
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
