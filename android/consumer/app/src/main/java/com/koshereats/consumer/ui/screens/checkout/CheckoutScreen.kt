package com.koshereats.consumer.ui.screens.checkout

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.koshereats.consumer.data.models.CartItem
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.models.PaymentSheetBundle
import com.koshereats.consumer.data.models.formatPrice
import com.koshereats.consumer.data.models.formatted
import com.koshereats.consumer.ui.theme.*
import com.koshereats.consumer.ui.viewmodels.CheckoutEvent
import com.koshereats.consumer.ui.viewmodels.CheckoutViewModel
import com.koshereats.consumer.ui.viewmodels.TipChoice
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    localCart: List<CartItem>,
    restaurantId: String,
    appliedDealId: String? = null,
    onBack: () -> Unit,
    onOrderPlaced: (Order) -> Unit,
    vm: CheckoutViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Bootstrap exactly once per screen entry
    LaunchedEffect(Unit) {
        vm.bootstrap(localCart, restaurantId, appliedDealId)
    }

    // Stripe PaymentSheet
    val paymentSheet = rememberPaymentSheet { result: PaymentSheetResult ->
        when (result) {
            is PaymentSheetResult.Completed -> vm.onPaymentResult(success = true)
            is PaymentSheetResult.Canceled -> vm.onPaymentResult(success = false)
            is PaymentSheetResult.Failed -> vm.onPaymentResult(success = false, error = result.error.localizedMessage)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm.events, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is CheckoutEvent.PresentPaymentSheet -> {
                        PaymentConfiguration.init(context, event.bundle.publishableKey)
                        val config = PaymentSheet.Configuration(
                            merchantDisplayName = "KosherEats",
                            customer = PaymentSheet.CustomerConfiguration(
                                id = event.bundle.customerId,
                                ephemeralKeySecret = event.bundle.ephemeralKeySecret,
                            ),
                            allowsDelayedPaymentMethods = true,
                        )
                        paymentSheet.presentWithPaymentIntent(
                            paymentIntentClientSecret = event.bundle.paymentIntentSecret,
                            configuration = config,
                        )
                    }
                }
            }
        }
    }

    // Fire once when the order comes back from /orders.
    LaunchedEffect(ui.placedOrder) {
        ui.placedOrder?.let(onOrderPlaced)
    }

    var showAddressSheet by remember { mutableStateOf(false) }
    var showScheduleSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
    ) {
        TopAppBar(
            title = {
                Text(
                    "Checkout", 
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextWhite
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            FulfillmentToggle(
                selected = ui.fulfillmentType,
                onSelect = vm::setFulfillmentType,
            )
            Spacer(Modifier.height(16.dp))
            if (ui.fulfillmentType == "delivery") {
                AddressCard(
                    address = ui.selectedAddress?.formatted,
                    onChangeClick = { showAddressSheet = true },
                )
                Spacer(Modifier.height(16.dp))
            }
            DeliveryTimeCard(
                scheduledFor = ui.scheduledFor,
                onAsapClick = { vm.updateScheduledFor(null) },
                onScheduleClick = { showScheduleSheet = true },
            )
            Spacer(Modifier.height(16.dp))
            if (ui.fulfillmentType == "delivery") {
                TipSelectorCard(
                    tipChoice = ui.tipChoice,
                    customTipText = ui.customTipText,
                    subtotalCents = ui.bundle?.subtotal ?: 0,
                    onSelect = vm::selectTip,
                    onCustomChange = vm::updateCustomTip,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (ui.isLoadingBundle && ui.bundle == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(28.dp))
                }
            } else {
                ui.bundle?.let { TotalsCard(it) }
            }

            ui.errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        // Sticky pay button
        val canPay = ui.bundle != null &&
            (ui.fulfillmentType == "pickup" || ui.selectedAddress != null) &&
            !ui.isProcessing
        val totalLabel = ui.bundle?.let { it.total.formatPrice() } ?: "--"
        Button(
            onClick = { vm.onPayTapped() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            enabled = canPay,
        ) {
            if (ui.isProcessing) {
                CircularProgressIndicator(color = TextWhite, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = "Pay $totalLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    if (showAddressSheet) {
        AddressPickerSheet(
            addresses = ui.addresses,
            selectedId = ui.selectedAddress?.id,
            onSelect = { addr ->
                vm.selectAddress(addr)
                showAddressSheet = false
            },
            onAdd = { addr ->
                vm.addAddress(addr)
                showAddressSheet = false
            },
            onDismiss = { showAddressSheet = false },
        )
    }

    if (showScheduleSheet) {
        SchedulePickerSheet(
            current = ui.scheduledFor,
            onConfirm = {
                vm.updateScheduledFor(it)
                showScheduleSheet = false
            },
            onAsap = {
                vm.updateScheduledFor(null)
                showScheduleSheet = false
            },
            onDismiss = { showScheduleSheet = false },
        )
    }
}

@Composable
private fun FulfillmentToggle(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FulfillmentTile(
            label = "Delivery",
            selected = selected == "delivery",
            onClick = { onSelect("delivery") },
            modifier = Modifier.weight(1f),
        )
        FulfillmentTile(
            label = "Pickup",
            selected = selected == "pickup",
            onClick = { onSelect("pickup") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FulfillmentTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(56.dp)
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, Orange, RoundedCornerShape(12.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Orange.copy(alpha = 0.12f) else SurfaceDark,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) Orange else TextWhite,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AddressCard(address: String?, onChangeClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onChangeClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Deliver to",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = Orange,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = address ?: "Set up delivery address",
                style = MaterialTheme.typography.bodyLarge,
                color = if (address != null) TextWhite else TextMuted,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DeliveryTimeCard(
    scheduledFor: LocalDateTime?,
    onAsapClick: () -> Unit,
    onScheduleClick: () -> Unit,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, h:mm a") }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Delivery Time", 
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary, 
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeChip(
                    text = "ASAP",
                    selected = scheduledFor == null,
                    onClick = onAsapClick,
                    modifier = Modifier.weight(1f),
                )
                TimeChip(
                    text = scheduledFor?.format(fmt) ?: "Schedule",
                    selected = scheduledFor != null,
                    onClick = onScheduleClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Orange else SurfaceDarkElevated)
            .border(1.dp, if (selected) Orange else SurfaceDarkBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TextWhite else TextSecondary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TipSelectorCard(
    tipChoice: TipChoice,
    customTipText: String,
    subtotalCents: Int,
    onSelect: (TipChoice) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Add a Tip", 
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary, 
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TipChoice.presets.forEach { preset ->
                    val selected = when {
                        tipChoice is TipChoice.Percent && preset is TipChoice.Percent -> tipChoice.fraction == preset.fraction
                        else -> tipChoice == preset
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Orange else SurfaceDarkElevated)
                            .border(1.dp, if (selected) Orange else SurfaceDarkBorder, RoundedCornerShape(10.dp))
                            .clickable { onSelect(preset) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = preset.label(subtotalCents),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) TextWhite else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (tipChoice is TipChoice.Custom) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = customTipText,
                    onValueChange = onCustomChange,
                    label = { Text("Custom amount ($)", color = TextTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = SurfaceDarkBorder,
                        cursorColor = Orange,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(bundle: PaymentSheetBundle) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TotalRow("Subtotal", bundle.subtotal)
            if (bundle.discount > 0) {
                Spacer(Modifier.height(8.dp))
                TotalRow("Deal discount", -bundle.discount, accent = true)
            }
            Spacer(Modifier.height(8.dp))
            TotalRow("Delivery fee", bundle.deliveryFee)
            Spacer(Modifier.height(8.dp))
            TotalRow("Service fee", bundle.serviceFee)
            Spacer(Modifier.height(8.dp))
            TotalRow("Tax", bundle.tax)
            if (bundle.tip > 0) {
                Spacer(Modifier.height(8.dp))
                TotalRow("Tip", bundle.tip)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = SurfaceDarkBorder)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Total", 
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite, 
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = bundle.total.formatPrice(),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, cents: Int, accent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
