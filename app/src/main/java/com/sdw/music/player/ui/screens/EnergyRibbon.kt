package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.playback.MusicService

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 【V8.369】能量带 —— DJ 界面的「滚动波形」。
 *
 * 数据来源：项目已有的 8 段 FFT。
 *   - A 轨：`getBands8()`，native 音频回调每帧更新。**注意它是混音之后的输出**，
 *     所以交叉淡化期间读到的是 A+B 的和，不是纯 A。
 *   - B 轨：`getBandsB()`，V8.370 新增，在 B 轨解码线程里单独算，不经过混音。
 *     这是唯一能代表「下一首」真实频谱的数据源。
 * **零 native 改动、零新增解码开销** —— 引擎早就在算，我们只是把它读出来画出来。
 *
 * 采样在**独立的 IO协程**里做，每 60ms 一次。
 * 绝不在 Composable 里直接调 JNI：那会在主线程每帧做 8 次跨语言调用，
 * 而且会让 DJ 关掉时也白白消耗（违反 V8.363「普通模式音频链路只读」）。
 *
 * 这是「最近 N 秒的能量起伏」，不是整首歌的静态包络。
 * 对用户来说这正是需要的：它显示歌的情绪起伏，比静态波形更有信息量，
 * 视觉上也更接近 djay 那条彩色波形桥。
 */
class EnergyRibbon(val slots: Int = 140) {
    /** Bumped on every push so the Canvas recomposes. Draws nothing by itself. */
    var revision: Int = 0
        private set

    /** 每槽 8 段的能量快照，取 RMS 作为该槽高度。环形缓冲。 */
    private val buf = FloatArray(slots)
    private var head = 0
    private var filled = 0

    /** 写入一份 8 段能量。 */
    fun push(bands: FloatArray) {
        if (bands.size < 8) return
        // 8 段求 RMS：比取最大值更接近听感上的"响度"
        var acc = 0f
        for (i in 0 until 8) {
            val v = bands[i]
            acc += v * v
        }
        buf[head] = kotlin.math.sqrt(acc / 8f)
        head = (head + 1) % slots
        if (filled < slots) filled++
        revision++
    }

    /** 取第 i 个槽（0 = 最旧）。 */
    fun at(i: Int): Float {
        if (i >= filled) return 0f
        val idx = (head - filled + i + slots * 2) % slots
        return buf[idx]
    }

    fun clear() {
        buf.fill(0f)
        head = 0
        filled = 0
    }
}

/** 哪条轨的数据源。 */
enum class RibbonTrack { A, B }

/**
 * 启动采样协程，返回最新的能量带。
 *
 * 协程对象必须跨重组稳定，所以用 remember 拿实例、LaunchedEffect 只管采样生命周期。
 */
@Composable
private fun rememberEnergyRibbon(active: Boolean, track: RibbonTrack): EnergyRibbon {
    val ribbon = remember { EnergyRibbon() }
    LaunchedEffect(active, track) {
        if (!active) {
            ribbon.clear()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            while (isActive) {
                val bands = try {
                    val p = MusicService.instance?.oboeDirectPlayer
                    when (track) {
                        // A 轨读混音后输出；B 轨必须用独立级联，两者不可混用
                        RibbonTrack.A -> p?.getBands8()
                        RibbonTrack.B -> p?.getBandsB()
                    }
                } catch (_: Throwable) { null }
                if (bands != null) ribbon.push(bands)
                delay(100)   // 【V8.428】60ms → 100ms，与玩家页统一节拍
            }
        }
    }
    return ribbon
}

/**
 * 绘制一条能量带。
 * @param heightDp 条高
 * @param tint 主色
 * @param secondarySecondColor 是否把上半段用第二个色（模拟频谱高低两段）
 */
@Composable
fun EnergyRibbonBar(
    active: Boolean,
    modifier: Modifier = Modifier,
    heightDp: Dp = 16.dp,
    tint: Color = Color(0xFF4A7FB5),
    progress: Float = 0f,
    track: RibbonTrack = RibbonTrack.A,
) {
    val ribbon = rememberEnergyRibbon(active, track)
    // Read the revision so the Canvas re-runs whenever a new sample lands.
    @Suppress("UNUSED_EXPRESSION") ribbon.revision
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val n = ribbon.slots
        if (n <= 0 || w <= 0f) return@Canvas

        // 中轴
        drawLine(
            color = Color(0xFF2A2A30),
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1f
        )

        val colW = w / n
        for (i in 0 until n) {
            val e = ribbon.at(i).coerceIn(0f, 1f)
            if (e <= 0.004f) continue
            val x = i * colW + colW / 2f
            // 高度做一点平方，让弱信号也可见
            val amp = (e * e).coerceIn(0f, 1f) * (h * 0.46f)
            // 播放头之后的部分淡化
            val headX = w * progress.coerceIn(0f, 1f)
            val c = if (x <= headX) tint else tint.copy(alpha = 0.35f)
            drawLine(
                color = c,
                start = Offset(x, mid - amp),
                end = Offset(x, mid + amp),
                strokeWidth = (colW * 0.8f).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }
    }
}
