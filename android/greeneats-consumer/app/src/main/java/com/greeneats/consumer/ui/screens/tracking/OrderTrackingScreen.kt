package com.greeneats.consumer.ui.screens.tracking

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.greeneats.consumer.data.models.CourierPublic
import com.greeneats.consumer.data.models.Order
import com.greeneats.consumer.data.models.OrderStatus
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.ui.viewmodels.OrderTrackingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTrackingScreen(
    orderId: String,
    onBack: () -> Unit,
    onChat: (String) -> Unit,
    vm: OrderTrackingViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    LaunchedEffect(orderId) { vm.start(orderId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.order_tracking_title), color = TextWhite, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        val order = state.order
        if (order == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(color = Orange)
                } else {
                    Text(
                        text = state.errorMessage ?: stringResource(R.string.tracking_load_error),
                        color = TextMuted,
                    )
                }
            }
        } else {
            TrackingMap(order = order, modifier = Modifier.fillMaxWidth().height(340.dp))
            StatusHeader(status = order.status)

            state.errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = WarningYellow,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.retryStream() }) {
                        Text(stringResource(R.string.action_retry), color = Orange, fontSize = 12.sp)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                order.courier?.let { courier ->
                    CourierCard(
                        courier = courier,
                        onChat = { onChat(orderId) },
                    )
                }
                AddressCard(address = order.deliveryAddress)
            }
        }
    }
}

@Composable
private fun TrackingMap(order: Order, modifier: Modifier = Modifier) {
    val restaurant = order.restaurantLat?.let { lat ->
        order.restaurantLng?.let { lng -> LatLng(lat, lng) }
    }
    val delivery = LatLng(order.deliveryLat, order.deliveryLng)
    val courier = order.courier?.let { c ->
        val lat = c.lat
        val lng = c.lng
        if (lat != null && lng != null) LatLng(lat, lng) else null
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(delivery, 14f)
    }

    LaunchedEffect(restaurant, courier, delivery) {
        val points = listOfNotNull(restaurant, delivery, courier)
        when {
            points.size >= 2 -> {
                val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 140))
            }
            points.size == 1 -> {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 14f))
            }
        }
    }

    Box(modifier = modifier.background(SurfaceDark)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.NORMAL),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
            ),
        ) {
            restaurant?.let {
                Marker(state = MarkerState(position = it), title = "Restaurant")
            }
            Marker(state = MarkerState(position = delivery), title = "Delivery")
            courier?.let {
                Marker(state = MarkerState(position = it), title = "Courier")
            }
        }
    }
}

@Composable
private fun StatusHeader(status: OrderStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = phaseText(status),
            color = TextWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        val subtext = phaseSubtext(status)
        if (subtext.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtext, color = TextTertiary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(status = status)
    }
}

@Composable
private fun ProgressBar(status: OrderStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index <= status.stepIndex) Orange else SurfaceDark),
            )
        }
    }
}

private fun phaseText(status: OrderStatus): String = when (status) {
    OrderStatus.PENDING -> "Waiting for the restaurant"
    OrderStatus.CONFIRMED -> "Restaurant accepted your order"
    OrderStatus.PREPARING -> "Your food is being prepared"
    OrderStatus.READY -> "Waiting for a courier"
    OrderStatus.PICKED_UP -> "Your order is on the way"
    OrderStatus.DELIVERED -> "Delivered \u2014 enjoy!"
    OrderStatus.COMPLETED -> "Order complete"
    OrderStatus.CANCELLED -> "Order was ${status.displayName.lowercase()}"
}

private fun phaseSubtext(status: OrderStatus): String = when (status) {
    OrderStatus.PENDING -> "We've sent your order to the restaurant."
    OrderStatus.CONFIRMED -> "They'll start cooking any moment."
    OrderStatus.PREPARING -> "Arriving soon."
    OrderStatus.READY -> "A courier will claim your order shortly."
    OrderStatus.PICKED_UP -> "Your courier is heading to you."
    else -> ""
}

@Composable
private fun CourierCard(courier: CourierPublic, onChat: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SurfaceDarkElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = courier.firstName.take(1).uppercase().ifEmpty { "C" },
                    color = Orange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
            }

            Spacer(Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = courier.firstName.ifEmpty { "Your courier" },
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = WarningYellow,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = String.format("%.1f", courier.rating),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                if (courier.vehicleSummary.isNotEmpty()) {
                    Text(
                        text = courier.vehicleSummary,
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onChat,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceDarkElevated),
                ) {
                    Icon(Icons.Filled.ChatBubble, contentDescription = "Message courier", tint = Orange)
                }
                if (!courier.phone.isNullOrEmpty()) {
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${courier.phone}"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SurfaceDarkElevated),
                    ) {
                        Icon(Icons.Filled.Phone, contentDescription = "Call courier", tint = Orange)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressCard(address: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Home,
                contentDescription = null,
                tint = Orange,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Delivering to", color = TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = address.ifEmpty { "—" },
                    color = TextWhite,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
