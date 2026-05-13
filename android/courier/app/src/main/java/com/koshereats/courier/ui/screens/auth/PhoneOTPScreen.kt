package com.koshereats.courier.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.ErrorRed
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.SurfaceDarkBorder
import com.koshereats.courier.ui.theme.TextMuted
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import com.koshereats.courier.ui.viewmodels.AuthViewModel

@Composable
fun PhoneOTPScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit,
) {
    val state by authViewModel.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(BackgroundBlack).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextWhite)
            }
            Text("Verify phone", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            "We sent a 4-digit code to ${state.phoneE164}.",
            color = TextTertiary,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(4) },
            label = { Text("Code", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = Orange,
                unfocusedBorderColor = SurfaceDarkBorder,
                cursorColor = Orange,
            ),
        )

        Spacer(Modifier.height(4.dp))
        Text(
            "If this is your first time, also tell us your name:",
            color = TextMuted,
            fontSize = 12.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text("First name", color = TextMuted) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = SurfaceDarkBorder,
                    cursorColor = Orange,
                ),
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text("Last name", color = TextMuted) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = SurfaceDarkBorder,
                    cursorColor = Orange,
                ),
            )
        }

        state.errorMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = ErrorRed, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                authViewModel.verifyPhoneLogin(
                    code = code,
                    firstName = firstName.ifBlank { null },
                    lastName = lastName.ifBlank { null },
                )
            },
            enabled = code.length == 4 && !state.phoneIsVerifying,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
        ) {
            if (state.phoneIsVerifying) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.height(24.dp))
            } else {
                Text("Verify", fontWeight = FontWeight.SemiBold)
            }
        }

        TextButton(onClick = { authViewModel.resetPhoneFlow(); onBack() }) {
            Text("Wrong number? Go back.", color = Orange)
        }
    }
}
