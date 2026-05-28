package com.greeneats.seller.ui.screens.orders

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalShipping
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.seller.data.models.OrderStatus
import com.greeneats.seller.data.models.formatPrice
import com.greeneats.seller.ui.screens.dashboard.OrderStatusBadge
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.DividerColor
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.StatusAccepted
import com.greeneats.seller.ui.theme.StatusPreparing
import com.greeneats.seller.ui.theme.StatusReady
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.OrdersViewModel

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
    var pendingAction by remember { mutableStateOf<Pair<OrderStatus, () -> Unit>?>(null) }

    // Confirmation dialog for cancel/reject actions
    pendingAction?.let { (targetStatus, action) ->
        val title = when (targetStatus) {
            OrderStatus.CANCELLED -> "Cancel Order"
            else -> "Confirm Status Change"
        }
        val message = when (targetStatus) {
            OrderStatus.CANCELLED -> "Are you sure you want to cancel this order? This cannot be undone."
            OrderStatus.ACCEPTED -> "Accept this order? The customer will be notified."
            OrderStatus.PREPARING -> "Start preparing this order?"
            OrderStatus.READY -> "Mark this order as ready for pickup?"
            OrderStatus.COMPLETED -> "Mark this order as completed?"
            else -> "Change order status to ${targetStatus.displayName}?"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title, color = TextWhite) },
            text = { Text(message, color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    pendingAction = null
                }) {
                    Text(
                        if (targetStatus == OrderStatus.CANCELLED) "Cancel Order" else "Confirm",
                        color = if (targetStatus == OrderStatus.CANCELLED) ErrorRed else Orange,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Go Back", color = TextWhite)
                }
            },
            containerColor = SurfaceDark,
        )
    }

    LaunchedEffect(orderId) {
        viewModel.loadOrderDetail(orderId)
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
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
                    modifier = if (order != null) {
                        Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Order ID", order.id))
                            Toast.makeText(context, "Order ID copied", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Modifier
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isDetailLoading || order == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Orange)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Order status: ${order.status.displayName}"
                                },
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
                            modifier = Modifier.semantics { heading() },
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
                                        if (!item.specialInstructions.isNullOrBlank()) {
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
                    isUpdating = state.pendingOrderIds.contains(orderId),
                    onAccept = {
                        pendingAction = OrderStatus.ACCEPTED to {
                            viewModel.updateOrderStatus(orderId, OrderStatus.ACCEPTED)
                        }
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
                    onCancel = {
                        pendingAction = OrderStatus.CANCELLED to {
                            viewModel.updateOrderStatus(orderId, OrderStatus.CANCELLED)
                        }
                    },
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

private object OrderDetailStrings {
    const val ACCEPT_ORDER = "Accept Order"
    const val REJECT_ORDER = "Reject Order"
    const val START_PREPARING = "Start Preparing"
    const val CANCEL_ORDER = "Cancel Order"
    const val MARK_READY = "Mark as Ready"
    const val COMPLETE_ORDER = "Complete Order"
    const val MARK_COMPLETED = "Mark as Completed"
    const val UPDATING_ORDER = "Updating order status"
}

@Composable
private fun OrderActionButtons(
    status: OrderStatus,
    isUpdating: Boolean,
    onAccept: () -> Unit,
    onStartPreparing: () -> Unit,
    onMarkReady: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (status) {
            OrderStatus.PENDING -> {
                Button(
                    onClick = onAccept,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            color = TextWhite, strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics { contentDescription = OrderDetailStrings.UPDATING_ORDER },
                        )
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OrderDetailStrings.ACCEPT_ORDER, fontWeight = FontWeight.SemiBold)
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
                    Text(OrderDetailStrings.REJECT_ORDER, fontWeight = FontWeight.SemiBold, color = ErrorRed)
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
                        CircularProgressIndicator(
                            color = TextWhite, strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics { contentDescription = OrderDetailStrings.UPDATING_ORDER },
                        )
                    } else {
                        Icon(Icons.Filled.Restaurant, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OrderDetailStrings.START_PREPARING, fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(OrderDetailStrings.CANCEL_ORDER, fontWeight = FontWeight.SemiBold, color = ErrorRed)
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
                        CircularProgressIndicator(
                            color = TextWhite, strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics { contentDescription = OrderDetailStrings.UPDATING_ORDER },
                        )
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OrderDetailStrings.MARK_READY, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OrderStatus.READY -> {
                Button(
                    onClick = onComplete,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusAccepted),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            color = TextWhite, strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics { contentDescription = OrderDetailStrings.UPDATING_ORDER },
                        )
                    } else {
                        Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OrderDetailStrings.COMPLETE_ORDER, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OrderStatus.PICKED_UP -> {
                Button(
                    onClick = onComplete,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            color = TextWhite, strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics { contentDescription = OrderDetailStrings.UPDATING_ORDER },
                        )
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(OrderDetailStrings.MARK_COMPLETED, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            else -> {
                // No actions for completed/cancelled
            }
        }
    }
}
