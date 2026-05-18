package com.koshereats.consumer.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.ui.components.GlattBadge
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, SurfaceDarkBorder.copy(alpha = 0.45f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                AsyncImage(
                    model = restaurant.imageUrl.ifBlank { null },
                    contentDescription = restaurant.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(SurfaceDark),
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(SurfaceDark),
                    error = ColorPainter(SurfaceDark),
                )

                // Optional logo badge overlaid on the hero photo (bottom-left).
                // Sellers upload this during onboarding when their brand mark
                // is distinct from the food photo — gives an extra at-a-glance
                // differentiator beyond the restaurant name.
                if (!restaurant.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = restaurant.logoUrl,
                        contentDescription = "${restaurant.name} logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(TextWhite),
                        placeholder = ColorPainter(SurfaceDark),
                        error = ColorPainter(SurfaceDark),
                    )
                }

                if (!restaurant.isOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = restaurant.name,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KosherBadge(certification = restaurant.kosherCertification ?: KosherCertification.OTHER)
                    if (restaurant.isGlattKosher) {
                        GlattBadge()
                    }

                    val cuisines = restaurant.cuisineTypes
                        .mapNotNull { it?.displayName }
                        .joinToString(" · ")
                    if (cuisines.isNotBlank()) {
                        Text(
                            text = cuisines,
                            color = TextTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}
