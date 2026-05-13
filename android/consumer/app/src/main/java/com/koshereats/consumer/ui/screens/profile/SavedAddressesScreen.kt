package com.koshereats.consumer.ui.screens.profile

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.consumer.data.models.Address
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.AddressViewModel

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
fun SavedAddressesScreen(
    onBack: () -> Unit,
    viewModel: AddressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddForm by remember { mutableStateOf(false) }

    // Add form fields
    var newStreet by remember { mutableStateOf("") }
    var newApt by remember { mutableStateOf("") }
    var newCity by remember { mutableStateOf("") }
    var newState by remember { mutableStateOf("") }
    var newZip by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text(
                    "Saved Addresses",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Orange)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
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
                                    viewModel.addAddress(
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

                if (state.addresses.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No saved addresses",
                                color = TextWhite,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Add an address to speed up checkout.",
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))

                    state.addresses.forEach { address ->
                        val isSelected = state.selectedAddress?.id == address.id
                        val isDefault = address.isDefault
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { viewModel.selectAddress(address) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Orange.copy(alpha = 0.08f) else SurfaceDark,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isDefault) Orange.copy(alpha = 0.15f) else SurfaceDarkElevated),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = iconForLabel(address.label),
                                        contentDescription = null,
                                        tint = if (isDefault) Orange else TextMuted,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = address.label.ifBlank { "Address" },
                                            color = TextWhite,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (isDefault) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Default",
                                                color = Orange,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${address.streetAddress}, ${address.city}, ${address.state} ${address.zipCode}",
                                        color = TextTertiary,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isDefault) viewModel.clearDefault(address.id)
                                        else viewModel.setDefault(address.id)
                                    },
                                ) {
                                    Icon(
                                        if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = if (isDefault) "Unset default" else "Make default",
                                        tint = if (isDefault) Orange else TextMuted,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteAddress(address.id) },
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = TextMuted,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
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
