package com.greeneats.seller.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Primary — theme-independent
val Orange = Color(0xFFF97316)
val OrangeLight = Color(0xFFFB923C)
val OrangeDark = Color(0xFFEA580C)

// Status colors — theme-independent
val StatusPending = Color(0xFFFBBF24)
val StatusAccepted = Color(0xFF3B82F6)
val StatusPreparing = Color(0xFF8B5CF6)
val StatusReady = Color(0xFF22C55E)
val StatusCompleted = Color(0xFF10B981)
val StatusCancelled = Color(0xFFEF4444)

// Misc — theme-independent
val ErrorRed = Color(0xFFEF4444)
val SuccessGreen = Color(0xFF22C55E)

@Immutable
data class GreenEatsSellerColors(
    val backgroundBlack: Color,
    val backgroundDark: Color,
    val surfaceDark: Color,
    val surfaceDarkElevated: Color,
    val divider: Color,
    val textWhite: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
)

internal val DarkSellerColors = GreenEatsSellerColors(
    backgroundBlack = Color(0xFF0A0A0A),
    backgroundDark = Color(0xFF171717),
    surfaceDark = Color(0xFF262626),
    surfaceDarkElevated = Color(0xFF333333),
    divider = Color(0xFF404040),
    textWhite = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFD4D4D4),
    textTertiary = Color(0xFFA3A3A3),
    textMuted = Color(0xFF737373),
)

internal val LightSellerColors = GreenEatsSellerColors(
    backgroundBlack = Color(0xFFFFFFFF),
    backgroundDark = Color(0xFFF7F7F8),
    surfaceDark = Color(0xFFFFFFFF),
    surfaceDarkElevated = Color(0xFFF1F2F4),
    divider = Color(0xFFE5E7EB),
    textWhite = Color(0xFF111827),
    textSecondary = Color(0xFF374151),
    textTertiary = Color(0xFF6B7280),
    textMuted = Color(0xFF9CA3AF),
)

internal val LocalSellerColors = staticCompositionLocalOf { DarkSellerColors }

val BackgroundBlack: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.backgroundBlack

val BackgroundDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.backgroundDark

val SurfaceDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.surfaceDark

val SurfaceDarkElevated: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.surfaceDarkElevated

val DividerColor: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.divider

val TextWhite: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.textWhite

val TextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.textSecondary

val TextTertiary: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.textTertiary

val TextMuted: Color
    @Composable @ReadOnlyComposable
    get() = LocalSellerColors.current.textMuted
