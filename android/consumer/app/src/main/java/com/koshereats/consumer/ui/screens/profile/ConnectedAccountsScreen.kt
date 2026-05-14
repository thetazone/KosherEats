package com.koshereats.consumer.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.data.models.LinkedProvider
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.LinkedProvidersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAccountsScreen(
    onBack: () -> Unit,
    vm: LinkedProvidersViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var unlinkConfirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(BackgroundBlack)) {
        TopAppBar(
            title = { Text("Connected Accounts", color = TextWhite) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
            return@Column
        }

        Text(
            "Sign in faster by connecting Apple, Google, or your phone number to this account.",
            color = TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column {
                val providers = state.providers
                if (providers.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No connected accounts yet.", color = TextTertiary)
                    }
                } else {
                    providers.forEachIndexed { index, provider ->
                        ProviderRow(
                            provider = provider,
                            onUnlink = { unlinkConfirm = provider.provider },
                        )
                        if (index < providers.lastIndex) HorizontalDivider(color = SurfaceDarkBorder)
                    }
                }
            }
        }

        state.error?.let { msg ->
            Text(
                msg,
                color = ErrorRed,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    unlinkConfirm?.let { provider ->
        AlertDialog(
            onDismissRequest = { unlinkConfirm = null },
            containerColor = SurfaceDark,
            title = { Text("Disconnect ${provider.replaceFirstChar { it.uppercase() }}?", color = TextWhite) },
            text = {
                Text(
                    "You won't be able to sign in with this method until you reconnect it.",
                    color = TextTertiary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.unlinkProvider(provider)
                    unlinkConfirm = null
                }) {
                    Text("Disconnect", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { unlinkConfirm = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun ProviderRow(provider: LinkedProvider, onUnlink: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (provider.provider == "phone") Icons.Filled.Phone else Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = Orange,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(provider.displayName, color = TextWhite, fontWeight = FontWeight.SemiBold)
                Text(
                    "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }
        TextButton(onClick = onUnlink) {
            Text("Disconnect", color = ErrorRed)
        }
    }
}
