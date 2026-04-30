package com.koshereats.courier.ui.screens.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.courier.data.models.CourierProfile
import com.koshereats.courier.data.models.OnboardingStatus
import com.koshereats.courier.data.models.VehicleType
import com.koshereats.courier.services.UploadService
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.ErrorRed
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SuccessGreen
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.TextMuted
import com.koshereats.courier.ui.theme.TextSecondary
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.OnboardingViewModel
import kotlinx.coroutines.launch

/**
 * OnboardingFlowScreen is the multi-step signup funnel. Which step is shown
 * is decided entirely by the server-side CourierProfile (so the user can
 * kill the app and resume at the right step), mirroring iOS.
 */
@Composable
fun OnboardingFlowScreen(
    profile: CourierProfile,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier.fillMaxSize().background(BackgroundBlack),
    ) {
        ProgressHeader(profile)

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !profile.phoneVerified -> PhoneVerifyStep(vm, onRefresh)
                profile.onboardingStatus == OnboardingStatus.PENDING_INFO -> VehicleStep(vm, onRefresh)
                profile.onboardingStatus == OnboardingStatus.PENDING_DOCUMENTS -> DocumentsStep(vm, onRefresh)
                else -> BackgroundCheckStep(onRefresh)
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Log out", color = TextMuted)
            }
        }
    }
}

@Composable
private fun ProgressHeader(profile: CourierProfile) {
    val s = profile.onboardingStatus
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        step("Phone", done = profile.phoneVerified, active = !profile.phoneVerified, modifier = Modifier.weight(1f))
        step("Vehicle", done = profile.phoneVerified && s != OnboardingStatus.PENDING_INFO, active = profile.phoneVerified && s == OnboardingStatus.PENDING_INFO, modifier = Modifier.weight(1f))
        step("Documents", done = s == OnboardingStatus.PENDING_BACKGROUND || s == OnboardingStatus.APPROVED, active = s == OnboardingStatus.PENDING_DOCUMENTS, modifier = Modifier.weight(1f))
        step("Review", done = s == OnboardingStatus.APPROVED, active = s == OnboardingStatus.PENDING_BACKGROUND, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun step(title: String, done: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    when {
                        done -> SuccessGreen
                        active -> Orange
                        else -> SurfaceDark
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            color = if (done || active) TextWhite else TextMuted,
            fontSize = 11.sp,
        )
    }
}

// ── Step 1: phone verify ────────────────────────────────────

@Composable
private fun PhoneVerifyStep(vm: OnboardingViewModel, onDone: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    Text("Verify your phone", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("We'll text you a 4-digit code. (Dev stub: any code works.)", color = TextSecondary)

    OutlinedTextField(
        value = code, onValueChange = { code = it },
        label = { Text("1234") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    Button(
        onClick = { vm.verifyPhone(onDone) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange),
    ) {
        Text("Verify", color = Color.White, fontWeight = FontWeight.SemiBold)
    }

    state.errorMessage?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }
}

// ── Step 2: vehicle info ────────────────────────────────────

@Composable
private fun VehicleStep(vm: OnboardingViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()

    Text("What are you driving?", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)

    val rows = VehicleType.values().toList().chunked(2)
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { type ->
                VehicleTile(
                    type = type,
                    selected = state.vehicleType == type,
                    onClick = { vm.setVehicleType(type) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

    when (state.vehicleType) {
        VehicleType.CAR, VehicleType.MOTORCYCLE -> {
            OutlinedTextField(state.vehicleMake, vm::setVehicleMake, label = { Text("Make") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(state.vehicleModel, vm::setVehicleModel, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                value = state.vehicleYear,
                onValueChange = { v -> if (v.length <= 4 && v.all { c -> c.isDigit() }) vm.setVehicleYear(v) },
                label = { Text("Year") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val yearInt = state.vehicleYear.toIntOrNull()
            if (state.vehicleYear.length == 4 && (yearInt == null || yearInt !in 1950..(currentYear + 1))) {
                Text("Year must be between 1950 and ${currentYear + 1}", color = ErrorRed, fontSize = 12.sp)
            }
            OutlinedTextField(state.vehicleColor, vm::setVehicleColor, label = { Text("Color") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(state.licensePlate, vm::setLicensePlate, label = { Text("License plate") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        VehicleType.BIKE, VehicleType.SCOOTER -> {
            // Optional color for identification, nothing else.
            OutlinedTextField(state.vehicleColor, vm::setVehicleColor, label = { Text("Color (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        VehicleType.WALK -> { /* nothing to collect */ }
    }

    Button(
        onClick = { vm.submitVehicle { onDone() } },
        enabled = vm.vehicleFormValid && !state.isSubmitting,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange),
    ) {
        if (state.isSubmitting) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp))
        } else {
            Text("Continue", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    state.errorMessage?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }
}

@Composable
private fun VehicleTile(
    type: VehicleType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (type) {
        VehicleType.CAR -> Icons.Filled.DirectionsCar
        VehicleType.BIKE -> Icons.Filled.DirectionsBike
        VehicleType.SCOOTER -> Icons.Filled.ElectricScooter
        VehicleType.MOTORCYCLE -> Icons.Filled.TwoWheeler
        VehicleType.WALK -> Icons.Filled.DirectionsWalk
    }
    Column(
        modifier = modifier
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Orange else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = type.displayName, tint = if (selected) Orange else TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(type.displayName, color = if (selected) TextWhite else TextSecondary, fontSize = 13.sp)
    }
}

// ── Step 3: documents ───────────────────────────────────────

@Composable
private fun DocumentsStep(vm: OnboardingViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()

    Text("Upload your documents", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("We review these as part of your background check.", color = TextSecondary)

    OutlinedTextField(
        value = state.driversLicenseNumber,
        onValueChange = vm::setDriversLicenseNumber,
        label = { Text("Drivers license number") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )

    DocumentUploadRow("Drivers license photo", OnboardingViewModel.DocumentKind.LICENSE, UploadService.Kind.LICENSE, state.driversLicenseUrl, vm)
    DocumentUploadRow("Insurance card", OnboardingViewModel.DocumentKind.INSURANCE, UploadService.Kind.INSURANCE, state.insuranceUrl, vm)
    DocumentUploadRow("Vehicle registration", OnboardingViewModel.DocumentKind.REGISTRATION, UploadService.Kind.REGISTRATION, state.registrationUrl, vm)
    DocumentUploadRow("Profile photo (selfie)", OnboardingViewModel.DocumentKind.PROFILE, UploadService.Kind.PROFILE, state.profilePhotoUrl, vm)

    Button(
        onClick = { vm.submitDocuments { onDone() } },
        enabled = vm.documentsFormValid && !state.isSubmitting,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange),
    ) {
        if (state.isSubmitting) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp))
        } else {
            Text("Submit for review", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }

    state.errorMessage?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }
}

@Composable
private fun DocumentUploadRow(
    title: String,
    vmKind: OnboardingViewModel.DocumentKind,
    uploadKind: UploadService.Kind,
    currentUrl: String,
    vm: OnboardingViewModel,
) {
    val uploaded = currentUrl.isNotBlank()
    var isUploading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            isUploading = true
            vm.uploadDocument(uri, vmKind, uploadKind)
            // Clear the local spinner on next state change. The vm updates
            // state.driversLicenseUrl etc which triggers recomposition; by
            // then isUploading should reset via LaunchedEffect below.
        }
    }

    // When the URL transitions from empty -> non-empty, clear the local loading flag.
    androidx.compose.runtime.LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank()) isUploading = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
            .clickable(enabled = !isUploading) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (uploaded) Icons.Filled.CheckCircle else Icons.Filled.CameraAlt,
            contentDescription = null,
            tint = if (uploaded) SuccessGreen else Orange,
        )
        Spacer(Modifier.padding(4.dp))
        Text(title, color = TextWhite, modifier = Modifier.weight(1f))
        if (isUploading) {
            CircularProgressIndicator(color = Orange, modifier = Modifier.size(18.dp))
        } else {
            Text(
                if (uploaded) "Uploaded" else "Select photo",
                color = TextTertiary, fontSize = 13.sp,
            )
        }
    }
}

// ── Step 4: background check waiting ────────────────────────

@Composable
private fun BackgroundCheckStep(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(64.dp),
        )
        Text("Running background check", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "This usually takes a few minutes. We'll notify you as soon as you're approved to drive.",
            color = TextSecondary,
        )
        CircularProgressIndicator(color = Orange)
        TextButton(onClick = onRefresh) {
            Text("Refresh status", color = Orange)
        }
    }
}
