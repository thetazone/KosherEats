package com.greeneats.seller.ui.screens.dashboard

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
import androidx.compose.material.icons.filled.ChevronRight
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
import com.greeneats.seller.data.models.Order
import com.greeneats.seller.data.models.OrderStatus
import com.greeneats.seller.data.models.formatPrice
import com.greeneats.seller.ui.theme.DividerColor
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.StatusAccepted
import com.greeneats.seller.ui.theme.StatusCancelled
import com.greeneats.seller.ui.theme.StatusPending
import com.greeneats.seller.ui.theme.StatusPreparing
import com.greeneats.seller.ui.theme.StatusReady
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite

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
                        text = "#${order.id.take(8)}",
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

            // Items summary
            val count = order.items.size
            val itemLabel = if (count == 1) "item" else "items"
            val preview = order.items.take(2).joinToString(", ") { "${it.quantity}x ${it.menuItemName}" }
            val suffix = if (count > 2) "..." else ""
            Text(
                text = "$count $itemLabel $preview$suffix",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = order.total.formatPrice(),
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
    val color = when (status) {
        OrderStatus.SCHEDULED, OrderStatus.PENDING -> StatusPending
        OrderStatus.ACCEPTED -> StatusAccepted
        OrderStatus.PREPARING -> StatusPreparing
        OrderStatus.READY, OrderStatus.PICKED_UP, OrderStatus.DELIVERED, OrderStatus.COMPLETED -> StatusReady
        OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.UNKNOWN -> StatusCancelled
    }
    val label = status.displayName

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
