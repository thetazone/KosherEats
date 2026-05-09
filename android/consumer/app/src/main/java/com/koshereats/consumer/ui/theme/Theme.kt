package com.koshereats.consumer.ui.theme

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun darkScheme(c: KosherEatsColors): ColorScheme = darkColorScheme(
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
    surfaceVariant = c.surfaceDark,
    onSurfaceVariant = c.textSecondary,
    outline = c.surfaceDarkBorder,
    outlineVariant = c.surfaceDark,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = c.textWhite,
    inverseOnSurface = c.backgroundBlack,
    inversePrimary = OrangeDark,
    surfaceTint = Orange,
    scrim = Color(0xFF101214),
)

private fun lightScheme(c: KosherEatsColors): ColorScheme = lightColorScheme(
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
    outline = c.surfaceDarkBorder,
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
fun KosherEatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val koshereatsColors = if (darkTheme) DarkKosherEatsColors else LightKosherEatsColors
    val colorScheme = if (darkTheme) darkScheme(koshereatsColors) else lightScheme(koshereatsColors)

    val view = LocalView.current
    if (!view.isInEditMode) {
        val barColor = koshereatsColors.backgroundBlack.toArgb()
        val lightBars = !darkTheme
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = lightBars
            insets.isAppearanceLightNavigationBars = lightBars
        }
    }

    CompositionLocalProvider(LocalKosherEatsColors provides koshereatsColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KosherEatsTypography,
            content = content,
        )
    }
}
