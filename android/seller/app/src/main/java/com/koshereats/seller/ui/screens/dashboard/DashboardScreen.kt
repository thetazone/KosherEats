package com.koshereats.seller.ui.screens.dashboard

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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusAccepted
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.DashboardViewModel

@Composable
fun DashboardScreen(
    onOrderClick: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                    )
                    Text(
                        text = "Restaurant overview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }

                IconButton(onClick = { viewModel.refresh() }) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            color = Orange,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = Orange,
                        )
                    }
                }
            }
        }

        // Stats grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = "Today's Orders",
                    value = "${state.stats.totalOrdersToday}",
                    icon = Icons.Filled.ShoppingBag,
                    iconTint = Orange,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Revenue",
                    value = "$${String.format("%.0f", state.stats.revenueToday)}",
                    icon = Icons.Filled.AttachMoney,
                    iconTint = SuccessGreen,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = "Active Orders",
                    value = "${state.stats.activeOrders}",
                    icon = Icons.Filled.TrendingUp,
                    iconTint = StatusAccepted,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Avg Prep Time",
                    value = "${state.stats.averagePrepTime} min",
                    icon = Icons.Filled.AccessTime,
                    iconTint = StatusPreparing,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Weekly summary
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "This Week",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${state.stats.totalOrdersWeek} orders",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Weekly Revenue",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$${String.format("%.2f", state.stats.revenueWeek)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Active orders section
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Active Orders",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
            )
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Orange)
                }
            }
        } else if (state.activeOrders.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No active orders right now",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                    )
                }
            }
        } else {
            items(state.activeOrders, key = { it.id }) { order ->
                ActiveOrderCard(
                    order = order,
                    onClick = { onOrderClick(order.id) },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
            )

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
    }
}
