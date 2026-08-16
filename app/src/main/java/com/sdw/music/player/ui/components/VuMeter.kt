package com.sdw.music.player.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class VuMeterStyle(val label: String) {
    ANALOG_NEEDLE("Analog"),
    MIXER("Mixer")
}

private fun barColor(ratio: Float): Color = when {
    ratio < 0.40f -> Color(0xFF00E5A0)
    ratio < 0.70f -> Color(0xFFFFB300)
    else          -> Color(0xFFFF3D3D)
}
private fun mixerColor(lv: Float): Color = when {
    lv < 0.30f -> Color(0xFF00E676)
    lv < 0.70f -> Color(0xFFFFCA28)
    else       -> Color(0xFFFF1744)
}

// Per-bar smoothed value holder (no coroutine, survives recompose)
internal class Smooth(val alpha: Float = 0.35f) {
    private var v = 0f
    fun update(input: Float): Float { v += (input - v) * alpha; return v }
}

/**
 * Add per-bar jitter so not every bar in a band looks identical.
 * Uses a combination of sinusoidal position modulation + time-based pseudo-random
 * to create a "spectrum" look from a single scalar band value.
 */
private fun jittered(v: Float, barIndex: Int, totalBars: Int, frameSeed: Long): Float {
    if (v < 0.01f) return 0f
    val pos = barIndex.toFloat() / (totalBars - 1)
    val shape = when {
        pos < 0.25f -> 0.6f + 0.4f * sin(pos * Math.PI.toFloat() * 4f)
        pos < 0.45f -> 1.0f - 0.15f * sin(pos * Math.PI.toFloat() * 2.5f)
        pos < 0.65f -> 0.85f + 0.15f * sin(pos * Math.PI.toFloat() * 3f)
        else        -> 0.7f + 0.3f * sin(pos * Math.PI.toFloat() * 5f + frameSeed * 0.1f)
    }
    // Deterministic jitter using trig hash: avoids Random alloc per-bar per-frame
    val h = sin((barIndex * 7919 + frameSeed % 1009).toFloat() * 0.73f) * 0.5f + 0.5f
    val jitter = 0.85f + h * 0.30f
    return (v * shape * jitter).coerceIn(0f, 1f)
}

// ═══════════════════════════════════════════════════════════════════
// ② ANALOG NEEDLES — rotated 180°, gap at bottom, arc sweeps upward
// Pointer sits at bottom, empty zone at foot avoids progress bar overlap
@Composable
private fun VuAnalogNeedles(sub: Float, bass: Float, mid: Float, high: Float,
                            isActive: Boolean, modifier: Modifier, accentColor: Color) {
    val smooth = remember { Smooth(0.3f) }
    val lowAvg = smooth.update((sub + bass) * 0.5f)
    val highAvg = smooth.update((mid + high) * 0.5f)
    val warm = accentColor

    // Theme-derived bezel colors from accent
    val bezelOuter = accentColor.copy(alpha = 0.08f)
    val bezelInner = accentColor.copy(alpha = 0.04f)

    @Composable
    fun drawMeter(level: Float, label: String, modifier: Modifier) {
        val needleAngle by animateFloatAsState(
            // Gap at bottom: pivot at 125° (6 o'clock + gap), sweep 290° upward to 415°
            targetValue = if (isActive) 125f + level.coerceIn(0f, 1f) * 290f else 125f,
            animationSpec = tween(80), label = "vun"
        )
        Canvas(modifier = modifier) {
            val w = size.width; val h = size.height
            // Full-face bezel
            drawRoundRect(bezelOuter, Offset.Zero, Size(w, h), CornerRadius(8.dp.toPx()))
            drawRoundRect(bezelInner, Offset(2.dp.toPx(), 2.dp.toPx()),
                Size(w - 4.dp.toPx(), h - 4.dp.toPx()), CornerRadius(6.dp.toPx()))
            // Pivot at upper area, gap at bottom
            val cx = w / 2f
            val cy = h * 0.56f
            val rOuter = minOf(w * 0.38f, h * 0.40f)
            val rInner = rOuter * 0.72f
            val rLabel = rOuter * 1.18f
            // Tick marks: 31 ticks, sweep 290° starting from 125° (bottom-left gap)
            for (j in 0..30) {
                val deg = 125.0 + j * 9.35
                val a = Math.toRadians(deg).toFloat()
                val thick = j % 5 == 0
                val i2 = if (thick) rOuter * 0.72f else rOuter * 0.85f
                val o2 = rOuter
                val tc = if (j >= 26) Color(0xFFFF5252).copy(alpha = 0.85f)
                         else Color.White.copy(alpha = if (thick) 0.6f else 0.18f)
                drawLine(tc, Offset(cx + cos(a) * i2, cy + sin(a) * i2),
                         Offset(cx + cos(a) * o2, cy + sin(a) * o2),
                         if (thick) 1.5.dp.toPx() else 0.6.dp.toPx())
            }
            // dB labels
            val p = android.graphics.Paint().apply {
                color = 0x60FFFFFF.toInt(); textSize = 8.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE
            }
            val dbLabels = listOf(-20 to 0, -10 to 4, -7 to 6, -5 to 8, -3 to 11, -1 to 14, 0 to 17, 1 to 20, 2 to 23, 3 to 26)
            for ((db, tick) in dbLabels) {
                val a = Math.toRadians(125.0 + tick * 9.35).toFloat()
                val lx = cx + cos(a) * rLabel
                val ly = cy + sin(a) * rLabel + 4.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText("$db", lx, ly, p)
            }
            // Needle
            val na = Math.toRadians(needleAngle.toDouble()).toFloat()
            val tipR = rInner * 0.88f
            drawLine(Color.White.copy(alpha = 0.25f), Offset(cx, cy),
                     Offset(cx + cos(na) * tipR, cy + sin(na) * tipR), 2.dp.toPx())
            drawLine(warm, Offset(cx, cy),
                     Offset(cx + cos(na) * tipR, cy + sin(na) * tipR), 1.4.dp.toPx())
            drawCircle(warm, 2.5.dp.toPx(), Offset(cx, cy))
            // Label at bottom
            val lp = android.graphics.Paint().apply {
                color = 0x70FFFFFF.toInt(); textSize = 10.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(label, cx, h - 8.dp.toPx(), lp)
        }
    }

    Row(modifier = modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp).height(130.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        drawMeter(lowAvg, "LOW", Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
        drawMeter(highAvg, "HIGH", Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
// ⑤ MIXER CHANNEL — vertical strips with cover-color tint + delayed peak fall
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun VuMixer(sub: Float, bass: Float, mid: Float, high: Float,
                    isActive: Boolean, accentColor: Color, modifier: Modifier) {
    val smooth = remember { Array(14) { Smooth(0.7f) } }
    val peaks = remember { FloatArray(14) }
    val frame = remember { longArrayOf(0L) }
    // [v6.0.15] Own 60fps frame clock — decouples from FFT polling (80ms),
    // keeps peak-fall animation smooth even when Edge Glow is off.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { tick = it }
        }
    }
    val f = tick  // read to trigger recompose every frame

    // Pre-compute 14 strip base hues from accentColor (once, not per-frame)
    val stripHues = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (accentColor.red * 255).toInt().coerceIn(0, 255),
                (accentColor.green * 255).toInt().coerceIn(0, 255),
                (accentColor.blue * 255).toInt().coerceIn(0, 255)
            ), hsv
        )
        val baseHue = hsv[0]
        FloatArray(14) { i -> (baseHue + (i - 7f) * 2.5f + 360f) % 360f }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width; val h = size.height
        val strips = 14; val gap = 3.5.dp.toPx()
        val stripW = ((w - gap * (strips - 1) - gap * 2) / strips).coerceAtLeast(3.dp.toPx())
        val cr = stripW * 0.4f
        for (i in 0 until strips) {
            val bi = (i * 24 / strips).coerceIn(0, 23)
            val raw = when { bi < 6 -> sub; bi < 14 -> bass; bi < 20 -> mid; else -> high }
            val target = if (isActive) jittered(raw, bi, 24, f) else 0.03f
            val v = smooth[i].update(target)
            // Peak: instant rise, slow fall
            if (v > peaks[i]) peaks[i] = v
            else peaks[i] += (v - peaks[i]) * 0.20f
            val pk = peaks[i].coerceIn(0f, 1f)
            val x = gap + i * (stripW + gap)
            val half = stripW / 2f - 1.dp.toPx()
            // Color: use Android native HSV→ARGB (faster than Compose Color.hsl)
            val hCol = stripHues[i]
            val stripAlpha = (0.15f + v * 0.7f).coerceIn(0f, 1f)
            val stripColor = colorFromHsv(hCol, 0.55f + v * 0.35f, 0.25f + v * 0.55f, stripAlpha)
            val peakColor = colorFromHsv(hCol, 0.55f + pk * 0.35f, 0.45f + pk * 0.55f, (0.78f + pk * 0.22f).coerceAtMost(0.92f))
            // Main strip
            val lh = (v * h).coerceAtLeast(0f)
            if (lh > 1f) drawRoundRect(stripColor, Offset(x, h - lh), Size(half, lh), CornerRadius(cr, cr))
            // Peak block
            val pkH = (pk * h).coerceAtLeast(0f)
            if (pk > 0.03f && pkH > 2f) drawRoundRect(peakColor, Offset(x, h - pkH - 2.dp.toPx()),
                Size(half, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            // Right channel (slightly dimmer)
            val rv = v * 0.92f
            val rh = (rv * h).coerceAtLeast(0f)
            val rc = colorFromHsv(hCol, 0.55f + rv * 0.35f, 0.25f + rv * 0.55f, stripAlpha * 0.85f)
            val rx = x + stripW / 2f + 1.dp.toPx()
            if (rh > 1f) drawRoundRect(rc, Offset(rx, h - rh), Size(half, rh), CornerRadius(cr, cr))
            val rpk = (pk * 0.92f).coerceIn(0f, 1f)
            val rpkH = (rpk * h).coerceAtLeast(0f)
            if (rpk > 0.03f && rpkH > 2f) drawRoundRect(peakColor.copy(alpha = 0.78f), Offset(rx, h - rpkH - 2.dp.toPx()),
                Size(half, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
        }
    }
}

// Fast HSV→ARGB: native FloatArray API avoids Compose Color.hsl allocs
private fun colorFromHsv(h: Float, s: Float, v: Float, alpha: Float): Color {
    val hsv = floatArrayOf(h, s.coerceIn(0f,1f), v.coerceIn(0f,1f))
    val argb = android.graphics.Color.HSVToColor((alpha * 255).toInt().coerceIn(0, 255), hsv)
    return Color(argb)
}

// ═══════════════════════════════════════════════════════════════════
@Composable
fun VuMeter(
    sub: Float = 0f, bass: Float = 0f, mid: Float = 0f, high: Float = 0f, rms: Float = 0f,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    style: VuMeterStyle = VuMeterStyle.MIXER,
    accentColor: Color = Color(0xFFFFA726)
) {
    when (style) {
        VuMeterStyle.ANALOG_NEEDLE -> VuAnalogNeedles(sub, bass, mid, high, isActive, modifier, accentColor)
        VuMeterStyle.MIXER         -> VuMixer(sub, bass, mid, high, isActive, accentColor, modifier)
    }
}
