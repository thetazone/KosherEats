package com.greeneats.seller.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.greeneats.seller.MainActivity
import com.greeneats.seller.R
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.RegisterDeviceRequest
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
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
class GreenEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var orderEventBus: OrderEventBus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Persist locally FIRST so the token survives API-call failures and can
        // be retried on next app launch via PushBootstrap.
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()

        scope.launch {
            try {
                apiService.registerDevice(RegisterDeviceRequest(token = token))
            } catch (_: Throwable) {
                // Non-fatal — next app open retries via PushBootstrap.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "GreenEats"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val orderId = message.data["order_id"]
        showNotification(this, title, body, orderId)
        val type = message.data["type"]
        if (type == "new_order" || type == "courier_assigned" ||
            type == "order_status_changed" || type == "order_cancelled" || type == "payment_update") {
            orderEventBus.notifyOrderChanged()
        }
    }

    companion object {
        const val CHANNEL_ID = "greeneats_seller_default"
        private val notifId = AtomicInteger(0)

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

        private fun showNotification(context: Context, title: String, body: String, orderId: String? = null) {
            ensureChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (!orderId.isNullOrBlank()) putExtra("order_id", orderId)
            }
            val pi = PendingIntent.getActivity(
                context, notifId.incrementAndGet(), intent,
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
            nm.notify(notifId.incrementAndGet(), notification)
        }
    }
}
