package com.koshereats.seller.ui.screens.menu

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.seller.data.models.CreateModifierGroupRequest
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.data.models.ModifierGroup
import com.koshereats.seller.data.models.ModifierOptionRequest
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import com.koshereats.seller.data.models.formatPrice
import java.util.Locale
import kotlin.math.roundToInt
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.MenuViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MenuCategory.MAINS) }
    var imageUrl by remember { mutableStateOf("") }
    var isPareve by remember { mutableStateOf(false) }
    var isDairy by remember { mutableStateOf(false) }
    var isMeat by remember { mutableStateOf(false) }
    var spiceLevel by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isUploadingImage by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isUploadingImage = true
        scope.launch {
            val result = uploadImage(context, uri, viewModel)
            if (result != null) {
                imageUrl = result
            } else {
                viewModel.setError("Image upload failed. Please try again.")
            }
            isUploadingImage = false
        }
    }

    LaunchedEffect(itemId) {
        if (itemId == null) {
            viewModel.setSelectedItem(null)
        } else {
            viewModel.loadMenuItem(itemId)
        }
    }

    LaunchedEffect(state.selectedItem) {
        state.selectedItem?.let { item ->
            name = item.name
            description = item.description
            price = String.format(Locale.US, "%.2f", item.price / 100.0)
            category = item.category
            imageUrl = item.imageUrl
            isPareve = item.isKosherPareve
            isDairy = item.isDairy
            isMeat = item.isMeat
            spiceLevel = if (item.spiceLevel > 0) item.spiceLevel.toString() else ""
        } ?: run {
            name = ""
            description = ""
            price = ""
            category = MenuCategory.MAINS
            imageUrl = ""
            isPareve = false
            isDairy = false
            isMeat = false
            spiceLevel = ""
        }
    }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess != null) {
            viewModel.clearMessages()
            onSaved()
        }
    }

    LaunchedEffect(state.deleteSuccess) {
        if (state.deleteSuccess) {
            viewModel.clearMessages()
            onBack()
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

    if (showDeleteConfirm && itemId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Item", color = TextWhite) },
            text = { Text("Are you sure you want to delete this item? This cannot be undone.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteMenuItem(itemId)
                }) {
                    Text("Delete Item", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextWhite)
                }
            },
            containerColor = SurfaceDark,
        )
    }

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
                    IconButton(onClick = { showDeleteConfirm = true }) {
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
            // Image picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .clickable(enabled = !isUploadingImage) {
                        imagePicker.launch("image/*")
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isUploadingImage) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Uploading...", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Menu item image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = "Add photo",
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap to add photo", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text("Item Name") },
                singleLine = true,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 500) description = it },
                label = { Text("Description") },
                minLines = 2,
                maxLines = 4,
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Price
            OutlinedTextField(
                value = price,
                onValueChange = { v ->
                    val filtered = v.filter { c -> c.isDigit() || c == '.' }
                    if (filtered.count { it == '.' } <= 1) price = filtered
                },
                label = { Text("Price (\$)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

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

            // Spice level (optional)
            OutlinedTextField(
                value = spiceLevel,
                onValueChange = { v ->
                    val n = v.filter { it.isDigit() }
                    if (n.isEmpty() || (n.toIntOrNull() ?: 0) <= 5) spiceLevel = n
                },
                label = { Text("Spice Level (0-5, optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

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

            // Modifier Groups (only when editing an existing item)
            if (isEditing && itemId != null) {
                var showModifierDialog by remember { mutableStateOf(false) }
                var editingGroup by remember { mutableStateOf<ModifierGroup?>(null) }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Modifier Groups",
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = { editingGroup = null; showModifierDialog = true }) {
                        Text("+ Add", color = Orange)
                    }
                }

                val groups = state.selectedItem?.modifierGroups ?: emptyList()
                groups.forEach { group ->
                    ModifierGroupCard(
                        group = group,
                        onEdit = { editingGroup = group; showModifierDialog = true },
                        onDelete = { viewModel.deleteModifierGroup(group.id, itemId) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (showModifierDialog) {
                    ModifierGroupDialog(
                        existing = editingGroup,
                        onDismiss = { showModifierDialog = false },
                        onSave = { request ->
                            showModifierDialog = false
                            if (editingGroup != null) {
                                viewModel.updateModifierGroup(editingGroup!!.id, itemId, request)
                            } else {
                                viewModel.createModifierGroup(itemId, request)
                            }
                            editingGroup = null
                        },
                    )
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
                    val dollars = price.toDoubleOrNull() ?: 0.0
                    if (!isPareve && !isDairy && !isMeat) {
                        viewModel.setError("Select a Kosher type (Meat, Dairy, or Pareve)")
                        return@Button
                    }
                    if (dollars <= 0) {
                        viewModel.setError("Price must be greater than \$0.00")
                        return@Button
                    }
                    val request = UpdateMenuItemRequest(
                        name = name.trim(),
                        description = description.trim(),
                        price = (dollars * 100.0).roundToInt(),
                        category = category.name.lowercase(),
                        imageUrl = imageUrl.trim().ifBlank { null },
                        isKosherPareve = isPareve,
                        isDairy = isDairy,
                        isMeat = isMeat,
                        spiceLevel = spiceLevel.toIntOrNull(),
                        calories = null,
                    )
                    if (isEditing && itemId != null) {
                        viewModel.updateMenuItem(itemId, request)
                    } else {
                        viewModel.createMenuItem(request)
                    }
                },
                enabled = name.isNotBlank() && (price.toDoubleOrNull() ?: 0.0) > 0 && !state.isSaving && !isUploadingImage,
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

private suspend fun uploadImage(
    context: android.content.Context,
    uri: Uri,
    viewModel: MenuViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("menu_item", contentType)
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

@Composable
private fun ModifierGroupCard(
    group: ModifierGroup,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .clickable(onClick = onEdit)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, color = TextWhite, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (group.isRequired) "Required" else "Optional",
                    color = if (group.isRequired) Orange else TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (group.isRequired) Orange.copy(alpha = 0.15f) else SurfaceDarkElevated)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
        if (group.modifiers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            group.modifiers.forEach { mod ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(mod.name, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    if (mod.priceDelta > 0) {
                        Text("+${mod.priceDelta.formatPrice()}", color = Orange, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (group.maxSelections > 1) {
            Text(
                text = "Select ${group.minSelections}-${group.maxSelections}",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModifierGroupDialog(
    existing: ModifierGroup?,
    onDismiss: () -> Unit,
    onSave: (CreateModifierGroupRequest) -> Unit,
) {
    var groupName by remember { mutableStateOf(existing?.name ?: "") }
    var isRequired by remember { mutableStateOf(existing?.isRequired ?: false) }
    var minSel by remember { mutableStateOf((existing?.minSelections ?: 0).toString()) }
    var maxSel by remember { mutableStateOf((existing?.maxSelections ?: 1).toString()) }

    data class OptionEntry(val id: String? = null, var name: String = "", var priceDelta: String = "0.00", var isAvailable: Boolean = true)

    var options by remember {
        mutableStateOf(
            existing?.modifiers?.map {
                OptionEntry(id = it.id, name = it.name, priceDelta = String.format(Locale.US, "%.2f", it.priceDelta / 100.0), isAvailable = it.isAvailable)
            } ?: listOf(OptionEntry()),
        )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(if (existing != null) "Edit Modifier Group" else "Add Modifier Group", color = TextWhite) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    placeholder = { Text("e.g., Size, Toppings", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                    singleLine = true,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isRequired,
                        onCheckedChange = {
                            isRequired = it
                            if (it && (minSel.toIntOrNull() ?: 0) < 1) minSel = "1"
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Orange, uncheckedColor = TextMuted, checkmarkColor = TextWhite),
                    )
                    Text("Required", color = TextWhite)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSel,
                        onValueChange = { minSel = it.filter { c -> c.isDigit() } },
                        label = { Text("Min") },
                        modifier = Modifier.weight(1f),
                        colors = textFieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = maxSel,
                        onValueChange = { maxSel = it.filter { c -> c.isDigit() } },
                        label = { Text("Max") },
                        modifier = Modifier.weight(1f),
                        colors = textFieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text("Options", color = TextWhite, fontWeight = FontWeight.SemiBold)

                options.forEachIndexed { index, option ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = option.name,
                            onValueChange = { v ->
                                options = options.toMutableList().also { it[index] = option.copy(name = v) }
                            },
                            label = { Text("Name") },
                            modifier = Modifier.weight(1f),
                            colors = textFieldColors,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = option.priceDelta,
                            onValueChange = { v ->
                                val filtered = v.filter { c -> c.isDigit() || c == '.' }
                                options = options.toMutableList().also { it[index] = option.copy(priceDelta = filtered) }
                            },
                            label = { Text("+$") },
                            modifier = Modifier.width(80.dp),
                            colors = textFieldColors,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (options.size > 1) {
                            IconButton(
                                onClick = { options = options.toMutableList().also { it.removeAt(index) } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = ErrorRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                TextButton(onClick = { options = options + OptionEntry() }) {
                    Text("+ Add Option", color = Orange)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val request = CreateModifierGroupRequest(
                        name = groupName.trim(),
                        isRequired = isRequired,
                        minSelections = minSel.toIntOrNull() ?: 0,
                        maxSelections = maxSel.toIntOrNull() ?: 1,
                        modifiers = options.filter { it.name.isNotBlank() }.mapIndexed { i, opt ->
                            ModifierOptionRequest(
                                id = opt.id,
                                name = opt.name.trim(),
                                priceDelta = ((opt.priceDelta.toDoubleOrNull() ?: 0.0) * 100).toInt(),
                                isAvailable = opt.isAvailable,
                                sortOrder = i,
                            )
                        },
                    )
                    onSave(request)
                },
                enabled = groupName.isNotBlank() && options.any { it.name.isNotBlank() },
            ) {
                Text("Save", color = Orange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}
