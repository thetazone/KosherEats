package com.greeneats.seller.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.greeneats.seller.BuildConfig
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.RegisterDeviceRequest
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

    /** Request code callers can use to match the POST_NOTIFICATIONS permission result. */
    const val REQUEST_CODE_POST_NOTIFICATIONS = 9001

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
        try {
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
            initialized = true
            Log.i(TAG, "Firebase initialized for project=${BuildConfig.FIREBASE_PROJECT_ID}")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed: ${e.message}", e)
            // Leave initialized = false so push calls are no-ops.
        }
    }

    /**
     * On Android 13+ (API 33), POST_NOTIFICATIONS is a runtime permission.
     * Call from your main Activity's onCreate or after login. Returns true
     * if the permission is already granted; false if a request was launched
     * (result arrives in onRequestPermissionsResult).
     */
    fun ensureNotificationPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        ActivityCompat.requestPermissions(activity, arrayOf(perm), REQUEST_CODE_POST_NOTIFICATIONS)
        return false
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
                Log.w(TAG, "FCM token registration failed: ${t.message}", t)
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
