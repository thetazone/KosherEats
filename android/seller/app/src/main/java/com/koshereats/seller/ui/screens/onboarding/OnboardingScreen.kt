package com.koshereats.seller.ui.screens.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.KosherCertification
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusReady
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.OnboardingMenuItem
import com.koshereats.seller.ui.viewmodels.OnboardingStep
import com.koshereats.seller.ui.viewmodels.OnboardingViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.isComplete) {
        SubmittedScreen(onContinue = onComplete)
        return
    }

    val stepIndex = OnboardingStep.entries.indexOf(state.step)
    val progress = (stepIndex + 1) / OnboardingStep.entries.size.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = when (state.step) {
                        OnboardingStep.BASICS -> "Restaurant Details"
                        OnboardingStep.ADDRESS -> "Address"
                        OnboardingStep.KOSHER -> "Kosher Certification"
                        OnboardingStep.MENU -> "Menu Items"
                        OnboardingStep.REVIEW -> "Review & Submit"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                )
            },
            navigationIcon = {
                if (state.step != OnboardingStep.BASICS) {
                    IconButton(onClick = { viewModel.previousStep() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            color = Orange,
            trackColor = SurfaceDark,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Step ${stepIndex + 1} of ${OnboardingStep.entries.size}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enter = slideInHorizontally { if (forward) it else -it } + fadeIn()
                val exit = slideOutHorizontally { if (forward) -it else it } + fadeOut()
                enter togetherWith exit
            },
            label = "onboarding_step",
        ) { step ->
            when (step) {
                OnboardingStep.BASICS -> BasicsStep(state, viewModel)
                OnboardingStep.ADDRESS -> AddressStep(state, viewModel)
                OnboardingStep.KOSHER -> KosherStep(state, viewModel)
                OnboardingStep.MENU -> MenuStep(state, viewModel)
                OnboardingStep.REVIEW -> ReviewStep(state, viewModel)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = DividerColor,
    focusedLabelColor = Orange,
    unfocusedLabelColor = TextMuted,
    cursorColor = Orange,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)

// ─── Step 1: Basics ──────────────────────────────────────

@Composable
private fun BasicsStep(
    state: com.koshereats.seller.ui.viewmodels.OnboardingState,
    viewModel: OnboardingViewModel,
) {
    var name by remember { mutableStateOf(state.restaurantName) }
    var description by remember { mutableStateOf(state.description) }
    var phone by remember { mutableStateOf(state.phone) }
    var email by remember { mutableStateOf(state.email) }
    var logoUrl by remember { mutableStateOf(state.logoUrl) }
    var localLogoUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingLogo by remember { mutableStateOf(false) }
    var logoUploadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = fieldColors()

    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        localLogoUri = uri
        logoUploadError = null
        isUploadingLogo = true
        scope.launch {
            val result = uploadRestaurantLogo(context, uri, viewModel)
            if (result != null) {
                logoUrl = result
            } else {
                logoUploadError = "Upload failed. Tap to retry."
            }
            isUploadingLogo = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            "Restaurant Logo",
            style = MaterialTheme.typography.titleSmall,
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Shown to customers in the marketplace and on your restaurant page",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .border(1.dp, DividerColor, CircleShape)
                    .background(SurfaceDark)
                    .clickable(enabled = !isUploadingLogo) { logoPicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                val displayModel = localLogoUri ?: logoUrl.ifBlank { null }
                if (isUploadingLogo && displayModel != null) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = displayModel,
                            contentDescription = "Restaurant logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CircularProgressIndicator(
                            color = Orange,
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center),
                        )
                    }
                } else if (isUploadingLogo) {
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(28.dp))
                } else if (displayModel != null) {
                    AsyncImage(
                        model = displayModel,
                        contentDescription = "Restaurant logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = "Add logo",
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Add logo", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        logoUploadError?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 200) name = it },
            label = { Text("Restaurant Name") },
            singleLine = true,
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = description,
            onValueChange = { if (it.length <= 2000) description = it },
            label = { Text("Description (optional)") },
            minLines = 2,
            maxLines = 4,
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                if (name.isBlank()) {
                    viewModel.setError("Restaurant name is required")
                    return@Button
                }
                if (phone.isBlank()) {
                    viewModel.setError("Phone is required")
                    return@Button
                }
                if (email.isBlank() || !email.contains("@")) {
                    viewModel.setError("Valid email is required")
                    return@Button
                }
                if (isUploadingLogo) {
                    viewModel.setError("Logo is still uploading…")
                    return@Button
                }
                viewModel.updateBasics(name, description, logoUrl, phone, email)
                viewModel.nextStep()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
            ),
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Step 2: Address ──────────────────────────────────────

@Composable
private fun AddressStep(
    state: com.koshereats.seller.ui.viewmodels.OnboardingState,
    viewModel: OnboardingViewModel,
) {
    var street by remember { mutableStateOf(state.street) }
    var city by remember { mutableStateOf(state.city) }
    var stateVal by remember { mutableStateOf(state.state) }
    var zipCode by remember { mutableStateOf(state.zipCode) }
    val colors = fieldColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = street,
            onValueChange = { street = it },
            label = { Text("Street Address") },
            singleLine = true,
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text("City") },
            singleLine = true,
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = stateVal,
                onValueChange = { if (it.length <= 2) stateVal = it.uppercase() },
                label = { Text("State") },
                singleLine = true,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = zipCode,
                onValueChange = { if (it.length <= 10) zipCode = it },
                label = { Text("Zip Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
        }

        state.error?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                if (street.isBlank() || city.isBlank() || stateVal.isBlank() || zipCode.isBlank()) {
                    viewModel.setError("All address fields are required")
                    return@Button
                }
                viewModel.updateAddress(street, city, stateVal, zipCode)
                viewModel.nextStep()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
            ),
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Step 3: Kosher Details ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KosherStep(
    state: com.koshereats.seller.ui.viewmodels.OnboardingState,
    viewModel: OnboardingViewModel,
) {
    var certification by remember { mutableStateOf(state.certification) }
    var agency by remember { mutableStateOf(state.certifyingAgency) }
    var cholovYisroel by remember { mutableStateOf(state.isCholovYisroel) }
    var pasYisroel by remember { mutableStateOf(state.isPasYisroel) }
    var glattKosher by remember { mutableStateOf(state.isGlattKosher) }
    var certExpanded by remember { mutableStateOf(false) }
    var certificateUrl by remember { mutableStateOf(state.kosherCertificateUrl) }
    var localImageUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingCert by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = fieldColors()

    val certPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        localImageUri = uri
        uploadError = null
        isUploadingCert = true
        scope.launch {
            val result = uploadCertificate(context, uri, viewModel)
            if (result != null) {
                certificateUrl = result
            } else {
                uploadError = "Upload failed. Tap to retry."
            }
            isUploadingCert = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = certExpanded,
            onExpandedChange = { certExpanded = it },
        ) {
            OutlinedTextField(
                value = certification.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Kosher Certification") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = certExpanded) },
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = certExpanded,
                onDismissRequest = { certExpanded = false },
                modifier = Modifier.background(SurfaceDark),
            ) {
                KosherCertification.entries.forEach { cert ->
                    DropdownMenuItem(
                        text = { Text(cert.name, color = TextWhite) },
                        onClick = {
                            certification = cert
                            certExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = agency,
            onValueChange = { agency = it },
            label = { Text("Certifying Agency (optional)") },
            singleLine = true,
            colors = colors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Kosher Certificate Photo",
            style = MaterialTheme.typography.titleSmall,
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Upload a clear, well-lit photo of your current kosher certificate",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .clickable(enabled = !isUploadingCert) { certPicker.launch("image/*") },
            contentAlignment = Alignment.Center,
        ) {
            val displayModel = localImageUri ?: certificateUrl.ifBlank { null }
            if (isUploadingCert && displayModel != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = displayModel,
                        contentDescription = "Kosher certificate",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CircularProgressIndicator(
                        color = Orange,
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center),
                    )
                }
            } else if (isUploadingCert) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Uploading...", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            } else if (displayModel != null) {
                AsyncImage(
                    model = displayModel,
                    contentDescription = "Kosher certificate",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (uploadError != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uploadError!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.AddAPhoto,
                        contentDescription = "Upload certificate",
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Tap to upload certificate", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            "Additional Certifications",
            style = MaterialTheme.typography.titleSmall,
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        LabeledCheckbox("Cholov Yisroel", cholovYisroel) { cholovYisroel = it }
        LabeledCheckbox("Pas Yisroel", pasYisroel) { pasYisroel = it }
        LabeledCheckbox("Glatt Kosher", glattKosher) { glattKosher = it }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                viewModel.updateKosher(certification, agency, cholovYisroel, pasYisroel, glattKosher, certificateUrl)
                viewModel.nextStep()
            },
            enabled = !isUploadingCert,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
                disabledContainerColor = Orange.copy(alpha = 0.4f),
            ),
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Orange,
                uncheckedColor = TextMuted,
                checkmarkColor = TextWhite,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextWhite)
    }
}

// ─── Step 4: Menu Builder ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuStep(
    state: com.koshereats.seller.ui.viewmodels.OnboardingState,
    viewModel: OnboardingViewModel,
) {
    var showForm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            "Add your menu items so they're ready when you launch.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        state.menuItems.forEachIndexed { index, item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, color = TextWhite, fontWeight = FontWeight.Medium)
                        Text(
                            formatItemPrice(item.priceDollars) + " · " +
                                item.category.name.lowercase()
                                    .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        val kosherLabel = when {
                            item.isMeat -> "Meat"
                            item.isDairy -> "Dairy"
                            item.isPareve -> "Pareve"
                            else -> ""
                        }
                        if (kosherLabel.isNotEmpty()) {
                            Text(
                                kosherLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = Orange,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.removeMenuItem(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = ErrorRed)
                    }
                }
            }
        }

        if (showForm) {
            AddMenuItemForm(
                onAdd = { item ->
                    viewModel.addMenuItem(item)
                    showForm = false
                },
                onCancel = { showForm = false },
                viewModel = viewModel,
            )
        } else {
            OutlinedButton(
                onClick = { showForm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Menu Item")
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { viewModel.nextStep() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
            ),
        ) {
            Text("Continue to Review", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMenuItemForm(
    onAdd: (OnboardingMenuItem) -> Unit,
    onCancel: () -> Unit,
    viewModel: OnboardingViewModel,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MenuCategory.MAINS) }
    var isMeat by remember { mutableStateOf(false) }
    var isDairy by remember { mutableStateOf(false) }
    var isPareve by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var imageUrl by remember { mutableStateOf("") }
    var localImageUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingImage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = fieldColors()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        localImageUri = uri
        isUploadingImage = true
        scope.launch {
            val result = uploadMenuItemImage(context, uri, viewModel)
            if (result != null) {
                imageUrl = result
            } else {
                error = "Image upload failed. You can still add the item."
            }
            isUploadingImage = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "New Menu Item",
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
                    .background(BackgroundBlack)
                    .clickable(enabled = !isUploadingImage) { imagePicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                val displayModel = localImageUri ?: imageUrl.ifBlank { null }
                if (isUploadingImage && displayModel != null) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = displayModel,
                            contentDescription = "Menu item photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CircularProgressIndicator(
                            color = Orange,
                            modifier = Modifier.size(28.dp).align(Alignment.Center),
                        )
                    }
                } else if (displayModel != null) {
                    AsyncImage(
                        model = displayModel,
                        contentDescription = "Menu item photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = "Add photo",
                            tint = TextMuted,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Add photo", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text("Item Name") },
                singleLine = true,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                maxLines = 2,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { v ->
                        val filtered = v.filter { c -> c.isDigit() || c == '.' }
                        if (filtered.count { it == '.' } <= 1) price = filtered
                    },
                    label = { Text("Price ($)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = colors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )

                ExposedDropdownMenuBox(
                    expanded = catExpanded,
                    onExpandedChange = { catExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = category.name.lowercase().replace('_', ' ')
                            .replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        colors = colors,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = catExpanded,
                        onDismissRequest = { catExpanded = false },
                        modifier = Modifier.background(SurfaceDark),
                    ) {
                        MenuCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        cat.name.lowercase().replace('_', ' ')
                                            .replaceFirstChar { it.uppercase() },
                                        color = TextWhite,
                                    )
                                },
                                onClick = {
                                    category = cat
                                    catExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Text(
                "Kosher Type",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KosherChip("Meat", isMeat) {
                    isMeat = it; if (it) { isDairy = false; isPareve = false }
                }
                KosherChip("Dairy", isDairy) {
                    isDairy = it; if (it) { isMeat = false; isPareve = false }
                }
                KosherChip("Pareve", isPareve) {
                    isPareve = it; if (it) { isMeat = false; isDairy = false }
                }
            }

            error?.let {
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (name.isBlank()) { error = "Name is required"; return@Button }
                        val dollars = price.toDoubleOrNull() ?: 0.0
                        if (dollars <= 0) { error = "Enter a valid price"; return@Button }
                        if (!isMeat && !isDairy && !isPareve) {
                            error = "Select a kosher type"
                            return@Button
                        }
                        onAdd(
                            OnboardingMenuItem(
                                name = name, description = description,
                                priceDollars = price, category = category,
                                isMeat = isMeat, isDairy = isDairy, isPareve = isPareve,
                                imageUrl = imageUrl,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange,
                        contentColor = TextWhite,
                    ),
                ) {
                    Text("Add", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun KosherChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Orange,
                uncheckedColor = TextMuted,
                checkmarkColor = TextWhite,
            ),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextWhite)
    }
}

// ─── Step 5: Review ──────────────────────────────────────

@Composable
private fun ReviewStep(
    state: com.koshereats.seller.ui.viewmodels.OnboardingState,
    viewModel: OnboardingViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        ReviewSection("Restaurant") {
            ReviewRow("Name", state.restaurantName)
            if (state.description.isNotBlank()) ReviewRow("Description", state.description)
            ReviewRow("Phone", state.phone)
            ReviewRow("Email", state.email)
        }

        ReviewSection("Address") {
            ReviewRow("Street", state.street)
            ReviewRow("City", state.city)
            ReviewRow("State", state.state)
            ReviewRow("Zip Code", state.zipCode)
        }

        ReviewSection("Kosher") {
            ReviewRow("Certification", state.certification.name)
            if (state.certifyingAgency.isNotBlank()) ReviewRow("Agency", state.certifyingAgency)
            if (state.isCholovYisroel) ReviewRow("", "Cholov Yisroel")
            if (state.isPasYisroel) ReviewRow("", "Pas Yisroel")
            if (state.isGlattKosher) ReviewRow("", "Glatt Kosher")
        }

        ReviewSection("Menu (${state.menuItems.size} items)") {
            if (state.menuItems.isEmpty()) {
                Text(
                    "No menu items added. You can add them later from the Menu tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            state.menuItems.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(item.name, color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatItemPrice(item.priceDollars),
                        color = Orange,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        state.error?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { viewModel.submit() },
            enabled = !state.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
                disabledContainerColor = Orange.copy(alpha = 0.4f),
            ),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    color = TextWhite,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text("Submit for Review", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReviewSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Orange,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    if (label.isEmpty()) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = StatusReady,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(value, color = TextWhite, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                color = TextWhite,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ─── Submitted Screen ────────────────────────────────────

@Composable
private fun SubmittedScreen(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Orange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(40.dp),
                )
            }

            Text(
                "You're all set!",
                style = MaterialTheme.typography.headlineSmall,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
            )

            Text(
                "Your restaurant has been submitted for review. We'll notify you once it's approved and ready to go live.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = TextWhite,
                ),
            ) {
                Text("Go to Dashboard", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun formatItemPrice(dollars: String): String {
    val d = dollars.toDoubleOrNull() ?: return "$0.00"
    return String.format(Locale.US, "$%.2f", d)
}

private suspend fun uploadCertificate(
    context: android.content.Context,
    uri: Uri,
    viewModel: OnboardingViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        android.util.Log.d("CertUpload", "presigning kind=restaurant/certificate ct=$contentType")
        val presignResponse = viewModel.presignUpload("restaurant/certificate", contentType)
        if (presignResponse == null) {
            android.util.Log.e("CertUpload", "presign returned null")
            return@withContext null
        }
        android.util.Log.d("CertUpload", "uploadUrl=${presignResponse.uploadUrl}")
        android.util.Log.d("CertUpload", "publicUrl=${presignResponse.publicUrl}")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            android.util.Log.e("CertUpload", "failed to read bytes from uri")
            return@withContext null
        }
        android.util.Log.d("CertUpload", "uploading ${bytes.size} bytes")
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                android.util.Log.d("CertUpload", "upload OK, returning publicUrl")
                presignResponse.publicUrl
            } else {
                android.util.Log.e("CertUpload", "upload failed: ${it.code} ${it.message}")
                null
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("CertUpload", "exception: ${e.javaClass.name} ${e.message}", e)
        null
    }
}

private suspend fun uploadRestaurantLogo(
    context: android.content.Context,
    uri: Uri,
    viewModel: OnboardingViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("restaurant/logo", contentType) ?: return@withContext null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) presignResponse.publicUrl else null
        }
    } catch (e: Exception) {
        android.util.Log.e("LogoUpload", "exception: ${e.javaClass.name} ${e.message}", e)
        null
    }
}

private suspend fun uploadMenuItemImage(
    context: android.content.Context,
    uri: Uri,
    viewModel: OnboardingViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        android.util.Log.d("MenuItemUpload", "presigning kind=menu_item ct=$contentType")
        val presignResponse = viewModel.presignUpload("menu_item", contentType)
        if (presignResponse == null) {
            android.util.Log.e("MenuItemUpload", "presign returned null")
            return@withContext null
        }
        android.util.Log.d("MenuItemUpload", "uploadUrl=${presignResponse.uploadUrl}")
        android.util.Log.d("MenuItemUpload", "publicUrl=${presignResponse.publicUrl}")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            android.util.Log.e("MenuItemUpload", "failed to read bytes from uri")
            return@withContext null
        }
        android.util.Log.d("MenuItemUpload", "uploading ${bytes.size} bytes")
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                android.util.Log.d("MenuItemUpload", "upload OK, returning publicUrl")
                presignResponse.publicUrl
            } else {
                android.util.Log.e("MenuItemUpload", "upload failed: ${it.code} ${it.message}")
                null
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MenuItemUpload", "exception: ${e.javaClass.name} ${e.message}", e)
        null
    }
}
