package com.koshereats.courier.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LocationTracker wraps FusedLocationProviderClient for the courier's GPS
 * heartbeats. Called from DashboardViewModel when the courier goes online.
 *
 * Requests ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION at the UI layer;
 * this class assumes permission is already granted and silently no-ops if not
 * (we don't want to crash if the user revoked permission mid-shift).
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var handlerThread = HandlerThread("location-tracker").also { it.start() }
    private var handlerThreadAlive = true
    private val listeners = ConcurrentHashMap<Any, (Double, Double, Double, Double) -> Unit>()
    private var locationCallback: LocationCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Location? = suspendCancellableCoroutine { cont ->
        if (!hasPermission()) {
            cont.resume(null); return@suspendCancellableCoroutine
        }
        client.lastLocation
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    @SuppressLint("MissingPermission")
    fun start(key: Any, onLocation: (lat: Double, lng: Double, heading: Double, speed: Double) -> Unit): Boolean {
        if (!hasPermission()) return false

        listeners[key] = onLocation
        if (locationCallback != null) return true

        // Re-create the HandlerThread if it was quit during a previous stopOsUpdates().
        if (!handlerThreadAlive) {
            handlerThread = HandlerThread("location-tracker").also { it.start() }
            handlerThreadAlive = true
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(15f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Mirror iOS accuracy filter: reject invalid, coarse (>100 m), or stale (>30 s) fixes.
                if (!loc.hasAccuracy() || loc.accuracy <= 0f || loc.accuracy > 100f) return
                if (System.currentTimeMillis() - loc.time > 30_000L) return
                val lat = loc.latitude
                val lng = loc.longitude
                val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                listeners.values.toList().forEach { it(lat, lng, heading, speed) }
            }
        }
        locationCallback = cb
        client.requestLocationUpdates(request, cb, handlerThread.looper)
        return true
    }

    fun removeListener(key: Any) {
        listeners.remove(key)
    }

    // Pauses OS location updates without touching listeners. Callers that only want
    // to pause (e.g. going offline mid-delivery) should use this + removeListener()
    // rather than stop(), so secondary screens retain their callbacks.
    fun stopOsUpdates() {
        locationCallback?.let { client.removeLocationUpdates(it) }
        locationCallback = null
        if (handlerThreadAlive) {
            handlerThread.quitSafely()
            handlerThreadAlive = false
        }
    }

    // Full teardown: removes OS updates AND clears all listeners. Only call when
    // the tracker itself is being destroyed (e.g. app sign-out).
    fun stop() {
        stopOsUpdates()
        listeners.clear()
    }
}
