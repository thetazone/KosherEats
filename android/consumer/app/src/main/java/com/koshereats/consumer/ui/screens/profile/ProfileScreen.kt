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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
                Text("Profile", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (!state.isLoggedIn) {
            // Not logged in
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(80.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sign in to your account",
                        color = TextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manage your orders, addresses, and more",
                        color = TextTertiary,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onLoginClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth(),
                    ) {
                        Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
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
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Orange.copy(alpha = 0.2f))
                                .border(2.dp, Orange, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            state.user?.profileImageUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Profile",
                                    modifier = Modifier.size(64.dp).clip(CircleShape),
                                )
                            } ?: Text(
                                text = "${state.user?.firstName?.firstOrNull() ?: ""}${state.user?.lastName?.firstOrNull() ?: ""}",
                                color = Orange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "${state.user?.firstName} ${state.user?.lastName}",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                text = state.user?.email ?: "",
                                color = TextTertiary,
                                fontSize = 14.sp,
                            )
                            if (!state.user?.phone.isNullOrBlank()) {
                                Text(
                                    text = state.user?.phone ?: "",
                                    color = TextTertiary,
                                    fontSize = 13.sp,
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
                            onClick = {},
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.LocationOn,
                            title = "Saved Addresses",
                            onClick = {},
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.CreditCard,
                            title = "Payment Methods",
                            onClick = {},
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.Favorite,
                            title = "Favorites",
                            onClick = {},
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
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App version
                Text(
                    text = "KosherEats v1.0.0",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
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
            color = TextWhite,
            fontSize = 15.sp,
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
