package com.sdw.music.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SDWDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = DarkBg,
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFB3D4FC),
    secondary = Gold80,
    onSecondary = DarkBg,
    secondaryContainer = Gold60,
    tertiary = Color(0xFF5C8ABF),
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkCard,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurface,
    outlineVariant = TextTertiary,
    error = AccentRed,
    onError = TextPrimary,
)

@Composable
fun SDWMusicTheme(
    content: @Composable () -> Unit
) {
    // Force dark theme always, using system MD3 dynamic dark color
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        try {
            val dyn = dynamicDarkColorScheme(context)
            android.util.Log.d("SDWTheme", "Dynamic dark color OK: primary=${dyn.primary}")
            dyn
        } catch (e: Exception) {
            android.util.Log.w("SDWTheme", "Dynamic color FAILED, fallback: ${e.message}")
            SDWDarkColorScheme
        }
    } else {
        SDWDarkColorScheme
    }

    val darkTheme = true
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SDWTypography,
        content = content
    )
}
