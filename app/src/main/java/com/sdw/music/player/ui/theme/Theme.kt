package com.sdw.music.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sdw.music.player.ThemeManager

val SDWDarkColorScheme = darkColorScheme(
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

private val SDWLightColorScheme = lightColorScheme(
    primary = Color(0xFF1E6FBA),          // 深蓝主色（浅色背景下保证对比度）
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF0A3D6B),
    secondary = Color(0xFF8C6D1F),        // 深金
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E2B8),
    onSecondaryContainer = Color(0xFF3E2E00),
    tertiary = Color(0xFF3A6EA5),
    background = Color(0xFFF5F5F7),       // 浅灰白背景
    onBackground = Color(0xFF1A1A1C),
    surface = Color(0xFFFFFFFF),          // 白色卡片
    onSurface = Color(0xFF1A1A1C),
    surfaceVariant = Color(0xFFE8E8EA),   // 浅灰表面
    onSurfaceVariant = Color(0xFF5A5A5E),
    outline = Color(0xFFD0D0D4),
    outlineVariant = Color(0xFFB8B8BC),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun SDWMusicTheme(
    content: @Composable () -> Unit
) {
    // 浅色模式由 ThemeManager 驱动（默认深色）
    val lightMode = ThemeManager.lightMode.value
    val context = LocalContext.current

    val colorScheme = when {
        // 浅色模式：Material You 动态取色，回退静态浅色
        lightMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            try {
                dynamicLightColorScheme(context)
            } catch (e: Exception) {
                SDWLightColorScheme
            }
        }
        lightMode -> SDWLightColorScheme
        // 深色模式：Material You 动态取色（主色跟随壁纸），但背景仍保持纯黑（CDJ-3000 风格）
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            try {
                dynamicDarkColorScheme(context).copy(
                    background = DarkBg,
                    surface = DarkCard,
                    surfaceVariant = DarkSurface,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            } catch (e: Exception) {
                SDWDarkColorScheme
            }
        }
        else -> SDWDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightMode
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightMode
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SDWTypography,
        content = content
    )
}
