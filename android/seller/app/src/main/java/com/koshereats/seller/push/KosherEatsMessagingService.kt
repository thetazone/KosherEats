package com.koshereats.seller.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FCM receiver for the seller app. Hilt-injects ApiService so onNewToken
 * can push fresh tokens to the backend without a DI bootstrap dance.
 */
@AndroidEntryPoint
class KosherEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var orderEventBus: OrderEventBus

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
        val type = message.data["type"]
        showNotification(this, title, body, message.data["order_id"], type)
        if (type == "new_order" || type == "courier_assigned" ||
            type == "order_status_changed" || type == "order_cancelled" || type == "payment_update") {
            orderEventBus.notifyOrderChanged(orderId = message.data["order_id"], type = type)
        }
    }

    companion object {
        const val CHANNEL_ID = "koshereats_seller_default"
        const val NEW_ORDER_CHANNEL_ID = "koshereats_seller_new_orders"
        private const val GROUP_KEY = "com.koshereats.seller.ORDERS"
        private const val SUMMARY_ID = 0
        private val NEW_ORDER_VIBRATION = longArrayOf(0, 500, 300, 500, 300, 500)
        private val notifIdMap = ConcurrentHashMap<String, Int>()
        private val notifIdCounter = AtomicInteger(1)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Orders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming orders and status updates"
                },
            )

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()
            nm.createNotificationChannel(
                NotificationChannel(
                    NEW_ORDER_CHANNEL_ID,
                    "New Orders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "New incoming orders — requires immediate attention"
                    setSound(ringtoneUri, audioAttributes)
                    vibrationPattern = NEW_ORDER_VIBRATION
                    enableVibration(true)
                },
            )
        }

        private fun showNotification(
            context: Context,
            title: String,
            body: String,
            orderId: String?,
            type: String?,
        ) {
            ensureChannel(context)

            val isNewOrder = type == "new_order"
            val channelId = if (isNewOrder) NEW_ORDER_CHANNEL_ID else CHANNEL_ID
            // Stable ID per order key — re-pushes for the same order overwrite; distinct orders don't collide.
            val key = orderId ?: type ?: CHANNEL_ID
            val notifId = notifIdMap.getOrPut(key) { notifIdCounter.getAndIncrement() }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (orderId != null) putExtra("order_id", orderId)
            }
            val pi = PendingIntent.getActivity(
                context, notifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP_KEY)

            // On pre-O devices the channel does not exist; set sound/vibration on the builder.
            if (isNewOrder && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder
                    .setVibrate(NEW_ORDER_VIBRATION)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            }

            val summary = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, builder.build())
            nm.notify(SUMMARY_ID, summary)
        }
    }
}
