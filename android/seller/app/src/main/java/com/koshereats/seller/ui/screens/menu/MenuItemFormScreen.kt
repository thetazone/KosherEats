package com.koshereats.seller.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.MenuViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuItemFormScreen(
    itemId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val isEditing = itemId != null

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MenuCategory.MAINS) }
    var imageUrl by remember { mutableStateOf("") }
    var prepTime by remember { mutableStateOf("15") }
    var isPareve by remember { mutableStateOf(false) }
    var isDairy by remember { mutableStateOf(false) }
    var isMeat by remember { mutableStateOf(false) }
    var spiceLevel by remember { mutableStateOf("0") }
    var calories by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }

    // Load existing item data
    LaunchedEffect(itemId) {
        if (itemId != null) {
            viewModel.loadMenuItem(itemId)
        }
    }

    LaunchedEffect(state.selectedItem) {
        state.selectedItem?.let { item ->
            name = item.name
            description = item.description
            price = item.price.toString()
            category = item.category
            imageUrl = item.imageUrl
            prepTime = item.preparationTime.toString()
            isPareve = item.isKosherPareve
            isDairy = item.isDairy
            isMeat = item.isMeat
            spiceLevel = item.spiceLevel.toString()
            calories = item.calories?.toString() ?: ""
        }
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess != null) {
            onSaved()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Orange,
        unfocusedBorderColor = DividerColor,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextMuted,
        cursorColor = Orange,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedContainerColor = SurfaceDark,
        unfocusedContainerColor = SurfaceDark,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = if (isEditing) "Edit Item" else "New Item",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            actions = {
                if (isEditing && itemId != null) {
                    IconButton(onClick = {
                        viewModel.deleteMenuItem(itemId)
                        onBack()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ErrorRed)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                singleLine = true,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                minLines = 2,
                maxLines = 4,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Price + Prep Time row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price ($)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = prepTime,
                    onValueChange = { prepTime = it },
                    label = { Text("Prep (min)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            // Category dropdown
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it },
            ) {
                OutlinedTextField(
                    value = category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false },
                    modifier = Modifier.background(SurfaceDark),
                ) {
                    MenuCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = cat.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                                    color = TextWhite,
                                )
                            },
                            onClick = {
                                category = cat
                                categoryExpanded = false
                            },
                        )
                    }
                }
            }

            // Image URL
            OutlinedTextField(
                value = imageUrl,
                onValueChange = { imageUrl = it },
                label = { Text("Image URL") },
                singleLine = true,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Spice + Calories row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = spiceLevel,
                    onValueChange = { spiceLevel = it },
                    label = { Text("Spice (0-5)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = calories,
                    onValueChange = { calories = it },
                    label = { Text("Calories") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            // Kosher type checkboxes
            Text(
                text = "Kosher Type",
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KosherCheckbox(label = "Pareve", checked = isPareve) {
                    isPareve = it
                    if (it) { isDairy = false; isMeat = false }
                }
                KosherCheckbox(label = "Dairy", checked = isDairy) {
                    isDairy = it
                    if (it) { isPareve = false; isMeat = false }
                }
                KosherCheckbox(label = "Meat", checked = isMeat) {
                    isMeat = it
                    if (it) { isPareve = false; isDairy = false }
                }
            }

            // Error
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                )
            }

            // Save button
            Button(
                onClick = {
                    val request = UpdateMenuItemRequest(
                        name = name.trim(),
                        description = description.trim(),
                        price = price.toDoubleOrNull() ?: 0.0,
                        category = category.name.lowercase(),
                        imageUrl = imageUrl.trim().ifBlank { null },
                        isKosherPareve = isPareve,
                        isDairy = isDairy,
                        isMeat = isMeat,
                        preparationTime = prepTime.toIntOrNull() ?: 15,
                        spiceLevel = spiceLevel.toIntOrNull() ?: 0,
                        calories = calories.toIntOrNull(),
                    )
                    if (isEditing && itemId != null) {
                        viewModel.updateMenuItem(itemId, request)
                    } else {
                        viewModel.createMenuItem(request)
                    }
                },
                enabled = name.isNotBlank() && price.toDoubleOrNull() != null && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = TextWhite,
                    disabledContainerColor = Orange.copy(alpha = 0.4f),
                ),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = TextWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text = if (isEditing) "Update Item" else "Create Item",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun KosherCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Orange,
                uncheckedColor = TextMuted,
                checkmarkColor = TextWhite,
            ),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextWhite,
        )
    }
}
