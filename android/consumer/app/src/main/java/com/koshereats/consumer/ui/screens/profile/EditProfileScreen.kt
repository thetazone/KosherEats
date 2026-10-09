package com.koshereats.consumer.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.EditProfileViewModel
import com.koshereats.consumer.ui.viewmodels.AuthUiState
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

private val fieldColors
    @Composable get() = TextFieldDefaults.colors(
        focusedContainerColor = SurfaceDarkElevated,
        unfocusedContainerColor = SurfaceDarkElevated,
        cursorColor = Orange,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextTertiary,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onSaved: (firstName: String, lastName: String, phone: String) -> Unit = { _, _, _ -> },
    viewModel: EditProfileViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val lastNameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // A verified phone change lands on the session user; mirror it into this form.
    LaunchedEffect(authState.user?.phone) {
        authState.user?.phone?.let { if (it.isNotBlank()) viewModel.setPhone(it) }
    }
    LaunchedEffect(Unit) { authViewModel.backToVPhoneEntry() }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            onSaved(state.firstName, state.lastName, state.phone)
            onBack()
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
                    "Edit Profile",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Orange)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Personal Information",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )

                TextField(
                    value = state.firstName,
                    onValueChange = viewModel::updateFirstName,
                    label = { Text("First Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    colors = fieldColors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { lastNameFocus.requestFocus() }),
                )

                TextField(
                    value = state.lastName,
                    onValueChange = viewModel::updateLastName,
                    label = { Text("Last Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(lastNameFocus)
                        .clip(RoundedCornerShape(12.dp)),
                    colors = fieldColors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )

                PhoneSection(
                    currentPhone = state.phone,
                    auth = authState,
                    onCountryCode = authViewModel::updateVCountryCode,
                    onNumber = authViewModel::updateVPhoneNumber,
                    onCode = authViewModel::updateVPhoneCode,
                    onSend = authViewModel::sendVPhoneCode,
                    onConfirm = authViewModel::confirmVPhoneCode,
                    onBackToEntry = authViewModel::backToVPhoneEntry,
                )

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::saveProfile,
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = TextWhite,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            "Save Changes",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Read-only phone with an inline "Change" flow. The number is a login factor,
 * so it only changes through the SMS-verified /user/phone/change endpoints
 * (the same AuthViewModel state the verification gate uses).
 */
@Composable
private fun PhoneSection(
    currentPhone: String,
    auth: AuthUiState,
    onCountryCode: (String) -> Unit,
    onNumber: (String) -> Unit,
    onCode: (String) -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onBackToEntry: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var lastPhone by remember { mutableStateOf(currentPhone) }
    // Verification succeeded → the profile phone changed → collapse the editor.
    if (currentPhone != lastPhone) {
        lastPhone = currentPhone
        editing = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Phone Number",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentPhone.ifBlank { "No phone on file" },
                color = TextWhite,
                fontSize = 16.sp,
            )
            TextButton(onClick = {
                editing = !editing
                if (!editing) onBackToEntry()
            }) {
                Text(if (editing) "Cancel" else "Change", color = Orange, fontWeight = FontWeight.SemiBold)
            }
        }
        if (editing) {
            Text(
                text = "We'll text a 4-digit code to verify the new number.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
            if (!auth.vPhoneCodeSent) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = auth.vCountryCode,
                        onValueChange = { v ->
                            val digits = v.removePrefix("+").filter { c -> c.isDigit() }.take(3)
                            onCountryCode(if (digits.isEmpty()) "+" else "+$digits")
                        },
                        modifier = Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)),
                        colors = fieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    TextField(
                        value = auth.vPhoneNumber,
                        onValueChange = onNumber,
                        label = { Text("New number") },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                        colors = fieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            } else {
                TextField(
                    value = auth.vPhoneCode,
                    onValueChange = onCode,
                    label = { Text("4-digit code") },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    colors = fieldColors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
            auth.vError?.let { err ->
                Text(text = err, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (auth.vPhoneCodeSent) onConfirm() else onSend() },
                    enabled = !auth.vBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    if (auth.vBusy) {
                        CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (auth.vPhoneCodeSent) "Verify" else "Send code", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (auth.vPhoneCodeSent) {
                    TextButton(onClick = onBackToEntry) {
                        Text("Use a different number", color = TextTertiary)
                    }
                }
            }
        }
    }
}
