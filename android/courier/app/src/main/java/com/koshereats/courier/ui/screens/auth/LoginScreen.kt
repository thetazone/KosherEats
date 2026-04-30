package com.koshereats.courier.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.courier.R
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.ErrorRed
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.AuthViewModel

@Composable
fun LoginScreen(authViewModel: AuthViewModel) {
    val state by authViewModel.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(BackgroundBlack).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.auth_login_title), color = TextWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        state.errorMessage?.let {
            Text(it, color = ErrorRed, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { authViewModel.login(email, password) },
            enabled = email.isNotBlank() && password.isNotBlank() && !state.isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp))
            } else {
                Text(stringResource(R.string.auth_log_in), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
