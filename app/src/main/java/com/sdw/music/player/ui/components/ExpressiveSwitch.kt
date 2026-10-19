package com.sdw.music.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Material 3 Expressive 风格开关。
 *  - 开启：手柄放大并显示白色对勾 ✓，轨道填充主题色
 *  - 关闭：手柄缩小并显示 ✕
 *  - 滑动与放大均用弹簧动画，符合 M3 Expressive 的「有弹性」手感
 *
 * 兼容原 [androidx.compose.material3.Switch] 的
 * (checked, onCheckedChange, modifier, enabled) 调用方式，
 * 同时允许播放页等场景用 accent 自定义配色。
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbOnColor: Color = Color.White,
    trackOnColor: Color = MaterialTheme.colorScheme.primary,
    thumbOffColor: Color = Color.White,
    trackOffColor: Color = Color(0xFF3A3A3E),
    iconOnColor: Color = MaterialTheme.colorScheme.primary,
    iconOffColor: Color = Color(0xFF9A9A9E)
) {
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val thumbOffSize = 22.dp
    val thumbOnSize = 28.dp
    val pad = 4.dp

    val thumbSize by animateDpAsState(
        targetValue = if (checked) thumbOnSize else thumbOffSize,
        animationSpec = spring(stiffness = 800f, dampingRatio = 0.7f),
        label = "expressiveThumbSize"
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbOnSize - pad else pad,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.55f),
        label = "expressiveThumbX"
    )
    // 【修复】黑主题下 Material You 动态取色可能给出近白的 primary：
    // 默认白手柄 + 主题色勾会全部融进白色轨道（一片白、看不到勾）。
    // 轨道偏亮时自动改用深色手柄 + 白色勾，保证勾始终可见；
    // 深色轨道（常规蓝主色等）维持原配色。调用方显式传入的颜色仍被尊重。
    val trackIsLight = trackOnColor.luminance() > 0.55f
    val effThumbOn = if (trackIsLight && thumbOnColor == Color.White) Color(0xFF141416) else thumbOnColor
    val effIconOn  = if (trackIsLight && iconOnColor == MaterialTheme.colorScheme.primary) Color.White else iconOnColor

    val trackColor by animateColorAsState(
        targetValue = if (checked) trackOnColor else trackOffColor,
        animationSpec = spring(stiffness = 600f, dampingRatio = 1f),
        label = "expressiveTrackColor"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) effThumbOn else thumbOffColor,
        animationSpec = spring(stiffness = 600f, dampingRatio = 1f),
        label = "expressiveThumbColor"
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(stiffness = 1000f, dampingRatio = 1f),
        label = "expressiveCheckAlpha"
    )
    val closeAlpha by animateFloatAsState(
        targetValue = if (checked) 0f else 1f,
        animationSpec = spring(stiffness = 1000f, dampingRatio = 1f),
        label = "expressiveCloseAlpha"
    )

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(trackColor)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                onClick = { onCheckedChange?.invoke(!checked) }
            )
            .semantics { stateDescription = if (checked) "On" else "Off" },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = effIconOn,
                modifier = Modifier
                    .size(16.dp)
                    .alpha(checkAlpha)
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = iconOffColor,
                modifier = Modifier
                    .size(14.dp)
                    .alpha(closeAlpha)
            )
        }
    }
}
