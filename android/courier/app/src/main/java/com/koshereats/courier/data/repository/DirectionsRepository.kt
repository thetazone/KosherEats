package com.koshereats.courier.data.repository

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.koshereats.courier.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Driving route + ETA between two points via the Google Directions HTTP API.
 *
 * Uses its own OkHttp client (no auth interceptor) so we don't leak the user's
 * KosherEats JWT to Google. The Maps API key is a separate secret loaded from
 * local.properties at build time (see BuildConfig.MAPS_API_KEY).
 */
data class DirectionsResult(
    val polyline: List<LatLng>,
    val durationText: String,
    val distanceText: String,
)

@Singleton
class DirectionsRepository @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun route(origin: LatLng, destination: LatLng): Result<DirectionsResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = BuildConfig.MAPS_API_KEY
                if (key.isBlank()) {
                    throw IllegalStateException("MAPS_API_KEY is not configured")
                }
                val url = "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&mode=driving" +
                    "&key=$key"

                val req = Request.Builder().url(url).get().build()
                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Directions HTTP ${resp.code}")
                    resp.body?.string() ?: throw IllegalStateException("Empty Directions body")
                }

                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "OK") {
                    throw IllegalStateException("Directions status=$status")
                }
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) throw IllegalStateException("No routes returned")

                val route0 = routes.getJSONObject(0)
                val encoded = route0.getJSONObject("overview_polyline").getString("points")
                val leg0 = route0.getJSONArray("legs").getJSONObject(0)
                val durationText = leg0.getJSONObject("duration").getString("text")
                val distanceText = leg0.getJSONObject("distance").getString("text")

                DirectionsResult(
                    polyline = PolyUtil.decode(encoded),
                    durationText = durationText,
                    distanceText = distanceText,
                )
            }
        }
}
