package com.sdw.music.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.ui.theme.LocalWallpaperEffectiveLight
import com.sdw.music.player.ui.theme.ThemeFlip

/**
 * V8.59 - Android 17 "Material 3 Expressive" surface language for the main screen.
 *
 * Android 17's visual direction is translucent, layered, depth-first surfaces (the
 * system-level liquid-glass panels) with springy, physics-driven touch feedback. The
 * app previously drew flat, fully opaque cards, which is what makes the main screen
 * read as flat next to the system UI.
 *
 * Why this is not Modifier.blur:
 * Modifier.blur is a RenderEffect and only works on API 31+. minSdk here is 24, so a
 * real backdrop blur would silently do nothing on a large share of devices. Instead the
 * frosted look is built the portable way: a translucent two-stop vertical gradient
 * (brighter at the top, where light would land), a hairline vertical-gradient border
 * that reads as the pane's lit edge, and an optional soft ambient shadow to lift
 * genuinely floating panels off the background. That combination reads as glass on
 * every API level and costs one background draw plus one border draw.
 *
 * Wallpaper mode is honoured: over a wallpaper the fill becomes a dark scrim, or a light
 * one when the wallpaper is bright, so text keeps contrast - the same rule the existing
 * tiles already followed.
 *
 * @param shape      corner shape of the pane
 * @param accent     colour used for the selected wash and the lit edge
 * @param selected   draws the accent wash (now-playing row, active filter tile)
 * @param elevation  shadow height; pass 0.dp for list rows (see note below)
 * @param accentEdge use the accent colour for the top edge highlight instead of the
 *                   neutral outline - for panels that should announce their colour
 * @param fillBoost  0f..1f, raises the fill opacity when a pane must stay readable while
 *                   content scrolls underneath it (sticky headers)
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = SDWShapes.Row,
    accent: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    elevation: Dp = 0.dp,
    accentEdge: Boolean = false,
    fillBoost: Float = 0f,
    // [V8.112] 真·背景模糊（API31+）时，条子底下已经贴了一层模糊内容；此时自身不该再糊一层
    // 厚底色，否则读起来是"一块死板的黑/白"，而不是透亮的液态玻璃。打开后填充降到极薄的主题色薄纱。
    blurBacked: Boolean = false
): Modifier {
    // [V8.113] 真·液态玻璃：背后永远有模糊层，填充按「薄纱」处理。但「自定义图片壁纸」是繁忙照片，
    // 面板太透文字会看不清 —— 设了图片壁纸时把 scrim 加厚成磨砂玻璃（仍透出模糊照片），未设则极薄真玻璃。
    val hasWallpaper = WallpaperManager.hasWallpaper()
    val wpLight = LocalWallpaperEffectiveLight.current
    val scrim = if (wpLight) Color.White else Color.Black
    // [V8.110] L2 - 主题切换期玻璃面「重新结霜」：切换进行中（ThemeFlip.reveal 钟形曲线
    // 峰值 1）时把 pane 临时加厚、顶边高光拉亮，光扫过哪块、哪块就像重新凝了一层霜。
    // 静止期 reveal 恒 0 → 下面两项都是 0，无额外开销；且不引入新动画（不建 Animatable，
    // 只读一个 State，避免给每一块玻璃面各挂一个 animateFloatAsState）。
    val frost = ThemeFlip.reveal.value.coerceIn(0f, 1f)
    val boost = (fillBoost + frost * 0.18f).coerceIn(0f, 0.9f)

    val topColor = when {
        selected -> accent.copy(alpha = (if (hasWallpaper) 0.40f else 0.20f) + boost * 0.5f)
        // [V8.112] 模糊底：极薄薄纱，让下面的模糊内容透出来
        blurBacked -> MaterialTheme.colorScheme.surface.copy(alpha = 0.10f + boost * 0.5f)
        // 有图片壁纸：磨砂玻璃，scrim 加厚到 0.46 托住文字；无壁纸：极薄 0.30 真玻璃
        hasWallpaper -> scrim.copy(alpha = 0.46f + boost)
        else -> scrim.copy(alpha = 0.30f + boost)
    }
    val bottomColor = when {
        selected -> accent.copy(alpha = (if (hasWallpaper) 0.18f else 0.06f) + boost * 0.5f)
        blurBacked -> MaterialTheme.colorScheme.surface.copy(alpha = 0.20f + boost * 0.5f)
        hasWallpaper -> scrim.copy(alpha = 0.30f + boost)
        else -> scrim.copy(alpha = 0.15f + boost)
    }
    // Edge highlight: brighter along the top edge and dropping off at the bottom. This is
    // what actually sells the "pane of glass" read, more than the fill does.
    // [V8.110] 顶边高光在切换期拉到接近 1.0（`0.78 + 0.22*frost`），结霜感来自"边缘先亮"。
    val edgeLift = 0.22f * frost
    val edgeTop = if (selected || accentEdge) accent.copy(alpha = (0.78f + edgeLift).coerceAtMost(1f))
                  else MaterialTheme.colorScheme.outlineVariant.copy(alpha = (0.34f + edgeLift).coerceAtMost(1f))
    val edgeBottom = if (selected || accentEdge) accent.copy(alpha = (0.34f + edgeLift).coerceAtMost(1f))
                     else MaterialTheme.colorScheme.outlineVariant.copy(alpha = (0.58f + edgeLift).coerceAtMost(1f))

    // A library can hold thousands of rows and every shadow forces its own graphics layer,
    // so rows are drawn with elevation = 0.dp and rely on the hairline edge alone. Only the
    // handful of genuinely floating panels get a real shadow.
    val shadowed = if (elevation > 0.dp) {
        this.shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.30f),
            spotColor = Color.Black.copy(alpha = 0.42f)
        )
    } else this

    return shadowed
        .clip(shape)
        .background(
            // V8.68 - a 3-stop gradient with a hard 0.46 break showed up as a visible
            // rectangular seam inside the small 56dp filter tiles (the fill alpha step
            // read as an inset block). Interpolate smoothly instead.
            Brush.verticalGradient(
                0.0f to topColor,
                1.0f to bottomColor
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(0f to edgeTop, 1f to edgeBottom),
            shape = shape
        )
}

/**
 * V8.59 - M3 Expressive press feedback.
 *
 * Expressive design reacts to touch with spring physics instead of a fixed tween, and it
 * compresses only slightly - a 4% squeeze reads as "the surface gave way", while anything
 * more looks like a rendering bug. MediumBouncy + StiffnessLow adds the small overshoot on
 * release that makes the interaction feel alive.
 *
 * The caller owns the [MutableInteractionSource] so it can pass the same one to
 * `clickable(indication = null)` and keep the ripple from fighting the scale.
 */
@Composable
fun Modifier.springPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "springPress"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
