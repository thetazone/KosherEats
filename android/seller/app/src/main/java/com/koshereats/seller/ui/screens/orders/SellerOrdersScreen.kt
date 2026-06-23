package com.koshereats.seller.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.OrderStatus
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.screens.dashboard.ActiveOrderCard
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.OrdersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerOrdersScreen(
    onOrderClick: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        if (state.error != null) {
            Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
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
        onDispose { viewModel.stopPolling() }
    }

    val filters = listOf(
        null to "All",
        OrderStatus.SCHEDULED to "Scheduled",
        OrderStatus.PENDING to "Pending",
        OrderStatus.ACCEPTED to "Accepted",
        OrderStatus.PREPARING to "Preparing",
        OrderStatus.READY to "Ready",
        OrderStatus.PICKED_UP to "Picked Up",
        OrderStatus.DELIVERED to "Delivered",
        OrderStatus.COMPLETED to "Completed",
        OrderStatus.CANCELLED to "Cancelled",
        OrderStatus.REJECTED to "Rejected",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        // Header
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Orders",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
            )
            Text(
                text = "Manage incoming orders",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.forEach { (status, label) ->
                FilterChip(
                    selected = state.selectedFilter == status,
                    onClick = { viewModel.loadOrders(status) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextMuted,
                        selectedContainerColor = Orange.copy(alpha = 0.2f),
                        selectedLabelColor = Orange,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = SurfaceDark,
                        selectedBorderColor = Orange.copy(alpha = 0.5f),
                        enabled = true,
                        selected = state.selectedFilter == status,
                    ),
                )
            }
        }

        // Today's-sales ticker (mirrors iOS SellerOrdersView.todayTicker): count + subtotal sum of
        // today's orders, excluding cancelled/rejected. Only shown on the "All" filter, since under a
        // status filter state.orders is narrowed client-side and would under-report the day's totals.
        val today = remember(state.orders, state.selectedFilter) {
            if (state.selectedFilter != null) return@remember null
            val zone = java.time.ZoneId.systemDefault()
            val todayDate = java.time.LocalDate.now(zone)
            val todays = state.orders.filter { order ->
                if (order.status == OrderStatus.CANCELLED || order.status == OrderStatus.REJECTED) {
                    return@filter false
                }
                val instant = runCatching { java.time.Instant.parse(order.createdAt) }
                    .recoverCatching { java.time.OffsetDateTime.parse(order.createdAt).toInstant() }
                    .getOrNull() ?: return@filter false
                instant.atZone(zone).toLocalDate() == todayDate
            }
            if (todays.isEmpty()) null
            else todays.size to todays.sumOf { it.subtotal }
        }
        if (today != null) {
            val (todayCount, todaySubtotal) = today
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$todayCount orders today",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
                Text(
                    text = "${todaySubtotal.formatPrice()} food sales",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Orange,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Orders list
        val listState = rememberLazyListState()
        val shouldLoadMore by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                lastVisible >= totalItems - 3 && totalItems > 0
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && state.hasMorePages && !state.isLoadingMore) {
                viewModel.loadMoreOrders()
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CircularProgressIndicator(color = Orange)
                }
            } else if (state.orders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "No orders found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.orders, key = { it.id }) { order ->
                        ActiveOrderCard(
                            order = order,
                            onClick = { onOrderClick(order.id) },
                        )
                    }
                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = Orange,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}
