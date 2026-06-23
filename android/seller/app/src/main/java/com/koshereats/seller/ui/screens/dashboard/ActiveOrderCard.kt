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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koshereats.seller.data.models.Order
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusAccepted
import com.koshereats.seller.ui.theme.StatusCancelled
import com.koshereats.seller.ui.theme.StatusPending
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.StatusReady
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
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

            Spacer(modifier = Modifier.height(8.dp))

            // Fulfillment type chip + customer name + elapsed time
            val minutesAgo by produceState<Long?>(initialValue = null, key1 = order.createdAt) {
                while (true) {
                    value = runCatching {
                        val instant = runCatching { Instant.parse(order.createdAt) }
                            .recoverCatching { java.time.OffsetDateTime.parse(order.createdAt).toInstant() }
                            .getOrThrow()
                        Duration.between(instant, Instant.now()).toMinutes()
                    }.getOrNull()
                    delay(60_000L)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (order.isPickup) "PICKUP" else "DELIVERY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (order.isPickup) Orange else TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (order.isPickup) Orange.copy(alpha = 0.15f) else SurfaceDarkElevated)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (order.customerName.isNotBlank()) {
                        Text(
                            text = order.customerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
                if (order.status == OrderStatus.PENDING) {
                    PendingCountdown(createdAt = order.createdAt)
                } else {
                    val mins = minutesAgo
                    if (mins != null) {
                        Text(
                            text = if (mins < 1) "just now" else "${mins}m ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
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

            if (order.status == OrderStatus.SCHEDULED && order.scheduledFor != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val activatesAt = remember(order.scheduledFor) {
                    runCatching {
                        java.time.ZonedDateTime.parse(order.scheduledFor)
                            .withZoneSameInstant(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"))
                    }.getOrElse { order.scheduledFor }
                }
                Text(
                    text = "Activates: $activatesAt",
                    style = MaterialTheme.typography.bodySmall,
                    color = Orange,
                )
            }

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

/**
 * Live, per-second countdown to the backend's auto-reject deadline for a pending
 * order. Mirrors iOS PendingCountdown and the backend's pendingOrderTTL
 * (scheduler/dispatcher.go): after 10 minutes in 'pending' the order is
 * auto-rejected and the customer refunded, so the seller needs to see how close
 * they are. Flips to the error color in the final 2 minutes. Renders nothing if
 * createdAt can't be parsed, matching the iOS nil-date guard.
 */
@Composable
private fun PendingCountdown(createdAt: String) {
    val placedAt = remember(createdAt) {
        runCatching { Instant.parse(createdAt) }
            .recoverCatching { java.time.OffsetDateTime.parse(createdAt).toInstant() }
            .getOrNull()
    } ?: return

    val ttlSeconds = 10L * 60L
    val urgentThresholdSeconds = 2L * 60L

    val elapsedSeconds by produceState(
        initialValue = Duration.between(placedAt, Instant.now()).seconds.coerceAtLeast(0L),
        key1 = placedAt,
    ) {
        while (true) {
            value = Duration.between(placedAt, Instant.now()).seconds.coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val remaining = (ttlSeconds - elapsedSeconds).coerceAtLeast(0L)
    val expired = remaining <= 0L
    val urgent = remaining <= urgentThresholdSeconds

    val label = if (expired) {
        "Auto-rejecting…"
    } else {
        "Respond in ${formatMmSs(remaining)} • pending ${formatMmSs(elapsedSeconds)}"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (urgent) ErrorRed else StatusPending,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatMmSs(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun OrderStatusBadge(
    status: OrderStatus,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (status) {
        OrderStatus.SCHEDULED -> StatusPending to "Scheduled"
        OrderStatus.PENDING -> StatusPending to "Pending"
        OrderStatus.ACCEPTED -> StatusAccepted to "Accepted"
        OrderStatus.PREPARING -> StatusPreparing to "Preparing"
        OrderStatus.READY -> StatusReady to "Ready"
        OrderStatus.PICKED_UP -> StatusReady to "Picked Up"
        OrderStatus.DELIVERED -> StatusReady to "Delivered"
        OrderStatus.COMPLETED -> StatusReady to "Completed"
        OrderStatus.CANCELLED -> StatusCancelled to "Cancelled"
        OrderStatus.REJECTED -> StatusCancelled to "Rejected"
        OrderStatus.UNKNOWN -> TextMuted to "Unknown"
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
