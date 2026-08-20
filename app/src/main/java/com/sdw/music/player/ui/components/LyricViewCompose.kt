package com.sdw.music.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LrcParser
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure Compose replacement for the legacy [com.sdw.music.player.LyricView] (a custom
 * android.view.View subclass). Behaviour:
 *  - `visibleLines` lines (default 7), current line centered
 *  - smooth sub-pixel scroll (current line glides to center instead of snapping)
 *  - top/bottom fade scrim so lines dissolve into the background at the edges
 *  - current line: white + accent glow, bold, with a scale "pop" on change
 *  - drag to scroll, fling, auto-revert to follow playback after 3s
 *  - click a line to seek
 *  - translation support (same as legacy default)
 *  - optional right-side progress bar (`showProgressBar`) showing line position
 *  - adjustable font size (`fontSize` = highlight sp, normal line = fontSize - 6)
 *
 * `themeColor` drives the current-line glow and progress bar fill.
 */
private val LINE_PADDING = 16.dp
private const val AUTO_REVERT_DELAY = 3000L

@Composable
fun LyricViewCompose(
    lyrics: List<LyricLine>,
    positionMs: Long,
    themeColor: Int,
    onLineClick: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    showTranslation: Boolean = true,
    showProgressBar: Boolean = false,
    fontSize: Int = 28,
    visibleLines: Int = 7
) {
    val highlightTextSize = fontSize.sp
    val normalTextSize = (fontSize - 6).coerceAtLeast(12).sp
    val centerOffset = visibleLines / 2
    val lineHeightSp = (fontSize * 1.28f).sp

    val density = LocalDensity.current
    val lineHeightPx = with(density) { lineHeightSp.toPx() + LINE_PADDING.toPx() * 2f }
    val accent = ComposeColor(themeColor)

    val currentLineIndex = remember(lyrics, positionMs) {
        if (lyrics.isEmpty()) -1 else LrcParser.findCurrentLineIndex(lyrics, positionMs)
    }

    val scope = rememberCoroutineScope()
    val manualOffset = remember { Animatable(0f) }
    var revertJob by remember { mutableStateOf<Job?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    val lastIndex = (lyrics.size - 1).coerceAtLeast(0)

    // 平滑滚动：用 Animatable 连续逼近「当前行 + 手动偏移」，floor 出整数行索引，
    // 小数部分通过 graphicsLayer translationY 做亚像素位移，实现丝滑上移而非跳行。
    val smoothScroll = remember { Animatable(currentLineIndex.toFloat()) }
    LaunchedEffect(currentLineIndex, manualOffset.value) {
        val target = currentLineIndex + manualOffset.value
        if (isDragging) {
            smoothScroll.snapTo(target)
        } else {
            smoothScroll.animateTo(target, tween(450, easing = FastOutSlowInEasing))
        }
    }

    val displayIndex = floor(smoothScroll.value).toInt().coerceIn(0, lastIndex)
    val subPixel = smoothScroll.value - displayIndex

    val centerScale = remember { Animatable(1f) }
    LaunchedEffect(displayIndex) {
        centerScale.snapTo(0.88f)
        centerScale.animateTo(1f, tween(400))
    }

    // 渐隐遮罩颜色 = 背景色（surface）
    val fadeColor = MaterialTheme.colorScheme.surface

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = -subPixel * lineHeightPx }
                .pointerInput(lyrics) {
                    var tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            revertJob?.cancel()
                            isDragging = true
                            tracker = VelocityTracker()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val deltaLines = (dragAmount / lineHeightPx).toInt()
                            if (deltaLines != 0) {
                                val newOffset = (manualOffset.value + deltaLines)
                                    .coerceIn(-lastIndex.toFloat(), lastIndex.toFloat())
                                scope.launch { manualOffset.snapTo(newOffset) }
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            val vel = tracker.calculateVelocity().y
                            val flingLines = (vel / lineHeightPx / 10f).toInt().coerceIn(-10, 10)
                            if (flingLines != 0) {
                                val newOffset = (manualOffset.value + flingLines)
                                    .coerceIn(-lastIndex.toFloat(), lastIndex.toFloat())
                                scope.launch { manualOffset.snapTo(newOffset) }
                            }
                            revertJob = scope.launch {
                                delay(AUTO_REVERT_DELAY)
                                manualOffset.animateTo(0f, tween(400))
                            }
                        }
                    )
                }
        ) {
            for (i in 0 until visibleLines) {
                val lyricIndex = displayIndex - centerOffset + i
                val distance = abs(i - centerOffset)
                val isCenter = distance == 0
                val line = if (lyricIndex in lyrics.indices) lyrics[lyricIndex] else null
                val text = if (line != null) {
                    if (showTranslation && !line.translation.isNullOrBlank()) {
                        "${line.text}\n${line.translation}"
                    } else {
                        line.text.ifEmpty { "~" }
                    }
                } else {
                    ""
                }

                val fontSizeSp = if (isCenter) highlightTextSize else normalTextSize
                val fontWeight = if (isCenter) FontWeight.Normal else FontWeight.Normal
                val textColor = if (isCenter) ComposeColor.White else ComposeColor(0xE0FFFFFF)
                val shadow = if (isCenter) {
                    // 当前行 glow 改用主题/封面取色（accent），与播放界面呼应，替代原硬编码金色
                    Shadow(accent.copy(alpha = 0.85f), blurRadius = 22f)
                } else {
                    Shadow(ComposeColor(0x40000000), offset = Offset(1f, 1f), blurRadius = 6f)
                }

                val textStyle = TextStyle(
                    fontSize = fontSizeSp,
                    fontWeight = fontWeight,
                    color = textColor,
                    shadow = shadow,
                    textAlign = TextAlign.Center,
                    lineHeight = lineHeightSp
                )

                val lineModifier = Modifier
                    .padding(vertical = LINE_PADDING)
                    .then(
                        if (isCenter) {
                            Modifier.graphicsLayer {
                                scaleX = centerScale.value
                                scaleY = centerScale.value
                            }
                        } else {
                            Modifier
                        }
                    )
                    .then(if (line != null) Modifier.clickable { onLineClick(line) } else Modifier)

                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    style = textStyle,
                    modifier = lineModifier
                )
            }
        }

        // 上下渐隐遮罩：让歌词在边缘自然淡出
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(fadeColor, fadeColor.copy(alpha = 0f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(fadeColor.copy(alpha = 0f), fadeColor)
                    )
                )
        )

        // 右侧进度条：显示当前行在整首歌的位置比例
        if (showProgressBar && lyrics.isNotEmpty()) {
            val progress = if (lastIndex == 0) 1f
                else (currentLineIndex.toFloat() / lastIndex).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .width(3.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ComposeColor.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(progress)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
            }
        }
    }
}
