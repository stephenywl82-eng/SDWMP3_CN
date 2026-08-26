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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sdw.music.player.ThemeManager
import com.sdw.music.player.WallpaperManager

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
    // 自定义壁纸取色感知：壁纸偏亮时用深文字（浅色配色），否则浅文字（深色配色）
    val wallpaperDarkText = WallpaperManager.shouldUseDarkText()
    val effectiveLight = lightMode || wallpaperDarkText
    val context = LocalContext.current

    val colorScheme = when {
        // 浅色模式（含壁纸偏亮取色感知）：Material You 动态取色，回退静态浅色
        effectiveLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            try {
                dynamicLightColorScheme(context)
            } catch (e: Exception) {
                SDWLightColorScheme
            }
        }
        effectiveLight -> SDWLightColorScheme
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

    // 自定义壁纸：有壁纸时页面背景 + 卡片统一透明，透出底部壁纸层。
    val hasWallpaper = WallpaperManager.hasWallpaper()
    val finalScheme = if (hasWallpaper) {
        if (effectiveLight) {
            // 浅色（含壁纸偏亮取色感知）：几乎全透明白卡片，深文字
            colorScheme.copy(
                background = Color.Transparent,
                surface = Color.White.copy(alpha = 0.14f),
                surfaceVariant = Color(0xFFE8E8EA).copy(alpha = 0.14f),
                onSurface = Color(0xFF141416),
                onSurfaceVariant = Color(0xFF3C3C40),
                outline = Color(0xFF3A3A3E).copy(alpha = 0.10f),
                outlineVariant = Color(0xFF8E8E93).copy(alpha = 0.08f)
            )
        } else {
            // 深色：几乎全透明深色卡片，浅文字（统一透明质感）
            colorScheme.copy(
                background = Color.Transparent,
                surface = DarkCard.copy(alpha = 0.10f),
                surfaceVariant = DarkSurface.copy(alpha = 0.10f),
                onSurface = TextPrimary,
                onSurfaceVariant = TextSecondary,
                outline = DarkSurface.copy(alpha = 0.08f),
                outlineVariant = DarkSurface.copy(alpha = 0.06f)
            )
        }
    } else colorScheme

    // 壁纸激活时：文字自动描边衬底（居中光晕模拟描边），深色/浅色模式都生效。
    val finalTypography = if (hasWallpaper) {
        val textShadow = if (effectiveLight) {
            // 浅色模式：深文字 + 白色描边衬底（居中光晕，四周浮出）
            Shadow(color = Color.White.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = 3f)
        } else {
            // 深色模式：浅文字 + 黑色描边衬底
            Shadow(color = Color.Black.copy(alpha = 0.80f), offset = Offset.Zero, blurRadius = 3f)
        }
        SDWTypography.copy(
            headlineLarge = SDWTypography.headlineLarge.copy(shadow = textShadow),
            headlineMedium = SDWTypography.headlineMedium.copy(shadow = textShadow),
            headlineSmall = SDWTypography.headlineSmall.copy(shadow = textShadow),
            titleLarge = SDWTypography.titleLarge.copy(shadow = textShadow),
            titleMedium = SDWTypography.titleMedium.copy(shadow = textShadow),
            titleSmall = SDWTypography.titleSmall.copy(shadow = textShadow),
            bodyLarge = SDWTypography.bodyLarge.copy(shadow = textShadow),
            bodyMedium = SDWTypography.bodyMedium.copy(shadow = textShadow),
            bodySmall = SDWTypography.bodySmall.copy(shadow = textShadow),
            labelLarge = SDWTypography.labelLarge.copy(shadow = textShadow),
            labelMedium = SDWTypography.labelMedium.copy(shadow = textShadow),
            labelSmall = SDWTypography.labelSmall.copy(shadow = textShadow)
        )
    } else SDWTypography

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏/导航栏保持纯色（壁纸层不延伸到 window 层，避免系统栏文字看不清）
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = effectiveLight
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = effectiveLight
        }
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = finalTypography,
        content = content
    )
}
