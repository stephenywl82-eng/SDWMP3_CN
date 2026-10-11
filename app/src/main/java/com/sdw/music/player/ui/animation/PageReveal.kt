package com.sdw.music.player.ui.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// [V8.101] 页面「跟手圆角」（手势返回 R 角）
//
// 背景：Compose 的导航转场原语（Enter/ExitTransition）只有 Fade / Slide / Scale /
// Expand-Shrink 四个维度，结构上不提供 shape / cornerRadius，所以「返回时页面收 R 角、
// 四角露出壁纸」这件事必须由页面自己挂 graphicsLayer + clip 实现。
//
// 为什么能「跟手」：
//   NavHost（navigation-compose 2.8.0）内部用 SeekableTransitionState 驱动
//   PredictiveBackHandler。手势拖动时它逐帧调用 transitionState.seekTo(progress, prevEntry)，
//   seek 会沿 Transition 树递归传播（Transition.seekAnimations → 子 transition →
//   子动画 TransitionAnimationState.seekTo）。而 composable{} 的 content 接收者就是
//   AnimatedContentScope（AnimatedVisibilityScope 的子类型），它暴露的
//   transition: Transition<EnterExitState> 正是这棵树上的子节点 —— 在这里读到的进度
//   与手指位置一一对应，不是事后补播的固定时长动画。
//
// 进度定义：
//   arrived = 该页「就位」程度。Visible → 1f，PreEnter / PostExit → 0f。
//     · 出页（被返回掉的那页）：Visible → PostExit，arrived 由 1 掉到 0 → 圆角 0 → R。
//     · 入页（后面露出的那页）：PreEnter → Visible，arrived 由 0 升到 1 → 圆角 R → 0。
//   两者叠加即「双层收/放角」，最接近 Android 14 系统预测返回观感。
//
// 关于动画时长（关键，别乱改）：
//   被 seek 时，动画取值用的是绝对时间 getValueFromNanos(playTime)，而 playTime =
//   fraction × 父 transition 的 totalDurationNanos。因此只有让本动画的时长 == 该页
//   transition 的总时长，进度才会与手势严格线性对应（totalDurationNanos 取所有子动画
//   的最大值，本动画取 max 后自洽、不再变化）。若随便写个固定时长，会出现「手势还没
//   拖到底，圆角已经收满」或反之的脱节。
//
// 风险边界：纯 UI 层，只读 transition 进度 + 加一层 clip，不碰音频链路、不碰
// SharedCoverState 的封面飞行，也不改 NavHost 任何参数。
@Composable
fun AnimatedVisibilityScope.PageReveal(
    cornerRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    // 该页 enter/exit transition 的总时长（毫秒）。夹到 [1, 2000] 防 0 时长导致
    // seek 时瞬间跳到目标值（那样圆角会在手指刚动一下就收满）。
    val totalMs = (transition.totalDurationNanos / 1_000_000L).toInt().coerceIn(1, 2000)

    val arrived by transition.animateFloat(
        transitionSpec = { tween(durationMillis = totalMs, easing = LinearEasing) },
        label = "pageReveal",
        targetValueByState = { state -> if (state == EnterExitState.Visible) 1f else 0f }
    )

    val radius = cornerRadius * (1f - arrived)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundedCornerShape(radius.toPx())
                clip = true
            }
    ) {
        content()
    }
}
