package com.koshereats.consumer.ui.screens.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.ui.screens.checkout.SchedulePickerSheet
import com.koshereats.consumer.ui.theme.Orange
import com.koshereats.consumer.ui.theme.SurfaceDark
import com.koshereats.consumer.ui.theme.SurfaceDarkBorder
import com.koshereats.consumer.ui.theme.TextSecondary
import com.koshereats.consumer.ui.theme.TextWhite
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ASAP vs Schedule toggle on the cart. Two mutually exclusive pills; tapping
 * "Schedule" opens the same [SchedulePickerSheet] checkout uses, so the two
 * screens can never disagree about the earliest allowed slot.
 *
 * The parent owns `scheduledFor`; this composable renders and bubbles changes
 * up through `onChange`. `null` means ASAP. [asapSubtitle] is the restaurant's
 * quoted window ("25–40 min") when it publishes one.
 */
@Composable
fun DeliveryTimeCard(
    scheduledFor: LocalDateTime?,
    asapSubtitle: String,
    onChange: (LocalDateTime?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "When do you want it?",
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePill(
                title = "ASAP",
                subtitle = asapSubtitle,
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
        SchedulePickerSheet(
            current = scheduledFor,
            onConfirm = {
                onChange(it)
                showPicker = false
            },
            onAsap = {
                onChange(null)
                showPicker = false
            },
            onDismiss = { showPicker = false },
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
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = TextWhite,
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
