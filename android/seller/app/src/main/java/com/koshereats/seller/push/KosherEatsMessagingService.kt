package com.koshereats.seller.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.koshereats.seller.MainActivity
import com.koshereats.seller.R
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.RegisterDeviceRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM receiver for the seller app. Hilt-injects ApiService so onNewToken
 * can push fresh tokens to the backend without a DI bootstrap dance.
 */
@AndroidEntryPoint
class KosherEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            try {
                apiService.registerDevice(RegisterDeviceRequest(token = token))
            } catch (_: Throwable) {
                // Non-fatal — next app open retries via PushBootstrap.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "KosherEats"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(this, title, body)
    }

    companion object {
        const val CHANNEL_ID = "koshereats_seller_default"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming orders and status updates"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        private fun showNotification(context: Context, title: String, body: String) {
            ensureChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}
