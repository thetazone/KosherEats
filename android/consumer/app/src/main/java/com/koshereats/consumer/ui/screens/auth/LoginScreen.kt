package com.koshereats.consumer.ui.screens.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.R
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel
import com.koshereats.consumer.ui.viewmodels.SessionState

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onPhoneCodeSent: () -> Unit,
    onEmailLoginClick: () -> Unit,
    onPhoneNeeded: () -> Unit = {},
    onGuestContinue: () -> Unit = onLoginSuccess,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var showCountryPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.sessionState, state.needsPhone) {
        if (state.sessionState == SessionState.Authenticated) {
            if (state.needsPhone) onPhoneNeeded() else onLoginSuccess()
        }
    }

    // When OTP send succeeds, advance to the code-entry screen.
    LaunchedEffect(state.otpSent) {
        if (state.otpSent) onPhoneCodeSent()
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))

        // Logo: orange circle with fork-and-knife icon
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Orange),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Restaurant,
                contentDescription = null,
                tint = BackgroundBlack,
                modifier = Modifier.size(50.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.auth_welcome_title),
            style = MaterialTheme.typography.displaySmall,
            color = TextWhite,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.auth_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        // Mobile number entry
        Text(
            text = stringResource(R.string.auth_mobile_number),
            style = MaterialTheme.typography.labelMedium,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Country code chip
            Row(
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, SurfaceDarkBorder, RoundedCornerShape(12.dp))
                    .clickable { showCountryPicker = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(countryFlagFor(state.phoneCountryCode), fontSize = 20.sp)
                Text(
                    text = state.phoneCountryCode,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
                Text("▾", color = TextMuted, fontSize = 14.sp)
            }
            OutlinedTextField(
                value = state.phoneNumber,
                onValueChange = {
                    viewModel.updatePhoneNumber(it)
                    if (state.error != null) viewModel.clearError()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = welcomeFieldColors(),
                textStyle = MaterialTheme.typography.titleMedium,
                placeholder = { Text(stringResource(R.string.auth_mobile_number), color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (state.phoneNumber.length >= 7 && !state.phoneIsSending) {
                        focusManager.clearFocus()
                        viewModel.startPhoneLogin()
                    }
                }),
            )
        }

        Spacer(Modifier.height(20.dp))

        val phoneValid = state.phoneNumber.length >= 7
        Button(
            onClick = { viewModel.startPhoneLogin() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (phoneValid) Orange else Orange.copy(alpha = 0.4f),
                disabledContainerColor = Orange.copy(alpha = 0.4f),
                contentColor = TextWhite,
                disabledContentColor = TextWhite,
            ),
            enabled = phoneValid && !state.phoneIsSending,
        ) {
            if (state.phoneIsSending) {
                CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    stringResource(R.string.auth_continue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        state.error?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(32.dp))

        // "or" divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
            Text(
                text = stringResource(R.string.auth_or_divider),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
        }

        Spacer(Modifier.height(24.dp))

        OutlinedAuthButton(
            text = stringResource(R.string.auth_continue_google),
            onClick = { viewModel.signInWithGoogle(context) },
            leading = {
                Text(
                    text = "G",
                    color = GoogleRed,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
        )

        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                            putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        }
                    )
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.auth_account_settings_unavailable)) }
                }
            },
        ) {
            Text(
                text = stringResource(R.string.auth_use_different_google),
                style = MaterialTheme.typography.bodySmall,
                color = Orange,
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedAuthButton(
            text = stringResource(R.string.auth_continue_email),
            onClick = onEmailLoginClick,
            leading = {
                Icon(Icons.Filled.Email, contentDescription = null, tint = TextWhite, modifier = Modifier.size(20.dp))
            },
        )

        Spacer(Modifier.height(16.dp))

        OutlinedAuthButton(
            text = stringResource(R.string.auth_continue_guest),
            onClick = {
                viewModel.continueAsGuest()
                onGuestContinue()
            },
            textColor = Orange,
        )

        Spacer(Modifier.height(48.dp))
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    if (showCountryPicker) {
        CountryCodePickerSheet(
            onPick = { country ->
                viewModel.updatePhoneCountryCode(country.dialCode)
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false },
        )
    }
    } // end Box
}

@Composable
private fun OutlinedAuthButton(
    text: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    textColor: androidx.compose.ui.graphics.Color = TextWhite,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(width = 1.dp, color = SurfaceDarkBorder, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) leading()
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun welcomeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = Orange,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)

private val GoogleRed = androidx.compose.ui.graphics.Color(0xFFE94235)
