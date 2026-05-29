package com.greeneats.seller.ui.screens.dashboard

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.seller.data.models.Restaurant
import com.greeneats.seller.ui.viewmodels.RestaurantPickerViewModel
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite

/**
 * Bottom sheet that lets a multi-restaurant seller swap the active restaurant.
 * Ports iOS's `RestaurantPickerSheet.swift`. Picks up the list via
 * `RestaurantPickerViewModel` (Hilt-scoped so the sheet can be opened/closed
 * without losing state) and persists the choice through `SelectedRestaurant`.
 *
 * After a selection the `onChange` callback tells the caller to reload its
 * own data — seller endpoints will now carry the new `?restaurant_id=` query
 * param via the OkHttp interceptor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantPickerSheet(
    onDismiss: () -> Unit,
    onChange: () -> Unit,
    viewModel: RestaurantPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundBlack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Your restaurants",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pick which one your dashboard should show.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Orange)
                    }
                }
                state.error != null -> {
                    Text(
                        text = state.error.orEmpty(),
                        color = ErrorRed,
                        fontSize = 13.sp,
                    )
                }
                state.restaurants.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Storefront,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No restaurants yet",
                            color = TextWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "No restaurants are assigned to this seller account yet.",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
                else -> {
                    // Small list (a seller's own restaurants, usually < 10)
                    // so iterating with `item { ... }` keeps overload
                    // resolution simple vs. the `items(list, key = ...)`
                    // extension which sometimes mis-resolves in nested scopes.
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Plain for-loop avoids a `forEach` overload
                        // ambiguity inside LazyListScope. The list is small
                        // (a seller's owned restaurants) so the singular
                        // `item { ... }` per row is fine.
                        for (rest in state.restaurants) {
                            item(key = rest.id) {
                                RestaurantRow(
                                    restaurant = rest,
                                    isSelected = rest.id == state.selectedId,
                                    onClick = {
                                        viewModel.select(rest.id) {
                                            onChange()
                                            onDismiss()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestaurantRow(
    restaurant: Restaurant,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val statusText = if (restaurant.isOpen) "Open" else "Closed"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) SurfaceDark.copy(alpha = 0.9f) else SurfaceDark)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Orange.copy(alpha = 0.5f) else SurfaceDark,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(role = Role.RadioButton) { onClick() }
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${restaurant.name}, $statusText${if (isSelected) ", currently selected" else ""}"
                selected = isSelected
                role = Role.RadioButton
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Storefront,
            contentDescription = null,
            tint = if (isSelected) Orange else TextSecondary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = restaurant.name,
                color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = statusText,
                color = if (restaurant.isOpen) Orange else TextMuted,
                fontSize = 12.sp,
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = Orange,
            )
        }
    }
}
