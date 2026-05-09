package com.koshereats.consumer.ui.screens.cart

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.koshereats.consumer.R
import com.koshereats.consumer.data.models.Cart
import com.koshereats.consumer.data.models.CartItem
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    onBackClick: () -> Unit,
    onCheckoutClick: () -> Unit,
    onViewStore: (restaurantId: String) -> Unit,
    cartViewModel: CartViewModel,
) {
    val state by cartViewModel.uiState.collectAsStateWithLifecycle()

    // When there are multiple carts and no active restaurant selected,
    // show the multi-cart list. When the user taps "View cart" we set
    // the active restaurant and flip to the detail view.
    var showingDetail by remember { mutableStateOf(false) }

    // If only one cart exists, go straight to detail.
    val effectiveShowDetail = showingDetail || !state.hasMultipleCarts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text(
                    if (effectiveShowDetail && !state.isEmpty) state.cart.restaurantName
                    else stringResource(R.string.cart_screen_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (showingDetail && state.hasMultipleCarts) {
                        showingDetail = false
                    } else {
                        onBackClick()
                    }
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            actions = {
                if (effectiveShowDetail && !state.isEmpty) {
                    IconButton(onClick = {
                        val rid = state.cart.restaurantId
                        cartViewModel.clearCartForRestaurant(rid)
                        if (state.hasMultipleCarts) {
                            showingDetail = false
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear cart", tint = TextMuted)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.totalItemCount == 0) {
            // Empty cart
            EmptyCartView(onBackClick = onBackClick)
        } else if (effectiveShowDetail) {
            // Single cart detail
            CartDetailView(
                state = state,
                cartViewModel = cartViewModel,
                onCheckoutClick = onCheckoutClick,
            )
        } else {
            // Multi-cart list
            MultiCartListView(
                carts = state.allCarts,
                onViewCart = { cart ->
                    cartViewModel.setActiveRestaurant(cart.restaurantId)
                    showingDetail = true
                },
                onViewStore = onViewStore,
                onDeleteCart = { cart ->
                    cartViewModel.clearCartForRestaurant(cart.restaurantId)
                },
            )
        }
    }
}

@Composable
private fun EmptyCartView(onBackClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ShoppingCart,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.cart_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = TextWhite,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.cart_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(R.string.cart_browse_restaurants),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Multi-cart list ──────────────────────────────────────────

@Composable
private fun MultiCartListView(
    carts: List<Cart>,
    onViewCart: (Cart) -> Unit,
    onViewStore: (restaurantId: String) -> Unit,
    onDeleteCart: (Cart) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "${carts.size} restaurant carts",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        items(carts, key = { it.restaurantId }) { cart ->
            RestaurantCartCard(
                cart = cart,
                onViewCart = { onViewCart(cart) },
                onViewStore = { onViewStore(cart.restaurantId) },
                onDelete = { onDeleteCart(cart) },
            )
        }
    }
}

@Composable
private fun RestaurantCartCard(
    cart: Cart,
    onViewCart: () -> Unit,
    onViewStore: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Restaurant image
                if (cart.restaurantImageUrl != null) {
                    AsyncImage(
                        model = cart.restaurantImageUrl,
                        contentDescription = cart.restaurantName,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDarkElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Storefront,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                }

                // Restaurant info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cart.restaurantName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${cart.itemCount} item${if (cart.itemCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }

                // Subtotal
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = cart.subtotal.formatPrice(),
                        style = MaterialTheme.typography.titleMedium,
                        color = OrangeLight,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Item summary
            val itemSummary = cart.items.take(3).joinToString(", ") { "${it.quantity}x ${it.menuItem.name}" }
            val suffix = if (cart.items.size > 3) " ..." else ""
            Text(
                text = itemSummary + suffix,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onViewCart,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                ) {
                    Text(
                        text = "View cart",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onViewStore,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceDarkBorder),
                ) {
                    Text(
                        text = "View store",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDarkElevated),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove cart",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Single cart detail ───────────────────────────────────────

@Composable
private fun CartDetailView(
    state: com.koshereats.consumer.ui.viewmodels.CartUiState,
    cartViewModel: CartViewModel,
    onCheckoutClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Cart items
            items(state.cart.items, key = { it.id }) { cartItem ->
                CartItemRow(
                    cartItem = cartItem,
                    onIncrement = {
                        cartViewModel.updateQuantity(cartItem.id, cartItem.quantity + 1)
                    },
                    onDecrement = {
                        cartViewModel.updateQuantity(cartItem.id, cartItem.quantity - 1)
                    },
                    onRemove = {
                        cartViewModel.removeItem(cartItem.id)
                    },
                )
            }

            // Delivery time (ASAP vs schedule for later)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                DeliveryTimeCard(
                    scheduledFor = state.scheduledFor,
                    onChange = { cartViewModel.updateScheduledFor(it) },
                )
            }

            // Tip section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Add a Tip",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(0, 200, 300, 500, 800).forEach { tipAmount ->
                        val isSelected = state.tip == tipAmount
                        val isDisabled = tipAmount > 0 && tipAmount > state.subtotal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Orange else SurfaceDark)
                                .border(
                                    1.dp,
                                    if (isSelected) Orange else SurfaceDarkBorder,
                                    RoundedCornerShape(12.dp),
                                )
                                .then(
                                    if (!isDisabled) Modifier.clickable { cartViewModel.updateTip(tipAmount) }
                                    else Modifier
                                )
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (tipAmount == 0) "None" else "$${tipAmount / 100}",
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isDisabled -> TextMuted
                                    isSelected -> TextWhite
                                    else -> TextSecondary
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Price breakdown
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        PriceRow("Subtotal", state.subtotal)
                        if (state.discount > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            PriceRow(
                                label = state.appliedDeal?.title?.let { "Deal: $it" } ?: "Deal discount",
                                cents = -state.discount,
                                accent = true,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        PriceRow("Delivery fee", state.deliveryFee)
                        Spacer(modifier = Modifier.height(10.dp))
                        PriceRow("Service fee", state.serviceFee)
                        Spacer(modifier = Modifier.height(10.dp))
                        PriceRow("Tax", state.tax)
                        if (state.tip > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            PriceRow("Tip", state.tip)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = SurfaceDarkBorder)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Total",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.total.formatPrice(),
                                style = MaterialTheme.typography.titleLarge,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Place order button
        Button(
            onClick = { onCheckoutClick() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            enabled = !state.isPlacingOrder,
        ) {
            if (state.isPlacingOrder) {
                CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = "Checkout - ${state.total.formatPrice()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CartItemRow(
    cartItem: CartItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cartItem.menuItem.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val mods = cartItem.selectedCustomizations
                    .flatMap { it.selectedOptions }
                    .joinToString(", ") { it.name }
                if (mods.isNotBlank()) {
                    Text(
                        text = mods,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                cartItem.specialInstructions?.let { instructions ->
                    Text(
                        text = instructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = cartItem.totalPrice.formatPrice(),
                    style = MaterialTheme.typography.titleSmall,
                    color = OrangeLight,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Quantity controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDarkElevated)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(
                    onClick = onDecrement,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Decrease",
                        tint = TextWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Text(
                    text = cartItem.quantity.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.Center,
                )

                val atMax = cartItem.quantity >= 99
                IconButton(
                    onClick = onIncrement,
                    enabled = !atMax,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Increase",
                        tint = if (atMax) TextMuted else Orange,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, cents: Int, accent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (accent) Orange else TextTertiary,
        )
        Text(
            text = if (cents < 0) "-${(-cents).formatPrice()}" else cents.formatPrice(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (accent) Orange else TextSecondary,
            fontWeight = FontWeight.Medium,
        )
    }
}
