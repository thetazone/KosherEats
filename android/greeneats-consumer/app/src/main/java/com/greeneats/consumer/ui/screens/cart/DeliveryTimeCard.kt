package com.greeneats.consumer.ui.screens.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.greeneats.consumer.ui.theme.Orange
import com.greeneats.consumer.ui.theme.SurfaceDark
import com.greeneats.consumer.ui.theme.SurfaceDarkBorder
import com.greeneats.consumer.ui.theme.TextSecondary
import com.greeneats.consumer.ui.theme.TextWhite
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ASAP vs Schedule toggle for checkout. Port of iOS
 * `DeliveryTimeCard` from CheckoutView.swift — two mutually exclusive
 * pills, and tapping "Schedule" opens a date + time picker dialog.
 *
 * The parent owns `scheduledFor` state; this composable just renders and
 * bubbles changes up through `onChange`. `null` means ASAP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryTimeCard(
    scheduledFor: LocalDateTime?,
    onChange: (LocalDateTime?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Delivery time",
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePill(
                title = "ASAP",
                subtitle = "30–45 min",
                isSelected = scheduledFor == null,
                modifier = Modifier.weight(1f),
                onClick = { onChange(null) },
            )
            TimePill(
                title = "Schedule",
                subtitle = scheduledFor?.let { formatted(it) } ?: "Pick a time",
                isSelected = scheduledFor != null,
                modifier = Modifier.weight(1f),
                onClick = { showPicker = true },
            )
        }
    }

    if (showPicker) {
        ScheduledTimePickerDialog(
            initial = scheduledFor ?: LocalDateTime.now().plusHours(1),
            onDismiss = { showPicker = false },
            onConfirm = {
                onChange(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun TimePill(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Orange else SurfaceDark)
            .border(
                BorderStroke(1.dp, if (isSelected) Orange else SurfaceDarkBorder),
                RoundedCornerShape(12.dp),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $subtitle${if (isSelected) ", selected" else ""}"
                selected = isSelected
                role = Role.Tab
            }
            .clickable(onClickLabel = "Select $title") { onClick() }
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = if (isSelected) TextWhite else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = subtitle,
                color = if (isSelected) TextWhite.copy(alpha = 0.85f) else TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Combined date-then-time picker. Material3 doesn't have a single "date +
 * time" composable, so we show a date picker first, then a time picker
 * once the date is chosen. Confirm on the time step fires `onConfirm`.
 *
 * Minimum allowed: now + 30 minutes (scheduled orders less than that
 * should just use ASAP — backend treats anything within 30 min as
 * immediate anyway).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTimePickerDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    var pickedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    if (pickedDate == null) {
        val initialMillis = initial.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            // Disallow past days. Anything "today or later" passes; we
            // validate the combined date+time on confirm.
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = java.time.Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneId.of("UTC")).toLocalDate()
                    return !day.isBefore(java.time.LocalDate.now(ZoneId.systemDefault()))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        pickedDate = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                }) { Text("Next", color = Orange) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
            },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = false,
        )
        Dialog(onDismissRequest = onDismiss) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Select a time", color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    TimePicker(state = timeState)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val date = pickedDate!!
                                val combined = LocalDateTime.of(
                                    date,
                                    java.time.LocalTime.of(timeState.hour, timeState.minute),
                                )
                                // Guard: if the user picked a time that's
                                // already in the past (e.g. today + earlier
                                // hour), bump it to 1h from now silently.
                                val safe =
                                    if (combined.isBefore(LocalDateTime.now().plusMinutes(30))) {
                                        LocalDateTime.now().plusHours(1)
                                    } else {
                                        combined
                                    }
                                onConfirm(safe)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        ) { Text("Confirm") }
                    }
                }
            }
        }
    }
}

private fun formatted(dt: LocalDateTime): String {
    val today = java.time.LocalDate.now()
    val isToday = dt.toLocalDate() == today
    val isTomorrow = dt.toLocalDate() == today.plusDays(1)
    val time = DateTimeFormatter.ofPattern("h:mm a").format(dt)
    return when {
        isToday -> "Today, $time"
        isTomorrow -> "Tomorrow, $time"
        else -> DateTimeFormatter.ofPattern("MMM d, h:mm a").format(dt)
    }
}
