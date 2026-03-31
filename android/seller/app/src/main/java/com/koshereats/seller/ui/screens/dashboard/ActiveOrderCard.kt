package com.koshereats.seller.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusAccepted
import com.koshereats.seller.ui.theme.StatusCancelled
import com.koshereats.seller.ui.theme.StatusPending
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.StatusReady
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite

@Composable
fun ActiveOrderCard(
    order: Order,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${order.orderNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OrderStatusBadge(status = order.status)
                }

                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Customer info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = order.customerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Items summary
            Text(
                text = "${order.items.size} item${if (order.items.size != 1) "s" else ""} " +
                    order.items.take(2).joinToString(", ") { "${it.quantity}x ${it.menuItemName}" } +
                    if (order.items.size > 2) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AccessTime,
                        contentDescription = null,
                        tint = Orange,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${order.estimatedPrepTime} min",
                        style = MaterialTheme.typography.labelMedium,
                        color = Orange,
                    )
                }

                Text(
                    text = "$${String.format("%.2f", order.total)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                )
            }
        }
    }
}

@Composable
fun OrderStatusBadge(
    status: OrderStatus,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (status) {
        OrderStatus.PENDING -> StatusPending to "Pending"
        OrderStatus.ACCEPTED -> StatusAccepted to "Accepted"
        OrderStatus.PREPARING -> StatusPreparing to "Preparing"
        OrderStatus.READY -> StatusReady to "Ready"
        OrderStatus.PICKED_UP -> StatusReady to "Picked Up"
        OrderStatus.DELIVERED -> StatusReady to "Delivered"
        OrderStatus.COMPLETED -> StatusReady to "Completed"
        OrderStatus.CANCELLED -> StatusCancelled to "Cancelled"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
