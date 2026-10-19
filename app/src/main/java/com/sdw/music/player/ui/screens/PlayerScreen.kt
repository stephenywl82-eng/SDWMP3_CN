@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.dsp.EqualizerManager
import com.sdw.music.player.core.audio.dsp.MsebCalculator
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.metadata.SongSpecReader

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.util.Log
import android.provider.Settings
import com.sdw.music.player.BuildConfig
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.blur
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import com.sdw.music.player.R
import com.sdw.music.player.util.DeviceMapper
import com.sdw.music.player.ui.components.LyricViewCompose
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LrcParser
import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.MemoryManager
import android.os.PowerManager
import android.os.SystemClock
import com.sdw.music.player.ui.theme.*

import com.sdw.music.player.ui.components.PlayerVisualMode
import com.sdw.music.player.ui.components.VuMeter
import com.sdw.music.player.ui.components.VuMeterStyle
import com.sdw.music.player.ui.components.PlayerUiTicker   // 【V8.428】玩家页统一 ticker
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.SheetImmersiveStatusBar
import com.sdw.music.player.ui.components.ImmersiveSystemBars
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.viewmodel.PlayerState
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.SharedCoverState
import androidx.media3.common.Player

// 【V8.174】bandsL/bandsR = Oboe 里左右声道各自那条 8 频段级联的输出（未压到 1.0，
// 上限 4.0）。Mixer VU 表的 14 根柱子靠它画真正的 L/R 两列 —— 以前只有混音后的
// 单声道值，右列只能是左列乘个常数，所以"右边的永远低"。
data class BandLevels(
    val sub: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val high: Float = 0f, val rms: Float = 0f,
    val bandsL: FloatArray = FloatArray(8), val bandsR: FloatArray = FloatArray(8)
)

/** 【V8.177】封面转速（度/秒）：12°/s = 30 秒转一圈，跟黑胶一个量级。 */
private const val COVER_ROT_DPS = 12f

/** Map raw band energy (0~0.15ish) to display range 0~1 using log10, calibrated for LP-diff output */
private fun Float.toLog(): Float {
    if (this <= 0.0001f) return 0f
    // log10 maps: 0.001→0.35, 0.01→0.67, 0.1→1.0
    val v = (kotlin.math.log10(this) + 3f) / 3f
    return v.coerceIn(0f, 1f)
}

@Composable
fun PlayerScreen(
    state: PlayerState,
    sharedCoverState: SharedCoverState? = null,
    fullCoverVisible: Boolean = true,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    isPlaying: Boolean = false,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    // 【Lyrico 联动】三点菜单「用 Lyrico 编辑」返回后的回调（携带 result extras）；由调用方接到 VM
    // 的 onExternalTagEditReturned，就地刷新封面/标签（含从被改写文件重新抽封面，绕过平台 albumart 缓存）
    onLyricoExternalEditReturned: (songId: Long, filePath: String, extras: Bundle?) -> Unit = { _, _, _ -> },
    onToggleEqualizer: () -> Unit,
    onDeleteSong: () -> Unit,
    onNavigateToAlbum: (albumName: String) -> Unit = {},
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onNavigateToAudioDiagnostic: () -> Unit = {},
    onNavigateToMseb: () -> Unit = {},
    onPlayQueueIndex: (Int) -> Unit = {},
    onReorderQueueByFlow: () -> Unit = {},
    onPreviewUpNext: () -> List<Pair<Song, String>> = { emptyList() },
    onDismiss: (() -> Unit)? = null,
    audioSessionId: Int = 0,
) {
    val context = LocalContext.current

    // 【v8.165 省电】「亮屏 + 本 Activity 真的在前台」双条件开关
    // LaunchedEffect 不感知 STOP：熄屏、或被其它 App 完全盖住时组合仍在，while(true)
    // 轮询照样按原频率跑。这里把两个条件取交集，供下面所有轮询共用。
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var screenOn by remember { mutableStateOf(powerManager.isInteractive) }
    var uiResumed by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (isActive) {
            screenOn = powerManager.isInteractive
            delay(2000)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> uiResumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> uiResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        uiResumed = lifecycleOwner.lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val uiActive = screenOn && uiResumed
    // 【V8.227 修复】LaunchedEffect(Unit) 里直接读 uiActive 是组合时捕获的旧值（协程不会
    // 随重组更新），导致：启动于亮屏 → 熄屏后轮询永不休眠；启动于熄屏 → 解锁后永不恢复。
    // rememberUpdatedState 让协程里读到的永远是最新值。
    val uiActiveState by rememberUpdatedState(uiActive)
    // 【V8.428】同理：isPlaying 是函数参数，协程里必须读 rememberUpdatedState 才是最新值，
    // 否则 VU 频段发布会在首次组合后一直卡在"未播放"（60Hz 快路径永不进）。
    val isPlayingState by rememberUpdatedState(isPlaying)

    // Resolve cover URI: embedded album art from MediaStore
    fun resolveCoverUri(): String? {
        // [V8.94] 判据用 hasRealCover：扫描器给每行都写非空 albumArtUri（无封面时是 albumart
        // 虚拟表死链接），原来的 isNullOrEmpty 恒 false → 死链一路流进封面位与模糊背景解码
        // → 无封面歌的封面位反而空白，且 accent 提取失败路径被绕过。
        return state.currentSongAlbumArt.takeIf { SongRepository.hasRealCover(it) }
    }
    var coverUri by remember { mutableStateOf(resolveCoverUri()) }
    // 【V8.178】换碟防闪：封面/模糊背景都走 Coil 异步解码，直接切 uri 会让
    // AnimatedContent/Crossfade 的 incoming 层空一帧（露白或露占位图）—— 就是切歌时
    // 播放界面"闪一下"。改成先把新封面的两张图（big 封面 + bg 小图）预热进内存缓存，
    // 就绪后才把 coverUri 指向新图。B 轨（crossfade overlay）在下面的预读处同样预热。
    val bgDecodePx = remember(context) {
        val w = context.resources.displayMetrics.widthPixels
        (w / 3).coerceAtLeast(320)
    }
    suspend fun preheatCoverImages(uri: String) {
        val loader = coil.Coil.imageLoader(context)
        withContext(Dispatchers.IO) {
            // big 封面：与 rememberSharedCoverPainter(big=true) 同 key → 命中同一条目
            runCatching {
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(uri)
                        .size(com.sdw.music.player.ui.components.SharedCoverRequest.decodePx(context))
                        .memoryCacheKey(com.sdw.music.player.ui.components.SharedCoverRequest.cacheKeyBig(uri))
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                )
            }
            // 模糊背景：与背景 Crossfade 的显示请求同参数 → 同缓存键
            runCatching {
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(uri)
                        .size(bgDecodePx)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                )
            }
        }
    }
    // Re-resolve when song changes, OR when the cover URI changes for the same song
    // (e.g. Lyrico edited the cover and we pushed a fresh URI into currentSongAlbumArt).
    LaunchedEffect(state.currentSongId, state.currentSongAlbumArt) {
        val next = resolveCoverUri()
        if (next != null) preheatCoverImages(next)
        coverUri = next
    }

    // 【V8.4】Crossfade UI 状态轮询：驱动氛围色交接 + 封面交叉淡化动画
    // 【V8.357】Which surface are we on? DJ off -> plain player, no ⚡ / ⏭ buttons,
    // hard cuts only. DJ on -> DJ player. Two separate UIs, not one shared UI.
    val djEnabled = rememberDjEnabled(context)
    var crossfadeInfo by remember { mutableStateOf(com.sdw.music.player.core.audio.playback.MusicService.CrossfadeUiInfo()) }
    // 【V8.428】原来这里有一个独立的 60ms 轮询环（外加下面 bandLevels 的 60Hz 环、
    // 两处 50ms 装饰动画、VuMeter 自己的 60fps 帧时钟）。现已全部并入下方**唯一**的
    // 统一 ticker（PlayerUiTicker，100ms 一帧），一帧只重组一次。
    // 熄屏自动切歌 → 服务端状态走到 DONE；熄屏时仍以 1s 节奏同步 crossfadeUiInfo，
    // 否则解锁后叠加层状态机 / 封面预热 / xfadeSettled 判定会拿到过期快照 → 封面停在旧歌。
    // 【V8.4】crossfade 触发时预读 B 轨封面主色（供 accent 动画目标色用）
    var crossfadeNextAccent by remember { mutableStateOf(0L) }
    var crossfadeNextArt by remember { mutableStateOf<String?>(null) }
    // 【V8.178】B 轨 5 色调色板预读：VU 表/边缘光带在交叉淡化期间就该跟 B 轨走，
    // 不然要等切歌后异步提取完成才变色（提取失败还会一直挂旧色）。
    var crossfadeNextCoverColors by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(crossfadeInfo.nextAlbumArt, crossfadeInfo.state) {
        val nextArt = crossfadeInfo.nextAlbumArt
        if (nextArt?.isNotBlank() == true) {
            crossfadeNextArt = nextArt
            // 【V8.178】B 轨两张图（big 封面给 overlay、bg 小图给模糊背景）提前预热，
            // 交叉淡化开始时 B 层已就绪，不空帧。
            preheatCoverImages(nextArt)
            // 【V8.179】只提一次调色板：extractAccentColor 和 extractCoverColors 各自会
            // 完整解码一遍封面（大图 3000px 时是几百 ms）。交叉淡化还没开始就先付两遍解码
            // 代价，切歌瞬间 CPU 被打满 → 掉帧。调色板首位就是 accent 同源色。
            val colors = MemoryManager.extractCoverColors(
                context, nextArt, state.currentSongId + 1000000L
            )
            crossfadeNextCoverColors = colors?.toList().orEmpty()
            val c = colors?.let { it.getOrNull(1) ?: it.getOrNull(0) } ?: 0
            if (c != 0) crossfadeNextAccent = c.toLong() and 0xFFFFFFFFL
        } else {
            crossfadeNextArt = null
            crossfadeNextAccent = 0L
            crossfadeNextCoverColors = emptyList()
        }
    }

    val hasCoverColor = state.accentColor != 0L && coverUri?.isNotEmpty() == true
    // 【V8.4】状态机：ACTIVE=交叉淡化中（动画）；DONE=音频已交接但封面 URI 未切（保留叠加层防跳变）
    val xfadeUiState = crossfadeInfo.state
    // 【V8.179】crossfade 收尾后 MusicService 的 crossfadeUiInfo 停在 DONE 不回 IDLE
    // （只有下一轮 PRELOADING 才会整体重建）。DONE 且封面 URI 已切到 B 轨 = 过渡真的结束了，
    // 这里必须当 IDLE 看：否则配色会一直锁在预读的 B 主色上、换碟动画（instantCoverSwitch）
    // 也被永久关掉 —— 表现就是「交叉切歌后卡住，颜色不跟着新歌走」。
    val xfadeSettled = xfadeUiState == com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.DONE &&
            crossfadeNextArt?.isNotBlank() == true && coverUri == crossfadeNextArt
    val xfadeStateEff = if (xfadeSettled) com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.IDLE else xfadeUiState
    val xfadeActive = xfadeStateEff == com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.ACTIVE
    // 叠加层显示条件：只在 ACTIVE/DONE 阶段显示（PRELOADING 时 crossfade 还没开始，B 不能提前盖住 A）
    // 【V8.208】外加"交接完成后的宽限期"：settled 时 xfadeStateEff 已经是 IDLE，得单独放行，
    // 否则叠加层会在主封面还没画上的那一帧就被撤掉（见下方 overlayRelease）。
    val xfadeOverlayVisible = (xfadeStateEff == com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.ACTIVE
            || xfadeStateEff == com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.DONE
            || xfadeSettled)
            && crossfadeNextArt?.isNotBlank() == true
    // 【V8.208】撤叠加层的时机从"封面 URI 一切到 B 就撤"改成"再挂 150ms 再撤"。
    // 主封面 AnimatedContent 换到 B 的那一帧，painter 未必已经解码完（Coil 异步），
    // 底下会先露出 DefaultCoverImage 占位图 = 一次可见的闪。叠加层画的是同一张 B 封面，
    // 多留几帧盖住这个空窗，观感上毫无差别，但那一下闪烁没了。
    var overlayRelease by remember { mutableStateOf(false) }
    LaunchedEffect(xfadeSettled) {
        overlayRelease = if (xfadeSettled) {
            delay(150)
            true
        } else false
    }
    // DONE 阶段封面 URI 已切（currentSongId 变化）时，叠加层撤除时机：coverUri 已等于 B 封面
    val xfadeOverlayStale = overlayRelease
    // 【V8.178】VU 表 / 边缘光带的调色板：交叉淡化期间直接用 B 轨预读色，
    // 别等切歌后异步提取（那 1~2 秒里柱子还挂在上首歌的颜色上）。
    // 【V8.187】但"交叉淡化结算"不能只看封面 URI 有没有切完，还得看新歌的调色板是否真的到手：
    // 提取是异步的，交接瞬间 coverUri 已经指向新封面、而 state.coverColors 还是上一首的旧值，
    // 一回落就先把 VU 表打回旧色、等提取完再跳回来 —— 这就是"切歌后 vu 表颜色不对"。
    // 所以：新调色板到手才交给 state.coverColors，否则继续挂着 B 轨预读色。
    val coverPaletteFresh = state.coverColors.isNotEmpty() &&
        state.coverColorsSource?.isNotBlank() == true && state.coverColorsSource == coverUri
    // 【V8.205】封面取色直接搬进 UI 会刺眼：取色算法挑的就是封面最艳那几档（S≈0.9~1.0），
    // 同一支色同时喂给 VU 柱、歌词 glow、歌名、按键。这里统一收敛一次 —— 色相不动，
    // 只压饱和度和明度，省得每一处调用点各写一遍 clamp。
    // 【V8.490 回归修复】原先这里每次组合都 new 出一个 List 传给 LiveVuMeter，
    // 成为重组风暴的源头之一：PlayerScreen 每帧重排时 vuCoverColors 都是新对象，
    // VuMeter 的 remember(coverColors, accentColor) 跟着每帧重算、狂打 SDW_VuColor 日志。
    // 用 remember 包住，只有取色 / 转场输入真正变化时才重建，稳定对象引用。
    val vuCoverColors = remember(
        coverPaletteFresh, xfadeStateEff, crossfadeNextCoverColors, state.coverColors
    ) {
        CoverTone.calmAll(
            when {
                coverPaletteFresh -> state.coverColors
                xfadeStateEff != com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.IDLE &&
                    crossfadeNextCoverColors.any { it != 0 } -> crossfadeNextCoverColors
                else -> state.coverColors
            }
        )
    }
    // 【V8.190】真机诊断：柱子颜色 ≠ 封面色时，看这里就知道 coverColors 到底是什么。
    // 歌曲或调色板变化时打一次：coverColors 5 档 hex + vuCoverColors 最终采用值 + accent。
    LaunchedEffect(coverUri, state.coverColors, state.accentColor) {
        if (coverUri.isNullOrBlank()) return@LaunchedEffect
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        // 注意：这里不能直接引用下方 361 行才声明的 accentColor（Compose 的 val 必须先声明后用），
        // 所以改用 state.accentColor —— 主题兜底时它是 0L。
        android.util.Log.d("SDW_VuColor",
            "state=[${state.coverColors.joinToString(",") { hex(it) }}] " +
            "vu=[${vuCoverColors.joinToString(",") { hex(it) }}] " +
            "accent=${String.format("#%06X", (state.accentColor and 0xFFFFFF).toInt())} " +
            "src=${state.coverColorsSource?.takeLast(24)}")
    }
    // 【V8.187】真机诊断：交叉淡化结算那一刻，VU 表用的是谁的调色板。
    // 打不出"颜色不对"的根因时，看这几行就知道是"新调色板没到手"还是"预读提错图"。
    LaunchedEffect(crossfadeInfo.state, coverPaletteFresh, coverUri) {
        if (crossfadeInfo.state != com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.DONE) return@LaunchedEffect
        android.util.Log.d(
            "SDW_XfadeColor",
            "xfade DONE: fresh=$coverPaletteFresh stateColors=${state.coverColors.size} " +
                "stateSrc=${state.coverColorsSource?.takeLast(20)} nextColors=${crossfadeNextCoverColors.count { it != 0 }} " +
                "coverUri=${coverUri?.takeLast(20)} -> vuSize=${vuCoverColors.size}"
        )
    }
    // 【V8.139】与音频同相的过渡进度（0..1）：以 native 真正启动时刻为基准，按 16ms 帧推进。
    // B 封面/背景据此「续接」而不是从 0 重跑整段——音频混到一半时画面已同步走了一半，
    // 观感上 A 轨画面明显提前开始让位（用户诉求），且不再受 200ms 轮询量化影响。
    val xfadeStart = crossfadeInfo.startedAtMs
    // 【V8.207】帧时钟已从本页挪进 PlayerCoverArt（见那边 fadeOverlayActive/StartMs）。
    // 原来每 16ms 改一次顶层 state，整页跟着 60fps 重组，白掉帧。
    // 【V8.207】DONE 之后必须锁 1f：native 交接完成时进度条往往还停在 0.9x（tick 已停），
    // 叠加层就留着 10% 的 A 轨封面透出来；紧接着的「撤叠加层 + 主封面瞬切到 B」那一帧，
    // 这点残留会表现为一次可见的跳变。音频既然已经全在 B，画面就该是纯 B。
    val xfadeOverlayProgress = if (!xfadeActive && xfadeOverlayVisible) 1f else 0f
    // 【V8.207】换碟动画门控延后一拍放开。
    // 旧写法直接拿 xfadeStateEff != IDLE 当 instantCoverSwitch：交接成功（DONE 且封面 URI
    // 已切到 B）的那一帧 xfadeStateEff 立刻变 IDLE，于是同一帧里叠加层撤除、主封面的
    // AnimatedContent 却改用 280ms 淡入 + spring 缩放（0.90→1）——画面从 B(叠加层) 掉回 A
    // 再渐变回 B，就是自动切歌时看到的「闪一下」。这里让门控比换碟动画多活一会儿，
    // 保证最后一次切换仍走 0ms 瞬时分支，叠加层撤除与主封面落地在同一帧完成。
    var coverSwitchInstant by remember { mutableStateOf(false) }
    // 【V8.208】主色窗口比换碟窗口更长：取色是异步的（几百 ms 到 1s+），
    // 520ms 一过就把主色交还给 state.accentColor，而那时提取往往还没回来，
    // 背景/VU/文字会先闪回 A 轨旧色（或主题色）再跳一次 —— 换个更长的窗口兜住。
    var accentHoldWindow by remember { mutableStateOf(false) }
    LaunchedEffect(xfadeStateEff) {
        if (xfadeStateEff != com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.IDLE) {
            coverSwitchInstant = true
            accentHoldWindow = true
        } else {
            // 520ms > 换碟动画（280ms 淡入 + spring 收尾），确保切换已落地
            delay(520)
            coverSwitchInstant = false
            // 再留 700ms（累计约 1.2s）给异步取色，期间继续挂 B 轨预读色
            delay(700)
            accentHoldWindow = false
        }
    }
    // 【V8.207】新歌主色到手了没有：提取是异步的（几百 ms），交接那一帧 state.accentColor
    // 往往还停在 A 轨的旧值（或 0 → 主题色），直接用会让背景/文字/VU 先闪回旧色再跳一次。
    val accentFresh = state.accentColorSource?.isNotBlank() == true && state.accentColorSource == coverUri
    // accent 目标色：ACTIVE/DONE 阶段用 B 主色（DONE 等封面 URI 切完后由 state.accentColor 接管新歌主色）
    // 【V8.208】交接刚结算、新歌主色还没到手的那一小段（用 accentHoldWindow，约 1.2s）
    // 继续挂 B 轨预读色 —— B 色本来就是这首歌的主色，接着用不会错，比闪回旧色好看得多。
    val xfadeTargetAccent = when {
        xfadeStateEff != com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.IDLE && crossfadeNextAccent != 0L ->
            Color(crossfadeNextAccent)
        accentHoldWindow && !accentFresh && crossfadeNextAccent != 0L -> Color(crossfadeNextAccent)
        else -> null
    }
    val accentColor by animateColorAsState(
        // 【V8.205】降饱和收敛：色相原样保留，艳度压到眼睛舒服的区间。
        targetValue = CoverTone.calm(
            xfadeTargetAccent
                ?: if (hasCoverColor) Color(state.accentColor) else MaterialTheme.colorScheme.primary
        ),
        animationSpec = tween(
            if (xfadeActive) crossfadeInfo.durationMs.coerceIn(1000, 15000) else 600,
            easing = FastOutSlowInEasing
        ),
        label = "accentColor"
    )
    val textAccentColor = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (accentColor.red * 255).toInt(), (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(), hsv
        )
        if (hsv[2] < 0.65f) hsv[2] = 0.65f + (hsv[2] * 0.25f).coerceIn(0f, 0.1f)
        if (hsv[1] > 0.55f) hsv[1] = 0.55f + (hsv[1] - 0.55f) * 0.3f
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    var showMenu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    // 【V8.7】播放界面右上角菜单 -> 添加到歌单
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val currentSong = remember(state.currentSongId, state.songList, state.queue) {
        state.songList.firstOrNull { it.id == state.currentSongId }
            ?: state.queue.firstOrNull { it.id == state.currentSongId }
    }

    // 【V8.21】歌曲规格懒显示（Spec 对象 → 分段着色）：FLAC · 24bit · 96kHz · 4609kbps
    var songSpec by remember { mutableStateOf<com.sdw.music.player.core.audio.metadata.SongSpecReader.Spec?>(null) }
    LaunchedEffect(currentSong?.id) {
        val song = currentSong
        songSpec = null
        if (song != null) {
            songSpec = withContext(Dispatchers.IO) {
                com.sdw.music.player.core.audio.metadata.SongSpecReader.specFor(song)
            }
        }
    }

    // Sleep timer countdown display (poll every second while active)
    var sleepTimerRemaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            sleepTimerRemaining = MusicService.instance?.getSleepTimerRemainingMs() ?: 0L
            delay(1000)
        }
    }

    // Lyrics
    val lyricsLines = remember(state.currentLyrics) {
        if (!state.currentLyrics.isNullOrBlank()) LrcParser.parse(state.currentLyrics) else emptyList<LyricLine>()
    }
    val currentLyricLine = if (lyricsLines.isNotEmpty()) {
        val idx = LrcParser.findCurrentLineIndex(lyricsLines, positionMs)
        if (idx in lyricsLines.indices) lyricsLines[idx].text else null
    } else null
    // 【V8.7】三行歌词：当前行索引（用于取上一行/下一行）
    val currentLyricIdx = if (lyricsLines.isNotEmpty()) {
        val idx = LrcParser.findCurrentLineIndex(lyricsLines, positionMs)
        if (idx in lyricsLines.indices) idx else -1
    } else -1

    // [V8.135] 播放界面同步全屏歌词页的「逐字开关」（同一偏好键 sdw_music_prefs / lyric_word_by_word）。
    // 与全屏歌词页保持完全一致：不再要求「必须带真实逐字时间戳」，普通整行 LRC
    // 也按行时长逐字插值（buildWordSegments 内已有该兜底分支），否则会出现
    // 「全屏页开了逐字、播放页却还是整行」的不一致。
    val wordByWordOn = LocalContext.current
        .getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
        .getBoolean("lyric_word_by_word", false)

    // [V8.81] No BackHandler here on purpose.
    // This screen is composed inside the Player route's PredictiveBackHandler. A plain
    // BackHandler registers an OnBackPressedCallback whose handleOnBackStarted/
    // Progressed/Cancelled are the base no-ops, and OnBackPressedDispatcher routes back
    // lifecycle events to the LAST-ADDED enabled callback - i.e. this one, because it is
    // composed later. That shadowed the predictive handler: progress never arrived, so the
    // cover never followed the finger and back just popped instantly. The route-level
    // PredictiveBackHandler already pops the Player destination, so nothing else is needed.

    // [V8.125] Immersive: hide the status bar while the player is on screen.
    // The previous version took the controller from the COMPOSE view
    // (WindowInsetsControllerCompat(window, view)); that controller is null until the view is
    // attached to the window, so hide() could silently no-op forever. It also resolved the
    // host window with a bare `view.context as? Activity` cast, which fails whenever the
    // context is a ContextWrapper. Neither failure logs or crashes - the status bar just keeps
    // showing, which is exactly the reported bug. ImmersiveSystemBars takes the controller
    // from the window's decor view, resolves the Activity through the ContextWrapper chain,
    // and re-hides on window focus gain (so a dismissed sheet can't bring the bar back).
    ImmersiveSystemBars(hideNavigationBar = false)

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val aspectRatio = screenWidthDp.toFloat() / screenHeightDp.toFloat()
    // 【V8.8】Moto Razr 折叠机外屏：方形比例（razr50: 413x409≈1.01, razr50U: 488x414≈1.18），
    // 区别于普通手机竖屏(~0.5)/横屏(>1.6)/内屏展开(≈2.4)。此形态需走紧凑布局。
    // [V8.43] DEBUG-only override: reproduce the Razr outer-screen layout on any
    // test device with  adb shell settings put global sdw_force_razr_outer 1
    val forceRazrOuter = remember {
        BuildConfig.DEBUG && Settings.Global.getInt(
            context.contentResolver, "sdw_force_razr_outer", 0) == 1
    }
    val isRazrOuter = forceRazrOuter || (
            Build.MANUFACTURER.lowercase().contains("motorola") &&
            Build.MODEL.lowercase().contains("razr") &&
            screenWidthDp >= 360 && aspectRatio in 0.85f..1.35f)
    val isCompact = screenWidthDp < 400 || isRazrOuter
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFoldable = !isRazrOuter && screenWidthDp >= 420 && aspectRatio in 0.7f..1.4f

    val artSize = if (isRazrOuter) {
        // 方形外屏：封面取宽度的 42%，上限 170dp，保证底部控制条/进度/操作全可见
        (screenWidthDp * 0.42f).dp.coerceAtMost(170.dp)
    } else if (isFoldable && isLandscape) (screenHeightDp * 0.68f).dp.coerceAtMost(260.dp)
                  else if (isLandscape) (screenHeightDp * 0.55f).dp.coerceAtMost(220.dp)
                  else (screenWidthDp * 0.55f).dp.coerceAtMost(240.dp)

    // （screenOn / uiResumed / uiActive 已在函数顶部统一声明）

    // 封面旋转：播放+亮屏时按帧推进，暂停/熄屏时冻结（连续无跳变）
    //
    // 【V8.177】旧实现是 LaunchedEffect(isPlaying, screenOn) 里 while 循环调
    // rotation.animateTo(+360, tween(30000))。致命点在 key：isPlaying / screenOn
    // 只要抖一下（或组合短暂离屏），挂起的 animateTo 就被取消，抛出的
    // CancellationException 会直接结束整个协程 —— 角度从此不再推进，画面上只剩
    // "呼吸"缩放在一鼓一鼓地动。真机实测：1.5s 内封面圆环极坐标相关 0.998、位移 0°，
    // 也就是完全没转 —— 用户看到的"一直在抖、不旋转"就是它。
    // 现在改成按帧积分推进：没有可取消的长动画，状态抖动打断不了，暂停即从原角度冻住。
    val rotation = remember { Animatable(0f) }
    // 【V8.492 转场减负】转场（飞入/飞出）期间冻结封面旋转：共享元素封面的 graphics-layer
    // 变换优化要求封面在飞行中保持「稳定」、不要每帧重组。否则 rotation.snapTo 每帧改
    // rotation.value → effectiveRotation 每帧变 → 整个 PlayerScreen + 四个 PlayerCoverArt
    // 每帧重组 → 共享封面节点每帧重排重绘，直接抵消共享转场的层变换加速（飞入/飞出都卡，
    // 和 VU 无关）。封面正在飞，暂停旋转几百 ms 用户无感。
    val rotateNow by rememberUpdatedState(isPlaying && screenOn)
    val coverInTransition by rememberUpdatedState(sharedCoverState?.isAnimating == true)
    LaunchedEffect(Unit) {
        var lastT = 0L
        while (true) {
            if (!rotateNow || coverInTransition) {
                // 暂停/熄屏/转场：不跑帧循环（省电 + 让出主线程给飞行动画）；
                // 醒来/转场结束清掉时间基准，避免一次补上一大截角度
                lastT = 0L
                kotlinx.coroutines.delay(120)
                continue
            }
            // withFrameMillis 的 lambda 不是 suspend（只有 withFrameMillis 自己是），
            // 所以 snapTo 必须写在 lambda 外面：只是拿这一帧的时间戳。
            val t = withFrameMillis { it }
            if (lastT == 0L) lastT = t
            val dt = (t - lastT).coerceIn(0L, 100L) / 1000f
            lastT = t
            val v = rotation.value + dt * COVER_ROT_DPS
            rotation.snapTo(if (v >= 360f) v - 360f else v)
        }
    }

    // EQ status poll
    var eqEnabled by remember { mutableStateOf(false) }
    var eqPresetName by remember { mutableStateOf<String?>(null) }
    var msebActive by remember { mutableStateOf(false) }
    // 【V8.19】DTS 环绕（M/S 声场）独立快捷开关状态
    var dtsOn by remember { mutableStateOf(com.sdw.music.player.core.audio.dsp.MsebCalculator.isDtsEnabled(context)) }
    var vuSessionId by remember { mutableIntStateOf(audioSessionId) }

    /** 【V8.19】DTS 环绕快捷开关：开→应用声场/结像（无滑块设置时用内置默认环绕），关→旁路 */
    fun toggleDts() {
        val ctx = context
        val newOn = !com.sdw.music.player.core.audio.dsp.MsebCalculator.isDtsEnabled(ctx)
        com.sdw.music.player.core.audio.dsp.MsebCalculator.setDtsEnabled(ctx, newOn)
        dtsOn = newOn
        val svc = com.sdw.music.player.core.audio.playback.MusicService.instance
        if (newOn) {
            val (ss, img) = com.sdw.music.player.core.audio.dsp.MsebCalculator.dtsStageParams(ctx)
            svc?.applyMsStage(ss, img)
        } else {
            svc?.resetMsStage()
        }
    }

    // 【V8.177】原声直通：与设置页共用同一把 key（settings/direct_passthrough），
    // 两边改的是同一个开关，不会各说各话。
    val settingsPrefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var directPassthrough by remember { mutableStateOf(settingsPrefs.getBoolean("direct_passthrough", false)) }
    /** 【V8.177】原声直通一键切换：写 pref + 立刻下发到 native（限幅/softClip 一起旁路） */
    fun toggleDirectPassthrough() {
        val newOn = !directPassthrough
        settingsPrefs.edit().putBoolean("direct_passthrough", newOn).apply()
        com.sdw.music.player.core.audio.playback.MusicService.instance?.applyDirectPassthrough(newOn)
        directPassthrough = newOn
    }

    // 【V8.177】自动增益（AGC）：native 默认关，与设置页共用 settings/agc_enabled
    var agcOn by remember { mutableStateOf(settingsPrefs.getBoolean("agc_enabled", false)) }
    /** 【V8.177】AGC 一键切换：写 pref + 立刻下发到 native */
    fun toggleAgc() {
        val newOn = !agcOn
        settingsPrefs.edit().putBoolean("agc_enabled", newOn).apply()
        com.sdw.music.player.core.audio.playback.MusicService.instance?.applyAgc(newOn)
        agcOn = newOn
    }

    // 【V8.274】三行歌词：存在 settings/player_inline_lyrics（与 DTS / AGC 同一份 prefs），默认开。
    var inlineLyrics by remember { mutableStateOf(settingsPrefs.getBoolean("player_inline_lyrics", true)) }
    /** 【V8.274】三行歌词一键切换：写 pref（纯 UI，无需下发 native） */
    fun toggleInlineLyrics() {
        val newOn = !inlineLyrics
        settingsPrefs.edit().putBoolean("player_inline_lyrics", newOn).apply()
        inlineLyrics = newOn
    }

    val vuPrefs = remember { context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE) }
    var vuStyleIdx by remember { mutableIntStateOf(vuPrefs.getInt("vu_meter_style", 1).coerceIn(0, VuMeterStyle.entries.lastIndex)) }
    // 【V8.439】进度条上方显示什么：VU 表 / 整曲波形 / 两者 / 都不显示。
    // 波形（FullSongWaveform）此前只存在于已下线的 DJ 独立页，没有任何调用点，
    // 所以"看不见横线"不是渲染问题，是入口没了。这里给它一个常驻入口。
    var visualMode by remember {
        mutableStateOf(PlayerVisualMode.read(vuPrefs))
    }
    LaunchedEffect(Unit) {
        // 【v8.165 省电】EQ 状态轮询（500ms）在熄屏/后台没有意义，跳过
        // 【V8.227】uiActive → uiActiveState：修 stale 捕获（启动于熄屏时永不恢复的隐患）
        while (true) {
            if (!uiActiveState) {
                kotlinx.coroutines.delay(2000)
                continue
            }
            try {
                // Native DSP EQ state (AAudio Direct, no ExoPlayer)
                val svc = com.sdw.music.player.core.audio.playback.MusicService.instance
                // 【V7.200】MSEB active → show real-time tone summary instead of fixed "MSEB"
                msebActive = com.sdw.music.player.core.audio.dsp.MsebCalculator.isEnabled(context)
                dtsOn = com.sdw.music.player.core.audio.dsp.MsebCalculator.isDtsEnabled(context)
                // 【V8.177】在设置页改过直通后回到播放页，菜单状态要跟着变（同一个 pref）
                directPassthrough = settingsPrefs.getBoolean("direct_passthrough", false)
                agcOn = settingsPrefs.getBoolean("agc_enabled", false)
                inlineLyrics = settingsPrefs.getBoolean("player_inline_lyrics", true)
                eqEnabled = svc?.isDspEqEnabled() == true || EqualizerManager.isEnabled() || msebActive
                eqPresetName = when {
                    msebActive -> {
                        // 【V8.20】优先显示应用中的预设名（Vocal Sweet / Bass Monster / 自定义…）
                        val pn = com.sdw.music.player.core.audio.dsp.MsebCalculator.getPresetName(context)
                        if (pn.isNotEmpty()) pn
                        else {
                            val desc = com.sdw.music.player.core.audio.dsp.MsebCalculator.describe(
                                com.sdw.music.player.core.audio.dsp.MsebCalculator.load(context))
                            // 【V8.184】胶囊宽度有限，描述最多带 2 个特征词（describe 本身已限制 3 个）
                            if (desc.startsWith("Flat") || desc == "Light touch") "MSEB"
                            else "MSEB · " + desc.split(" · ").take(2).joinToString(" · ")
                        }
                    }
                    eqEnabled -> EqualizerManager.getCurrentPresetName()
                    else -> null
                }
                } catch (_: Exception) { eqPresetName = null }
            kotlinx.coroutines.delay(500)
        }
    }

    // Dismiss drag (for embedded mode)
    var _dismissDragAmount by remember { mutableStateOf(0f) }
    val portraitModifier = if (onDismiss != null) {
        Modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (_dismissDragAmount > 300f) onDismiss.invoke()
                        _dismissDragAmount = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0) _dismissDragAmount += dragAmount
                        else _dismissDragAmount = maxOf(0f, _dismissDragAmount + dragAmount)
                    }
                )
            }
            .graphicsLayer {
                // 跟手：下拉时内容随手下移 + 逐渐透明，松手回弹或超阈值关闭
                val progress = (_dismissDragAmount / 400f).coerceIn(0f, 1f)
                translationY = _dismissDragAmount * 0.55f
                alpha = 1f - progress * 0.45f
            }
    } else Modifier

    // 【V8.207】bulk 取值缓冲：整个生命周期复用一块，逐帧 readBandLevels 不再每次分配。
    val levelBuf = remember { FloatArray(25) }
    val bandLevelsState = remember { mutableStateOf(BandLevels()) }
    LaunchedEffect(Unit) {
        val emaSub = mutableStateOf(0f); val emaBass = mutableStateOf(0f)
        val emaMid = mutableStateOf(0f); val emaHigh = mutableStateOf(0f)
        // 【V8.486 省电】装饰相位发布节流：跟帧环里 60Hz 推频段给 VU（保平滑），
        // 但 PlayerUiTicker.frame 只在 ≥INTERVAL_MS 时推一次，避免整页装饰 60fps 重组。
        var lastTickerPublishMs = 0L
        MusicService.instance?.setFftCallback { fft ->
            val n = fft.size / 2 // pairs of (real, imag)
            if (n < 8) return@setFftCallback
            // Split FFT bins into 4 bands
            var s = 0f; var b = 0f; var m = 0f; var h = 0f
            var cs = 0; var cb = 0; var cm = 0; var ch = 0
            val subEnd = (n * 0.08f).toInt().coerceAtLeast(1)
            val bassEnd = (n * 0.20f).toInt().coerceAtLeast(2)
            val midEnd = (n * 0.55f).toInt().coerceAtLeast(4)
            for (i in 0 until n step 2) {
                val re = fft[i].toFloat() / 128f
                val im = if (i + 1 < fft.size) fft[i + 1].toFloat() / 128f else 0f
                val mag = kotlin.math.sqrt(re * re + im * im)
                if (i < subEnd) { s += mag; cs++ }
                else if (i < bassEnd) { b += mag; cb++ }
                else if (i < midEnd) { m += mag; cm++ }
                else { h += mag; ch++ }
            }
            val sv = (s / maxOf(1, cs)).coerceIn(0f, 1f)
            val bv = (b / maxOf(1, cb)).coerceIn(0f, 1f)
            val mv = (m / maxOf(1, cm)).coerceIn(0f, 1f)
            val hv = (h / maxOf(1, ch)).coerceIn(0f, 1f)
            emaSub.value = emaSub.value * 0.85f + sv * 0.15f
            emaBass.value = emaBass.value * 0.85f + bv * 0.15f
            emaMid.value = emaMid.value * 0.85f + mv * 0.15f
            emaHigh.value = emaHigh.value * 0.85f + hv * 0.15f
            // bandLevelsState updated exclusively by Oboe poll loop below
        }
        // 【V8.428】玩家页周期驱动。
        // crossfadeInfo + 装饰相位走**统一 ticker**（PlayerUiTicker，100ms 一帧）；
        // **VU 表的频段数据按用户要求恢复原样**：播放中 60Hz（原来的 withFrameMillis 行为），
        // 用户反馈"降到 10Hz 后 VU 表动画变卡"，平滑度优先于省电。
        // 与原版唯一的差别：原版**暂停时也在 60Hz 空转**（只看 uiActiveState），
        // 现在暂停/熄屏降到 100ms / 1s，那部分空转是真浪费。
        fun publishBands() {
            try {
                val oboe = MusicService.instance?.getOboePlayer()
                if (oboe != null) {
                    // 【V8.207】bulk 接口：一次 JNI 填完 25 个 float，不额外分配。
                    val b = levelBuf
                    if (oboe.readBandLevels(b)) {
                        bandLevelsState.value = BandLevels(
                            sub = b[16], bass = b[17], mid = b[18], high = b[19], rms = b[20],
                            bandsL = floatArrayOf(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]),
                            bandsR = floatArrayOf(b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])
                        )
                    }
                } else if (emaSub.value > 0.0001f || emaBass.value > 0.0001f ||
                           emaMid.value > 0.0001f || emaHigh.value > 0.0001f) {
                    // ExoPlayer mode: use FFT callback EMA values (Visualizer API)
                    val rms = emaSub.value * 0.3f + emaBass.value * 0.4f + emaMid.value * 0.2f + emaHigh.value * 0.1f
                    bandLevelsState.value = BandLevels(
                        sub = emaSub.value, bass = emaBass.value,
                        mid = emaMid.value, high = emaHigh.value, rms = rms
                    )
                }
            } catch (_: Exception) { }
        }

        var vuFreezeStartMs = 0L
        while (isActive) {
            crossfadeInfo = MusicService.instance?.crossfadeUiInfo
                ?: MusicService.CrossfadeUiInfo()
            // 【V8.490】转场（进/出播放页的共享元素动画）进行中：暂停 60fps 跟帧重活，
            // 把主线程让给转场本身。否则 VU 环每帧抢 Choreographer 回调，转场掉帧卡顿。
            // 【自恢复兜底】isAnimating 偶尔复位不及时（SharedCoverOverlay 的 reset 在动画被打断 /
            // 拖拽取消 / 部分导航路径不一定触发），用超时强制恢复，避免 VU 被永久冻结。
            val animating = sharedCoverState?.isAnimating == true
            if (animating) {
                if (vuFreezeStartMs == 0L) vuFreezeStartMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - vuFreezeStartMs < 1000L) {
                    delay(16)
                    continue
                }
                // 超时：即便 isAnimating 没复位也强制恢复 VU，保底不卡死。
            } else {
                vuFreezeStartMs = 0L
            }
            val playing = isPlayingState
            if (uiActiveState && playing) {
                // VU 跟帧走：60Hz 推频段（仅 VU Canvas 读，红绘不重排，保平滑）；
                // 装饰相位（PlayerUiTicker.frame）压到 10Hz，避免整页 60fps 重组——这是亮屏播放电耗主因。
                publishBands()
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastTickerPublishMs >= PlayerUiTicker.INTERVAL_MS) {
                    PlayerUiTicker.publish(nowMs)
                    lastTickerPublishMs = nowMs
                }
                withFrameMillis { }
                continue
            }
            if (uiActiveState) publishBands()
            PlayerUiTicker.publish(System.currentTimeMillis())
            delay(if (uiActiveState) PlayerUiTicker.INTERVAL_MS else 1000L)
        }
    }
    DisposableEffect(Unit) {
        onDispose { MusicService.instance?.setFftCallback(null) }
    }

    // 【V8.207】切歌那一两帧里 position 还是 A 轨尾部、duration 已经是 B 轨的，
    // 比值会 >1 —— 进度条会先满格再归零，看着就是「闪一下」。这里直接夹住。
    val progressFraction = if (durationMs > 0)
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // Band-reactive Aurora — 4 band groups × 3 blobs each
    val baseHue = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (accentColor.alpha * 255).toInt(), (accentColor.red * 255).toInt(),
                (accentColor.green * 255).toInt(), (accentColor.blue * 255).toInt()
            ), hsv
        )
        hsv[0]
    }
    val seedHues = remember(baseHue) {
        listOf(baseHue, (baseHue + 120f) % 360f, (baseHue + 240f) % 360f)
    }

    data class BandBlob(
        val hueOff: Float, val period: Int, val scale: Float,
        val baseX: Float, val baseY: Float, val baseAlpha: Float,
        val sat: Float, val bright: Float, val phaseOff: Float,
        val bandGroup: Int // 0=sub, 1=bass, 2=mid, 3=high
    )

    val bandReactiveBlobs = remember {
        listOf(
            BandBlob( 4f,   19000, 0.82f, 0.08f, 0.08f, 0.22f, 0.62f, 0.72f, 0.0f, 0),
            BandBlob(-3f,   23000, 0.64f, 0.88f, 0.15f, 0.24f, 0.58f, 0.76f, 1.5f, 0),
            BandBlob( 7f,   27000, 0.78f, 0.22f, 0.84f, 0.20f, 0.68f, 0.64f, 0.8f, 0),
            BandBlob(-9f,   29000, 0.56f, 0.92f, 0.78f, 0.20f, 0.60f, 0.70f, 2.3f, 1),
            BandBlob(11f,   31000, 0.70f, 0.12f, 0.92f, 0.22f, 0.65f, 0.74f, 1.1f, 1),
            BandBlob(-5f,   34000, 0.60f, 0.74f, 0.24f, 0.24f, 0.62f, 0.68f, 0.4f, 1),
            BandBlob(14f,   37000, 0.74f, 0.96f, 0.12f, 0.18f, 0.70f, 0.62f, 1.9f, 2),
            BandBlob(-8f,   41000, 0.54f, 0.36f, 0.68f, 0.20f, 0.58f, 0.78f, 0.6f, 2),
            BandBlob( 2f,   43000, 0.68f, 0.55f, 0.15f, 0.18f, 0.66f, 0.66f, 2.7f, 2),
            BandBlob(-12f,  47000, 0.58f, 0.05f, 0.58f, 0.22f, 0.64f, 0.72f, 0.2f, 3),
            BandBlob( 6f,   49000, 0.72f, 0.78f, 0.88f, 0.24f, 0.68f, 0.70f, 1.7f, 3),
            BandBlob(-15f,  53000, 0.62f, 0.32f, 0.42f, 0.20f, 0.60f, 0.76f, 3.1f, 3),
        )
    }

    val ease = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

    // Edge light preference
    val edgeLightPref = LocalContext.current.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
    val edgeLightEnabled = edgeLightPref.getBoolean("moto_edge_light", true)
    // Cover color background — blurred album art as player backdrop
    val coverColorBgEnabled = edgeLightPref.getBoolean("cover_color_bg", true)
    // [V8.71] Immersive player: square non-rotating cover + hide the three capsules
    // (MSEB / Bluetooth+output / DTS). Toggled in Settings.
val immersivePlayer = edgeLightPref.getBoolean("immersive_player", false)
    // [V8.72] fine-grained trim switches (only meaningful when immersive is on)
    val hideSongInfo = immersivePlayer && edgeLightPref.getBoolean("hide_song_info", false)
    val hideSpec = immersivePlayer && edgeLightPref.getBoolean("hide_spec", false)
    val hideBottom = immersivePlayer && edgeLightPref.getBoolean("hide_bottom", false)
    // 【自动隐藏底部四键】空闲 4.5 秒淡出，任何交互（走带键/拖动进度/点封面/直接点这块区域）唤回。
    // 沉浸模式里 hideBottom 已经把整排拿掉了，两者叠加没有意义，所以这里要排除掉。
    val bottomAutoHide = rememberBottomAutoHide(enabled = !hideBottom && edgeLightPref.getBoolean("auto_hide_bottom", true))
    // Freeze the cover spin in immersive mode (0 deg = original square artwork).
    // 【V8.177】角度必须在这里读成 Float 再往下传。试过把 Animatable 整个传下去、
    // 在 PlayerCoverArt 里读 rotation.value（想缩小重组范围）—— 结果封面直接停转：
    // 参数不再变化，Compose 会把那个组件整块跳过，角度根本读不到（真机实测 90°/s
    // 转速下 2 秒只转了 6°）。所以保留 Float 透传，重组范围换不来就别换。
    val effectiveRotation = if (immersivePlayer) 0f else rotation.value


    // Per-band beat flash for pulse effect
    data class BandBeat(val flash: Float = 0f, val decay: Float = 0f)
    var subBeat by remember { mutableStateOf(BandBeat()) }
    var bassBeat by remember { mutableStateOf(BandBeat()) }
    var midBeat by remember { mutableStateOf(BandBeat()) }
    var highBeat by remember { mutableStateOf(BandBeat()) }

    // 播放器页固定深色配色：无论全局切浅色还是深色，播放器内部始终走深色（含文字）
    MaterialTheme(colorScheme = SDWDarkColorScheme) {
    // 【V9.02 防误触】返回（出播放页）转场期间播放页仍在顶层逐渐淡出，此刻点击会打在播放页控件
    // （如中央播放/暂停键）上造成误触发。isAnimating 在转场期间为真，故在此期间吞掉播放页所有指针
    // 事件；转场结束播放页被销毁后列表立即正常可点，无残留副作用。
    val coverAnimating = sharedCoverState?.isAnimating == true
    val blockInputDuringTransition = if (coverAnimating) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
        }
    } else Modifier
    Box(modifier = Modifier.fillMaxSize().then(blockInputDuringTransition)) {
        // Cover color blurred background
        if (coverColorBgEnabled) {
            // 【V8.178】直接用外层门控后的 coverUri（原来这里局部重定义了一个没过
            // hasRealCover 判据、也不走预热的遮蔽变量 —— 死链和闪屏都从这来的）。
            // 【V8.142】背景与音频同相的单层过渡：交叉淡化期间背景目标切到 B 轨封面，
            // 由同一个 Crossfade 平滑 A→B（时长=交叉淡化时长）。原实现是「A 层 600ms Crossfade
            // + B 的 0.65 alpha 叠加层」两层合成，交接瞬间叠加层卸载会丢掉那 0.65 的亮度
            // （合成从约 1.3 掉到 0.65）→ 就是看到的「闪一下」。单层后不存在叠加，
            // 交接连续无跳变；同时提前到 ACTIVE 就开始过渡，画面不再滞后于音频。
            val bgTargetUri = if (xfadeOverlayVisible && !crossfadeNextArt.isNullOrBlank())
                crossfadeNextArt else coverUri
            // 【V8.178】背景也走预热门控：bg 尺寸的图解码就绪后才切 Crossfade，
            // incoming 层不再有空窗帧。
            var bgShownUri by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(bgTargetUri) {
                val next = bgTargetUri?.takeIf { it.isNotBlank() }
                if (next != null) preheatCoverImages(next)
                bgShownUri = next
            }
            // 【V8.490·自恢复】转场进行中冻结全屏模糊 + 墨滴（让 GPU/主线程给飞行动画），
            // 但 isAnimating 偶尔复位不及时（见 VU 环注释），故用超时兜底：最多冻 1000ms
            // 强制恢复，避免背景永久无模糊 / 墨滴永久消失。
            var blurFrozen by remember { mutableStateOf(false) }
            LaunchedEffect(sharedCoverState?.isAnimating) {
                if (sharedCoverState?.isAnimating == true) {
                    blurFrozen = true
                    delay(1000L)
                    blurFrozen = false
                } else {
                    blurFrozen = false
                }
            }
            if (bgShownUri?.isNotBlank() == true) {
                // 封面模糊背景：小半径模糊保留图案轮廓，高透明度让颜色充分透出
                // 【V8.5】整体过渡：uri 变化时 Crossfade 600ms 淡入淡出，避免背景瞬切
                // 【V8.490】转场（进/出播放页）期间把模糊半径降到 0：全屏 blur 是 GPU 大头，
                // 飞入/飞出动画每帧都要重跑一遍全屏模糊 shader，GPU 被占满导致转场掉帧
                // （跟 VU 表无关，关了 VU 也卡）。转场结束再恢复 40.dp，边缘仅一次性重模糊。
                // 40.dp 比原 28.dp 更糊，减少封面图案透出、让歌词更清晰（见下方中心压暗层）。
                // 【V8.492 转场减负】转场期间直接不挂 blur 层：blur(0.dp) 仍会强制全屏离屏
                // 渲染（每帧分配 offscreen layer + 跑一遍 blur shader），和共享元素 overlay
                // 抢合成带宽 → 转场掉帧。blurFrozen 时退化成「裸」Modifier（只留 scale），
                // 飞完再挂回 40.dp，边缘一次性重模糊用户无感。
                val bgBoxModifier = if (blurFrozen) {
                    Modifier.fillMaxSize().scale(1.35f)
                } else {
                    Modifier.fillMaxSize().scale(1.35f).blur(40.dp)
                }
                Box(
                    modifier = bgBoxModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = bgShownUri,
                        animationSpec = tween(
                            if (xfadeOverlayVisible) crossfadeInfo.durationMs.coerceIn(1000, 15000) else 600,
                            easing = FastOutSlowInEasing
                        ),
                        label = "coverBgFade"
                    ) { uri ->
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(context)
                                    .data(uri)
                                    .size(bgDecodePx)
                                    .allowHardware(false)
                                    .crossfade(false)
                                    .build()
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.65f
                        )
                    }
                    // 【V8.142】原先这里的第二层 B 叠加层已删除（见上方 bgTargetUri 注释）：
                    // 单层 Crossfade 即可完成与音频同相的 A→B 过渡，且交接无亮度跳变。
                }
            // 氛围渐变：上下柔和压暗（中间透出封面，无亮带无光环）
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.60f),
                        0.20f to Color.Black.copy(alpha = 0.30f),
                        0.50f to Color.Black.copy(alpha = 0.22f),
                        0.82f to Color.Black.copy(alpha = 0.35f),
                        1.0f to Color.Black.copy(alpha = 0.60f)
                    )
                )
            }
            // 【V8.490】扰动晕开层：低 alpha 墨滴在模糊背景上缓慢流动（仅 cover_color_bg 开启时叠加）。
            // 转场进行中冻结它——墨滴漂移极慢（26~44s 周期），转场那几百 ms 里重绘完全是浪费，
            // 还会和转场抢主线程/合成带宽，直接跳过。
            if (!blurFrozen) {
                InkBloomOverlay(accentColor = accentColor)
            }
        }
        // Edge light — 只保留方案 B（脉冲光带），随机呼吸效果
        // 【V8.492 转场减负】转场（飞入/飞出）期间跳过：脉冲光带用 infiniteRepeatable 每帧重组，
        // 飞行那几百 ms 里和共享元素抢主线程/合成带宽 → 掉帧。封面在飞，省掉这圈光带无感。
        if (!isFoldable && edgeLightEnabled && sharedCoverState?.isAnimating != true) {
            EdgePulseBand(
                coverColors = vuCoverColors,
                accentColor = accentColor
            )
        }

        // 【V8.182 过渡可视化】DJ 交叉淡化进行中：进度 + A/B 两轨电平，过渡结束自动收起
        if (crossfadeInfo.state == com.sdw.music.player.core.audio.playback.MusicService.XfadeUiState.ACTIVE &&
            crossfadeInfo.xfProgress > 0f
        ) {
            XfadeVisualizer(
                progress = crossfadeInfo.xfProgress,
                rmsA = crossfadeInfo.rmsA,
                rmsB = crossfadeInfo.rmsB,
                accentColor = accentColor
            )
        }

        // Vignette
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val maxR = kotlin.math.sqrt(cx * cx + cy * cy)
            drawCircle(
                Brush.radialGradient(
                    0.0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.10f),
                    center = Offset(cx, cy), radius = maxR * 1.2f
                ),
                radius = maxR * 1.2f, center = Offset(cx, cy)
            )
        }

        // Edge light removed — replaced by VU Meter

        // Layout switch
        // [V8.43] Razr outer screen (488x414dp): isFoldable is forced false for
        // isRazrOuter, so this probe used to fall through to the two-column
        // LandscapeLayout (tablet split view) -> squeezed, unreadable layout.
        // Route it to the phone-style PortraitLayout, which already carries the
        // V8.8 outer-screen compact rules.
        if (isRazrOuter) {
            PortraitLayout(
                isAnimating = sharedCoverState?.isAnimating == true,
                wordByWord = wordByWordOn,
                immersive = immersivePlayer,
                hideSongInfo = hideSongInfo,
                hideSpec = hideSpec,
                hideBottom = hideBottom,
                coverUri = coverUri,
                state = state,
                vuCoverColors = vuCoverColors,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = effectiveRotation,
                artSize = artSize,
                isCompact = isCompact,
                isRazrOuter = isRazrOuter,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                visualMode = visualMode,
                bandLevelsState = bandLevelsState,
                vuStyleIdx = vuStyleIdx,
                currentLyricLine = currentLyricLine,
                lyricsLines = lyricsLines,
                currentLyricIdx = currentLyricIdx,
                showMenu = showMenu,
                portraitModifier = portraitModifier,
                fadeOverlayUri = if (xfadeOverlayVisible && !xfadeOverlayStale) crossfadeNextArt else null,
                fadeOverlayDurationMs = crossfadeInfo.durationMs.coerceIn(1000, 15000),
                fadeOverlayProgress = xfadeOverlayProgress,
                fadeOverlayActive = xfadeActive,
                fadeOverlayStartMs = xfadeStart,
                instantCoverSwitch = coverSwitchInstant,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onAddToPlaylist = { if (currentSong != null) showAddToPlaylist = true },
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                djEnabled = djEnabled.value,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onSleepTimer = { showSleepDialog = true },
                onNavigateToQueue = { showQueue = true },
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                dtsOn = dtsOn,
                onToggleDts = ::toggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = ::toggleDirectPassthrough,
                agcEnabled = agcOn,
                onToggleAgc = ::toggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = ::toggleInlineLyrics,
                onNavigateToMseb = onNavigateToMseb,
                songSpec = songSpec,
                bottomAutoHide = bottomAutoHide,
                onLyricoExternalEditReturned = onLyricoExternalEditReturned
            )
        } else if (isLandscape && isFoldable) {
            FoldableLayout(
                wordByWord = wordByWordOn,
                immersive = immersivePlayer,
                hideSongInfo = hideSongInfo,
                hideSpec = hideSpec,
                hideBottom = hideBottom,
                coverUri = coverUri,
                state = state,
                sharedCoverState = sharedCoverState,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = effectiveRotation,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                lyricsLines = lyricsLines,
                showMenu = showMenu,
                fadeOverlayUri = if (xfadeOverlayVisible && !xfadeOverlayStale) crossfadeNextArt else null,
                fadeOverlayDurationMs = crossfadeInfo.durationMs.coerceIn(1000, 15000),
                fadeOverlayProgress = xfadeOverlayProgress,
                fadeOverlayActive = xfadeActive,
                fadeOverlayStartMs = xfadeStart,
                instantCoverSwitch = coverSwitchInstant,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onAddToPlaylist = { if (currentSong != null) showAddToPlaylist = true },
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                djEnabled = djEnabled.value,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onSleepTimer = { showSleepDialog = true },
                onNavigateToQueue = { showQueue = true },
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                dtsOn = dtsOn,
                onToggleDts = ::toggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = ::toggleDirectPassthrough,
                agcEnabled = agcOn,
                onToggleAgc = ::toggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = ::toggleInlineLyrics,
                onNavigateToMseb = onNavigateToMseb,
                songSpec = songSpec,
                bottomAutoHide = bottomAutoHide,
                onLyricoExternalEditReturned = onLyricoExternalEditReturned
            )
        } else if (isLandscape) {
            LandscapeLayout(
                isAnimating = sharedCoverState?.isAnimating == true,
                wordByWord = wordByWordOn,
                lyricsLines = lyricsLines,
                currentLyricIdx = currentLyricIdx,
                immersive = immersivePlayer,
                hideSongInfo = hideSongInfo,
                hideSpec = hideSpec,
                hideBottom = hideBottom,
                coverUri = coverUri,
                state = state,
                vuCoverColors = vuCoverColors,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = effectiveRotation,
                artSize = artSize,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                visualMode = visualMode,
                bandLevelsState = bandLevelsState,
                vuStyleIdx = vuStyleIdx,
                currentLyricLine = currentLyricLine,
                showMenu = showMenu,
                fadeOverlayUri = if (xfadeOverlayVisible && !xfadeOverlayStale) crossfadeNextArt else null,
                fadeOverlayDurationMs = crossfadeInfo.durationMs.coerceIn(1000, 15000),
                fadeOverlayProgress = xfadeOverlayProgress,
                fadeOverlayActive = xfadeActive,
                fadeOverlayStartMs = xfadeStart,
                instantCoverSwitch = coverSwitchInstant,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onAddToPlaylist = { if (currentSong != null) showAddToPlaylist = true },
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                djEnabled = djEnabled.value,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onSleepTimer = { showSleepDialog = true },
                onNavigateToQueue = { showQueue = true },
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                dtsOn = dtsOn,
                onToggleDts = ::toggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = ::toggleDirectPassthrough,
                agcEnabled = agcOn,
                onToggleAgc = ::toggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = ::toggleInlineLyrics,
                onNavigateToMseb = onNavigateToMseb,
                songSpec = songSpec,
                bottomAutoHide = bottomAutoHide,
                onLyricoExternalEditReturned = onLyricoExternalEditReturned
            )
        } else {
            PortraitLayout(
                isAnimating = sharedCoverState?.isAnimating == true,
                wordByWord = wordByWordOn,
                immersive = immersivePlayer,
                hideSongInfo = hideSongInfo,
                hideSpec = hideSpec,
                hideBottom = hideBottom,
                coverUri = coverUri,
                state = state,
                vuCoverColors = vuCoverColors,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = effectiveRotation,
                artSize = artSize,
                isCompact = isCompact,
                isRazrOuter = isRazrOuter,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                visualMode = visualMode,
                bandLevelsState = bandLevelsState,
                vuStyleIdx = vuStyleIdx,
                currentLyricLine = currentLyricLine,
                lyricsLines = lyricsLines,
                currentLyricIdx = currentLyricIdx,
                showMenu = showMenu,
                portraitModifier = portraitModifier,
                fadeOverlayUri = if (xfadeOverlayVisible && !xfadeOverlayStale) crossfadeNextArt else null,
                fadeOverlayDurationMs = crossfadeInfo.durationMs.coerceIn(1000, 15000),
                fadeOverlayProgress = xfadeOverlayProgress,
                fadeOverlayActive = xfadeActive,
                fadeOverlayStartMs = xfadeStart,
                instantCoverSwitch = coverSwitchInstant,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onAddToPlaylist = { if (currentSong != null) showAddToPlaylist = true },
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                djEnabled = djEnabled.value,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onSleepTimer = { showSleepDialog = true },
                onNavigateToQueue = { showQueue = true },
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                dtsOn = dtsOn,
                onToggleDts = ::toggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = ::toggleDirectPassthrough,
                agcEnabled = agcOn,
                onToggleAgc = ::toggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = ::toggleInlineLyrics,
                onNavigateToMseb = onNavigateToMseb,
                songSpec = songSpec,
                bottomAutoHide = bottomAutoHide,
                onLyricoExternalEditReturned = onLyricoExternalEditReturned
            )
        }

        // Sleep timer countdown bar
        if (sleepTimerRemaining > 0L) {
            val remainingMin = sleepTimerRemaining / 60000
            val remainingSec = (sleepTimerRemaining % 60000) / 1000
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC_000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "⏳ Sleep in ${remainingMin}:${remainingSec.toString().padStart(2, '0')}  ·  Tap to cancel",
                    color = Color(0xFF_FF8A65),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable {
                        MusicService.instance?.cancelSleepTimer()
                    }
                )
            }
            }
        }

        if (showQueue) {
            // 【V8.250】打开队列时算一次 Automix 预览（接下来怎么接）
            // 【V8.251 修复】面板打开期间每 5s 自动刷新——原来只在重开面板/歌 ID 变化时重算，
            // 交叉淡化路径下 UI 的 currentSongId 更新不可靠，预览永远停在旧的三首。
            val upNext = produceState<List<Pair<Song, String>>>(emptyList(), showQueue, state.currentSongId) {
                value = onPreviewUpNext()
                while (isActive) {
                    delay(5000)
                    value = onPreviewUpNext()
                }
            }.value
            QueueSheet(
                queue = state.queue,
                currentSongId = state.currentSongId,
                accentColor = accentColor,
                onSongClick = onPlayQueueIndex,
                onFlowSort = onReorderQueueByFlow,
                upNext = upNext,
                onDismiss = { showQueue = false }
            )
        }

        // 【V8.7】右上角菜单 -> 添加到歌单
        if (showAddToPlaylist && currentSong != null) {
            AddToPlaylistSheet(
                song = currentSong,
                onDismiss = { showAddToPlaylist = false }
            )
        }

        // Sleep Timer — 【V8.5】统一为 ModalBottomSheet，与队列面板风格一致
        if (showSleepDialog) {
            val sleepSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSleepDialog = false },
                sheetState = sleepSheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onBackground,
                scrimColor = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                // [V8.117] 去掉默认拖拽小短条；独立 Dialog 窗口单独隐藏状态栏。
                dragHandle = null
            ) {
                SheetImmersiveStatusBar()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.title_sleep_timer), modifier = Modifier.padding(vertical = 12.dp))
                    listOf(15, 30, 60).forEach { min ->
                        TextButton(
                            onClick = {
                                MusicService.instance?.setSleepTimer(min)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${min} minutes", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (MusicService.instance?.isSleepTimerActive() == true) {
                        TextButton(
                            onClick = {
                                MusicService.instance?.cancelSleepTimer()
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.player_cancel_timer), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = { showSleepDialog = false }) { Text(stringResource(R.string.action_close)) }
                }
        }

    }
    }
}

// ============================================================================
}
// Layout Composables
// ============================================================================

@Composable
private fun FoldableLayout(coverUri: String?,
    immersive: Boolean = false, 
    hideSongInfo: Boolean = false,
    hideSpec: Boolean = false,
    hideBottom: Boolean = false,
    state: PlayerState,
    sharedCoverState: SharedCoverState?,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    lyricsLines: List<LyricLine>,
    // [V8.135] 播放界面逐字高亮（与全屏歌词页共用 lyric_word_by_word 开关）
    wordByWord: Boolean = false,
    showMenu: Boolean,
    // 【V8.4】Crossfade 封面交叉淡化透传
    fadeOverlayUri: String? = null,
    fadeOverlayDurationMs: Int = 5000,
    // 【V8.139】同相过渡进度 0..1
    fadeOverlayProgress: Float = 0f,
    // 【V8.207】ACTIVE 阶段帧时钟下沉所需的两个量（透传给 PlayerCoverArt）
    fadeOverlayActive: Boolean = false,
    fadeOverlayStartMs: Long = 0L,
    // 【V8.5】crossfade 交接期间换碟瞬时完成
    instantCoverSwitch: Boolean = false,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    // 【V8.357】false = plain player surface: no ⚡ / ⏭ buttons, hard cuts only
    djEnabled: Boolean = false,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onSleepTimer: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    onNavigateToAudioDiagnostic: () -> Unit = {},
    onNavigateToMseb: () -> Unit = {},
    // 【V8.19】DTS 环绕快捷开关
    dtsOn: Boolean = false,
    onToggleDts: () -> Unit = {},
    directPassthrough: Boolean = false,
    onToggleDirectPassthrough: () -> Unit = {},
    agcEnabled: Boolean = false,
    onToggleAgc: () -> Unit = {},
    // 【V8.274】三行歌词（封面下、频谱表上那三行）开关，与设置页共用 settings/player_inline_lyrics
    inlineLyrics: Boolean = true,
    onToggleInlineLyrics: () -> Unit = {},
    // 【V8.21】歌曲规格显示（分段着色）
    songSpec: com.sdw.music.player.core.audio.metadata.SongSpecReader.Spec? = null,
    /** 底部四键自动隐藏控制器（null = 四键常驻）。 */
    bottomAutoHide: BottomAutoHide? = null,
    // 【Lyrico 联动】三点菜单「用 Lyrico 编辑」返回后的回调，透传给 PlayerTopBar
    onLyricoExternalEditReturned: (songId: Long, filePath: String, extras: Bundle?) -> Unit = { _, _, _ -> }
) {
    // [V8.99] Insets parity with PortraitLayout: this page has no Scaffold, so the
    // navigation-bar inset must be consumed here or bottom-anchored controls sit under
    // the 3-button navigation bar. PortraitLayout already did this; Foldable/Landscape
    // were missed, which is exactly the inconsistency this unifies.
    Row(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
    ) {
        // Left: player controls
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(
                showMenu = showMenu,
                onToggleMenu = onToggleMenu,
                onDismissMenu = onDismissMenu,
                onNavigateBack = onNavigateBack,
                onDelete = onDeleteSong,
                currentSongPath = state.currentSongFilePath,
                onLyricoEditReturned = { extras ->
                    onLyricoExternalEditReturned(state.currentSongId, state.currentSongFilePath, extras)
                },
                onAddToPlaylist = onAddToPlaylist,
                onSleepTimer = onSleepTimer,
                onNavigateToQueue = onNavigateToQueue,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                onToggleEqualizer = onToggleEqualizer,
                dtsEnabled = dtsOn,
                onToggleDts = onToggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = onToggleDirectPassthrough,
                agcEnabled = agcEnabled,
                onToggleAgc = onToggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = onToggleInlineLyrics,
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
            )

            // Center: cover + song info
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Cover (fixed position, outside AnimatedContent)
                // [V8.140] 沉浸模式：折叠屏横屏封面由固定 130dp 放大到左栏可用空间的
                // min(宽*0.60, 高*0.62)（上限 300dp）；普通模式保持 130dp 不变。
                BoxWithConstraints {
                  val foldArt = if (immersive)
                      minOf(maxWidth * 0.60f, maxHeight * 0.62f).coerceAtLeast(130.dp).coerceAtMost(300.dp)
                  else 140.dp
                  Box(
                    modifier = Modifier
                        .size(foldArt)
                        .graphicsLayer { alpha = 1f }
                        .clickable(
                            indication = ripple(bounded = false, radius = foldArt / 2),
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNavigateToAlbum(state.currentSongAlbum) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(foldArt).clip(CircleShape).background(accentColor.copy(alpha = 0.08f)))
                    PlayerCoverArt(
                        artUri = coverUri,
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        accentColor = accentColor,
                        isPlaying = isPlaying,
                        rotation = rotation,
                        sizeDp = if (immersive) foldArt else 130.dp,
                        glowSizeDp = if (immersive) foldArt else 140.dp,
                        onCoverPositioned = { offset, size ->
                            sharedCoverState?.fullCoverPosition = CoverPosition(windowOffset = offset, size = size)
                        },
                        onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                        onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                        onSwipePrevious = onPrevious,
                        onSwipeNext = onNext,
                        fadeOverlayUri = fadeOverlayUri,
                        instantCoverSwitch = instantCoverSwitch,
                        fadeOverlayDurationMs = fadeOverlayDurationMs,
                        fadeOverlayProgress = fadeOverlayProgress,
                        fadeOverlayActive = fadeOverlayActive,
                        fadeOverlayStartMs = fadeOverlayStartMs,
                        squareCover = immersive,
                        noRotation = immersive,
                    )
                }
                }

                Spacer(Modifier.height(12.dp))

                // Song info animated on track change
                AnimatedContent(
                                    targetState = state.currentSongId,
                                    // [V8.84] Track change: the title block travels a short
                                    // distance instead of merely cross-fading. Displacement
                                    // only (no size/geometry animation) per approved scope.
                                    transitionSpec = {
                                        (fadeIn(tween(350, easing = FastOutSlowInEasing)) +
                                            slideInVertically(tween(350, easing = FastOutSlowInEasing)) { it / 7 })
                                            .togetherWith(
                                                fadeOut(tween(250, easing = FastOutSlowInEasing)) +
                                                    slideOutVertically(tween(250, easing = FastOutSlowInEasing)) { -it / 7 }
                                            )
                                    }
                                ) { _ ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                                        // [V8.115] 歌名调小调细：titleMedium -> titleSmall + Light，
                                        // 颜色从纯白降到 0.88，去掉"纯白太亮、字体太粗"的观感。
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Light),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                    )
                                    if (!hideSongInfo) {
                                        PlayerSongInfo(
                                            artist = state.currentSongArtist,
                                            format = state.currentSongFormat,
                                            textAccentColor = textAccentColor
                                        )
                                    }
                                }
                                }
                            

            }

            // Bottom: controls
            Spacer(Modifier.height(4.dp))
            DacInfoBar(accentColor, textAccentColor, isPlaying,
                onClick = onNavigateToAudioDiagnostic,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            OutputDeviceBar(accentColor, textAccentColor, isPlaying,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))

            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { bottomAutoHide?.poke(); onSeekTo((durationMs * it).toLong()) },
                // 【V8.184】规格并入进度条下方的时间行，与「时长」同水平、右对齐
                specSlot = { if (!hideSpec) SongSpecLabel(songSpec, accentColor, textAccentColor) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(2.dp))

                // 【V8.19】DTS 环绕快捷开关 — 播放条上方左侧
                // [V8.71] hidden in immersive mode
                if (!immersive) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        PlayerDtsToggle(
                            dtsOn = dtsOn,
                            accentColor = accentColor,
                            textAccentColor = textAccentColor,
                            onClick = onToggleDts
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            if (djEnabled) {
                // 【V8.380】DJ 模式：只有播放 / 暂停 / 下一曲三个键
                DjTransportBar(
                    isPlaying = isPlaying,
                    accentColor = accentColor,
                    onPlay = onPlay,
                    onPause = onPause,
                    // 【V8.362】DJ 模式的切歌键 = DJ 切歌：先走交叉淡化（triggerCrossfadeNow），
                    // 仅当"失败且未排队"（暂停中/无曲目等）才回退普通硬切，保证键永远可用。
                    onNext = djCutOrFallbackNext(onNext),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SDWTransport.Gutter),
                    onInteract = { bottomAutoHide?.poke() }
                )
            } else {
        PlayerControlBar(
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                isPlaying = isPlaying,
                accentColor = accentColor,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlay = onPlay,
                onPause = onPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
                // [SDWTransport] 五键尺寸/铺法统一契约，沉浸与非沉浸完全同一套
                modifier = Modifier.fillMaxWidth().padding(horizontal = SDWTransport.Gutter),
                onInteract = { bottomAutoHide?.poke() }
            )
            }
            Spacer(Modifier.height(4.dp))

            if (!hideBottom) {
                PlayerBottomActions(
                    eqEnabled = eqEnabled,
                    isCurrentSongFavorite = state.isCurrentSongFavorite,
                    onNavigateToLyrics = onNavigateToLyrics,
                    onToggleEqualizer = onToggleEqualizer,
                    onToggleFavorite = onToggleFavorite,
                    onShare = onShare,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    autoHide = bottomAutoHide
                )
            }

            MotorolaWatermark(accentColor = accentColor)
        }

        // Divider
        Box(
            modifier = Modifier.fillMaxHeight().width(0.5.dp)
                .background(accentColor.copy(alpha = 0.15f))
        )

        // Right: full-screen lyrics
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lyrics - ${state.currentSongTitle.ifEmpty { "Lyrics" }}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNavigateToLyrics, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Search, stringResource(R.string.action_search), tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }

            if (lyricsLines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.player_no_lyrics), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                } else {
                LyricViewCompose(
                    lyrics = lyricsLines,
                    positionMs = positionMs,
                    themeColor = accentColor.value.toInt(),
                    onLineClick = { line -> onSeekTo(line.timeMs) },
                    // [V8.135] 折叠屏右栏全屏歌词同样跟随逐字开关
                    wordByWord = wordByWord,
                    isPlaying = isPlaying,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
            }
        }
    }
    }

/**
 * 【V8.486 省电】VU 表包装：把 BandLevels 的 60Hz 读取收敛到 VU 自身作用域，
 * 不让外层整页布局因频段变化而 60fps 重组。频段由播放页统一 ticker 以 60Hz 写入
 * bandLevelsState；这里只在 VU 自己的帧时钟（VuMixer 内部 60fps）驱动的重绘里读最新值，
 * VU 平滑度不受影响。外层只传稳定的 State 引用，频段变化不再触发整页重组。
 */
@Composable
private fun LiveVuMeter(
    bandLevelsState: State<BandLevels>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    style: VuMeterStyle,
    accentColor: Color,
    coverColors: List<Int>,
    /** 转场进行中：让 VU 60fps 帧时钟让位给共享元素转场（见 VuMeter）。 */
    isAnimating: Boolean = false
) {
    val bl = bandLevelsState.value
    VuMeter(
        sub = bl.sub, bass = bl.bass, mid = bl.mid, high = bl.high, rms = bl.rms,
        bandsL = bl.bandsL, bandsR = bl.bandsR,
        isActive = isActive, style = style, accentColor = accentColor,
        coverColors = coverColors, modifier = modifier, isAnimating = isAnimating
    )
}

@Composable
private fun LandscapeLayout(coverUri: String?,
    immersive: Boolean = false, 
    hideSongInfo: Boolean = false,
    hideSpec: Boolean = false,
    hideBottom: Boolean = false,
    state: PlayerState,
    // 【V8.178】交叉淡化期间直接用 B 轨预读调色板（VU 表颜色跟 B 轨走）
    vuCoverColors: List<Int>,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    artSize: androidx.compose.ui.unit.Dp,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    //【V8.439】进度条上方显示什么（VU 表 / 整曲波形 / 两者 / 都不显示）
    visualMode: PlayerVisualMode,
    bandLevelsState: State<BandLevels>,
    vuStyleIdx: Int,
    /** 转场（飞进/飞出）进行中：让 VU 60fps 帧时钟让位给共享元素转场（见 LiveVuMeter）。 */
    isAnimating: Boolean = false,
    currentLyricLine: String?,
    // [V8.135] 播放界面逐字高亮（与全屏歌词页共用 lyric_word_by_word 开关）
    wordByWord: Boolean = false,
    lyricsLines: List<LyricLine> = emptyList(),
    currentLyricIdx: Int = -1,
    showMenu: Boolean,
    // 【V8.4】Crossfade 封面交叉淡化透传
    fadeOverlayUri: String? = null,
    fadeOverlayDurationMs: Int = 5000,
    // 【V8.139】同相过渡进度 0..1
    fadeOverlayProgress: Float = 0f,
    // 【V8.207】ACTIVE 阶段帧时钟下沉所需的两个量（透传给 PlayerCoverArt）
    fadeOverlayActive: Boolean = false,
    fadeOverlayStartMs: Long = 0L,
    // 【V8.5】crossfade 交接期间换碟瞬时完成
    instantCoverSwitch: Boolean = false,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    // 【V8.357】false = plain player surface: no ⚡ / ⏭ buttons, hard cuts only
    djEnabled: Boolean = false,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onSleepTimer: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    onNavigateToAudioDiagnostic: () -> Unit = {},
    onNavigateToMseb: () -> Unit = {},
    // 【V8.19】DTS 环绕快捷开关
    dtsOn: Boolean = false,
    onToggleDts: () -> Unit = {},
    directPassthrough: Boolean = false,
    onToggleDirectPassthrough: () -> Unit = {},
    agcEnabled: Boolean = false,
    onToggleAgc: () -> Unit = {},
    // 【V8.274】三行歌词（封面下、频谱表上那三行）开关，与设置页共用 settings/player_inline_lyrics
    inlineLyrics: Boolean = true,
    onToggleInlineLyrics: () -> Unit = {},
    // 【V8.21】歌曲规格显示（分段着色）
    songSpec: com.sdw.music.player.core.audio.metadata.SongSpecReader.Spec? = null,
    /** 底部四键自动隐藏控制器（null = 四键常驻）。 */
    bottomAutoHide: BottomAutoHide? = null,
    // 【Lyrico 联动】三点菜单「用 Lyrico 编辑」返回后的回调，透传给 PlayerTopBar
    onLyricoExternalEditReturned: (songId: Long, filePath: String, extras: Bundle?) -> Unit = { _, _, _ -> }
) {
    // 【V8.380】DJ 模式不再走独立界面。
    // 之前给 DJ 做了横屏卡片 / 竖屏卡片 / 彩虹频谱 / 整曲波形 / 切片 / BPM 读数，
    // 用户反馈「太复杂」—— DJ 的价值在**引擎自动混音**，界面只需能播、能停、能切下一首。
    // 现在：复用普通播放界面 + 强制沉浸样式（见上方 immersivePlayer），
    // 底部走带换成「播放 / 暂停 / 下一曲」三键（DjTransportBar）。

    // [V8.99] Insets parity with PortraitLayout: this page has no Scaffold, so the
    // navigation-bar inset must be consumed here or bottom-anchored controls sit under
    // the 3-button navigation bar. PortraitLayout already did this; Foldable/Landscape
    // were missed, which is exactly the inconsistency this unifies.
    Row(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
    ) {
        // Left: controls — top bar fixed, content scrollable
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fixed top bar (always visible)
            PlayerTopBar(
                showMenu = showMenu,
                onToggleMenu = onToggleMenu,
                onDismissMenu = onDismissMenu,
                onNavigateBack = onNavigateBack,
                onDelete = onDeleteSong,
                currentSongPath = state.currentSongFilePath,
                onLyricoEditReturned = { extras ->
                    onLyricoExternalEditReturned(state.currentSongId, state.currentSongFilePath, extras)
                },
                onAddToPlaylist = onAddToPlaylist,
                onSleepTimer = onSleepTimer,
                onNavigateToQueue = onNavigateToQueue,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                onToggleEqualizer = onToggleEqualizer,
                dtsEnabled = dtsOn,
                onToggleDts = onToggleDts,
                directPassthrough = directPassthrough,
                onToggleDirectPassthrough = onToggleDirectPassthrough,
                agcEnabled = agcEnabled,
                onToggleAgc = onToggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = onToggleInlineLyrics,
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic
            )

            // 【V8.21 fix】滚动区只放信息（标题/歌词/VU/EQ/DAC/进度/规格/DTS），
            // PlayerControlBar/PlayerBottomActions 移出滚动区固定底部 —— 横屏旋转后控制键不再被推出屏幕
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Center: song info
                Spacer(Modifier.height(12.dp))
                // 【V8.5】切歌时标题/歌手淡入淡出过渡
                AnimatedContent(
                                    targetState = state.currentSongId,
                                    transitionSpec = {
                                        // [V8.84] Displacement on track change instead of a
                                        // pure crossfade (slide out up, in from below).
                                        (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                            slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 7 })
                                            .togetherWith(
                                                fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                                                    slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { -it / 7 }
                                            )
                                    }
                                ) { _ ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                                        // [V8.115] 歌名调小调细（同上）。
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Light),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                    )
                                    if (!hideSongInfo) {
                                        PlayerSongInfo(
                                            artist = state.currentSongArtist,
                                            format = state.currentSongFormat,
                                            textAccentColor = textAccentColor,
                                            onArtistClick = { name -> onNavigateToArtist(name) }
                                        )
                                    }
                                }
                                }
                            

                Spacer(Modifier.height(6.dp))
                // [V8.135] 逐字开启且当前行有效时用逐字高亮，否则保持原单行跑马灯
                if (wordByWord && currentLyricIdx in lyricsLines.indices) {
                    InlineLyricWord(
                        line = lyricsLines[currentLyricIdx],
                        lineEndMs = lyricsLines.getOrNull(currentLyricIdx + 1)?.timeMs ?: Long.MAX_VALUE,
                        positionMs = positionMs,
                        color = textAccentColor,
                        isPlaying = isPlaying,
                        onClick = onNavigateToLyrics
                    )
                } else {
                    PlayerInlineLyric(currentLyricLine, textAccentColor, onClick = onNavigateToLyrics)
                }

                // Controls
                Spacer(Modifier.height(8.dp))
            // VU 表/ 整曲波形 — compact in landscape
            if (visualMode.showVu) {
                LiveVuMeter(bandLevelsState = bandLevelsState, isActive = isPlaying, style = VuMeterStyle.entries[vuStyleIdx.coerceIn(0, VuMeterStyle.entries.lastIndex)], accentColor = accentColor, coverColors = vuCoverColors, isAnimating = isAnimating, modifier = Modifier.padding(horizontal = 8.dp).heightIn(max = 50.dp))
                Spacer(Modifier.height(6.dp))
            }
            if (visualMode.showWave) {
                FullSongWaveform(
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
                    progress = progressFraction,
                    showSliceMarkers = false,
                    showBarLabels = false,
                    // 【V8.439】省电：播放页只等 20 × 600ms = 12s，扫不完就留着单色
                    // （后台 warmDjStructure 会继续填缓存，下次进来直接就有颜色）。
                    pollBudget = 20,
                    onSeekToFraction = { frac ->
                        if (durationMs > 0) onSeekTo((durationMs * frac).toLong())
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
            DacInfoBar(accentColor, textAccentColor, isPlaying,
                onClick = onNavigateToAudioDiagnostic,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            OutputDeviceBar(accentColor, textAccentColor, isPlaying,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))
            } // close scrollable inner Column（信息区：标题/歌词/VU/EQ/DAC条/输出条）

            // 【V8.21 fix】固定底部控制区：进度/规格/DTS/控制键/底部操作 旋转后始终可见
            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { bottomAutoHide?.poke(); onSeekTo((durationMs * it).toLong()) },
                // 【V8.184】规格并入进度条下方的时间行，与「时长」同水平、右对齐
                specSlot = { if (!hideSpec) SongSpecLabel(songSpec, accentColor, textAccentColor) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(2.dp))
            // [V8.71] hidden in immersive mode
            if (!immersive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    PlayerDtsToggle(
                        dtsOn = dtsOn,
                        accentColor = accentColor,
                        textAccentColor = textAccentColor,
                        onClick = onToggleDts
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            if (djEnabled) {
                // 【V8.380】DJ 模式：只有播放 / 暂停 / 下一曲三个键
                DjTransportBar(
                    isPlaying = isPlaying,
                    accentColor = accentColor,
                    onPlay = onPlay,
                    onPause = onPause,
                    // 【V8.362】DJ 模式的切歌键 = DJ 切歌：先走交叉淡化（triggerCrossfadeNow），
                    // 仅当"失败且未排队"（暂停中/无曲目等）才回退普通硬切，保证键永远可用。
                    onNext = djCutOrFallbackNext(onNext),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    onInteract = { bottomAutoHide?.poke() }
                )
            } else {
        PlayerControlBar(
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                isPlaying = isPlaying,
                accentColor = accentColor,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlay = onPlay,
                onPause = onPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
                // [SDWTransport] 五键尺寸/铺法统一契约，沉浸与非沉浸完全同一套
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                onInteract = { bottomAutoHide?.poke() }
            )
            }
            Spacer(Modifier.height(2.dp))

            if (!hideBottom) {
                PlayerBottomActions(
                    eqEnabled = eqEnabled,
                    isCurrentSongFavorite = state.isCurrentSongFavorite,
                    onNavigateToLyrics = onNavigateToLyrics,
                    onToggleEqualizer = onToggleEqualizer,
                    onToggleFavorite = onToggleFavorite,
                    onShare = onShare,
                    modifier = Modifier.fillMaxWidth(),
                    autoHide = bottomAutoHide
                )
            }

            MotorolaWatermark(accentColor = accentColor)
        } // close outer left Column

        // Right: album art — height capped by artSize, centered vertically
        // [V8.140] 沉浸模式：旋转后封面放大到右栏可用空间的 ~96%（解除 artSize 0.55h 上限），
        // 与竖屏沉浸同一规则；普通模式保持原尺寸与居中留白。
        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(if (immersive) 12.dp else 24.dp), contentAlignment = Alignment.Center) {
            BoxWithConstraints {
                val artDp = if (immersive)
                    minOf(maxWidth * 0.96f, maxHeight * 0.96f).coerceAtLeast(120.dp)
                else artSize
                PlayerCoverArt(
                    artUri = coverUri,
                    songTitle = state.currentSongTitle,
                    songArtist = state.currentSongArtist,
                    accentColor = accentColor,
                    isPlaying = isPlaying,
                    rotation = rotation,
                    sizeDp = artDp,
                    glowSizeDp = artDp + if (immersive) 0.dp else 32.dp,
                    onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                    onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                    onSwipePrevious = onPrevious,
                    onSwipeNext = onNext,
                    fadeOverlayUri = fadeOverlayUri,
                    instantCoverSwitch = instantCoverSwitch,
                    fadeOverlayDurationMs = fadeOverlayDurationMs,
                    fadeOverlayProgress = fadeOverlayProgress,
                    fadeOverlayActive = fadeOverlayActive,
                    fadeOverlayStartMs = fadeOverlayStartMs,
                    squareCover = immersive,
                    noRotation = immersive,
                )
            }
            }
        }
    }

@Composable
private fun PortraitLayout(coverUri: String?,
    immersive: Boolean = false, 
    hideSongInfo: Boolean = false,
    hideSpec: Boolean = false,
    hideBottom: Boolean = false,
    state: PlayerState,
    // 【V8.178】交叉淡化期间直接用 B 轨预读调色板（VU 表颜色跟 B 轨走）
    vuCoverColors: List<Int>,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    artSize: androidx.compose.ui.unit.Dp,
    isCompact: Boolean,
    isRazrOuter: Boolean = false,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    //【V8.439】进度条上方显示什么（VU 表 / 整曲波形 / 两者 / 都不显示）
    visualMode: PlayerVisualMode,
    bandLevelsState: State<BandLevels>,
    vuStyleIdx: Int,
    /** 转场（飞进/飞出）进行中：让 VU 60fps 帧时钟让位给共享元素转场（见 LiveVuMeter）。 */
    isAnimating: Boolean = false,
    currentLyricLine: String?,
    lyricsLines: List<LyricLine>,
    currentLyricIdx: Int,
    // [V8.135] 播放界面逐字高亮（与全屏歌词页共用 lyric_word_by_word 开关）
    wordByWord: Boolean = false,
    showMenu: Boolean,
    portraitModifier: Modifier,
    // 【V8.4】Crossfade 封面交叉淡化透传
    fadeOverlayUri: String? = null,
    fadeOverlayDurationMs: Int = 5000,
    // 【V8.139】同相过渡进度 0..1
    fadeOverlayProgress: Float = 0f,
    // 【V8.207】ACTIVE 阶段帧时钟下沉所需的两个量（透传给 PlayerCoverArt）
    fadeOverlayActive: Boolean = false,
    fadeOverlayStartMs: Long = 0L,
    // 【V8.5】crossfade 交接期间换碟瞬时完成
    instantCoverSwitch: Boolean = false,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    // 【V8.357】false = plain player surface: no ⚡ / ⏭ buttons, hard cuts only
    djEnabled: Boolean = false,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onSleepTimer: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    onNavigateToAudioDiagnostic: () -> Unit = {},
    onNavigateToMseb: () -> Unit = {},
    // 【V8.19】DTS 环绕快捷开关
    dtsOn: Boolean = false,
    onToggleDts: () -> Unit = {},
    directPassthrough: Boolean = false,
    onToggleDirectPassthrough: () -> Unit = {},
    agcEnabled: Boolean = false,
    onToggleAgc: () -> Unit = {},
    // 【V8.274】三行歌词（封面下、频谱表上那三行）开关，与设置页共用 settings/player_inline_lyrics
    inlineLyrics: Boolean = true,
    onToggleInlineLyrics: () -> Unit = {},
    // 【V8.21】歌曲规格显示（分段着色）
    songSpec: com.sdw.music.player.core.audio.metadata.SongSpecReader.Spec? = null,
    /** 底部四键自动隐藏控制器（null = 四键常驻）。 */
    bottomAutoHide: BottomAutoHide? = null,
    // 【Lyrico 联动】三点菜单「用 Lyrico 编辑」返回后的回调，透传给 PlayerTopBar
    onLyricoExternalEditReturned: (songId: Long, filePath: String, extras: Bundle?) -> Unit = { _, _, _ -> }
) {
    val hPadding = if (isRazrOuter) 12.dp else if (isCompact) 24.dp else 48.dp
    val infoPadding = if (isRazrOuter) 14.dp else if (isCompact) 20.dp else 32.dp

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
    ) {
        // [UI-TIDY] 与 PlayerComponents.kt 的 PlayerTopBar 是同一套实现，这里改为直接复用，删掉逐字重复的三份菜单。
        PlayerTopBar(
            showMenu = showMenu,
            onToggleMenu = onToggleMenu,
            onDismissMenu = onDismissMenu,
            onNavigateBack = onNavigateBack,
            onDelete = onDeleteSong,
            currentSongPath = state.currentSongFilePath,
            onLyricoEditReturned = { extras ->
                onLyricoExternalEditReturned(state.currentSongId, state.currentSongFilePath, extras)
            },
            onAddToPlaylist = onAddToPlaylist,
            onSleepTimer = onSleepTimer,
            onNavigateToQueue = onNavigateToQueue,
            isCurrentSongFavorite = state.isCurrentSongFavorite,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare,
            onToggleEqualizer = onToggleEqualizer,
            dtsEnabled = dtsOn,
            onToggleDts = onToggleDts,
            directPassthrough = directPassthrough,
            onToggleDirectPassthrough = onToggleDirectPassthrough,
                agcEnabled = agcEnabled,
                onToggleAgc = onToggleAgc,
                inlineLyrics = inlineLyrics,
                onToggleInlineLyrics = onToggleInlineLyrics,
                onNavigateToAudioDiagnostic = onNavigateToAudioDiagnostic,
                verticalPadding = 0.dp,
            backIconSize = 32.dp,
            modifier = Modifier.then(portraitModifier)
        )

        Spacer(Modifier.height(2.dp))

        // Song header
        // [V8.125] 沉浸模式下歌名/歌手整体上移 10dp，顶部更紧凑，也给封面让出高度。
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding)
                .offset(y = if (immersive) (-10).dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 【V8.5】切歌时标题/歌手淡入淡出过渡
            AnimatedContent(
                            targetState = state.currentSongId,
                            transitionSpec = {
                                // [V8.84] Displacement on track change instead of a
                                // pure crossfade (slide out up, in from below).
                                (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                    slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 7 })
                                    .togetherWith(
                                        fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                                            slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { -it / 7 }
                                    )
                            }
                        ) { _ ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                                // [V8.115] 歌名调小调细：headlineMedium(22/Bold) -> titleMedium(16/Light)，
                                // Razr 外屏 titleLarge -> titleSmall；颜色降到 0.88 的柔和白。
                                style = if (isRazrOuter) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Light)
                                        else MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f), maxLines = if (isRazrOuter) 1 else 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                            )
                            if (!hideSongInfo) {
                                PlayerSongInfo(
                                    artist = state.currentSongArtist,
                                    format = state.currentSongFormat,
                                    textAccentColor = textAccentColor,
                                    onArtistClick = { name -> onNavigateToArtist(name) }
                                )
                            }
                        }
                        }
                    

            // 【V8.7】Analog 模式歌词保持原位置（封面上方单行）；Mixer 模式歌词移到封面下三行
            if (vuStyleIdx == VuMeterStyle.ANALOG_NEEDLE.ordinal) {
                Spacer(Modifier.height(14.dp))
                // [V8.135] 逐字开启且当前行有效时用逐字高亮，否则保持原单行跑马灯
                if (wordByWord && currentLyricIdx in lyricsLines.indices) {
                    InlineLyricWord(
                        line = lyricsLines[currentLyricIdx],
                        lineEndMs = lyricsLines.getOrNull(currentLyricIdx + 1)?.timeMs ?: Long.MAX_VALUE,
                        positionMs = positionMs,
                        color = textAccentColor,
                        isPlaying = isPlaying,
                        onClick = onNavigateToLyrics
                    )
                } else {
                    PlayerInlineLyric(currentLyricLine, textAccentColor, onClick = onNavigateToLyrics)
                }
        }

        }
        // [V8.48] Breathing room between the song header (title/artist) and the
        // artwork. The old flat 8.dp gap made the title look glued to the cover;
        // compact and Razr-outer layouts keep the tight value (height is scarce).
        // [V8.125] 沉浸模式把这段留白收窄到 12dp，空间全部让给更大的封面。
        Spacer(Modifier.height(if (isRazrOuter) 8.dp else if (isCompact) 20.dp else if (immersive) 12.dp else 34.dp))

        // Album art — fixed position, does not move with lyrics
        // 【V8.8】Razr 方形外屏：封面占剩余空间（weight 弹性 + BoxWithConstraints 动态算大小），
        // 底部控制/进度/操作固定可见，封面大小随剩余空间自适应，永不溢出。
        if (isRazrOuter) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints {
                    val maxArt = minOf(maxWidth * 0.72f, maxHeight, 190.dp)
                    PlayerCoverArt(
                        artUri = coverUri,
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        accentColor = accentColor,
                        isPlaying = isPlaying,
                        rotation = rotation,
                        sizeDp = maxArt,
                        glowSizeDp = maxArt + 24.dp,
                        onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                        onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                        onSwipePrevious = onPrevious,
                        onSwipeNext = onNext,
                        fadeOverlayUri = fadeOverlayUri,
                        instantCoverSwitch = instantCoverSwitch,
                        fadeOverlayDurationMs = fadeOverlayDurationMs,
                        fadeOverlayProgress = fadeOverlayProgress,
                        fadeOverlayActive = fadeOverlayActive,
                        fadeOverlayStartMs = fadeOverlayStartMs,
                        squareCover = immersive,
                        noRotation = immersive,
                        modifier = Modifier.size(maxArt + 24.dp)
                    )
                }
            }
        } else {
            // [V8.45] The cover takes the leftover space and is capped by the height that
            // is ACTUALLY available (not just by screenWidth). On short / squarish screens
            // the fixed stack below used to spill past the bottom edge, cutting off the
            // last children (bottom actions / Motorola watermark).
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                BoxWithConstraints {
                    // [V8.125] 沉浸模式：封面放大到接近满宽（左右各留约 3%），且不再受
                    // artSize(0.55w) 上限约束；普通模式保持原尺寸与居中留白。
                    val maxArt = if (immersive) {
                        minOf(maxWidth * 0.94f, maxHeight - 24.dp).coerceAtLeast(120.dp)
                    } else {
                        minOf(maxWidth * 0.72f, maxHeight - 36.dp, artSize).coerceAtLeast(96.dp)
                    }
                    val glowPad = if (immersive) 0.dp else 36.dp
                    PlayerCoverArt(
                        artUri = coverUri,
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        accentColor = accentColor,
                        isPlaying = isPlaying,
                        rotation = rotation,
                        sizeDp = maxArt,
                        glowSizeDp = maxArt + glowPad,
                        onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                        onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                        onSwipePrevious = onPrevious,
                        onSwipeNext = onNext,
                        fadeOverlayUri = fadeOverlayUri,
                        instantCoverSwitch = instantCoverSwitch,
                        fadeOverlayDurationMs = fadeOverlayDurationMs,
                        fadeOverlayProgress = fadeOverlayProgress,
                        fadeOverlayActive = fadeOverlayActive,
                        fadeOverlayStartMs = fadeOverlayStartMs,
                        squareCover = immersive,
                        noRotation = immersive,
                        modifier = Modifier.size(maxArt + glowPad)
                    )
                }
        }

        // 【V8.7】Mixer 模式：封面下、频谱表上，三行歌词（中间行高亮当前歌词）
        // 【V8.8】Razr 方形外屏：不显示三行（空间不足），统一用顶部单行歌词
        // 【V8.274】三点菜单里的「三行歌词」开关关掉后整块（含上下留白）收起，封面自动变大
        if (vuStyleIdx != VuMeterStyle.ANALOG_NEEDLE.ordinal && !isRazrOuter && inlineLyrics) {
            Spacer(Modifier.height(10.dp))
            InlineLyric3(
                lyricsLines = lyricsLines,
                currentIdx = currentLyricIdx,
                highlight = true,
                color = textAccentColor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding),
                // [V8.126] 点三行歌词进全屏歌词页
                onClick = onNavigateToLyrics,
                // [V8.135] 逐字开关透传：中间行按词渐亮
                wordByWord = wordByWord,
                positionMs = positionMs,
                isPlaying = isPlaying
            )
            // [V8.58] This spacer absorbs the lyric block's growth (64->80dp) so the
            // cover size and everything below stay exactly where V8.48/V8.49 put them.
            // [V8.49] 歌词整体上提：歌词块下方补一段留白，把封面上方弹性区里那一大片空档
            // 收窄。控件顺序是从底部往上排的，所以只有歌词会上移 50dp，
            // 下方 VU 表/进度条/控制条/机型水印的绝对位置不变（封面按 maxHeight 自适应，
            // 空间不足时会自动缩小，不会和歌词重叠）。
            Spacer(Modifier.height(if (isCompact) 14.dp else 34.dp))
        }

        // [V8.45] Both branches now give the cover weight(1f), so a second flex spacer
        // would split the leftover and shrink the cover. It is gone entirely.

        // VU 表 / 整曲波形
        if (!isRazrOuter && visualMode.showVu) {
                LiveVuMeter(bandLevelsState = bandLevelsState, isActive = isPlaying, style = VuMeterStyle.entries[vuStyleIdx.coerceIn(0, VuMeterStyle.entries.lastIndex)], accentColor = accentColor, coverColors = vuCoverColors, isAnimating = isAnimating, modifier = Modifier.padding(horizontal = infoPadding))
            Spacer(Modifier.height(6.dp))
        }
        if (!isRazrOuter && visualMode.showWave) {
            FullSongWaveform(
                modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = infoPadding),
                progress = progressFraction,
                showSliceMarkers = false,
                showBarLabels = false,
                // 【V8.439】省电：播放页只等 20 × 600ms = 12s
                pollBudget = 20,
                onSeekToFraction = { frac ->
                    if (durationMs > 0) onSeekTo((durationMs * frac).toLong())
                }
            )
            Spacer(Modifier.height(6.dp))
        }

            Spacer(Modifier.height(8.dp))

        // V3.3.4: DAC info capsule (visible only in USB DAC exclusive mode)
        if (!isRazrOuter) {
            DacInfoBar(accentColor, textAccentColor, isPlaying,
                onClick = onNavigateToAudioDiagnostic,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            OutputDeviceBar(accentColor, textAccentColor, isPlaying,
                show = !immersive,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        // Progress
            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { bottomAutoHide?.poke(); onSeekTo((durationMs * it).toLong()) },
                // 【V8.184】规格并入进度条下方的时间行，与「时长」同水平、右对齐
                specSlot = { if (!hideSpec) SongSpecLabel(songSpec, accentColor, textAccentColor) },
                modifier = Modifier.padding(horizontal = infoPadding)
            )
            Spacer(Modifier.height(6.dp))

        // 【V8.19】DTS 环绕快捷开关 — 播放条上方左侧
        // [V8.71] hidden in immersive mode
        if (!immersive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                PlayerDtsToggle(
                    dtsOn = dtsOn,
                    accentColor = accentColor,
                    textAccentColor = textAccentColor,
                    onClick = onToggleDts
                )
            }
            Spacer(Modifier.height(2.dp))
        }
        // Controls
        if (djEnabled) {
            // 【V8.380】DJ 模式：只有播放 / 暂停 / 下一曲三个键
            DjTransportBar(
                isPlaying = isPlaying,
                accentColor = accentColor,
                onPlay = onPlay,
                onPause = onPause,
                // 【V8.362】同上：切歌键 = DJ 切歌，失败且未排队才回退普通硬切。
                onNext = djCutOrFallbackNext(onNext),
                modifier = Modifier.fillMaxWidth().padding(horizontal = SDWTransport.Gutter),
                onInteract = { bottomAutoHide?.poke() }
            )
        } else {
        PlayerControlBar(
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            isPlaying = isPlaying,
            accentColor = accentColor,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onPlay = onPlay,
            onPause = onPause,
            onNext = onNext,
            onCycleRepeat = onCycleRepeat,
            // [SDWTransport] 五键尺寸/铺法统一契约，沉浸与非沉浸完全同一套
            modifier = Modifier.fillMaxWidth().padding(horizontal = SDWTransport.Gutter),
            playButtonSize = if (isRazrOuter) 48.dp else SDWTransport.Play,
            onInteract = { bottomAutoHide?.poke() }
        )
        }
        Spacer(Modifier.height(8.dp))

        // Bottom actions
        if (!hideBottom) {
            PlayerBottomActions(
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onNavigateToLyrics = onNavigateToLyrics,
                onToggleEqualizer = onToggleEqualizer,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding),
                    autoHide = bottomAutoHide
            )
        }

        if (!isRazrOuter) {
            MotorolaWatermark(accentColor = accentColor)
        }
    }
}

// ============================================================================
}
// Motorola Device Detection & Branding — delegates to DeviceMapper singleton
// ============================================================================

internal fun getMotorolaDeviceName(): String? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    if (!manufacturer.contains("motorola")) return null
    val model = Build.MODEL
    return DeviceMapper.getDisplayName(model) ?: model
}

@Composable
private fun MotorolaWatermark(accentColor: Color) {
    val deviceName = remember { getMotorolaDeviceName() } ?: return
    val montserrat = remember { FontFamily(Font(R.font.montserrat_regular)) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Motorola $deviceName",
            fontFamily = montserrat,
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
            color = lerp(accentColor, Color.White, 0.65f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

// ═════════════════════════════════════════════════════════════════
// InkBloom — 封面模糊背景扰动晕开（墨滴入水），V8.37
// 3 个低 alpha 大半径径向光晕缓慢漂移 + 呼吸，色相跟随封面/accent，
// 叠加在封面模糊背景上制造"边缘流动晕开"的液态感。纯 Canvas，帧开销可忽略。
// ═════════════════════════════════════════════════════════════════
@Composable
private fun InkBloomOverlay(accentColor: Color, modifier: Modifier = Modifier) {
    val baseHue = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (accentColor.alpha * 255).toInt(), (accentColor.red * 255).toInt(),
                (accentColor.green * 255).toInt(), (accentColor.blue * 255).toInt()
            ), hsv
        )
        hsv[0]
    }
    // 3 个墨滴：漂移周期 26~44s（极慢），初始位置/半径/相位各异
    data class Bloom(val px: Float, val py: Float, val pr: Float, val periodMs: Int, val phase: Float, val sat: Float)
    val blooms = remember {
        listOf(
            Bloom(0.22f, 0.30f, 0.55f, 28000, 0.0f, 0.45f),
            Bloom(0.80f, 0.22f, 0.48f, 36000, 2.1f, 0.38f),
            Bloom(0.55f, 0.85f, 0.60f, 44000, 4.2f, 0.50f)
        )
    }
    // V8.67 These blooms drift over 28-44s, so repainting them every vsync was pure
    // waste: three screen-sized radial gradients refilled 60x a second for motion far too
    // slow to perceive. A 20fps ticker is indistinguishable here and cuts that fill 3x.
    // The canvas is also drawn at 0.34 scale and blown back up; after the 28dp blur the
    // gradients are visually identical either way.
    // 【V8.428】装饰动画相位改由统一 ticker 提供（原来这里是独立的 20Hz 环）。
    // 保留一条读取即可参与重组：相位本身在 PlayerUiTicker.frame 里。
    val uiFrame = PlayerUiTicker.frame.value
    val flow = uiFrame.flow
    val breathe = uiFrame.breathe

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Canvas(
        modifier = Modifier
            .fillMaxSize(0.34f)
            .graphicsLayer { scaleX = 2.95f; scaleY = 2.95f }
    ) {
        val w = size.width; val h = size.height
        if (w <= 10f || h <= 10f) return@Canvas
        // 每个墨滴：沿 (px,py) 起点做小幅圆形漂移 + 半径脉动
        blooms.forEachIndexed { idx, b ->
            val ang = (flow * 6.2832f + b.phase)
            val dx = kotlin.math.sin(ang) * 0.06f
            val dy = kotlin.math.cos(ang * 0.77f) * 0.05f
            val cx = (b.px + dx).coerceIn(0f, 1f) * w
            val cy = (b.py + dy).coerceIn(0f, 1f) * h
            val pulse = 1f + 0.10f * kotlin.math.sin(flow * 6.2832f * 0.5f + b.phase * 2f)
            val r = b.pr * pulse * minOf(w, h)
            val alphaBase = 0.10f + 0.05f * breathe + (idx * 0.012f)
            val hue = (baseHue + b.phase * 18f) % 360f
            val c1 = android.graphics.Color.HSVToColor(
                floatArrayOf(hue, b.sat, 0.85f)
            )
            val c2 = android.graphics.Color.HSVToColor(
                floatArrayOf((hue + 24f) % 360f, b.sat * 0.7f, 0.55f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(c1).copy(alpha = alphaBase),
                        Color(c2).copy(alpha = alphaBase * 0.35f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
        }
    }
    }
}

// ═════════════════════════════════════════════════════════════════
// Edge Pulse Band — 随机呼吸效果的光带贴着屏幕边缘，非节拍驱动
// ═════════════════════════════════════════════════════════════════
// V8.67 Scratch object for the edge band. These fields are written from inside the draw
// phase, so they are intentionally plain (not snapshot state): writing state there would
// invalidate the layer every frame and defeat the whole point of the caching.
private class EdgeBandCache {
    var shader: android.graphics.SweepGradient? = null
    var angleKey: Float = -1f
    var cornerKey: Float = -1f
    // 【V8.381 横屏修复】旋转下 minDim 不变导致 cornerKey 不变、路径不重建 ->
    // 竖屏形状的路径被画进横屏画布，出现"竖屏框 + 中间竖条"。改用 w/h 本身做闸门。
    var wKey: Float = -1f
    var hKey: Float = -1f
}

@Composable
private fun EdgePulseBand(
    coverColors: List<Int>,
    accentColor: Color
) {
    val coverHue = remember(coverColors) {
        val hsv = FloatArray(3)
        val c = coverColors.firstOrNull { it != 0 }
        if (c != null) { android.graphics.Color.colorToHSV(c, hsv); hsv[0] } else -1f
    }
    val accentHue = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb(
            (accentColor.red * 255).toInt().coerceIn(0, 255),
            (accentColor.green * 255).toInt().coerceIn(0, 255),
            (accentColor.blue * 255).toInt().coerceIn(0, 255)), hsv)
        hsv[0]
    }
    val hueBase = (if (coverHue >= 0f) coverHue else accentHue + 360f) % 360f

    // 流动：色相沿边缘绕圈（极慢，近乎静止）
    // V8.67 The sweep gradient rotates once per 25s and the breathe cycle is 8s, yet both
    // were driven at 60fps. Rebuilding an android SweepGradient, two Paints and a
    // BlurMaskFilter sixty times a second is what actually blew the frame budget (the mask
    // filter especially - it re-tessellates the glow on every frame). 20fps for a 25s
    // rotation is invisible, and the breathe curve is reproduced from the same keyframes.
    // 【V8.428】同上游：相位来自统一 ticker（原来这里是第二个独立的 20Hz 环）。
    // keyframes 0.30@0 → 1.00@4000 → 0.30@8000 的缓动已在 PlayerUiTicker 里算好（breathe8）。
    val uiFrame2 = PlayerUiTicker.frame.value
    val flow = uiFrame2.flowAlt
    val breathe = uiFrame2.breathe8

    // Hoisted: Paint, SweepGradient and the Path are built once, not per frame. The gauge
    // angle is committed via setLocalMatrix only when the ticker actually moved it.
    val glowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
    val corePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
    val sweepMatrix = remember { android.graphics.Matrix() }
    val ringPathAndroid = remember { android.graphics.Path() }
    val cache = remember { EdgeBandCache() }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 50f || h <= 50f) return@Canvas
        val minDim = minOf(w, h)
        val tk = minDim * 0.010f          // 线宽基准（比原 0.014 更细）
        val inset = minDim * 0.010f
        // 【V8.113+】只保留两条长边、删除两条短边：
        // 竖屏（高≥宽）长边为左右两条竖边；横屏长边为上下两条横边。
        // 软头（Cap.ROUND）让长边两端柔和收尾，不再画圆角整框。
        val longVertical = h >= w
        // 几何仅在尺寸/旋转变化时重建，其余帧复用同一条 Path。
        // 闸门用 w/h：90° 旋转时 minDim 不变，仅靠 cornerKey 不会触发重建。
        if (w != cache.wKey || h != cache.hKey) {
            ringPathAndroid.reset()
            if (longVertical) {
                // 左右两条长竖边
                ringPathAndroid.moveTo(inset, inset)
                ringPathAndroid.lineTo(inset, h - inset)
                ringPathAndroid.moveTo(w - inset, inset)
                ringPathAndroid.lineTo(w - inset, h - inset)
            } else {
                // 上下两条长横边
                ringPathAndroid.moveTo(inset, inset)
                ringPathAndroid.lineTo(w - inset, inset)
                ringPathAndroid.moveTo(inset, h - inset)
                ringPathAndroid.lineTo(w - inset, h - inset)
            }
            cache.wKey = w
            cache.hKey = h
            cache.cornerKey = -1f
        }

        val breatheAmp = breathe.coerceIn(0f, 1f)
        val cx = w / 2f; val cy = h / 2f

        drawIntoCanvas { canvas ->
            val c = 0.55f
            val vBright = 0.95f
            val vDim = 0.32f
            // The sweep gradient depends only on the hue and the gauge angle. Both are
            // quantised, so on the vast majority of frames the existing shader is reused.
            val angle = (flow * 360f)
            val key = hueBase * 0.01f + angle
            val sh = cache.shader
            val grad = if (sh == null || key != cache.angleKey) {
                val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
                val c2 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase + 30f, c, vDim))
                val c3 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
                val c4 = android.graphics.Color.HSVToColor(floatArrayOf((hueBase - 30f + 360f) % 360f, c, vDim))
                val c5 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
                android.graphics.SweepGradient(
                    cx, cy,
                    intArrayOf(c1, c2, c3, c4, c5),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)
                ).also { cache.shader = it; cache.angleKey = key }
            } else sh
            sweepMatrix.setRotate(angle, cx, cy)
            grad.setLocalMatrix(sweepMatrix)

            glowPaint.strokeWidth = tk * 3.0f
            glowPaint.shader = grad
            glowPaint.alpha = (breatheAmp * 255).toInt().coerceIn(0, 255)
            if (glowPaint.maskFilter == null) {
                glowPaint.maskFilter = BlurMaskFilter(tk * 1.8f, BlurMaskFilter.Blur.NORMAL)
            }
            corePaint.strokeWidth = tk * 0.9f
            corePaint.shader = grad
            corePaint.alpha = (breatheAmp * 255).toInt().coerceIn(0, 255)

            canvas.nativeCanvas.drawPath(ringPathAndroid, glowPaint)
            canvas.nativeCanvas.drawPath(ringPathAndroid, corePaint)
        }
    }
    }


// 【V8.182 过渡可视化】DJ 交叉淡化的现场反馈：一条进度条 + A/B 两轨电平。
// 只在过渡 ACTIVE 期出现（MusicService 会把非过渡期的数值归零），结束后自动收起。
@Composable
private fun XfadeVisualizer(
    progress: Float,
    rmsA: Float,
    rmsB: Float,
    accentColor: Color
) {
    val p = progress.coerceIn(0f, 1f)
    val a = rmsA.coerceIn(0f, 1f)
    val b = rmsB.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 6.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(accentColor.copy(alpha = 0.14f))
    ) {
        // A 轨：从左端向右退去（过渡起点满，终点空）
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f - p)
                .background(accentColor.copy(alpha = 0.35f + 0.55f * a))
        )
        // B 轨：从右端向左进入（过渡终点满）
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(p)
                .align(Alignment.CenterEnd)
                .background(Color.White.copy(alpha = 0.70f + 0.30f * b))
        )
        // 中点标记：等功率交叉的响度平衡点
        Box(
            Modifier
                .fillMaxHeight(0.35f)
                .width(2.dp)
                .align(Alignment.Center)
                .background(accentColor.copy(alpha = 0.9f))
        )
    }
}
