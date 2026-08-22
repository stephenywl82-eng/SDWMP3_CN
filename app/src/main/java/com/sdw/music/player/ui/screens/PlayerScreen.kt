package com.sdw.music.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.util.Log
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.app.Activity
import android.os.Build
import android.content.res.Configuration
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdw.music.player.R
import com.sdw.music.player.util.DeviceMapper
import com.sdw.music.player.ui.components.LyricViewCompose
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LrcParser
import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.MusicService
import android.os.PowerManager
import com.sdw.music.player.ui.theme.*

import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.ui.components.VuMeter
import com.sdw.music.player.ui.components.VuMeterStyle
import com.sdw.music.player.ui.viewmodel.PlayerState
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.SharedCoverState
import androidx.media3.common.Player

data class BandLevels(val sub: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val high: Float = 0f, val rms: Float = 0f)

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
    onToggleEqualizer: () -> Unit,
    onDeleteSong: () -> Unit,
    onNavigateToAlbum: (albumName: String) -> Unit = {},
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onPlayQueueIndex: (Int) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    audioSessionId: Int = 0,
) {
    val context = LocalContext.current

    // Resolve cover URI: embedded album art from MediaStore
    fun resolveCoverUri(): String? {
        state.currentSongAlbumArt.takeIf { !it.isNullOrEmpty() }?.let { return it }
        return null
    }
    var coverUri by remember { mutableStateOf(resolveCoverUri()) }
    // Re-resolve when song changes
    LaunchedEffect(state.currentSongId) { coverUri = resolveCoverUri() }
    val hasCoverColor = state.accentColor != 0L && coverUri?.isNotEmpty() == true
    val accentColor by animateColorAsState(
        targetValue = if (hasCoverColor) Color(state.accentColor) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
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

    // BackHandler: system back triggers navigate back
    BackHandler(onBack = onNavigateBack)

    // Immersive: hide status bar, restore on leave
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isCompact = screenWidthDp < 400
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFoldable = screenWidthDp >= 420 && (screenWidthDp.toFloat() / screenHeightDp.toFloat()) in 0.7f..1.4f

    val artSize = if (isFoldable && isLandscape) (screenHeightDp * 0.68f).dp.coerceAtMost(260.dp)
                  else if (isLandscape) (screenHeightDp * 0.55f).dp.coerceAtMost(220.dp)
                  else (screenWidthDp * 0.55f).dp.coerceAtMost(240.dp)

    // 【V7.97】屏幕状态感知：熄屏时停掉60fps动画，主线程CPU从~16%压到~1%
    val screenOn = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        while (isActive) {
            screenOn.value = pm.isInteractive
            delay(2000)
        }
    }

    // 封面旋转：播放+亮屏时推进，暂停/熄屏时冻结（连续无跳变，切歌 isPlaying 抖动不再导致角度突变）
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, screenOn.value) {
        while (isPlaying && screenOn.value) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(30000, easing = LinearEasing)
            )
        }
    }

    // EQ status poll
    var eqEnabled by remember { mutableStateOf(false) }
    var eqPresetName by remember { mutableStateOf<String?>(null) }
    var vuSessionId by remember { mutableIntStateOf(audioSessionId) }

    val vuPrefs = remember { context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE) }
    var vuEnabled by remember { mutableStateOf(vuPrefs.getBoolean("vu_meter_enabled", true)) }
    var vuStyleIdx by remember { mutableIntStateOf(vuPrefs.getInt("vu_meter_style", 1).coerceIn(0, VuMeterStyle.entries.lastIndex)) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                // Native DSP EQ state (AAudio Direct, no ExoPlayer)
                val svc = com.sdw.music.player.MusicService.instance
                // 【V7.200】MSEB active → show "MSEB" instead of EqualizerManager preset name
                val msebActive = com.sdw.music.player.MsebCalculator.isEnabled(context)
                eqEnabled = svc?.isDspEqEnabled() == true || EqualizerManager.isEnabled() || msebActive
                eqPresetName = when {
                    msebActive -> "MSEB"
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

    val bandLevelsState = remember { mutableStateOf(BandLevels()) }
    LaunchedEffect(Unit) {
        val emaSub = mutableStateOf(0f); val emaBass = mutableStateOf(0f)
        val emaMid = mutableStateOf(0f); val emaHigh = mutableStateOf(0f)
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
        // Poll real band levels from Oboe LP-diff analysis (or FFT callback fallback for ExoPlayer)
        while (true) {
            try {
                val oboe = MusicService.instance?.getOboePlayer()
                if (oboe != null) {
                    bandLevelsState.value = BandLevels(
                        sub = oboe.getBandSub(),
                        bass = oboe.getBandBass(),
                        mid = oboe.getBandMid(),
                        high = oboe.getBandHigh(),
                        rms = oboe.getRmsLevel()
                    )
                } else if (emaSub.value > 0.0001f || emaBass.value > 0.0001f ||
                           emaMid.value > 0.0001f || emaHigh.value > 0.0001f) {
                    // ExoPlayer mode: use FFT callback EMA values (Visualizer API)
                    val rms = (emaSub.value * 0.3f + emaBass.value * 0.4f + emaMid.value * 0.2f + emaHigh.value * 0.1f)
                    bandLevelsState.value = BandLevels(
                        sub = emaSub.value, bass = emaBass.value,
                        mid = emaMid.value, high = emaHigh.value, rms = rms
                    )
                }
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(80)
        }
    }
    DisposableEffect(Unit) {
        onDispose { MusicService.instance?.setFftCallback(null) }
    }

    val progressFraction = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

    // Band-reactive Aurora 鈥?4 band groups 脳 3 blobs each
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

    // Per-band beat flash for pulse effect
    data class BandBeat(val flash: Float = 0f, val decay: Float = 0f)
    var subBeat by remember { mutableStateOf(BandBeat()) }
    var bassBeat by remember { mutableStateOf(BandBeat()) }
    var midBeat by remember { mutableStateOf(BandBeat()) }
    var highBeat by remember { mutableStateOf(BandBeat()) }

    // 播放器页固定深色配色：无论全局切浅色还是深色，播放器内部始终走深色（含文字）
    MaterialTheme(colorScheme = SDWDarkColorScheme) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Cover color blurred background
        if (coverColorBgEnabled) {
            val coverUri = state.currentSongAlbumArt
            if (coverUri?.isNotBlank() == true) {
                // 封面模糊背景：小半径模糊保留图案轮廓，高透明度让颜色充分透出
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.35f)
                        .blur(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(coverUri),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.65f
                    )
                }
            }
            // 氛围渐变：上下柔和压暗（中间透出封面，无亮带无光环）
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.55f),
                        0.20f to Color.Black.copy(alpha = 0.10f),
                        0.50f to Color.Black.copy(alpha = 0.03f),
                        0.82f to Color.Black.copy(alpha = 0.15f),
                        1.0f to Color.Black.copy(alpha = 0.55f)
                    )
                )
            }
        }
        // Edge light — 只保留方案 B（脉冲光带），随机呼吸效果
        if (!isFoldable && edgeLightEnabled) {
            EdgePulseBand(
                coverColors = state.coverColors,
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
        if (isLandscape && isFoldable) {
            FoldableLayout(
                coverUri = coverUri,
                state = state,
                sharedCoverState = sharedCoverState,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation.value,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                lyricsLines = lyricsLines,
                showMenu = showMenu,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
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
                onNavigateToQueue = { showQueue = true }
            )
        } else if (isLandscape) {
            LandscapeLayout(
                coverUri = coverUri,
                state = state,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation.value,
                artSize = artSize,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                vuEnabled = vuEnabled,
                bandLevels = bandLevelsState.value,
                vuStyleIdx = vuStyleIdx,
                currentLyricLine = currentLyricLine,
                showMenu = showMenu,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
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
                onNavigateToQueue = { showQueue = true }
            )
        } else {
            PortraitLayout(
                coverUri = coverUri,
                state = state,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation.value,
                artSize = artSize,
                isCompact = isCompact,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                vuEnabled = vuEnabled,
                bandLevels = bandLevelsState.value,
                vuStyleIdx = vuStyleIdx,
                currentLyricLine = currentLyricLine,
                showMenu = showMenu,
                portraitModifier = portraitModifier,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
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
                onNavigateToQueue = { showQueue = true }
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

        if (showQueue) {
            QueueSheet(
                queue = state.queue,
                currentSongId = state.currentSongId,
                accentColor = accentColor,
                onSongClick = onPlayQueueIndex,
                onDismiss = { showQueue = false }
            )
        }

        // Sleep Timer Dialog
        if (showSleepDialog) {
            AlertDialog(
                onDismissRequest = { showSleepDialog = false },
                title = { Text(stringResource(R.string.title_sleep_timer)) },
                text = {
                    Column {
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
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showSleepDialog = false }) { Text(stringResource(R.string.action_close)) } }
            )
        }

    }
    }
}

// ============================================================================
// Layout Composables
// ============================================================================

@Composable
private fun FoldableLayout(coverUri: String?, 
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
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
    onNavigateToQueue: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
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
                onSleepTimer = onSleepTimer,
                onNavigateToQueue = onNavigateToQueue,
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
            )

            // Center: cover + song info
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Cover (fixed position, outside AnimatedContent)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .graphicsLayer { alpha = if (true) 1f else 0f }
                        .clickable(
                            indication = rememberRipple(bounded = false, radius = 70.dp),
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNavigateToAlbum(state.currentSongAlbum) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(140.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.08f)))
                    PlayerCoverArt(
                        artUri = coverUri,
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        accentColor = accentColor,
                        isPlaying = isPlaying,
                        rotation = rotation,
                        sizeDp = 130.dp,
                        glowSizeDp = 140.dp,
                        onCoverPositioned = { offset, size ->
                            sharedCoverState?.fullCoverPosition = CoverPosition(windowOffset = offset, size = size)
                        },
                        onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                        onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                        onSwipePrevious = onPrevious,
                        onSwipeNext = onNext
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Song info animated on track change
                AnimatedContent(
                    targetState = state.currentSongId,
                    transitionSpec = {
                        (fadeIn(tween(350))).togetherWith(fadeOut(tween(250)))
                    }
                ) { _ ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                        PlayerSongInfo(
                            artist = state.currentSongArtist,
                            format = state.currentSongFormat,
                            textAccentColor = textAccentColor
                        )
                    }
                }
            }

            // Bottom: controls
            PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
            Spacer(Modifier.height(4.dp))
            DacInfoBar(accentColor, textAccentColor, isPlaying,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))

            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { onSeekTo((durationMs * it).toLong()) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(6.dp))

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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                playButtonSize = 48.dp
            )
            Spacer(Modifier.height(4.dp))

            PlayerBottomActions(
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onNavigateToLyrics = onNavigateToLyrics,
                onToggleEqualizer = onToggleEqualizer,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

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
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
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
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LandscapeLayout(coverUri: String?, 
    state: PlayerState,
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
    vuEnabled: Boolean,
    bandLevels: BandLevels,
    vuStyleIdx: Int,
    currentLyricLine: String?,
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
    onNavigateToQueue: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
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
                onSleepTimer = onSleepTimer,
                onNavigateToQueue = onNavigateToQueue
            )

            // Scrollable content area
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Center: song info
                Spacer(Modifier.height(12.dp))
                Text(
                    state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                PlayerSongInfo(
                    artist = state.currentSongArtist,
                    format = state.currentSongFormat,
                    textAccentColor = textAccentColor,
                    onArtistClick = { name -> onNavigateToArtist(name) }
                )
                Spacer(Modifier.height(6.dp))
                PlayerInlineLyric(currentLyricLine, textAccentColor)

                // Controls
                Spacer(Modifier.height(8.dp))
            // VU Meter — compact in landscape
            if (vuEnabled) {
                VuMeter(sub = bandLevels.sub, bass = bandLevels.bass, mid = bandLevels.mid, high = bandLevels.high, rms = bandLevels.rms, isActive = isPlaying, style = VuMeterStyle.entries[vuStyleIdx.coerceIn(0, VuMeterStyle.entries.lastIndex)], accentColor = accentColor, modifier = Modifier.padding(horizontal = 8.dp).heightIn(max = 50.dp))
                Spacer(Modifier.height(6.dp))
            }
            PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
            DacInfoBar(accentColor, textAccentColor, isPlaying,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(2.dp))
            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { onSeekTo((durationMs * it).toLong()) }
            )
            Spacer(Modifier.height(8.dp))

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
                modifier = Modifier.fillMaxWidth(),
                playButtonSize = 52.dp
            )
            Spacer(Modifier.height(4.dp))

            PlayerBottomActions(
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onNavigateToLyrics = onNavigateToLyrics,
                onToggleEqualizer = onToggleEqualizer,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                modifier = Modifier.fillMaxWidth()
            )

            MotorolaWatermark(accentColor = accentColor)
            } // close scrollable inner Column
        } // close outer left Column

        // Right: album art — height capped by artSize, centered vertically
        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp), contentAlignment = Alignment.Center) {
            PlayerCoverArt(
                artUri = coverUri,
                songTitle = state.currentSongTitle,
                songArtist = state.currentSongArtist,
                accentColor = accentColor,
                isPlaying = isPlaying,
                rotation = rotation,
                sizeDp = artSize,
                glowSizeDp = artSize + 32.dp,
                onClick = { onNavigateToAlbum(state.currentSongAlbum) },
                onDoubleTap = { if (isPlaying) onPause() else onPlay() },
                onSwipePrevious = onPrevious,
                onSwipeNext = onNext
            )
        }
    }
}

@Composable
private fun PortraitLayout(coverUri: String?, 
    state: PlayerState,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    artSize: androidx.compose.ui.unit.Dp,
    isCompact: Boolean,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    vuEnabled: Boolean,
    bandLevels: BandLevels,
    vuStyleIdx: Int,
    currentLyricLine: String?,
    showMenu: Boolean,
    portraitModifier: Modifier,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
    onNavigateToQueue: () -> Unit = {}
) {
    val hPadding = if (isCompact) 24.dp else 48.dp
    val infoPadding = if (isCompact) 20.dp else 32.dp

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).then(portraitModifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateToQueue) {
                    Icon(Icons.Default.QueueMusic, stringResource(R.string.player_queue), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = onToggleMenu) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.player_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.title_sleep_timer)) },
                        onClick = { onDismissMenu(); onSleepTimer() },
                        leadingIcon = { Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_delete_song), color = Color.Red) },
                        onClick = { onDismissMenu(); onDeleteSong() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    )
                }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Song header
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.currentSongTitle.ifEmpty { stringResource(R.string.player_not_playing) },
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            PlayerSongInfo(
                artist = state.currentSongArtist,
                format = state.currentSongFormat,
                textAccentColor = textAccentColor,
                onArtistClick = { name -> onNavigateToArtist(name) }
            )
            Spacer(Modifier.height(14.dp))
            PlayerInlineLyric(currentLyricLine, textAccentColor)
        }

        Spacer(Modifier.height(8.dp))

        // Album art — fixed position, does not move with lyrics
        PlayerCoverArt(
            artUri = coverUri,
            songTitle = state.currentSongTitle,
            songArtist = state.currentSongArtist,
            accentColor = accentColor,
            isPlaying = isPlaying,
            rotation = rotation,
            sizeDp = artSize,
            glowSizeDp = artSize + 36.dp,
            onClick = { onNavigateToAlbum(state.currentSongAlbum) },
            onDoubleTap = { if (isPlaying) onPause() else onPlay() },
            onSwipePrevious = onPrevious,
            onSwipeNext = onNext,
            modifier = Modifier.fillMaxWidth().height(artSize + 36.dp)
        )

        Spacer(Modifier.weight(1f))

        // VU Meter
        if (vuEnabled) {
                VuMeter(sub = bandLevels.sub, bass = bandLevels.bass, mid = bandLevels.mid, high = bandLevels.high, rms = bandLevels.rms, isActive = isPlaying, style = VuMeterStyle.entries[vuStyleIdx.coerceIn(0, VuMeterStyle.entries.lastIndex)], accentColor = accentColor, modifier = Modifier.padding(horizontal = infoPadding))
            Spacer(Modifier.height(6.dp))
        }

        // EQ label
        PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
        Spacer(Modifier.height(8.dp))

        // V3.3.4: DAC info capsule (visible only in USB DAC exclusive mode)
        DacInfoBar(accentColor, textAccentColor, isPlaying,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        // Progress
        PlayerProgress(
            progressFraction, durationMs, positionMs, accentColor,
            onSeekTo = { onSeekTo((durationMs * it).toLong()) },
            modifier = Modifier.padding(horizontal = infoPadding)
        )
        Spacer(Modifier.height(16.dp))

        // Controls
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding)
        )
        Spacer(Modifier.height(8.dp))

        // Bottom actions
        PlayerBottomActions(
            eqEnabled = eqEnabled,
            isCurrentSongFavorite = state.isCurrentSongFavorite,
            onNavigateToLyrics = onNavigateToLyrics,
            onToggleEqualizer = onToggleEqualizer,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare,
                modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding)
        )

        MotorolaWatermark(accentColor = accentColor)
    }
}

// ============================================================================
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
        Box(
            modifier = Modifier
                .padding(horizontal = 64.dp)
                .height(0.5.dp)
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.2f))
        )
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
// Edge Pulse Band — 随机呼吸效果的光带贴着屏幕边缘，非节拍驱动
// ═════════════════════════════════════════════════════════════════
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
    val infinite = rememberInfiniteTransition(label = "edge_flow")
    val flow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow"
    )
    // 呼吸：整体亮度 0.30~1.00，最暗时保留可见光不纯黑
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 8000
                0.30f at 0 with FastOutSlowInEasing
                1.00f at 4000 with FastOutSlowInEasing
                0.30f at 8000 with FastOutSlowInEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "breathe"
    )

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 50f || h <= 50f) return@Canvas
        val minDim = minOf(w, h)
        val tk = minDim * 0.014f          // 线宽基准
        val inset = minDim * 0.008f
        // 圆角：屏幕物理玻璃圆角(68px)偏方、旧值(0.14=170px)太圆不贴合，取 0.085 折中
        val ringCorner = minDim * 0.085f
        val ringRect = Rect(inset, inset, w - inset, h - inset)
        val ringPath = Path().apply {
            addRoundRect(RoundRect(ringRect, CornerRadius(ringCorner, ringCorner)))
        }

        val breatheAmp = breathe.coerceIn(0f, 1f)
        val cx = w / 2f; val cy = h / 2f

        drawIntoCanvas { canvas ->
            // 均匀往返渐变：色相 ±30° 五段平滑往返；亮度按方位调制：左右亮、上下暗
            val c = 0.55f  // 略饱和
            val vBright = 0.95f  // 左右亮
            val vDim = 0.32f     // 上下暗
            val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
            val c2 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase + 30f, c, vDim))
            val c3 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
            val c4 = android.graphics.Color.HSVToColor(floatArrayOf((hueBase - 30f + 360f) % 360f, c, vDim))
            val c5 = android.graphics.Color.HSVToColor(floatArrayOf(hueBase, c, vBright))
            val sweep = android.graphics.SweepGradient(
                cx, cy,
                intArrayOf(c1, c2, c3, c4, c5),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)
            )
            sweep.setLocalMatrix(android.graphics.Matrix().apply {
                setRotate(flow * 360f, cx, cy)
            })

            // 外发光：宽 + 柔和 blur
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = tk * 3.6f
                shader = sweep
                maskFilter = BlurMaskFilter(tk * 1.8f, BlurMaskFilter.Blur.NORMAL)
                alpha = (breatheAmp * 255).toInt().coerceIn(0, 255)
            }
            // 核心亮线：更细、接近白的高亮
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = tk * 1.1f
                shader = sweep
                alpha = (breatheAmp * 255).toInt().coerceIn(0, 255)
            }

            val ap = ringPath.asAndroidPath()
            canvas.nativeCanvas.drawPath(ap, glow)
            canvas.nativeCanvas.drawPath(ap, core)
        }
    }
}

