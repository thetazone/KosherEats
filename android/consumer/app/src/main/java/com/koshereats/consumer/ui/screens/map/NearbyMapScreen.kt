package com.koshereats.consumer.ui.screens.map

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
import com.koshereats.consumer.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyMapScreen(
    onRestaurantClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }

    val restaurants = state.allRestaurants.filter { it.address.latitude != 0.0 && it.address.longitude != 0.0 }
    val selected = restaurants.firstOrNull { it.id == selectedId }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(40.7128, -74.0060), 11f)
    }

    LaunchedEffect(restaurants.size) {
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

    Column(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        TopAppBar(
            title = { Text("Nearby", color = TextWhite, fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(mapType = MapType.NORMAL),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
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
                text = String.format("%.1f", restaurant.rating),
                color = if (restaurant.isOpen) TextWhite else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = restaurant.name,
            color = TextWhite,
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
                        text = String.format("%.1f", restaurant.rating),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("·", color = TextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = restaurant.kosherCertification.displayName,
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
