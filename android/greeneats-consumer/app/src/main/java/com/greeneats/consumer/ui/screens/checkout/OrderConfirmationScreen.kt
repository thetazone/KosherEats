package com.greeneats.consumer.ui.screens.checkout

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.data.models.Order
import com.greeneats.consumer.data.models.formatPrice
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.ui.viewmodels.OrderConfirmationViewModel

@Composable
fun OrderConfirmationScreen(
    orderId: String,
    onDone: () -> Unit,
    onTrack: (String) -> Unit,
    vm: OrderConfirmationViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(orderId) { vm.load(orderId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(SuccessGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Order confirmed",
                tint = SuccessGreen,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        val order = ui.order
        val isPickup = order?.isPickup == true

        Text(
            text = "Order placed!",
            color = TextWhite,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))

        // Pickup vs delivery messaging
        Text(
            text = if (isPickup) {
                "Your order has been sent to the restaurant. You'll get a notification when it's ready for pickup."
            } else {
                "We've sent the order to the restaurant. You'll get a notification once it's accepted."
            },
            color = TextTertiary,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Fulfillment type badge
        if (order != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPickup) Orange.copy(alpha = 0.15f) else SuccessGreen.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (isPickup) Icons.Filled.Check else Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (isPickup) Orange else SuccessGreen,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (isPickup) "Pickup Order" else "Delivery Order",
                    color = if (isPickup) Orange else SuccessGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        if (order == null) {
            if (ui.isLoading) {
                CircularProgressIndicator(
                    color = Orange,
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "Loading order details" },
                )
            } else {
                Text(
                    text = ui.errorMessage ?: "Couldn't load order details.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            OrderSummaryCard(order = order)
        }

        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onTrack(orderId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics {
                        contentDescription = if (isPickup) "Track pickup order" else "Track delivery order"
                    },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
            ) {
                Text(
                    text = if (isPickup) "Track Pickup" else "Track Order",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceDarkBorder),
            ) {
                Text("Done", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(order: Order) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Order summary for ${order.restaurantName}" },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(order.restaurantName, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (order.id.isNotEmpty()) {
                Text("Order #${order.id.take(8)}", color = TextTertiary, fontSize = 12.sp)
            }

            // Show fulfillment details
            if (order.isPickup) {
                Text(
                    text = "Pickup from restaurant",
                    color = Orange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else if (order.deliveryAddress.isNotEmpty()) {
                Text(
                    text = "Delivering to: ${order.deliveryAddress}",
                    color = TextTertiary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = SurfaceDarkBorder)
            Spacer(Modifier.height(12.dp))
            order.items.orEmpty().forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${item.quantity}x ${item.name}",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = (item.price * item.quantity).formatPrice(),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = SurfaceDarkBorder)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = order.total.formatPrice(),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
