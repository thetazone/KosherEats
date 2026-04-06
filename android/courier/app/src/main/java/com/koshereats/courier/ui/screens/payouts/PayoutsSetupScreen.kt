package com.koshereats.courier.ui.screens.payouts

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.courier.data.models.PayoutStatus
import com.koshereats.courier.data.repository.CourierRepository
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.ErrorRed
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SuccessGreen
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.TextSecondary
import com.koshereats.courier.ui.theme.TextWhite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PayoutsViewModel @Inject constructor(
    private val repo: CourierRepository,
) : ViewModel() {
    private val _status = MutableStateFlow<PayoutStatus?>(null)
    val status: StateFlow<PayoutStatus?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _status.value = repo.getPayoutStatus().getOrNull()
    }

    /**
     * Starts the hosted onboarding flow: creates a Stripe Connect Express
     * account (idempotent), fetches a fresh account link, and returns the
     * URL so the caller can open it in Chrome Custom Tabs.
     */
    suspend fun startOnboarding(): String? {
        _error.value = null
        val acct = repo.createPayoutAccount()
        if (acct.isFailure) {
            _error.value = acct.exceptionOrNull()?.message
            return null
        }
        val link = repo.getPayoutLink()
        if (link.isFailure) {
            _error.value = link.exceptionOrNull()?.message
            return null
        }
        return link.getOrNull()
    }
}

@Composable
fun PayoutsSetupScreen(
    onBack: () -> Unit,
    vm: PayoutsViewModel = hiltViewModel(),
) {
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Header()

        if (status?.payoutReady == true) {
            ReadyCard(onUpdate = {
                scope.launch {
                    isLoading = true
                    vm.startOnboarding()?.let { openCustomTab(context, it) }
                    isLoading = false
                    vm.refresh()
                }
            })
        } else {
            SetupCard(
                isLoading = isLoading,
                onSetUp = {
                    scope.launch {
                        isLoading = true
                        vm.startOnboarding()?.let { openCustomTab(context, it) }
                        isLoading = false
                        vm.refresh()
                    }
                },
            )
        }

        error?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.MonetizationOn,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Direct deposit", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Get paid for every delivery, straight to your bank account.",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SetupCard(isLoading: Boolean, onSetUp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up with Stripe", color = TextWhite, fontWeight = FontWeight.SemiBold)
        Text(
            "Stripe securely handles your bank info and tax forms. It takes about 3 minutes.",
            color = TextSecondary, fontSize = 13.sp,
        )
        Bullet(Icons.Filled.Lock, "Bank-level security")
        Bullet(Icons.Filled.Schedule, "Same-day or 2-day transfers")
        Bullet(Icons.Filled.Description, "Automatic tax forms (1099-NEC)")

        Button(
            onClick = onSetUp,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
            } else {
                Text("Set up payouts", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReadyCard(onUpdate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(48.dp))
        Text("Payouts are ready", color = TextWhite, fontWeight = FontWeight.SemiBold)
        Text(
            "You'll receive earnings from every delivery directly in your bank account.",
            color = TextSecondary, fontSize = 13.sp,
        )
        OutlinedButton(
            onClick = onUpdate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Update banking info", color = Orange) }
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, color = TextSecondary, fontSize = 13.sp)
    }
}

private fun openCustomTab(context: android.content.Context, url: String) {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .launchUrl(context, Uri.parse(url))
}
