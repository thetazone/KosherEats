package com.koshereats.consumer.ui.screens.orders

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.OrderItem
import com.koshereats.consumer.data.models.OrderStatus
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.OrderDetailViewModel

/**
 * Cancellable statuses — kept in sync with the backend orders.cancel whitelist
 * (pending/accepted) and the iOS OrderDetailView gate. SCHEDULED is included to
 * mirror iOS so a customer who booked ahead can request a cancel; the server is
 * the source of truth and any rejection surfaces via the error dialog.
 */
private fun OrderStatus.isCancellable(): Boolean =
    this == OrderStatus.SCHEDULED || this == OrderStatus.PENDING || this == OrderStatus.ACCEPTED

/**
 * Receipt / order-detail screen for non-active (terminal) orders — delivered,
 * completed, cancelled, rejected — and any order opened outside the live map.
 * Mirrors iOS OrderDetailView: status header, restaurant, line items with
 * modifiers, price breakdown, delivery address, and a gated Cancel action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
    vm: OrderDetailViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(orderId) { vm.load(orderId) }

    var showCancelConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = { Text("Order Details", color = TextWhite, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        val order = ui.order
        when {
            order == null && ui.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange)
                }
            }
            order == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = ui.errorMessage ?: "Couldn't load order details.",
                            color = TextMuted,
                        )
                        TextButton(onClick = { vm.retry(orderId) }) {
                            Text("Retry", color = Orange)
                        }
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusCard(status = order.status)

                    if (order.status == OrderStatus.REJECTED) {
                        RejectedNotice()
                    }

                    RestaurantHeader(order = order)

                    ItemsSection(items = order.items)

                    if (order.fulfillmentType != "pickup") {
                        DeliverySection(address = order.deliveryAddress)
                    }

                    PriceBreakdown(order = order)

                    if (order.status.isCancellable()) {
                        OutlinedButton(
                            onClick = { showCancelConfirm = true },
                            enabled = !ui.isCancelling,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        ) {
                            if (ui.isCancelling) {
                                CircularProgressIndicator(
                                    color = ErrorRed,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Text("Cancel Order", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = "Order #${order.id.take(8)}",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            containerColor = SurfaceDark,
            title = { Text("Cancel this order?", color = TextWhite) },
            text = {
                Text(
                    "This can't be undone. You'll be refunded to your original payment method.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    vm.cancelOrder(orderId)
                }) {
                    Text("Cancel Order", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep Order", color = TextSecondary)
                }
            },
        )
    }

    ui.cancelError?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissCancelError() },
            containerColor = SurfaceDark,
            title = { Text("Couldn't cancel order", color = TextWhite) },
            text = { Text(message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { vm.dismissCancelError() }) {
                    Text("OK", color = Orange)
                }
            },
        )
    }
}

@Composable
private fun StatusCard(status: OrderStatus) {
    val color = statusColor(status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = status.displayName,
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun statusColor(status: OrderStatus): androidx.compose.ui.graphics.Color = when (status) {
    OrderStatus.SCHEDULED, OrderStatus.PENDING -> WarningYellow
    OrderStatus.ACCEPTED, OrderStatus.PREPARING -> Orange
    OrderStatus.READY, OrderStatus.PICKED_UP -> InfoBlue
    OrderStatus.DELIVERED, OrderStatus.COMPLETED -> SuccessGreen
    OrderStatus.CANCELLED, OrderStatus.REJECTED -> ErrorRed
    OrderStatus.UNKNOWN -> TextMuted
}

@Composable
private fun RejectedNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Restaurant couldn't take this order",
                color = TextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Your payment has been refunded to your original payment method.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RestaurantHeader(order: Order) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SurfaceDarkElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Restaurant, contentDescription = null, tint = Orange)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.restaurantName,
                    color = TextWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (order.createdAt.isNotEmpty()) {
                    Text(
                        text = order.createdAt.take(10),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsSection(items: List<OrderItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Items", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        text = "${item.quantity}x",
                        color = Orange,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = TextWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        val modifierLabel = item.selectedModifiers
                            .filter { it.name.isNotEmpty() }
                            .joinToString(", ") { it.name }
                        if (modifierLabel.isNotEmpty()) {
                            Text(text = modifierLabel, color = TextTertiary, fontSize = 12.sp)
                        }
                        item.specialInstructions?.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(text = notes, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = (item.price * item.quantity).formatPrice(),
                        color = TextSecondary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliverySection(address: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Orange, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Delivery Address", color = TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(text = address.ifEmpty { "—" }, color = TextWhite, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PriceBreakdown(order: Order) {
    // Driver tip mirrors iOS, which reads `courierTip`. Fall back to the legacy
    // `tip` field for older orders that predate the courier_tip split.
    val tip = if (order.courierTip > 0) order.courierTip else order.tip
    // The persisted Order carries no discount field; derive one only when the
    // line components overshoot the charged total, so a discounted order's
    // breakdown still reconciles to its total (display-only).
    val components = order.subtotal + order.deliveryFee + order.serviceFee + order.tax + tip
    val discount = (components - order.total).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryRow("Subtotal", order.subtotal.formatPrice())
            if (discount > 0) {
                // Mirrors iOS OrderDetailView's "Savings" row (value formatted "-$X.XX").
                SummaryRow("Savings", "-${discount.formatPrice()}", valueColor = SuccessGreen)
            }
            SummaryRow("Delivery Fee", order.deliveryFee.formatPrice())
            SummaryRow("Service Fee", order.serviceFee.formatPrice())
            SummaryRow("Tax", order.tax.formatPrice())
            if (tip > 0) {
                SummaryRow("Driver Tip", tip.formatPrice())
            }
            HorizontalDivider(color = SurfaceDarkBorder)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Total", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(order.total.formatPrice(), color = Orange, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextSecondary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp)
    }
}
