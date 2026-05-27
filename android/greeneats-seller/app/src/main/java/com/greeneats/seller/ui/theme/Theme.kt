package com.greeneats.seller.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun darkScheme(c: GreenEatsSellerColors): ColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = OrangeDark,
    onPrimaryContainer = Color.White,
    secondary = OrangeLight,
    onSecondary = c.backgroundBlack,
    secondaryContainer = c.surfaceDark,
    onSecondaryContainer = c.textWhite,
    tertiary = OrangeLight,
    onTertiary = c.backgroundBlack,
    background = c.backgroundBlack,
    onBackground = c.textWhite,
    surface = c.backgroundDark,
    onSurface = c.textWhite,
    surfaceVariant = c.surfaceDarkElevated,
    onSurfaceVariant = c.textSecondary,
    outline = c.divider,
    outlineVariant = c.divider,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = c.textWhite,
    inverseOnSurface = c.backgroundBlack,
    inversePrimary = OrangeDark,
    surfaceTint = Orange,
    scrim = Color(0xFF0A0A0A),
)

private fun lightScheme(c: GreenEatsSellerColors): ColorScheme = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = OrangeLight,
    onPrimaryContainer = c.textWhite,
    secondary = OrangeDark,
    onSecondary = Color.White,
    secondaryContainer = c.surfaceDarkElevated,
    onSecondaryContainer = c.textWhite,
    tertiary = OrangeDark,
    onTertiary = Color.White,
    background = c.backgroundBlack,
    onBackground = c.textWhite,
    surface = c.backgroundDark,
    onSurface = c.textWhite,
    surfaceVariant = c.surfaceDarkElevated,
    onSurfaceVariant = c.textSecondary,
    outline = c.divider,
    outlineVariant = c.surfaceDarkElevated,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color.White,
    inversePrimary = OrangeLight,
    surfaceTint = Orange,
    scrim = Color(0x66000000),
)

@Composable
fun GreenEatsSellerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val sellerColors = if (darkTheme) DarkSellerColors else LightSellerColors
    val colorScheme = if (darkTheme) darkScheme(sellerColors) else lightScheme(sellerColors)

    // enableEdgeToEdge() in MainActivity handles system-bar colours and
    // edge-to-edge layout. We only need to update the icon-tint appearance
    // flags here so status/nav bar icons stay visible against the dark
    // background.  Do NOT set window.statusBarColor / navigationBarColor
    // directly — that conflicts with the edge-to-edge contract.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightBars = !darkTheme
        SideEffect {
            val window = (view.context as Activity).window
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = lightBars
            insets.isAppearanceLightNavigationBars = lightBars
        }
    }

    CompositionLocalProvider(LocalSellerColors provides sellerColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SellerTypography,
            content = content,
        )
    }
}
