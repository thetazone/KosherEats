package com.koshereats.consumer.ui.screens.home

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.data.models.CuisineType
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel
import com.koshereats.consumer.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRestaurantClick: (String) -> Unit,
    onCartClick: () -> Unit,
    cartViewModel: CartViewModel,
    startWithSearch: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val cartState by cartViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var showFilterSheet by remember { mutableStateOf(false) }

    // Load more when reaching the end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 3 && !uiState.isLoading && uiState.hasMore
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    LaunchedEffect(startWithSearch) {
        if (startWithSearch) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
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
                            text = "KosherEats",
                            color = Orange,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Kosher food delivered to your door",
                            color = TextTertiary,
                            fontSize = 14.sp,
                        )
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
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        },
                    ) {
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(SurfaceDark),
                        ) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "Filter",
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
                        Text("Search restaurants, cuisines...", color = TextMuted)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                Spacer(modifier = Modifier.height(12.dp))
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
                            label = { Text("All", fontSize = 13.sp) },
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
                            label = { Text(cuisine.displayName, fontSize = 13.sp) },
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
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Kosher filter chips
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = uiState.filterGlattOnly,
                        onClick = { viewModel.toggleGlattFilter() },
                        label = { Text("Glatt", fontSize = 12.sp) },
                        leadingIcon = if (uiState.filterGlattOnly) {
                            { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SuccessGreen.copy(alpha = 0.2f),
                            selectedLabelColor = SuccessGreen,
                            selectedLeadingIconColor = SuccessGreen,
                            containerColor = SurfaceDark,
                            labelColor = TextTertiary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = SurfaceDarkBorder,
                            selectedBorderColor = SuccessGreen.copy(alpha = 0.5f),
                            enabled = true,
                            selected = uiState.filterGlattOnly,
                        ),
                    )
                    FilterChip(
                        selected = uiState.filterCholovYisroelOnly,
                        onClick = { viewModel.toggleCholovYisroelFilter() },
                        label = { Text("Cholov Yisroel", fontSize = 12.sp) },
                        leadingIcon = if (uiState.filterCholovYisroelOnly) {
                            { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DairyBlue.copy(alpha = 0.2f),
                            selectedLabelColor = DairyBlue,
                            selectedLeadingIconColor = DairyBlue,
                            containerColor = SurfaceDark,
                            labelColor = TextTertiary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = SurfaceDarkBorder,
                            selectedBorderColor = DairyBlue.copy(alpha = 0.5f),
                            enabled = true,
                            selected = uiState.filterCholovYisroelOnly,
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Search results
            if (uiState.searchQuery.length >= 2) {
                if (uiState.isSearching) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orange)
                        }
                    }
                } else {
                    if (uiState.searchResults.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No restaurants found", color = TextMuted, fontSize = 16.sp)
                            }
                        }
                    } else {
                        items(uiState.searchResults, key = { it.id }) { restaurant ->
                            RestaurantCard(
                                restaurant = restaurant,
                                onClick = { onRestaurantClick(restaurant.id) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            } else {
                // Featured section
                if (uiState.featuredRestaurants.isNotEmpty()) {
                    item {
                        Text(
                            text = "Featured",
                            color = TextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(uiState.featuredRestaurants, key = { it.id }) { restaurant ->
                                FeaturedRestaurantCard(
                                    restaurant = restaurant,
                                    onClick = { onRestaurantClick(restaurant.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                // All restaurants
                item {
                    Text(
                        text = "All Restaurants",
                        color = TextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                items(uiState.allRestaurants, key = { it.id }) { restaurant ->
                    RestaurantCard(
                        restaurant = restaurant,
                        onClick = { onRestaurantClick(restaurant.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                if (uiState.isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orange, modifier = Modifier.size(32.dp))
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
                                Text("No restaurants available", color = TextMuted, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Pull down to refresh", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Floating cart button
        AnimatedVisibility(
            visible = cartState.itemCount > 0,
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
                            Text(cartState.itemCount.toString())
                        }
                    }
                ) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = "Cart")
                }
            }
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
