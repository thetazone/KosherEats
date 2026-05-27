package com.greeneats.consumer.ui.screens.auth

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.ui.viewmodels.AuthViewModel

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onLoginClick: () -> Unit,
    onPhoneNeeded: () -> Unit = {},
    onGuestContinue: () -> Unit = onRegisterSuccess,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoggedIn, state.isGuest, state.needsPhone) {
        if (state.isLoggedIn && !state.isGuest) {
            if (state.needsPhone) onPhoneNeeded() else onRegisterSuccess()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Orange,
        unfocusedBorderColor = SurfaceDarkBorder,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextMuted,
        cursorColor = Orange,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedContainerColor = SurfaceDark,
        unfocusedContainerColor = SurfaceDark,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "GreenEats",
            color = Orange,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your account",
            color = TextTertiary,
            fontSize = 16.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Social login buttons
        Button(
            onClick = { viewModel.signInWithGoogle(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { contentDescription = "Sign up with your Google account" },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3C4043),
                contentColor = Color.White,
            ),
        ) {
            Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                    }
                )
            },
        ) {
            Text(
                text = "Use a different Google account",
                fontSize = 13.sp,
                color = Orange,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // "or" divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Divider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
            Text(
                text = "or",
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Divider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Name row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.registerFirstName,
                onValueChange = { viewModel.updateRegisterFirstName(it) },
                label = { Text("First Name") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = "First name", tint = TextMuted) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.registerLastName,
                onValueChange = { viewModel.updateRegisterLastName(it) },
                label = { Text("Last Name") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                singleLine = true,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerEmail,
            onValueChange = { viewModel.updateRegisterEmail(it) },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = "Email", tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = state.registerEmail.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(state.registerEmail).matches(),
            supportingText = if (state.registerEmail.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(state.registerEmail).matches()) {
                { Text("Enter a valid email address", color = ErrorRed) }
            } else null,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerPhone,
            onValueChange = { viewModel.updateRegisterPhone(it) },
            label = { Text("Phone Number") },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = "Phone number", tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerPassword,
            onValueChange = { viewModel.updateRegisterPassword(it) },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = "Password", tint = TextMuted) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = TextMuted,
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        // Password strength indicator
        if (state.registerPassword.isNotEmpty()) {
            val pwd = state.registerPassword
            val hasMinLength = pwd.length >= 8
            val hasUpper = pwd.any { it.isUpperCase() }
            val hasDigit = pwd.any { it.isDigit() }
            val hasSpecial = pwd.any { !it.isLetterOrDigit() }
            val score = listOf(hasMinLength, hasUpper, hasDigit, hasSpecial).count { it }
            val strengthLabel = when (score) {
                0, 1 -> "Weak"
                2 -> "Fair"
                3 -> "Good"
                else -> "Strong"
            }
            val strengthColor = when (score) {
                0, 1 -> ErrorRed
                2 -> WarningYellow
                3 -> Orange
                else -> SuccessGreen
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { score / 4f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .semantics { contentDescription = "Password strength: $strengthLabel" },
                color = strengthColor,
                trackColor = SurfaceDarkBorder,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strengthLabel,
                color = strengthColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!hasMinLength) {
                Text("At least 8 characters", color = TextMuted, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val passwordMismatch = state.registerConfirmPassword.isNotEmpty() &&
            state.registerConfirmPassword != state.registerPassword

        OutlinedTextField(
            value = state.registerConfirmPassword,
            onValueChange = { viewModel.updateRegisterConfirmPassword(it) },
            label = { Text("Confirm Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = "Confirm password", tint = TextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = passwordMismatch,
            supportingText = if (passwordMismatch) {
                { Text("Passwords do not match", color = ErrorRed) }
            } else null,
        )

        if (state.error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = state.error!!, color = ErrorRed, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.register() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            enabled = !state.isLoading,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
            } else {
                Text("Create Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row {
            Text("Already have an account? ", color = TextTertiary, fontSize = 14.sp)
            Text(
                text = "Sign In",
                color = Orange,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onLoginClick),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Continue as Guest
        Text(
            text = "Continue as Guest",
            color = TextTertiary,
            fontSize = 14.sp,
            modifier = Modifier.clickable {
                viewModel.continueAsGuest()
                onGuestContinue()
            },
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
