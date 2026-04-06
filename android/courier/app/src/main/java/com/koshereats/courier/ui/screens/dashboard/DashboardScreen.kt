package com.koshereats.courier.ui.screens.dashboard

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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.courier.data.models.AvailableDelivery
import com.koshereats.courier.data.models.CourierOrder
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.TextMuted
import com.koshereats.courier.ui.theme.TextSecondary
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.DashboardViewModel

@Composable
fun DashboardScreen(
    onOpenChat: (String) -> Unit = {},
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnlineToggleCard(state.isOnline, onToggle = { vm.toggleOnline() })

        val activeOrder = state.active.firstOrNull()
        when {
            activeOrder != null -> ActiveDeliveryCard(
                order = activeOrder,
                onPickup = { vm.pickup(it) },
                onDeliver = { vm.deliver(it) },
                onOpenChat = onOpenChat,
            )
            state.isOnline -> AvailableSection(state.available, state.isLoading, onAccept = { vm.claim(it) })
            else -> OfflineHero()
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
            Text("Available nearby", color = TextWhite, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (isLoading) CircularProgressIndicator(color = Orange, modifier = Modifier.size(18.dp))
        }
        if (list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No deliveries right now", color = TextSecondary)
                Text("You'll be notified when a new order is ready.", color = TextMuted, fontSize = 12.sp)
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
        Text("$${"%.2f".format(d.deliveryFee / 100.0)}", color = Orange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
        ) { Text("Accept", color = Color.White, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ActiveDeliveryCard(
    order: CourierOrder,
    onPickup: (CourierOrder) -> Unit,
    onDeliver: (CourierOrder) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val phase = if (order.status == "ready") "Heading to restaurant" else "Delivering"
    val actionLabel = if (order.status == "ready") "I've picked it up" else "Mark delivered"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row: phase label on the left, chat shortcut on the right.
        // Matches the iOS courier DashboardView where the message button
        // sits alongside the status chip on the active order card.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phase.uppercase(),
                color = Orange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onOpenChat(order.id) }) {
                Icon(
                    imageVector = Icons.Filled.ChatBubble,
                    contentDescription = "Open chat",
                    tint = Orange,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Column {
            Text("Pickup", color = TextTertiary, fontSize = 11.sp)
            Text(order.restaurantName, color = TextWhite)
        }
        Divider(color = SurfaceDark)
        Column {
            Text("Dropoff", color = TextTertiary, fontSize = 11.sp)
            Text(order.deliveryAddress, color = TextWhite)
        }

        Button(
            onClick = { if (order.status == "ready") onPickup(order) else onDeliver(order) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
        ) { Text(actionLabel, color = Color.White, fontWeight = FontWeight.SemiBold) }
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
            Text("You're offline", color = TextWhite, fontWeight = FontWeight.SemiBold)
            Text("Tap the toggle above to start receiving deliveries.", color = TextSecondary, fontSize = 12.sp)
        }
    }
}
