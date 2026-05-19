package com.koshereats.consumer.ui.screens.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.NearbyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun NearbyMapScreen(
    onRestaurantClick: (String) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val restaurants = state.restaurants.filter { it.address.latitude != 0.0 && it.address.longitude != 0.0 }
    val selected = restaurants.firstOrNull { it.id == selectedId }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(40.7128, -74.0060), 11f)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    var locationGranted by remember { mutableStateOf(hasLocationPermission()) }
    // Tracks whether we've already moved the camera to the user — prevents the
    // restaurant bounds-fit effect below from overriding their location zoom.
    var centeredOnUser by remember { mutableStateOf(false) }
    var showLocationRationale by remember { mutableStateOf(false) }
    var showLocationPermanentDenial by remember { mutableStateOf(false) }

    fun centerOnUser() {
        if (!hasLocationPermission()) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                cameraPositionState.position = CameraPosition.fromLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    15f, // ~10 cross streets visible — matches iOS reference
                )
                centeredOnUser = true
                viewModel.loadNearby(loc.latitude, loc.longitude)
            } else {
                // lastLocation null on emulators/fresh installs — load with default viewport center
                viewModel.loadNearby(40.7128, -74.0060)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationGranted = granted
        if (granted) {
            centerOnUser()
        } else {
            viewModel.loadNearby(40.7128, -74.0060)
            val needsRationale =
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == true ||
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) == true
            if (needsRationale) showLocationRationale = true else showLocationPermanentDenial = true
        }
    }

    LaunchedEffect(Unit) {
        if (locationGranted) {
            centerOnUser()
        } else {
            val needsRationale =
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == true ||
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) == true
            if (needsRationale) {
                showLocationRationale = true
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    LaunchedEffect(restaurants.size, centeredOnUser) {
        // Skip the bounds-fit when we've already framed the user's location —
        // their neighborhood view is more useful than a city-wide overview.
        if (centeredOnUser) return@LaunchedEffect
        if (restaurants.size >= 2) {
            val bounds = LatLngBounds.builder().apply {
                restaurants.forEach { include(LatLng(it.address.latitude, it.address.longitude)) }
            }.build()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } else if (restaurants.size == 1) {
            val only = restaurants.first()
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(only.address.latitude, only.address.longitude), 14f)
            )
        }
    }

    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text("Location needed for nearby restaurants") },
            text = { Text("KosherEats uses your location to show restaurants near you. Without it, a default area is shown.") },
            confirmButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRationale = false }) { Text("Not now") }
            },
        )
    }

    if (showLocationPermanentDenial) {
        AlertDialog(
            onDismissRequest = { showLocationPermanentDenial = false },
            title = { Text("Location access blocked") },
            text = { Text("To see restaurants near you, enable location access for KosherEats in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showLocationPermanentDenial = false
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationPermanentDenial = false }) { Text("Dismiss") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        TopAppBar(
            title = { Text("Nearby", color = TextWhite, fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    mapType = MapType.NORMAL,
                    isMyLocationEnabled = locationGranted,
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = locationGranted,
                    mapToolbarEnabled = false,
                ),
                onMapClick = { selectedId = null },
            ) {
                restaurants.forEach { restaurant ->
                    MarkerComposable(
                        keys = arrayOf(restaurant.id, restaurant.isOpen, restaurant.rating),
                        state = MarkerState(position = LatLng(restaurant.address.latitude, restaurant.address.longitude)),
                        onClick = {
                            selectedId = restaurant.id
                            true
                        },
                    ) {
                        RestaurantMapPin(restaurant = restaurant)
                    }
                }
            }

            if (selected != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    SelectedRestaurantCard(
                        restaurant = selected,
                        onClick = { onRestaurantClick(selected.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RestaurantMapPin(restaurant: Restaurant) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(if (restaurant.isOpen) SurfaceDarkElevated else SurfaceDark)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = if (restaurant.isOpen) TextWhite else TextMuted,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = String.format(java.util.Locale.US, "%.1f", restaurant.rating),
                color = if (restaurant.isOpen) TextWhite else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = restaurant.name,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SelectedRestaurantCard(restaurant: Restaurant, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = SurfaceDarkElevated,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = restaurant.imageUrl.ifEmpty { null },
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark),
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = restaurant.name,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Orange,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", restaurant.rating),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("·", color = TextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = restaurant.kosherCertification?.displayName ?: "Kosher",
                        color = Orange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Orange.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (restaurant.isGlattKosher) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Glatt",
                            color = TextTertiary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceDark)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${restaurant.deliveryTimeMin}-${restaurant.deliveryTimeMax} min",
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("·", color = TextMuted)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = restaurant.deliveryFee.formatPrice(),
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )
                }
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
