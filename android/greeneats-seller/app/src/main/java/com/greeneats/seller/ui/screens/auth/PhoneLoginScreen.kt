package com.greeneats.seller.ui.screens.auth

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.greeneats.seller.R
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.DividerColor
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextTertiary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.AuthViewModel

@Composable
fun PhoneLoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: AuthViewModel,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }

    LaunchedEffect(state.otpCode) {
        if (state.otpSent && state.otpCode.length == 4 && !state.phoneIsVerifying) {
            viewModel.verifyPhoneCode()
        }
    }

    LaunchedEffect(state.otpSent) {
        if (state.otpSent) {
            delay(15_000)
            val current = viewModel.state.value
            if (current.otpSent && current.otpCode.isEmpty() && !current.phoneIsVerifying) {
                viewModel.silentResend()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (state.otpSent) viewModel.backToPhoneEntry() else {
                    viewModel.resetPhoneFlow()
                    onBack()
                }
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
                style = MaterialTheme.typography.headlineSmall,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (state.otpSent) {
                    "We texted a 4-digit code to ${state.phoneE164}"
                } else {
                    "We'll text you a code to verify your identity."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))

            if (!state.otpSent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.phoneCountryCode,
                        onValueChange = {
                            val cleaned = "+" + it.removePrefix("+").filter { c -> c.isDigit() }.take(3)
                            viewModel.updatePhoneCountryCode(cleaned)
                        },
                        modifier = Modifier.width(96.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = phoneFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    OutlinedTextField(
                        value = state.phoneNumber,
                        onValueChange = viewModel::updatePhoneNumber,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = phoneFieldColors(),
                        placeholder = { Text("Phone number", color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.otpCode,
                    onValueChange = viewModel::updateOtpCode,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = phoneFieldColors(),
                    placeholder = { Text("1234", color = TextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.error!!,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (state.otpSent) viewModel.verifyPhoneCode() else viewModel.startPhoneLogin()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = TextWhite,
                    disabledContainerColor = Orange.copy(alpha = 0.4f),
                    disabledContentColor = TextWhite.copy(alpha = 0.6f),
                ),
                enabled = !state.phoneIsSending && !state.phoneIsVerifying,
            ) {
                if (state.phoneIsSending || state.phoneIsVerifying) {
                    CircularProgressIndicator(
                        color = TextWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = stringResource(if (state.otpSent) R.string.auth_verify else R.string.auth_send_code),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (state.otpSent) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.auth_resend_code),
                    color = Orange,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(enabled = !state.phoneIsSending) {
                        viewModel.startPhoneLogin()
                    },
                )
            }
        }
    }
}

@Composable
private fun phoneFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = DividerColor,
    cursorColor = Orange,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)
