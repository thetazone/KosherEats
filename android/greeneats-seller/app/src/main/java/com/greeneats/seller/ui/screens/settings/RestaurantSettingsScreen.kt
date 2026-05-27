package com.greeneats.seller.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.greeneats.seller.data.models.Restaurant
import com.greeneats.seller.data.models.formatPrice
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.DividerColor
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.SurfaceDarkElevated
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.AuthViewModel
import kotlinx.coroutines.Dispatchers

private object SettingsStrings {
    const val TITLE = "Settings"
    const val SUBTITLE = "Restaurant configuration"
    const val YOUR_RESTAURANT = "Your Restaurant"
    const val OPEN = "Open"
    const val CLOSED = "Closed"
    const val OPEN_A11Y = "Restaurant is closed. Double tap to open"
    const val CLOSE_A11Y = "Restaurant is open. Double tap to close"
    const val RESTAURANT_DETAILS = "Restaurant Details"
    const val ADDRESS_LABEL = "Address"
    const val PHONE_LABEL = "Phone"
    const val EMAIL_LABEL = "Email"
    const val KOSHER_CERTIFICATION = "Kosher Certification"
    const val NOT_SET = "Not set"
    const val KOSHER_CERTIFICATE = "Kosher Certificate"
    const val UPDATE_CERTIFICATE = "Update Certificate"
    const val UPLOAD_CERTIFICATE = "Upload certificate photo"
    const val UPLOAD_FAILED = "Failed to upload certificate. Please try again."
    const val PRICING = "Pricing"
    const val DELIVERY_FEE = "Delivery Fee"
    const val MINIMUM_ORDER = "Minimum Order"
    const val INTEGRATIONS = "Integrations"
    const val SIGN_OUT = "Sign Out"
    const val SIGN_OUT_CONFIRM = "Are you sure you want to sign out?"
    const val CANCEL = "Cancel"
    const val APP_VERSION = "GreenEats Seller v1.0.0"
    const val REVIEWS_FORMAT = "%s (%d reviews)"
}
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun RestaurantSettingsScreen(
    onLogout: () -> Unit,
    onIntegrations: () -> Unit = {},
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val restaurant = authState.restaurant
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploadingCertificate by remember { mutableStateOf(false) }
    var certificateUploadError by remember { mutableStateOf<String?>(null) }
    var showCertificate by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(SettingsStrings.SIGN_OUT, color = TextWhite) },
            text = { Text(SettingsStrings.SIGN_OUT_CONFIRM, color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text(SettingsStrings.SIGN_OUT, color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(SettingsStrings.CANCEL, color = TextWhite)
                }
            },
            containerColor = SurfaceDark,
        )
    }

    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUploadingCertificate = true
            certificateUploadError = null
            val url = uploadCertificateSettings(context, uri, authViewModel)
            if (url != null) {
                authViewModel.updateRestaurantField("kosher_certificate_url", url)
            } else {
                certificateUploadError = SettingsStrings.UPLOAD_FAILED
            }
            isUploadingCertificate = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = SettingsStrings.TITLE,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
        )
        Text(
            text = SettingsStrings.SUBTITLE,
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = restaurant?.name ?: SettingsStrings.YOUR_RESTAURANT,
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
                                text = if (restaurant?.isOpen == true) SettingsStrings.OPEN else SettingsStrings.CLOSED,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (restaurant?.isOpen == true) SuccessGreen else ErrorRed,
                            )
                        }
                    }
                    val toggleDesc = if (restaurant?.isOpen == true) {
                        SettingsStrings.CLOSE_A11Y
                    } else {
                        SettingsStrings.OPEN_A11Y
                    }
                    Switch(
                        checked = restaurant?.isOpen == true,
                        onCheckedChange = { authViewModel.toggleOpen(it) },
                        enabled = !authState.isTogglingOpen,
                        modifier = Modifier.semantics {
                            contentDescription = toggleDesc
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SuccessGreen,
                            checkedTrackColor = SuccessGreen.copy(alpha = 0.3f),
                        ),
                    )
                }

                if (restaurant != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
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
                                text = SettingsStrings.REVIEWS_FORMAT.format(restaurant.rating.toString(), restaurant.totalReviews),
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
                        text = SettingsStrings.RESTAURANT_DETAILS,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsRow(
                        icon = Icons.Filled.LocationOn,
                        label = SettingsStrings.ADDRESS_LABEL,
                        value = restaurant.address.ifBlank { SettingsStrings.NOT_SET },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Phone,
                        label = SettingsStrings.PHONE_LABEL,
                        value = restaurant.phone.ifBlank { SettingsStrings.NOT_SET },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.Email,
                        label = SettingsStrings.EMAIL_LABEL,
                        value = restaurant.email.ifBlank { SettingsStrings.NOT_SET },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Filled.VerifiedUser,
                        label = SettingsStrings.KOSHER_CERTIFICATION,
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
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Kosher Certificate Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = SettingsStrings.KOSHER_CERTIFICATE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (restaurant.kosherCertificateUrl.isNotBlank()) {
                        AsyncImage(
                            model = restaurant.kosherCertificateUrl,
                            contentDescription = SettingsStrings.KOSHER_CERTIFICATE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showCertificate = true },
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { certificatePicker.launch("image/*") },
                            enabled = !isUploadingCertificate,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                        ) {
                            if (isUploadingCertificate) {
                                CircularProgressIndicator(
                                    color = Orange,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(SettingsStrings.UPDATE_CERTIFICATE)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDarkElevated)
                                .clickable(enabled = !isUploadingCertificate) {
                                    certificateUploadError = null
                                    certificatePicker.launch("image/*")
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isUploadingCertificate) {
                                CircularProgressIndicator(
                                    color = Orange,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.UploadFile,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = SettingsStrings.UPLOAD_CERTIFICATE,
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }

                    certificateUploadError?.let { errorMsg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg,
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Certificate full-screen viewer
            if (showCertificate && restaurant.kosherCertificateUrl.isNotBlank()) {
                Dialog(
                    onDismissRequest = { showCertificate = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BackgroundBlack)
                            .clickable { showCertificate = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = restaurant.kosherCertificateUrl,
                            contentDescription = SettingsStrings.KOSHER_CERTIFICATE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
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
                        text = SettingsStrings.PRICING,
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
                            Text(SettingsStrings.DELIVERY_FEE, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(
                                restaurant.deliveryFee.formatPrice(),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(SettingsStrings.MINIMUM_ORDER, style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

        Spacer(modifier = Modifier.height(16.dp))

        // Integrations (POS — Clover etc.)
        OutlinedButton(
            onClick = onIntegrations,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(SettingsStrings.INTEGRATIONS, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Logout
        Button(
            onClick = { showLogoutConfirm = true },
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
            Text(SettingsStrings.SIGN_OUT, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Version
        Text(
            text = SettingsStrings.APP_VERSION,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private suspend fun uploadCertificateSettings(
    context: android.content.Context,
    uri: Uri,
    viewModel: AuthViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("restaurant/certificate", contentType)
            ?: return@withContext null

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use { if (it.isSuccessful) presignResponse.publicUrl else null }
    } catch (_: Exception) {
        null
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
    HorizontalDivider(color = DividerColor.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 32.dp))
    Spacer(modifier = Modifier.height(12.dp))
}
