package com.cuppa.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Cuppa Theme
 *
 * Implements Material 3 with dynamic color support (Android 12+).
 * Falls back to a rich branded teal/amber palette on older devices.
 */

// ============================================================
// Light color scheme (branded fallback)
// ============================================================
private val CuppaLightColorScheme = lightColorScheme(
    primary = CuppaTeal40,
    onPrimary = Color.White,
    primaryContainer = CuppaTeal90,
    onPrimaryContainer = CuppaTeal10,
    secondary = CuppaAmber40,
    onSecondary = Color.White,
    secondaryContainer = CuppaAmber90,
    onSecondaryContainer = CuppaAmber10,
    tertiary = CuppaRose40,
    onTertiary = Color.White,
    tertiaryContainer = CuppaRose90,
    onTertiaryContainer = CuppaRose10,
    error = CuppaError40,
    onError = CuppaError100,
    errorContainer = CuppaError90,
    onErrorContainer = CuppaError10,
    background = CuppaGray99,
    onBackground = CuppaGray10,
    surface = CuppaGray99,
    onSurface = CuppaGray10,
    surfaceVariant = CuppaGray90,
    onSurfaceVariant = CuppaGray30,
    outline = CuppaGray50,
    outlineVariant = CuppaGray80,
    inverseSurface = CuppaGray20,
    inverseOnSurface = CuppaGray95,
    inversePrimary = CuppaTeal80,
)

// ============================================================
// Dark color scheme (branded fallback)
// ============================================================
private val CuppaDarkColorScheme = darkColorScheme(
    primary = CuppaTeal80,
    onPrimary = CuppaTeal20,
    primaryContainer = CuppaTeal30,
    onPrimaryContainer = CuppaTeal90,
    secondary = CuppaAmber80,
    onSecondary = CuppaAmber20,
    secondaryContainer = CuppaAmber30,
    onSecondaryContainer = CuppaAmber90,
    tertiary = CuppaRose80,
    onTertiary = CuppaRose20,
    tertiaryContainer = CuppaRose30,
    onTertiaryContainer = CuppaRose90,
    error = CuppaError80,
    onError = CuppaError20,
    errorContainer = CuppaError30,
    onErrorContainer = CuppaError90,
    background = CuppaGray10,
    onBackground = CuppaGray90,
    surface = CuppaGray10,
    onSurface = CuppaGray90,
    surfaceVariant = CuppaGray30,
    onSurfaceVariant = CuppaGray80,
    outline = CuppaGray60,
    outlineVariant = CuppaGray30,
    inverseSurface = CuppaGray90,
    inverseOnSurface = CuppaGray20,
    inversePrimary = CuppaTeal40,
)

/**
 * Main Cuppa theme composable.
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use dynamic color (Material You). Requires Android 12+.
 * @param content The composable content to theme.
 */
@Composable
fun CuppaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic color: Android 12+ (API 31)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Branded fallback
        darkTheme -> CuppaDarkColorScheme
        else -> CuppaLightColorScheme
    }

    // Configure system bars to match theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CuppaTypography,
        content = content,
    )
}
