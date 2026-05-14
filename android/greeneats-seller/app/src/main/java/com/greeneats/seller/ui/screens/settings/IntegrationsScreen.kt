package com.greeneats.seller.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.seller.data.models.POSIntegration
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.IntegrationsViewModel
import kotlinx.coroutines.launch

// Settings → Integrations. Mirrors the iOS IntegrationsView. Lets a seller
// connect a POS (Clover today) so accepted orders auto-print at the kitchen.
// The Connect button launches a Chrome Custom Tab against our backend's
// /seller/integrations/clover/connect-url; backend returns the Clover OAuth
// AuthorizeURL with our HMAC state token. After Clover redirects to the
// backend callback the seller dismisses the browser manually — list
// refreshes on the next LaunchedEffect tick.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    viewModel: IntegrationsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val testResults: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    var testingID by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = { Text("Integrations", color = TextWhite) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                "Connect your POS",
                style = MaterialTheme.typography.titleLarge,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "When you tap Accept on a new order, it'll push to your POS so your kitchen printer fires automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange)
                }
            } else if (state.integrations.isEmpty()) {
                EmptyState()
            } else {
                state.integrations.forEach { integ ->
                    IntegrationCard(
                        integ = integ,
                        testResult = testResults[integ.id],
                        isTesting = testingID == integ.id,
                        onTest = {
                            testingID = integ.id
                            testResults[integ.id] = ""
                            scope.launch {
                                val result = viewModel.test(integ.id)
                                testResults[integ.id] = if (result == null) "OK — connection verified." else "Fail: $result"
                                testingID = null
                            }
                        },
                        onDisconnect = {
                            scope.launch { viewModel.disconnect(integ.id) }
                        },
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val url = viewModel.cloverConnectURL()
                        if (url != null) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // No browser installed; fall through, state.error will be set if URL fetch failed.
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = TextWhite),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect Clover", fontWeight = FontWeight.SemiBold)
            }

            state.error?.let {
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "Square and Toast support coming soon. If you use a different POS, let us know.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Print, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        Text("No POS connected yet", color = TextWhite, fontWeight = FontWeight.SemiBold)
        Text(
            "Connect Clover below to start auto-printing kitchen tickets.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun IntegrationCard(
    integ: POSIntegration,
    testResult: String?,
    isTesting: Boolean,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        integ.provider.replaceFirstChar { it.uppercase() },
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Merchant ${integ.merchantId}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    if (integ.isActive) "Active" else "Disconnected",
                    color = if (integ.isActive) TextWhite else TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(
                            if (integ.isActive) Orange else SurfaceDark,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Test", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (!testResult.isNullOrEmpty()) {
                Text(
                    testResult,
                    color = if (testResult.startsWith("OK")) SuccessGreen else ErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
