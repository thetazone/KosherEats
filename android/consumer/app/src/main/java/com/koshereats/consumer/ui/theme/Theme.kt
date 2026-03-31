package com.koshereats.consumer.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val KosherEatsDarkColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = TextWhite,
    primaryContainer = OrangeDark,
    onPrimaryContainer = TextWhite,
    secondary = OrangeLight,
    onSecondary = BackgroundBlack,
    secondaryContainer = SurfaceDark,
    onSecondaryContainer = TextWhite,
    tertiary = OrangeLight,
    onTertiary = BackgroundBlack,
    background = BackgroundBlack,
    onBackground = TextWhite,
    surface = BackgroundDark,
    onSurface = TextWhite,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceDarkBorder,
    outlineVariant = SurfaceDark,
    error = ErrorRed,
    onError = TextWhite,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = TextWhite,
    inverseOnSurface = BackgroundBlack,
    inversePrimary = OrangeDark,
    surfaceTint = Orange,
    scrim = BackgroundBlack,
)

@Composable
fun KosherEatsTheme(content: @Composable () -> Unit) {
    val colorScheme = KosherEatsDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BackgroundBlack.toArgb()
            window.navigationBarColor = BackgroundBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KosherEatsTypography,
        content = content
    )
}
