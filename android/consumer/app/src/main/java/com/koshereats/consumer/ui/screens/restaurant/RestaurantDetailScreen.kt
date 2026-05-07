package com.koshereats.consumer.ui.screens.restaurant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.MenuItem
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.formatPriceWhole
import com.koshereats.consumer.ui.components.KosherInfoRow
import com.koshereats.consumer.ui.components.MenuItemDietaryDot
import com.koshereats.consumer.ui.components.MenuItemShimmer
import com.koshereats.consumer.ui.components.ShimmerBrush
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel
import com.koshereats.consumer.ui.viewmodels.RestaurantViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantDetailScreen(
    onBackClick: () -> Unit,
    onCartClick: () -> Unit,
    cartViewModel: CartViewModel,
    viewModel: RestaurantViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val cartState by cartViewModel.uiState.collectAsState()
    val totalItemCount = cartState.totalItemCount
    val restaurant = uiState.restaurant
    val listState = rememberLazyListState()
    var sheetItem by remember { mutableStateOf<MenuItem?>(null) }
    var showCertificate by remember { mutableStateOf(false) }

    val headerHeight = 260.dp
    val headerHeightPx = with(LocalDensity.current) { headerHeight.toPx() }

    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > headerHeightPx * 0.7f
        }
    }

    val topBarAlpha by animateFloatAsState(
        targetValue = if (showTopBarTitle) 1f else 0f,
        label = "topBarAlpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        if (uiState.isLoading && restaurant == null) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerHeight)
                            .background(ShimmerBrush())
                    )
                }
                items(5) {
                    MenuItemShimmer()
                }
            }
        } else if (restaurant == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = uiState.error ?: "Restaurant unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onBackClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Back")
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (totalItemCount > 0) 88.dp else 16.dp),
            ) {
                // Hero image
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerHeight)
                    ) {
                        AsyncImage(
                            model = restaurant.coverImageUrl ?: restaurant.imageUrl,
                            contentDescription = restaurant.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )

                        // Gradient overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            BackgroundBlack.copy(alpha = 0.2f),
                                            BackgroundBlack.copy(alpha = 0.9f),
                                        )
                                    )
                                )
                        )

                        // Restaurant name at bottom of hero
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                                .alpha(1f - topBarAlpha)
                        ) {
                            Text(
                                text = restaurant.name,
                                color = TextWhite,
                                style = MaterialTheme.typography.displaySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Restaurant info
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = restaurant.cuisineTypes.mapNotNull { it?.displayName }.joinToString(" • "),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextTertiary,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Kosher info
                        KosherInfoRow(
                            certification = restaurant.kosherCertification ?: KosherCertification.OTHER,
                            isGlatt = restaurant.isGlattKosher,
                            isCholovYisroel = restaurant.isCholovYisroel,
                            isPasYisroel = restaurant.isPasYisroel,
                        )

                        if (!restaurant.certifyingAgency.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Certified by: ${restaurant.certifyingAgency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }

                        restaurant.mashgiachName?.let { name ->
                            Text(
                                text = "Mashgiach: $name",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }

                        if (restaurant.kosherCertificateUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showCertificate = true },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Orange),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                            ) {
                                Icon(
                                    Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Kosher Certificate", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        if (restaurant.description.isNotBlank()) {
                            Text(
                                text = restaurant.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                lineHeight = 22.sp,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }

                // Category tabs
                if (uiState.menuCategories.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(uiState.menuCategories) { index, category ->
                                FilterChip(
                                    selected = uiState.selectedCategoryIndex == index,
                                    onClick = { viewModel.selectCategory(index) },
                                    label = { Text(category.name, style = MaterialTheme.typography.labelLarge) },
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
                                        selected = uiState.selectedCategoryIndex == index,
                                    ),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Menu items for selected category
                    val selectedCategory = uiState.menuCategories.getOrNull(uiState.selectedCategoryIndex)
                    if (selectedCategory != null) {
                        item {
                            Text(
                                text = selectedCategory.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextWhite,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            selectedCategory.description?.let { desc ->
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        items(selectedCategory.items, key = { it.id }) { menuItem ->
                            MenuItemCard(
                                menuItem = menuItem,
                                onAddToCart = { sheetItem = menuItem },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // Sticky Top Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundBlack.copy(alpha = topBarAlpha))
                    .statusBarsPadding()
                    .height(56.dp)
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (showTopBarTitle) Color.Transparent 
                            else BackgroundBlack.copy(alpha = 0.5f)
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite,
                    )
                }

                Text(
                    text = restaurant.name,
                    color = TextWhite,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(topBarAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Floating cart FAB
            AnimatedVisibility(
                visible = totalItemCount > 0,
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
                                Text(totalItemCount.toString())
                            }
                        }
                    ) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = "Cart")
                    }
                }
            }

            if (showCertificate && restaurant.kosherCertificateUrl.isNotBlank()) {
                Dialog(
                    onDismissRequest = { showCertificate = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BackgroundBlack.copy(alpha = 0.95f))
                            .clickable { showCertificate = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Kosher Certificate",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextWhite,
                                )
                                IconButton(onClick = { showCertificate = false }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextWhite)
                                }
                            }
                            AsyncImage(
                                model = restaurant.kosherCertificateUrl,
                                contentDescription = "Kosher certificate",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
            }
        }

        sheetItem?.let { item ->
            MenuItemSheet(
                menuItem = item,
                onDismiss = { sheetItem = null },
                onAddToCart = { qty, customizations, instructions ->
                    cartViewModel.addItem(
                        menuItem = item,
                        restaurantId = restaurant!!.id,
                        restaurantName = restaurant.name,
                        restaurantImageUrl = restaurant.logoUrl ?: restaurant.imageUrl,
                        quantity = qty,
                        selectedCustomizations = customizations,
                        specialInstructions = instructions,
                    )
                },
            )
        }
    }
}

@Composable
fun MenuItemCard(
    menuItem: MenuItem,
    onAddToCart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MenuItemDietaryDot(
                        isMeat = menuItem.isMeat,
                        isDairy = menuItem.isDairy,
                        isPareve = menuItem.isPareve,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = menuItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (menuItem.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = menuItem.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = menuItem.price.formatPrice(),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                    )
                    if (menuItem.isPopular) {
                        Text(
                            text = "Popular",
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Orange.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            color = Orange,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (menuItem.isSpicy) {
                        Text(text = "🌶", fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (menuItem.imageUrl != null) {
                    AsyncImage(
                        model = menuItem.imageUrl,
                        contentDescription = menuItem.name,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (menuItem.isAvailable) {
                    IconButton(
                        onClick = onAddToCart,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Orange),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add to cart",
                            tint = TextWhite,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    Text(
                        text = "Unavailable",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}
