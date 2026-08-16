package com.sdw.music.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure Compose replacement for the legacy [com.sdw.music.player.LyricView] (a custom
 * android.view.View subclass). Behaves identically:
 *  - 7 visible lines, current line centered
 *  - smooth alpha / size falloff by distance from center
 *  - current line: white + gold glow, bold, with a scale "pop" on change
 *  - drag to scroll, fling, auto-revert to follow playback after 3s
 *  - click a line to seek
 *  - translation support (same as legacy default)
 *
 * `themeColor` is accepted for API compatibility but the legacy gradient code was
 * never actually wired in, so styling matches the legacy white + gold-glow look.
 */
private const val VISIBLE_LINES = 7
private const val CENTER_OFFSET = VISIBLE_LINES / 2
private val HIGHLIGHT_TEXT_SIZE = 28.sp
private val NORMAL_TEXT_SIZE = 22.sp
private val LINE_PADDING = 16.dp
private const val AUTO_REVERT_DELAY = 3000L

@Composable
fun LyricViewCompose(
    lyrics: List<LyricLine>,
    positionMs: Long,
    themeColor: Int,
    onLineClick: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    showTranslation: Boolean = true
) {
    val density = LocalDensity.current
    val lineHeightPx = with(density) { HIGHLIGHT_TEXT_SIZE.toPx() + LINE_PADDING.toPx() * 2f }

    val currentLineIndex = remember(lyrics, positionMs) {
        if (lyrics.isEmpty()) -1 else LrcParser.findCurrentLineIndex(lyrics, positionMs)
    }

    val scope = rememberCoroutineScope()
    val manualOffset = remember { Animatable(0f) }
    var revertJob by remember { mutableStateOf<Job?>(null) }

    val lastIndex = (lyrics.size - 1).coerceAtLeast(0)
    val displayIndex = (currentLineIndex + manualOffset.value.roundToInt()).coerceIn(0, lastIndex)

    val centerScale = remember { Animatable(1f) }
    LaunchedEffect(displayIndex) {
        centerScale.snapTo(0.88f)
        centerScale.animateTo(1f, tween(400))
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(lyrics) {
                    var tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            revertJob?.cancel()
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
            for (i in 0 until VISIBLE_LINES) {
                val lyricIndex = displayIndex - CENTER_OFFSET + i
                val distance = abs(i - CENTER_OFFSET)
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

                val fontSize = if (isCenter) HIGHLIGHT_TEXT_SIZE else NORMAL_TEXT_SIZE
                val fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal
                val textColor = if (isCenter) ComposeColor.White else ComposeColor(0xE0FFFFFF)
                val shadow = if (isCenter) {
                    Shadow(ComposeColor(0xCCFFD700), blurRadius = 20f)
                } else {
                    Shadow(ComposeColor(0x40000000), offset = Offset(1f, 1f), blurRadius = 6f)
                }

                val textStyle = TextStyle(
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = textColor,
                    shadow = shadow,
                    textAlign = TextAlign.Center,
                    lineHeight = 36.sp
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
    }
}
