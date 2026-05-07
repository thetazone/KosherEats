package com.koshereats.seller.ui.screens.deals

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.DiscountType
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.DealsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDealScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: DealsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var discountType by remember { mutableStateOf(DiscountType.PERCENTAGE) }
    var discountValue by remember { mutableStateOf("") }
    var minOrderAmount by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var expiresAtMillis by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.createSuccess) {
        if (state.createSuccess) {
            viewModel.clearCreateSuccess()
            onCreated()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedBorderColor = Orange,
        unfocusedBorderColor = SurfaceDarkElevated,
        cursorColor = Orange,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextMuted,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    "Create Deal",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Deal Title") },
                placeholder = { Text("e.g., 20% off falafel plates", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
                singleLine = true,
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                placeholder = { Text("Describe the deal...", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
                minLines = 2,
                maxLines = 4,
            )

            // Discount Type selector
            Text(
                text = "Discount Type",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiscountType.entries.forEach { type ->
                    val selected = discountType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Orange.copy(alpha = 0.15f) else SurfaceDark)
                            .border(
                                width = 1.dp,
                                color = if (selected) Orange else SurfaceDarkElevated,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { discountType = type },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when (type) {
                                DiscountType.PERCENTAGE -> "% Off"
                                DiscountType.FIXED -> "$ Off"
                                DiscountType.BOGO -> "BOGO"
                            },
                            color = if (selected) Orange else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Discount Value (hidden for BOGO)
            if (discountType != DiscountType.BOGO) {
                OutlinedTextField(
                    value = discountValue,
                    onValueChange = { discountValue = it.filter { c -> c.isDigit() } },
                    label = {
                        Text(
                            when (discountType) {
                                DiscountType.PERCENTAGE -> "Discount Percentage (1-100)"
                                DiscountType.FIXED -> "Discount Amount (cents)"
                                else -> "Value"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            // Min order amount (optional)
            OutlinedTextField(
                value = minOrderAmount,
                onValueChange = { minOrderAmount = it.filter { c -> c.isDigit() } },
                label = { Text("Min Order Amount (cents, optional)") },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            // Expiry date picker
            Text(
                text = "Expires At",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark)
                    .border(1.dp, SurfaceDarkElevated, RoundedCornerShape(10.dp))
                    .clickable { showDatePicker = true },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (expiresAtMillis != null) {
                        val zdt = Instant.ofEpochMilli(expiresAtMillis!!)
                            .atZone(ZoneId.systemDefault())
                        zdt.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    } else {
                        "Select expiration date"
                    },
                    color = if (expiresAtMillis != null) TextWhite else TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (showDatePicker) {
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = expiresAtMillis
                        ?: (System.currentTimeMillis() + 7 * 24 * 3600 * 1000L),
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                expiresAtMillis = datePickerState.selectedDateMillis
                                showDatePicker = false
                            },
                        ) {
                            Text("OK", color = Orange)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text("Cancel", color = TextMuted)
                        }
                    },
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            // Error message
            state.error?.let {
                Text(
                    text = it,
                    color = com.koshereats.seller.ui.theme.ErrorRed,
                    fontSize = 13.sp,
                )
            }

            // Create button
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val expiresAtStr = expiresAtMillis?.let { millis ->
                        Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .withHour(23).withMinute(59).withSecond(59)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    } ?: return@Button

                    viewModel.createDeal(
                        title = title,
                        description = description,
                        discountType = discountType,
                        discountValue = discountValue.toIntOrNull() ?: 0,
                        minOrderAmount = minOrderAmount.toIntOrNull(),
                        startsAt = null,
                        expiresAt = expiresAtStr,
                    )
                },
                enabled = title.isNotBlank() && expiresAtMillis != null && !state.isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    disabledContainerColor = Orange.copy(alpha = 0.3f),
                ),
            ) {
                if (state.isCreating) {
                    CircularProgressIndicator(
                        color = TextWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "Create Deal",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
