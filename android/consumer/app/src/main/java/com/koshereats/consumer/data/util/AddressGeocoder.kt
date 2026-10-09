package com.koshereats.consumer.data.util

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.koshereats.consumer.data.models.Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The one geocoder behind every "add address" path (home picker, checkout
 * picker, saved addresses) and the view-model backstop behind them. The backend
 * stores whatever lat/lng the app sends and refuses a delivery order for an
 * address at (0, 0), so an address must be located on-device BEFORE it is
 * saved — never after, and never silently.
 */
object AddressGeocoder {
    const val FAILURE_MESSAGE =
        "We couldn't verify this address. Please check the street, city, state and ZIP and try again."

    private const val TIMEOUT_MS = 10_000L

    /** [address] with lat/lng filled in, or null when it could not be located. */
    suspend fun geocode(context: Context, address: Address): Address? {
        if (address.hasCoordinates) return address
        // The apartment line is deliberately left out: "123 Main St, Apt 4B" is
        // what makes platform geocoders return nothing.
        val query = listOf(address.streetAddress, address.city, address.state, address.zipCode)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
        if (query.isBlank() || !Geocoder.isPresent()) return null
        val coords = withTimeoutOrNull(TIMEOUT_MS) { lookup(context.applicationContext, query) }
            ?: return null
        if (coords.first == 0.0 && coords.second == 0.0) return null
        return address.copy(latitude = coords.first, longitude = coords.second)
    }

    private suspend fun lookup(context: Context, query: String): Pair<Double, Double>? {
        val geocoder = Geocoder(context, Locale.US)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(
                    query,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            val first = addresses.firstOrNull()
                            if (cont.isActive) cont.resume(first?.let { it.latitude to it.longitude })
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 1)
                        ?.firstOrNull()
                        ?.let { it.latitude to it.longitude }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
