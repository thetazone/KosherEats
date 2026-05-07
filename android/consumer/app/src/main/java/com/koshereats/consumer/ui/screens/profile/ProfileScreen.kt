package com.koshereats.consumer.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLoginClick: () -> Unit,
    onEditProfileClick: () -> Unit = {},
    onSavedAddressesClick: () -> Unit = {},
    onPaymentMethodsClick: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text(
                    "Profile", 
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextWhite
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (!state.isLoggedIn || state.isGuest) {
            // Not logged in or browsing as guest
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Orange.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = Orange,
                        modifier = Modifier.size(64.dp),
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "Unlock your full experience",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Sign in to track orders, save addresses, and earn rewards on every kosher meal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GuestBenefitRow(icon = Icons.Filled.LocalMall, text = "Track your orders in real-time")
                    GuestBenefitRow(icon = Icons.Filled.LocationOn, text = "Save multiple delivery addresses")
                    GuestBenefitRow(icon = Icons.Filled.Security, text = "Secure and fast checkout")
                }
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = onLoginClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        "Sign In or Register", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // User info header
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Orange.copy(alpha = 0.2f))
                                .border(2.dp, Orange, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            state.user?.profileImageUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Profile",
                                    modifier = Modifier.size(72.dp).clip(CircleShape),
                                )
                            } ?: Text(
                                text = "${state.user?.firstName?.firstOrNull() ?: ""}${state.user?.lastName?.firstOrNull() ?: ""}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Orange,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "${state.user?.firstName} ${state.user?.lastName}",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.user?.email ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary,
                            )
                            if (!state.user?.phone.isNullOrBlank()) {
                                Text(
                                    text = state.user?.phone ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                )
                            }
                        }
                    }
                }

                // Settings sections
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column {
                        ProfileMenuItem(
                            icon = Icons.Filled.Person,
                            title = "Edit Profile",
                            onClick = onEditProfileClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.LocationOn,
                            title = "Saved Addresses",
                            onClick = onSavedAddressesClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.CreditCard,
                            title = "Payment Methods",
                            onClick = onPaymentMethodsClick,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column {
                        ProfileMenuItem(
                            icon = Icons.Filled.Notifications,
                            title = "Notifications",
                            onClick = {},
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.Settings,
                            title = "Settings",
                            onClick = {},
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            title = "Help & Support",
                            onClick = {},
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Logout
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { viewModel.logout() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Sign Out",
                            style = MaterialTheme.typography.titleMedium,
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App version
                Text(
                    text = "KosherEats v1.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun GuestBenefitRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = OrangeLight,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextWhite,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}
