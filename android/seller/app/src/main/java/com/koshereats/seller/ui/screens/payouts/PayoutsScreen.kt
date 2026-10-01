package com.koshereats.seller.ui.screens.payouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.seller.data.models.PayoutLine
import com.koshereats.seller.data.models.PayoutStatus
import com.koshereats.seller.data.models.PayoutSummary
import com.koshereats.seller.data.repository.PayoutSetupState
import com.koshereats.seller.data.repository.setupState
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusPending
import com.koshereats.seller.ui.theme.StatusPreparing
import com.koshereats.seller.ui.theme.SuccessGreen
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.util.PartnerTermsCopy
import com.koshereats.seller.ui.util.openCustomTab
import com.koshereats.seller.ui.viewmodels.PAYOUTS_ZONE
import com.koshereats.seller.ui.viewmodels.PayoutPeriod
import com.koshereats.seller.ui.viewmodels.PayoutsViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Settings → Payouts (also reached from the Dashboard prompt card). Stripe
// Connect setup + earnings summary + per-order payout history. Setup mirrors
// the courier app's PayoutsSetupScreen: POST account → GET link → Custom Tab,
// then ON_RESUME re-reads /seller/payouts/status when the seller comes back.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayoutsScreen(
    onBack: () -> Unit,
    viewModel: PayoutsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.payoutStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedLine by remember { mutableStateOf<PayoutLine?>(null) }

    // The Stripe Custom Tab is a separate Activity, so this composable never
    // leaves composition while it's open — ON_RESUME is the "came back" signal.
    // Also fires on first entry, which is what loads the status initially.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startSetup: () -> Unit = {
        scope.launch {
            viewModel.startSetup()?.let { url -> openCustomTab(context, url) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = { Text("Payouts", color = TextWhite) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "status") {
                    Spacer(Modifier.height(4.dp))
                    PayoutStatusCard(
                        status = status,
                        isStarting = state.isStartingSetup,
                        isChecking = state.isCheckingStatus,
                        onSetup = startSetup,
                    )
                    state.setupError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item(key = "summary_header") {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("Earnings")
                    Spacer(Modifier.height(10.dp))
                    PeriodChips(selected = state.period, onSelect = viewModel::selectPeriod)
                }

                item(key = "summary") {
                    SummaryCard(
                        period = state.period,
                        summary = state.summary,
                        isLoading = state.summaryLoading,
                        error = state.summaryError,
                    )
                }

                item(key = "fees") { FeeExplainerCard() }

                item(key = "history_header") {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("Payout history")
                }

                when {
                    !state.linesLoaded || (state.linesLoading && state.lines.isEmpty()) -> item(key = "history_loading") {
                        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Orange)
                        }
                    }
                    state.lines.isEmpty() && state.linesError != null -> item(key = "history_error") {
                        Text(state.linesError.orEmpty(), color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }
                    state.lines.isEmpty() -> item(key = "history_empty") {
                        Text(
                            "No payouts yet. Completed orders will show up here with a full breakdown.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    else -> items(state.lines, key = { "line_${it.id}" }) { line ->
                        PayoutLineRow(line = line, onClick = { selectedLine = line })
                    }
                }

                if (state.nextCursor != null && state.lines.isNotEmpty()) {
                    item(key = "load_more") {
                        // Composed only when scrolled near → auto-fetch the next page.
                        // The button is the manual retry if that fetch failed.
                        LaunchedEffect(state.nextCursor) { viewModel.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            } else {
                                TextButton(onClick = viewModel::loadMore) { Text("Load more", color = Orange) }
                            }
                        }
                    }
                }

                if (state.lines.isNotEmpty() && state.linesError != null) {
                    item(key = "more_error") {
                        Text(state.linesError.orEmpty(), color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item(key = "bottom_space") { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    selectedLine?.let { line ->
        PayoutLineSheet(line = line, onDismiss = { selectedLine = null })
    }
}

// ─── Status / setup ─────────────────────────────────────────

@Composable
private fun PayoutStatusCard(
    status: PayoutStatus?,
    isStarting: Boolean,
    isChecking: Boolean,
    onSetup: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isChecking) "Checking payout status…" else "Payout status unavailable",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!isChecking) {
                    SetupButton(label = "Set up payouts", isStarting = isStarting, onClick = onSetup)
                }
                return@Column
            }

            val setup = status.setupState
            val (icon, tint) = when (setup) {
                PayoutSetupState.READY -> Icons.Filled.CheckCircle to SuccessGreen
                PayoutSetupState.PENDING_VERIFICATION -> Icons.Filled.HourglassTop to StatusPending
                PayoutSetupState.NOT_SET_UP -> Icons.Filled.AccountBalance to Orange
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Stripe payouts", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        setup.label,
                        color = tint,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isChecking) {
                    CircularProgressIndicator(color = TextMuted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                when (setup) {
                    PayoutSetupState.READY ->
                        "You're paid automatically through Stripe for every completed order."
                    PayoutSetupState.PENDING_VERIFICATION ->
                        "Stripe is verifying your details. If they need anything else, continue setup below."
                    PayoutSetupState.NOT_SET_UP ->
                        "${PartnerTermsCopy.PAYOUTS_PROMPT}. Stripe securely handles your bank details — it takes about 5 minutes."
                },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            when (setup) {
                PayoutSetupState.READY -> OutlinedButton(
                    onClick = onSetup,
                    enabled = !isStarting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Update bank details", fontWeight = FontWeight.SemiBold)
                    }
                }
                PayoutSetupState.PENDING_VERIFICATION ->
                    SetupButton(label = "Continue setup", isStarting = isStarting, onClick = onSetup)
                PayoutSetupState.NOT_SET_UP ->
                    SetupButton(label = "Set up payouts", isStarting = isStarting, onClick = onSetup)
            }
        }
    }
}

@Composable
private fun SetupButton(label: String, isStarting: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isStarting,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Orange,
            contentColor = TextWhite,
            disabledContainerColor = Orange.copy(alpha = 0.3f),
            disabledContentColor = TextWhite.copy(alpha = 0.6f),
        ),
    ) {
        if (isStarting) {
            CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Summary ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChips(selected: PayoutPeriod, onSelect: (PayoutPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PayoutPeriod.entries.forEach { period ->
            val isSelected = period == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(period) },
                label = { Text(period.label, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceDark,
                    labelColor = TextMuted,
                    selectedContainerColor = Orange.copy(alpha = 0.2f),
                    selectedLabelColor = Orange,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = SurfaceDark,
                    selectedBorderColor = Orange.copy(alpha = 0.5f),
                    enabled = true,
                    selected = isSelected,
                ),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    period: PayoutPeriod,
    summary: PayoutSummary?,
    isLoading: Boolean,
    error: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (from, to) = remember(period) { period.range() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRange(from, to),
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (summary != null) {
                    Text(
                        if (summary.orders == 1) "1 order" else "${summary.orders} orders",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            when {
                summary == null && isLoading -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Orange) }

                summary == null -> Text(
                    error ?: "No earnings data for this period.",
                    color = if (error != null) ErrorRed else TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    MoneyRow("Food sales", summary.foodSubtotalCents.formatCents())
                    MoneyRow("Sales tax collected (passed to you)", summary.salesTaxCents.formatCents())
                    MoneyRow(
                        "Delivery fees + tips kept (self-delivery)",
                        (summary.deliveryFeeCents + summary.tipCents).formatCents(),
                    )
                    MoneyRow("KosherEats fees", summary.keFeeCents.formatDeduction(), valueColor = TextSecondary)
                    MoneyRow("Card processing", summary.processingFeeCents.formatDeduction(), valueColor = TextSecondary)
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "NET",
                            color = TextWhite,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            summary.netCents.formatCents(),
                            color = SuccessGreen,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "Paid ${summary.paidCents.formatCents()} · Pending ${summary.pendingCents.formatCents()}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isLoading) {
                        Text("Updating…", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeeExplainerCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(PartnerTermsCopy.FEE_EXPLAINER, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

// ─── History ────────────────────────────────────────────────

@Composable
private fun PayoutLineRow(line: PayoutLine, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatShortDate(line.completedAt),
                    color = TextWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    line.netCents.formatCents(),
                    color = TextWhite,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "${orderLabel(line)} · ${fulfillmentLabel(line.fulfillment)}",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            StatusChip(line.status)
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = payoutStatusChip(status)
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayoutLineSheet(line: PayoutLine, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundBlack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                orderLabel(line),
                color = TextWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${fulfillmentLabel(line.fulfillment)} · ${formatLongDateTime(line.completedAt)}",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            StatusChip(line.status)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            MoneyRow("Food subtotal", line.foodSubtotalCents.formatCents())
            MoneyRow("Sales tax (passed to you)", line.salesTaxCents.formatCents())
            MoneyRow("Delivery fee", line.deliveryFeeCents.formatCents())
            MoneyRow("Tip", line.tipCents.formatCents())
            MoneyRow("KosherEats fee", line.keFeeCents.formatDeduction(), valueColor = TextSecondary)
            MoneyRow("Card processing", line.processingFeeCents.formatDeduction(), valueColor = TextSecondary)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Net payout",
                    color = TextWhite,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    line.netCents.formatCents(),
                    color = SuccessGreen,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (line.fulfillment == "courier_delivery") {
                Text(
                    "On courier-delivered orders the delivery fee and tip go to the courier.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            line.paidAt?.takeIf { it.isNotBlank() }?.let {
                MoneyRow("Paid on", formatLongDateTime(it), valueColor = TextSecondary)
            }
            line.transferId?.takeIf { it.isNotBlank() }?.let {
                MoneyRow("Stripe transfer", it, valueColor = TextMuted)
            }
        }
    }
}

// ─── Shared bits ────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextWhite,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MoneyRow(label: String, value: String, valueColor: Color = TextWhite) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun payoutStatusChip(status: String): Pair<String, Color> = when (status) {
    "awaiting_account" -> "Set up payouts to receive" to Orange
    "pending" -> "Processing" to StatusPending
    "paid" -> "Paid" to SuccessGreen
    "failed" -> "Failed – we're retrying" to ErrorRed
    "reversed" -> "Reversed (refund)" to StatusPreparing
    "void" -> "Voided" to TextMuted
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Unknown" } to TextMuted
}

private fun fulfillmentLabel(fulfillment: String): String = when (fulfillment) {
    "courier_delivery" -> "Delivered by courier"
    "pickup" -> "Pickup"
    "self_delivery" -> "Self-delivery"
    else -> fulfillment.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun orderLabel(line: PayoutLine): String {
    val number = line.orderNumber?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
        ?: line.orderId.take(8).uppercase(Locale.US)
    return "Order #$number"
}

private val currency: NumberFormat get() = NumberFormat.getCurrencyInstance(Locale.US)

private fun Long.formatCents(): String = currency.format(this / 100.0)

/** Fees are shown as deductions regardless of the sign the server sends. */
private fun Long.formatDeduction(): String = if (this == 0L) 0L.formatCents() else "−" + abs(this).formatCents()

private val shortDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val longDateTimeFmt = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US)
private val rangeDayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

private fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
}

private fun formatShortDate(raw: String): String =
    parseInstant(raw)?.atZone(PAYOUTS_ZONE)?.format(shortDateFmt) ?: raw.take(10)

private fun formatLongDateTime(raw: String): String =
    parseInstant(raw)?.atZone(PAYOUTS_ZONE)?.format(longDateTimeFmt) ?: raw

private fun formatRange(from: LocalDate, to: LocalDate): String =
    if (from == to) from.format(rangeDayFmt) else "${from.format(rangeDayFmt)} – ${to.format(rangeDayFmt)}"
