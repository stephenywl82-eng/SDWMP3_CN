package com.sdw.music.player.ui.components

import androidx.compose.runtime.mutableStateOf

/**
 * 【V8.428】玩家页**统一动画 ticker**。
 *
 * 合并前玩家页有 5 个各自为政的高频协程环：
 *  - PlayerScreen crossfadeInfo 轮询 60ms
 *  - PlayerScreen bandLevels 走 `withFrameMillis` → 实测 **60Hz**，而且每帧 `new` 两个
 *    FloatArray（bandsL/bandsR），60Hz × 2 = 每秒 120 次分配
 *  - PlayerScreen 两处装饰动画 flow/breathe 各 50ms（20Hz）
 *  - VuMeter.VuMixer 自己的 **60fps 帧时钟**（峰值回落动画，`withFrameMillis { tick = it }`）
 *
 * 结果是 3 个互不相识的 60Hz 状态写入各自触发一轮重组，主线程实测 43~78%（≈0.6 核），
 * 整页亮屏时 App 吃 122% CPU、RenderThread 另吃 10~30%。
 *
 * 现在统一由 [publish] 每 [INTERVAL_MS] 推一帧：所有装饰动画只读这一份快照，
 * 一帧只重组一次；VuMixer 的帧时钟也换成 [Frame.tickMs]。10Hz 下渐变流动/呼吸
 * 肉眼仍然平滑，VU 柱子和峰值回落本来就是 10~20Hz 量级。
 */
object PlayerUiTicker {

    /** 统一节拍 10Hz。 */
    const val INTERVAL_MS = 100L

    data class Frame(
        /** 单调毫秒时间戳；消费者用它算 dt，替代各自的 60fps 帧时钟。 */
        val tickMs: Long = 0L,
        /** 44 秒周期的流光相位（第一处装饰动画，原 50ms 环）。 */
        val flow: Float = 0f,
        /** 25 秒周期的流光相位（第二处装饰动画 / 表盘，原 50ms 环）。 */
        val flowAlt: Float = 0f,
        /** 24 秒周期呼吸，0..1 三角波（第一处，原 50ms 环）。 */
        val breathe: Float = 0f,
        /** 8 秒周期呼吸，0.3..1.0 平滑缓动（表盘，原 50ms 环）。 */
        val breathe8: Float = 0.3f,
    )

    val frame = mutableStateOf(Frame())

    private var startMs = 0L

    /** 由唯一的 ticker 循环调用，一次算完所有装饰相位。 */
    fun publish(nowMs: Long) {
        if (startMs == 0L) startMs = nowMs
        val el = nowMs - startMs
        val b24 = (el % 24_000L) / 24_000f
        val p8 = (el % 8_000L) / 8_000f
        val lin = if (p8 <= 0.5f) p8 * 2f else (1f - p8) * 2f
        frame.value = Frame(
            tickMs = nowMs,
            flow = (el % 44_000L) / 44_000f,
            flowAlt = (el % 25_000L) / 25_000f,
            breathe = if (b24 <= 0.5f) b24 * 2f else (1f - b24) * 2f,
            breathe8 = 0.3f + 0.7f * (lin * lin * (3f - 2f * lin)),
        )
    }
}
