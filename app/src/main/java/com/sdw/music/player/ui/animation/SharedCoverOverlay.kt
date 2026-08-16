package com.sdw.music.player.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.ui.components.DefaultCoverImage
import kotlin.math.abs
import kotlin.math.roundToInt

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** 拖拽触发的阈值比例（屏幕高度） */
private const val DISMISS_THRESHOLD_RATIO = 0.25f

@Composable
fun SharedCoverOverlay(
    state: SharedCoverState,
    albumArtUri: String,
    screenHeightPx: Float,
    onAnimationFinished: () -> Unit,
    defaultCover: @Composable (modifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current

    // 拖拽累积的 Y 偏移（像素）
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }

    // 动画进度：进入时 0→1，离On时 1→0（由 state.dragProgress 覆盖）
    val baseProgress by animateFloatAsState(
        targetValue = if (state.isEntering) 1f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "coverTransition"
    )

    // 拖拽进度叠加到动画进度（只影响离On方向）
    val effectiveProgress = if (state.isDragging || state.dragProgress > 0f) {
        // 拖拽中：progress 被 dragProgress 覆盖
        if (state.isEntering) 1f - state.dragProgress
        else state.dragProgress
    } else {
        baseProgress
    }

    // 动画完成时重置状态
    LaunchedEffect(effectiveProgress, state.isAnimating) {
        if (!state.isAnimating) return@LaunchedEffect
        val done = if (state.isDragging) false
        else if (state.isEntering) effectiveProgress >= 1f
        else effectiveProgress <= 0f
        if (done) {
            state.reset()
            onAnimationFinished()
        }
    }

    val from = state.miniCoverPosition
    val to = state.fullCoverPosition

    // 用 effectiveProgress 插值Cover位置
    val animOffset = Offset(
        x = lerpFloat(from.windowOffset.x, to.windowOffset.x, effectiveProgress),
        y = lerpFloat(from.windowOffset.y, to.windowOffset.y, effectiveProgress)
    )
    val animWidth = lerpFloat(from.size.width, to.size.width, effectiveProgress)
    val animHeight = lerpFloat(from.size.height, to.size.height, effectiveProgress)

    // 拖拽时把Cover Y 往下拉
    val dragOffsetY = if (state.isDragging) accumulatedDragY else 0f
    val finalOffsetX = animOffset.x
    val finalOffsetY = animOffset.y + dragOffsetY

    // 背景透明度：进入时随 progress 渐显，拖拽时随 dragProgress 渐隐
    val bgAlpha by animateFloatAsState(
        targetValue = if (state.isDragging) 1f - state.dragProgress * 0.5f
        else if (state.isEntering) effectiveProgress
        else effectiveProgress,
        animationSpec = tween(durationMillis = if (state.isDragging) 0 else 300),
        label = "bgAlpha"
    )

    // Cover圆角插值
    val cornerRadiusPx = with(density) {
        val miniRadius = 4.dp.toPx()
        val maxRadius = animWidth.coerceAtLeast(animHeight) / 2f
        lerpFloat(miniRadius, maxRadius, effectiveProgress)
    }

    val coverWidthDp = with(density) { animWidth.toDp() }
    val coverHeightDp = with(density) { animHeight.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 半透明背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = bgAlpha * 0.85f }
                .background(Color.Black)
        )

        // Cover图片（可拖拽）
        Box(
            modifier = Modifier
                .offset { IntOffset(finalOffsetX.roundToInt(), finalOffsetY.roundToInt()) }
                .size(coverWidthDp, coverHeightDp)
                .clip(
                    if (cornerRadiusPx >= animWidth / 2f - 1f) CircleShape
                    else RoundedCornerShape(with(density) { cornerRadiusPx.toDp() })
                )
                .graphicsLayer {
                    alpha = if (effectiveProgress in 0.01f..0.99f || state.isDragging) 1f else 0f
                }
                .then(
                    if (!state.isEntering && state.isAnimating) {
                        Modifier.pointerInput(state.isAnimating, state.isDragging) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    if (!state.isEntering && state.isAnimating) {
                                        accumulatedDragY = 0f
                                        state.isDragging = true
                                        state.dragProgress = 0f
                                    }
                                },
                                onDragEnd = {
                                    if (state.isDragging) {
                                        state.isDragging = false
                                        val progress = state.dragProgress
                                        if (progress >= DISMISS_THRESHOLD_RATIO) {
                                            // 触发Back动画
                                            accumulatedDragY = 0f
                                            state.isAnimating = true
                                            // onAnimationEnd 会在动画完成时调用 popBackStack
                                            state.onAnimationEnd?.invoke()
                                            state.onAnimationEnd = null
                                        } else {
                                            // 弹回原位
                                            state.dragProgress = 0f
                                            accumulatedDragY = 0f
                                        }
                                    }
                                },
                                onDragCancel = {
                                    if (state.isDragging) {
                                        state.isDragging = false
                                        state.dragProgress = 0f
                                        accumulatedDragY = 0f
                                    }
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (state.isDragging && !state.isEntering) {
                                        // 只允许向下滑动
                                        val delta = dragAmount
                                        if (delta > 0 || accumulatedDragY > 0) {
                                            accumulatedDragY = (accumulatedDragY + delta).coerceAtLeast(0f)
                                            // 计算拖拽进度（最多到屏幕高度的 50%）
                                            state.dragProgress = (accumulatedDragY / (screenHeightPx * 0.5f)).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
            if (albumArtUri.isNotEmpty()) {
                val overlayPainter = rememberAsyncImagePainter(
                    model = albumArtUri,
                    contentScale = ContentScale.Crop
                )
                val imgState = overlayPainter.state
                if (imgState is AsyncImagePainter.State.Loading || imgState is AsyncImagePainter.State.Error) {
                    defaultCover(Modifier.fillMaxSize())
                } else {
                    Image(
                        painter = overlayPainter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                defaultCover(Modifier.fillMaxSize())
            }
        }
    }
}


