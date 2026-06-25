package com.koshereats.seller.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.seller.data.models.MenuImport
import com.koshereats.seller.data.models.MenuItem
import com.koshereats.seller.data.models.SellerMenuCategory
import com.koshereats.seller.data.models.formatPrice
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.SurfaceDarkElevated
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.MenuViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuManagementScreen(
    onAddItem: () -> Unit,
    onEditItem: (String) -> Unit,
    viewModel: MenuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local, client-side filter over the already-loaded category items. Mirrors iOS's
    // .searchable on name/description so a seller can find and 86 one item fast during
    // a rush instead of scrolling the whole list. No ViewModel round-trip needed.
    var searchQuery by remember { mutableStateOf("") }
    val visibleItems = remember(state.items, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            state.items
        } else {
            state.items.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
        }
    }

    // Surface action errors (failed toggle/delete) as a transient toast. When the menu
    // list is already populated, optimistic-update reverts would otherwise be silent.
    LaunchedEffect(state.error) {
        if (state.error != null && state.items.isNotEmpty()) {
            Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    val categories: List<Pair<SellerMenuCategory?, String>> =
        listOf(null to "All") + state.categories.map { cat -> cat to cat.name }

    Scaffold(
        containerColor = BackgroundBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddItem,
                containerColor = Orange,
                contentColor = TextWhite,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add menu item")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Header
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Menu",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                )
                Text(
                    text = "${state.items.size} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            // Import-status banner — mirrors iOS's Menu-tab import banner. Shows
            // progress while an UberEats import is in flight, then a dismissible
            // success/failure summary.
            state.latestImport?.let { import ->
                ImportStatusBanner(
                    import = import,
                    onDismiss = { viewModel.dismissImportBanner() },
                )
            }

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = {
                    Text("Search menu items", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Clear search",
                                tint = TextMuted,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    cursorColor = Orange,
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedPlaceholderColor = TextMuted,
                    unfocusedPlaceholderColor = TextMuted,
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Category chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { (category, label) ->
                    FilterChip(
                        selected = state.selectedCategory == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SurfaceDark,
                            labelColor = TextMuted,
                            selectedContainerColor = Orange.copy(alpha = 0.2f),
                            selectedLabelColor = Orange,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = SurfaceDark,
                            selectedBorderColor = Orange.copy(alpha = 0.5f),
                            enabled = true,
                            selected = state.selectedCategory == category,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.loadMenuItems(state.selectedCategory) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Orange)
                    }
                } else if (state.error != null && state.items.isEmpty()) {
                    // Distinguish a failed load from a genuinely empty menu so a network
                    // error doesn't masquerade as "No menu items yet".
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Couldn't load your menu",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextWhite,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.error ?: "Please try again",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = { viewModel.loadMenuItems(state.selectedCategory) },
                            ) {
                                Text("Retry", color = Orange)
                            }
                        }
                    }
                } else if (state.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No menu items yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap + to add your first item",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                        }
                    }
                } else if (visibleItems.isEmpty()) {
                    // Items exist but the search query matched none of them.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No items match \"${searchQuery.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visibleItems, key = { it.id }) { item ->
                            MenuItemCard(
                                item = item,
                                isPending = item.id in state.pendingItemIds,
                                onEdit = { onEditItem(item.id) },
                                onToggleAvailability = { viewModel.toggleAvailability(item) },
                                onDelete = { viewModel.deleteMenuItem(item.id) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportStatusBanner(
    import: MenuImport,
    onDismiss: () -> Unit,
) {
    val inProgress = import.isInProgress
    val failed = import.status == "failed"
    val tint = when {
        failed -> ErrorRed
        inProgress -> Orange
        else -> SuccessGreen
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inProgress) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                if (failed) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            val title = when {
                failed -> "Menu import failed"
                inProgress -> "Importing your UberEats menu…"
                else -> "Menu import complete"
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite,
            )
            val subtitle = when {
                failed -> import.error?.takeIf { it.isNotBlank() }
                    ?: "Something went wrong. You can re-run the import from onboarding."
                inProgress -> if (import.itemsCreated > 0) {
                    "${import.itemsCreated} items added so far — this can take a few minutes."
                } else {
                    "This can take a few minutes. Items will appear here automatically."
                }
                else -> "${import.itemsCreated} items added from UberEats."
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        // Finished imports are dismissible; an in-flight one is not (it clears
        // itself once polling sees it complete).
        if (!inProgress) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuItemCard(
    item: MenuItem,
    isPending: Boolean,
    onEdit: () -> Unit,
    onToggleAvailability: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Item", color = TextWhite) },
            text = { Text("Are you sure you want to delete this item? This cannot be undone.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isPending, onClick = onEdit),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDarkElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Restaurant,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Item info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.isAvailable) TextWhite else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.isAvailable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "UNAVAILABLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceDarkElevated)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.price.formatPrice(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Orange,
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    val typeLabel = when {
                        item.isKosherPareve -> "Pareve"
                        item.isDairy -> "Dairy"
                        item.isMeat -> "Meat"
                        else -> ""
                    }
                    if (typeLabel.isNotEmpty()) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SurfaceDarkElevated)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // Toggle + delete
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Orange,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Switch(
                        checked = item.isAvailable,
                        onCheckedChange = { onToggleAvailability() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextWhite,
                            checkedTrackColor = SuccessGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = SurfaceDarkElevated,
                        ),
                    )
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete item",
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
