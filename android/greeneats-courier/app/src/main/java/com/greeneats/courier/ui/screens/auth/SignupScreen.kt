package com.greeneats.courier.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greeneats.courier.ui.theme.BackgroundBlack
import com.greeneats.courier.ui.theme.ErrorRed
import com.greeneats.courier.ui.theme.Orange
import com.greeneats.courier.ui.theme.TextMuted
import com.greeneats.courier.ui.theme.TextTertiary
import com.greeneats.courier.ui.theme.TextWhite
import com.greeneats.courier.ui.viewmodels.AuthViewModel

@Composable
fun SignupScreen(authViewModel: AuthViewModel) {
    val state by authViewModel.state.collectAsState()
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val valid = firstName.isNotBlank() && phone.isNotBlank() && email.isNotBlank() && password.length >= 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create your driver account", color = TextWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Step 1 of 4 • Basic info", color = TextTertiary, fontSize = 12.sp)

        OutlinedTextField(
            value = firstName, onValueChange = { firstName = it },
            label = { Text("First name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = lastName, onValueChange = { lastName = it },
            label = { Text("Last name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password (8+ characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        state.errorMessage?.let {
            Text(it, color = ErrorRed, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { authViewModel.signup(email, password, firstName, lastName, phone) },
            enabled = valid && !state.isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp))
            } else {
                Text("Continue", fontWeight = FontWeight.SemiBold)
            }
        }

        Text(
            "By continuing you agree to a background check and GreenEats' Courier Agreement.",
            color = TextMuted, fontSize = 11.sp,
        )
    }
}
