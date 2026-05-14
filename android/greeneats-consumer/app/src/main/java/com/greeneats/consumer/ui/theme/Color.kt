package com.greeneats.consumer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand colors — theme-independent
val Orange = Color(0xFFFF7A1A)
val OrangeLight = Color(0xFFFFA64D)
val OrangeDark = Color(0xFFE85D04)

// Status — theme-independent
val SuccessGreen = Color(0xFF22C55E)
val ErrorRed = Color(0xFFEF4444)
val WarningYellow = Color(0xFFFACC15)
val InfoBlue = Color(0xFF3B82F6)

// Kosher badge colors — theme-independent
val KosherOU = Color(0xFF2563EB)
val KosherOK = Color(0xFF059669)
val KosherStar = Color(0xFFD97706)
val KosherGeneric = Color(0xFF7C3AED)

// Dietary — theme-independent
val MeatRed = Color(0xFFDC2626)
val DairyBlue = Color(0xFF60A5FA)
val PareveGreen = Color(0xFF4ADE80)

@Immutable
data class GreenEatsColors(
    val backgroundBlack: Color,
    val backgroundDark: Color,
    val surfaceDark: Color,
    val surfaceDarkElevated: Color,
    val surfaceDarkBorder: Color,
    val textWhite: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
)

internal val DarkGreenEatsColors = GreenEatsColors(
    backgroundBlack = Color(0xFF101214),
    backgroundDark = Color(0xFF171A1D),
    surfaceDark = Color(0xFF202428),
    surfaceDarkElevated = Color(0xFF292E33),
    surfaceDarkBorder = Color(0xFF3A4148),
    textWhite = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFD4D4D4),
    textTertiary = Color(0xFFA3A3A3),
    textMuted = Color(0xFF737373),
)

internal val LightGreenEatsColors = GreenEatsColors(
    backgroundBlack = Color(0xFFFFFFFF),
    backgroundDark = Color(0xFFF7F7F8),
    surfaceDark = Color(0xFFFFFFFF),
    surfaceDarkElevated = Color(0xFFF1F2F4),
    surfaceDarkBorder = Color(0xFFE5E7EB),
    textWhite = Color(0xFF111827),
    textSecondary = Color(0xFF374151),
    textTertiary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),
)

internal val LocalGreenEatsColors = staticCompositionLocalOf { DarkGreenEatsColors }

val BackgroundBlack: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.backgroundBlack

val BackgroundDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.backgroundDark

val SurfaceDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.surfaceDark

val SurfaceDarkElevated: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.surfaceDarkElevated

val SurfaceDarkBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.surfaceDarkBorder

val TextWhite: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.textWhite

val TextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.textSecondary

val TextTertiary: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.textTertiary

val TextMuted: Color
    @Composable @ReadOnlyComposable
    get() = LocalGreenEatsColors.current.textMuted
