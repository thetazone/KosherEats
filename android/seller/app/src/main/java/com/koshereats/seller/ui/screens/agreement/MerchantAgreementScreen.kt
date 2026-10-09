package com.koshereats.seller.ui.screens.agreement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.DividerColor
import com.koshereats.seller.ui.theme.ErrorRed
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.StatusPending
import com.koshereats.seller.ui.theme.SurfaceDark
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextSecondary
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.util.LegalUrls
import com.koshereats.seller.ui.util.PartnerTermsCopy
import com.koshereats.seller.ui.util.openCustomTab
import com.koshereats.seller.ui.viewmodels.AgreementViewModel

/**
 * Full-screen, non-dismissable Restaurant Partner Agreement gate. NavGraph
 * renders this INSTEAD of the NavHost while acceptance is required, so there is
 * no route to back out to — the only exits are Accept or Sign out.
 */
@Composable
fun MerchantAgreementScreen(
    viewModel: AgreementViewModel,
    onSignOut: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agreement = state.agreement
    val context = LocalContext.current
    val canSubmit = state.legalName.isNotBlank() && state.agreed && !state.isSubmitting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Orange.copy(alpha = 0.15f))
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Handshake, contentDescription = null, tint = Orange, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Restaurant Partner Agreement",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Please review and accept the agreement to keep selling on KosherEats.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        state.notice?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = StatusPending,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StatusPending.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Key terms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite,
                )
                KeyTerm(
                    Icons.Filled.Percent,
                    "Fees",
                    PartnerTermsCopy.FEE_SENTENCE,
                )
                KeyTerm(
                    Icons.Filled.AccountBalance,
                    "Payouts via Stripe",
                    "Your earnings are paid out automatically through Stripe to the bank account you connect.",
                )
                KeyTerm(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    "Sales tax pass-through",
                    PartnerTermsCopy.TAX_SENTENCE,
                )
                KeyTerm(
                    Icons.Filled.Wc,
                    "Bathroom access for delivery workers",
                    "You allow delivery workers to use your restroom when they're picking up orders.",
                )
                KeyTerm(
                    Icons.Filled.RestaurantMenu,
                    "Menu, pricing & kosher certification",
                    "You're responsible for the accuracy of your menu, pricing and kosher certification.",
                )
                KeyTerm(
                    Icons.Filled.EventBusy,
                    "Ending the agreement",
                    "Either party may end the agreement at any time.",
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { openCustomTab(context, agreement?.termsUrl?.takeIf { it.isNotBlank() } ?: LegalUrls.PARTNER_TERMS) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
        ) {
            Text("Read the full agreement", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (!agreement?.currentVersion.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Version ${agreement?.currentVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.legalName,
            onValueChange = viewModel::updateLegalName,
            label = { Text("Legal business name") },
            placeholder = { Text("e.g. Kosher Grill LLC") },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = Orange,
                unfocusedBorderColor = DividerColor,
                cursorColor = Orange,
                focusedLabelColor = Orange,
                unfocusedLabelColor = TextMuted,
                focusedPlaceholderColor = TextMuted,
                unfocusedPlaceholderColor = TextMuted,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    enabled = !state.isSubmitting,
                    role = Role.Checkbox,
                    onClick = { viewModel.setAgreed(!state.agreed) },
                )
                .padding(vertical = 4.dp),
        ) {
            Checkbox(
                checked = state.agreed,
                onCheckedChange = null, // the whole row toggles
                enabled = !state.isSubmitting,
                colors = CheckboxDefaults.colors(
                    checkedColor = Orange,
                    uncheckedColor = TextMuted,
                    checkmarkColor = TextWhite,
                ),
                modifier = Modifier.padding(12.dp),
            )
            Text(
                "I have read and agree to the Restaurant Partner Agreement on behalf of this business",
                style = MaterialTheme.typography.bodyMedium,
                color = TextWhite,
                modifier = Modifier.padding(top = 10.dp, end = 8.dp),
            )
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::accept,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = TextWhite,
                disabledContainerColor = Orange.copy(alpha = 0.3f),
                disabledContentColor = TextWhite.copy(alpha = 0.6f),
            ),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("Accept", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onSignOut,
            enabled = !state.isSubmitting,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Sign out", color = TextMuted)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyTerm(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextWhite)
            Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
