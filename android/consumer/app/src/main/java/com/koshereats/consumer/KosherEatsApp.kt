package com.koshereats.consumer

import android.app.Application
import com.koshereats.consumer.data.api.TokenProvider
import com.koshereats.consumer.push.KosherEatsMessagingService
import com.koshereats.consumer.push.PushBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KosherEatsApp : Application() {

    @Inject lateinit var tokenProvider: TokenProvider

    override fun onCreate() {
        super.onCreate()
        // Eagerly kick off the EncryptedSharedPreferences keystore unlock so
        // the token is in-memory before the first network request fires.
        CoroutineScope(Dispatchers.IO).launch { tokenProvider.awaitToken() }
        // Manual Firebase init from BuildConfig (see FIREBASE.md). Gracefully
        // skips when keys are blank so fresh clones still build and run.
        PushBootstrap.init(this)
        KosherEatsMessagingService.ensureChannel(this)
    }
}
