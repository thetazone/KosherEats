package com.koshereats.consumer.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.consumer.ui.theme.BackgroundBlack
import com.koshereats.consumer.ui.theme.ErrorRed
import com.koshereats.consumer.ui.theme.Orange
import com.koshereats.consumer.ui.theme.SurfaceDarkBorder
import com.koshereats.consumer.ui.theme.TextMuted
import com.koshereats.consumer.ui.theme.TextSecondary
import com.koshereats.consumer.ui.theme.TextTertiary
import com.koshereats.consumer.ui.theme.TextWhite
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

/**
 * Two-step password reset backed by POST auth/password/forgot and
 * auth/password/reset: email → emailed code + new password. On success the
 * view model pre-fills the sign-in form and [onDone] pops back to it.
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startResetFlow() }
    LaunchedEffect(state.resetDone) { if (state.resetDone) onDone() }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack).imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (state.resetCodeSent) "Check your email" else "Reset your password",
                    color = TextWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.resetCodeSent) {
                        "We sent a code to ${state.resetEmail}. Enter it below with your new password."
                    } else {
                        "Enter the email you signed up with and we'll send you a reset code."
                    },
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(24.dp))

                if (!state.resetCodeSent) {
                    OutlinedTextField(
                        value = state.resetEmail,
                        onValueChange = viewModel::updateResetEmail,
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = resetFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                } else {
                    OutlinedTextField(
                        value = state.resetCode,
                        onValueChange = viewModel::updateResetCode,
                        label = { Text("Reset code") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = resetFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.resetNewPassword,
                        onValueChange = viewModel::updateResetNewPassword,
                        label = { Text("New password") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = resetFieldColors(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }

                state.resetError?.let { msg ->
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = ErrorRed, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { if (state.resetCodeSent) viewModel.submitPasswordReset() else viewModel.sendResetCode() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    enabled = !state.resetBusy,
                ) {
                    if (state.resetBusy) {
                        CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            text = if (state.resetCodeSent) "Set new password" else "Send code",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextWhite,
                        )
                    }
                }

                if (state.resetCodeSent) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Didn't get it? Resend code",
                        color = if (!state.resetBusy) Orange else Orange.copy(alpha = 0.4f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(enabled = !state.resetBusy) { viewModel.sendResetCode() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Use a different email",
                        color = TextTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { viewModel.backToResetEmail() },
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun resetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orange,
    unfocusedBorderColor = SurfaceDarkBorder,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    cursorColor = Orange,
    focusedLabelColor = Orange,
    unfocusedLabelColor = TextMuted,
)
