package com.koshereats.courier.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
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

    private var callback: LocationCallback? = null

    private fun hasPermission(): Boolean =
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
    fun start(onLocation: (lat: Double, lng: Double, heading: Double, speed: Double) -> Unit) {
        if (!hasPermission()) return

        stop()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(15f)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(
                    loc.latitude,
                    loc.longitude,
                    if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                    if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                )
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, context.mainLooper)
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
