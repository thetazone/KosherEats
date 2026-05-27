package com.greeneats.seller.ui.screens.dashboard

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.seller.data.models.formatPriceWhole
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.StatusAccepted
import com.greeneats.seller.ui.theme.StatusPreparing
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.viewmodels.AuthViewModel
import com.greeneats.seller.ui.viewmodels.DashboardViewModel

private object DashboardStrings {
    const val TITLE = "Dashboard"
    const val SUBTITLE = "Restaurant overview"
    const val SWITCH_RESTAURANT = "Switch restaurant"
    const val PENDING_APPROVAL = "Pending approval"
    const val OPEN_FOR_ORDERS = "Open for orders"
    const val CLOSED = "Closed"
    const val CLOSE_RESTAURANT = "Close restaurant"
    const val OPEN_RESTAURANT = "Open restaurant"
    const val PENDING_APPROVAL_BODY = "We'll email you once the platform admin reviews your application. You can edit your menu and settings while you wait."
    const val TODAYS_ORDERS = "Today's Orders"
    const val REVENUE = "Revenue"
    const val ACTIVE_ORDERS = "Active Orders"
    const val AVG_PREP_TIME = "Avg Prep Time"
    const val ACTIVE_ORDERS_SECTION = "Active Orders"
    const val NO_ACTIVE_ORDERS = "No active orders right now"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOrderClick: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        RestaurantPickerSheet(
            onDismiss = { showPicker = false },
            // After a restaurant is picked, every /seller/ call will now carry
            // the new ?restaurant_id= param. Reload the dashboard so the stats
            // + active orders reflect the new selection.
            onChange = { viewModel.loadDashboard() },
        )
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPicker = true },
                ) {
                    Text(
                        text = DashboardStrings.TITLE,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = DashboardStrings.SUBTITLE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = DashboardStrings.SWITCH_RESTAURANT,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Open / Closed toggle. Disabled while the restaurant is still
            // awaiting platform approval — the toggle is enforced server-side
            // too, but greying it out keeps the UI honest about why a seller
            // can't go live yet.
            val restaurant = authState.restaurant
            if (restaurant != null) {
                val isApproved = restaurant.approvalStatus.equals("approved", ignoreCase = true)
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = when {
                                            !isApproved -> DashboardStrings.PENDING_APPROVAL
                                            restaurant.isOpen -> DashboardStrings.OPEN_FOR_ORDERS
                                            else -> DashboardStrings.CLOSED
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            !isApproved -> Orange
                                            restaurant.isOpen -> SuccessGreen
                                            else -> ErrorRed
                                        },
                                    )
                                    Text(
                                        text = restaurant.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                                val toggleLabel = if (isApproved && restaurant.isOpen) DashboardStrings.CLOSE_RESTAURANT else DashboardStrings.OPEN_RESTAURANT
                                Switch(
                                    checked = isApproved && restaurant.isOpen,
                                    onCheckedChange = { authViewModel.toggleOpen(it) },
                                    enabled = isApproved && !authState.isTogglingOpen,
                                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = TextWhite,
                                        checkedTrackColor = SuccessGreen,
                                        uncheckedThumbColor = TextWhite,
                                        uncheckedTrackColor = TextMuted,
                                        disabledCheckedTrackColor = TextMuted.copy(alpha = 0.5f),
                                        disabledUncheckedTrackColor = TextMuted.copy(alpha = 0.5f),
                                    ),
                                )
                            }
                            if (!isApproved) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = DashboardStrings.PENDING_APPROVAL_BODY,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
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
                        title = DashboardStrings.TODAYS_ORDERS,
                        value = "${state.stats.todayOrders}",
                        icon = Icons.Filled.ShoppingBag,
                        iconTint = Orange,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        title = DashboardStrings.REVENUE,
                        value = state.stats.todayRevenue.formatPriceWhole(),
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
                        title = DashboardStrings.ACTIVE_ORDERS,
                        value = "${state.stats.activeOrders}",
                        icon = Icons.Filled.TrendingUp,
                        iconTint = StatusAccepted,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        title = DashboardStrings.AVG_PREP_TIME,
                        value = "${state.stats.avgPrepTime.toInt()} min",
                        icon = Icons.Filled.AccessTime,
                        iconTint = StatusPreparing,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Active orders section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = DashboardStrings.ACTIVE_ORDERS_SECTION,
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
                            text = DashboardStrings.NO_ACTIVE_ORDERS,
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
                    contentDescription = title,
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
