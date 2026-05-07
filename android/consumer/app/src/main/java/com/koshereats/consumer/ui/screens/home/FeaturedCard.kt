package com.koshereats.consumer.ui.screens.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.ui.components.GlattBadge
import com.koshereats.consumer.ui.components.KosherBadge
import com.koshereats.consumer.ui.theme.*

@Composable
fun FeaturedCard(
    restaurant: Restaurant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(232.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = SurfaceDarkElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SurfaceDarkBorder.copy(alpha = 0.45f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                AsyncImage(
                    model = restaurant.imageUrl.ifBlank { null },
                    contentDescription = restaurant.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(SurfaceDark),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = restaurant.name,
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                            fontSize = 12.sp,
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
