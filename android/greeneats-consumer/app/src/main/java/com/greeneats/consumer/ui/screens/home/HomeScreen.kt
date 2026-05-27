package com.greeneats.consumer.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.data.models.CuisineType
import com.greeneats.consumer.ui.components.RestaurantCardShimmer
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.data.models.Address
import com.greeneats.consumer.ui.viewmodels.AddressViewModel
import com.greeneats.consumer.ui.viewmodels.CartViewModel
import com.greeneats.consumer.ui.viewmodels.HomeViewModel

private const val TAG = "HomeScreen"
private const val LOAD_MORE_THRESHOLD = 3

private object HomeStrings {
    const val APP_TITLE = "GreenEats"
    const val SET_DELIVERY_ADDRESS = "Set delivery address"
    const val CHANGE_ADDRESS = "Change address"
    const val FILTER = "Filter"
    const val SEARCH_PLACEHOLDER = "Search restaurants, cuisines..."
    const val CLEAR = "Clear"
    const val ALL = "All"
    const val SUGGESTED_FOR_YOU = "Suggested for you"
    const val NO_RESTAURANTS_FOUND = "No restaurants found"
    const val YOU_MIGHT_LIKE = "You might like"
    const val LOAD_MORE_RETRY = "Couldn't load more — Tap to retry"
    const val NO_RESTAURANTS_AVAILABLE = "No restaurants available"
    const val TRY_AGAIN = "Try again"
    const val CART = "Cart"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRestaurantClick: (String) -> Unit,
    onCartClick: () -> Unit,
    onRequireAuth: () -> Unit,
    isLoggedIn: Boolean,
    cartViewModel: CartViewModel,
    addressViewModel: AddressViewModel,
    startWithSearch: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cartState by cartViewModel.uiState.collectAsStateWithLifecycle()
    val addressState by addressViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showAddressSheet by remember { mutableStateOf(false) }

    // Load more when reaching the end
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - LOAD_MORE_THRESHOLD && !uiState.isLoading && uiState.hasMore
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) viewModel.loadMore()
        }
    }

    LaunchedEffect(startWithSearch) {
        if (startWithSearch) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                Log.d(TAG, "FocusRequester not yet attached, skipping requestFocus", e)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = HomeStrings.APP_TITLE,
                            color = Orange,
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                if (isLoggedIn) showAddressSheet = true
                                else onRequireAuth()
                            }
                                .padding(vertical = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = Orange,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = addressState.selectedAddress?.let { addr ->
                                    addr.label.ifBlank { addr.streetAddress }
                                } ?: HomeStrings.SET_DELIVERY_ADDRESS,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextWhite,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = HomeStrings.CHANGE_ADDRESS,
                                tint = TextTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    val activeFilterCount = (if (uiState.filterGlattOnly) 1 else 0) +
                        (if (uiState.filterCholovYisroelOnly) 1 else 0) +
                        (if (uiState.filterPasYisroelOnly) 1 else 0) +
                        uiState.filterCertifications.size
                    BadgedBox(
                        badge = {
                            if (activeFilterCount > 0) {
                                Badge(containerColor = Orange) {
                                    Text(
                                        activeFilterCount.toString(),
                                        color = TextWhite,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        },
                    ) {
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(SurfaceDarkElevated),
                        ) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = HomeStrings.FILTER,
                                tint = TextWhite,
                            )
                        }
                    }
                }
            }

            // Search bar
            item {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.search(it) },
                    placeholder = {
                        Text(HomeStrings.SEARCH_PLACEHOLDER, color = TextMuted)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(Icons.Filled.Close, contentDescription = HomeStrings.CLEAR, tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        cursorColor = Orange,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                    ),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Cuisine filters
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedCuisine == null,
                            onClick = { viewModel.selectCuisine(null) },
                            label = { Text(HomeStrings.ALL, style = MaterialTheme.typography.labelLarge) },
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
                                selected = uiState.selectedCuisine == null,
                            ),
                        )
                    }
                    items(CuisineType.entries) { cuisine ->
                        FilterChip(
                            selected = uiState.selectedCuisine == cuisine,
                            onClick = { viewModel.selectCuisine(cuisine) },
                            label = { Text(cuisine.displayName, style = MaterialTheme.typography.labelLarge) },
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
                                selected = uiState.selectedCuisine == cuisine,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Suggested restaurants (personalised alternating picks)
            if (uiState.searchQuery.length < 2) {
                val suggested = uiState.suggestedRestaurants
                if (suggested.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = HomeStrings.SUGGESTED_FOR_YOU,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextWhite,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(suggested, key = { it.id }) { r ->
                                FeaturedCard(
                                    restaurant = r,
                                    onClick = { onRestaurantClick(r.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                } else if (!uiState.isSuggestedLoading) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }

            // Search results
            if (uiState.searchQuery.length >= 2) {
                if (uiState.isSearching) {
                    items(3) {
                        RestaurantCardShimmer()
                    }
                } else {
                    if (uiState.searchResults.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(HomeStrings.NO_RESTAURANTS_FOUND, color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        items(uiState.searchResults, key = { it.id }) { restaurant ->
                            RestaurantCard(
                                restaurant = restaurant,
                                onClick = { onRestaurantClick(restaurant.id) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            } else {
                // All restaurants
                item {
                    Text(
                        text = HomeStrings.YOU_MIGHT_LIKE,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextWhite,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (uiState.isLoading && uiState.allRestaurants.isEmpty()) {
                    items(5) {
                        RestaurantCardShimmer()
                    }
                } else {
                    items(uiState.allRestaurants, key = { it.id }) { restaurant ->
                        RestaurantCard(
                            restaurant = restaurant,
                            onClick = { onRestaurantClick(restaurant.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    if (uiState.isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    // Pagination error: show inline retry when loading more fails
                    // but there are already restaurants displayed.
                    if (uiState.error != null && !uiState.isLoading && uiState.allRestaurants.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.loadMore() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange),
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(HomeStrings.LOAD_MORE_RETRY)
                                }
                            }
                        }
                    }
                }

                if (uiState.allRestaurants.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (uiState.error != null) {
                                    Text(
                                        uiState.error!!,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextMuted,
                                    )
                                } else {
                                    Text(HomeStrings.NO_RESTAURANTS_AVAILABLE, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = { viewModel.refresh() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange),
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(HomeStrings.TRY_AGAIN)
                                }
                            }
                        }
                    }
                }
            }
        }
        } // PullToRefreshBox

        // Floating cart button
        AnimatedVisibility(
            visible = cartState.totalItemCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            FloatingActionButton(
                onClick = onCartClick,
                containerColor = Orange,
                contentColor = TextWhite,
                shape = CircleShape,
            ) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = ErrorRed, contentColor = TextWhite) {
                            Text(cartState.totalItemCount.toString())
                        }
                    }
                ) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = HomeStrings.CART)
                }
            }
        }

        if (showAddressSheet) {
            AddressPickerSheet(
                addresses = addressState.addresses,
                selectedAddress = addressState.selectedAddress,
                onAddressSelected = { addressViewModel.selectAddress(it) },
                onAddAddress = { addressViewModel.addAddress(it) },
                onDismiss = { showAddressSheet = false },
            )
        }

        if (showFilterSheet) {
            KosherFilterSheet(
                currentGlatt = uiState.filterGlattOnly,
                currentCholovYisroel = uiState.filterCholovYisroelOnly,
                currentPasYisroel = uiState.filterPasYisroelOnly,
                currentCertifications = uiState.filterCertifications,
                allRestaurants = uiState.allRestaurants,
                onDismiss = { showFilterSheet = false },
                onApply = { g, c, p, certs ->
                    viewModel.applyKosherFilters(g, c, p, certs)
                },
            )
        }
    }
}
