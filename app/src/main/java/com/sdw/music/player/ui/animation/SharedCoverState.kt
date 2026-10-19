package com.sdw.music.player.ui.animation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

data class CoverPosition(
    val windowOffset: Offset = Offset.Zero,
    val size: Size = Size.Zero,
    val isCircular: Boolean = false
)

class SharedCoverState {
    /** 【V8.52 回归修复】手搓的 SharedCoverOverlay 已退役，reset() 不再有任何调用点触发，
     *  但 enter() 仍会把 isAnimating 置 true —— 于是进入播放页后 isAnimating 永久卡 true，
     *  把 VU 循环与背景模糊都冻在「转场中」状态（即用户复现的「VU 死 + 背景模糊没了」）。
     *  这里用主线程 Handler 做兜底：转场时长（飞入约 380ms，留足缓冲到 700ms）后强制复位，
     *  不受重组风暴影响；拖拽进行中（isDragging）不复位以免打断返回手势。 */
    private val resetHandler = Handler(Looper.getMainLooper())
    private var resetGen = 0

    /** MiniPlayer Cover的窗口坐标+尺寸 */
    var miniCoverPosition by mutableStateOf(CoverPosition())

    /** 全屏播放器Cover的窗口坐标+尺寸 */
    var fullCoverPosition by mutableStateOf(CoverPosition())

    /** overlay 是否正在动画中 */
    var isAnimating by mutableStateOf(false)

    /** true=进入Player(false=BackSongList) */
    var isEntering by mutableStateOf(false)

    /** 拖拽下滑进度 [0f, 1f]，1f=可触发Back */
    var dragProgress by mutableStateOf(0f)

    /** 是否正在拖拽中 */
    var isDragging by mutableStateOf(false)

    /**
     * V8.84 非空时表示「列表行」这一端占用共享元素 key（迷你条自动让位）。
     * 只有用户点中的那一行会挂 sharedCoverArt，因此「迷你条」与「列表行」
     * 永远不可能同时成为同一个 key 的源端 —— 这是 Compose 共享元素配对的硬约束。
     */
    var rowFlightSongId by mutableStateOf<Long?>(null)

    /** 动画完成后回调 */
    var onAnimationEnd: (() -> Unit)? = null

    /** 等待全屏Cover位置捕获后再On始进入动画 */
    var pendingEnter: (() -> Unit)? = null

    fun enter() {
        // Safety: skip shared-element animation if mini cover position isn't captured yet
        // (process death / cold start: mini player hasn't been laid out)
        if (miniCoverPosition.size.width <= 0f || miniCoverPosition.size.height <= 0f) {
            isAnimating = false
            isEntering = true
            return
        }
        isEntering = true
        isAnimating = true
        dragProgress = 0f
        scheduleReset()
    }

    fun exit() {
        isEntering = false
        isAnimating = true
        dragProgress = 0f
        scheduleReset()
    }

    /** 外部判断全屏Cover位置是否已就绪（size > 0）*/
    fun tryCompleteEnter() {
        if (isEntering && isAnimating && fullCoverPosition.size.width > 0f) {
            isAnimating = true // 保持动画进行
        }
    }

    fun reset() {
        isAnimating = false
        isDragging = false
        dragProgress = 0f
        onAnimationEnd = null
    }

    /** 转场结束后兜底复位 isAnimating：generation 守卫保证只有最后一次 enter/exit 的定时器生效，
     *  拖拽进行中不复位（避免打断返回手势）。 */
    private fun scheduleReset() {
        val gen = ++resetGen
        resetHandler.postDelayed({
            if (gen == resetGen && isAnimating && !isDragging) reset()
        }, 700L)
    }
}


