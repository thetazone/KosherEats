package com.koshereats.seller.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.Restaurant
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.AuthViewModel

@Composable
fun RestaurantSettingsScreen(
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by authViewModel.state.collectAsState()
    val restaurant = authState.restaurant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
        )
        Text(
            text = "Restaurant configuration",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Restaurant Profile Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Orange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Restaurant,
                            contentDescription = null,
                            tint = Orange,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = restaurant?.name ?: "Your Restaurant",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (restaurant?.isOpen == true) SuccessGreen else ErrorRed
                                    ),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (restaurant?.isOpen == true) "Open" else "Closed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (restaurant?.isOpen == true) SuccessGreen else ErrorRed,
                            )
                        }
                    }
                }

                if (restaurant != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = DividerColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Rating
                    if (restaurant.rating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = Orange,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${restaurant.rating} (${restaurant.totalReviews} reviews)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Details Card
        if (restaurant != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Restaurant Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsRow(
                        icon = Icons.Filled.LocationOn,
                        label = "Address",
                        value = restaurant.address.ifBlank { "Not set" },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Phone,
                        label = "Phone",
                        value = restaurant.phone.ifBlank { "Not set" },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Email,
                        label = "Email",
                        value = restaurant.email.ifBlank { "Not set" },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.VerifiedUser,
                        label = "Kosher Certification",
                        value = restaurant.kosherCertification.name,
                    )
                    if (restaurant.certificationDetails.isNotBlank()) {
                        Text(
                            text = restaurant.certificationDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 42.dp, top = 4.dp),
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Schedule,
                        label = "Avg Prep Time",
                        value = "${restaurant.averagePrepTime} min",
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pricing card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Pricing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Delivery Fee", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(
                                restaurant.deliveryFee.formatPrice(),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Minimum Order", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(
                                restaurant.minimumOrder.formatPrice(),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Logout
        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ErrorRed.copy(alpha = 0.15f),
                contentColor = ErrorRed,
            ),
        ) {
            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out", fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Version
        Text(
            text = "KosherEats Seller v1.0.0",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = TextWhite,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    Divider(color = DividerColor.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 32.dp))
    Spacer(modifier = Modifier.height(12.dp))
}
