package com.sdw.music.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure Compose replacement for the legacy [com.sdw.music.player.view.WaveSeekBar]
 * (a custom android.view.View). Mimics the Android 13 SquigglySlider effect:
 * rounded track, sine-wave filled progress with gradient, thumb ring, and a
 * continuously scrolling wave phase while playing.
 */
private val TRACK_HEIGHT = 8.dp
private val THUMB_RADIUS = 8.dp
private val WAVE_AMPLITUDE = 3.dp
private val WAVE_FREQUENCY = 0.04f
private val TRACK_COLOR = Color(0x33FFFFFF)
private val THUMB_RING_COLOR = Color.White

@Composable
fun WaveSeekBar(
    progress: Int,
    max: Int = 100,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(28.dp),
    onSeekStart: (Int) -> Unit = {},
    onSeek: (Int) -> Unit = {},
    onSeekEnd: (Int) -> Unit = {}
) {
    val progressColor = MaterialTheme.colorScheme.primary
    val waveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val thumbColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val trackH = with(density) { TRACK_HEIGHT.toPx() }
    val thumbR = with(density) { THUMB_RADIUS.toPx() }
    val amp = with(density) { WAVE_AMPLITUDE.toPx() }
    val pad = thumbR + with(density) { 4.dp.toPx() }

    val transition = rememberInfiniteTransition()
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing))
    )
    val phase = if (isPlaying) animatedPhase else 0f

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { onSeekStart(progress.coerceIn(0, max)) },
                onHorizontalDrag = { change, _ ->
                    val x = change.position.x
                    val w = size.width - 2 * pad
                    if (w > 0) {
                        val p = ((x - pad) / w * max).toInt().coerceIn(0, max)
                        onSeek(p)
                    }
                },
                onDragEnd = { onSeekEnd(progress.coerceIn(0, max)) }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        val left = pad
        val right = w - pad
        val centerY = h / 2f
        val trackTop = centerY - trackH / 2f
        val thumbX = left + (right - left) * progress / max.toFloat()

        drawRoundRect(
            color = TRACK_COLOR,
            topLeft = Offset(left, trackTop),
            size = Size(right - left, trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )

        if (thumbX > left) {
            val path = Path()
            val halfH = trackH / 2f
            path.moveTo(left, centerY - halfH)
            var x = 0f
            while (x <= thumbX - left) {
                val wx = left + x
                val y = centerY - halfH + amp * sin((phase + x * WAVE_FREQUENCY).toDouble()).toFloat()
                path.lineTo(wx, y)
                x += 2f
            }
            path.lineTo(thumbX, centerY + halfH)
            var x2 = thumbX - left
            while (x2 >= 0f) {
                val wx = left + x2
                val y = centerY + halfH + amp * 0.5f *
                    sin((phase + x2 * WAVE_FREQUENCY + PI.toFloat()).toDouble()).toFloat()
                path.lineTo(wx, y)
                x2 -= 2f
            }
            path.close()
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    0f to progressColor,
                    1f to waveColor,
                    start = Offset(left, 0f),
                    end = Offset(right, 0f)
                )
            )
        }

        drawCircle(color = THUMB_RING_COLOR, radius = thumbR + 2f, center = Offset(thumbX, centerY))
        drawCircle(color = thumbColor, radius = thumbR, center = Offset(thumbX, centerY))
    }
}
