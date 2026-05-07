package com.koshereats.consumer.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WaterDrop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.ui.theme.*

private val PasYisroelYellow = Color(0xFFFACC15)

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
    val activeFilterCount = (if (glatt) 1 else 0) +
        (if (cholov) 1 else 0) +
        (if (pas) 1 else 0) +
        certs.size

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top bar: Cancel | Filters | Clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarPill(
                    text = "Cancel",
                    color = Orange,
                    onClick = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Filters",
                    color = TextWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TopBarPill(
                    text = "Clear",
                    color = ErrorRed,
                    onClick = {
                        glatt = false
                        cholov = false
                        pas = false
                        certs = emptySet()
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Certification section
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Certification",
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Select any that work for you",
                        color = TextTertiary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    val certList = KosherCertification.entries.toList()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height(((certList.size + 1) / 2 * 64).dp),
                        userScrollEnabled = false,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(certList) { cert ->
                            CertCard(
                                cert = cert,
                                selected = cert in certs,
                                onToggle = {
                                    certs = if (cert in certs) certs - cert else certs + cert
                                },
                            )
                        }
                    }
                }

                // Dietary Standards section
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Dietary Standards",
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Stricter kashrus? Toggle what matters to you",
                        color = TextTertiary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DietaryToggleRow(
                            icon = Icons.Filled.Verified,
                            iconTint = Orange,
                            title = "Glatt Kosher",
                            subtitle = "Only Glatt-certified meat establishments",
                            checked = glatt,
                            onChange = { glatt = it },
                        )
                        DietaryToggleRow(
                            icon = Icons.Filled.WaterDrop,
                            iconTint = DairyBlue,
                            title = "Cholov Yisroel",
                            subtitle = "Dairy under full Yisroel supervision",
                            checked = cholov,
                            onChange = { cholov = it },
                        )
                        DietaryToggleRow(
                            icon = Icons.Filled.BakeryDining,
                            iconTint = PasYisroelYellow,
                            title = "Pas Yisroel",
                            subtitle = "Baked goods under full Yisroel supervision",
                            checked = pas,
                            onChange = { pas = it },
                        )
                    }
                }
            }

            HorizontalDivider(color = SurfaceDarkBorder)

            // Sticky bottom action button
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
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    enabled = previewCount > 0,
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (previewCount > 0) {
                                "Show $previewCount result${if (previewCount == 1) "" else "s"}"
                            } else "No matches",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        if (activeFilterCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TextWhite.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "$activeFilterCount filter${if (activeFilterCount == 1) "" else "s"}",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarPill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CertCard(
    cert: KosherCertification,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Orange else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Radio circle
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Orange else Color.Transparent)
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = TextMuted,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TextWhite),
                )
            }
        }
        Text(
            text = certShortName(cert),
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DietaryToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextTertiary, fontSize = 13.sp)
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

private fun certShortName(cert: KosherCertification): String = when (cert) {
    KosherCertification.OU -> "OU"
    KosherCertification.OK -> "OK"
    KosherCertification.STAR_K -> "Star-K"
    KosherCertification.KOF_K -> "Kof-K"
    KosherCertification.CRC -> "cRc"
    KosherCertification.BADATZ -> "Badatz"
    KosherCertification.CHABAD -> "Chabad"
    KosherCertification.LOCAL -> "Local"
    KosherCertification.OTHER -> "Other"
}
