package com.koshereats.seller.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import com.koshereats.seller.BuildConfig
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

@Composable
fun RestaurantSettingsScreen(
    onLogout: () -> Unit,
    onIntegrations: () -> Unit = {},
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val restaurant = authState.restaurant
    val isApproved = restaurant?.approvalStatus?.equals("approved", ignoreCase = true) == true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploadingCertificate by remember { mutableStateOf(false) }
    var showCertificate by remember { mutableStateOf(false) }

    LaunchedEffect(authState.updateFieldError) {
        if (authState.updateFieldError != null) {
            Toast.makeText(context, authState.updateFieldError, Toast.LENGTH_SHORT).show()
            authViewModel.clearUpdateFieldError()
        }
    }

    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUploadingCertificate = true
            val url = uploadCertificateSettings(context, uri, authViewModel)
            if (url != null) {
                authViewModel.updateRestaurantField("kosher_certificate_url", url)
            } else {
                Toast.makeText(context, "Certificate upload failed. Please try again.", Toast.LENGTH_SHORT).show()
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
                    Column(modifier = Modifier.weight(1f)) {
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
                    Switch(
                        checked = isApproved && restaurant?.isOpen == true,
                        onCheckedChange = { authViewModel.toggleOpen(it) },
                        enabled = isApproved && !authState.isTogglingOpen,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SuccessGreen,
                            checkedTrackColor = SuccessGreen.copy(alpha = 0.3f),
                        ),
                    )
                }

                if (!isApproved && restaurant != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pending approval — you'll be able to go live once the platform admin reviews your application.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
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
                        text = "Kosher Certificate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (restaurant.kosherCertificateUrl.isNotBlank()) {
                        AsyncImage(
                            model = restaurant.kosherCertificateUrl,
                            contentDescription = "Kosher certificate",
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
                            Text("Update Certificate")
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDarkElevated)
                                .clickable(enabled = !isUploadingCertificate) {
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
                                        text = "Upload certificate photo",
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
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
                            contentDescription = "Kosher certificate",
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
            Text("Integrations", fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            text = "KosherEats Seller v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private val certUploadClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private suspend fun uploadCertificateSettings(
    context: android.content.Context,
    uri: Uri,
    viewModel: AuthViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("restaurant/certificate", contentType)
            ?: return@withContext null

        val contentLength = context.contentResolver.openFileDescriptor(uri, "r")
            ?.use { it.statSize } ?: -1L

        val client = certUploadClient
        val requestBody = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.source()?.use { sink.writeAll(it) }
            }
        }
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(requestBody)
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
