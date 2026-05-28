package com.greeneats.seller.ui.screens.deals

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
import com.greeneats.seller.data.models.DiscountType
import com.greeneats.seller.data.models.MenuItem
import com.greeneats.seller.data.models.formatPrice
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.SurfaceDarkElevated
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.DealsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Default half-hour time slot index for deal expiry.
 * The picker has 48 slots (indices 0-47), each representing a 30-minute window:
 *   index 0 = 12:00 AM, index 1 = 12:30 AM, ... index 47 = 11:30 PM.
 * Defaulting to 47 (11:30 PM) means new deals expire at end-of-day by default.
 */
private const val DEFAULT_EXPIRY_TIME_SLOT = 47 // 11:30 PM

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

    var isGeneralDeal by remember { mutableStateOf(true) }
    var selectedItem by remember { mutableStateOf<MenuItem?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var discountType by remember { mutableStateOf(DiscountType.PERCENTAGE) }
    var discountValue by remember { mutableStateOf("") }
    var minOrderAmount by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var expiresAtMillis by remember {
        mutableStateOf<Long?>(
            Instant.now().atZone(ZoneId.of("UTC"))
                .toLocalDate().atStartOfDay(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        )
    }
    var selectedHalfHour by remember { mutableIntStateOf(DEFAULT_EXPIRY_TIME_SLOT) }
    var imageUrl by remember { mutableStateOf("") }
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
                        if (it) selectedItem = null
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

                if (selectedItem != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Orange.copy(alpha = 0.1f))
                            .border(1.dp, Orange.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedItem!!.imageUrl.isNotBlank()) {
                            AsyncImage(
                                model = selectedItem!!.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedItem!!.name, color = TextWhite, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(selectedItem!!.price.formatPrice(), color = Orange, fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { selectedItem = null },
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
                                    .clickable { selectedItem = item }
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
                    onValueChange = { newVal ->
                        discountValue = if (discountType == DiscountType.FIXED) {
                            filterDollarCents(newVal)
                        } else {
                            newVal.filter { c -> c.isDigit() }
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
                Text(
                    text = if (expiresAtMillis != null) {
                        Instant.ofEpochMilli(expiresAtMillis!!)
                            .atZone(ZoneId.of("UTC"))
                            .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    } else {
                        "Select expiration date"
                    },
                    color = if (expiresAtMillis != null) TextWhite else TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (showDatePicker) {
                val todayMillis = remember {
                    Instant.now().atZone(ZoneId.of("UTC"))
                        .toLocalDate().atStartOfDay(ZoneId.of("UTC"))
                        .toInstant().toEpochMilli()
                }
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = expiresAtMillis ?: todayMillis,
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis >= todayMillis
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
                )
            }

            // Error message
            state.error?.let {
                Text(
                    text = it,
                    color = com.greeneats.seller.ui.theme.ErrorRed,
                    fontSize = 13.sp,
                )
            }

            val discountInt = discountValue.toIntOrNull() ?: 0
            val hasDiscountValue = discountType == DiscountType.BOGO
                    || discountInt > 0
            val percentageInRange = discountType != DiscountType.PERCENTAGE
                    || discountInt in 1..100
            val minOrderValid = minOrderAmount.isBlank()
                    || dollarsToCents(minOrderAmount) > 0
            val canCreate = title.isNotBlank()
                    && hasDiscountValue
                    && percentageInRange
                    && minOrderValid
                    && expiresAtMillis != null
                    && !state.isCreating
                    && !isUploadingImage
                    && (isGeneralDeal || selectedItem != null)

            val validationHint = when {
                title.isBlank() -> "Enter a deal title"
                !hasDiscountValue -> "Enter a discount value"
                !percentageInRange -> "Percentage must be between 1 and 100"
                !minOrderValid -> "Min order amount must be greater than \$0"
                !isGeneralDeal && selectedItem == null -> "Select a menu item"
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
                        DiscountType.PERCENTAGE -> discountValue.toIntOrNull() ?: 0
                        DiscountType.FIXED -> dollarsToCents(discountValue)
                        DiscountType.BOGO, DiscountType.UNKNOWN -> 0
                    }

                    val minOrderCents = dollarsToCents(minOrderAmount).let {
                        if (it > 0) it else null
                    }

                    viewModel.createDeal(
                        title = title,
                        description = description,
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
                    .clickable { onSelected(index) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) Orange else TextSecondary,
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

private fun dollarsToCents(dollars: String): Int {
    if (dollars.isBlank()) return 0
    val amount = dollars.toDoubleOrNull() ?: return 0
    return (amount * 100).toInt()
}

private suspend fun uploadDealImage(
    context: android.content.Context,
    uri: Uri,
    viewModel: DealsViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("deal", contentType)
            ?: return@withContext null

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use { if (it.isSuccessful) presignResponse.publicUrl else null }
    } catch (_: Exception) {
        null
    }
}
