package com.koshereats.consumer.ui.screens.auth

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
    onPhoneNeeded: () -> Unit = {},
    onGuestContinue: () -> Unit = onRegisterSuccess,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var passwordVisible by remember { mutableStateOf(false) }
    val lastNameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

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

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                containerColor = SurfaceDarkElevated,
                contentColor = TextWhite,
            ),
        ) {
            Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                            putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        }
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    scope.launch { snackbarHostState.showSnackbar("Account settings not available on this device") }
                }
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
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
            Text(
                text = "or",
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { lastNameFocus.requestFocus() }),
            )
            OutlinedTextField(
                value = state.registerLastName,
                onValueChange = { viewModel.updateRegisterLastName(it) },
                label = { Text("Last Name") },
                modifier = Modifier.weight(1f).focusRequester(lastNameFocus),
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerEmail,
            onValueChange = { viewModel.updateRegisterEmail(it) },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth().focusRequester(emailFocus),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { phoneFocus.requestFocus() }),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerPhone,
            onValueChange = { viewModel.updateRegisterPhone(it) },
            label = { Text("Phone Number") },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
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
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { confirmFocus.requestFocus() }),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = state.registerConfirmPassword,
            onValueChange = { viewModel.updateRegisterConfirmPassword(it) },
            label = { Text("Confirm Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = TextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().focusRequester(confirmFocus),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); viewModel.register() }),
        )

        state.error?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = msg, color = ErrorRed, fontSize = 14.sp)
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
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}
