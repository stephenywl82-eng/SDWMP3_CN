package com.sdw.music.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 【M3 Expressive · 波浪播放条】
 *
 * 官方 M3 Expressive 的进度形态（Material 3 Expressive "wavy progress indicator"）：
 *   · 已播段是一条**正弦波线**（stroke 更粗），未播段是**细直线**；
 *   · 播放时波在流动、振幅饱满；暂停时振幅收到近乎直线（弹簧收敛，不硬切）；
 *   · 播放头不是圆点，而是一根**竖短线**（Expressive 把圆 thumb 换成"游标刻度"）；
 *   · 拖动中振幅变大、游标加长，手指反馈更明显。
 *
 * 这个 alpha 版本的 material3 还没有官方 `WavyProgressIndicator`（jar 里无该类），
 * 所以用 Canvas 按官方形态参数自己画，视觉与官方一致，颜色全部走 ColorScheme（动态取色）。
 *
 * @param fraction 0..1 进度
 * @param color 已播段/游标颜色（一般是 accent / primary）
 * @param active 是否在播放（决定波是否流动 + 振幅是否饱满）
 * @param dragging 是否正在拖动
 * @param showTrack 画底槽。播放条默认 false（V8.193：用户要纯波浪，不要灰线）；加载/扫描类
 *                  进度必须 true —— 那种场景要读得出「整条有多长、还差多少」，没有底槽
 *                  就没有参照，等于没进度。
 * @param unplayedTrack 在「未播段」额外画一条灰色线表达剩余时长（恢复灰色线），
 *                  已播段照常画彩色波纹——灰色线=还剩多少，波纹=播到哪了，两者并存。
 *                  与 showTrack 互斥时本参数优先。
 * @param trackColor 底槽/未播线颜色，不传则取 color 的 22% alpha
 * @param indeterminate 不确定进度：fraction 自动来回摆动，用来替 LinearProgressIndicator 的
 *                      无参（indeterminate）形态。此时波恒在流动。
 */
@Composable
fun ExpressiveWavyProgress(
    fraction: Float,
    color: Color,
    active: Boolean,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
    activeStroke: Dp = 5.dp,
    wavelength: Dp = 26.dp,
    amplitude: Dp = 5.dp,
    showTrack: Boolean = false,
    unplayedTrack: Boolean = false,
    trackColor: Color = Color.Unspecified,
    indeterminate: Boolean = false
) {
    // 波沿 x 轴无限平移：周期 2.6s 走一个波长
    val phase by rememberInfiniteTransition(label = "wavyPhase").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavyPhaseValue"
    )
    // 不确定进度：进度自己在 10%~90% 之间来回扫，读起来就是"在跑、但不知道多久"。
    val sweep by rememberInfiniteTransition(label = "wavySweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wavySweepValue"
    )
    val waveActive = active || indeterminate
    // 暂停 -> 振幅弹簧收敛到接近 0（波"躺平"），恢复播放再弹起来
    val ampTarget = if (dragging) 1.35f else if (waveActive) 1f else 0.10f
    val ampScale by animateFloatAsState(
        targetValue = ampTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "wavyAmp"
    )
    // 暂停时波不再流动（相位冻结在本帧），只在播放/拖动时消耗帧
    val drift = if (waveActive || dragging) phase else 0f

    Canvas(modifier = modifier) {
        val w = size.width
        val cy = size.height / 2f
        val frac = if (indeterminate) (0.10f + 0.80f * sweep) else fraction.coerceIn(0f, 1f)
        val playedW = w * frac
        val wl = wavelength.toPx()
        val amp = amplitude.toPx() * ampScale
        val aStroke = activeStroke.toPx()

        // [P2] 底槽：加载/扫描类进度靠它做参照系，不然"已完成多少"读不出来。
        if (showTrack && !unplayedTrack) {
            drawLine(
                color = if (trackColor == Color.Unspecified) color.copy(alpha = 0.22f) else trackColor,
                start = Offset(0f, cy),
                end = Offset(w, cy),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // [P7] unplayedTrack 模式：在「未播段」画一条灰色线表达剩余时长（恢复灰色线），
        // 已播段照常画彩色波浪（波纹）——灰色线=还剩多少，波纹=播到哪了，两者并存。
        if (unplayedTrack && playedW < w - 0.5f) {
            drawLine(
                color = if (trackColor == Color.Unspecified) color.copy(alpha = 0.22f) else trackColor,
                start = Offset(playedW, cy),
                end = Offset(w, cy),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // [V8.193 / P7] 已播：正弦波（波纹）。无论是否 unplayedTrack 都画——
        // unplayedTrack 只是额外在未播段加一条灰色线，不该把波纹也吃掉。
        // [P2] showTrack=true 时上面已画了底槽作为参照系，这里仍是彩色波浪覆盖其上。
        if (playedW > 0.5f && wl > 1f) {
            val shift = drift * wl
            val stepPx = 2.5f
            val path = Path()
            var x = 0f
            path.moveTo(0f, cy + amp * sin(2f * PI.toFloat() * ((0f - shift) / wl)))
            while (x < playedW) {
                x = (x + stepPx).coerceAtMost(playedW)
                val y = cy + amp * sin(2f * PI.toFloat() * ((x - shift) / wl))
                path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = aStroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // 播放头：竖短线游标（拖动时加长加粗）
        val cursorH = (if (dragging) 26.dp else 20.dp).toPx()
        val cursorW = (if (dragging) 4.5.dp else 4.dp).toPx()
        drawLine(
            color = color,
            start = Offset(playedW, cy - cursorH / 2f),
            end = Offset(playedW, cy + cursorH / 2f),
            strokeWidth = cursorW,
            cap = StrokeCap.Round
        )
    }
}
