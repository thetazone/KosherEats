package com.greeneats.consumer.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greeneats.consumer.data.models.Address
import com.greeneats.consumer.ui.theme.*

private val fieldColors
    @Composable get() = TextFieldDefaults.colors(
        focusedContainerColor = SurfaceDarkElevated,
        unfocusedContainerColor = SurfaceDarkElevated,
        cursorColor = Orange,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressPickerSheet(
    addresses: List<Address>,
    selectedAddress: Address?,
    onAddressSelected: (Address) -> Unit,
    onAddAddress: (Address) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var showAddForm by remember { mutableStateOf(false) }

    // Add form fields
    var newStreet by remember { mutableStateOf("") }
    var newApt by remember { mutableStateOf("") }
    var newCity by remember { mutableStateOf("") }
    var newState by remember { mutableStateOf("") }
    var newZip by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }

    val filteredAddresses = if (searchQuery.length >= 2) {
        addresses.filter {
            it.streetAddress.contains(searchQuery, ignoreCase = true) ||
            it.city.contains(searchQuery, ignoreCase = true) ||
            it.label.contains(searchQuery, ignoreCase = true)
        }
    } else {
        addresses
    }

    val homeAddress = addresses.firstOrNull { it.label.equals("Home", ignoreCase = true) }
    val workAddress = addresses.firstOrNull { it.label.equals("Work", ignoreCase = true) }
    val schoolAddress = addresses.firstOrNull { it.label.equals("School", ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextMuted.copy(alpha = 0.4f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Addresses",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search for an address", color = TextMuted) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(10.dp)),
                colors = fieldColors,
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quick label chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                homeAddress?.let { addr ->
                    item {
                        LabelChip(
                            icon = Icons.Filled.Home,
                            label = "Home",
                            subtitle = addr.streetAddress.take(18).let {
                                if (addr.streetAddress.length > 18) "$it..." else it
                            },
                            isSelected = selectedAddress?.id == addr.id,
                            onClick = {
                                onAddressSelected(addr)
                                onDismiss()
                            },
                        )
                    }
                }
                workAddress?.let { addr ->
                    item {
                        LabelChip(
                            icon = Icons.Filled.Work,
                            label = "Work",
                            subtitle = addr.streetAddress.take(18).let {
                                if (addr.streetAddress.length > 18) "$it..." else it
                            },
                            isSelected = selectedAddress?.id == addr.id,
                            onClick = {
                                onAddressSelected(addr)
                                onDismiss()
                            },
                        )
                    }
                }
                schoolAddress?.let { addr ->
                    item {
                        LabelChip(
                            icon = Icons.Filled.School,
                            label = "School",
                            subtitle = addr.streetAddress.take(18).let {
                                if (addr.streetAddress.length > 18) "$it..." else it
                            },
                            isSelected = selectedAddress?.id == addr.id,
                            onClick = {
                                onAddressSelected(addr)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = SurfaceDarkBorder)

            // Add new address button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAddForm = !showAddForm }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Orange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = Orange,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "Add new address",
                    color = Orange,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Inline add form
            AnimatedVisibility(
                visible = showAddForm,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDarkElevated.copy(alpha = 0.5f))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(
                        value = newStreet,
                        onValueChange = { newStreet = it },
                        placeholder = { Text("Street address", color = TextMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                        colors = fieldColors,
                        singleLine = true,
                    )
                    TextField(
                        value = newApt,
                        onValueChange = { newApt = it },
                        placeholder = { Text("Apt, suite, floor (optional)", color = TextMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                        colors = fieldColors,
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            value = newCity,
                            onValueChange = { newCity = it },
                            placeholder = { Text("City", color = TextMuted) },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp)),
                            colors = fieldColors,
                            singleLine = true,
                        )
                        TextField(
                            value = newState,
                            onValueChange = { newState = it.take(2).uppercase() },
                            placeholder = { Text("State", color = TextMuted) },
                            modifier = Modifier
                                .width(80.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            colors = fieldColors,
                            singleLine = true,
                        )
                    }
                    TextField(
                        value = newZip,
                        onValueChange = { newZip = it.filter { c -> c.isDigit() }.take(5) },
                        placeholder = { Text("ZIP code", color = TextMuted) },
                        modifier = Modifier
                            .width(140.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = fieldColors,
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Label (optional)",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Home", "Work", "School").forEach { option ->
                            FilterChip(
                                selected = newLabel == option,
                                onClick = { newLabel = if (newLabel == option) "" else option },
                                label = { Text(option, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Orange,
                                    selectedLabelColor = TextWhite,
                                    containerColor = SurfaceDark,
                                    labelColor = TextSecondary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = SurfaceDarkBorder,
                                    selectedBorderColor = Orange,
                                    enabled = true,
                                    selected = newLabel == option,
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (newStreet.isNotBlank() && newCity.isNotBlank() && newState.isNotBlank() && newZip.isNotBlank()) {
                                val street = if (newApt.isNotBlank()) "$newStreet, $newApt" else newStreet
                                onAddAddress(
                                    Address(
                                        label = newLabel,
                                        streetAddress = street,
                                        city = newCity,
                                        state = newState,
                                        zipCode = newZip,
                                    )
                                )
                                newStreet = ""
                                newApt = ""
                                newCity = ""
                                newState = ""
                                newZip = ""
                                newLabel = ""
                                showAddForm = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        enabled = newStreet.isNotBlank() && newCity.isNotBlank() && newState.isNotBlank() && newZip.length == 5,
                    ) {
                        Text(
                            text = "Save address",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            HorizontalDivider(color = SurfaceDarkBorder)

            // Saved addresses
            if (filteredAddresses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Saved addresses",
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))

                filteredAddresses.take(8).forEach { address ->
                    val isSelected = selectedAddress?.id == address.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAddressSelected(address)
                                onDismiss()
                            }
                            .background(
                                if (isSelected) Orange.copy(alpha = 0.08f) else Color.Transparent
                            )
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Orange.copy(alpha = 0.15f) else SurfaceDarkElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = iconForLabel(address.label),
                                contentDescription = null,
                                tint = if (isSelected) Orange else TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = address.label.ifBlank { address.streetAddress },
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            if (address.label.isNotBlank()) {
                                Text(
                                    text = "${address.streetAddress}, ${address.city}, ${address.state}",
                                    color = TextTertiary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            } else if (searchQuery.length >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No addresses found", color = TextMuted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun LabelChip(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Orange.copy(alpha = 0.12f) else SurfaceDarkElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) Orange else TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                color = if (isSelected) Orange else TextWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun iconForLabel(label: String): ImageVector = when {
    label.equals("Home", ignoreCase = true) -> Icons.Filled.Home
    label.equals("Work", ignoreCase = true) -> Icons.Filled.Work
    label.equals("School", ignoreCase = true) -> Icons.Filled.School
    else -> Icons.Filled.LocationOn
}
