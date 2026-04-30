package com.koshereats.courier.ui.screens.delivery

import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.koshereats.courier.data.models.CourierOrder
import com.koshereats.courier.data.repository.DirectionsRepository
import com.koshereats.courier.services.LocationTracker
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.TextSecondary
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.DashboardViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * Full-bleed in-app delivery map, Uber Driver-style. Replaces the
 * ActiveDeliveryCard when the courier has a claimed order. Two phases:
 *
 *   ready      → heading to pickup (restaurant marker)
 *   picked_up  → delivering to customer (dropoff marker)
 *
 * The polyline + ETA come from the Google Directions HTTP API; camera
 * auto-fits origin + destination whenever the phase flips or the courier
 * moves a meaningful distance.
 */

// Hilt doesn't inject into @Composable functions directly, and LocationTracker /
// DirectionsRepository are @Singleton. We pull them off the application
// component via an EntryPoint so this screen stays a plain Composable and
// callers don't have to plumb them in from the dashboard.
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DeliveryMapDeps {
    fun locationTracker(): LocationTracker
    fun directionsRepository(): DirectionsRepository
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryMapScreen(
    order: CourierOrder,
    viewModel: DashboardViewModel,
    onOpenChat: (String) -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()
    val context = LocalContext.current
    val deps = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DeliveryMapDeps::class.java,
        )
    }
    val locationTracker = deps.locationTracker()
    val directionsRepo = deps.directionsRepository()
    val scope = rememberCoroutineScope()

    val isPickupPhase = order.status == "ready"
    val destination = remember(order.id, order.status) {
        if (isPickupPhase) LatLng(order.restaurantLat, order.restaurantLng)
        else LatLng(order.deliveryLat, order.deliveryLng)
    }
    val destinationTitle = if (isPickupPhase) order.restaurantName else "Customer"
    val headerLabel = if (isPickupPhase) "Heading to pickup" else "Delivering to customer"
    val actionLabel = if (isPickupPhase) "I've picked it up" else "Mark delivered"

    var courierLatLng by remember { mutableStateOf<LatLng?>(null) }
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    // Pair<fetchOrigin, fetchDestination>; null forces a fetch on first update.
    // Comparing destination lets us detect phase flips and bypass the distance gate.
    var lastDirectionsFetch by remember { mutableStateOf<Pair<LatLng, LatLng>?>(null) }
    var etaText by remember { mutableStateOf<String?>(null) }
    var distanceText by remember { mutableStateOf<String?>(null) }
    var showNavSheet by remember { mutableStateOf(false) }

    val cameraPositionState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(destination, 14f)
    }

    // Seed with the last known fix so the map doesn't sit on a null center
    // while the first update flows in from FusedLocationProvider.
    LaunchedEffect(order.id) {
        locationTracker.lastKnown()?.let { loc ->
            courierLatLng = LatLng(loc.latitude, loc.longitude)
        }
    }

    // LocationTracker is shared with DashboardViewModel (which uses it to ping
    // the backend). start() is additive — both listeners fire; the VM's backend
    // ping callback is not disturbed. On dispose we remove only our listener;
    // stop() is never called here because the VM owns the tracker lifecycle.
    DisposableEffect(order.id) {
        locationTracker.start(order.id) { lat, lng, _, _ ->
            courierLatLng = LatLng(lat, lng)
        }
        onDispose { locationTracker.removeListener(order.id) }
    }

    // Refetch the route when the phase flips or the courier has moved ≥100 m
    // since the last successful fetch. Comparing `last.second == destination`
    // detects phase flips (destination changes) and bypasses the distance gate,
    // so a phase flip always triggers a fresh fetch regardless of position.
    LaunchedEffect(destination, courierLatLng) {
        val origin = courierLatLng ?: return@LaunchedEffect
        val last = lastDirectionsFetch
        if (last != null && last.second == destination) {
            val dist = FloatArray(1)
            Location.distanceBetween(
                last.first.latitude, last.first.longitude,
                origin.latitude, origin.longitude,
                dist,
            )
            if (dist[0] < 100f) return@LaunchedEffect
        }
        directionsRepo.route(origin, destination).onSuccess { r ->
            routePoints = r.polyline
            etaText = r.durationText
            distanceText = r.distanceText
            lastDirectionsFetch = Pair(origin, destination)
            val bounds = LatLngBounds.Builder()
                .include(origin)
                .include(destination)
                .apply { r.polyline.forEach { include(it) } }
                .build()
            runCatching {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngBounds(bounds, 140),
                )
            }
        }.onFailure {
            // Directions is best-effort; if the key is missing or Google is
            // flaky we still want the map + markers. Leave routePoints empty.
            routePoints = emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = courierLatLng != null),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
            ),
        ) {
            Marker(
                state = MarkerState(position = destination),
                title = destinationTitle,
            )
            if (routePoints.isNotEmpty()) {
                Polyline(
                    points = routePoints,
                    color = Orange,
                    width = 12f,
                )
            }
        }

        DeliveryOverlayCard(
            order = order,
            isPickupPhase = isPickupPhase,
            headerLabel = headerLabel,
            actionLabel = actionLabel,
            etaText = etaText,
            distanceText = distanceText,
            isSubmitting = uiState.isSubmitting,
            onPrimaryAction = {
                if (isPickupPhase) viewModel.pickup(order) else viewModel.deliver(order)
            },
            onOpenChat = { onOpenChat(order.id) },
            onOpenNav = { showNavSheet = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        )

        if (showNavSheet) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showNavSheet = false },
                sheetState = sheetState,
                containerColor = SurfaceDark,
            ) {
                NavigationAppPicker(
                    destination = destination,
                    onPicked = { intent ->
                        context.startActivity(intent)
                        scope.launch {
                            sheetState.hide()
                            showNavSheet = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DeliveryOverlayCard(
    order: CourierOrder,
    isPickupPhase: Boolean,
    headerLabel: String,
    actionLabel: String,
    etaText: String?,
    distanceText: String?,
    isSubmitting: Boolean,
    onPrimaryAction: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenNav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                headerLabel.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )

            // Restaurant + dropoff rows. The active leg's icon gets a filled
            // orange tint; the other is muted so the courier always knows
            // which address matters right now.
            AddressRow(
                icon = @Composable {
                    Icon(
                        imageVector = Icons.Filled.Restaurant,
                        contentDescription = null,
                        tint = if (isPickupPhase) Orange else TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                primary = order.restaurantName,
                active = isPickupPhase,
            )
            AddressRow(
                icon = @Composable {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = null,
                        tint = if (!isPickupPhase) Orange else TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                primary = order.deliveryAddress,
                active = !isPickupPhase,
            )

            if (etaText != null && distanceText != null) {
                Text(
                    "$etaText · $distanceText",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onOpenChat,
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceDark, shape = RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChatBubble,
                        contentDescription = "Open chat",
                        tint = Orange,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Button(
                    onClick = onPrimaryAction,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Text(actionLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = onOpenNav,
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceDark, shape = RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = "Open external navigation",
                        tint = Orange,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    icon: @Composable () -> Unit,
    primary: String,
    active: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(
            primary,
            color = if (active) TextWhite else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
        )
    }
}

private const val GOOGLE_MAPS_PKG = "com.google.android.apps.maps"
private const val WAZE_PKG = "com.waze"

@Composable
private fun NavigationAppPicker(
    destination: LatLng,
    onPicked: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager

    // packageManager.getPackageInfo throws NameNotFoundException when the app
    // isn't installed — that's our signal to hide the row. Doing this once at
    // compose time is fine because the sheet is recomposed every open.
    val hasGoogleMaps = remember { isInstalled(pm, GOOGLE_MAPS_PKG) }
    val hasWaze = remember { isInstalled(pm, WAZE_PKG) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Navigate with",
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (!hasGoogleMaps && !hasWaze) {
            Text(
                "No navigation app installed.",
                color = TextTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (hasGoogleMaps) {
            NavAppRow(label = "Google Maps") {
                val uri = Uri.parse(
                    "google.navigation:q=${destination.latitude},${destination.longitude}&mode=d",
                )
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(GOOGLE_MAPS_PKG)
                }
                onPicked(intent)
            }
        }
        if (hasWaze) {
            NavAppRow(label = "Waze") {
                val uri = Uri.parse(
                    "waze://?ll=${destination.latitude},${destination.longitude}&navigate=yes",
                )
                onPicked(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }
}

@Composable
private fun NavAppRow(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Orange,
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Navigation,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
    try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
