package com.koshereats.seller.ui.screens.orders

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.screens.dashboard.OrderStatusBadge
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusAccepted
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.StatusReady
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.OrdersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerOrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val order = state.selectedOrder
    val context = LocalContext.current
    var showRejectConfirm by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(orderId) {
        viewModel.clearMessages()
        viewModel.loadOrderDetail(orderId)
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            // Errors (e.g. reject failure, Stripe refund 502) need to stay visible
            // long enough for the seller to actually read them.
            Toast.makeText(context, state.error, Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.updateSuccess) {
        if (state.updateSuccess != null) {
            Toast.makeText(context, state.updateSuccess, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose {
            viewModel.stopPolling()
            viewModel.clearSelectedOrder()
        }
    }

    if (showRejectConfirm) {
        AlertDialog(
            onDismissRequest = { showRejectConfirm = false },
            title = { Text("Reject Order?", color = TextWhite) },
            text = { Text("This will cancel the customer's order. This cannot be undone.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showRejectConfirm = false
                    viewModel.rejectPending(orderId)
                }) {
                    Text("Reject Order", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRejectConfirm = false }) {
                    Text("Cancel", color = TextWhite)
                }
            },
            containerColor = SurfaceDark,
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel Order?", color = TextWhite) },
            text = { Text("The customer will be notified that their order has been cancelled.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    viewModel.cancelInProgress(orderId)
                }) {
                    Text("Cancel Order", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep Order", color = TextWhite)
                }
            },
            containerColor = SurfaceDark,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = if (order != null) "Order #${order.id.take(8)}" else "Order Detail",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        val minutesAgo by produceState<Long?>(initialValue = null, key1 = order?.createdAt) {
            val createdAt = order?.createdAt ?: return@produceState
            while (true) {
                value = runCatching {
                    val instant = runCatching { Instant.parse(createdAt) }
                        .recoverCatching { java.time.OffsetDateTime.parse(createdAt).toInstant() }
                        .getOrThrow()
                    Duration.between(instant, Instant.now()).toMinutes()
                }.getOrNull()
                delay(60_000L)
            }
        }

        if (state.isLoadingDetail) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Orange)
            }
            return
        }

        if (order == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Failed to load order details. Please go back and try again.",
                    color = ErrorRed,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status + order info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                            OrderStatusBadge(status = order.status)
                        }

                        val mins = minutesAgo
                        if (mins != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (mins < 1) "Placed just now" else "Placed $mins min ago",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }

                        if (order.deliveryAddress.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Delivery Address",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.LocalShipping,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = order.deliveryAddress,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // Customer contact card
            if (order.customerName.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Customer",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = order.customerName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextWhite,
                                )
                                if (order.customerPhone.isNotBlank()) {
                                    IconButton(onClick = {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone}"))
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: android.content.ActivityNotFoundException) {
                                            Toast.makeText(context, "Phone dialer not available", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.Phone,
                                            contentDescription = "Call customer",
                                            tint = Orange,
                                        )
                                    }
                                }
                            }
                            if (order.customerPhone.isNotBlank()) {
                                Text(
                                    text = order.customerPhone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                }
            }

            // Order items
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Order Items",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        order.items.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.quantity}x",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Orange,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = item.menuItemName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextWhite,
                                        )
                                        if (item.specialInstructions.isNotBlank()) {
                                            Text(
                                                text = item.specialInstructions,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = item.totalPrice.formatPrice(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextWhite,
                                )
                            }
                            if (index < order.items.lastIndex) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Totals
                        PriceRow("Subtotal", order.subtotal)
                        Spacer(modifier = Modifier.height(4.dp))
                        PriceRow("Delivery Fee", order.deliveryFee)
                        Spacer(modifier = Modifier.height(4.dp))
                        PriceRow("Service Fee", order.serviceFee)
                        Spacer(modifier = Modifier.height(4.dp))
                        PriceRow("Tax", order.tax)
                        if (order.courierTip > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            PriceRow("Courier Tip", order.courierTip)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Total",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                            )
                            Text(
                                text = order.total.formatPrice(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Orange,
                            )
                        }
                    }
                }
            }

            // Action buttons
            item {
                OrderActionButtons(
                    status = order.status,
                    isPickup = order.isPickup,
                    scheduledFor = order.scheduledFor,
                    isUpdating = state.pendingOrderIds.contains(orderId),
                    onAccept = {
                        viewModel.updateOrderStatus(orderId, OrderStatus.ACCEPTED)
                    },
                    onStartPreparing = {
                        viewModel.updateOrderStatus(orderId, OrderStatus.PREPARING)
                    },
                    onMarkReady = {
                        viewModel.updateOrderStatus(orderId, OrderStatus.READY)
                    },
                    onComplete = {
                        viewModel.updateOrderStatus(orderId, OrderStatus.COMPLETED)
                    },
                    onCancel = { showRejectConfirm = true },
                    onCancelInProgress = { showCancelConfirm = true },
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PriceRow(label: String, amount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Text(
            text = amount.formatPrice(),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
private fun OrderActionButtons(
    status: OrderStatus,
    isPickup: Boolean,
    scheduledFor: String?,
    isUpdating: Boolean,
    onAccept: () -> Unit,
    onStartPreparing: () -> Unit,
    onMarkReady: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onCancelInProgress: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (status) {
            OrderStatus.SCHEDULED -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Scheduled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Orange,
                        )
                        if (scheduledFor != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val activatesAt = remember(scheduledFor) {
                                runCatching {
                                    java.time.ZonedDateTime.parse(scheduledFor)
                                        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d 'at' h:mm a"))
                                }.getOrElse { scheduledFor }
                            }
                            Text(
                                text = "Activates: $activatesAt",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This order will auto-activate at its scheduled time. You can pre-prep, but no customer action is needed yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
            OrderStatus.PENDING -> {
                Button(
                    onClick = onAccept,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Accept Order", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        // uses default
                    ),
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reject Order", fontWeight = FontWeight.SemiBold, color = ErrorRed)
                }
            }
            OrderStatus.ACCEPTED -> {
                Button(
                    onClick = onStartPreparing,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusPreparing),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Preparing", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onCancelInProgress,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel Order", fontWeight = FontWeight.SemiBold, color = ErrorRed)
                }
            }
            OrderStatus.PREPARING -> {
                Button(
                    onClick = onMarkReady,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusReady),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mark as Ready", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onCancelInProgress,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel Order", fontWeight = FontWeight.SemiBold, color = ErrorRed)
                }
            }
            OrderStatus.READY -> {
                if (isPickup) {
                    Button(
                        onClick = onComplete,
                        enabled = !isUpdating,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusAccepted),
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Complete Order", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusReady),
                    ) {
                        Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Awaiting Pickup…", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OrderStatus.PICKED_UP -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Out for Delivery",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusAccepted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "The courier has picked up this order and is en route to the customer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (status) {
                                OrderStatus.DELIVERED -> "Order Delivered"
                                OrderStatus.COMPLETED -> "Order Complete"
                                OrderStatus.CANCELLED -> "Order Cancelled"
                                else -> "Order Rejected"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (status) {
                                OrderStatus.DELIVERED, OrderStatus.COMPLETED -> SuccessGreen
                                else -> TextMuted
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (status) {
                                OrderStatus.DELIVERED -> "This order has been delivered to the customer."
                                OrderStatus.COMPLETED -> "This order has been completed successfully."
                                OrderStatus.CANCELLED -> "This order was cancelled."
                                else -> "This order was rejected."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
    }
}
