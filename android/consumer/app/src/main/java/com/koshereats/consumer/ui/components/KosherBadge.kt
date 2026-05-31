package com.koshereats.consumer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koshereats.consumer.data.models.DietaryType
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.ui.theme.*

@Composable
fun KosherBadge(
    certification: KosherCertification,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val badgeColor = when (certification) {
        KosherCertification.OU -> KosherOU
        KosherCertification.OK -> KosherOK
        KosherCertification.STAR_K -> KosherStar
        else -> KosherGeneric
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor.copy(alpha = 0.15f))
            .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Verified,
            contentDescription = "Kosher certified",
            tint = badgeColor,
            modifier = Modifier.size(14.dp),
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = certification.abbreviation,
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun DietaryBadge(
    dietaryType: DietaryType,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (dietaryType) {
        DietaryType.MEAT -> MeatRed to "Meat"
        DietaryType.DAIRY -> DairyBlue to "Dairy"
        DietaryType.PAREVE -> PareveGreen to "Pareve"
        else -> return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GlattBadge(modifier: Modifier = Modifier) {
    Text(
        text = "Glatt",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SuccessGreen.copy(alpha = 0.15f))
            .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = SuccessGreen,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun CholovYisroelBadge(modifier: Modifier = Modifier) {
    Text(
        text = "CY",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DairyBlue.copy(alpha = 0.15f))
            .border(1.dp, DairyBlue.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = DairyBlue,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun PasYisroelBadge(modifier: Modifier = Modifier) {
    Text(
        text = "PY",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(OrangeLight.copy(alpha = 0.15f))
            .border(1.dp, OrangeLight.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = OrangeLight,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KosherInfoRow(
    certification: KosherCertification,
    isGlatt: Boolean = false,
    isCholovYisroel: Boolean = false,
    isPasYisroel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KosherBadge(certification = certification)
        if (isGlatt) GlattBadge()
        if (isCholovYisroel) CholovYisroelBadge()
        if (isPasYisroel) PasYisroelBadge()
    }
}

@Composable
fun MenuItemDietaryDot(
    isMeat: Boolean,
    isDairy: Boolean,
    isPareve: Boolean,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when {
        isMeat -> MeatRed to "Meat"
        isDairy -> DairyBlue to "Dairy"
        isPareve -> PareveGreen to "Pareve"
        else -> return
    }
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101214)
@Composable
fun KosherBadgePreview() {
    KosherEatsTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KosherBadge(certification = KosherCertification.OU)
            DietaryBadge(dietaryType = DietaryType.MEAT)
            GlattBadge()
            CholovYisroelBadge()
            PasYisroelBadge()
        }
    }
}
