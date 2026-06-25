package com.koshereats.consumer.ui.screens.tracking

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import android.content.ActivityNotFoundException
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.R
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
import com.koshereats.consumer.data.models.CourierPublic
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.OrderStatus
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.OrderTrackingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTrackingScreen(
    orderId: String,
    onBack: () -> Unit,
    onChat: (String) -> Unit,
    onRate: (String) -> Unit = {},
    vm: OrderTrackingViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Auto-prompt for a courier rating once the order is delivered with a courier
    // and no prior rating (mirrors iOS OrderTrackingView.maybePromptForRating).
    // rememberSaveable so a config change / re-emit of the same DELIVERED state
    // doesn't re-trigger the navigation after the user has been sent to rate.
    var ratingPrompted by rememberSaveable(orderId) { mutableStateOf(false) }
    LaunchedEffect(state.order?.status, state.order?.courier?.id, state.order?.courierRating) {
        val o = state.order ?: return@LaunchedEffect
        if (!ratingPrompted &&
            o.status == OrderStatus.DELIVERED &&
            o.courier != null &&
            o.courierRating == null
        ) {
            ratingPrompted = true
            onRate(orderId)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(orderId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.start(orderId)
                Lifecycle.Event.ON_STOP -> vm.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.pause()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.order_tracking_title), color = TextWhite, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.errorMessage ?: stringResource(R.string.tracking_load_error),
                            color = TextMuted,
                        )
                        TextButton(onClick = { vm.start(orderId) }) {
                            Text(stringResource(R.string.action_retry), color = Orange)
                        }
                    }
                }
            }
        } else {
            // External delivery (Uber Direct / DoorDash) has no platform courier
            // and no live location stream, so the frozen map / "finding a courier"
            // UI is replaced by a simple provider card.
            if (order.isExternalDelivery) {
                ExternalDeliveryCard(
                    provider = order.externalProvider,
                    trackingUrl = order.externalTrackingUrl,
                )
            } else {
                TrackingMap(order = order, modifier = Modifier.fillMaxWidth().height(340.dp))
            }
            StatusHeader(status = order.status, estimatedDeliveryTime = order.estimatedDeliveryTime)

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
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                order.courier?.let { courier ->
                    CourierCard(
                        courier = courier,
                        onChat = { onChat(orderId) },
                        onDialError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )
                }
                AddressCard(order = order)
            }
        }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    } // end Box
}

@Composable
private fun TrackingMap(order: Order, modifier: Modifier = Modifier) {
    val restaurant = order.restaurantLat?.let { lat ->
        order.restaurantLng?.let { lng -> LatLng(lat, lng) }
    }
    val delivery = if (order.deliveryLat != 0.0 && order.deliveryLng != 0.0)
        LatLng(order.deliveryLat, order.deliveryLng) else null
    val courier = order.courier?.let { c ->
        val lat = c.lat
        val lng = c.lng
        if (lat != null && lng != null) LatLng(lat, lng) else null
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(delivery ?: restaurant ?: LatLng(0.0, 0.0), 14f)
    }
    var didInitialFit by remember { mutableStateOf(false) }

    LaunchedEffect(restaurant, courier, delivery) {
        if (didInitialFit) return@LaunchedEffect
        val points = listOfNotNull(restaurant, delivery, courier)
        when {
            points.size >= 2 -> {
                didInitialFit = true
                val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 140))
            }
            points.size == 1 -> {
                didInitialFit = true
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
            delivery?.let {
                Marker(state = MarkerState(position = it), title = "Delivery")
            }
            courier?.let {
                Marker(state = MarkerState(position = it), title = "Courier")
            }
        }
    }
}

@Composable
private fun StatusHeader(status: OrderStatus, estimatedDeliveryTime: String?) {
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
        // ETA, shown for active non-pending statuses once the backend has
        // populated an estimate (mirrors iOS OrderTrackingView's ETA header).
        if (status.isActive && status != OrderStatus.PENDING && status != OrderStatus.SCHEDULED) {
            formatEta(estimatedDeliveryTime)?.let { eta ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ETA: $eta",
                    color = Orange,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(status = status)
    }
}

/**
 * Formats an RFC-3339 estimated-delivery timestamp as a local `h:mm a` clock
 * string. Returns null when the value is missing or unparseable so the ETA row
 * is simply omitted rather than crashing or showing a raw timestamp.
 */
private fun formatEta(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val local = java.time.OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(local)
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun ProgressBar(status: OrderStatus) {
    val isCancelled = status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(6) { index ->
            val color = when {
                isCancelled -> ErrorRed
                index <= status.stepIndex -> Orange
                else -> SurfaceDark
            }
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

private fun phaseText(status: OrderStatus): String = when (status) {
    OrderStatus.SCHEDULED -> "Your order is scheduled"
    OrderStatus.PENDING -> "Waiting for the restaurant"
    OrderStatus.ACCEPTED -> "Restaurant accepted your order"
    OrderStatus.PREPARING -> "Your food is being prepared"
    OrderStatus.READY -> "Waiting for a courier"
    OrderStatus.PICKED_UP -> "Your order is on the way"
    OrderStatus.DELIVERED -> "Delivered \u2014 enjoy!"
    OrderStatus.COMPLETED -> "Order complete"
    OrderStatus.CANCELLED -> "Order was ${status.displayName.lowercase()}"
    OrderStatus.REJECTED -> "Order was rejected"
    OrderStatus.UNKNOWN -> "Order status unknown"
}

private fun phaseSubtext(status: OrderStatus): String = when (status) {
    OrderStatus.SCHEDULED -> "We'll start preparing closer to your delivery time."
    OrderStatus.PENDING -> "We've sent your order to the restaurant."
    OrderStatus.ACCEPTED -> "They'll start cooking any moment."
    OrderStatus.PREPARING -> "Arriving soon."
    OrderStatus.READY -> "A courier will claim your order shortly."
    OrderStatus.PICKED_UP -> "Your courier is heading to you."
    OrderStatus.REJECTED -> ""
    else -> ""
}

@Composable
private fun CourierCard(
    courier: CourierPublic,
    onChat: () -> Unit,
    onDialError: (String) -> Unit = {},
) {
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
                        text = String.format(java.util.Locale.US, "%.1f", courier.rating),
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
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${courier.phone}"))
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                onDialError("Phone dialer not available on this device")
                            }
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

/**
 * Card shown in place of the live map for orders fulfilled by a third-party
 * delivery provider (Uber Direct / DoorDash Drive). These have no platform
 * courier and no live location stream, so the map / "finding a courier" UI
 * would sit frozen forever; instead we link out to the provider's tracker.
 */
@Composable
private fun ExternalDeliveryCard(provider: String?, trackingUrl: String?) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        text = "Delivered by ${humanizeProvider(provider)}",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Your order is on its way.",
                        color = TextTertiary,
                        fontSize = 13.sp,
                    )
                }
            }
            if (!trackingUrl.isNullOrBlank()) {
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trackingUrl))
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // No browser available; nothing more we can do here.
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Text("Track delivery", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Maps a backend provider slug to a user-facing brand name. */
private fun humanizeProvider(provider: String?): String = when (provider) {
    "uber_direct" -> "Uber"
    "doordash_drive" -> "DoorDash"
    else -> "our delivery partner"
}

@Composable
private fun AddressCard(order: Order) {
    val isPickup = order.fulfillmentType == "pickup"
    val label = if (isPickup) "Pickup from" else "Delivering to"
    val destination = if (isPickup) {
        order.restaurantName.ifEmpty { "—" }
    } else {
        order.deliveryAddress.ifEmpty { "—" }
    }
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
                if (isPickup) Icons.Filled.Restaurant else Icons.Filled.Home,
                contentDescription = null,
                tint = Orange,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = destination,
                    color = TextWhite,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
