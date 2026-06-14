package com.koshereats.consumer.ui.screens.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Schedule-for-later picker. Two-step flow (date → time) to keep it simple on
 * compact screens without a wheel-style combined picker. Minimum window is
 * 45 minutes from now; anything inside that window falls back to ASAP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePickerSheet(
    current: LocalDateTime?,
    onConfirm: (LocalDateTime) -> Unit,
    onAsap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val zone = remember { ZoneId.systemDefault() }
    val startLocal = remember(current) { current ?: LocalDateTime.now().plusMinutes(45) }

    // Material3 DatePicker works entirely in UTC start-of-day millis. Build the
    // bounds the same way so the calendar lines up with the user's local date:
    // today must be selectable and the window must reach exactly 7 local days out.
    val todayUtcMillis = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val sevenDaysMillis = LocalDate.now(zone).plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = startLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                return utcTimeMillis in todayUtcMillis..sevenDaysMillis
            }
        },
    )
    val timePickerState = rememberTimePickerState(
        initialHour = startLocal.hour,
        initialMinute = startLocal.minute,
    )
    var showTime by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = if (showTime) "Pick a time" else "Pick a date",
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (!showTime) {
                DatePicker(
                    state = datePickerState,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(
                        containerColor = BackgroundDark,
                        titleContentColor = TextWhite,
                        headlineContentColor = TextWhite,
                        weekdayContentColor = TextSecondary,
                        dayContentColor = TextWhite,
                        todayContentColor = Orange,
                        todayDateBorderColor = Orange,
                        selectedDayContainerColor = Orange,
                        selectedDayContentColor = TextWhite,
                        yearContentColor = TextWhite,
                        currentYearContentColor = Orange,
                        selectedYearContainerColor = Orange,
                    ),
                )
            } else {
                TimePicker(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = SurfaceDark,
                        clockDialSelectedContentColor = TextWhite,
                        clockDialUnselectedContentColor = TextSecondary,
                        selectorColor = Orange,
                        timeSelectorSelectedContainerColor = Orange,
                        timeSelectorUnselectedContainerColor = SurfaceDark,
                        timeSelectorSelectedContentColor = TextWhite,
                        timeSelectorUnselectedContentColor = TextSecondary,
                        periodSelectorBorderColor = SurfaceDarkBorder,
                    ),
                )
            }

            validationError?.let { error ->
                Text(
                    text = error,
                    color = ErrorRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAsap,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextWhite,
                    ),
                ) {
                    Text("ASAP instead", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        if (!showTime) {
                            validationError = null
                            showTime = true
                        } else {
                            // DatePicker.selectedDateMillis is UTC start-of-day; decode it
                            // in UTC so a NY user who taps June 11 gets June 11 (not June 10).
                            val chosenMillis = datePickerState.selectedDateMillis ?: todayUtcMillis
                            val date = Instant.ofEpochMilli(chosenMillis).atZone(ZoneOffset.UTC).toLocalDate()
                            val combined = LocalDateTime.of(
                                date,
                                java.time.LocalTime.of(timePickerState.hour, timePickerState.minute),
                            )
                            val minAllowed = LocalDateTime.now().plusMinutes(45)
                            if (combined.isBefore(minAllowed)) {
                                // Don't silently downgrade to ASAP — tell the user so they can
                                // pick a real later time instead of getting an immediate order.
                                validationError = "Pick a time at least 45 minutes from now, or choose ASAP."
                            } else {
                                validationError = null
                                onConfirm(combined)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Text(
                        text = if (!showTime) "Next" else "Confirm",
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite,
                    )
                }
            }
        }
    }
}
