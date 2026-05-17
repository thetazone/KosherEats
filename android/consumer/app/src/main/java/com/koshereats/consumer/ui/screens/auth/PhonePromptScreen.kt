package com.koshereats.consumer.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.ui.theme.BackgroundBlack
import com.koshereats.consumer.ui.theme.Orange
import com.koshereats.consumer.ui.theme.SurfaceDark
import com.koshereats.consumer.ui.theme.SurfaceDarkBorder
import com.koshereats.consumer.ui.theme.TextMuted
import com.koshereats.consumer.ui.theme.TextTertiary
import com.koshereats.consumer.ui.theme.TextWhite
import com.koshereats.consumer.ui.theme.ErrorRed
import com.koshereats.consumer.ui.viewmodels.AuthViewModel

@Composable
fun PhonePromptScreen(
    onComplete: () -> Unit,
    viewModel: AuthViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var phone by remember { mutableStateOf("") }
    val countryCode = state.phoneCountryCode

    LaunchedEffect(state.needsPhone) {
        if (!state.needsPhone && state.isLoggedIn) onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(100.dp))

        Text(
            text = "What's your phone number?",
            style = MaterialTheme.typography.headlineSmall,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "We'll use this for order updates and delivery coordination.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
        )

        Spacer(Modifier.height(40.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, SurfaceDarkBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("🇺🇸", fontSize = 20.sp)
                Text(
                    text = countryCode,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(10) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = Orange,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                ),
                textStyle = MaterialTheme.typography.titleMedium,
                placeholder = { Text("Phone number", color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        val phoneValid = phone.length >= 7
        Button(
            onClick = { viewModel.submitPhone("$countryCode$phone") },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (phoneValid) Orange else Orange.copy(alpha = 0.4f),
                disabledContainerColor = Orange.copy(alpha = 0.4f),
                contentColor = TextWhite,
                disabledContentColor = TextWhite,
            ),
            enabled = phoneValid && !state.isLoading,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    "Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Skip for now",
            color = TextMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { viewModel.skipPhone() },
        )
    }
}
