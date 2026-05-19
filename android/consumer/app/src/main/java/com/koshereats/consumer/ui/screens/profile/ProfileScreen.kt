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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.consumer.R
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onDeleteAccountClick: () -> Unit,
    onEditProfileClick: () -> Unit = {},
    onSavedAddressesClick: () -> Unit = {},
    onPaymentMethodsClick: () -> Unit = {},
    onFavoritesClick: () -> Unit = {},
    onNotificationPreferencesClick: () -> Unit = {},
    onConnectedAccountsClick: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshUser()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.tabs_profile),
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextWhite
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isRehydrating && !state.isLoggedIn) {
            // Auth state not yet determined — show spinner to avoid flashing the
            // guest CTA for a returning authenticated user on cold start.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Orange)
            }
        } else if (!state.isLoggedIn || state.isGuest) {
            // Not logged in or browsing as guest — show sign-in prompt
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
                    text = stringResource(R.string.profile_guest_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.profile_guest_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GuestBenefitRow(icon = Icons.Filled.LocalMall, text = stringResource(R.string.profile_benefit_track_orders))
                    GuestBenefitRow(icon = Icons.Filled.LocationOn, text = stringResource(R.string.profile_benefit_save_addresses))
                    GuestBenefitRow(icon = Icons.Filled.Security, text = stringResource(R.string.profile_benefit_secure_checkout))
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
                        stringResource(R.string.profile_sign_in_register),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (state.isRehydrating || state.user == null) {
            // Token present but profile fetch is in-flight or failed transiently.
            // Render a loading spinner or a retry prompt rather than accessing
            // a null user object.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundBlack),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isRehydrating) {
                    CircularProgressIndicator(color = Orange)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                        )
                        Button(
                            onClick = { viewModel.retryAuth() },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
                        }
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
                            title = stringResource(R.string.profile_menu_edit),
                            onClick = onEditProfileClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.LocationOn,
                            title = stringResource(R.string.profile_menu_saved_addresses),
                            onClick = onSavedAddressesClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.CreditCard,
                            title = stringResource(R.string.profile_menu_payment_methods),
                            onClick = onPaymentMethodsClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.Favorite,
                            title = stringResource(R.string.profile_menu_favorites),
                            onClick = onFavoritesClick,
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
                            title = stringResource(R.string.profile_menu_notifications),
                            onClick = onNotificationPreferencesClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.Filled.Link,
                            title = stringResource(R.string.profile_menu_connected_accounts),
                            onClick = onConnectedAccountsClick,
                        )
                        HorizontalDivider(color = SurfaceDarkBorder)
                        ProfileMenuItem(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            title = stringResource(R.string.profile_menu_help),
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
                        .clickable { showSignOutConfirm = true },
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
                            text = stringResource(R.string.profile_sign_out),
                            style = MaterialTheme.typography.titleMedium,
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Delete account
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { showDeleteConfirm = true },
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
                            Icons.Filled.DeleteForever,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.profile_delete_account),
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = { Text(stringResource(R.string.profile_delete_confirm_title), color = TextWhite) },
            text = {
                Text(
                    stringResource(R.string.profile_delete_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteAccountClick()
                }) {
                    Text(stringResource(R.string.profile_delete_btn), color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = TextSecondary)
                }
            },
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            containerColor = SurfaceDark,
            title = { Text(stringResource(R.string.profile_sign_out_confirm_title), color = TextWhite) },
            text = {
                Text(
                    stringResource(R.string.profile_sign_out_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOutClick()
                }) {
                    Text(stringResource(R.string.profile_sign_out), color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = TextSecondary)
                }
            },
        )
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
