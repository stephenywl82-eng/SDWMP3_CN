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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.sdw.music.player.ui.theme.SDWShape
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class VuMeterStyle(val label: String) {
    ANALOG_NEEDLE("Analog"),
    MIXER("Mixer")
}

// V8.69 - this list used to be rebuilt inside the Canvas draw lambda on every frame.
private val DB_LABELS = listOf(-20 to 0, -10 to 4, -7 to 6, -5 to 8, -3 to 11, -1 to 14, 0 to 17, 1 to 20, 2 to 23, 3 to 26)

private fun barColor(ratio: Float): Color = when {
    ratio < 0.40f -> Color(0xFF00E5A0)
    ratio < 0.70f -> Color(0xFFFFB300)
    else          -> Color(0xFFFF3D3D)
}

/**
 * Mixer 柱的电平配色。
 * V8.140 原实现里 mixerColor() 定义了却从没被调用过 —— 柱子颜色只由主题
 * accent 的 hue 派生，于是"音量"这件事只有一个信息通道（高度）。这里让颜色
 * 也表达电平：过半转琥珀、逼近满刻度转红，一眼能看出过载。
 */
private fun levelColor(ratio: Float): Color = when {
    ratio < 0.45f -> Color(0xFF00E5A0)
    ratio < 0.78f -> Color(0xFFFFB300)
    else          -> Color(0xFFFF3D3D)
}

// Per-bar smoothed value holder (no coroutine, survives recompose)
internal class Smooth(val alpha: Float = 0.35f) {
    private var v = 0f
    fun update(input: Float): Float { v += (input - v) * alpha; return v }
}

// 【V8.174】jittered() 整个删掉。它做的事是「一个标量值 × 一串假的正弦扰动」，
// 三个致命后果：① 十四根柱子共用同一相位 → 整排同涨同落，看着"不实时"；
// ② 高频段那一项均值只有 ~0.85，右边几根凭空矮一截 → "右边的低"；
// ③  Height 里混了 0.35 权重的纯慢速项，跟音乐毫无关系。
// 现在每根柱子读的是 Oboe 里那 8 段级联各自的真实能量，跳动就是音乐本身。

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
        // V8.69 - the needle tweens at 80ms, so this Canvas redraws every frame while
        // playing. Both Paints used to be constructed INSIDE the draw lambda (4 new
        // android.graphics.Paint per frame: LOW + HIGH meters x 2). Build them once.
        val density = LocalDensity.current
        val dbPaint = remember(density) {
            android.graphics.Paint().apply {
                color = 0x60FFFFFF.toInt(); textSize = with(density) { 8.dp.toPx() }
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE
            }
        }
        val labelPaint = remember(density) {
            android.graphics.Paint().apply {
                color = 0x70FFFFFF.toInt(); textSize = with(density) { 10.dp.toPx() }
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
        }
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
            for ((db, tick) in DB_LABELS) {
                val a = Math.toRadians(125.0 + tick * 9.35).toFloat()
                val lx = cx + cos(a) * rLabel
                val ly = cy + sin(a) * rLabel + 4.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText("$db", lx, ly, dbPaint)
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
            drawContext.canvas.nativeCanvas.drawText(label, cx, h - 8.dp.toPx(), labelPaint)
        }
    }

    Row(modifier = modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp).height(130.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        drawMeter(lowAvg, "LOW", Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
        drawMeter(highAvg, "HIGH", Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
// ⑤ MIXER CHANNEL — 调音台式电平柱
//
// V8.140 pixel-audit 后的重画。旧版只是"一堆柱子浮在背景上"：
//   · 没有槽底 → 低电平时柱子几乎看不见，也没有高度参照
//   · 颜色只跟主题 accent 走，跟电平无关（mixerColor() 定义了却没用）
//   · 没有 dB 刻度 / 0dB 线 / CLIP 区 → 看不出当前音量、看不出削波
//   · 峰值只是顶部一块 3dp，不像调音台的 peak-hold
// 现在：暗槽 + dB 参考线 + CLIP 灯 + peak-hold 横线 + L/R 分槽。
//
// 【V8.171】再修两处：
//   ① 动画一格一格地跳 —— FFT 只有 12.5Hz（PlayerScreen 80ms 轮询），而柱子原来
//      每帧按固定 alpha(0.7) 逼近目标：60fps 下 3 帧就吃满，剩下几十毫秒原地不动，
//      看上去就是「采样到了猛跳一下、采样没到就僵住」。改成按 dt 的一阶弹道
//      （起振 35ms / 回落 260ms），与帧率无关：60fps 连续，掉到 30fps 也不会变慢变顿。
//   ② 颜色不跟歌 —— 柱身以前是「88% 电平色 + 12% 封面色」，封面色被彻底盖掉，
//      切歌了也看不出变化。现在柱身直接用封面主色（色相锁死），电平只改亮度；
//      只有逼近满刻度才掺红，过载照样一眼看得出来。柱顶那一小截仍用 levelColor()
//      做「烫了」的提示。
// ═══════════════════════════════════════════════════════════════════
private const val MIXER_STRIPS = 14
/** 【V8.190】真机诊断开关：打 VuMixer 实际收到的调色板与 palette 输出色相。 */
private const val VuMeterDebug = true
/** Oboe 里那条 8 段级联的频段数（g_band0..7）。 */
private const val NATIVE_BANDS = 8
/**
 * 频段分界线：画在第 seg 根柱子的左缘前（seg 取柱索引）。
 * 【注意】这里的值必须是「14 根柱子布局」里的柱索引，不是 24 频段的索引。
 * 旧值 {6,14,20} 是照 24 频段的边界抄来的，seg=14 恰好落在画布右缘 x=w 上、
 * seg=20 直接画到画布外 —— 右缘那条来路不明的竖线就是它。
 * 实际分派（bi = i*24/14）：sub=i0..3 / bass=i4..7 / mid=i8..11 / high=i12..13。
 */
private val MIXER_SEGMENTS = intArrayOf(4, 8, 12)

/** 起振时间常数（秒）：跟得上鼓点。 */
private const val VU_ATTACK = 0.015f
/** 回落时间常数（秒）：比真 VU 表略快，够顺滑又不拖泥带水。 */
private const val VU_RELEASE = 0.17f
/**
 * 【V8.177】VU 表电平映射 —— 推倒重来的第三版。
 *
 * 前两版病根相同：分母跟着信号自己走。
 *   · V8.176 之前：分母取当前帧峰值 → 比值与音乐无关，十四根柱齐刷刷一样高；
 *   · V8.176：分母改成"有记忆的近期峰值"（1 秒级漏放）→ 仍在跟踪信号本身，
 *     raw ÷ 自家近期峰值 ≈ 0.7~1.0 的常数，柱子钉在顶部附近 —— 这就是"没跳动幅度"。
 * 电平表要出动态，分母必须比音乐的起伏慢得多。真机实测（8 段级联，57 秒采样）：
 * 每段调制深度 1.5~2.3、峰均比 1.6~2.3 —— 数据本身动态很足，全被归一化吃掉了。
 * 现在：
 *   1) 每段先乘固定频谱补偿 VU_TILT（按实测 8 段均值定标，低→高部分拉平，
 *      保留"低频高、高频矮"的频谱形状但不至于高频看不见）；
 *   2) 分母只有一个全局参考 G —— 校正后总电平的慢包络（起 4s / 落 7s），
 *      只负责吸收"这首歌整体响不响"，完全不跟踪鼓点；
 *   3) 高度 = 校正电平 / (G × AGC_GAIN)，定标成典型段落在半程、鼓点冲顶。
 * 于是鼓点进来冲顶、走了往后掉，切歌响度变了 G 慢慢跟上，柱子永远在动。
 */
private val VU_TILT = floatArrayOf(0.85f, 1.00f, 1.00f, 1.15f, 1.03f, 1.55f, 2.18f, 1.28f)
/**
 * 全局参考包络的起/落时间常数（秒）。
 * 【V8.177 复查】原来 4s/7s 还是太快：分母取的是 14 根柱的平均值，鼓点一来所有柱子
 * 一起涨、平均值跟着涨，一除就把鼓点抵消了 —— 真机量到左边几根的相对起伏只有 3~8%，
 * 用户直说"鼓点不明显"。现在拉到 9s/14s，鼓点这种 0.1~0.5s 的瞬态完全推不动分母，
 * 柱子才跳得出来。
 */
private const val AGC_TAU_UP = 9.0f
private const val AGC_TAU_DOWN = 14.0f
/** 满刻度定标：除以 (G × AGC_GAIN) 后，典型电平约 0.5、鼓点峰值贴顶。 */
private const val AGC_GAIN = 2.7f
/**
 * 参考包络下限：静音/底噪段不让柱子被放大成满格。
 *
 * 【V8.203】注意它只是「分母的绝对下限」，别指望它把静音段的柱子压住 ——
 * 下限值只有 0.03，×AGC_GAIN 后分母仅 0.081，比正常播放小一个数量级。
 * 真正防止「切歌满格」靠的是两件事（见下方的 signalValid 冻结与 denom
 * 里的 mean 兜底），AGC_FLOOR 现在只承担「分母不会归零」这一条。
 */
private const val AGC_FLOOR = 0.03f
/**
 * 【V8.203】有效信号门限：低于这个电平就认为这一帧没有音乐（切歌间隙、
 * 暂停、静音），参考包络冻结、柱子直接归零。
 * 原来的 `AGC_FLOOR * 0.5f` 散在两处硬写，语义不清，提成一个常量。
 */
private const val SIGNAL_GATE = AGC_FLOOR * 0.5f

/**
 * 取第 pos 段（0~7 之间可带小数）的能量，相邻两段之间线性插值。
 * 14 根柱 / 8 段 → 柱位必然落在两段中间，直接取整会让相邻两根一样高。
 */
private fun bandAt(bands: FloatArray, pos: Float): Float {
    val k = pos.toInt().coerceIn(0, NATIVE_BANDS - 1)
    val k2 = (k + 1).coerceAtMost(NATIVE_BANDS - 1)
    val f = (pos - k).coerceIn(0f, 1f)
    return bands[k] * (1f - f) + bands[k2] * f
}

/**
 * 【V8.177】封面取色 —— 柱子颜色的来源。
 *
 * 演进：① V8.140 把 Palette 5 档全铺开 → 红封面能渐变出一截绿柱，成了彩虹；
 * ② V8.176 改成"第一档主色 + ±12° 微移" → 色相跟对了，但柱子看着仍不像封面色：
 *    真机量过，柱区最艳像素只有 V≈0.5 的暗红（封面是 #FC1326 那种亮红），
 *    原因是柱身套了 0.55 的 alpha 叠在暗槽上，饱和度被一起洗掉。
 * ③ 现在：色相/饱和度**原样取自封面的一到两档**（低频档 → 高频档，同色系渐变），
 *    柱身不再透明（透明 = 发灰），电平只改明度 —— 于是"这是封面那支红"一眼可辨。
 */
private class CoverPalette private constructor(
    internal val h0: Float, private val s0: Float, private val v0: Float,
    internal val h1: Float, private val s1: Float, private val v1: Float) {
    companion object {
        /** 取 a→b 的最短弧带符号色相差（-180..180），避免绕远路穿过别的色系。 */
        private fun hueDelta(a: Float, b: Float): Float {
            var d = (b - a) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** 从封面调色板取主色；提取不到就用 accentColor 兜底。 */
        fun of(coverColors: List<Int>, fallback: Color): CoverPalette {
            val tmp = FloatArray(3)
            val hs = ArrayList<FloatArray>(5)
            for (c in coverColors) {
                if (c == 0) continue
                android.graphics.Color.colorToHSV(c, tmp)
                // 近灰/近黑的档是背景色，拿来着色只会让柱子发死，跳过
                if (tmp[1] < 0.10f && tmp[2] < 0.40f) continue
                hs.add(floatArrayOf(tmp[0], tmp[1], tmp[2]))
            }
            if (hs.isEmpty()) {
                android.graphics.Color.colorToHSV(
                    android.graphics.Color.rgb(
                        (fallback.red * 255).toInt().coerceIn(0, 255),
                        (fallback.green * 255).toInt().coerceIn(0, 255),
                        (fallback.blue * 255).toInt().coerceIn(0, 255)), tmp)
                val h = tmp[0]
                // 兜底色也给它一个能看的饱和度/明度，别让柱子灰成一片
                val s = tmp[1].coerceAtLeast(0.55f)
                val v = tmp[2].coerceAtLeast(0.78f)
                return CoverPalette(h, s, v, h + 16f, s, v)
            }
            val a = hs[0]
            // 第二档：优先取色相相近（<55°）的另一档做同色系渐变，
            // 找不到就只在主色上偏 16° —— 绝不让整排柱子出现第二个色系。
            val b = hs.firstOrNull { x -> x !== a && kotlin.math.abs(hueDelta(a[0], x[0])) < 55f }
            val h1 = if (b != null) a[0] + hueDelta(a[0], b[0]) else a[0] + 16f
            val s1 = b?.get(1) ?: a[1]
            val v1 = b?.get(2) ?: a[2]
            // 【V8.205】饱和度地板 0.45 → 0.30：输入色已由 CoverTone 降过饱和（近灰档 S≈0.24），
            // 这里再抬回 0.45 等于把收敛原地撤销。
            return CoverPalette(
                a[0], a[1].coerceAtLeast(0.30f), a[2].coerceAtLeast(0.72f),
                h1, s1.coerceAtLeast(0.30f), v1.coerceAtLeast(0.72f))
        }
    }

    /** 在 t(0..1) 处取色写入 out([h,s,v])；复用数组避免每帧分配。 */
    fun sampleInto(t: Float, out: FloatArray) {
        val f = t.coerceIn(0f, 1f)
        // 沿最短弧插值：直接线性插会在跨 0°/360° 时绕一整圈，中途穿过别的色系
        out[0] = ((h0 + hueDelta(h0, h1) * f) % 360f + 360f) % 360f
        out[1] = (s0 + (s1 - s0) * f).coerceIn(0f, 1f)
        out[2] = (v0 + (v1 - v0) * f).coerceIn(0f, 1f)
    }
}
/** 柱身明度区间：低电平也保住识别度（旧版 0.30 起点让柱子发灰发黑）。 */
private const val BODY_V_MIN = 0.42f
private const val BODY_V_SPAN = 0.58f
/** peak-hold 线追随目标的时间常数（秒）。 */
private const val PEAK_TAU = 0.45f
/**
 * 超过这个电平开始掺红（0dB 参考线在 0.92h，留一点余量）。
 * 【V8.177】0.80 → 0.88：真机量过，蓝封面时"掺红"的像素能占到柱区有色像素的
 * 两成，蓝色被冲成紫红，看着就不像封面色了。现在只有真正逼近满刻度才转红。
 */
private const val HOT_AT = 0.88f
/** 柱顶"烫了"那道高亮的门槛，比掺红再高一点，避免常态出现。 */
private const val HOT_CAP_AT = 0.93f
/** CLIP 灯余辉时长（秒）。 */
private const val CLIP_HOLD_SEC = 0.8f

/**
 * 每根柱子的弹道状态。放在 Canvas 之外 remember，跨重组存活。
 *
 * 之所以要自己存帧时间戳：插值必须知道 dt，而 dt 只能由「上一帧是什么时候」算出，
 * 用固定 alpha 的写法在 60Hz / 120Hz / 掉帧这三种情况下速度会差好几倍。
 */
internal class MixerBallistics {
    /** 当前柱高（插值后的显示值）。 */
    val level = FloatArray(MIXER_STRIPS)
    /** 右声道柱高：与上面那条独立插值，读的是右声道自己的能量。 */
    val levelR = FloatArray(MIXER_STRIPS)
    /** peak-hold 线高度。 */
    val peak = FloatArray(MIXER_STRIPS)
    /** 右声道的 peak-hold 线高度。 */
    val peakR = FloatArray(MIXER_STRIPS)
    /** CLIP 灯余辉 0..1。 */
    val clip = FloatArray(MIXER_STRIPS)
    /**
     * 【V8.177】全局参考包络（校正后总电平的慢均值），整块表共用一个。
     * 【V8.203】静音帧不更新它 —— 详见 VuMixer 里的 signalValid 冻结逻辑。
     */
    var ref = 0f
    /**
     * 【V8.203 诊断】上一帧算不算「有效信号」，用来捕捉静音→出声的翻转帧 ——
     * 那正是「切歌满格」的高发点。详见 VuMixer 里的 SDW_VUAGC 日志。
     */
    var signalWasValid = false
    /**
     * 参考包络是否已用真实电平初始化过。
     * 【V8.203】原实现是「页面第一帧无条件初始化一次」，但那一帧很可能正好是
     * 静音（进页面时曲子暂停 / 刚切完歌），ref 就被永久锁死在一个接近 0 的值上，
     * 之后每次切歌分母都塌到 AGC_FLOOR，柱子必然满格。现在改成「直到出现第一个
     * 有效信号帧才初始化」，静音起步的情况由后续那一帧自己纠正。
     */
    var refReady = false
    /** 每根柱的校正后电平（频谱补偿后的 L/R），两遍扫描之间的暂存，避免每帧分配。 */
    val cL = FloatArray(MIXER_STRIPS)
    val cR = FloatArray(MIXER_STRIPS)
    /** 上一帧时间戳（毫秒，elapsedRealtime），0 表示还没跑过。 */
    var lastFrameAt = 0L
}

@Composable
private fun VuMixer(sub: Float, bass: Float, mid: Float, high: Float,
                    bandsL: FloatArray, bandsR: FloatArray,
                    isActive: Boolean, accentColor: Color,
                    /** 【V8.176】封面调色板（Palette 提取的 5 档主色），柱子着色的真正来源。 */
                    coverColors: List<Int>,
                    modifier: Modifier) {
    val bal = remember { MixerBallistics() }
    val peaks = bal.peak
    val peaksR = bal.peakR
    // [v6.0.15] Own 60fps frame clock — decouples from FFT polling (80ms),
    // keeps peak-fall animation smooth even when Edge Glow is off.
    // 【V8.428】曾并入玩家页统一 ticker（100ms），实测用户反馈"VU 表动画会变卡"，
    // 已按要求**恢复原样**：VU 表的平滑度优先于省电。
    var tick by remember { mutableLongStateOf(0L) }
    val activeNow by rememberUpdatedState(isActive)
    LaunchedEffect(Unit) {
        while (true) {
            // 空闲且峰值已衰减到底 → 停止 60fps 帧时钟，低频轮询等待重新激活（省电）
            val anyPeak = peaks.any { it > 0.04f }
            if (!activeNow && !anyPeak) {
                kotlinx.coroutines.delay(120)
                continue
            }
            withFrameMillis { tick = it }
        }
    }
    // read to trigger recompose every frame（dt 由上一帧时间戳算出，不再直接用 tick）
    @Suppress("UNUSED")
    val f = tick

    val segLine = Color.White.copy(alpha = 0.10f)
    // 【V8.176】封面取色：柱子颜色沿调色板的频段轴铺开，而不是十四根一个色。
    val palette = remember(coverColors, accentColor) {
        val p = CoverPalette.of(coverColors, accentColor)
        if (VuMeterDebug) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(accentColor.toArgb(), hsv)
            android.util.Log.d("SDW_VuColor",
                "VuMixer palette built: n=${coverColors.size} " +
                "colors=[${coverColors.joinToString(",") { String.format("#%06X", it and 0xFFFFFF) }}] " +
                "accentH=%.0f h0=%.0f h1=%.0f".format(hsv[0] * 360f, p.h0, p.h1))
        }
        p
    }
    // 采样复用同一块数组，别在每帧的 draw 里 new 出一串临时对象
    val sampled = remember { FloatArray(3) }
    val sampledR = remember { FloatArray(3) }
    val slotColor = remember(accentColor) { accentColor.copy(alpha = 0.10f) }
    // 0dB 参考线 / 危险区也沾一点封面色，整块 UI 的色调才是一套
    val refLine = Color(0xFFFFB300).copy(alpha = 0.32f)

    Canvas(modifier = modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width; val h = size.height
        val gap = 3.5.dp.toPx()
        // 【V8.174】不再给刻度标签留槽：右边那行孤零零的 "0" 是全表唯一没有
        // 任何对应柱子的一列，空着又难看，索性把整幅宽度还给柱子。
        val stripW = ((w - gap * (MIXER_STRIPS + 1)) / MIXER_STRIPS)
            .coerceAtLeast(3.dp.toPx())
        // 圆角跟着全站 token 走，不再纯按柱宽比例（宽屏窄屏观感一致）
        val cr = minOf(stripW * 0.34f, SDWShape.Thumb.toPx())
        val pad = 1.25.dp.toPx()
        // L/R 各占一半，中间留一道 pad 宽的缝。
        // 旧版 half = stripW/2 - pad：L 的右缘恰好等于 R 的左缘，两根永远贴死，
        // 再加 peak 线横跨整柱宽度，「两个声道」完全看不出来。
        val half = (stripW - pad * 3f) / 2f
        // 柱区右缘，参考线/危险区都只画到这里
        val meterRight = gap + MIXER_STRIPS * (stripW + gap) - gap

        // ── 危险区：0dB 以上一条淡红带，标示"再上去就削波了" ──
        // 旧版从 x=0 画到 x=w，比柱区左右各探出一截，看着像画布溢出。
        if (h * 0.06f > 1f) {
            drawRoundRect(Color(0xFFFF3D3D).copy(alpha = 0.09f), Offset(gap, 0f),
                Size(meterRight - gap, h * 0.06f), CornerRadius(cr, cr))
        }
        // ── dB 参考线（只画到柱区右缘）──
        drawLine(refLine, Offset(gap, h * 0.92f),
            Offset(meterRight, h * 0.92f), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.10f), Offset(gap, h * 0.35f),
            Offset(meterRight, h * 0.35f), 1.dp.toPx())
        // ── 频段分界（对应 Oboe 的 8 段级联），越界的直接跳过 ──
        for (seg in MIXER_SEGMENTS) {
            val sx = gap + seg * (stripW + gap) - gap / 2f
            if (sx <= gap || sx >= meterRight) continue
            drawLine(segLine, Offset(sx, 0f), Offset(sx, h), 1.dp.toPx())
        }

        // ── 帧间隔：插值必须按 dt 走，固定 alpha 在 60/90/120Hz 上速度会差好几倍 ──
        // （dt 上限 200ms：切后台回来时 tick 会跳一大截，别让柱子瞬间弹到顶）
        val dtMs = if (bal.lastFrameAt == 0L) 16L
                   else (tick - bal.lastFrameAt).coerceIn(0L, 200L)
        bal.lastFrameAt = tick
        val dt = dtMs / 1000f
        val kAttack = 1f - kotlin.math.exp(-dt / VU_ATTACK)
        val kRelease = 1f - kotlin.math.exp(-dt / VU_RELEASE)
        val kPeak = 1f - kotlin.math.exp(-dt / PEAK_TAU)

        // ── 第一遍：频谱补偿，顺便把整帧的平均电平算出来 ──
        // 【V8.177 复查】全局参考必须**整帧只更新一次**。上一版把它写在柱循环里，
        // 14 根柱各推一次 → 等效时间常数被缩短 14 倍（4s→约 0.29s、7s→约 0.5s），
        // 正好落回鼓点调制的频段，等于又把动态压平了一遍。现在两遍扫描：
        // 第一遍只算电平，第二遍做弹道 + 绘制。
        var sum = 0f
        for (i in 0 until MIXER_STRIPS) {
            // 柱 i → 0~7 段之间的浮点位置（14 根柱装不进 8 段，取中间值在相邻两段间插值）
            val pos = i * (NATIVE_BANDS - 1).toFloat() / (MIXER_STRIPS - 1)
            // 固定频谱补偿：保留"低频高、高频矮"的真实形状，只把高频抬到看得见。
            val tilt = bandAt(VU_TILT, pos)
            val l = bandAt(bandsL, pos) * tilt
            val r = bandAt(bandsR, pos) * tilt
            bal.cL[i] = l
            bal.cR[i] = r
            sum += (l + r) * 0.5f
        }
        val mean = sum / MIXER_STRIPS
        // 【V8.203 修「切歌满格」】这一帧算不算数，看有没有有效信号。
        val signalValid = mean >= SIGNAL_GATE
        // 全局参考 G：校正后总电平的慢包络。起 9s / 落 14s —— 远慢于鼓点的 0.3~1s
        // 调制周期，所以它只代表"这首歌整体多响"，鼓点冲不进分母，柱子才有得跳。
        // （更早那版分母跟着信号自己走，raw÷自家峰值≈常数，柱子钉死在顶部附近。）
        //
        // 【V8.203】核心修复①：静音帧【冻结】参考包络。
        // 原实现在 mean < ref 时一律按 AGC_TAU_DOWN(14s) 往下走 —— 然而 cL 那边
        // 的 EMA 是 τ=25ms，快了近五百倍。于是切歌静音期间 ref 被一路拖向 0，
        // 而此时新歌还没进来；等新歌第一帧的 cL 从 0 爬起来，分母却还停在被拖低
        // 的位置（甚至贴着 AGC_FLOOR），cL/denom 直接爆到几十倍然后被 coerce 削平
        // → 十四根柱全满格，要等 ref 按 9s 爬回来（十几秒）才落下来。
        // 静音时不更新 ref，分母就永远保持切歌前的水平，新歌起来那一刻比值是正常的。
        if (!bal.refReady && signalValid) { bal.ref = mean; bal.refReady = true }
        if (signalValid) {
            val kRef = 1f - kotlin.math.exp(
                -dt / (if (mean > bal.ref) AGC_TAU_UP else AGC_TAU_DOWN))
            bal.ref += (mean - bal.ref) * kRef
        }
        // 【V8.203 核心修复②】分母下限再跟本帧平均电平兜一道。
        // 只按 AGC_FLOOR 兜底时，静音边缘（mean 刚过 SIGNAL_GATE 但 ref 还没跟上）
        // 的分母依然是 0.081 那个极小值，照样能把新歌第一帧推成满格。
        // 把 mean 也纳入 maxOf，保证分母至少有「当下这帧的电平」那么大盘，
        // 比值就不会脱离物理意义。低电平段柱子略矮，比满格假象划算。
        val denom = maxOf(bal.ref, mean, AGC_FLOOR) * AGC_GAIN

        // 【V8.203 诊断】「静音 → 出声」的翻转帧就是切歌满格的高发点，打一行出来。
        // 修复前这一行会看到 hot=14/14（分子分母脱节、被 coerce 削平成满格）；
        // 修复后应当是 hot=0，且 denom 不会塌到 AGC_FLOOR×AGC_GAIN 那个下限。
        if (signalValid != bal.signalWasValid) {
            bal.signalWasValid = signalValid
            if (signalValid) {
                var hot = 0
                for (i in 0 until MIXER_STRIPS) {
                    if (bal.cL[i] / denom >= 0.995f) hot++
                }
                android.util.Log.d("SDW_VUAGC",
                    "wake mean=%.4f ref=%.4f denom=%.4f hot=%d/%d"
                        .format(mean, bal.ref, denom, hot, MIXER_STRIPS))
            }
        }

        for (i in 0 until MIXER_STRIPS) {
            val cL = bal.cL[i]
            val cR = bal.cR[i]
            // 完全静音时别把底噪放大成满格（【V8.203】门限提成 SIGNAL_GATE 常量）
            val tL = if (!signalValid) 0f else (cL / denom).coerceIn(0f, 1f)
            val tR = if (!signalValid) 0f else (cR / denom).coerceIn(0f, 1f)
            // 【V8.188】R 也要跟 L 一样受 isActive 门控：暂停/停止时两半同时归
            // 底位，否则静止画面上右半边还在自己漂。
            val target = if (isActive) tL else 0.03f
            val targetR = if (isActive) tR else 0.03f
            // 起振快、回落慢的一阶弹道：12.5Hz 的采样在 60fps 下被摊成连续运动
            val prev = bal.level[i]
            bal.level[i] = (prev + (target - prev) * (if (target > prev) kAttack else kRelease))
                .coerceIn(0f, 1f)
            // 右声道走自己的弹道：它现在读的是真数据，不是左边的固定比例
            val prevR = bal.levelR[i]
            bal.levelR[i] = (prevR + (targetR - prevR) * (if (targetR > prevR) kAttack else kRelease))
                .coerceIn(0f, 1f)
            val v = bal.level[i]
            val vR = bal.levelR[i]
            // Peak: instant rise, slow fall
            if (v > peaks[i]) peaks[i] = v
            else peaks[i] += (v - peaks[i]) * kPeak   // 按 dt 衰减，帧率无关
            val pk = peaks[i].coerceIn(0f, 1f)
            val pr = peaksR[i]
            peaksR[i] = if (vR > pr) vR else pr + (vR - pr) * kPeak
            val pkR = peaksR[i].coerceIn(0f, 1f)
            // CLIP：过 0.985 点亮，之后约 0.8s 渐隐（同样改成按秒衰减）
            bal.clip[i] = if (v > 0.985f) 1f else (bal.clip[i] - dt / CLIP_HOLD_SEC).coerceAtLeast(0f)

            val x = gap + i * (stripW + gap)
            // 槽底：低电平时也能看出"这一路存在"，并给柱子高度一个参照
            drawRoundRect(slotColor, Offset(x, 0f), Size(stripW, h), CornerRadius(cr, cr))

            palette.sampleInto(i.toFloat() / (MIXER_STRIPS - 1), sampled)
            val hot = ((v - HOT_AT) / (1f - HOT_AT)).coerceIn(0f, 1f)
            // 【V8.177】柱身 = 封面那支颜色本身（色相/饱和度原样取用），电平只改明度。
            // 旧版还给柱身套 0.55 的 alpha 叠在暗槽上 —— 透明 = 饱和度被背景洗掉，
            // 量出来柱区最艳像素只有 V≈0.5 的暗红，怎么看都不像封面色。现在不透明。
            sampled[2] = (BODY_V_MIN + v * BODY_V_SPAN).coerceIn(0f, 1f)
            val body = Color(android.graphics.Color.HSVToColor(sampled))
            val lc = if (hot > 0f) lerp(body, Color(0xFFFF3D3D), hot * 0.35f) else body
            val lh = (v * h).coerceAtLeast(0f)
            if (lh > 0.5f) {
                drawRoundRect(lc, Offset(x + pad, h - lh), Size(half, lh), CornerRadius(cr, cr))
                // 右声道：同一色系压暗一档 + 中间留缝来区分，不再靠透明度（透明就发灰）
                val rh = (vR * h).coerceAtLeast(0f)
                if (rh > 0.5f) {
                    sampledR[0] = sampled[0]
                    sampledR[1] = sampled[1]
                    sampledR[2] = (BODY_V_MIN + vR * BODY_V_SPAN).coerceIn(0f, 1f) * 0.70f
                    val bodyR = Color(android.graphics.Color.HSVToColor(sampledR))
                    drawRoundRect(if (hot > 0f) lerp(bodyR, Color(0xFFFF3D3D), hot * 0.35f) else bodyR,
                        Offset(x + pad + half + pad, h - rh), Size(half, rh), CornerRadius(cr, cr))
                }
            }
            // 柱顶一小截电平色：只在接近满刻度时亮起，当作"烫了"的高亮，
            // 平时不出现，免得又变回「一堆彩色小方块」的老样子。
            if (v > HOT_CAP_AT) {
                drawRoundRect(levelColor(v).copy(alpha = 0.9f),
                    Offset(x + pad, h - lh), Size(half, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            }
            // Peak-hold：L/R 各一条，各自只跨自己的半边。
            // 旧版一条横跨整柱宽，把本来就没缝的两个声道彻底糊成一块。
            if (pk > 0.03f) {
                val py = (h - pk * h - 2.dp.toPx()).coerceAtLeast(1.dp.toPx())
                drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x + pad, py),
                    Size(half, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                if (pkR > 0.03f) {
                    val pyR = (h - pkR * h - 2.dp.toPx()).coerceAtLeast(1.dp.toPx())
                    drawRoundRect(Color.White.copy(alpha = 0.55f),
                        Offset(x + pad + half + pad, pyR),
                        Size(half, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                }
            }
            // CLIP 灯
            if (bal.clip[i] > 0.02f) {
                drawRoundRect(Color(0xFFFF1744).copy(alpha = bal.clip[i]), Offset(x + pad, 1.dp.toPx()),
                    Size(stripW - pad * 2f, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
@Composable
fun VuMeter(
    sub: Float = 0f, bass: Float = 0f, mid: Float = 0f, high: Float = 0f, rms: Float = 0f,
    /** Oboe 里左右声道各自那条 8 段级联的输出，Mixer 柱的真数据源。 */
    bandsL: FloatArray = FloatArray(8), bandsR: FloatArray = FloatArray(8),
    isActive: Boolean,
    modifier: Modifier = Modifier,
    style: VuMeterStyle = VuMeterStyle.MIXER,
    accentColor: Color = Color(0xFFFFA726),
    /** 【V8.176】封面调色板（Palette 5 档主色）。Mixer 柱据此取色；留空则退回 accentColor。 */
    coverColors: List<Int> = emptyList()
) {
    when (style) {
        VuMeterStyle.ANALOG_NEEDLE -> VuAnalogNeedles(sub, bass, mid, high, isActive, modifier, accentColor)
        VuMeterStyle.MIXER         -> VuMixer(sub, bass, mid, high, bandsL, bandsR, isActive, accentColor, coverColors, modifier)
    }
}
