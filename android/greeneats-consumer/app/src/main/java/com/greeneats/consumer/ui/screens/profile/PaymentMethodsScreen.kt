@file:OptIn(com.stripe.android.customersheet.ExperimentalCustomerSheetApi::class)

package com.greeneats.consumer.ui.screens.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.ui.viewmodels.PaymentMethodsViewModel
import com.stripe.android.PaymentConfiguration
import com.stripe.android.customersheet.CustomerAdapter
import com.stripe.android.customersheet.CustomerEphemeralKey
import com.stripe.android.customersheet.CustomerSheet
import com.stripe.android.customersheet.CustomerSheetResult
import com.stripe.android.customersheet.CustomerSheetResultCallback
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodsScreen(
    onBack: () -> Unit,
    vm: PaymentMethodsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    var lastResultLabel by remember { mutableStateOf<String?>(null) }

    // Initialize Stripe with the publishable key from the bundle.
    LaunchedEffect(state.bundle) {
        val bundle = state.bundle ?: return@LaunchedEffect
        if (bundle.publishableKey.isNotBlank() && !bundle.isStub) {
            PaymentConfiguration.init(context, bundle.publishableKey)
        }
    }

    val customerSheet: CustomerSheet? = remember(state.bundle, activity) {
        val bundle = state.bundle
        val act = activity
        if (bundle == null || act == null || bundle.customerId.isBlank() || bundle.isStub) return@remember null

        val adapter = CustomerAdapter.create(
            context = context,
            customerEphemeralKeyProvider = {
                CustomerAdapter.Result.success(
                    CustomerEphemeralKey.create(
                        customerId = bundle.customerId,
                        ephemeralKey = bundle.ephemeralKeySecret,
                    ),
                )
            },
            setupIntentClientSecretProvider = { _ ->
                val secret = vm.fetchSetupIntentClientSecret()
                if (secret != null) CustomerAdapter.Result.success(secret)
                else CustomerAdapter.Result.failure(
                    cause = IllegalStateException("Couldn't fetch setup intent"),
                    displayMessage = "Couldn't add a card right now",
                )
            },
        )

        CustomerSheet.create(
            activity = act,
            configuration = CustomerSheet.Configuration.builder("GreenEats")
                .googlePayEnabled(true)
                .build(),
            customerAdapter = adapter,
            callback = CustomerSheetResultCallback { result ->
                lastResultLabel = when (result) {
                    is CustomerSheetResult.Selected -> result.selection?.paymentOption?.label ?: "No card selected"
                    is CustomerSheetResult.Canceled -> result.selection?.paymentOption?.label
                    is CustomerSheetResult.Failed -> "Error: ${result.exception.localizedMessage}"
                    else -> null
                }
            },
        )
    }

    // Preselect current saved card on first ready.
    LaunchedEffect(customerSheet) {
        val sheet = customerSheet ?: return@LaunchedEffect
        scope.launch {
            val selection = sheet.retrievePaymentOptionSelection()
            if (selection is CustomerSheetResult.Selected) {
                lastResultLabel = selection.selection?.paymentOption?.label
            }
        }
    }

    Column(Modifier.fillMaxSize().background(BackgroundBlack)) {
        TopAppBar(
            title = { Text("Payment Methods", color = TextWhite) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
            state.bundle?.isStub == true -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CreditCard, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Payment methods unavailable", color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stripe isn't configured on this environment. Try again on a production build.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Icon(Icons.Filled.CreditCard, null, tint = Orange, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Manage your saved cards",
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add, remove, and pick a default payment method.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                )
                lastResultLabel?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Default: $it",
                        color = TextWhite,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { customerSheet?.present() },
                    enabled = customerSheet != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("Manage cards", fontWeight = FontWeight.Bold)
                }
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
