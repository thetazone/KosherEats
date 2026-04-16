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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.OrderStatus
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.OrdersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onOrderClick: (String) -> Unit,
    onReorderClick: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text("Your Orders", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (uiState.isLoading && uiState.orders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
        } else if (uiState.orders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Receipt,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(80.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No orders yet",
                        color = TextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your order history will appear here",
                        color = TextTertiary,
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.orders, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        onClick = { onOrderClick(order.id) },
                        onReorder = { onReorderClick(order.restaurantId) },
                    )
                }

                if (uiState.isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: Order,
    onClick: () -> Unit,
    onReorder: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = order.restaurantName,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Order #${order.orderNumber}",
                        color = TextTertiary,
                        fontSize = 12.sp,
                    )
                }
                OrderStatusBadge(status = order.status)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = SurfaceDarkBorder)
            Spacer(modifier = Modifier.height(12.dp))

            // Items summary
            order.items.take(3).forEach { item ->
                Text(
                    text = "${item.quantity}x ${item.name}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
            if (order.items.size > 3) {
                Text(
                    text = "+${order.items.size - 3} more items",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Total + date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = order.total.formatPrice(),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = order.createdAt.take(10),
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }

            // Reorder button for delivered orders
            if (order.status == OrderStatus.DELIVERED) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onReorder,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reorder", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun OrderStatusBadge(status: OrderStatus) {
    val (color, icon) = when (status) {
        OrderStatus.PENDING -> Orange to Icons.Filled.Pending
        OrderStatus.CONFIRMED -> InfoBlue to Icons.Filled.CheckCircle
        OrderStatus.PREPARING -> WarningYellow to Icons.Filled.Restaurant
        OrderStatus.READY_FOR_PICKUP -> SuccessGreen to Icons.Filled.CheckCircle
        OrderStatus.OUT_FOR_DELIVERY -> InfoBlue to Icons.Filled.DeliveryDining
        OrderStatus.DELIVERED -> SuccessGreen to Icons.Filled.CheckCircle
        OrderStatus.CANCELLED -> ErrorRed to Icons.Filled.Pending
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = status.displayName,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
