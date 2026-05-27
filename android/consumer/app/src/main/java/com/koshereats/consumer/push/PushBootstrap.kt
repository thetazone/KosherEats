package com.koshereats.consumer.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.koshereats.consumer.BuildConfig
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.RegisterDeviceRequest
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
    private var appContext: Context? = null

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
            appContext = context.applicationContext
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
        appContext = context.applicationContext
        Log.i(TAG, "Firebase initialized for project=${BuildConfig.FIREBASE_PROJECT_ID}")
    }

    /**
     * Fetch the current FCM registration token and push it to the backend.
     * Call after login / session restore. Silent on failure.
     */
    fun registerCurrentToken(api: ApiService) {
        if (!initialized) return
        scope.launch {
            val prefs = appContext?.getSharedPreferences("koshereats_push_prefs", Context.MODE_PRIVATE)
            val token = try {
                prefs?.getString("pending_fcm_token", null)
                    ?: FirebaseMessaging.getInstance().token.await()
            } catch (t: Throwable) {
                Log.w(TAG, "FCM token fetch failed: ${t.message}")
                return@launch
            }
            val lastRegistered = prefs?.getString("last_registered_fcm_token", null)
            if (token == lastRegistered) {
                Log.d(TAG, "FCM token unchanged — skipping registration")
                return@launch
            }
            // Retry once on transient failure (e.g. token rotates during a backend
            // restart) so we don't leave the user pushless until next app launch.
            var attempt = 0
            while (attempt < 2) {
                try {
                    api.registerDevice(RegisterDeviceRequest(token = token))
                    prefs?.edit()
                        ?.remove("pending_fcm_token")
                        ?.putString("last_registered_fcm_token", token)
                        ?.apply()
                    Log.i(TAG, "FCM token registered (attempt=${attempt + 1})")
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "FCM token registration failed (attempt=${attempt + 1}): ${t.message}")
                    attempt++
                    if (attempt < 2) kotlinx.coroutines.delay(2_000)
                }
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
        // Always clear local prefs even if the Firebase call fails — otherwise the next
        // user on the device sees a "skip" via the unchanged-token guard in registerCurrentToken.
        appContext?.getSharedPreferences("koshereats_push_prefs", Context.MODE_PRIVATE)
            ?.edit()
            ?.remove("last_registered_fcm_token")
            ?.remove("pending_fcm_token")
            ?.apply()
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            Log.i(TAG, "FCM token deleted")
        } catch (t: Throwable) {
            Log.w(TAG, "FCM token deletion failed: ${t.message}")
        }
    }
}
