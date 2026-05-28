package com.greeneats.consumer.ui.screens.restaurant

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greeneats.consumer.data.models.CustomizationOption
import com.greeneats.consumer.data.models.MenuItem
import com.greeneats.consumer.data.models.MenuItemCustomization
import com.greeneats.consumer.data.models.SelectedCustomization
import com.greeneats.consumer.data.models.formatPrice
import com.greeneats.consumer.ui.components.MenuItemDietaryDot
import com.greeneats.consumer.ui.theme.*

fun defaultCustomizationsFor(item: MenuItem): List<MenuItemCustomization> {
    if (item.customizations.isNotEmpty()) return item.customizations

    val removeOptions = mutableListOf(
        CustomizationOption(id = "rm_onion", name = "No Onion"),
        CustomizationOption(id = "rm_tomato", name = "No Tomato"),
    )
    val extras = mutableListOf<CustomizationOption>()

    when {
        item.isMeat -> {
            removeOptions += listOf(
                CustomizationOption(id = "rm_lettuce", name = "No Lettuce"),
                CustomizationOption(id = "rm_pickles", name = "No Pickles"),
                CustomizationOption(id = "rm_sauce", name = "No Sauce"),
            )
            extras += listOf(
                CustomizationOption(id = "ex_avocado", name = "Avocado", priceModifier = 199),
                CustomizationOption(id = "ex_jalapeno", name = "Jalapeños", priceModifier = 99),
                CustomizationOption(id = "ex_extra_meat", name = "Extra Meat", priceModifier = 399),
            )
        }
        item.isDairy -> {
            removeOptions += listOf(
                CustomizationOption(id = "rm_cheese", name = "No Cheese"),
                CustomizationOption(id = "rm_sauce", name = "No Sauce"),
                CustomizationOption(id = "rm_mushrooms", name = "No Mushrooms"),
            )
            extras += listOf(
                CustomizationOption(id = "ex_cheese", name = "Extra Cheese", priceModifier = 149),
                CustomizationOption(id = "ex_mushrooms", name = "Mushrooms", priceModifier = 99),
            )
        }
        item.isPareve -> {
            removeOptions += listOf(
                CustomizationOption(id = "rm_dressing", name = "No Dressing"),
                CustomizationOption(id = "rm_cucumber", name = "No Cucumber"),
                CustomizationOption(id = "rm_peppers", name = "No Peppers"),
            )
            extras += listOf(
                CustomizationOption(id = "ex_dressing", name = "Extra Dressing", priceModifier = 79),
                CustomizationOption(id = "ex_hummus", name = "Side Hummus", priceModifier = 149),
            )
        }
    }

    if (removeOptions.size <= 2 && extras.isEmpty()) return emptyList()

    return listOfNotNull(
        MenuItemCustomization(
            id = "customize",
            name = "Customize",
            required = false,
            maxSelections = removeOptions.size,
            options = removeOptions,
        ),
        if (extras.isNotEmpty()) MenuItemCustomization(
            id = "extras",
            name = "Extras",
            required = false,
            maxSelections = extras.size,
            options = extras,
        ) else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuItemSheet(
    menuItem: MenuItem,
    onDismiss: () -> Unit,
    onAddToCart: (quantity: Int, customizations: List<SelectedCustomization>, specialInstructions: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var quantity by remember { mutableIntStateOf(1) }
    var specialInstructions by remember { mutableStateOf("") }

    val customizations = remember { defaultCustomizationsFor(menuItem) }
    val selections = remember { mutableStateMapOf<String, MutableSet<String>>() }

    fun isSelected(groupId: String, optionId: String): Boolean =
        selections[groupId]?.contains(optionId) == true

    fun toggle(groupId: String, optionId: String) {
        val group = selections.getOrPut(groupId) { mutableSetOf() }
        if (group.contains(optionId)) group.remove(optionId) else group.add(optionId)
    }

    fun extrasPrice(): Int {
        var total = 0
        for (cust in customizations) {
            val selected = selections[cust.id] ?: continue
            for (opt in cust.options) {
                if (opt.id in selected && opt.priceModifier > 0) total += opt.priceModifier
            }
        }
        return total
    }

    val unitPrice = menuItem.price + extrasPrice()
    val totalPrice = unitPrice * quantity

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextMuted),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Item name + dietary label
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuItemDietaryDot(
                    isMeat = menuItem.isMeat,
                    isDairy = menuItem.isDairy,
                    isPareve = menuItem.isPareve,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = menuItem.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (menuItem.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = menuItem.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    lineHeight = 20.sp,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = menuItem.price.formatPrice(),
                style = MaterialTheme.typography.titleMedium,
                color = Orange,
                fontWeight = FontWeight.Bold,
            )

            // Customization groups
            for (group in customizations) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))

                for (option in group.options) {
                    val selected = isSelected(group.id, option.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Orange.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { toggle(group.id, option.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    1.5.dp,
                                    if (selected) Orange else SurfaceDarkBorder,
                                    RoundedCornerShape(6.dp),
                                )
                                .then(if (selected) Modifier.background(Orange) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "${option.name} selected",
                                    tint = TextWhite,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextWhite,
                            modifier = Modifier.weight(1f),
                        )
                        if (option.priceModifier > 0) {
                            Text(
                                text = "+${option.priceModifier.formatPrice()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                    }
                }
            }

            // Special instructions
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Special Instructions",
                style = MaterialTheme.typography.titleMedium,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = specialInstructions,
                onValueChange = { specialInstructions = it.take(500) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. extra napkins, allergies...", color = TextMuted) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = SurfaceDarkBorder,
                    cursorColor = Orange,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                ),
                maxLines = 3,
            )

            // Quantity + Add to cart
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(
                        onClick = { if (quantity > 1) quantity-- },
                        enabled = quantity > 1,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Remove,
                            contentDescription = "Decrease",
                            tint = if (quantity > 1) TextWhite else TextMuted,
                        )
                    }
                    Text(
                        text = quantity.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = { if (quantity < 99) quantity++ },
                        enabled = quantity < 99,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Increase",
                            tint = if (quantity < 99) Orange else TextMuted,
                        )
                    }
                }

                Button(
                    onClick = {
                        val selected = customizations.mapNotNull { group ->
                            val opts = selections[group.id]?.let { ids ->
                                group.options.filter { it.id in ids }
                            } ?: emptyList()
                            if (opts.isNotEmpty()) SelectedCustomization(
                                customizationId = group.id,
                                selectedOptions = opts,
                            ) else null
                        }
                        onAddToCart(
                            quantity,
                            selected,
                            specialInstructions.trim().ifBlank { null },
                        )
                        onDismiss()
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Text(
                        text = "Add  ${totalPrice.formatPrice()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
