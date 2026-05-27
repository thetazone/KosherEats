package com.greeneats.consumer.ui.screens.deals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.greeneats.consumer.data.models.Deal
import com.greeneats.consumer.ui.theme.BackgroundBlack
import com.greeneats.consumer.ui.theme.Orange
import com.greeneats.consumer.ui.theme.OrangeDark
import com.greeneats.consumer.ui.theme.SurfaceDark
import com.greeneats.consumer.ui.theme.SurfaceDarkElevated
import com.greeneats.consumer.ui.theme.TextMuted
import com.greeneats.consumer.ui.theme.TextSecondary
import com.greeneats.consumer.ui.theme.TextTertiary
import com.greeneats.consumer.ui.theme.TextWhite
import com.greeneats.consumer.ui.viewmodels.DealsViewModel
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(
    onDealClick: (deal: Deal) -> Unit = {},
    viewModel: DealsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Filter out expired deals client-side
    val activeDeals = remember(state.deals) {
        state.deals.filter { deal ->
            if (!deal.isActive) return@filter false
            try {
                val expiry = ZonedDateTime.parse(deal.expiresAt)
                expiry.isAfter(ZonedDateTime.now())
            } catch (_: Exception) {
                true // keep deals with unparseable dates rather than hiding them
            }
        }
    }
    val expiredCount = state.deals.size - activeDeals.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Deals Near You",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = TextWhite,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && activeDeals.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Orange)
                    }
                }

                state.error != null && activeDeals.isEmpty() -> {
                    // Error state
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val minH = maxHeight
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = minH)
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(64.dp),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Couldn't Load Deals",
                                        color = TextWhite,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = state.error ?: "Something went wrong. Pull down to try again.",
                                        color = TextTertiary,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 20.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                activeDeals.isEmpty() -> {
                    // LazyColumn with a single full-height item -- gives PullToRefreshBox a
                    // scrollable child so pull gestures register even when there are no deals.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val minH = maxHeight
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = minH)
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.LocalOffer,
                                        contentDescription = null,
                                        tint = Orange.copy(alpha = 0.5f),
                                        modifier = Modifier.size(64.dp),
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "No Deals Right Now",
                                        color = TextWhite,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (expiredCount > 0) {
                                            "$expiredCount deal${if (expiredCount != 1) "s" else ""} recently expired. Check back soon for new offers!"
                                        } else {
                                            "Restaurants in your area will post limited-time deals here. Pull down to refresh."
                                        },
                                        color = TextTertiary,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 20.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(activeDeals, key = { it.id }) { deal ->
                            DealCard(
                                deal = deal,
                                onClick = { onDealClick(deal) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DealCard(
    deal: Deal,
    onClick: () -> Unit,
) {
    val expiryText = try {
        val expiry = ZonedDateTime.parse(deal.expiresAt)
        val now = ZonedDateTime.now()
        val hours = ChronoUnit.HOURS.between(now, expiry)
        when {
            hours < 1 -> {
                val mins = ChronoUnit.MINUTES.between(now, expiry)
                "${mins}m left"
            }
            hours < 24 -> "${hours}h left"
            hours < 48 -> "Ends tomorrow"
            else -> "${hours / 24}d left"
        }
    } catch (_: Exception) {
        ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Deal/restaurant image
            AsyncImage(
                model = deal.displayImageUrl,
                contentDescription = deal.restaurantName,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Restaurant name
                Text(
                    text = deal.restaurantName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Deal title
                Text(
                    text = deal.title,
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Description
                if (deal.description.isNotBlank()) {
                    Text(
                        text = deal.description,
                        color = TextTertiary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom row: discount badge + expiry countdown
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Discount badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Orange.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = deal.discountBadge,
                            color = Orange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Expiry countdown
                    if (expiryText.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AccessTime,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = expiryText,
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    // Min order if set
                    deal.minOrderAmount?.let { minCents ->
                        if (minCents > 0) {
                            Text(
                                text = "Min $${"%.2f".format(minCents / 100.0)}",
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
