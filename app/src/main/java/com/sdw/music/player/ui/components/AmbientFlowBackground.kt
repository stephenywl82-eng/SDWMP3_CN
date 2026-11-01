package com.sdw.music.player.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient as AndroidRadialGradient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sdw.music.player.MemoryManager
import com.sdw.music.player.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 氛围背景（移植 RawS RawFlowBackground 的核心思路）。
 *
 * ── 与 RawS 的对应关系 ──
 * 1. 取色：直接用现成的 [MemoryManager.extractCoverColors]（内部 LruCache + Dispatchers.IO），
 *    组合阶段**零解码**——这是 RawS 最关键的性能模式：composition 只 peek 缓存，
 *     Palette 提取/位图解码全部在后台线程。命中缓存时甚至不进 IO。
 * 2. 渲染：3 个 RadialGradient 色斑缓慢漂移。复刻 RawS 的 RetainedFlowRadialBlob 手法——
 *    单位径向 shader 每个色斑只建一次，颜色不变时**不重建**，每帧只用 Matrix 挪位置/缩放，
 *    避免每帧建 3 个 Shader 与列表滚动/转场动画抢 GPU 命令下发时间（弱机帧预算宝贵）。
 * 3. 颜色切换：animateColorAsState 平滑过渡，换歌不闪。
 *
 * ── 降耗设计 ──
 * - 页面退到后台（ON_STOP）时冻结漂移，只画静态氛围，不烧电；
 * - 无正在播放歌曲时整体不组合 Canvas（零开销）。
 */
@Composable
fun AmbientFlowBackground(
    currentSong: Song?,
    modifier: Modifier = Modifier,
    blobCount: Int = 3
) {
    val context = LocalContext.current
    var palette by remember { mutableStateOf<List<Int>>(emptyList()) }

    // 取色只在后台做；extractCoverColors 自带 LruCache，命中即返回（播放页已提过就是热缓存）
    LaunchedEffect(currentSong?.id, currentSong?.albumArtUri) {
        val song = currentSong
        palette = if (song == null || song.albumArtUri.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                MemoryManager.extractCoverColors(context, song.albumArtUri, song.id)
                    ?.filter { it != 0 }
                    ?.take(blobCount)
                    .orEmpty()
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    // 兜底策略：有歌但取色失败 → 主题色（仍有氛围）；无歌 → 不画（干净的列表底）
    val targetColors: List<Color> = when {
        palette.size >= 2 -> palette.map { Color(it) }
        currentSong != null -> listOf(scheme.primary, scheme.tertiary)
        else -> return
    }

    // 固定 3 个动画色槽（结构稳定，不随调色板长度抖动）：色不够就复用首色
    val c0 by animateColorAsState(
        targetColors[0], animationSpec = tween(durationMillis = 900), label = "ambC0"
    )
    val c1 by animateColorAsState(
        targetColors.getOrElse(1) { targetColors[0] },
        animationSpec = tween(durationMillis = 900), label = "ambC1"
    )
    val c2 by animateColorAsState(
        targetColors.getOrElse(2) { targetColors[0] },
        animationSpec = tween(durationMillis = 900), label = "ambC2"
    )

    val resumed by rememberIsResumed()
    val specs = remember(blobCount) { AMBIENT_BLOB_SPECS.take(blobCount) }
    val blobs = remember(blobCount) {
        specs.map { AmbientBlobRenderer(BLOB_STOPS, BLOB_ALPHAS) }
    }
    val transition = rememberInfiniteTransition(label = "ambientDrift")
    val phases = specs.mapIndexed { index, spec ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = spec.periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ambPhase$index"
        )
    }
    // 整屏竖向渐变底色：retained shader，仅在颜色/尺寸变化时重建（换歌的 900ms 过渡期内）
    val wash = remember { AmbientWashRenderer() }

    // fillMaxSize 必须在组件内兜底：Box 里空 Modifier 的子节点是 wrap-content，
    // 而 Canvas 无内容可测 → 0×0 什么都不画（RawS 是调用方显式传 fillMaxSize）。
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val unit = min(w, h)
        val colors = listOf(c0, c1, c2)
        // 后台态冻结在中位（phase=0.5 → 无偏移），只画静态氛围
        fun phaseOf(state: State<Float>): Float = if (resumed) state.value else 0.5f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // ① 整屏竖向渐变底色（上→中→下三色），先铺满，再叠漂移色斑
            wash.draw(native, w, h, listOf(c0, c1, c2), WASH_ALPHA)
            specs.forEachIndexed { i, spec ->
                val p = phaseOf(phases[i])
                val centered = p * 2f - 1f // -1..1
                val cx = (spec.baseX + centered * spec.driftX) * w
                val cy = (spec.baseY + centered * spec.driftY) * h
                // 半径轻微呼吸（±8%），静止时也有微弱生气，但不额外耗帧
                val radius = spec.radiusFactor * unit * (0.92f + 0.08f * p)
                blobs[i].draw(native, colors[i % colors.size], spec.alpha, cx, cy, radius)
            }
        }
    }
}

/** 单个色斑的规格：基准位置（比例）、半径系数、漂移幅度、周期、核心透明度。 */
private data class AmbientBlobSpec(
    val baseX: Float,
    val baseY: Float,
    val radiusFactor: Float,
    val driftX: Float,
    val driftY: Float,
    val periodMs: Int,
    val alpha: Float
)

/** 三个色斑错开位置/周期/大小，叠出非对称的有机感（周期取质数级，避免同步）。
 *  半径系数 >1：色斑比屏幕还大，边缘相互叠融合成「整屏色晕」而非集中圆斑。
 *  [2026-10-29] 收回到克制：wash 0.38→0.22、色斑约 -35%。底盘锁深色后，过亮的氛围色晕会把
 *  深色玻璃整体冲成"浅紫灰雾"（低对比、发浑、没有重点），只留一点氛围即可。 */
private val AMBIENT_BLOB_SPECS = listOf(
    AmbientBlobSpec(0.20f, 0.16f, 1.15f, 0.10f, 0.07f, 13_000, 0.21f),
    AmbientBlobSpec(0.86f, 0.28f, 0.95f, 0.08f, 0.11f, 17_000, 0.18f),
    AmbientBlobSpec(0.42f, 0.94f, 1.05f, 0.12f, 0.05f, 21_000, 0.19f),
)

/** 整屏渐变底色的强度（竖向三色渐变，保证「背景都是这个渐变」而非只有色斑处有色）。 */
private const val WASH_ALPHA = 0.22f

/** 径向渐变的色标：中心实 → 中段半 → 边缘全透。 */
private val BLOB_STOPS = floatArrayOf(0f, 0.55f, 1f)
/** 与色标对应的 alpha 乘数（核心最实，向外柔化消失）。 */
private val BLOB_ALPHAS = floatArrayOf(1f, 0.5f, 0f)

/**
 * 整屏竖向渐变底色（retained LinearGradient）。
 * 颜色不变时不重建 shader——换歌的 900ms 过渡期外，每帧零 shader 创建。
 */
private class AmbientWashRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: android.graphics.LinearGradient? = null
    private var colorKey = Long.MIN_VALUE

    fun draw(
        canvas: NativeCanvas,
        w: Float,
        h: Float,
        colors: List<Color>,
        alpha: Float,
    ) {
        if (w <= 0f || h <= 0f || colors.isEmpty()) return
        val key = colors.fold(0L) { acc, c -> acc * 31L + c.toArgb() } xor
            (alpha.toRawBits().toLong() shl 8) xor
            (w.toRawBits().toLong() * 31L) xor h.toRawBits().toLong()
        var active = shader
        if (active == null || colorKey != key) {
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            val argbs = colors.map { c ->
                val argb = c.toArgb()
                android.graphics.Color.argb(
                    a,
                    (argb shr 16) and 0xFF,
                    (argb shr 8) and 0xFF,
                    argb and 0xFF
                )
            }.toIntArray()
            val positions = if (argbs.size > 1) {
                FloatArray(argbs.size) { it.toFloat() / (argbs.size - 1) }
            } else null
            active = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                argbs, positions,
                android.graphics.Shader.TileMode.CLAMP
            )
            shader = active
            paint.shader = active
            colorKey = key
        }
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}

/**
 * Retained 径向色斑（移植 RawS RetainedFlowRadialBlob）：
 * shader 只在颜色/透明度变化时重建，每帧仅 Matrix 位移缩放。
 */
private class AmbientBlobRenderer(
    private val stops: FloatArray,
    private val alphas: FloatArray,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: AndroidRadialGradient? = null
    private var colorKey = Long.MIN_VALUE

    fun draw(
        canvas: NativeCanvas,
        color: Color,
        coreAlpha: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        if (radius <= 0f) return
        val argb = color.toArgb()
        val key = (argb.toLong() shl 32) xor coreAlpha.toRawBits().toLong()
        var active = shader
        if (active == null || colorKey != key) {
            val red = (argb shr 16) and 0xFF
            val green = (argb shr 8) and 0xFF
            val blue = argb and 0xFF
            val colors = IntArray(alphas.size) { index ->
                val a = ((coreAlpha * alphas[index]).coerceIn(0f, 1f) * 255f).toInt()
                android.graphics.Color.argb(a, red, green, blue)
            }
            active = AndroidRadialGradient(
                0f, 0f, 1f,
                colors, stops,
                android.graphics.Shader.TileMode.CLAMP
            )
            shader = active
            paint.shader = active
            colorKey = key
        }
        matrix.reset()
        matrix.setScale(radius, radius)
        matrix.postTranslate(centerX, centerY)
        active.setLocalMatrix(matrix)
        canvas.drawCircle(centerX, centerY, radius, paint)
    }
}

/** 生命周期 RESUMED 状态（ON_STOP 冻结漂移动画，省电）。 */
@Composable
private fun rememberIsResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumed = remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed.value = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return resumed
}
