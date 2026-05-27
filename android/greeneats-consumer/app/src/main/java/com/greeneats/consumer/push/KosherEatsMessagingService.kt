package com.greeneats.consumer.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.greeneats.consumer.MainActivity
import com.greeneats.consumer.R
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.RegisterDeviceRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM receiver for the consumer app. Hilt-injects ApiService so onNewToken
 * can push fresh tokens to the backend directly.
 */
@AndroidEntryPoint
class GreenEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            try {
                apiService.registerDevice(RegisterDeviceRequest(token = token))
            } catch (_: Throwable) {
                // Non-fatal — PushBootstrap retries on next app open.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "KosherEats"
        val body = message.notification?.body ?: data["body"] ?: ""
        val type = data["type"]  // e.g. "order_update", "chat", "promo"
        val orderId = data["order_id"]

        // Pick the right channel based on notification type
        val channelId = when (type) {
            "chat" -> CHANNEL_CHAT
            "promo", "deal" -> CHANNEL_PROMO
            else -> CHANNEL_ORDERS
        }

        showNotification(this, title, body, channelId, type, orderId)
    }

    companion object {
        const val CHANNEL_ORDERS = "greeneats_consumer_orders"
        const val CHANNEL_CHAT = "greeneats_consumer_chat"
        const val CHANNEL_PROMO = "greeneats_consumer_promo"

        // Keep the old constant as an alias so any existing references compile.
        const val CHANNEL_ID = CHANNEL_ORDERS

        /**
         * The deep link scheme for in-app routing from notification taps.
         * Must match the intent-filter in AndroidManifest.xml.
         */
        private const val DEEP_LINK_SCHEME = "koshereats"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ORDERS,
                    "Order updates",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Delivery progress and order status"
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CHAT,
                    "Chat messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Messages from your driver or restaurant"
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROMO,
                    "Deals & promotions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Special offers and deals"
                }
            )
        }

        private fun showNotification(
            context: Context,
            title: String,
            body: String,
            channelId: String,
            type: String?,
            orderId: String?,
        ) {
            ensureChannel(context)

            val intent = buildDeepLinkIntent(context, type, orderId)
            // Use a unique request code per notification so each PendingIntent
            // carries its own extras (otherwise Android deduplicates them).
            val requestCode = System.currentTimeMillis().rem(Int.MAX_VALUE).toInt()
            val pi = PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(requestCode, notification)
        }

        /**
         * Build an intent that deep-links into the correct screen when the
         * notification is tapped. Falls back to the main activity if no
         * route can be determined from the push payload.
         */
        private fun buildDeepLinkIntent(
            context: Context,
            type: String?,
            orderId: String?,
        ): Intent {
            // If we have an orderId, route to the relevant order screen.
            val deepLink: Uri? = when {
                type == "chat" && !orderId.isNullOrBlank() ->
                    Uri.parse("$DEEP_LINK_SCHEME://orders/$orderId/chat")
                !orderId.isNullOrBlank() ->
                    Uri.parse("$DEEP_LINK_SCHEME://orders/$orderId/tracking")
                else -> null
            }

            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (deepLink != null) {
                    action = Intent.ACTION_VIEW
                    data = deepLink
                }
            }
        }
    }
}
