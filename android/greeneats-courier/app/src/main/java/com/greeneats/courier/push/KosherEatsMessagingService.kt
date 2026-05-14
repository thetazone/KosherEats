package com.greeneats.courier.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.greeneats.courier.MainActivity
import com.greeneats.courier.R
import com.greeneats.courier.data.repository.CourierRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM messages for the courier app. Two responsibilities:
 *
 *   1. onNewToken — fires when Firebase mints or rotates a token. We push
 *      the fresh token to the backend so notifier.go can reach this device.
 *
 *   2. onMessageReceived — fires when a push arrives while the app is in the
 *      foreground. Background pushes are displayed by the system
 *      automatically from the `notification` payload; we only have to build
 *      the UI when we're in the foreground.
 *
 * Hilt-injects the repository so we don't have to bootstrap DI manually
 * inside a Service.
 */
@AndroidEntryPoint
class GreenEatsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var repository: CourierRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            try {
                repository.registerDevice(token)
            } catch (_: Throwable) {
                // Non-fatal — next app open will retry via PushBootstrap.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "GreenEats"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(this, title, body)
    }

    companion object {
        const val CHANNEL_ID = "greeneats_courier_default"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deliveries",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New delivery opportunities and order updates"
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
