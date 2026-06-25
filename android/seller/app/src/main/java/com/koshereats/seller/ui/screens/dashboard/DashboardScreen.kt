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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusAccepted
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.viewmodels.AuthViewModel
import com.koshereats.seller.ui.viewmodels.DashboardViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOrderClick: (String) -> Unit,
    onViewAllOrders: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        if (state.error != null) {
            Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
        }
    }

    // Open/Closed toggle failures land in authState.toggleError. The switch's
    // checked state is bound to restaurant.isOpen (which the ViewModel leaves
    // untouched on failure), so it already reverts on its own — here we just
    // surface the error so the failure isn't silent.
    LaunchedEffect(authState.toggleError) {
        authState.toggleError?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            authViewModel.clearToggleError()
        }
    }

    // Gate polling on the activity lifecycle, not just composition: pressing Home
    // (ON_STOP) must tear down the 30s poll loop so we don't keep hitting the
    // network while backgrounded; ON_START resumes it. onDispose covers navigation
    // away from the screen. Mirrors the consumer Chat/OrderTracking pattern.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startPolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    if (showPicker) {
        RestaurantPickerSheet(
            onDismiss = { showPicker = false },
            // After a restaurant is picked, every /seller/ call will now carry
            // the new ?restaurant_id= param. Reload the dashboard so the stats
            // + active orders reflect the new selection.
            onChange = {
                authViewModel.refreshRestaurants()
                viewModel.loadDashboard()
            },
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
                        .semantics { role = Role.Button }
                        .clickable { showPicker = true },
                ) {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Restaurant overview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Switch restaurant",
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
                                            !isApproved -> "Pending approval"
                                            restaurant.isOpen -> "Open for orders"
                                            else -> "Closed"
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
                                Switch(
                                    checked = isApproved && restaurant.isOpen,
                                    onCheckedChange = { authViewModel.toggleOpen(it) },
                                    enabled = isApproved && !authState.isTogglingOpen,
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
                                    text = "We'll email you once the platform admin reviews your application. You can edit your menu and settings while you wait.",
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
                        title = "Today's Orders",
                        value = "${state.stats.todayOrders}",
                        icon = Icons.Filled.ShoppingBag,
                        iconTint = Orange,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        title = "Revenue",
                        value = state.stats.todayRevenue.formatPrice(),
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
                        title = "Delivery Earnings",
                        // Seller's 50% of delivery fees on orders they self-delivered today.
                        value = state.stats.todayDeliveryEarnings.formatPrice(),
                        icon = Icons.Filled.DirectionsCar,
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
                        title = "Avg Prep Time",
                        value = "${state.stats.avgPrepTime.roundToInt()} min",
                        icon = Icons.Filled.AccessTime,
                        iconTint = StatusPreparing,
                        modifier = Modifier.weight(1f),
                    )
                    // Keep the trailing card half-width (grid parity with iOS's
                    // last-row single card) rather than stretching it full-width.
                    Spacer(modifier = Modifier.weight(1f))
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

            if (state.isLoading && state.activeOrders.isEmpty()) {
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
                // The server-side active count is authoritative. If it exceeds the number
                // of orders we fetched (limit 100), signal truncation so the seller knows
                // to open the Orders tab for the full list.
                if (!state.isLoading && state.stats.activeOrders > state.activeOrders.size) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onViewAllOrders() },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Showing ${state.activeOrders.size} of ${state.stats.activeOrders} active orders",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "View all",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Orange,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = Orange,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
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
