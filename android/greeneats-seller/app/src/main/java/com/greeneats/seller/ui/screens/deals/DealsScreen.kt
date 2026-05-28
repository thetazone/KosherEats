package com.greeneats.seller.ui.screens.deals

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.seller.data.models.Deal
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SuccessGreen
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextSecondary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.DealsViewModel
import java.time.ZonedDateTime

private object DealsStrings {
    const val CREATE_DEAL = "Create Deal"
    const val TITLE = "Deals"
    const val SUBTITLE = "Post limited-time deals for your customers"
    const val NO_DEALS_YET = "No deals yet"
    const val TAP_TO_CREATE = "Tap + to create your first deal"
    const val DEACTIVATE_DEAL = "Deactivate deal"
    const val EXPIRED = "Expired"
    const val DEACTIVATED = "Deactivated"
    const val ACTIVE = "Active"
    /** Separator between status and expiry countdown. */
    const val STATUS_SEPARATOR = "  ·  "
}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun DealsScreen(
    onCreateDeal: () -> Unit,
    viewModel: DealsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadDeals()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = BackgroundBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateDeal,
                containerColor = Orange,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = DealsStrings.CREATE_DEAL,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = DealsStrings.TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = DealsStrings.SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Orange)
                    }
                }
            } else if (state.deals.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.LocalOffer,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = DealsStrings.NO_DEALS_YET,
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = DealsStrings.TAP_TO_CREATE,
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                items(state.deals, key = { it.id }) { deal ->
                    DealCard(
                        deal = deal,
                        onDelete = { viewModel.deactivateDeal(deal.id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun DealCard(
    deal: Deal,
    onDelete: () -> Unit,
) {
    val isExpired = try {
        ZonedDateTime.parse(deal.expiresAt).isBefore(ZonedDateTime.now())
    } catch (_: Exception) {
        false
    }

    val expiryText = try {
        val expiry = ZonedDateTime.parse(deal.expiresAt)
        val now = ZonedDateTime.now()
        if (expiry.isBefore(now)) {
            DealsStrings.EXPIRED
        } else {
            val hours = ChronoUnit.HOURS.between(now, expiry)
            if (hours < 24) "${hours}h left" else "${hours / 24}d left"
        }
    } catch (_: Exception) {
        ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Discount badge
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Orange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = deal.discountLabel,
                    color = Orange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deal.title,
                    color = if (deal.isActive && !isExpired) TextWhite else TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (deal.description.isNotBlank()) {
                    Text(
                        text = deal.description,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when {
                        !deal.isActive -> ErrorRed
                        isExpired -> TextMuted
                        else -> SuccessGreen
                    }
                    val statusText = when {
                        !deal.isActive -> DealsStrings.DEACTIVATED
                        isExpired -> DealsStrings.EXPIRED
                        else -> DealsStrings.ACTIVE
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (expiryText.isNotEmpty() && deal.isActive && !isExpired) {
                        Text(
                            text = "${DealsStrings.STATUS_SEPARATOR}$expiryText",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (deal.isActive && !isExpired) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = DealsStrings.DEACTIVATE_DEAL,
                        tint = ErrorRed.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
