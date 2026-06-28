package com.koshereats.seller.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.koshereats.seller.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.seller.data.models.KosherCertification
import com.koshereats.seller.data.models.Restaurant
import com.koshereats.seller.data.util.Money
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }

    LaunchedEffect(authState.updateFieldError) {
        if (authState.updateFieldError != null) {
            Toast.makeText(context, authState.updateFieldError, Toast.LENGTH_SHORT).show()
            authViewModel.clearUpdateFieldError()
        }
    }

    // Open/Closed toggle failures land in authState.toggleError. The switch's
    // checked state is bound to restaurant.isOpen (left untouched by the
    // ViewModel on failure) so it reverts on its own; surface the error so the
    // failed toggle isn't silent.
    LaunchedEffect(authState.toggleError) {
        authState.toggleError?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            authViewModel.clearToggleError()
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

    // --- Editable form state, re-seeded whenever the loaded restaurant changes ---
    // Keyed off id so a restaurant switch (or first load) repopulates the fields,
    // but ordinary recompositions keep the seller's in-progress edits.
    val restaurantKey = restaurant?.id
    var name by remember(restaurantKey) { mutableStateOf(restaurant?.name.orEmpty()) }
    var description by remember(restaurantKey) { mutableStateOf(restaurant?.description.orEmpty()) }
    var phone by remember(restaurantKey) { mutableStateOf(restaurant?.phone.orEmpty()) }
    var email by remember(restaurantKey) { mutableStateOf(restaurant?.email.orEmpty()) }
    var street by remember(restaurantKey) { mutableStateOf(restaurant?.street.orEmpty()) }
    var city by remember(restaurantKey) { mutableStateOf(restaurant?.city.orEmpty()) }
    var state by remember(restaurantKey) { mutableStateOf(restaurant?.state.orEmpty()) }
    var zipCode by remember(restaurantKey) { mutableStateOf(restaurant?.zipCode.orEmpty()) }
    // Money fields are stored as integer cents; show dollars in the inputs.
    var deliveryFee by remember(restaurantKey) {
        mutableStateOf(centsToDollarString(restaurant?.deliveryFee ?: 0))
    }
    var minOrder by remember(restaurantKey) {
        mutableStateOf(centsToDollarString(restaurant?.minimumOrder ?: 0))
    }
    var estDeliveryMin by remember(restaurantKey) {
        mutableStateOf((restaurant?.averagePrepTime ?: 0).toString())
    }
    var estDeliveryMax by remember(restaurantKey) {
        mutableStateOf((restaurant?.estDeliveryMax ?: 0).toString())
    }
    var kosherCert by remember(restaurantKey) {
        mutableStateOf(restaurant?.kosherCertification ?: KosherCertification.OU)
    }
    var certifyingAgency by remember(restaurantKey) {
        mutableStateOf(restaurant?.certificationDetails.orEmpty())
    }
    // Who-delivers selector: restaurant = seller self-delivers; external = Uber
    // only. Platform courier mode is hidden while the KosherEats courier network
    // is shelved for launch.
    var deliveryMode by remember(restaurantKey) {
        mutableStateOf(normalizedDeliveryMode(restaurant?.deliveryMode))
    }
    var isSaving by remember { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedBorderColor = Orange,
        unfocusedBorderColor = DividerColor,
        cursorColor = Orange,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextMuted,
        focusedContainerColor = SurfaceDark,
        unfocusedContainerColor = SurfaceDark,
    )

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

        // Restaurant Info (editable)
        if (restaurant != null) {
            SettingsSectionCard(icon = Icons.Filled.Restaurant, title = "Restaurant Info") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Address (editable)
            SettingsSectionCard(icon = Icons.Filled.LocationOn, title = "Address") {
                OutlinedTextField(
                    value = street,
                    onValueChange = { street = it },
                    label = { Text("Street") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text("City") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.weight(0.6f),
                    )
                    OutlinedTextField(
                        value = state,
                        onValueChange = { state = it.uppercase().take(2) },
                        label = { Text("State") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(0.4f),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = zipCode,
                    onValueChange = { zipCode = it.filter { c -> c.isDigit() }.take(10) },
                    label = { Text("ZIP Code") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Kosher Certification (editable)
            SettingsSectionCard(icon = Icons.Filled.VerifiedUser, title = "Kosher Certification") {
                Text(
                    text = "Certification",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KosherCertification.entries
                        .filter { it != KosherCertification.UNKNOWN }
                        .forEach { cert ->
                            val selected = kosherCert == cert
                            Box(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (selected) Orange.copy(alpha = 0.15f)
                                        else SurfaceDarkElevated,
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) Orange else SurfaceDarkElevated,
                                        shape = RoundedCornerShape(18.dp),
                                    )
                                    .clickable { kosherCert = cert }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = cert.displayName,
                                    color = if (selected) Orange else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = certifyingAgency,
                    onValueChange = { certifyingAgency = it },
                    label = { Text("Certifying Agency") },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
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

            // Delivery / Pricing (editable)
            SettingsSectionCard(icon = Icons.Filled.AttachMoney, title = "Delivery") {
                // Who delivers — own driver or Uber Direct.
                // Mirrors iOS "Who delivers", including the dynamic caption per mode.
                Text(
                    text = "Who delivers",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDarkElevated)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DeliveryModeSegment(
                        label = "Self-delivery",
                        selected = deliveryMode == "restaurant",
                        modifier = Modifier.weight(1f),
                        onClick = { deliveryMode = "restaurant" },
                    )
                    DeliveryModeSegment(
                        label = "Uber Direct",
                        selected = deliveryMode == "external",
                        modifier = Modifier.weight(1f),
                        onClick = { deliveryMode = "external" },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = deliveryModeHelpText(deliveryMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = deliveryFee,
                        onValueChange = { deliveryFee = sanitizeDecimal(it) },
                        label = { Text("Delivery Fee ($)") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minOrder,
                        onValueChange = { minOrder = sanitizeDecimal(it) },
                        label = { Text("Min Order ($)") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = estDeliveryMin,
                        onValueChange = { estDeliveryMin = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Est. Min (min)") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = estDeliveryMax,
                        onValueChange = { estDeliveryMax = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Est. Max (min)") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save Changes
            Button(
                onClick = {
                    val validationError = validateSettings(
                        name = name,
                        email = email,
                        zipCode = zipCode,
                        stateAbbr = state,
                        estMin = estDeliveryMin,
                        estMax = estDeliveryMax,
                    )
                    if (validationError != null) {
                        Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val changes = buildRestaurantChanges(
                        restaurant = restaurant,
                        name = name,
                        description = description,
                        phone = phone,
                        email = email,
                        street = street,
                        city = city,
                        stateAbbr = state,
                        zipCode = zipCode,
                        deliveryFeeDollars = deliveryFee,
                        minOrderDollars = minOrder,
                        estDeliveryMin = estDeliveryMin,
                        estDeliveryMax = estDeliveryMax,
                        kosherCert = kosherCert,
                        certifyingAgency = certifyingAgency,
                        deliveryMode = deliveryMode,
                    )
                    if (changes.isEmpty()) {
                        Toast.makeText(context, "No changes to save", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    // Persist all changed fields in a single batched PUT and await the
                    // result, so the button stays disabled (blocking double-taps) and we
                    // only confirm success once the PUT actually lands. Failures surface
                    // via the updateFieldError Toast wired in LaunchedEffect above.
                    scope.launch {
                        val ok = authViewModel.updateRestaurantFields(changes)
                        isSaving = false
                        if (ok) {
                            Toast.makeText(context, "Changes saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = TextWhite,
                    disabledContainerColor = Orange.copy(alpha = 0.3f),
                    disabledContentColor = TextWhite.copy(alpha = 0.6f),
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = TextWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save Changes", fontWeight = FontWeight.SemiBold)
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

        // Legal & Support — mirrors iOS (App Store guideline 5.1.1) and keeps
        // Play's Data safety / policy reviews satisfied with in-app links to the
        // published policies and a support contact. External URLs open in the
        // browser / mail app (no in-app webview); failures fall back to a Toast.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column {
                LegalLinkRow(
                    icon = Icons.Filled.Shield,
                    label = "Privacy Policy",
                ) { openExternalUri(context, Uri.parse("https://koshereats.com/privacy")) }
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                LegalLinkRow(
                    icon = Icons.Filled.Description,
                    label = "Terms of Service",
                ) { openExternalUri(context, Uri.parse("https://koshereats.com/terms")) }
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                LegalLinkRow(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    label = "Help & Support",
                ) {
                    openExternalUri(
                        context = context,
                        uri = Uri.parse("mailto:sellers@koshereats.com"),
                        action = Intent.ACTION_SENDTO,
                    )
                }
            }
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

        Spacer(modifier = Modifier.height(12.dp))

        // Delete Account — required by Google Play's User Data deletion policy.
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            enabled = !isDeletingAccount,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
        ) {
            if (isDeletingAccount) {
                CircularProgressIndicator(
                    color = ErrorRed,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Delete Account", fontWeight = FontWeight.SemiBold)
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isDeletingAccount) showDeleteConfirm = false },
                title = { Text("Delete Account", color = TextWhite) },
                text = {
                    Text(
                        "This will permanently delete your account and all associated data. " +
                            "This action cannot be undone.",
                        color = TextMuted,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !isDeletingAccount,
                        onClick = {
                            isDeletingAccount = true
                            scope.launch {
                                val deleted = deleteAccountRequest()
                                isDeletingAccount = false
                                showDeleteConfirm = false
                                if (deleted) {
                                    // Clears local auth and routes back to login.
                                    onLogout()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Couldn't delete your account. Please try again.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Text("Delete", color = ErrorRed)
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isDeletingAccount,
                        onClick = { showDeleteConfirm = false },
                    ) {
                        Text("Cancel", color = TextWhite)
                    }
                },
                containerColor = SurfaceDark,
            )
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
private fun SettingsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun LegalLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextWhite,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Opens a legal/support URI in the appropriate external app (browser for https,
 * mail client for mailto), mirroring the ACTION_VIEW / ACTION_SENDTO pattern used
 * elsewhere in the app. Shows a Toast if no handling app is installed.
 */
private fun openExternalUri(
    context: android.content.Context,
    uri: Uri,
    action: String = Intent.ACTION_VIEW,
) {
    try {
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to open this link.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * The exact JSON value the backend stores / the Restaurant model decodes via @Json. Matches
 * each enum's displayName except OTHER, whose wire value is the lowercase "other".
 */
private fun kosherCertWireName(cert: KosherCertification): String =
    if (cert == KosherCertification.OTHER) "other" else cert.displayName

/** Formats integer cents as a plain dollar string (e.g. 599 -> "5.99") for text inputs. */
private fun centsToDollarString(cents: Int): String =
    if (cents == 0) "" else String.format(java.util.Locale.US, "%.2f", cents / 100.0)

/** Dollars in the inputs -> integer cents on the wire (delivery_fee / min_order are INTEGER cents). */
private fun dollarStringToCents(dollars: String): Int = Money.parseCents(dollars) ?: 0

/** Keeps digits and a single decimal point so dollar fields stay parseable. */
private fun sanitizeDecimal(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered
    val head = filtered.substring(0, firstDot + 1)
    val tail = filtered.substring(firstDot + 1).replace(".", "")
    return head + tail.take(2)
}

/** Returns a user-facing error string if the form is invalid, or null when it passes. */
private fun validateSettings(
    name: String,
    email: String,
    zipCode: String,
    stateAbbr: String,
    estMin: String,
    estMax: String,
): String? {
    if (name.trim().isEmpty()) return "Restaurant name is required."
    if (name.trim().length > 200) return "Restaurant name must be 200 characters or fewer."
    val trimmedEmail = email.trim()
    if (trimmedEmail.isNotEmpty() && (!trimmedEmail.contains("@") || !trimmedEmail.contains("."))) {
        return "Please enter a valid email address."
    }
    if (zipCode.trim().isEmpty()) return "ZIP code is required."
    val trimmedState = stateAbbr.trim()
    if (trimmedState.isNotEmpty() && trimmedState.length != 2) {
        return "State abbreviation must be exactly 2 characters (e.g. NY)."
    }
    val min = estMin.trim().toIntOrNull()
    val max = estMax.trim().toIntOrNull()
    if (min != null && max != null && min > max) {
        return "Estimated minimum delivery time can't exceed maximum."
    }
    return null
}

/**
 * Builds a map of only the fields that differ from the loaded restaurant, keyed by the
 * backend JSON names the seller `PUT /seller/restaurant` handler honors. The handler uses
 * COALESCE per column, so sending only changed fields is a safe partial update.
 */
/**
 * One segment of the "Who delivers" toggle. Selected = filled Orange pill;
 * unselected = transparent over the row's elevated background. Avoids the
 * experimental SegmentedButton API for cross-version safety.
 */
@Composable
private fun DeliveryModeSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.background(Orange) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TextWhite else TextSecondary,
            maxLines = 1,
        )
    }
}

private fun buildRestaurantChanges(
    restaurant: Restaurant,
    name: String,
    description: String,
    phone: String,
    email: String,
    street: String,
    city: String,
    stateAbbr: String,
    zipCode: String,
    deliveryFeeDollars: String,
    minOrderDollars: String,
    estDeliveryMin: String,
    estDeliveryMax: String,
    kosherCert: KosherCertification,
    certifyingAgency: String,
    deliveryMode: String,
): Map<String, Any> {
    val changes = mutableMapOf<String, Any>()
    if (name.trim() != restaurant.name) changes["name"] = name.trim()
    if (description != restaurant.description) changes["description"] = description
    if (phone.trim() != restaurant.phone) changes["phone"] = phone.trim()
    if (email.trim() != restaurant.email) changes["email"] = email.trim()
    if (street.trim() != restaurant.street) changes["street"] = street.trim()
    if (city.trim() != restaurant.city) changes["city"] = city.trim()
    if (stateAbbr.trim() != restaurant.state) changes["state"] = stateAbbr.trim()
    if (zipCode.trim() != restaurant.zipCode) changes["zip_code"] = zipCode.trim()

    val feeCents = dollarStringToCents(deliveryFeeDollars)
    if (feeCents != restaurant.deliveryFee) changes["delivery_fee"] = feeCents
    val minCents = dollarStringToCents(minOrderDollars)
    if (minCents != restaurant.minimumOrder) changes["min_order"] = minCents

    estDeliveryMin.trim().toIntOrNull()?.let {
        if (it != restaurant.averagePrepTime) changes["est_delivery_min"] = it
    }
    estDeliveryMax.trim().toIntOrNull()?.let {
        if (it != restaurant.estDeliveryMax) changes["est_delivery_max"] = it
    }

    if (kosherCert != restaurant.kosherCertification && kosherCert != KosherCertification.UNKNOWN) {
        changes["kosher_certification"] = kosherCertWireName(kosherCert)
    }
    if (certifyingAgency != restaurant.certificationDetails) {
        changes["certifying_agency"] = certifyingAgency
    }
    if (deliveryMode != restaurant.deliveryMode) {
        changes["delivery_mode"] = deliveryMode
    }
    return changes
}

private fun normalizedDeliveryMode(mode: String?): String = when (mode) {
    "restaurant", "external" -> mode
    else -> "external"
}

private fun deliveryModeHelpText(mode: String): String = when (mode) {
    "restaurant" ->
        "Your own driver handles pickup and delivery. You keep 50% of the delivery fee and can still send an order to Uber if you get slammed."
    else ->
        "Orders auto-dispatch to Uber Direct the moment you tap Ready."
}

/**
 * Authenticated DELETE of the signed-in seller's account. Hits the same endpoint iOS uses
 * (`DELETE {BASE_URL}user/account`) with the cached bearer token. Returns true on 2xx.
 * Kept self-contained (raw OkHttp, mirroring the certificate-upload helper) so it works
 * without changing the shared ApiService; on success the caller clears local auth via onLogout.
 */
private suspend fun deleteAccountRequest(): Boolean = withContext(Dispatchers.IO) {
    try {
        val token = com.koshereats.seller.data.api.NetworkModule.cachedToken
            ?: return@withContext false
        val request = Request.Builder()
            .url(BuildConfig.BASE_URL + "user/account")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        certUploadClient.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}
