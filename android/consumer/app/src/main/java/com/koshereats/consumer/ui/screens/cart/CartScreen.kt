package com.koshereats.consumer.ui.screens.cart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.data.models.CartItem
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CartViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    onBackClick: () -> Unit,
    onOrderPlaced: () -> Unit,
    cartViewModel: CartViewModel,
) {
    val state by cartViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text("Your Cart", color = TextWhite, fontWeight = FontWeight.Bold)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            actions = {
                if (!state.isEmpty) {
                    IconButton(onClick = { cartViewModel.clearCart() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear cart", tint = TextMuted)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.isEmpty) {
            // Empty cart
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.ShoppingCart,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(80.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your cart is empty",
                        color = TextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Browse restaurants and add items to get started",
                        color = TextTertiary,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onBackClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Browse Restaurants", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                // Restaurant name
                item {
                    Text(
                        text = state.cart.restaurantName,
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

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
                    Spacer(modifier = Modifier.height(16.dp))
                    DeliveryTimeCard(
                        scheduledFor = state.scheduledFor,
                        onChange = { cartViewModel.updateScheduledFor(it) },
                    )
                }

                // Tip section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Add a Tip",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.0, 2.0, 3.0, 5.0, 8.0).forEach { tipAmount ->
                            val isSelected = state.tip == tipAmount
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Orange else SurfaceDark)
                                    .border(
                                        1.dp,
                                        if (isSelected) Orange else SurfaceDarkBorder,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { cartViewModel.updateTip(tipAmount) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (tipAmount == 0.0) "None" else "$${tipAmount.toInt()}",
                                    color = if (isSelected) TextWhite else TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Price breakdown
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            PriceRow("Subtotal", state.subtotal)
                            Spacer(modifier = Modifier.height(8.dp))
                            PriceRow("Delivery fee", state.deliveryFee)
                            Spacer(modifier = Modifier.height(8.dp))
                            PriceRow("Service fee", state.serviceFee)
                            Spacer(modifier = Modifier.height(8.dp))
                            PriceRow("Tax", state.tax)
                            if (state.tip > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                PriceRow("Tip", state.tip)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = SurfaceDarkBorder)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Total",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                )
                                Text(
                                    text = "$${String.format("%.2f", state.total)}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Place order button
            Button(
                onClick = {
                    cartViewModel.placeOrder(
                        deliveryAddressId = "default",
                        paymentMethodId = "default",
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                enabled = !state.isPlacingOrder,
            ) {
                if (state.isPlacingOrder) {
                    CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = "Place Order - $${String.format("%.2f", state.total)}",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cartItem.menuItem.name,
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                cartItem.specialInstructions?.let { instructions ->
                    Text(
                        text = instructions,
                        color = TextTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$${String.format("%.2f", cartItem.totalPrice)}",
                    color = Orange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Quantity controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onDecrement,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SurfaceDarkElevated),
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Decrease",
                        tint = TextWhite,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Text(
                    text = cartItem.quantity.toString(),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.width(28.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                IconButton(
                    onClick = onIncrement,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Orange),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Increase",
                        tint = TextWhite,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, amount: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = TextTertiary, fontSize = 14.sp)
        Text(
            text = "$${String.format("%.2f", amount)}",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}
