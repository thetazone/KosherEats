package com.koshereats.consumer.ui.screens.auth

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

@Composable
fun PhoneAuthScreen(
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isLoggedIn, state.isGuest) {
        if (state.isLoggedIn && !state.isGuest) onAuthSuccess()
    }

    // Auto-submit when 4-digit code complete.
    LaunchedEffect(state.otpCode) {
        if (state.otpSent && state.otpCode.length == 4 && !state.phoneIsVerifying) {
            viewModel.verifyPhoneCode()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        // Top back bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (state.otpSent) viewModel.backToPhoneEntry() else onBack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Orange.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Phone,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (state.otpSent) "Enter the code" else "Continue with phone",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (state.otpSent) {
                    "We texted a 4-digit code to ${state.phoneE164}"
                } else {
                    "We'll text you a code to verify it's really you. Used for sign-in and order tracking."
                },
                color = TextTertiary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))

            if (!state.otpSent) {
                PhoneEntry(
                    countryCode = state.phoneCountryCode,
                    onCountryCodeChange = viewModel::updatePhoneCountryCode,
                    phone = state.phoneNumber,
                    onPhoneChange = viewModel::updatePhoneNumber,
                )
            } else {
                CodeEntry(
                    code = state.otpCode,
                    onCodeChange = viewModel::updateOtpCode,
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(text = state.error!!, color = ErrorRed, fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (state.otpSent) viewModel.verifyPhoneCode() else viewModel.startPhoneLogin()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                enabled = !state.phoneIsSending && !state.phoneIsVerifying,
            ) {
                if (state.phoneIsSending || state.phoneIsVerifying) {
                    CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(22.dp))
                } else {
                    Text(
                        text = if (state.otpSent) "Verify" else "Send code",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextWhite,
                    )
                }
            }

            if (state.otpSent) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Didn't get it? Resend code",
                    color = Orange,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = !state.phoneIsSending) {
                        viewModel.startPhoneLogin()
                    },
                )
            }
        }
    }
}

@Composable
private fun PhoneEntry(
    countryCode: String,
    onCountryCodeChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = countryCode,
            onValueChange = {
                val cleaned = "+" + it.removePrefix("+").filter { c -> c.isDigit() }.take(3)
                onCountryCodeChange(cleaned)
            },
            modifier = Modifier.width(96.dp),
            shape = RoundedCornerShape(12.dp),
            colors = phoneFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = phoneFieldColors(),
            placeholder = { Text("Phone number", color = TextMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

@Composable
private fun CodeEntry(
    code: String,
    onCodeChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = phoneFieldColors(),
        placeholder = { Text("1234", color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

@Composable
private fun phoneFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = SurfaceDarkBorder,
    cursorColor = Orange,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)
