package com.koshereats.consumer.ui.screens.checkout

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.koshereats.consumer.data.models.Address
import com.koshereats.consumer.data.models.formatted
import com.koshereats.consumer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressPickerSheet(
    addresses: List<Address>,
    selectedId: String?,
    onSelect: (Address) -> Unit,
    onAdd: (Address) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddForm by remember { mutableStateOf(addresses.isEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = if (showAddForm) "New Address" else "Delivery Address",
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (showAddForm) {
                AddAddressForm(
                    onCancel = {
                        if (addresses.isEmpty()) onDismiss() else showAddForm = false
                    },
                    onSubmit = onAdd,
                )
            } else {
                addresses.forEach { addr ->
                    AddressRow(
                        address = addr,
                        selected = addr.id == selectedId,
                        onClick = { onSelect(addr) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Orange, RoundedCornerShape(12.dp))
                        .clickable { showAddForm = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add a new address", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: Address,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Orange.copy(alpha = 0.12f) else SurfaceDark)
            .border(
                1.dp,
                if (selected) Orange else SurfaceDarkBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Home, contentDescription = null, tint = if (selected) Orange else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = address.label.ifEmpty { "Address" },
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = address.formatted,
                color = TextTertiary,
                fontSize = 13.sp,
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AddAddressForm(
    onCancel: () -> Unit,
    onSubmit: (Address) -> Unit,
) {
    var label by remember { mutableStateOf("Home") }
    var street by remember { mutableStateOf("") }
    var apt by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }

    val canSubmit = street.isNotBlank() && city.isNotBlank() && state.length == 2 && zip.length == 5

    Column {
        AddressField(value = label, onChange = { label = it }, placeholder = "Label (Home, Work, …)")
        AddressField(value = street, onChange = { street = it }, placeholder = "Street address")
        AddressField(value = apt, onChange = { apt = it }, placeholder = "Apt / unit (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddressField(
                value = city,
                onChange = { city = it },
                placeholder = "City",
                modifier = Modifier.weight(2f),
            )
            AddressField(
                value = state,
                onChange = { v -> if (v.length <= 2 && v.all { c -> c.isLetter() }) state = v.uppercase() },
                placeholder = "ST",
                modifier = Modifier.weight(1f),
            )
        }
        AddressField(
            value = zip,
            onChange = { v -> if (v.length <= 5 && v.all { c -> c.isDigit() }) zip = v },
            placeholder = "ZIP",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { if (it.length <= 500) notes = it },
            placeholder = { Text("Delivery instructions (optional)", color = TextMuted) },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = Orange,
                unfocusedBorderColor = SurfaceDarkBorder,
                cursorColor = Orange,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isDefault,
                onCheckedChange = { isDefault = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = Orange,
                    uncheckedColor = TextMuted,
                    checkmarkColor = TextWhite,
                ),
            )
            Text("Make this my default address", color = TextSecondary, fontSize = 14.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextWhite),
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = {
                    onSubmit(
                        Address(
                            label = label.ifBlank { "Home" },
                            streetAddress = if (apt.isNotBlank()) "${street.trim()}, ${apt.trim()}" else street.trim(),
                            city = city.trim(),
                            state = state,
                            zipCode = zip,
                            deliveryInstructions = notes.trim().ifBlank { null },
                            isDefault = isDefault,
                        )
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold, color = TextWhite)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = TextMuted) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedBorderColor = Orange,
            unfocusedBorderColor = SurfaceDarkBorder,
            cursorColor = Orange,
        ),
    )
}
