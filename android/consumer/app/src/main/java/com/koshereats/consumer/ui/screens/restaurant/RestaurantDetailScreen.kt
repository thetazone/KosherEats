package com.koshereats.consumer.ui.screens.restaurant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.koshereats.consumer.data.models.MenuItem
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.formatPriceWhole
import com.koshereats.consumer.ui.components.KosherInfoRow
import com.koshereats.consumer.ui.components.MenuItemDietaryDot
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel
import com.koshereats.consumer.ui.viewmodels.RestaurantViewModel

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
    val restaurant = uiState.restaurant

    Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack)) {
        if (uiState.isLoading && restaurant == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
        } else if (restaurant != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (cartState.itemCount > 0) 100.dp else 16.dp),
            ) {
                // Hero image
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
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
                                            BackgroundBlack.copy(alpha = 0.3f),
                                            BackgroundBlack.copy(alpha = 0.8f),
                                        )
                                    )
                                )
                        )

                        // Back button
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .padding(top = 40.dp, start = 8.dp)
                                .clip(CircleShape)
                                .background(BackgroundBlack.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextWhite,
                            )
                        }

                        // Restaurant name at bottom of hero
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = restaurant.name,
                                color = TextWhite,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Restaurant info
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Rating + delivery time
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${String.format("%.1f", restaurant.rating)} (${restaurant.reviewCount} reviews)",
                                    color = TextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${restaurant.deliveryTimeMin}-${restaurant.deliveryTimeMax} min",
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = restaurant.cuisineTypes.joinToString(" - ") { it.displayName },
                            color = TextTertiary,
                            fontSize = 14.sp,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Kosher info
                        KosherInfoRow(
                            certification = restaurant.kosherCertification,
                            dietaryType = restaurant.dietaryType,
                            isGlatt = restaurant.isGlattKosher,
                            isCholovYisroel = restaurant.isCholovYisroel,
                            isPasYisroel = restaurant.isPasYisroel,
                        )

                        if (!restaurant.certifyingAuthority.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Certified by: ${restaurant.certifyingAuthority}",
                                color = TextTertiary,
                                fontSize = 12.sp,
                            )
                        }

                        restaurant.mashgiachName?.let { name ->
                            Text(
                                text = "Mashgiach: $name",
                                color = TextTertiary,
                                fontSize = 12.sp,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Delivery info bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceDark)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (restaurant.deliveryFee == 0) "Free" else restaurant.deliveryFee.formatPrice(),
                                    color = if (restaurant.deliveryFee == 0) SuccessGreen else TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text("Delivery", color = TextMuted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = restaurant.minimumOrder.formatPriceWhole(),
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text("Minimum", color = TextMuted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${restaurant.deliveryTimeMin}-${restaurant.deliveryTimeMax}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text("Minutes", color = TextMuted, fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (restaurant.description.isNotBlank()) {
                            Text(
                                text = restaurant.description,
                                color = TextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
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
                                    label = { Text(category.name, fontSize = 13.sp) },
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
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Menu items for selected category
                    val selectedCategory = uiState.menuCategories.getOrNull(uiState.selectedCategoryIndex)
                    if (selectedCategory != null) {
                        item {
                            Text(
                                text = selectedCategory.name,
                                color = TextWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            selectedCategory.description?.let { desc ->
                                Text(
                                    text = desc,
                                    color = TextTertiary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        items(selectedCategory.items, key = { it.id }) { menuItem ->
                            MenuItemCard(
                                menuItem = menuItem,
                                onAddToCart = {
                                    cartViewModel.addItem(
                                        menuItem = menuItem,
                                        restaurantId = restaurant.id,
                                        restaurantName = restaurant.name,
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // Bottom cart bar
            AnimatedVisibility(
                visible = cartState.itemCount > 0,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Button(
                    onClick = onCartClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(TextWhite.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = cartState.itemCount.toString(),
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "View Cart",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                        Text(
                            text = cartState.subtotal.formatPrice(),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (menuItem.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = menuItem.description,
                        color = TextTertiary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = menuItem.price.formatPrice(),
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    if (menuItem.isPopular) {
                        Text(
                            text = "Popular",
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Orange.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Orange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (menuItem.isSpicy) {
                        Text(text = "🌶", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (menuItem.imageUrl != null) {
                    AsyncImage(
                        model = menuItem.imageUrl,
                        contentDescription = menuItem.name,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (menuItem.isAvailable) {
                    IconButton(
                        onClick = onAddToCart,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Orange),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add to cart",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Text(
                        text = "Unavailable",
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
