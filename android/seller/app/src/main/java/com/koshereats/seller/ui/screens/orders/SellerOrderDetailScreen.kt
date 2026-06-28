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
import androidx.compose.material.icons.filled.DirectionsCar
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
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
    var rejectReason by remember { mutableStateOf("") }

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
            text = {
                Column {
                    Text("This will cancel the customer's order. This cannot be undone.", color = TextMuted)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = { Text("Reason (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRejectConfirm = false
                    viewModel.rejectPending(orderId, rejectReason.trim().ifBlank { null })
                    rejectReason = ""
                }) {
                    Text("Reject Order", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRejectConfirm = false
                    rejectReason = ""
                }) {
                    Text("Cancel", color = TextWhite)
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = "Failed to load order details. Please try again.",
                        color = ErrorRed,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.loadOrderDetail(orderId) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    ) {
                        Text("Retry", fontWeight = FontWeight.SemiBold)
                    }
                }
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

                        Spacer(modifier = Modifier.height(8.dp))
                        // Fulfillment chip — mirrors ActiveOrderCard's chip and the
                        // iOS statusHeader so the seller can tell pickup from delivery.
                        Text(
                            text = if (order.isPickup) "PICKUP" else "DELIVERY",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (order.isPickup) Orange else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (order.isPickup) Orange.copy(alpha = 0.15f) else SurfaceDarkElevated,
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )

                        val mins = minutesAgo
                        if (mins != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (mins < 1) "Placed just now" else "Placed $mins min ago",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }

                        // Delivery destination — only meaningful for delivery orders.
                        // Pickup orders have no customer address (the chip above says
                        // PICKUP), so hide the block rather than mislabel it.
                        if (!order.isPickup && order.deliveryAddress.isNotBlank()) {
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
                                        // Customer-selected (and paid-for) modifiers — the kitchen
                                        // needs these to prepare the order correctly. Mirrors iOS's
                                        // modifierSummary ("Large • Extra hummus").
                                        val modifierSummary = item.selectedModifiers
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.joinToString(" • ") { it.name }
                                        if (modifierSummary != null) {
                                            Text(
                                                text = modifierSummary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary,
                                            )
                                        }
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
                        if (order.discount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            SavingsRow("Savings", order.discount)
                        }
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
                    // Derive from the per-order delivery_mode the backend stamps,
                    // not the (possibly stale/absent) global restaurant state — a
                    // mismatch there could leave a READY delivery order with no
                    // actionable button.
                    isSelfDelivery = order.isSelfDelivery,
                    // Mirrors the backend escalate guard: no courier, not already on
                    // a provider, and a delivery (not pickup) order.
                    canEscalate = order.courier == null &&
                        order.externalDeliveryId.isNullOrEmpty() &&
                        !order.isPickup &&
                        order.isSelfDelivery &&
                        order.status == OrderStatus.READY,
                    // A courier is attached or the order was handed to an external
                    // provider — the order is no longer seller-drivable.
                    isExternallyDispatched = order.courier != null ||
                        !order.externalDeliveryId.isNullOrEmpty(),
                    hasCourier = order.courier != null,
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
                    onSelfPickup = {
                        viewModel.sellerPickupOrder(orderId)
                    },
                    onSelfDeliver = {
                        viewModel.sellerDeliverOrder(orderId)
                    },
                    onEscalate = {
                        viewModel.escalateOrderToUber(orderId)
                    },
                    onSetDeliveryMode = { mode ->
                        viewModel.setOrderDeliveryMode(orderId, mode)
                    },
                    onCancel = { showRejectConfirm = true },
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

/**
 * A discount/savings line, rendered as a negative amount ("-$X.XX") and tinted
 * success-green so the breakdown rows still sum to order.total. [amount] is the
 * positive discount amount in cents. Mirrors iOS savingsRow.
 */
@Composable
private fun SavingsRow(label: String, amount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SuccessGreen,
        )
        Text(
            text = "-${amount.formatPrice()}",
            style = MaterialTheme.typography.bodyMedium,
            color = SuccessGreen,
        )
    }
}

/**
 * Secondary action on an open delivery order (accepted/preparing): hand it off
 * to an Uber Direct courier when the seller is swamped. One-way — the backend
 * rejects orders already on a courier/provider, surfaced as an error Toast.
 * Mirrors iOS escalateButton ("Dispatch to Uber").
 */
/**
 * Informational card shown on a READY delivery order that's already been handed to
 * a courier or external delivery partner — replaces a misleading "Awaiting Pickup"
 * button with a passive status (parity with iOS's partner-handoff cards).
 */
@Composable
private fun DispatchStatusCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocalShipping,
                contentDescription = null,
                tint = StatusReady,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, color = TextSecondary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EscalateToUberButton(
    isUpdating: Boolean,
    onEscalate: () -> Unit,
) {
    Button(
        onClick = onEscalate,
        enabled = !isUpdating,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange),
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Dispatch to Uber", fontWeight = FontWeight.SemiBold)
    }
}

private fun readyButtonTitle(
    isPickup: Boolean,
    isSelfDelivery: Boolean,
): String = when {
    isPickup -> "Ready for customer pickup"
    isSelfDelivery -> "Ready for your driver"
    else -> "Ready for Uber pickup"
}

private fun canChooseDeliveryMode(
    status: OrderStatus,
    isPickup: Boolean,
    isExternallyDispatched: Boolean,
): Boolean =
    !isPickup &&
        !isExternallyDispatched &&
        (status == OrderStatus.ACCEPTED || status == OrderStatus.PREPARING || status == OrderStatus.READY)

@Composable
private fun DeliveryModeChoiceButton(
    isSelfDelivery: Boolean,
    isUpdating: Boolean,
    onSetDeliveryMode: (String) -> Unit,
) {
    val switchToSelfDelivery = !isSelfDelivery
    val title = if (switchToSelfDelivery) {
        "Self-deliver this order"
    } else {
        "Use Uber Direct for this order"
    }
    val icon = if (switchToSelfDelivery) Icons.Filled.LocalShipping else Icons.Filled.DirectionsCar
    val nextMode = if (switchToSelfDelivery) "restaurant" else "external"

    OutlinedButton(
        onClick = { onSetDeliveryMode(nextMode) },
        enabled = !isUpdating,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

/** Locked stand-in for the "Self-deliver this order" button shown while an
 *  external (Uber) dispatch is in flight. Non-interactive — switching to
 *  self-delivery here would race the dispatcher's create-delivery call and the
 *  backend rejects it, so we explain the lock instead of erroring on tap.
 *  Parity with iOS dispatchPendingSelfDeliverLock(). */
@Composable
private fun DispatchPendingSelfDeliverLock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Self-deliver this order", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Dispatching to Uber — you can't switch once a courier is assigned.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrderActionButtons(
    status: OrderStatus,
    isPickup: Boolean,
    isSelfDelivery: Boolean,
    // True when the order isn't on a courier or external provider yet — the
    // escalate-to-Uber action is available (accepted/preparing/ready).
    canEscalate: Boolean,
    // True once a courier has claimed the order or it's been dispatched to an
    // external provider (Uber/DoorDash) — the seller no longer drives it.
    isExternallyDispatched: Boolean,
    // True specifically when a platform/external courier is attached (vs. merely
    // dispatched and awaiting a courier).
    hasCourier: Boolean,
    scheduledFor: String?,
    isUpdating: Boolean,
    onAccept: () -> Unit,
    onStartPreparing: () -> Unit,
    onMarkReady: () -> Unit,
    onComplete: () -> Unit,
    onSelfPickup: () -> Unit,
    onSelfDeliver: () -> Unit,
    onEscalate: () -> Unit,
    onSetDeliveryMode: (String) -> Unit,
    onCancel: () -> Unit,
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
                                        .withZoneSameInstant(java.time.ZoneId.systemDefault())
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
                if (canChooseDeliveryMode(status, isPickup, isExternallyDispatched)) {
                    DeliveryModeChoiceButton(
                        isSelfDelivery = isSelfDelivery,
                        isUpdating = isUpdating,
                        onSetDeliveryMode = onSetDeliveryMode,
                    )
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
                        Text(readyButtonTitle(isPickup, isSelfDelivery), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (canChooseDeliveryMode(status, isPickup, isExternallyDispatched)) {
                    DeliveryModeChoiceButton(
                        isSelfDelivery = isSelfDelivery,
                        isUpdating = isUpdating,
                        onSetDeliveryMode = onSetDeliveryMode,
                    )
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
                } else if (isSelfDelivery && !isExternallyDispatched) {
                    // Restaurant self-delivers: no platform courier will ever pick this up,
                    // so the seller drives ready→picked_up themselves. Suppressed once the
                    // order has been escalated to an external provider — at that point it's
                    // partner-owned and the backend rejects a seller pickup.
                    Button(
                        onClick = onSelfPickup,
                        enabled = !isUpdating,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusAccepted),
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mark Picked Up (self-delivery)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (isExternallyDispatched) {
                    // Order is owned by a courier or external partner — show an
                    // informational handoff state rather than a misleading
                    // "Awaiting Pickup" button (parity with iOS).
                    DispatchStatusCard(
                        text = if (hasCourier) {
                            "A courier has the order — they'll deliver it shortly."
                        } else {
                            "Handed to a delivery partner — a driver is on the way."
                        },
                    )
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
                // The seller's own driver (or the wait for a platform courier) can
                // still be punted to Uber while the order sits in Ready — same
                // one-way escalate as accepted/preparing. Drops off once a courier
                // claims it or it's dispatched (canEscalate).
                if (canEscalate) {
                    EscalateToUberButton(isUpdating = isUpdating, onEscalate = onEscalate)
                } else if (!isPickup && !isSelfDelivery && !isExternallyDispatched) {
                    // External (Uber) dispatch is in flight: we've marked this ready
                    // for Uber and the dispatcher creates the delivery within seconds.
                    // Switching to self-delivery now races that create and the backend
                    // rejects it, so present the option locked with a reason rather
                    // than erroring on tap. Flips to the handoff card once dispatched.
                    DispatchPendingSelfDeliverLock()
                } else if (canChooseDeliveryMode(status, isPickup, isExternallyDispatched)) {
                    DeliveryModeChoiceButton(
                        isSelfDelivery = isSelfDelivery,
                        isUpdating = isUpdating,
                        onSetDeliveryMode = onSetDeliveryMode,
                    )
                }
            }
            OrderStatus.PICKED_UP -> {
                if (isSelfDelivery) {
                    // Restaurant self-delivers: seller drives picked_up→delivered themselves.
                    Button(
                        onClick = onSelfDeliver,
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
                            Text("Mark Delivered", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
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
