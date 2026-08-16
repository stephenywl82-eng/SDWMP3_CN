package com.sdw.music.player.ui.animation

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
    }

    fun exit() {
        isEntering = false
        isAnimating = true
        dragProgress = 0f
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
}


