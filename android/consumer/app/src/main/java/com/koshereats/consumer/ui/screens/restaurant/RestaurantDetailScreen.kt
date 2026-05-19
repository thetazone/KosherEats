package com.koshereats.consumer.ui.screens.restaurant

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.LocalOffer
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import com.koshereats.consumer.data.models.Deal
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.MenuItem
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.formatPriceWhole
import com.koshereats.consumer.ui.components.KosherInfoRow
import com.koshereats.consumer.ui.components.MenuItemShimmer
import com.koshereats.consumer.ui.components.ShimmerBrush
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel
import com.koshereats.consumer.ui.viewmodels.RestaurantViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantDetailScreen(
    onBackClick: () -> Unit,
    onCartClick: () -> Unit,
    cartViewModel: CartViewModel,
    viewModel: RestaurantViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cartState by cartViewModel.uiState.collectAsStateWithLifecycle()
    val totalItemCount = cartState.totalItemCount
    val restaurant = uiState.restaurant
    val listState = rememberLazyListState()
    var sheetItem by remember { mutableStateOf<MenuItem?>(null) }
    var showCertificate by remember { mutableStateOf(false) }

    BackHandler(enabled = showCertificate) {
        showCertificate = false
    }

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
                            placeholder = ColorPainter(SurfaceDark),
                            error = ColorPainter(SurfaceDark),
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

                // Applied deal banner — shows when user came from Deals tab
                val appliedDealForThisRestaurant = cartState.carts[restaurant.id]?.appliedDeal
                if (appliedDealForThisRestaurant != null) {
                    item {
                        DealBanner(
                            deal = appliedDealForThisRestaurant,
                            currentSubtotal = cartState.carts[restaurant.id]?.subtotal ?: 0,
                            onRemove = { cartViewModel.removeDeal(restaurant.id) },
                        )
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

                // Deals section
                if (uiState.restaurantDeals.isNotEmpty()) {
                    item {
                        Text(
                            text = "Deals",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(uiState.restaurantDeals, key = { it.id }) { deal ->
                                RestaurantDealCard(
                                    deal = deal,
                                    onClick = {
                                        if (deal.hasLinkedItem) {
                                            val linkedItem = uiState.menuCategories
                                                .flatMap { it.items }
                                                .find { it.id == deal.menuItemId }
                                            if (linkedItem != null) {
                                                cartViewModel.applyDeal(deal)
                                                sheetItem = linkedItem
                                            }
                                        } else {
                                            cartViewModel.applyDeal(deal)
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
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
                        item(key = "category_header") {
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
                            VerticalMenuItemCard(
                                menuItem = menuItem,
                                onClick = { sheetItem = menuItem },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 10.dp),
                            )
                        }
                        item(key = "category_footer") {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            // Sticky Top Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundBlack.copy(alpha = topBarAlpha))
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
                                placeholder = ColorPainter(SurfaceDark),
                                error = ColorPainter(SurfaceDark),
                            )
                        }
                    }
                }
            }
        }

        val pendingDeal = cartState.pendingDealItem
        LaunchedEffect(pendingDeal, uiState.menuCategories) {
            if (pendingDeal != null && uiState.menuCategories.isNotEmpty()) {
                val linkedItem = uiState.menuCategories
                    .flatMap { it.items }
                    .find { it.id == pendingDeal.menuItemId }
                if (linkedItem != null) {
                    sheetItem = linkedItem
                }
                cartViewModel.clearPendingDealItem()
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
private fun DealBanner(
    deal: Deal,
    currentSubtotal: Int,
    onRemove: () -> Unit,
) {
    val minOrder = deal.minOrderAmount ?: 0
    val needsMore = (minOrder - currentSubtotal).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Orange.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocalOffer,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deal.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    needsMore <= 0 -> "Deal applied — discount appears at checkout"
                    minOrder > 0 && currentSubtotal > 0 ->
                        "Order: ${currentSubtotal.formatPrice()} / ${minOrder.formatPrice()} — add ${needsMore.formatPrice()} more"
                    minOrder > 0 ->
                        "Minimum ${minOrder.formatPrice()} — add items to unlock"
                    else -> "Add items to unlock this deal"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (needsMore > 0) Orange else TextTertiary,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove deal",
                tint = TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun HorizontalMenuItemCard(
    menuItem: MenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(158.dp)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(158.dp)) {
            AsyncImage(
                model = menuItem.imageUrl,
                contentDescription = menuItem.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(SurfaceDark),
                error = ColorPainter(SurfaceDark),
            )
            if (menuItem.isAvailable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(TextWhite)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add to cart",
                        tint = BackgroundBlack,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = menuItem.name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = menuItem.price.formatPrice(),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
fun VerticalMenuItemCard(
    menuItem: MenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = menuItem.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (menuItem.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = menuItem.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = menuItem.price.formatPrice(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(modifier = Modifier.size(88.dp)) {
            AsyncImage(
                model = menuItem.imageUrl,
                contentDescription = menuItem.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(SurfaceDark),
                error = ColorPainter(SurfaceDark),
            )
            if (menuItem.isAvailable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Orange)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add to cart",
                        tint = TextWhite,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RestaurantDealCard(
    deal: Deal,
    onClick: () -> Unit,
) {
    val expiryText = try {
        val expiry = ZonedDateTime.parse(deal.expiresAt)
        val hours = ChronoUnit.HOURS.between(ZonedDateTime.now(), expiry)
        when {
            hours < 1 -> "${ChronoUnit.MINUTES.between(ZonedDateTime.now(), expiry)}m left"
            hours < 24 -> "${hours}h left"
            hours < 48 -> "Ends tomorrow"
            else -> "${hours / 24}d left"
        }
    } catch (_: Exception) { "" }

    Card(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column {
            AsyncImage(
                model = deal.displayImageUrl,
                contentDescription = deal.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(SurfaceDark),
                error = ColorPainter(SurfaceDark),
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = deal.title,
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Orange.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = deal.discountBadge,
                            color = Orange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (expiryText.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = expiryText,
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
