package com.greeneats.courier.ui.screens.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.courier.R
import com.greeneats.courier.data.models.AvailableDelivery
import com.greeneats.courier.data.models.formatPrice
import com.greeneats.courier.ui.screens.delivery.DeliveryMapScreen
import com.greeneats.courier.ui.theme.BackgroundBlack
import com.greeneats.courier.ui.theme.Orange
import com.greeneats.courier.ui.theme.SurfaceDark
import com.greeneats.courier.ui.theme.TextMuted
import com.greeneats.courier.ui.theme.TextSecondary
import com.greeneats.courier.ui.theme.TextTertiary
import com.greeneats.courier.ui.theme.TextWhite
import com.greeneats.courier.ui.viewmodels.DashboardViewModel

@Composable
fun DashboardScreen(
    onOpenChat: (String) -> Unit = {},
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.resumeIfActive()
        vm.refresh()
    }

    val activeOrder = state.active.firstOrNull()
    if (activeOrder != null) {
        // Full-screen delivery map takes over the content area — the bottom
        // nav from CourierNavHost's Scaffold is still visible around it.
        DeliveryMapScreen(
            order = activeOrder,
            viewModel = vm,
            onOpenChat = onOpenChat,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.connectionLost) ConnectionLostBanner()
        OnlineToggleCard(state.isOnline, onToggle = { vm.toggleOnline() })

        if (state.isOnline) {
            AvailableSection(state.available, state.isLoading, onAccept = { vm.claim(it) })
        } else {
            OfflineHero()
        }
    }
}

@Composable
private fun OnlineToggleCard(isOnline: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isOnline) "Online" else "Offline",
                color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (isOnline) "You're receiving delivery requests" else "Go online to start earning",
                color = TextTertiary, fontSize = 12.sp,
            )
        }
        Switch(
            checked = isOnline,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Orange,
                uncheckedTrackColor = SurfaceDark,
            ),
        )
    }
}

@Composable
private fun AvailableSection(list: List<AvailableDelivery>, isLoading: Boolean, onAccept: (AvailableDelivery) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_available_nearby), color = TextWhite, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (isLoading) CircularProgressIndicator(color = Orange, modifier = Modifier.size(18.dp))
        }
        if (list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.dashboard_no_deliveries), color = TextSecondary)
                Text(stringResource(R.string.dashboard_no_deliveries_subtitle), color = TextMuted, fontSize = 12.sp)
            }
        } else {
            list.forEach { d ->
                AvailableDeliveryCard(d, onAccept = { onAccept(d) })
            }
        }
    }
}

@Composable
private fun AvailableDeliveryCard(d: AvailableDelivery, onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(d.deliveryFee.formatPrice(), color = Orange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Restaurant, contentDescription = null, tint = Orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(d.restaurantName, color = TextWhite)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Home, contentDescription = null, tint = Orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(d.deliveryAddress, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
        ) { Text(stringResource(R.string.dashboard_accept), color = Color.White, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ConnectionLostBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFB71C1C), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Column {
            Text(stringResource(R.string.dashboard_connection_lost), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(stringResource(R.string.dashboard_connection_lost_subtitle), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun OfflineHero() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dashboard_offline), color = TextWhite, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dashboard_offline_subtitle), color = TextSecondary, fontSize = 12.sp)
        }
    }
}
