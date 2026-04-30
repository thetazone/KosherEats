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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KosherFilterSheet(
    currentGlatt: Boolean,
    currentCholovYisroel: Boolean,
    currentPasYisroel: Boolean,
    currentCertifications: Set<KosherCertification>,
    allRestaurants: List<Restaurant>,
    onDismiss: () -> Unit,
    onApply: (
        glattOnly: Boolean,
        cholovYisroelOnly: Boolean,
        pasYisroelOnly: Boolean,
        certifications: Set<KosherCertification>,
    ) -> Unit,
) {
    var glatt by remember { mutableStateOf(currentGlatt) }
    var cholov by remember { mutableStateOf(currentCholovYisroel) }
    var pas by remember { mutableStateOf(currentPasYisroel) }
    var certs by remember { mutableStateOf(currentCertifications) }

    val previewCount = allRestaurants.count { r ->
        (!glatt || r.isGlattKosher) &&
            (!cholov || r.isCholovYisroel) &&
            (!pas || r.isPasYisroel) &&
            (certs.isEmpty() || r.kosherCertification in certs)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    text = "Filter",
                    color = TextWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Certifications")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.height(((KosherCertification.entries.size + 2) / 3 * 56).dp),
                        userScrollEnabled = false,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(KosherCertification.entries.toList()) { cert ->
                            CertChip(
                                cert = cert,
                                selected = cert in certs,
                                onToggle = {
                                    certs = if (cert in certs) certs - cert else certs + cert
                                },
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Dietary")
                    ToggleRow(
                        title = "Glatt only",
                        subtitle = "Only Glatt-certified meat establishments",
                        checked = glatt,
                        onChange = { glatt = it },
                    )
                    ToggleRow(
                        title = "Cholov Yisroel",
                        subtitle = "Dairy under full Yisroel supervision",
                        checked = cholov,
                        onChange = { cholov = it },
                    )
                    ToggleRow(
                        title = "Pas Yisroel",
                        subtitle = "Baked goods under full Yisroel supervision",
                        checked = pas,
                        onChange = { pas = it },
                    )
                }
            }

            HorizontalDivider(color = SurfaceDarkBorder)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundDark)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Button(
                    onClick = {
                        onApply(glatt, cholov, pas, certs)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    enabled = previewCount > 0,
                ) {
                    Text(
                        text = if (previewCount > 0) "Show $previewCount results" else "No matches",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun CertChip(cert: KosherCertification, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Orange.copy(alpha = 0.18f) else SurfaceDark)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Orange,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = cert.abbreviation,
            color = if (selected) Orange else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextTertiary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = Orange,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = SurfaceDarkElevated,
            ),
        )
    }
}
