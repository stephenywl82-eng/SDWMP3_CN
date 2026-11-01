package com.sdw.music.player.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.remember
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sdw.music.player.ThemeManager
import com.sdw.music.player.WallpaperManager

/**
 * [M3 Expressive · 自动主题色] 主题色**不再写死**，全部从系统取「用户当前真正选中的主题色」。
 *
 * 三级回退，取到即返回：
 *  1. `Settings.Secure` 的 `theme_customization_overlay_packages` —— 用户在「壁纸与样式」里
 *     选的调色板（`system_palette`，本机型 = #00857E）。这是最贴近用户意图的一份。
 *  2. `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` —— 系统从壁纸提取的主色。
 *  3. 都拿不到 → null，交给 `dynamicDark/LightColorScheme()`（Android 12+ 的 system_accent* 资源）。
 *
 * 为什么不能只靠 `dynamicDarkColorScheme()`：实测本机型（Motorola/MTK，Android 16）只把用户
 * 调色板作为 RRO 覆盖给 `com.android.systemui`（`cmd overlay list` 里 android 框架目标下没有任何
 * accent/palette 覆盖），framework 的 `android.R.color.system_accent1_*` 仍是基线默认值，
 * 于是 Compose 的动态取色在这里恒等于官方基线紫（实测渲染出的强调色 #CDBDFF ≈ Purple80）。
 *
 * ★ 早先这里还有两套写死的蓝/金品牌配色（`SDWDarkColorScheme` / `SDWLightColorScheme`），
 *   已被本函数的动态取色整体取代，故删除——留着会让人误以为配色是固定值。
 */
private fun sdwSystemAccentSeed(context: android.content.Context): Int? {
    // 1) 用户在系统里选的调色板
    try {
        val raw = android.provider.Settings.Secure.getString(
            context.contentResolver, "theme_customization_overlay_packages"
        )
        if (!raw.isNullOrBlank()) {
            val obj = org.json.JSONObject(raw)
            for (key in listOf(
                "android.theme.customization.system_palette",
                "android.theme.customization.accent_color"
            )) {
                val v = obj.optString(key, "")
                if (v.startsWith("#")) {
                    val parsed = v.substring(1).toLongOrNull(16)
                    if (parsed != null) return (parsed or 0xFF000000L).toInt()
                }
            }
        }
    } catch (_: Throwable) {}

    // 2) 系统从壁纸提取的主色
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val wc = android.app.WallpaperManager.getInstance(context)
                .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
            val c = wc?.primaryColor
            if (c != null) return c.toArgb()
        }
    } catch (_: Throwable) {}

    return null
}

/**
 * 用系统主题色 seed 重算整套强调色（primary / secondary / tertiary 三组色阶），
 * 铺到底盘配色上。底盘负责 surface* 与 background 层次，seed 只接管"色相"。
 *
 * 官方的 seed → tonal palette（HCT）是 internal API，第三方拿不到，故这里用 HSL 做等价映射：
 * 按 Material 的 tone 语义给深色/浅色各配一组亮度阶梯（dark: 80/18/28/88，light: 42/白/90/22），
 * secondary / tertiary 由 seed 色相 +26° / +58° 派生，饱和度递减，保证同族而不撞色。
 */
@Composable
private fun sdwAccentTuned(base: ColorScheme, light: Boolean): ColorScheme {
    val context = LocalContext.current
    val seed = remember(context) { sdwSystemAccentSeed(context) } ?: return base

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val baseHue = hsl[0]
    // 系统给的调色板有时饱和度很低（近灰），抬到 0.30 保证"有色"而不是灰
    val baseSat = hsl[1].coerceAtLeast(0.30f)
    fun tone(hue: Float, sat: Float, lum: Float): Color =
        Color(
            ColorUtils.HSLToColor(
                floatArrayOf(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), lum)
            )
        )

    /**
     * 强调色上的前景色：**不写死白**。
     *
     * 写死 `onPrimary = White` 时，seed 的色相决定成败——偏黄/偏绿的 seed 在浅色分支
     * 落在 0.42 亮度上，相对亮度约 0.38，白字对比度只有 **2.4:1**（正文完全不可读）；
     * 偏蓝的 seed 却有 10.3:1。同一个 App 会因系统调色板不同而忽好忽坏。
     *
     * 做法：三个候选里择优——同色相的深色版本（比纯黑更"同族"，M3 tone 语义也更贴）
     * 达标就用它；否则在黑 / 白里挑对比更高的那个。
     * 实测（HSL L=0.42 / sat=0.6 的主色）：
     *   黄  白 2.43 / 黑 8.63 -> 黑      蓝  白 10.31 / 黑 2.04 -> 白
     *   绿  白 3.00 / 黑 6.99 -> 黑      品红 白 5.72 / 黑 3.67 -> 白
     * 全部 >= 4.5:1；深色分支（主色 L=0.80）恒取同色相深色版本，实测 7.5~8.2:1。
     */
    fun onFor(bg: Color, dark: Color): Color {
        val lBg = ColorUtils.calculateLuminance(bg.toArgb())
        val lDark = ColorUtils.calculateLuminance(dark.toArgb())
        val cDark = (lBg + 0.05f) / (lDark + 0.05f)
        val cBlack = (lBg + 0.05f) / 0.05f
        val cWhite = 1.05f / (lBg + 0.05f)
        return when {
            cDark >= 4.5f -> dark
            cDark >= maxOf(cBlack, cWhite) -> dark
            cBlack >= cWhite -> Color.Black
            else -> Color.White
        }
    }

    return if (light) {
        val p = tone(baseHue, baseSat, 0.42f)
        val s = tone(baseHue + 26f, baseSat * 0.55f, 0.44f)
        val t = tone(baseHue + 58f, baseSat * 0.62f, 0.40f)
        base.copy(
            primary = p,
            onPrimary = onFor(p, tone(baseHue, baseSat, 0.22f)),
            primaryContainer = tone(baseHue, baseSat * 0.75f, 0.90f),
            onPrimaryContainer = tone(baseHue, baseSat, 0.22f),
            secondary = s,
            onSecondary = onFor(s, tone(baseHue + 26f, baseSat * 0.80f, 0.20f)),
            secondaryContainer = tone(baseHue + 26f, baseSat * 0.45f, 0.90f),
            onSecondaryContainer = tone(baseHue + 26f, baseSat * 0.80f, 0.20f),
            tertiary = t,
            onTertiary = onFor(t, tone(baseHue + 58f, baseSat * 0.80f, 0.20f)),
            tertiaryContainer = tone(baseHue + 58f, baseSat * 0.50f, 0.90f),
            onTertiaryContainer = tone(baseHue + 58f, baseSat * 0.80f, 0.20f)
        )
    } else {
        val p = tone(baseHue, baseSat, 0.80f)
        val s = tone(baseHue + 26f, baseSat * 0.55f, 0.80f)
        val t = tone(baseHue + 58f, baseSat * 0.62f, 0.82f)
        base.copy(
            primary = p,
            onPrimary = onFor(p, tone(baseHue, baseSat, 0.18f)),
            primaryContainer = tone(baseHue, baseSat * 0.85f, 0.28f),
            onPrimaryContainer = tone(baseHue, baseSat * 0.55f, 0.88f),
            secondary = s,
            onSecondary = onFor(s, tone(baseHue + 26f, baseSat * 0.70f, 0.18f)),
            secondaryContainer = tone(baseHue + 26f, baseSat * 0.50f, 0.28f),
            onSecondaryContainer = tone(baseHue + 26f, baseSat * 0.40f, 0.88f),
            tertiary = t,
            onTertiary = onFor(t, tone(baseHue + 58f, baseSat * 0.80f, 0.20f)),
            tertiaryContainer = tone(baseHue + 58f, baseSat * 0.55f, 0.30f),
            onTertiaryContainer = tone(baseHue + 58f, baseSat * 0.50f, 0.90f)
        )
    }
}

/**
 * [M3 Expressive · 自动主题色] 全局唯一的配色入口：底盘（Android 12+ 走动态取色，
 * 否则深色 darkColorScheme() / 浅色 expressiveLightColorScheme()）+ 系统主题色 seed 覆写强调色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sdwAutoScheme(light: Boolean): ColorScheme {
    val context = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (light) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else if (light) {
        // material3 1.5.x 至今只提供 expressiveLightColorScheme()，没有深色版
        expressiveLightColorScheme()
    } else {
        darkColorScheme().copy(
            surfaceContainerLowest = SurfaceContainerLowestDark,
            surfaceContainerLow = SurfaceContainerLowDark,
            surfaceContainer = SurfaceContainerDark,
            surfaceContainerHigh = SurfaceContainerHighDark,
            surfaceContainerHighest = SurfaceContainerHighestDark
        )
    }
    return sdwAccentTuned(base, light)
}

/**
 * 播放器页 / 歌词全屏页要锁深色，但**不能锁死配色**——原来它们直接
 * `MaterialTheme(colorScheme = SDWDarkColorScheme)` 把 primary 写死成 AccentBlue，
 * 等于绕过全局主题色。改为「同一套自动配色，只是强制取深色分支」。
 *
 * （`SDWDarkColorScheme` 本身已随蓝/金品牌配色一并删除，这里保留注释作为改动留痕。）
 */
@Composable
fun sdwForcedDarkScheme(): ColorScheme = sdwAutoScheme(light = false)

/** [M3 Expressive] 深色 Surface 色阶（仅回退底盘使用）：拉开 5 级层次，取代"一片糊"的单底色。 */
private val SurfaceContainerLowestDark = Color(0xFF080808)
private val SurfaceContainerLowDark = Color(0xFF101010)
private val SurfaceContainerDark = Color(0xFF181818)
private val SurfaceContainerHighDark = Color(0xFF212121)
private val SurfaceContainerHighestDark = Color(0xFF2C2C2C)

// [M3 Expressive · Path A] 全局 Expressive 形状体系：大圆角 + 胶囊。
// MaterialExpressiveTheme 在 1.5.0-alpha22 默认只注入 Expressive MotionScheme，形状仍是标准 M3，
// 故显式把 MotoExpressiveShapes 传入，让所有 M3 组件（Card / Button / FAB / Chip / Dialog）套用大圆角。
val MotoExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(50.dp),      // 胶囊：按钮 / FilterChip / Badge
    medium = RoundedCornerShape(28.dp),     // 默认卡片圆角
    large = RoundedCornerShape(36.dp),      // 大区块 / 弹窗
    extraLarge = RoundedCornerShape(48.dp)  // Hero 封面 / FAB / 主操作
)

/** 壁纸激活且取色感知判定为"亮壁纸"（需用深色文字）。非壁纸或暗壁纸 = false。 */
val LocalWallpaperEffectiveLight = staticCompositionLocalOf { false }

/**
 * 当前是否处于「浅色/亮色」模式：浅色开关开启，或壁纸取色感知判定为偏亮时为 true。
 * 主题与 WallpaperBackground 共用同一份判定，避免两边各算一遍导致漂移。
 */
@Composable
fun sdwEffectiveLight(): Boolean {
    // 主题由 ThemeManager 驱动（设置页「浅色模式」「跟随系统」两个开关）：
    //  - 跟随系统开启时，改读系统 uiMode；Manifest 已声明 configChanges=uiMode，系统切深/浅
    //    不会重建 Activity，LocalConfiguration 变化触发此处重组，实现实时跟随，无需重启。
    //  - 否则听用户手动「浅色模式」开关 lightMode。
    // 此前为修复浅色分支"灰色雾霾"问题把这里硬锁成 `return false`，导致两个开关全部失效；
    // 现恢复对 ThemeManager 状态的读取（浅色分支的配色逻辑在 SDWMusicTheme 内仍保留可用）。
    val followSystem = ThemeManager.followSystem.value
    val lightMode = ThemeManager.lightMode.value
    val isSystemLight = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_NO
    return if (followSystem) isSystemLight else lightMode
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SDWMusicTheme(
    content: @Composable () -> Unit
) {
    // 浅色模式由 ThemeManager 驱动（默认深色）
    // [V8.111] 「跟随系统」开启时改用系统 uiMode。Manifest 里 MainActivity 已声明
    // configChanges=...|uiMode，系统切换深/浅色不会重建 Activity，LocalConfiguration
    // 变化会触发这里重组，所以是实时跟随，无需重启。
    // [V8.113] 与 WallpaperBackground 共用同一份「是否浅色」判定，避免漂移
    val effectiveLight = sdwEffectiveLight()
    // [M3 Expressive · 自动主题色] 底盘 + 系统主题色 seed（见 sdwAutoScheme）；
    // 下方 finalScheme 再叠液态玻璃透明度。
    val colorScheme = sdwAutoScheme(effectiveLight)

    // [V8.113] 真·液态玻璃：背后永远有一层模糊底图。但「自定义图片壁纸」是繁忙照片，面板若太透
    // 文字会看不清，故按"是否真设了图片"分流：设了图片→磨砂玻璃（够厚托住文字，仍透出模糊照片）；
    // 未设图片（主题渐变底，底色干净）→ 极薄真玻璃。
    val hasWallpaper = WallpaperManager.hasWallpaper()
    // 设了图片壁纸时面板加厚到约 0.30；未设时回到极薄 0.10 真玻璃。
    val surfA = if (hasWallpaper) 0.30f else 0.10f
    val surfVA = if (hasWallpaper) 0.30f else 0.10f
    val finalScheme = if (effectiveLight) {
        // 浅色（含壁纸偏亮取色感知）：几乎全透明白卡片，深文字
        colorScheme.copy(
            background = Color.Transparent,
            surface = Color.White.copy(alpha = surfA),
            surfaceVariant = Color(0xFFE8E8EA).copy(alpha = surfVA),
            onSurface = Color(0xFF141416),
            onSurfaceVariant = Color(0xFF3C3C40),
            outline = Color(0xFF3A3A3E).copy(alpha = 0.10f),
            outlineVariant = Color(0xFF8E8E93).copy(alpha = 0.08f)
        )
    } else {
        // 深色：几乎全透明深色卡片，浅文字（统一透明质感）
        colorScheme.copy(
            background = Color.Transparent,
            surface = DarkCard.copy(alpha = surfA),
            surfaceVariant = DarkSurface.copy(alpha = surfVA),
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline = DarkSurface.copy(alpha = 0.08f),
            outlineVariant = DarkSurface.copy(alpha = 0.06f)
        )
    }

    // [2026-10-31] 去除全局文字描边（shadow）：原想给「压在模糊壁纸上的文字」加描边兜底，
    // 但一刀切导致实色卡片（DSP 主卡等）上的文字也带描边、边缘发虚像被磨糊。
    // 文字清晰度改靠实色衬底兜底：玻璃卡片（glassSurface）自身带 scrim；播放页大标题 / 迷你条
    // 这类压在模糊封面上的自由文字改用「封面渐变 scrim」托底（见 PlayerComponents / MiniBar）。
    // 壁纸偏亮导致自由文字看不清时，由对应 scrim 的明暗自适应保证可读性，不再用描边硬抗。
    val finalTypography = SDWTypography

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

    CompositionLocalProvider(
        LocalWallpaperEffectiveLight provides effectiveLight
    ) {
        MaterialExpressiveTheme(
            colorScheme = finalScheme,
            motionScheme = MotionScheme.expressive(),
            typography = finalTypography,
            shapes = MotoExpressiveShapes
        ) {
            // [V8.110] L1+L2 浅色/深色切换液态漫射叠层：全屏覆盖，压在内容之上、
            // 系统栏区域之内（app 已 edge-to-edge）。空闲时 ThemeFlipOverlay 直接
            // return，零组合零绘制。
            Box(modifier = Modifier.fillMaxSize()) {
                content()
                ThemeFlipOverlay()
            }
        }
    }
}
