package com.koshereats.consumer.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.formatPriceWhole
import com.koshereats.consumer.ui.components.DietaryBadge
import com.koshereats.consumer.ui.components.KosherBadge
import com.koshereats.consumer.ui.theme.*

@Composable
fun RestaurantCard(
    restaurant: Restaurant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // Image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                AsyncImage(
                    model = restaurant.imageUrl,
                    contentDescription = restaurant.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentScale = ContentScale.Crop,
                )

                // Closed overlay
                if (!restaurant.isOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(BackgroundBlack.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Currently Closed",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }

                // Delivery time chip
                if (restaurant.isOpen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundBlack.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "${restaurant.deliveryTimeMin}-${restaurant.deliveryTimeMax} min",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Content
            Column(modifier = Modifier.padding(12.dp)) {
                // Name + Rating row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = restaurant.name,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Orange,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = String.format("%.1f", restaurant.rating),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = " (${restaurant.reviewCount})",
                            color = TextTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Cuisine types
                Text(
                    text = restaurant.cuisineTypes.joinToString(" - ") { it.displayName },
                    color = TextTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Kosher badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KosherBadge(certification = restaurant.kosherCertification)
                    DietaryBadge(dietaryType = restaurant.dietaryType)
                    if (restaurant.isGlattKosher) {
                        com.koshereats.consumer.ui.components.GlattBadge()
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Delivery info row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeliveryDining,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (restaurant.deliveryFee == 0) "Free delivery" else restaurant.deliveryFee.formatPrice(),
                        color = if (restaurant.deliveryFee == 0) SuccessGreen else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = if (restaurant.deliveryFee == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )

                    if (restaurant.minimumOrder > 0) {
                        Text(
                            text = "  -  Min ${restaurant.minimumOrder.formatPriceWhole()}",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }

                    restaurant.distance?.let { dist ->
                        Text(
                            text = "  -  ${String.format("%.1f", dist)} mi",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FeaturedRestaurantCard(
    restaurant: Restaurant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(280.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                AsyncImage(
                    model = restaurant.imageUrl,
                    contentDescription = restaurant.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Orange)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Featured",
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = restaurant.name,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Orange,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = " ${String.format("%.1f", restaurant.rating)}",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KosherBadge(certification = restaurant.kosherCertification)
                    DietaryBadge(dietaryType = restaurant.dietaryType)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = " ${restaurant.deliveryTimeMin}-${restaurant.deliveryTimeMax} min",
                        color = TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
