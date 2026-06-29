package com.koshereats.consumer.ui.screens.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

/**
 * Mandatory post-sign-in verification. A new consumer must confirm BOTH a real
 * email (6-digit emailed code) and a real phone (Twilio SMS code) before they
 * can transact — the backend hard-gates order/payment on the same flags. Shown
 * by the gate in NavGraph whenever [AuthViewModel] reports needsVerification;
 * not dismissible (back is blocked) — the only escape is signing out.
 *
 * Walks whichever steps are missing, email → phone:
 *  - phone signup → email step only; Google → phone step only; both for a fresh
 *    account. (Email signup verifies its email pre-account in RegisterScreen.)
 */
private const val PHONE_PLACEHOLDER_SUFFIX = "@phone.koshereats.local"
private const val APPLE_RELAY_SUFFIX = "@privaterelay.appleid.com"

@Composable
fun AccountVerificationScreen(
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user = state.user

    val needEmail = user?.emailVerified == false
    val needPhone = user?.phoneVerified == false

    // Pre-fill the email field with the account's email unless it's a placeholder
    // (phone-OTP synthesized) or Apple relay forwarder — those need a real inbox.
    LaunchedEffect(needEmail) {
        if (needEmail && state.vEmail.isEmpty()) {
            val e = user?.email.orEmpty()
            if (e.isNotBlank() && !e.endsWith(PHONE_PLACEHOLDER_SUFFIX) && !e.endsWith(APPLE_RELAY_SUFFIX)) {
                viewModel.updateVEmail(e)
            }
        }
    }

    // Auto-submit when codes are complete.
    LaunchedEffect(state.vEmailCode) {
        if (state.vEmailCodeSent && state.vEmailCode.length == 6 && !state.vBusy) viewModel.confirmVEmail()
    }
    LaunchedEffect(state.vPhoneCode) {
        if (state.vPhoneCodeSent && state.vPhoneCode.length == 4 && !state.vBusy) viewModel.confirmVPhoneCode()
    }

    // Non-dismissible: block the back gesture/button.
    BackHandler(enabled = true) { /* mandatory — no-op */ }

    val onEmailStep = needEmail
    val codeStep = if (onEmailStep) state.vEmailCodeSent else state.vPhoneCodeSent

    Column(modifier = Modifier.fillMaxSize().background(BackgroundBlack).imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Orange.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (onEmailStep) Icons.Filled.MarkEmailRead else Icons.Filled.Sms,
                    contentDescription = null,
                    tint = Orange,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (onEmailStep) "Verify your email" else "Verify your phone",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = when {
                    onEmailStep && !codeStep -> "We'll send a 6-digit code to confirm it's really you."
                    onEmailStep && codeStep -> "Enter the 6-digit code we sent to ${state.vEmail}."
                    !onEmailStep && !codeStep -> "We'll text you a code to confirm your number."
                    else -> "Enter the code we sent to ${state.vPhoneE164}."
                },
                color = TextTertiary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))

            when {
                onEmailStep && !codeStep -> EmailField(state.vEmail, viewModel::updateVEmail)
                onEmailStep && codeStep -> CodeField(state.vEmailCode, viewModel::updateVEmailCode, "123456")
                !onEmailStep && !codeStep -> PhoneRow(
                    countryCode = state.vCountryCode,
                    onCountryCodeChange = viewModel::updateVCountryCode,
                    phone = state.vPhoneNumber,
                    onPhoneChange = viewModel::updateVPhoneNumber,
                )
                else -> CodeField(state.vPhoneCode, viewModel::updateVPhoneCode, "1234")
            }

            state.vError?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(text = msg, color = ErrorRed, fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    when {
                        onEmailStep && !codeStep -> viewModel.sendVEmailCode()
                        onEmailStep && codeStep -> viewModel.confirmVEmail()
                        !onEmailStep && !codeStep -> viewModel.sendVPhoneCode()
                        else -> viewModel.confirmVPhoneCode()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                enabled = !state.vBusy,
            ) {
                if (state.vBusy) {
                    CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(22.dp))
                } else {
                    Text(
                        text = if (codeStep) "Verify" else "Send code",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextWhite,
                    )
                }
            }

            if (codeStep) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Didn't get it? Resend code",
                    color = if (!state.vBusy) Orange else Orange.copy(alpha = 0.4f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = !state.vBusy) {
                        if (onEmailStep) viewModel.sendVEmailCode() else viewModel.sendVPhoneCode()
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (onEmailStep) "Use a different email" else "Use a different number",
                    color = TextTertiary,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        if (onEmailStep) viewModel.backToVEmailEntry() else viewModel.backToVPhoneEntry()
                    },
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "Not now — sign out",
                color = TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { viewModel.logout() },
            )
        }
    }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = verifyFieldColors(),
        placeholder = { Text("you@example.com", color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
}

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = code,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = verifyFieldColors(),
        placeholder = { Text(placeholder, color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

@Composable
private fun PhoneRow(
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
                val digits = it.removePrefix("+").filter { c -> c.isDigit() }.take(3)
                onCountryCodeChange(if (digits.isEmpty()) "+" else "+$digits")
            },
            modifier = Modifier.width(96.dp),
            shape = RoundedCornerShape(12.dp),
            colors = verifyFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = verifyFieldColors(),
            placeholder = { Text("Phone number", color = TextMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

@Composable
private fun verifyFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = SurfaceDarkBorder,
    cursorColor = Orange,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
)
