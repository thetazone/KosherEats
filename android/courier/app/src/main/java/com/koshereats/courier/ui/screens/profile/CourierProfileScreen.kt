package com.koshereats.courier.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.courier.data.models.CourierProfile
import com.koshereats.courier.ui.theme.ErrorRed
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SuccessGreen
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.SurfaceDarkBorder
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.AuthViewModel

@Composable
fun CourierProfileScreen(
    onLogout: () -> Unit,
    onPayoutsClick: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by authViewModel.state.collectAsState()
    val profile = state.profile ?: return
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.koshereats.courier.ui.theme.BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatsCard(profile)
        VehicleCard(profile)
        PayoutRow(profile, onPayoutsClick)

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Log out", color = Orange, fontWeight = FontWeight.SemiBold) }

        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Delete account", color = ErrorRed, fontWeight = FontWeight.SemiBold) }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = { Text("Delete account?", color = TextWhite) },
            text = {
                Text(
                    "This permanently deletes your courier account, earnings history, and payout setup. This cannot be undone.",
                    color = TextTertiary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    authViewModel.deleteAccount()
                }) {
                    Text("Delete", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextTertiary)
                }
            },
        )
    }
}

@Composable
private fun StatsCard(profile: CourierProfile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        StatBlock(profile.totalDeliveries.toString(), "Deliveries", Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(SurfaceDarkBorder),
        )
        StatBlock("%.1f".format(profile.rating), "Rating", Modifier.weight(1f))
    }
}

@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun VehicleCard(profile: CourierProfile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Vehicle", color = TextWhite, fontWeight = FontWeight.SemiBold)
        InfoRow("Type", profile.vehicleType.replaceFirstChar { it.uppercaseChar() })
        if (profile.vehicleMake.isNotBlank()) {
            InfoRow("Make / model", "${profile.vehicleMake} ${profile.vehicleModel}")
        }
        if (profile.licensePlate.isNotBlank()) {
            InfoRow("Plate", profile.licensePlate)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextTertiary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextWhite, fontSize = 13.sp)
    }
}

@Composable
private fun PayoutRow(profile: CourierProfile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = Orange)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Direct deposit", color = TextWhite)
            Text(
                if (profile.payoutReady) "Active" else "Set up to get paid",
                color = if (profile.payoutReady) SuccessGreen else Orange,
                fontSize = 11.sp,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextTertiary)
    }
}
