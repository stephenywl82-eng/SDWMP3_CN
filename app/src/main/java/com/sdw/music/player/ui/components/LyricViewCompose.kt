package com.sdw.music.player.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LrcParser
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    wordByWord: Boolean = false,
    isPlaying: Boolean = true,
    showProgressBar: Boolean = false,
    fontSize: Int = 28,
    visibleLines: Int = 7,
    fadeColor: ComposeColor = MaterialTheme.colorScheme.surface,
    showTopFade: Boolean = true,
    showBottomFade: Boolean = true
) {
    val highlightTextSize = fontSize.sp
    val normalTextSize = (fontSize - 6).coerceAtLeast(12).sp
    val centerOffset = visibleLines / 2
    val lineHeightSp = (fontSize * 1.28f).sp

    val density = LocalDensity.current
    val lineHeightPx = with(density) { lineHeightSp.toPx() + LINE_PADDING.toPx() * 2f }
    val accent = ComposeColor(themeColor)

    // [V8.128] 帧率插值位置：仅在「逐字显示 + 播放中」时启动帧循环。
    // 父组件不读取该 State，只有居中行子组件读 —— 避免整页 60Hz 重组。
    val smoothPosition = rememberSmoothPositionMs(positionMs, isPlaying && wordByWord)

    // [V8.129] 行索引改由帧级平滑位置推导。上游 positionMs 约 1 秒才更新一次，直接用它找
    // 「当前行」会让整行高亮最多晚 1 秒才切 —— 这正是用户报的「每一句开头都会延迟」。
    // derivedStateOf 只在「算出的行号真的变了」时才通知读者，因此整页不会每帧重组，
    // 仍只有居中行与其高亮同步切换。
    val lineIndexState = remember(lyrics) {
        derivedStateOf {
            if (lyrics.isEmpty()) -1 else LrcParser.findCurrentLineIndex(lyrics, smoothPosition.value)
        }
    }
    val currentLineIndex = lineIndexState.value

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
            smoothScroll.animateTo(target, tween(360, easing = FastOutSlowInEasing))
        }
    }

    val displayIndex = floor(smoothScroll.value).toInt().coerceIn(0, lastIndex)
    // 【V8.37】中心行浮出：仅当整行切换（displayIndex 变化）时触发 pop，
    // 拖动中不触发（isDragging 时 snap 到新行但保持静止），自动跟随恢复时同样浮出。
    var prevDisplayIndex by remember { mutableStateOf(displayIndex) }
    val centerPop = remember { Animatable(1f) }
    LaunchedEffect(displayIndex) {
        if (displayIndex != prevDisplayIndex) {
            prevDisplayIndex = displayIndex
            if (!isDragging) {
                if (centerPop.isRunning) {
                    // 【V8.38】连续快速切行（密集歌词）时动画会反复从头弹→抽搐，
                    // 直接静止到终态，跳过本次 pop。
                    centerPop.snapTo(1f)
                } else {
                    centerPop.snapTo(0f)
                    centerPop.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val subPixel = smoothScroll.value - displayIndex

    // 渐隐遮罩颜色 = 背景色（默认 surface，全屏歌词传黑色与模糊背景融合）

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)  // 【V8.38】左右留白，长歌词不再贴屏幕边缘
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
                // [V8.128] 行尾时间 = 下一行起点（用于无词时间戳时的插值兜底）
                val nextLineTime = lyrics.getOrNull(lyricIndex + 1)?.timeMs ?: Long.MAX_VALUE
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
                val targetColor = if (isCenter) ComposeColor.White else ComposeColor(0xA0FFFFFF)
                val textColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    label = "lyricColor"
                )
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
                                // 【V8.38】浮出轻量化：去掉 spring 回弹与 6dp 位移（视觉跳动源），
                                // 只保留 0.96→1.0 轻微放大 + alpha 0.5→1 淡入，200ms tween 无 overshoot
                                val pop = centerPop.value
                                scaleX = 0.96f + 0.04f * pop
                                scaleY = 0.96f + 0.04f * pop
                                alpha = 0.5f + 0.5f * pop
                            }
                        } else {
                            Modifier
                        }
                    )
                    .then(if (line != null) Modifier.clickable { onLineClick(line) } else Modifier)

            // [V8.121/8.128] 逐字显示：有真实词时间戳（增强型 LRC / QRC）就用真实时间戳，
            // 没有（普通整行 LRC，如多数内嵌歌词 / LRCLIB）则按行时长插值兜底。
            // 只把居中行交给独立子组件，帧级插值只让这一行重组。
                if (wordByWord && isCenter && line != null) {
                    WordByWordLine(
                        line = line,
                        lineEndMs = nextLineTime,
                        positionState = smoothPosition,
                        textStyle = textStyle,
                        showTranslation = showTranslation,
                        modifier = lineModifier
                    )
                } else {
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        style = textStyle,
                        modifier = lineModifier
                    )
                }
            }
        }

        // 上下渐隐遮罩：让歌词在边缘自然淡出（顶部渐隐可选，全屏歌词顶部有操作栏时禁用避免黑带）
        if (showTopFade) {
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
        }
        if (showBottomFade) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(fadeColor.copy(alpha = 0f), fadeColor.copy(alpha = 0.7f))
                        )
                    )
            )
        }

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
                    .clip(SDWShapes.Badge)
                    .background(ComposeColor.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(progress)
                        .clip(SDWShapes.Badge)
                        .background(accent)
                )
            }
        }
    }
}

/**
 * V8.128 居中行逐字渲染。
 *
 * 只在本组件内读取 [positionState]（帧级插值位置），因此每帧重组仅局限于这一行，
 * 不会带动整页歌词重组。
 *
 * 词时间戳优先级：真实词时间戳（增强型 LRC / QRC）> 按行时长插值（普通 LRC 兜底）。
 */
@Composable
private fun WordByWordLine(
    line: LyricLine,
    lineEndMs: Long,
    positionState: State<Long>,
    textStyle: TextStyle,
    showTranslation: Boolean,
    modifier: Modifier
) {
    val positionMs = positionState.value
    val segments = remember(line, lineEndMs) { buildWordSegments(line, lineEndMs) }
    val annotated = buildAnnotatedString {
        for ((segText, onset) in segments) {
            // [V8.129] 逐词连续淡入：每个词在自己起始时刻起的 WORD_FADE_MS 内
            // 由 0.35 平滑升到 1.0，替代原来的二值硬切（「生硬、没动画」）。
            // positionState 每帧刷新，所以这就是帧级动画，无需额外状态。
            // [V8.135] 淡入曲线抽成 wordByWordAlpha，播放界面歌词行共用。
            withStyle(SpanStyle(color = ComposeColor.White.copy(alpha = wordByWordAlpha(positionMs, onset)))) { append(segText) }
        }
        if (showTranslation && !line.translation.isNullOrBlank()) {
            append("\n")
            withStyle(SpanStyle(color = ComposeColor.White.copy(alpha = 0.7f))) {
                append(line.translation)
            }
        }
    }
    Text(
        text = annotated,
        textAlign = TextAlign.Center,
        style = textStyle,
        modifier = modifier
    )
}

/**
 * V8.135 逐词高亮淡入系数：词起始时刻起 WORD_FADE_MS 内由 0.35 线性升到 1.0。
 * 全屏歌词页与播放界面歌词行共用，保证两处观感完全一致。
 */
internal fun wordByWordAlpha(positionMs: Long, onsetMs: Long): Float {
    val p = ((positionMs - onsetMs).toFloat() / WORD_FADE_MS).coerceIn(0f, 1f)
    return 0.35f + 0.65f * p
}

/**
 * 逐字段列表：优先真实词时间戳；否则把整行时长按字符数均分，插值出每个字的起始时刻。
 *
 * 插值上限用「字数 × 3百毫秒」估一个自然朗读时长，再用下一行起点封顶，
 * 避免长间奏（下一行很远）时整行被拉得很慢。
 */
internal fun buildWordSegments(line: LyricLine, lineEndMs: Long): List<Pair<String, Long>> {
    val words = line.words
    if (!words.isNullOrEmpty()) return words.map { it.text to it.timeMs }
    val text = line.text
    if (text.isEmpty()) return emptyList()
    val start = line.timeMs
    val n = text.length
    val naturalSpan = (n * 300L).coerceIn(600L, 6000L)
    val availSpan = if (lineEndMs > start) lineEndMs - start else naturalSpan
    val span = minOf(naturalSpan, availSpan).coerceAtLeast(300L)
    return List(n) { i -> text[i].toString() to (start + span * i / n) }
}

/**
 * V8.128 帧率插值位置。
 *
 * 上游 positionMs 由 PlayerConnection 的位置循环提供，屏幕亮时约 1 秒才更新一次，
 * 直接拿它判「逐字亮到哪」会一秒一跳。这里以每个上游值为锚点、播放中用帧时钟外推：
 *  - 大跳变（seek / 切歌）→ 直接重锚；
 *  - 小幅偏差 → 重锚但播放中不倒退（避免已点亮的字又掉回去产生闪烁）；
 *  - 暂停 → 帧循环不跑，冻结在最后一个位置。
 */
@Composable
private fun rememberSmoothPositionMs(anchorMs: Long, running: Boolean): State<Long> {
    val holder = remember { SmoothPositionHolder() }
    LaunchedEffect(anchorMs) { holder.onAnchor(anchorMs) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            holder.advance()
        }
    }
    return holder.state
}

// [V8.129] 逐词高亮淡入时长（毫秒）。kapt 地雷：勿写成 KDoc 的 [V8.x] 链接
// [V8.135] 改为 internal：播放界面歌词行复用同一淡入节奏。
internal const val WORD_FADE_MS = 160f

private class SmoothPositionHolder {
    val state = mutableStateOf(0L)
    private var base = 0L
    private var anchorAt = 0L
    private var lastShown = 0L

    fun onAnchor(ms: Long) {
        val now = SystemClock.elapsedRealtime()
        if (anchorAt == 0L) {
            base = ms; anchorAt = now; lastShown = ms; state.value = ms
            return
        }
        val estimate = base + (now - anchorAt)
        if (abs(ms - estimate) > 1500L) {
            // 大跳（seek / 切歌 / 长时间卡顿）：直接重锚。
            base = ms; anchorAt = now; lastShown = ms; state.value = ms
        } else {
            // [V8.129] 小偏差：以「已显示位置」为基准续走，绝不冻结。
            // 旧实现在 ms < lastShown 时直接不更新 —— 上游采样（约 1 秒一次）总是略微落后于
            // 帧外推值，于是每一拍都被守卫挡住，画面「冻一下再走」，观感就是不流畅。
            // 现在取 max(ms, lastShown) 作新基准：既保持单调不倒退（不闪），又持续前进。
            val keep = maxOf(ms, lastShown)
            base = keep; anchorAt = now
            if (keep > lastShown) { lastShown = keep; state.value = keep }
        }
    }

    fun advance() {
        if (anchorAt == 0L) return
        val v = base + (SystemClock.elapsedRealtime() - anchorAt)
        if (v > lastShown) { lastShown = v; state.value = v }
    }
}
