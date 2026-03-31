package com.koshereats.seller

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KosherEatsSellerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: KosherEatsSellerApp
            private set
    }
}
