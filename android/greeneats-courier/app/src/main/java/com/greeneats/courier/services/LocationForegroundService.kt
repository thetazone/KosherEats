package com.greeneats.courier.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.greeneats.courier.MainActivity
import com.greeneats.courier.R
import com.greeneats.courier.data.repository.CourierRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps location updates alive when the app is backgrounded on Android 8+.
 * Without a foreground service, the OS throttles FusedLocationProvider for
 * backgrounded apps even with ACCESS_BACKGROUND_LOCATION granted.
 *
 * Started by DashboardViewModel when the courier goes online; stopped when
 * they go offline or the ViewModel is cleared.
 */
@AndroidEntryPoint
class LocationForegroundService : Service() {

    @Inject lateinit var locationTracker: LocationTracker
    @Inject lateinit var repo: CourierRepository

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_DELIVERY_ACTIVE, false) == true) {
            val notification = buildNotification(deliveryActive = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return START_STICKY
        }
        if (!locationTracker.hasPermission()) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PERMISSION_ERROR_NOTIFICATION_ID, buildPermissionErrorNotification())
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val started = locationTracker.start(this) { lat, lng, heading, speed ->
            scope.launch { repo.sendLocation(lat, lng, heading, speed) }
        }
        if (!started) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PERMISSION_ERROR_NOTIFICATION_ID, buildPermissionErrorNotification())
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationTracker.stopOsUpdates()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(deliveryActive: Boolean = false): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = if (deliveryActive)
            "Delivery in progress — tracking your location"
        else
            "You're online — tracking your location"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GreenEats Driver")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun buildPermissionErrorNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Location permission required")
            .setContentText("GreenEats needs background location to track deliveries. Tap to fix.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val PERMISSION_ERROR_NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "greeneats_location"
        const val EXTRA_DELIVERY_ACTIVE = "delivery_active"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active while you are online for deliveries"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
