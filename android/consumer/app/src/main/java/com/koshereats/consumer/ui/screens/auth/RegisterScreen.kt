package com.koshereats.consumer.ui.screens.auth

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onLoginClick: () -> Unit,
    onGuestContinue: () -> Unit = onRegisterSuccess,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoggedIn, state.isGuest) {
        if (state.isLoggedIn && !state.isGuest) onRegisterSuccess()
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
            text = "KosherEats",
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
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3C4043),
                contentColor = Color.White,
            ),
        ) {
            Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

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
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = TextMuted) },
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
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerPhone,
            onValueChange = { viewModel.updateRegisterPhone(it) },
            label = { Text("Phone Number") },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = TextMuted) },
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
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = TextMuted) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "Toggle password",
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

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerConfirmPassword,
            onValueChange = { viewModel.updateRegisterConfirmPassword(it) },
            label = { Text("Confirm Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = TextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
