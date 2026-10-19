package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.playback.WaveformCache

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 【V8.368】DJ 模式 · 横屏界面。
 *
 * 设计来源：djay Automix 的**信息架构**，不是它的代码或视觉。
 * 四张真机截图量出来的布局逻辑：
 *   顶部  双轨信息条（左右分栏，各占一半）—— 封面 / 曲名 / 艺人 / 剩余时间 / 波形+红播放头
 *   中部  中央跨接桥 —— 左右波形面对面，中间一条线表示跨接位置
 *   底部  操作条
 *
 * 刻意去掉的（与用户要求「全自动、只留一个开关」直接相关）：
 *   · 音调推子（tempo fader）—— 手动干预
 *   · 跨接推子 —— 手动干预
 *   · FX 按钮 / MIXER 抽屉 / SET 微调
 *   · VU 表 / 各类胶囊徽章
 *
 * 借鉴的一点设计手法（djay 的聪明之处）：它那条跨接推子，手动模式可拖、
 * Automix 模式由引擎自动推动。**同一个可视化，两种驱动方式。**
 * 这里只保留可视化，驱动权完全交给引擎。
 *
 * 数据来源：全部来自 [MusicService.CrossfadeUiInfo]（V8.367 刚打开的决策出口），
 * **不新增任何分析开销**，纯渲染引擎已经算好的东西。
 */
@Composable
fun DjLandscapeSurface(
    songTitle: String,
    songArtist: String,
    onPlayPause: () -> Unit,
    onMixNow: () -> Unit,
    onNext: () -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val xf by remember { androidx.compose.runtime.derivedStateOf {
        com.sdw.music.player.core.audio.playback.MusicService.instance?.crossfadeUiInfo
            ?: MusicService.CrossfadeUiInfo()
    } }

    val active = xf.state == MusicService.XfadeUiState.ACTIVE
    val progress = xf.xfProgress.coerceIn(0f, 1f)

    // A 轨播放进度（0..1）：波形播放头位置。
    // 每 500ms 读一次 native 位置足够 —— 波形有1400 桶，1 桶约 0.1 秒，
    // 更高的刷新率只是让白线抖得更细，看不出差别。
    var playProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val p = com.sdw.music.player.core.audio.playback.MusicService.instance?.oboeDirectPlayer
            val cur = p?.getCurrentPositionMs() ?: 0L
            val dur = p?.getDurationMs() ?: 0L
            playProgress = if (dur > 0) (cur.toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }

    // A 轨封面：djay 横屏左上角那块方形大图。
    val svc0 = MusicService.instance
    val curSong by MusicService.songChangedFlow.collectAsState(initial = null)
    val coverUri = curSong?.albumArtUri?.takeIf { com.sdw.music.player.SongRepository.hasRealCover(it) }

    // 剩余时间（djay 右上角 "-00:14"）
    var remainMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val p = svc0?.oboeDirectPlayer
            val cur = p?.getCurrentPositionMs() ?: 0L
            val dur = p?.getDurationMs() ?: 0L
            remainMs = if (dur > 0) (dur - cur).coerceAtLeast(0L) else 0L
            if (dur > 0) {
                playProgress = (cur.toFloat() / dur).coerceIn(0f, 1f)
            }
            // 【V8.378】33ms ≈ 30fps。之前 500ms（2fps）播放头是跳格走的，
            // 这就是"波纹很卡"的直接观感。波形已缓存成位图，高频更新不掉帧。
            delay(33)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // ================================================================ 顶部
        // djay 横屏：大封面 + 艺人/歌名/剩余时间 + 彩虹频谱条
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E22)),
                contentAlignment = Alignment.Center
            ) {
                if (coverUri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(model = coverUri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF3A3A40),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // 艺人 / 歌名 / 彩虹条
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songArtist.ifBlank { "未知艺人" },
                    color = Color(0xFF9AA0A6),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = songTitle.ifBlank { "未在播放" },
                    color = Color(0xFFE8EAED),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                // 彩虹频谱条：用已有的 8 段 FFT 上色，不新增任何分析开销
                SpectrumBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            }

            // 剩余时间
            Text(
                text = if (remainMs > 0) {
                    val s = remainMs / 1000
                    "-%02d:%02d".format(s / 60, s % 60)
                } else "--:--",
                color = Color(0xFFB8BCC0),
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        // ================================================================ 中部：整曲波形
        // djay 横屏最抢眼的就是这条 —— 整首歌的结构一眼看完。
        // 切片标记直接长在波形上（红竖线 + 小节号），不另起一行。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 10.dp)
        ) {
            FullSongWaveform(
                modifier = Modifier.fillMaxSize(),
                progress = playProgress,
                onSeekToFraction = { frac ->
                    val p = MusicService.instance?.oboeDirectPlayer
                    val dur = p?.getDurationMs() ?: 0L
                    if (dur > 0) p?.seekTo((dur * frac).toLong())
                }
            )
        }

        // ================================================================ 底部操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleAction(
                label = if (isPlaying) "暂停" else "播放",
                onClick = onPlayPause,
                enabled = true,
                filled = true
            )
            MixButton(
                onClick = onMixNow,
                enabled = xf.nextTitle.isNotBlank()
            )
            CircleAction(
                label = "下一曲",
                onClick = onNext,
                enabled = true,
                filled = false
            )
            Spacer(Modifier.weight(1f))
            // 对拍读数缩到右下角小字（BPM + 变速比例），这是横屏独有的信息
            DjEngineReadout(xf = xf)
        }
    }
}
/**
 * 【V8.376】彩虹频谱条 —— djay 横屏歌名下方那条彩色波形。
 *
 * 之前我只画了一条纯蓝线（EnergyRibbon），形状像但内容不对。
 * djay 那条是**按频段上色的**：低频红/橙、中频黄绿、高频青蓝紫，
 * 一眼能看出此刻的能量集中在哪个频段 —— 这对DJ 判断"能不能接"很有用。
 *
 * 数据仍是项目已有的 8 段 FFT（native 每帧更新，零新增开销），
 * 只是把 8 段分别按频率映射到色相，而不是求一个平均值。
 */
@Composable
internal fun SpectrumBar(
    modifier: Modifier = Modifier
) {
    // 【V8.378】不再按播放进度着色。这条是**实时滚动**的频谱（最右端就是"现在"），
    // 按 progress 区分已播/未播没有意义，却会让它随播放头每 33ms 重绘 960 个色块
    // ——那才是横屏卡顿的大头。现在只在采样落地（70ms）时重绘。
    val ribbon = rememberSpectrumBands()
    // revision 用于在采样落地时触发重组
    @Suppress("UNUSED_EXPRESSION") ribbon.revision

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val mid = h / 2f
        val n = ribbon.slots
        if (n <= 0) return@Canvas
        val colW = w / n

        for (i in 0 until n) {
            val bands = ribbon.bandsAt(i) ?: continue
            val x = i * colW + colW / 2f
            // 总能量决定这一列的高度
            var acc = 0f
            for (k in 0 until 8) acc += bands[k] * bands[k]
            val rms = kotlin.math.sqrt(acc / 8f)
            val amp = (rms * h * 0.92f).coerceIn(0.5f, h * 0.46f)

            // 8 段自下而上叠成一根柱子，每段用它自己的色相 —— 这就是彩虹效果
            var y = mid + amp
            for (k in 0 until 8) {
                val seg = (bands[k] * amp).coerceAtLeast(0f)
                if (seg < 0.4f) continue
                val hue = bandHue(k)
                val c = Color.hsv(hue, 0.72f, 1f)
                drawRect(
                    color = c,
                    topLeft = Offset(x - colW * 0.4f, y - seg),
                    size = androidx.compose.ui.geometry.Size(colW * 0.8f, seg)
                )
                y -= seg
            }
        }
    }
}

/** 8 段各自的固定色相（度）：低频红 -> 高频紫，与 djay 观感一致。 */
internal fun bandHue(k: Int): Float = when (k) {
    0 -> 8f     // 0-60Hz     深红
    1 -> 28f    // 60-120   红橙
    2 -> 48f    // 120-250  橙黄
    3 -> 68f    // 250-500  黄绿
    4 -> 145f   // 500-2k   青绿
    5 -> 178f   // 2k-6k    青
    6 -> 215f   // 6k-12k   蓝
    else -> 272f // 12k-20k  紫
}

/** 每槽保留 8 段而非平均值 —— 上色需要频段信息。 */
internal class SpectrumBands(val slots: Int = 120) {
    var revision: Int = 0
        private set
    private val buf = Array(slots) { FloatArray(8) }
    private var head = 0
    private var filled = 0

    fun push(bands: FloatArray) {
        if (bands.size < 8) return
        System.arraycopy(bands, 0, buf[head], 0, 8)
        head = (head + 1) % slots
        if (filled < slots) filled++
        revision++
    }

    /** 0 = 最旧。 */
    fun bandsAt(i: Int): FloatArray? {
        if (i >= filled) return null
        return buf[(head - filled + i + slots * 2) % slots]
    }

    fun clear() { buf.forEach { it.fill(0f) }; head = 0; filled = 0 }
}

@Composable
internal fun rememberSpectrumBands(): SpectrumBands {
    val obj = remember { SpectrumBands() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val p = MusicService.instance?.oboeDirectPlayer
                val b = try { p?.getBands8() } catch (_: Throwable) { null }
                if (b != null) obj.push(b)
                delay(70)
            }
        }
    }
    return obj
}

/**
 * 【V8.376】整曲波形 + 长在波形上的切片标记 —— 对应 djay 横屏中部那条。
 *
 * 三个关键差异（相比 V8.374的 WaveformStrip）：
 *  1. **占满整宽并吃掉剩余高度**，不再是14dp 的细线 —— 这才是一屏里最抢眼的东西。
 *  2. **切片标记画在波形里**：红竖线 + 小节号，不另起一行。
 *  3. **可点击 seek**：点波形任意位置跳过去（djay 支持，这里补上）。
 *
 * 小节号标签用 Canvas 画Text 是麻烦的，所以标记线在 Canvas 内，
 * 数字用 Composable 叠一层（只画首尾两个，避免糊成一片）。
 */
@Composable
internal fun FullSongWaveform(
    modifier: Modifier = Modifier,
    progress: Float,
    /** V8.439：窄条模式（进度条上方）不画 8 切片红竖线，只留波形 + 进拍/出拍横线。 */
    showSliceMarkers: Boolean = true,
    /** V8.439：窄条模式不画首尾小节号（高度不够会糊）。 */
    showBarLabels: Boolean = true,
    /**
     * V8.439：轮询预算（次）。DJ 独立页给默认 60（36s），
     * 播放页窄条传小一点的值——省电，用户对耗电敏感。
     */
    pollBudget: Int = 60,
    onSeekToFraction: (Float) -> Unit
) {
    val ctx = LocalContext.current
    val song by MusicService.songChangedFlow.collectAsState(initial = null)
    var env by remember { mutableStateOf<FloatArray?>(null) }
    var bars by remember { mutableStateOf<List<MusicService.SlicePoint>>(emptyList()) }
    // 【V8.437】三段包络（low/mid/high）—— djay 式频谱着色的数据。
    // null = 还没扫出来（回退单色）；扫出来后波形按频段渐变着色。
    var bands3 by remember { mutableStateOf<Triple<FloatArray, FloatArray, FloatArray>?>(null) }
    // 【V8.430】进拍/出拍标记（对齐 djay 的两条白线）。-1 = 未检出，不画。
    var marks by remember { mutableStateOf(Triple(-1, -1, -1)) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(song?.id) {
        env = null
        bars = emptyList()
        marks = Triple(-1, -1, -1)
        val s = song ?: return@LaunchedEffect
        val path = s.filePath.ifEmpty { s.path }
        if (path.isNotBlank()) {
            val uri = runCatching { android.net.Uri.parse(s.path) }.getOrNull()
            env = WaveformCache.envelope(ctx, path, uri, 1400)
        }
        bars = if (showSliceMarkers) MusicService.instance?.djSliceGrid(8).orEmpty() else emptyList()
        // 【V8.437】取三段包络（未就绪时 null → 渲染回退单色）
        bands3 = runCatching { WaveformCache.bands3(path) }.getOrNull()
        // 【V8.430】结构标记要等包络分析（warmDjStructure）落缓存才有值，
        // 这里先取一次；取不到时下面用 LaunchedEffect 重试。
        runCatching {
            val m = MusicService.instance?.djStructureMarks()
            marks = Triple(m?.introMs ?: -1, m?.outroMs ?: -1, m?.dropMs ?: -1)
        }
    }
    // 【V8.430】包络分析在后台 IO 线程（warmDjStructure），首次进入时缓存多半是空的。

    // 轮询取结果：检出即停。
    //
    // 【V8.430 修正】原来只等 12 x 700ms ≈ 8.4秒。整曲包络扫描要 seek+read 整首文件，
    // 在这台机器上常常超过 8 秒 —— 用户进DJ 页后等一会儿线还是没出现，就以为是没画。
    // 放宽到 30 x 600ms = 18 秒，且**只在三个值全为 -1 时继续等**（拿到任一项就不再空转）。
    LaunchedEffect(song?.id) {
        repeat(pollBudget) {
            delay(600)
            val m = runCatching { MusicService.instance?.djStructureMarks() }.getOrNull()
            // 【V8.437】kick 三段扫描要 20~60 秒（3400 首的库、6.6 小时超长曲更久），
            // 首帧一定拿不到 —— 所以这里轮询刷新，扫完后波形自己变色。
            // 【V8.437】kick 三段扫描要 20~60 秒（3400 首的库、6.6 小时超长曲更久），
            // 首帧一定拿不到 —— 所以这里轮询刷新，扫完后波形自己变色。
            // bands3() 只查缓存不触发扫描，所以这个轮询不会拖慢 UI。
            // 注意：`song` 是 State 的委托属性（open getter），**不能**在 lambda 里再次引用
            //   做 smart cast —— 必须先取到局部 val 再用。
            val s = song
            val p = if (s != null) s.filePath.ifEmpty { s.path } else ""
            if (p.isNotBlank() && bands3 == null) {
                bands3 = runCatching { WaveformCache.bands3(p) }.getOrNull()
            }
            val mm = m ?: return@LaunchedEffect
            val next = Triple(mm.introMs, mm.outroMs, mm.dropMs)
            if (next != marks) {
                marks = next
                if (next.first >= 0 || next.second >= 0 || next.third >= 0) return@LaunchedEffect
            }
        }
    }

    val peaks = env
    val unknown = peaks == null || peaks.isEmpty()

    // 【V8.378】波形离屏缓存成两张位图（已播亮色 / 未播暗色）。
    // 之前每帧画 1400 条 drawLine，播放头一动就全量重绘 —— 这是"波纹很卡"的根因。
    // 缓存后每帧只剩 2 次 drawImage + 1 条播放头线，播放头可以跑到 30fps 也不掉帧。
    // 【V8.443】改为**单张**位图（整曲）。旧版是「已播亮/ 未播暗」两张，
    // 靠 drawImage 裁剪拼接——那条路径会出现「右边一整块空白」。
    // 未播压暗改在 Canvas 上盖半透明遮罩，整曲的可见性由结构保证。
    val bitmap = remember(peaks, sizePx, bands3) {
        val pk = peaks
        if (pk == null || pk.isEmpty() || sizePx.width <= 0 || sizePx.height <= 0) null
        else renderWaveform(pk, sizePx.width, sizePx.height, bands3?.takeIf { it.first.size == pk.size })
    }

    // 【V8.444】去掉底色。
    //
    // 这层背景换过三次都没解决用户说的"黑色背景"：
    //   V8.440 `0x8C0E1116`（近黑 55%）→ 一块脏斑
    //   V8.442 `surface.copy(alpha=0.34f)` → 深色主题下 surface 本身就是深灰，
    //         34% 叠在浅色封面上照样是块能看见的暗 rectangle
    // 用户的原话是"波纹还有黑色背景"——他要的是**没有底色**，不是"底色浅一点"。
    // 未播段的可读性现在由 V8.444 的"整曲同亮 + 已播区提亮"保证，不再依赖底色托底。
    Box(
        modifier
            .then(Modifier.onSizeChanged { sizePx = it })
    ) {
        if (unknown || bitmap == null) {
            // 扫描中/尺寸未定：画一条极细的中线，表示"这里会出波形"，不是空白
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = Color(0x33FFFFFF),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1f
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(peaks) {
                        detectTapGestures { offset ->
                            if (size.width > 0) onSeekToFraction(offset.x / size.width)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas
                val wi = w.toInt()
                val hi = h.toInt()
                val hx = (w * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, wi)

                // 【V8.443 重写】整曲波形一次画全，未播段用遮罩压暗。
                //
                // 为什么不再用「两张位图 + drawImage 裁剪」：
                // 旧代码把已播/未播各烘一张位图，再按播放头 srcOffset/srcSize 裁剪贴图。
                // 这条路径有多个能变成**全空白**的入口（位图尺寸与实际有效列不匹配、
                // 裁剪矩形超出位图、扫描早退导致 peaks 长度 ≠ 请求桶数……），
                // 而症状都是「右边一整块空的」，极难定位。
                //
                // 现在只画**一张完整位图**（整曲必然全在），未播段盖一层半透明遮罩。
                // 这样"整曲一起显示"是结构上保证的，不依赖任何裁剪参数正确。
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(wi, hi),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(wi, hi)
                    )
                }

                // 【V8.444】不再压暗未播段。
                //
                // 原来这里盖一层 0xA60B0D11（近黑、65% 不透明），用户看到的
                //「黑色背景」有两层来源：Box 的底色 + 这层遮罩。两者叠加后，
                // 未播段在浅色封面上就是一条实实在在的黑带。
                //
                // 改成"整曲同亮度显示"，已播进度用底部一条亮线表示：
                //   - 整曲波纹全程可见（用户明确要"整曲都一起显示"）
                //   - 不需要任何深色底，浅色/深色封面上都不会糊成黑块
                //   - 进度靠底部亮线的长度读，比"半边暗半边亮"更干净
                if (hx > 0) {
                    val barH = (h * 0.09f).coerceAtLeast(1.5f)
                    drawRect(
                        color = Color(0xFFE8534F),
                        topLeft = Offset(0f, h - barH),
                        size = Size(hx.toFloat(), barH)
                    )
                }

                // 切片标记：红竖线贯穿整高，画在波形里
                if (bars.isNotEmpty()) {
                    val durMs = MusicService.instance?.oboeDirectPlayer?.getDurationMs() ?: 0L
                    val durF = durMs.toFloat().coerceAtLeast(1f)
                    for (b in bars) {
                        val bx = (b.positionMs / durF) * w
                        if (bx < 0f || bx > w) continue
                        drawLine(
                            color = Color(0xFFD04040),
                            start = Offset(bx, 0f),
                            end = Offset(bx, h),
                            strokeWidth = 1.5f
                        )
                    }
                }

                // 【V8.430】进拍/出拍标记：**横线**，对齐 djay 波形上那两条白线。
                //
                // 第一版画成了"竖线 + 上下小横帽"，理由是怕和 8 切片（半透明红竖线）、
                // 播放头（亮红竖线）混淆。但用户要看的就是**横线**，而且横线才符合
                // "某个时间点是能量边界"的语义。djay 的做法是：在标记时刻的 X 位置，
                // 于波形顶部和底部各画一条横线。这里照做：
                //   顶部横线 + 底部横线（明确的横线，一眼可见）+ 中间细竖线（指出时间位置）。
                // 颜色与 8 切片 / 播放头完全不同，不会混淆。
                val durMsHint = MusicService.instance?.oboeDirectPlayer?.getDurationMs() ?: 0L
                val (mkIntro, mkOutro, mkDrop) = marks
                // 【V8.436】**永远画虚拟出拍点**（曲长 94%）：
                // 现代母带（EDM/DJ 混音）常常检不出尾奏，实测 dyn=0.015 时三项全-1，
                // 界面就什么都不显示。用淡虚线画出"引擎实际会用的落点"，
                // 让用户永远看得到过渡会在哪里发生 —— 与检出的实测标记用不同样式区分。
                val virtualOutroMs = (durMsHint * 94L / 100L).toInt()
                run {
                    val durMs2 = MusicService.instance?.oboeDirectPlayer?.getDurationMs() ?: 0L
                    val durF2 = durMs2.toFloat().coerceAtLeast(1f)
                    fun markX(ms: Int): Float = (ms / durF2) * w

                    fun hMark(ms: Int, color: Color, alpha: Float, halfW: Float) {
                        val x = markX(ms)
                        if (x < -w * 0.02f || x > w * 1.02f) return
                        val cx = x.coerceIn(0f, w)
                        val x0 = (cx - halfW).coerceAtLeast(0f)
                        val x1 = (cx + halfW).coerceAtMost(w)
                        if (x1 <= x0) return

                        // 上下两条横线（主体）；先描深色底衬，保证压在亮波形上也能看清
                        for (yy in listOf(h * 0.055f, h * 0.945f)) {
                            drawLine(
                                color = Color(0xFF000000),
                                start = Offset(x0, yy),
                                end = Offset(x1, yy),
                                strokeWidth = 4.2f,
                                alpha = 0.5f * alpha
                            )
                            drawLine(
                                color = color,
                                start = Offset(x0, yy),
                                end = Offset(x1, yy),
                                strokeWidth = 2.0f,
                                alpha = alpha
                            )
                        }
                        // 中间细竖线：指示这条标记落在时间轴的哪一点
                        drawLine(
                            color = color,
                            start = Offset(cx, h * 0.055f),
                            end = Offset(cx, h * 0.945f),
                            strokeWidth = 1.2f,
                            alpha = 0.75f * alpha
                        )
                    }

                    // 【V8.436】虚线版标记（虚拟出拍点用）：短划线，无底衬，视觉更弱
                    fun hMarkDashed(ms: Int, color: Color, alpha: Float, halfW: Float) {
                        val x = markX(ms)
                        if (x < -w * 0.02f || x > w * 1.02f) return
                        val cx = x.coerceIn(0f, w)
                        // 5 段短划，间隔 4px —— 视觉上明确区别于实测标记的实线
                        val segW = (halfW * 2f) / 5f
                        for (k in 0 until 5) {
                            val sx0 = (cx - halfW + k * segW).coerceAtLeast(0f)
                            val sx1 = (sx0 + segW * 0.6f).coerceAtMost(w)
                            if (sx1 <= sx0) continue
                            for (yy in listOf(h * 0.055f, h * 0.945f)) {
                                drawLine(color, Offset(sx0, yy), Offset(sx1, yy), 1.8f, alpha = alpha)
                            }
                        }
                        drawLine(color, Offset(cx, h * 0.055f), Offset(cx, h * 0.945f), 1f, alpha = 0.5f * alpha)
                    }

                    // 出拍：青绿（能量塌下去的收尾点）
                    if (mkOutro >= 0) hMark(mkOutro, Color(0xFF3FD0C8), 1f, w * 0.022f)
                    // 进拍：琥珀（主旋律真正开始 = 可起手点）
                    if (mkIntro >= 0) hMark(mkIntro, Color(0xFFE8B040), 1f, w * 0.022f)
                    // Drop：半透明紫（能量爆发点，视觉更弱，不抢主标记）
                    if (mkDrop >= 0) hMark(mkDrop, Color(0x998A6BE0), 0.8f, w * 0.014f)
                    // 【V8.436】实测尾奏检不出时，画虚拟出拍点（曲长 94%）。
                    // 这**就是引擎实际会用的落点**（MusicService 的 virtualOutro 同口径），
                    // 所以看到这条线就等于知道"过渡会在此处前 dur 秒开始"。
                    if (mkOutro < 0 && virtualOutroMs > 0) {
                        hMarkDashed(virtualOutroMs, Color(0xFF3FD0C8), 0.55f, w * 0.026f)
                    }
                }


                // 播放头
                drawLine(
                    color = Color(0xFFE84040),
                    start = Offset(hx.toFloat(), 0f),
                    end = Offset(hx.toFloat(), h),
                    strokeWidth = 2.5f
                )
            }

            // 小节号标签：只标首尾两个，中间画满数字会糊成一片
            if (showBarLabels && bars.size >= 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1", color = Color(0xFF8A8A92), fontSize = 9.sp)
                    Text("8", color = Color(0xFF8A8A92), fontSize = 9.sp)
                }
            }
        }
    }
}

/**
 * 【V8.378】把整曲波形离屏渲染成两张位图（已播亮色 / 未播暗色）。
 *
 * 只画一次，之后每帧只做 drawImage 裁剪拼接。播放头从 500ms/次（2fps，肉眼卡顿）
 * 提到 33ms/次（30fps）也不会掉帧 —— 因为重绘成本从"1400 条线"降到"2 次贴图"。
 */
/**
 * 【V8.437】波形渲染：djay 式**频谱着色**。
 *
 * ## 为什么这样配色
 * djay 的波形是**连续按频段着色**的（Algoriddim 官方定义）：
 *   红 = Bass(低频)  黄绿 = Low-mid  绿 = High-mid  蓝 = Vocals/高频
 * 所以"人声句是蓝色、低频重的 drop 段是红色"，是渐变过渡而非整齐色块。
 *
 * ## 实现要点
 * · 每一列用三段能量占比混色：low 偏红 / mid 偏绿 / high 偏蓝
 * · **已播/未播两态保留**：未播整体压暗+降饱和，仍能一眼看出进度
 *   （这是原有行为，不能为了 djay 配色牺牲掉）
 * · 三段缺失（还没扫完 / 该曲没跑过 wantKick）时回退到原来的单色
 * · 逐列 drawLine 1400 次，和原来一样在**离屏位图**上画完再缓存，
 *   不进Canvas 每帧回调（V8.378 的性能结论不能破）
 */
/** 【V8.455】三段能量全为0（极端静默）时的兜底色。 */
private val WAVE_IDLE = Color(0xFF4A5568)

/**
 * 【V8.455】HSL -> Color。
 *
 * 波形着色改用 HSL 的原因：原来的 RGB 加权平均会让三段互相抵消出脏灰色。
 * HSL 里**色相独立于饱和度和亮度**，可以只控色相（决定是哪个频段），
 * 同时把饱和度和亮度分别绑定到"主导度"和"该列包络"，互不干扰。
 */
private fun hslToColor(hDeg: Float, s: Float, l: Float): Color {
    val h = ((hDeg % 360f) + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    var r = 0f; var g = 0f; var b = 0f
    when (hp.toInt().coerceIn(0, 5)) {
        0 -> { r = c; g = x; b = 0f }
        1 -> { r = x; g = c; b = 0f }
        2 -> { r = 0f; g = c; b = x }
        3 -> { r = 0f; g = x; b = c }
        4 -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    val m = l - c / 2f
    return Color(
        ((r + m) * 255f).toInt().coerceIn(0, 255),
        ((g + m) * 255f).toInt().coerceIn(0, 255),
        ((b + m) * 255f).toInt().coerceIn(0, 255),
        255
    )
}

private fun renderWaveform(
    peaks: FloatArray,
    w: Int,
    h: Int,
    bands: Triple<FloatArray, FloatArray, FloatArray>? = null
): ImageBitmap {
    val bm = ImageBitmap(w, h)
    val canvas = Canvas(bm)
    val paint = Paint().apply {
        strokeCap = StrokeCap.Round
        isAntiAlias = false
    }
    val mid = h / 2f
    val n = peaks.size
    //【V8.443】列宽按**实际桶数**算，并且至少 1px。
    // 旧代码 `colW = w / n` 在扫描早退（peaks.length < 请求桶数）时会把列拉得很宽，
    // 最后一列的起点可能超过 w，尾部列画到界外看不见 —— 症状是右边一整块空。
    val colW = (w.toFloat() / n).coerceAtLeast(1f)
    // 只有当实际列总宽超出画布时才裁剪；正常情况（colW>=1）画满整宽。
    paint.strokeWidth = (colW * 0.92f).coerceAtLeast(1f)
    for (i in 0 until n) {
        val x = i * colW
        // 越界（colW 被下限截到 1px 而 n*colW > w 的极端情况）直接停，
        // 否则最后一列会画到画布外，白白浪费一列还可能触发异常。
        if (x >= w) break
        val amp = (peaks[i] * h * 0.47f).coerceIn(0.5f, h * 0.47f)
        val c = when {
            bands != null && i < bands.first.size && i < bands.second.size && i < bands.third.size -> {
                val lo = bands.first[i]
                // 【V8.482 修"整条波形是蓝色"· 先还原真实能量比例】
                // WaveformCache 扫描端对 mid x2.0 / high x4.0（+6dB/+12dB），
                // 那是为响度分析/人声检测提亮用的。这里直接拿原始值当色相权重，
                // 高频被放大 4 倍 -> 几乎每列都是高频主导 -> 色相被钉死在 186°（青蓝），
                // 整条波形一片蓝（用户截图 FLAC 实测）。先除回去再算占比。
                val md = bands.second[i] / 2f
                val hi = bands.third[i] / 4f
                val sum = lo + md + hi
                if (sum < 1e-5f) WAVE_IDLE
                else {
                    val wl = (lo / sum).coerceIn(0f, 1f)
                    val wm = (md / sum).coerceIn(0f, 1f)
                    val wh = (hi / sum).coerceIn(0f, 1f)

                    // 【V8.455 重做配色· 用户反馈"颜色很难看"】
                    //
                    // 原公式是三段 RGB 的**加权平均**：
                    //   r = 0.86*wl + 0.62*wm + 0.30*wh ...（再除以权重和）
                    // 这是个致命错误：只要三段都有能量（几乎每一列都是），
                    // 平均结果就趋向中性灰/灰黄。djay 的波形是**高饱和的纯色带**，
                    // 永远不会出现这种脏色。用户看到的"很难看"就是它。
                    //
                    // 正确做法：按**主导频段**选色相，而不是混色。
                    // 取三段里权重最大的那个，取它对应的纯色（低=红/橙，中=黄绿，高=蓝），
                    // 再按"领先程度"做一点相邻色相的插值，让过渡自然但保持饱和。
                    //
                    // 用 HSL 而不是直接 RGB：色相均匀分布，不会出现某段特别暗。
                    val maxW = maxOf(wl, wm, wh)
                    // 领先度：主导段比第二名高出多少（0 = 三段完全持平）
                    val second = maxOf(
                        if (maxW == wl) maxOf(wm, wh) else maxOf(wl, wm),
                        if (maxW == wh) maxOf(wl, wm) else 0f
                    )
                    val lead = ((maxW - second) / maxW).coerceIn(0f, 1f)

                    // 【V8.482 暖色系调色板 · 用户反馈"蓝色很难看"】
                    //
                    // 色相仍按三段权重连续插值（向量法，跨 0° 不出错），
                    // 但三档颜色全部收进暖色弧段，整条波形永远协调：
                    //   低频（鼓/贝斯）   -> 12°  橙红
                    //   中频（旋律/人声） -> 45°  金黄
                    //   高频（镲片/空气） -> 335° 玫红
                    // 鼓点多的段落偏橙红、明亮的段落偏玫红，过渡平滑，
                    // 不会再出现整条青蓝/黄绿/灰这类翻车色。
                    val hLo = 12f; val hMd = 45f; val hHi = 335f
                    val radD = Math.PI / 180.0
                    var vx = wl * Math.cos(hLo * radD) + wm * Math.cos(hMd * radD) + wh * Math.cos(hHi * radD)
                    var vy = wl * Math.sin(hLo * radD) + wm * Math.sin(hMd * radD) + wh * Math.sin(hHi * radD)
                    // 三段几乎持平时向量和趋近 0，色相会乱飘 -> 往金黄（中频）拉
                    val vlen = Math.sqrt(vx * vx + vy * vy)
                    if (vlen < 0.25) {
                        val tt = (vlen / 0.25).toFloat()
                        vx += (1.0 - tt) * (0.75 * Math.cos(hMd * radD))
                        vy += (1.0 - tt) * (0.75 * Math.sin(hMd * radD))
                    }
                    var hDeg = (Math.atan2(vy, vx) / radD).toFloat()
                    if (hDeg < 0f) hDeg += 360f

                    // 饱和度跟主导度走：越明确越饱和；持平稍降避免噪点感
                    val sat = 0.74f + 0.18f * lead
                    // 亮度：深色背景上保持明亮，再叠一层包络保留强弱层次。
                    val light = 0.50f + 0.22f * peaks[i].coerceIn(0f, 1f)
                    hslToColor(hDeg, sat, light)
                }
            }
            else -> Color(0xFF5FA8DC)
        }
        paint.color = c
        canvas.drawLine(Offset(x, mid - amp), Offset(x, mid + amp), paint)
    }
    return bm
}

/**
 * 【V8.376】右下角引擎读数 —— 缩成小字，不抢波形的位置。
 * djay 横屏没有这一块（那是它 Automix 隐藏了），但你之前明确要看对拍数字，
 * 所以保留、只是不占地方。
 */
@Composable
private fun DjEngineReadout(xf: MusicService.CrossfadeUiInfo) {
    val txt = when {
        xf.aBpm > 0f && xf.bBpm > 0f ->
            "A ${xf.aBpm.toInt()} ⇄ B ${xf.bBpm.toInt()} → ${xf.targetBpm.toInt()} BPM" +
                if (kotlin.math.abs(xf.ratioPct) > 0.2f)
                    "   ${if (xf.ratioPct > 0) "+" else ""}${"%.1f".format(xf.ratioPct)}%"
                else ""
        xf.nextTitle.isNotBlank() -> "下一首：${xf.nextTitle.take(14)}"
        else -> "待命 · 引擎自动选曲"
    }
    Text(
        text = txt,
        color = Color(0xFF6A6A72),
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 340.dp)
    )
}

@Composable
private fun CircleAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (filled) Color(0xFF2E7D4A) else Color(0xFF26262A))
                .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (label == "下一曲") Icons.Default.SkipNext else Icons.Default.PlayArrow,
                contentDescription = label,
                tint = if (enabled) Color(0xFFE8E8EA) else Color(0xFF5A5A60),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFF5A5A60), fontSize = 11.sp)
    }
}

@Composable
private fun MixButton(onClick: () -> Unit, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (enabled) Color(0xFF2A2A2E) else Color(0xFF1E1E21))
                .clickable(enabled = enabled, onClickLabel = "切歌", onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "切歌  ››",
                color = if (enabled) Color(0xFFE8E8EA) else Color(0xFF4A4A50),
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("立即混合", color = Color(0xFF5A5A60), fontSize = 11.sp)
    }
}
