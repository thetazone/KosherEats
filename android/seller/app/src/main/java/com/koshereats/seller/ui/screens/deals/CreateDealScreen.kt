package com.koshereats.seller.ui.screens.deals

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.seller.data.models.DiscountType
import com.koshereats.seller.data.models.MenuItem
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.data.util.Money
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.DealsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDealScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: DealsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isGeneralDeal by rememberSaveable { mutableStateOf(true) }
    // Persist only the id across config change / process death; re-resolve the full
    // MenuItem against the (re)loaded list so a rotation doesn't clear the picker.
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedItem = state.menuItems.firstOrNull { it.id == selectedItemId }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var discountType by rememberSaveable { mutableStateOf(DiscountType.PERCENTAGE) }
    var discountValue by rememberSaveable { mutableStateOf("") }
    var minOrderAmount by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val todayUtcMidnightMillis = remember {
        // DatePicker expects UTC midnight of the user's *local* date; staying in
        // ZoneId.systemDefault() avoids the off-by-one-day on the day of a DST
        // transition that the prior "atStartOfDay(UTC)" caused for some locales.
        java.time.LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
    }
    // First half-hour slot that is >= now + 30 min (may exceed 47 if no slot valid today)
    val minValidSlot = remember {
        val nowLocal = Instant.now().atZone(ZoneId.systemDefault())
        val nowMinutes = nowLocal.hour * 60 + nowLocal.minute
        (nowMinutes + 59) / 30
    }
    var expiresAtMillis by rememberSaveable { mutableStateOf<Long?>(todayUtcMidnightMillis) }
    var selectedHalfHour by rememberSaveable { mutableIntStateOf(minValidSlot.coerceIn(0, 47)) }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    var isUploadingImage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadMenuItems() }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isUploadingImage = true
        scope.launch {
            val result = uploadDealImage(context, uri, viewModel)
            if (result != null) {
                imageUrl = result
            } else {
                viewModel.setError("Image upload failed. Please try again.")
            }
            isUploadingImage = false
        }
    }

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

    val timeSlots = (0..47).map { idx ->
        val hour24 = idx / 2
        val minute = if (idx % 2 == 0) "00" else "30"
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        "$hour12:$minute $amPm"
    }

    val isToday = expiresAtMillis == todayUtcMidnightMillis
    val effectiveMinSlot = if (isToday) minValidSlot else 0
    val expiresInstant = expiresAtMillis?.let { millis ->
        val utcDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
        val h = selectedHalfHour / 2
        val m = if (selectedHalfHour % 2 == 0) 0 else 30
        utcDate.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant()
    }
    val isExpiryFuture = expiresInstant != null &&
        expiresInstant.isAfter(Instant.now().plusSeconds(30L * 60))

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
            // General deal toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("General Deal", color = TextWhite, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Not tied to a specific menu item",
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = isGeneralDeal,
                    onCheckedChange = {
                        isGeneralDeal = it
                        if (it) selectedItemId = null
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = SuccessGreen,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceDarkElevated,
                    ),
                )
            }

            // Menu item picker (shown when not general)
            if (!isGeneralDeal) {
                Text(
                    text = "Select Menu Item",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )

                val currentItem = selectedItem
                if (currentItem != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Orange.copy(alpha = 0.1f))
                            .border(1.dp, Orange.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentItem.imageUrl.isNotBlank()) {
                            AsyncImage(
                                model = currentItem.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentItem.name, color = TextWhite, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentItem.price.formatPrice(), color = Orange, fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { selectedItemId = null },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                } else if (state.menuItems.isEmpty()) {
                    Text("No menu items found. Add items to your menu first.", color = TextMuted, fontSize = 13.sp)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        state.menuItems.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedItemId = item.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.imageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = item.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(
                                    item.name,
                                    color = TextWhite,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(item.price.formatPrice(), color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Deal Image
            Text(
                text = "Deal Image ${if (!isGeneralDeal && selectedItem != null) "(uses item image if blank)" else "(optional)"}",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, SurfaceDarkElevated, RoundedCornerShape(12.dp))
                    .clickable(enabled = !isUploadingImage) { imagePicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (isUploadingImage) {
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
                } else if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Deal image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap to add image", color = TextMuted, fontSize = 13.sp)
                    }
                }
            }

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 200) title = it },
                label = { Text("Deal Title") },
                placeholder = { Text("e.g., 20% off falafel plates", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
                singleLine = true,
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 2000) description = it },
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
                DiscountType.entries.filter { it != DiscountType.UNKNOWN }.forEach { type ->
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
                            .clickable {
                                discountType = type
                                discountValue = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when (type) {
                                DiscountType.PERCENTAGE -> "% Off"
                                DiscountType.FIXED -> "$ Off"
                                DiscountType.BOGO -> "BOGO"
                                DiscountType.UNKNOWN -> "Other"
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
                    onValueChange = onValueChange@{ newVal ->
                        if (discountType == DiscountType.FIXED) {
                            discountValue = filterDollarCents(newVal)
                        } else {
                            // Percentage is a whole number (1-100); the deal value is
                            // stored as an Int. Reject any input containing a decimal
                            // separator outright (keeping the prior value) rather than
                            // stripping it — stripping turned "20.5" into "205" → "100".
                            if (newVal.any { c -> c == '.' || c == ',' }) return@onValueChange
                            val digits = newVal.filter { c -> c.isDigit() }
                            discountValue = if ((digits.toIntOrNull() ?: 0) > 100) "100" else digits
                        }
                    },
                    label = {
                        Text(
                            when (discountType) {
                                DiscountType.PERCENTAGE -> "Discount Percentage (1-100)"
                                DiscountType.FIXED -> "Discount Amount ($)"
                                else -> "Value"
                            },
                        )
                    },
                    prefix = if (discountType == DiscountType.FIXED) {
                        { Text("$", color = TextSecondary) }
                    } else null,
                    suffix = if (discountType == DiscountType.PERCENTAGE) {
                        { Text("%", color = TextSecondary) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }

            // Min order amount (optional)
            OutlinedTextField(
                value = minOrderAmount,
                onValueChange = { minOrderAmount = filterDollarCents(it) },
                label = { Text("Min Order Amount (optional)") },
                prefix = { Text("$", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                val expiresAt = expiresAtMillis
                Text(
                    text = if (expiresAt != null) {
                        Instant.ofEpochMilli(expiresAt)
                            .atZone(ZoneId.of("UTC"))
                            .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    } else {
                        "Select expiration date"
                    },
                    color = if (expiresAt != null) TextWhite else TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (showDatePicker) {
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = expiresAtMillis ?: todayUtcMidnightMillis,
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis >= todayUtcMidnightMillis
                    },
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

            // Expiry time picker (half-hour slots)
            if (expiresAtMillis != null) {
                Text(
                    text = "Expiry Time",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                TimeSlotPicker(
                    slots = timeSlots,
                    selectedIndex = selectedHalfHour,
                    onSelected = { selectedHalfHour = it },
                    minSelectableIndex = effectiveMinSlot,
                )
            }

            // Error message
            state.error?.let {
                Text(
                    text = it,
                    color = com.koshereats.seller.ui.theme.ErrorRed,
                    fontSize = 13.sp,
                )
            }

            val hasDiscountValue = discountType == DiscountType.BOGO
                    || (discountValue.toDoubleOrNull() ?: 0.0) > 0.0
            // Fixed-amount deals are capped at $100 (10000 cents) server-side; mirror
            // that limit inline rather than surfacing a raw backend 400.
            val fixedExceedsCap = discountType == DiscountType.FIXED &&
                    dollarsToCents(discountValue) > 10000
            val canCreate = title.isNotBlank()
                    && hasDiscountValue
                    && !fixedExceedsCap
                    && isExpiryFuture
                    && !state.isCreating
                    && !isUploadingImage
                    && (isGeneralDeal || selectedItem != null)

            val validationHint = when {
                title.isBlank() -> "Enter a deal title"
                !hasDiscountValue -> "Enter a discount value"
                fixedExceedsCap -> "Fixed discount can't exceed $100"
                !isGeneralDeal && selectedItem == null -> "Select a menu item"
                !isExpiryFuture -> "Expiry must be at least 30 minutes from now"
                isUploadingImage -> "Image upload in progress…"
                else -> null
            }
            if (!canCreate && validationHint != null && !state.isCreating) {
                Text(validationHint, color = TextMuted, fontSize = 13.sp)
            }

            // Create button
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val hour = selectedHalfHour / 2
                    val minute = if (selectedHalfHour % 2 == 0) 0 else 30

                    val expiresAtStr = expiresAtMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        date.atTime(hour, minute)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    } ?: return@Button

                    val discountValueCents = when (discountType) {
                        DiscountType.PERCENTAGE -> (discountValue.toDoubleOrNull() ?: 0.0).coerceIn(1.0, 100.0).roundToInt()
                        DiscountType.FIXED -> dollarsToCents(discountValue).coerceAtLeast(1)
                        else -> 0
                    }

                    val minOrderCents = dollarsToCents(minOrderAmount).let {
                        if (it > 0) it else null
                    }

                    viewModel.createDeal(
                        title = title.trim().take(200),
                        description = description.trim().take(2000),
                        imageUrl = imageUrl,
                        menuItemId = selectedItem?.id,
                        discountType = discountType,
                        discountValue = discountValueCents,
                        minOrderAmount = minOrderCents,
                        startsAt = null,
                        expiresAt = expiresAtStr,
                    )
                },
                enabled = canCreate,
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

@Composable
private fun TimeSlotPicker(
    slots: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    minSelectableIndex: Int = 0,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        val itemWidth = 90
        val offset = (selectedIndex * itemWidth - itemWidth * 2).coerceAtLeast(0)
        scrollState.scrollTo(offset)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slots.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val enabled = index >= minSelectableIndex
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Orange.copy(alpha = 0.15f) else SurfaceDark)
                    .border(
                        width = 1.dp,
                        color = if (selected) Orange else SurfaceDarkElevated,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = enabled) { onSelected(index) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = when {
                        !enabled -> TextMuted.copy(alpha = 0.4f)
                        selected -> Orange
                        else -> TextSecondary
                    },
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun filterDollarCents(input: String): String {
    val cleaned = input.filter { it.isDigit() || it == '.' }
    val parts = cleaned.split('.')
    return when {
        parts.size > 2 -> parts[0] + "." + parts[1].take(2)
        parts.size == 2 -> parts[0] + "." + parts[1].take(2)
        else -> cleaned
    }
}

private fun dollarsToCents(dollars: String): Int = Money.parseCents(dollars) ?: 0

private val uploadClient = OkHttpClient.Builder()
    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .build()

private const val MAX_LONG_EDGE_PX = 1080

private fun compressImageUri(context: android.content.Context, uri: Uri): ByteArray? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    val longEdge = maxOf(opts.outWidth, opts.outHeight)
    var sampleSize = 1
    while (longEdge / (sampleSize * 2) >= MAX_LONG_EDGE_PX) sampleSize *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return null
    return ByteArrayOutputStream().also { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
    }.toByteArray()
}

private suspend fun uploadDealImage(
    context: android.content.Context,
    uri: Uri,
    viewModel: DealsViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val compressed = compressImageUri(context, uri) ?: return@withContext null
        val presignResponse = viewModel.presignUpload("deal", "image/jpeg")
            ?: return@withContext null

        val requestBody = object : RequestBody() {
            override fun contentType() = "image/jpeg".toMediaType()
            override fun contentLength() = compressed.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(compressed)
            }
        }
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(requestBody)
            .build()

        val response = uploadClient.newCall(request).execute()
        response.use { if (it.isSuccessful) presignResponse.publicUrl else {
            android.util.Log.w("CreateDealScreen", "Deal image upload failed: HTTP ${it.code}")
            null
        } }
    } catch (e: Exception) {
        android.util.Log.w("CreateDealScreen", "Deal image upload threw", e)
        null
    }
}
