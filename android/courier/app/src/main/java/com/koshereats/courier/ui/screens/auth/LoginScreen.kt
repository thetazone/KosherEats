package com.koshereats.courier.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Driver welcome / entry screen. Same shape as the consumer LoginScreen:
 * phone-number field on top, then Continue with Google + Continue with Email.
 */
@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onPhoneCodeSent: () -> Unit,
    onEmailLoginClick: () -> Unit,
) {
    val state by authViewModel.state.collectAsState()
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    val countryCode = "+1"

    LaunchedEffect(state.otpSent) {
        if (state.otpSent) onPhoneCodeSent()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Orange),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LocalShipping,
                contentDescription = null,
                tint = BackgroundBlack,
                modifier = Modifier.size(50.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Deliver with KosherEats",
            color = TextWhite,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Set your own schedule. Earn on every drop.",
            color = TextTertiary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        // Mobile number field + Continue
        Text(
            "Mobile number",
            color = TextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
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
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { v -> phoneNumber = v.filter { it.isDigit() } },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Mobile number", color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
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
        Spacer(Modifier.height(20.dp))

        val phoneValid = phoneNumber.length >= 7
        Button(
            onClick = { authViewModel.startPhoneLogin("$countryCode$phoneNumber") },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (phoneValid) Orange else Orange.copy(alpha = 0.4f),
                disabledContainerColor = Orange.copy(alpha = 0.4f),
                contentColor = Color.White,
                disabledContentColor = Color.White,
            ),
            enabled = phoneValid && !state.phoneIsSending,
        ) {
            if (state.phoneIsSending) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        state.errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = ErrorRed, fontSize = 12.sp)
        }

        Spacer(Modifier.height(28.dp))

        // "OR" divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
            Text(
                "OR",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceDarkBorder)
        }

        Spacer(Modifier.height(20.dp))

        AuthOptionButton(
            text = "Continue with Google",
            onClick = { authViewModel.signInWithGoogle(context) },
            leading = {
                Text(
                    "G",
                    color = Color(0xFFEA4335),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
        )

        Spacer(Modifier.height(12.dp))

        AuthOptionButton(
            text = "Continue with Email",
            onClick = onEmailLoginClick,
            leading = {
                Icon(Icons.Filled.Email, contentDescription = null, tint = TextWhite, modifier = Modifier.size(20.dp))
            },
        )

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun AuthOptionButton(
    text: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, SurfaceDarkBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading()
            Text(text, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
