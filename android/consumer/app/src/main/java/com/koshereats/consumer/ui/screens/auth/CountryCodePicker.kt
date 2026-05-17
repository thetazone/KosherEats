package com.koshereats.consumer.ui.screens.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.ui.theme.SurfaceDark
import com.koshereats.consumer.ui.theme.TextMuted
import com.koshereats.consumer.ui.theme.TextWhite

data class Country(val name: String, val flag: String, val dialCode: String)

val KNOWN_COUNTRIES: List<Country> = listOf(
    Country("United States", "🇺🇸", "+1"),
    Country("Canada", "🇨🇦", "+1"),
    Country("Israel", "🇮🇱", "+972"),
    Country("United Kingdom", "🇬🇧", "+44"),
    Country("France", "🇫🇷", "+33"),
    Country("Mexico", "🇲🇽", "+52"),
    Country("Australia", "🇦🇺", "+61"),
    Country("South Africa", "🇿🇦", "+27"),
    Country("Argentina", "🇦🇷", "+54"),
    Country("Brazil", "🇧🇷", "+55"),
    Country("Germany", "🇩🇪", "+49"),
)

fun countryFlagFor(dialCode: String): String =
    KNOWN_COUNTRIES.firstOrNull { it.dialCode == dialCode }?.flag ?: "🌐"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryCodePickerSheet(
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
    ) {
        Text(
            text = "Select country",
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(KNOWN_COUNTRIES) { country ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(country) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(country.flag, fontSize = 22.sp)
                    Text(
                        text = country.name,
                        color = TextWhite,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = country.dialCode,
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
