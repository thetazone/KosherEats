package com.koshereats.consumer.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.koshereats.consumer.MainActivity
import com.koshereats.consumer.R
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.api.TokenProvider
import com.koshereats.consumer.data.models.RegisterDeviceRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * FCM receiver for the consumer app. Hilt-injects ApiService so onNewToken
 * can push fresh tokens to the backend directly.
 */
@AndroidEntryPoint
class KosherEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var tokenProvider: TokenProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            tokenProvider.awaitToken() ?: return@launch
            try {
                apiService.registerDevice(RegisterDeviceRequest(token = token))
            } catch (t: Throwable) {
                if (t !is HttpException || t.code() != 401) {
                    android.util.Log.w("KosherEatsMessagingService", "registerDevice failed: ${t.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "KosherEats"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(this, title, body, message.data["order_id"])
    }

    companion object {
        const val CHANNEL_ID = "koshereats_consumer_default"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Order updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Delivery progress and order status"
            }
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        private fun showNotification(context: Context, title: String, body: String, orderId: String? = null) {
            ensureChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                orderId?.let {
                    putExtra("order_id", it)
                }
            }
            val pi = PendingIntent.getActivity(
                context, orderId?.hashCode() ?: System.currentTimeMillis().toInt(), intent,
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

            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (orderId != null) {
                nm.notify(orderId, 0, notification)
            } else {
                nm.notify(System.currentTimeMillis().toInt(), notification)
            }
        }
    }
}
