package com.koshereats.consumer

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.koshereats.consumer.data.api.PrefsKeys
import com.koshereats.consumer.data.api.TokenProvider
import com.koshereats.consumer.data.api.dataStore
import com.koshereats.consumer.push.KosherEatsMessagingService
import com.koshereats.consumer.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KosherEatsApp : Application() {

    @Inject lateinit var tokenProvider: TokenProvider

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Eagerly kick off the EncryptedSharedPreferences keystore unlock so
        // the token is in-memory before the first network request fires.
        appScope.launch { tokenProvider.awaitToken() }
        // Earliest-possible scrub of legacy cleartext tokens left on disk by
        // pre-migration builds (which stored access/refresh tokens unencrypted
        // in the "koshereats_prefs" DataStore). Tokens now live only in
        // EncryptedSharedPreferences; this runs in Application.onCreate so the
        // wipe happens before any UI — and thus before the (lazily constructed)
        // AuthViewModel — can touch the session. AuthViewModel.init keeps its own
        // redundant scrub as a belt-and-suspenders no-op. USER_ID is non-sensitive
        // and intentionally left intact.
        appScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs.remove(PrefsKeys.AUTH_TOKEN)
                    prefs.remove(PrefsKeys.REFRESH_TOKEN)
                }
            } catch (e: Exception) {
                android.util.Log.w("KosherEatsApp", "legacy token scrub failed (non-fatal): ${e.message}")
            }
        }
        // Manual Firebase init from BuildConfig (see FIREBASE.md). Gracefully
        // skips when keys are blank so fresh clones still build and run.
        PushBootstrap.init(this)
        KosherEatsMessagingService.ensureChannel(this)
    }
}
