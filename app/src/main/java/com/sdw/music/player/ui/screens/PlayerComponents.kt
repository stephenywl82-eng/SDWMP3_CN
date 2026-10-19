package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.metadata.SongSpecReader
import com.sdw.music.player.core.audio.engine.UsbDacManager

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.sdw.music.player.util.buildLyricoEditIntent
import android.os.SystemClock
import android.os.Bundle
import androidx.compose.ui.text.TextStyle
import android.widget.Toast
import com.sdw.music.player.core.audio.DebugLog
import com.sdw.music.player.LyricLine
import com.sdw.music.player.ui.components.buildWordSegments
import com.sdw.music.player.ui.components.wordByWordAlpha
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import com.sdw.music.player.Song
import com.sdw.music.player.splitArtists
import com.sdw.music.player.R
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.sharedCoverArt
import com.sdw.music.player.ui.animation.LocalCoverDragProgress
import com.sdw.music.player.ui.animation.Motion
import androidx.compose.ui.platform.LocalConfiguration
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.SheetImmersiveStatusBar
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.updateTransition
import com.sdw.music.player.ui.components.rememberSharedCoverPainter

// ============================================================================
// Shared Player Components - extracted from PlayerScreen.kt
// ============================================================================

/** Top bar: back button + title + overflow menu (delete). */
@Composable
fun PlayerTopBar(
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    onNavigateToQueue: () -> Unit = {},
    // 【V8.139】把底部功能键（收藏 / 分享 / DSP 均衡器）同步进右上角三点菜单
    isCurrentSongFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onShare: () -> Unit = {},
    // 【Lyrico 联动】传入当前歌曲路径，供三点菜单「用 Lyrico 编辑」打开外部标签编辑器；
    // onLyricoEditReturned 在 Lyrico 编辑返回后回调（携带 result extras），由调用方就地刷新封面/标签
    currentSongPath: String = "",
    onLyricoEditReturned: (extras: Bundle?) -> Unit = {},
    onToggleEqualizer: () -> Unit = {},
    // 【DTS-in-menu】三点菜单里的 DTS 环绕开关（开→M/S 声场，关→旁路）
    dtsEnabled: Boolean = false,
    onToggleDts: () -> Unit = {},
    // 【V8.177】三点菜单里的原声直通开关（与设置页同一把 key：settings/direct_passthrough）
    directPassthrough: Boolean = false,
    onToggleDirectPassthrough: () -> Unit = {},
    // 【V8.177】三点菜单里的自动增益（AGC）开关（与设置页同一把 key：settings/agc_enabled）
    agcEnabled: Boolean = false,
    onToggleAgc: () -> Unit = {},
    // 【V8.274】三点菜单里的「三行歌词」开关（封面下、频谱表上那三行）。
    // 存在 settings/player_inline_lyrics（与 DTS / AGC 同一份 prefs），默认开。
    inlineLyrics: Boolean = true,
    onToggleInlineLyrics: () -> Unit = {},
    // 【音频诊断】右上角三点菜单入口：跳到 AudioDiagnosticScreen（输出路由 / 采样率 / bit-perfect 实时诊断）
    onNavigateToAudioDiagnostic: () -> Unit = {},
    // [UI-TIDY] Razr 外屏 / 紧凑屏的复用出口：占位符换成自定义中间内容、压掉竖向内边距、挂外屏专属 modifier。
    center: @Composable RowScope.() -> Unit = {
        Text("", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    },
    verticalPadding: Dp = 4.dp,
    backIconSize: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 【Lyrico 联动】打开 Lyrico 单曲编辑页；返回后把 result extras 抛给 onLyricoEditReturned，
    // 由调用方（PlayerScreen）就地刷新封面/标签（含从被改写文件重新抽封面，绕过平台 albumart 缓存）
    val lyricoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onLyricoEditReturned(result.data?.extras)
        }
    }
    fun launchLyricoEdit() {
        val intent = buildLyricoEditIntent(context, currentSongPath)
        when {
            intent == null -> Toast.makeText(context, R.string.lyrico_edit_unsupported, Toast.LENGTH_SHORT).show()
            intent.resolveActivity(context.packageManager) == null ->
                Toast.makeText(context, R.string.lyrico_not_installed, Toast.LENGTH_LONG).show()
            else -> lyricoLauncher.launch(intent)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.size(backIconSize)) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
        }
        center()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateToQueue, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.QueueMusic, stringResource(R.string.player_queue), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = onToggleMenu, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.player_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.title_sleep_timer)) },
                        onClick = { onDismissMenu(); onSleepTimer() },
                        leadingIcon = { Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_add_to)) },
                        onClick = { onDismissMenu(); onAddToPlaylist() },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isCurrentSongFavorite) R.string.action_unfavorite else R.string.action_favorite)) },
                        onClick = { onDismissMenu(); onToggleFavorite() },
                        leadingIcon = { Icon(if (isCurrentSongFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        onClick = { onDismissMenu(); onShare() },
                        leadingIcon = { Icon(Icons.Default.Share, stringResource(R.string.action_share), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit_with_lyrico)) },
                        onClick = { onDismissMenu(); launchLyricoEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, stringResource(R.string.action_edit_with_lyrico), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.title_dsp_equalizer)) },
                        onClick = { onDismissMenu(); onToggleEqualizer() },
                        leadingIcon = { Icon(Icons.Default.Equalizer, stringResource(R.string.title_dsp_equalizer), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    // 【DTS-in-menu】紧跟 DSP 均衡器（都是音频处理），删除这种破坏性操作留在最后
                    DropdownMenuItem(
                        text = { Text(stringResource(if (dtsEnabled) R.string.menu_dts_on else R.string.menu_dts_off)) },
                        onClick = { onDismissMenu(); onToggleDts() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Audiotrack,
                                stringResource(if (dtsEnabled) R.string.menu_dts_on else R.string.menu_dts_off),
                                tint = if (dtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // 【V8.177】原声直通：整条 DSP 旁路（限幅/softClip 也旁路），
                    // 状态与设置页共用 settings/direct_passthrough，两边永远一致。
                    DropdownMenuItem(
                        text = { Text(stringResource(if (directPassthrough) R.string.menu_direct_passthrough_on else R.string.menu_direct_passthrough_off)) },
                        onClick = { onDismissMenu(); onToggleDirectPassthrough() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.SurroundSound,
                                stringResource(if (directPassthrough) R.string.menu_direct_passthrough_on else R.string.menu_direct_passthrough_off),
                                tint = if (directPassthrough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // 【V8.177】自动增益（AGC）：与设置页共用 settings/agc_enabled。
                    // native 里默认关，之前界面上完全没有开关，只能靠诊断页那一格才知道它存在。
                    DropdownMenuItem(
                        text = { Text(stringResource(if (agcEnabled) R.string.menu_agc_on else R.string.menu_agc_off)) },
                        onClick = { onDismissMenu(); onToggleAgc() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.GraphicEq,
                                stringResource(if (agcEnabled) R.string.menu_agc_on else R.string.menu_agc_off),
                                tint = if (agcEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // 【V8.274】三行歌词开关：紧跟在歌词/音频处理项后面，删除留在最后。
                    DropdownMenuItem(
                        text = { Text(stringResource(if (inlineLyrics) R.string.menu_inline_lyrics_on else R.string.menu_inline_lyrics_off)) },
                        onClick = { onDismissMenu(); onToggleInlineLyrics() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lyrics,
                                stringResource(if (inlineLyrics) R.string.menu_inline_lyrics_on else R.string.menu_inline_lyrics_off),
                                tint = if (inlineLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // 【音频诊断】跳到 AudioDiagnosticScreen：实时显示输出路由 / 采样率 / bit-perfect 状态等。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_audio_diag)) },
                        onClick = { onDismissMenu(); onNavigateToAudioDiagnostic() },
                        leadingIcon = { Icon(Icons.Default.QueryStats, stringResource(R.string.settings_audio_diag), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_delete_song), color = Color.Red) },
                        onClick = { onDismissMenu(); onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = Color.Red) }
                    )
                                }
            }
        }

    }
}

/** Album cover art with glow ring and loading/error fallback. */
@Composable
fun PlayerCoverArt(
    artUri: String?,
    songTitle: String,
    songArtist: String,
    accentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    sizeDp: androidx.compose.ui.unit.Dp,
    glowSizeDp: androidx.compose.ui.unit.Dp,
    onCoverPositioned: ((Offset, Size) -> Unit)? = null,
    onClick: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onSwipePrevious: () -> Unit = {},
    onSwipeNext: () -> Unit = {},
    // 【V8.4】Crossfade 封面交叉淡化：fadeOverlayUri 非空时叠加浮现（B 轨封面）
    fadeOverlayUri: String? = null,
    fadeOverlayDurationMs: Int = 5000,
    // 【V8.139】与音频同相的过渡进度 0..1（由 PlayerScreen 按 native 启动时刻推出）。
    // 由「重跑整段动画」改为「按进度续接」，B 封面得以在真正交叉之前就开始过渡，不再慢半拍。
    fadeOverlayProgress: Float = 0f,
    // 【V8.207】ACTIVE 阶段由组件自己按帧推进进度：native 启动时刻 + 是否仍在过渡中。
    // 帧时钟放在这里而不是 PlayerScreen 顶层 —— 之前每 16ms 改一次顶层 state，
    // 整页（歌词/VU/进度条/模糊背景）跟着重组 60fps，白掉帧还费电。
    fadeOverlayActive: Boolean = false,
    fadeOverlayStartMs: Long = 0L,
    // 【V8.5】crossfade 交接期间换碟瞬时完成（不做 350ms 换碟动画，B 已通过叠加层全显，避免“变两次”跳变）
    instantCoverSwitch: Boolean = false,
    // [V8.71] Immersive player mode: plain square cover, no rotation, no glow.
    squareCover: Boolean = false,
    noRotation: Boolean = false,
    modifier: Modifier = Modifier
) {
    // [V8.74] Immersive mode: rounded square, not a hard rectangle. The shared-element
    // overlay clip interpolates proportionally (see sharedCoverArt), so the SAME percent
    // keeps the flight and the landing shape identical - no pop on the final frame.
    // [V8.75] 22dp flat was too round for a full-bleed cover (read as a generic card).
    // percent = 5 gives ~11-12dp at the 216-240dp cover sizes we actually render, i.e.
    // the Apple Music / Tidal class of rounding, and it scales with the art size instead
    // of being a fixed dp that looks wrong on both the small and the large layouts.
    val coverShape: androidx.compose.ui.graphics.Shape =
        if (squareCover) RoundedCornerShape(percent = 6) else CircleShape
    // [V8.52] Marks this art as the target end of the official shared-element flight
    // from the mini player. No-op when not hosted by SharedTransitionLayout.
    val sharedCover = Modifier.sharedCoverArt()
    // [V8.52] Predictive-back finger-follow: the official transition only starts after the
    // gesture commits, so while the user is still dragging we offset the art ourselves.
    val coverDragProgress = LocalCoverDragProgress.current
    val coverDragOffsetPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenHeightDp.dp.toPx() * 0.45f) * coverDragProgress
    }
    // [V8.42] pointerInput(Unit) never re-runs its block, so the lambdas captured there
    // (and whatever THEY capture, e.g. isPlaying at the call site) would stay frozen at
    // first composition. Route every gesture through a fresh-value holder instead.
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestOnSwipePrevious by rememberUpdatedState(onSwipePrevious)
    val latestOnSwipeNext by rememberUpdatedState(onSwipeNext)
    // 【V8.34 封面呼吸悬浮】播放中缓慢呼吸（半周期 4s → 全周期 8s），暂停/停止归位
    val breathe = remember { Animatable(1f) }
    LaunchedEffect(isPlaying, noRotation) {
        if (isPlaying && !noRotation) {
            breathe.snapTo(1f)
            breathe.animateTo(
                1.03f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        } else {
            breathe.animateTo(1f, animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier = modifier
            .size(glowSizeDp)
            .graphicsLayer {
                // 呼吸缩放（悬浮感核心）
                scaleX = breathe.value
                scaleY = breathe.value
                // [V8.52] follow the finger during predictive back
                translationY = coverDragOffsetPx
            }
            // [V8.42] Same stale-capture / competing-detector fix as PlayerProgress.
            // Tap, double-tap and horizontal swipe used to live on TWO pointerInput(Unit)
            // nodes: detectTapGestures consumed DOWN so the swipe detector's slop pass bailed
            // out (swipes dropped), and both lambdas stayed frozen at first composition.
            // One awaitEachGesture loop now arbitrates all three:
            //   horizontal movement > 100px  -> swipe prev/next
            //   no movement, 2nd tap in time -> double tap
            //   no movement, single tap      -> click
            .pointerInput(Unit) {
                val tapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val moveThreshold = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = 0f
                    var total = 0f
                    var swiping = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id }
                        if (ch == null) break
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        moved = maxOf(moved, abs(dx), abs(dy))
                        if (moved > moveThreshold) {
                            total = dx
                            swiping = true
                        }
                        if (swiping && abs(dx) > abs(dy)) ch.consume()
                        if (!ch.pressed) break
                    }
                    if (swiping) {
                        if (total <= -100f) latestOnSwipeNext()
                        else if (total >= 100f) latestOnSwipePrevious()
                    } else if (moved <= moveThreshold) {
                        // Still a tap: give the user the double-tap window before firing click.
                        val isDouble = withTimeoutOrNull(tapTimeout) {
                            val d2 = awaitFirstDown(requireUnconsumed = false)
                            var m2 = 0f
                            while (true) {
                                val e2 = awaitPointerEvent()
                                val c2 = e2.changes.firstOrNull { it.id == d2.id }
                                if (c2 == null) break
                                m2 = maxOf(
                                    m2,
                                    abs(c2.position.x - d2.position.x),
                                    abs(c2.position.y - d2.position.y)
                                )
                                if (m2 > moveThreshold) {
                                    c2.consume()
                                    return@withTimeoutOrNull false
                                }
                                if (!c2.pressed) break
                            }
                            m2 <= moveThreshold
                        }
                        if (isDouble == true) latestOnDoubleTap() else latestOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Glow ring behind the art (omitted in immersive square mode)
        if (!squareCover) {
            Box(
                modifier = Modifier
                    .size(glowSizeDp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.08f))
            )
        }
        // 始终旋转（角度连续累积，暂停/切歌不归零），换碟动画发生在旋转层内部
        // [V8.55] The shared-element marker lives HERE, on the stable container, instead of
        // on the cover Image inside AnimatedContent. On a track change AnimatedContent keeps
        // the outgoing AND incoming cover composed at the same time; both carried the same
        // shared key, so the framework saw a match and hoisted them into the transition
        // overlay - the old cover popped out of this rotating box (it looked like it flew
        // away) and the overlay attach/detach cost a frame. One marker on a container that
        // never leaves composition fixes both.
        Box(
            modifier = Modifier
                .size(sizeDp)
                .then(if (noRotation) Modifier else Modifier.rotate(rotation))
                // [V8.76] 沉浸模式封面：加一层极淡的主色调扩散阴影（soft drop shadow），
                // 让它"浮"在毛玻璃背景之上而不是平铺贴上去。shadow 的 ambient/spot 取
                // accentColor（API28+ 着色，以下自动退化为中性阴影），比自绘 blur 便宜且
                // 不依赖 API31 的 RenderEffect。旋转模式不加：阴影会跟着封面一起转。
                .then(
                    if (squareCover) {
                        // [V8.76] ambientColor/spotColor 的 shadow 重载只在 API 28+ 生效
                        // （底层是 RenderNode.setAmbientShadowColor）；低版本退化为中性阴影，
                        // 不会崩，所以按版本分支即可，不需要自绘 blur。
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            Modifier.shadow(
                                elevation = 14.dp,
                                shape = coverShape,
                                clip = false,
                                ambientColor = accentColor.copy(alpha = 0.62f),
                                spotColor = accentColor.copy(alpha = 0.78f)
                            )
                        } else {
                            Modifier.shadow(elevation = 14.dp, shape = coverShape, clip = false)
                        }
                    } else Modifier
                )
                .then(sharedCover)
        ) {
            if (artUri.isNullOrEmpty()) {
                // V8.92 传页面 accentColor：无封面歌时它是全局主题 primary（Material You），
                // 不再被播放器页写死的 SDWDarkColorScheme.primary 锁成常量 AccentBlue。
                DefaultCoverImage(songTitle, songArtist, Modifier.size(sizeDp), shape = coverShape, tintColor = accentColor)
            } else {
                // CD 换碟：旧碟缩小淡出、新碟放大淡入（绕中心缩放，旋转无关，方向稳定）
                // crossfade 交接期间（instantCoverSwitch=true）瞬时完成，不做换碟动画
                AnimatedContent(
                    targetState = artUri,
                    transitionSpec = {
                        if (instantCoverSwitch) {
                            // 【V8.208】不能用 fadeIn(tween(0)) 当"瞬时"：0ms 的动画仍然有
                            // 起始帧，那一帧新封面 alpha=0，底下 DefaultCoverImage（默认
                            // 唱片图标）就透出来了 —— 交叉淡化交接瞬间看到的"闪一下"就是它。
                            // EnterTransition.None 直接跳过动画，新封面当帧即满不透明。
                            EnterTransition.None.togetherWith(ExitTransition.None)
                        } else {
                            // 【V8.5】换碟改为交叉淡化：旧碟淡出同时新碟淡入（无空窗、无缩放跳变），
                            // 观感更接近“两张封面平滑过渡”，而非旧碟缩走再放新碟
                            // [V8.44] spatial scale springs, opacity stays tween
                            (fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                                    scaleIn(
                                        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                                        initialScale = 0.90f
                                    ))
                                .togetherWith(
                                    fadeOut(tween(220, easing = FastOutSlowInEasing)) +
                                            scaleOut(
                                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                                targetScale = 1.04f
                                            )
                                )
                        }
                    },
                    label = "coverSwitch"
                ) { uri ->
                    Box(modifier = Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
                        DefaultCoverImage(songTitle, songArtist, Modifier.size(sizeDp), shape = coverShape, tintColor = accentColor)
                        // [V8.56] Shared request: identical to the mini player's, so the
                        // flight's destination and origin hit ONE decoded bitmap in the
                        // memory cache instead of two independently decoded copies.
                        // V8.66 flight tier: destination of the flight; same bitmap as
                        // the mini player's so the shared element never re-decodes.
                        val coverPainter = rememberSharedCoverPainter(uri, big = true)
                        Image(
                            painter = coverPainter,
                            contentDescription = stringResource(R.string.player_album_art),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(sizeDp)
                                .clip(coverShape)
                                .then(
                                    if (onCoverPositioned != null) {
                                        Modifier.onGloballyPositioned { coords ->
                                            onCoverPositioned(
                                                Offset(coords.positionInWindow().x, coords.positionInWindow().y),
                                                Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                                            )
                                        }
                                    } else Modifier
                                )
                        )
                    }
                }
            }
            // 【V8.4】Crossfade 封面交叉淡化：B 轨封面从 A 封面下方浮现（同尺寸圆角裁剪）
            // 【V8.5】alpha 从 0 启动渐显（Animatable），避免叠加层一出现就全显造成“提前变一次”
            if (!fadeOverlayUri.isNullOrEmpty()) {
                // 【V8.207】帧时钟下沉到本组件：只有这几行会跟着 tick 重组，
                // 不再把整个播放页拖进 60fps 的 recompose。
                var phaseTick by remember { mutableLongStateOf(0L) }
                LaunchedEffect(fadeOverlayActive, fadeOverlayStartMs) {
                    while (isActive && fadeOverlayActive && fadeOverlayStartMs > 0L) {
                        phaseTick = android.os.SystemClock.elapsedRealtime()
                        delay(16)
                    }
                }
                // 【V8.139】相位驱动：目标 alpha 直接由进度推出（0→1），用短 tween 平滑帧间跳变。
                // 进度由 UI 按 native startedAtMs 计算，因此画面与音频严格同相。
                val phaseProgress = if (fadeOverlayActive && fadeOverlayStartMs > 0L) {
                    val d = fadeOverlayDurationMs.coerceIn(1000, 15000).toFloat()
                    ((phaseTick - fadeOverlayStartMs).toFloat() / d).coerceIn(0f, 1f)
                } else fadeOverlayProgress
                val overlayTarget = phaseProgress.coerceIn(0f, 1f)
                val overlayAlpha by animateFloatAsState(
                    targetValue = overlayTarget,
                    animationSpec = tween(180, easing = LinearEasing),
                    label = "xfadeCoverAlpha"
                )
                Box(modifier = Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
                    // V8.66 big tier: this overlay is drawn at the very same sizeDp as the
                    // main art, so it belongs to the flight tier and reuses its cache entry.
                    val overlayPainter = rememberSharedCoverPainter(fadeOverlayUri, big = true)
                    Image(
                        painter = overlayPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(sizeDp)
                            .clip(coverShape),
                        alpha = overlayAlpha
                    )
                }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerSongInfo(
    artist: String,
    format: String,
    textAccentColor: Color,
    onArtistClick: (String) -> Unit = {}
) {
    val artists = remember(artist) { splitArtists(artist) }
    val artistShadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 1f), blurRadius = 6f)
    // 【V8.4】修复：整体居中——artist 不撑满（fill=false），格式徽章紧跟，整行 Arrangement.Center
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
    ) {
        // 【2026-09-02】固定单行：多歌手只显示前 3 个 + "…"，整体单个 Text 单行省略
        // 之前多歌手走 FlowRow 换行 → 歌手多时撑成多行 → 下半部 (歌词/封面/VuMeter) 被移位
        val displayArtists = if (artists.size <= 3) artists else artists.take(3) + listOf("…")
        val artistText = displayArtists.joinToString(" · ")
        Text(
            artistText.ifEmpty { " " },
            style = MaterialTheme.typography.bodyLarge.copy(shadow = artistShadow),
            color = textAccentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onArtistClick(artists.firstOrNull().orEmpty()) }
        )
        // [V8.125] 歌手右侧的歌曲格式徽章（FLAC / MP3 / M4A…）已按要求移除。
        // 格式信息仍在播放页下方的 SongSpecLabel 规格行显示（含位深/采样率/码率）。
    }
}

/** Full song header: title + artist + format, portrait style. */
@Composable
fun PlayerSongHeader(
    title: String,
    artist: String,
    format: String,
    textAccentColor: Color,
    onArtistClick: (String) -> Unit = {}
) {
    Text(
        title.ifEmpty { stringResource(R.string.player_not_playing) },
        // [V8.115] 与 PlayerScreen 保持一致的歌名样式（调小调细 + 柔和白）。
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    PlayerSongInfo(
        artist = artist,
        format = format,
        textAccentColor = textAccentColor,
        onArtistClick = onArtistClick
    )
}

/** Progress bar + time labels. */
@Composable
fun PlayerProgress(
    progressFraction: Float,
    durationMs: Long,
    positionMs: Long,
    accentColor: Color,
    onSeekTo: (Float) -> Unit,
    /**
     * 【V8.184】歌曲规格标签插槽（FLAC · 24bit · 44.1kHz · 1462kbps）。
     * 原来它在进度条下方独占一整行居中，与「时长」不在同一水平线上、整体偏下。
     * 现在并入进度条下面那行时间文字，与「时长」同水平行、并在两侧时间之间水平居中（V8.186）。
     */
    specSlot: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(progressFraction) }
    var isDragging by remember { mutableStateOf(false) }
    // [V8.84] Released thumb coasts to a stop instead of freezing dead. The exit velocity
    // of the drag is fed into a decaying under-damped spring, so the cursor overshoots a
    // hair past the release point, swings back through it and settles ("阻尼振荡晃动半下").
    // Purely visual: the seek value itself is untouched, no audio behaviour changes.
    val settleOffset = remember { Animatable(0f) }
    val settleScope = rememberCoroutineScope()

    // [V8.42] STALE-CAPTURE FIX
    // The two pointerInput(Unit) blocks below are re-used by Compose:
    // SuspendPointerInputElement.equals() compares only the keys, so with key = Unit
    // the node is ActionReuse'd and update() is never called -> the lambda captured at
    // FIRST composition is kept forever. Because the caller's lambda closes over
    // durationMs, every seek after the first song used a STALE duration
    // (=> tap at 50% landed at staleDur*0.5, often near the end of the track).
    // Read the latest lambda at gesture time instead.
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)

    // Sync external progress into slider when not dragging
    LaunchedEffect(progressFraction) {
        if (!isDragging) {
            sliderValue = progressFraction.coerceIn(0f, 1f)
        }
    }

    // 【液态】流动高光相位（无限循环）
    val flowPhase = remember { Animatable(0f) }
    val liquidOn = durationMs > 0 && !isDragging
    LaunchedEffect(liquidOn) {
        if (liquidOn) {
            flowPhase.snapTo(0f)
            flowPhase.animateTo(
                1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (durationMs > 0) {
            // 【V8.34 液态进度条】渐变填充 + 流动高光 + 发光播放头（替代标准 Slider）
            val trackHeight = 5.dp
            val thumbR = 7.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    // [V8.42] Single unified gesture handler.
                    // Previously detectTapGestures + detectHorizontalDragGestures lived on TWO
                    // separate pointerInput(Unit) modifiers. detectTapGestures consumes the DOWN
                    // event, which made the drag detector's slop/cancellation pass bail out, so
                    // slow finger drags were frequently dropped (measured: 0/6 slow drags vs
                    // 4/4 fast programmatic swipes). Merging both into one awaitEachGesture loop
                    // lets tap and drag cooperate. We only consume after the gesture is clearly
                    // horizontal, so the parent vertical scroll still works, and a non-tap,
                    // non-horizontal gesture (a scroll) deliberately does NOT seek on release.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = size.width.toFloat()
                            if (w <= 0f) return@awaitEachGesture
                            var dragging = false
                            var moved = 0f
                            // [V8.84] two-sample velocity used by the release settle
                            var lastX = down.position.x
                            var lastT = down.uptimeMillis
                            var lastV = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id }
                                if (ch == null) {
                                    if (dragging) isDragging = false
                                    return@awaitEachGesture
                                }
                                val dx = ch.position.x - down.position.x
                                val dy = ch.position.y - down.position.y
                                moved = maxOf(moved, abs(dx), abs(dy))
                                if (!dragging &&
                                    abs(dx) > viewConfiguration.touchSlop &&
                                    abs(dx) > abs(dy)
                                ) {
                                    dragging = true
                                    isDragging = true
                                }
                                if (dragging) {
                                    ch.consume()
                                    sliderValue = (ch.position.x / w).coerceIn(0f, 1f)
                                    val dt = ch.uptimeMillis - lastT
                                    if (dt > 0L) lastV = (ch.position.x - lastX) / (dt / 1000f)
                                    lastX = ch.position.x
                                    lastT = ch.uptimeMillis
                                }
                                if (!ch.pressed) {
                                    if (dragging) {
                                        latestOnSeekTo(sliderValue)
                                        isDragging = false
                                        // [V8.84] drag exit velocity -> damped settle
                                        val nudge = (lastV / w * 0.03f).coerceIn(-0.05f, 0.05f)
                                        if (nudge != 0f) {
                                            settleScope.launch {
                                                settleOffset.snapTo(nudge)
                                                settleOffset.animateTo(
                                                    0f,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.45f,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                )
                                            }
                                        }
                                    } else if (moved <= viewConfiguration.touchSlop) {
                                        // plain tap -> seek to the tapped position
                                        latestOnSeekTo(
                                            (down.position.x / w).coerceIn(0f, 1f)
                                        )
                                    }
                                    // else: vertical scroll / cancelled -> do not seek
                                    return@awaitEachGesture
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart
            ) {
                val barTopDp = (28.dp - trackHeight) / 2
                // 轨道底
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .offset(y = barTopDp)
                        .clip(RoundedCornerShape(trackHeight / 2))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                // [V8.84] settle wobble rides on top of the seek value
                val fillFrac = (sliderValue + settleOffset.value).coerceIn(0f, 1f)
                // 已播段（液态渐变）
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFrac)
                        .height(trackHeight)
                        .offset(y = barTopDp)
                        .clip(RoundedCornerShape(trackHeight / 2))
                        .background(
                            Brush.horizontalGradient(
                                0.0f to accentColor.copy(alpha = 0.55f),
                                0.55f to accentColor,
                                1.0f to lerp(accentColor, Color.White, 0.35f)
                            )
                        )
                ) {
                    // 流动高光斑（仅非拖动时）
                    if (!isDragging) {
                        val bandW = 60.dp
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val w = this.size.width
                            val h = this.size.height
                            if (w > bandW.toPx() * 1.5f) {
                                val span = w + bandW.toPx() * 2f
                                val cx = flowPhase.value * span - bandW.toPx()
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0.0f to Color.White.copy(alpha = 0f),
                                        0.5f to Color.White.copy(alpha = 0.35f),
                                        1.0f to Color.White.copy(alpha = 0f)
                                    ),
                                    topLeft = Offset(cx, 0f),
                                    size = Size(bandW.toPx(), h)
                                )
                            }
                        }
                    }
                }
                // 发光播放头（拖动放大）——精确居中于 fillFrac 位置
                val thumbScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.4f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "thumbScale"
                )
                val thumbBaseDp = thumbR * 2f   // Dp（thumbR 已是 Dp）
                val thumbDp = thumbBaseDp * thumbScale   // Dp * Float = Dp
                var barWidthPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .onSizeChanged { barWidthPx = it.width }
                ) {}
                // thumb 独立定位：x 从 0..(barWidth - thumbW) 映射 fillFrac，y 垂直居中
                val thumbWpx = with(LocalDensity.current) { thumbDp.toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            val maxX = (barWidthPx - thumbWpx).coerceAtLeast(0f)
                            IntOffset(
                                x = (maxX * fillFrac).roundToInt(),
                                y = (barTopDp.toPx() + (trackHeight.toPx() - thumbWpx) / 2f).roundToInt()
                            )
                        }
                        .size(thumbDp)
                        .graphicsLayer {
                            // [V8.111] 阴影形状跟随图层 outline：graphicsLayer 默认 shape 是矩形，
                            // 圆只是 background 画上去的，所以之前投出来是「方影」，圆头压不住。
                            // 显式给图层设 CircleShape（clip=false 不裁内容），阴影才随圆变圆柔影。
                            shape = CircleShape
                            clip = false
                            shadowElevation = 6f * thumbScale
                        }
                        .background(accentColor, CircleShape)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 【V8.186】两侧时间列固定等宽，规格列才能真正落在整行正中（否则时长比进度时间宽时
            // 中间列会整体左移，看着又偏右）。minWidth 兜住极端字体缩放，不挤压规格列。
            Text(
                formatDurationPlayer(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                textAlign = TextAlign.Start,
                modifier = Modifier.widthIn(min = 44.dp)
            )
            // 【V8.184/186】歌曲规格与「时长」同一水平行：在两侧时间列之间水平居中。
            // 时长本身不参与 weight，空间不足时只会被规格的 Ellipsis 挤压，时间永远不被压掉。
            if (specSlot != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { specSlot() }
            }
            Text(
                formatDurationPlayer(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .padding(start = if (specSlot != null) 6.dp else 0.dp)
            )
        }
    }
}

/** 液态涟漪 IconButton：按下时柔光圆从中心扩散（液态感），松手 spring 回弹。
 *  替代默认 ripple 的"实心圆扩散"，扩散圆带渐变边缘更像水滴涟漪。V8.37 */
@Composable
private fun LiquidIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        // [V8.44] livelier press response
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "liqPress"
    )
    // 涟漪扩散：按下 0→1，松手快速收尾
    val rippleProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isPressed) {
        if (isPressed) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                1f,
                animationSpec = tween(420, easing = FastOutSlowInEasing)
            )
        } else {
            // 松手快速淡出（从当前值快速收尾，不跳变）
            scope.launch {
                rippleProgress.animateTo(
                    0f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                )
            }
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 液态涟漪圆（按下扩散、松手收拢）
        Canvas(modifier = Modifier.matchParentSize()) {
            val rMax = size.minDimension / 2f
            if (rMax > 2f && rippleProgress.value > 0f) {
                val r = rMax * (0.35f + 0.65f * rippleProgress.value)
                // 扩散圆：外圈半透明 + 内圈渐隐 = 液态晕开
                val alpha = (1f - rippleProgress.value) * 0.28f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = alpha * 0.7f),
                            Color.White.copy(alpha = alpha * 0.25f),
                            Color.White.copy(alpha = 0f)
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
        content()
    }
}

/**
 * 走带区五个键（随机 / 上曲 / 播放 / 下曲 / 循环）。
 *
 * 尺寸与铺法全部取自 SDWTransport，四个布局不要再自己传 playButtonSize，
 * 否则竖屏和沉浸模式会逐渐长歪（历史上出现过 64 / 52 / 48 三种直径）。
 *
 * @param onInteract 播报「用户碰了走带区」，供自动隐藏逻辑刷新计时。
 */
/**
 * 【V8.380】DJ 模式走带：只有三个键 —— 播放 / 暂停 / 下一曲。
 *
 * 用户原话：「DJ模式的界面就是和那个我们的沉浸模式一样，只不过按键不一样，
 * 只需要播放键、暂停键和切歌下一曲三个案件」。
 *
 * 之前给 DJ 做了一整套独立界面（横屏卡片 / 竖屏卡片 / 彩虹频谱 / 整曲波形 /
 * 切片标记 / BPM 读数），用户反馈「太复杂」。DJ 的价值在**引擎自动混音**，
 * 界面只需要能播、能停、能切下一首 —— 决策信息不需要暴露给用户。
 *
 * 播放/暂停做成两个独立键（不是可切换的单键），按用户明确要求。
 */
@Composable
fun DjTransportBar(
    isPlaying: Boolean,
    accentColor: Color,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonSize: Dp = SDWTransport.Play,
    onInteract: () -> Unit = {}
) {
    val stepSize = SDWTransport.Step
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放
        LiquidIconButton(
            onClick = { onInteract(); onPlay() },
            modifier = Modifier.size(stepSize)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = if (isPlaying) accentColor else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        // 暂停
        LiquidIconButton(
            onClick = { onInteract(); onPause() },
            modifier = Modifier.size(stepSize)
        ) {
            Icon(
                Icons.Default.Pause,
                null,
                tint = if (!isPlaying) accentColor else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        // 下一曲
        LiquidIconButton(
            onClick = { onInteract(); onNext() },
            modifier = Modifier.size(stepSize)
        ) {
            Icon(
                Icons.Default.SkipNext,
                stringResource(R.string.player_next),
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun PlayerControlBar(
    shuffleEnabled: Boolean,
    repeatMode: Int,
    isPlaying: Boolean,
    accentColor: Color,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonSize: Dp = SDWTransport.Play,
    onInteract: () -> Unit = {}
) {
    val sideSize = SDWTransport.Side
    val stepSize = SDWTransport.Step
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidIconButton(onClick = { onInteract(); onToggleShuffle() }, modifier = Modifier.size(sideSize)) {
            Icon(
                if (shuffleEnabled) Icons.Default.ShuffleOn else Icons.Default.Shuffle,
                null,
                tint = if (shuffleEnabled) accentColor else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        LiquidIconButton(onClick = { onInteract(); onPrevious() }, modifier = Modifier.size(stepSize)) {
            Icon(Icons.Default.SkipPrevious, stringResource(R.string.player_previous), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.width(6.dp))
        // Play/Pause 标准切换 — isPlaying 现走 MusicService 权威状态（DAC/Oboe 真实值），
        // 不再依赖 ExoPlayer JNI 延迟，可以安全按状态切换图标
        val playInteraction = remember { MutableInteractionSource() }
        val isPressed by playInteraction.collectIsPressedAsState()
        // [V8.44] one driver for the whole play/pause morph: icon crossfade,
        // icon rotation and button scale can no longer drift apart.
        val playT = updateTransition(targetState = isPlaying, label = "playToggle")
        val pauseProgress by playT.animateFloat(
            transitionSpec = {
                spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "pauseProgress"
        ) { playing -> if (playing) 1f else 0f }
        val iconSpin by playT.animateFloat(
            transitionSpec = {
                spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "iconSpin"
        ) { playing -> if (playing) 0f else -90f }
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) 0.90f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "playPress"
        )
        FilledIconButton(
            onClick = { onInteract(); if (isPlaying) onPause() else onPlay() },
            interactionSource = playInteraction,
            modifier = Modifier
                .size(playButtonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accentColor,
                contentColor = MaterialTheme.colorScheme.background
            )
        ) {
            // [V8.44] both glyphs coexist and cross-fade off pauseProgress
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    modifier = Modifier
                        .size(playButtonSize * 0.45f)
                        .graphicsLayer {
                            alpha = 1f - pauseProgress
                            rotationZ = iconSpin + 90f
                        }
                )
                Icon(
                    Icons.Default.Pause,
                    contentDescription = stringResource(R.string.action_pause),
                    modifier = Modifier
                        .size(playButtonSize * 0.45f)
                        .graphicsLayer {
                            alpha = pauseProgress
                            rotationZ = iconSpin
                        }
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        LiquidIconButton(onClick = { onInteract(); onNext() }, modifier = Modifier.size(stepSize)) {
            Icon(Icons.Default.SkipNext, stringResource(R.string.player_next), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(36.dp))
        }
        LiquidIconButton(onClick = { onInteract(); onCycleRepeat() }, modifier = Modifier.size(sideSize)) {
            val (icon, tintColor) = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to accentColor
                Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to accentColor
                else -> Icons.Default.Repeat to Color.White
            }
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * 底部四键（歌词 / DSP / 收藏 / 分享）的空闲自动隐藏开关。
 *
 * 播放时手上多半是空的，这排按钮多数时候只是占着一块亮斑。做法是：任何交互（走带键、
 * 拖动进度、或者直接点这块区域）把计时归零；连续空闲满 delayMs 就把整排淡出，
 * 只留位置不留像素，所以上面的进度条和五键的行位置一动不动，不会「抖」。
 *
 * 淡出后加了一层吃点击的覆盖层：这一下的触摸只负责唤回，不会误触到下面的收藏/分享。
 * 淡出期间四个按钮同时 enabled=false，即使覆盖层没拦住也不会误动作。
 */
class BottomAutoHide(delayMs: Long) {
    /** 构造函数参数只在初始化表达式里可见，成员函数要用得另存一份。 */
    private val timeoutMs = delayMs
    private val visible = mutableStateOf(true)
    private var lastPokeAt = SystemClock.elapsedRealtime()

    /** 当前是否显示（只读，在 composable 里读 .value 才会参与重组）。 */
    val isVisible: Boolean get() = visible.value

    /** 松一拍：刷新计时，必要时立刻回到可见。 */
    fun poke() {
        lastPokeAt = SystemClock.elapsedRealtime()
        if (!visible.value) visible.value = true
    }

    /** 由 ticker 每 300ms 调一次，超时就淡出。 */
    fun tick() {
        if (visible.value && SystemClock.elapsedRealtime() - lastPokeAt > timeoutMs) {
            visible.value = false
        }
    }
}

@Composable
fun rememberBottomAutoHide(enabled: Boolean, delayMs: Long = 4500L): BottomAutoHide {
    val controller = remember(enabled) { BottomAutoHide(delayMs) }
    // 关掉开关的那一刻把人叫回来，否则四颗键就永久消失了
    LaunchedEffect(enabled) { controller.poke() }
    LaunchedEffect(enabled, delayMs) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            delay(300)
            controller.tick()
        }
    }
    return controller
}

/**
 * 底部四/五键。44dp 圆 + SpaceEvenly 铺满，与走带区同一套铺法逻辑。
 *
 * 【V8.212】onDjTrigger 非空时在 DSP 与收藏之间多一颗 ⚡ DJ 键：不等候选窗口/
 * 拍点对齐，点了当下就走一次 DJ 交叉切歌（与设置页「立即触发」同义，现场比对用）。
 * 被挡（总开关关/正在过渡/DAC 独占/暂停/单曲循环）时 Toast 说明原因。
 * 传 null（如歌曲列表迷你播放器）则不显示，布局退回四键。
 *
 * @param autoHide 传了就启用自动隐藏，null 则常驻显示。
 */
// 【V8.310】Beat Jump 键用 Box+combinedClickable（IconButton 无 onLongClick），
// combinedClickable 属 ExperimentalFoundationApi → 本函数需显式 OptIn。
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerBottomActions(
    eqEnabled: Boolean,
    isCurrentSongFavorite: Boolean,
    onNavigateToLyrics: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    autoHide: BottomAutoHide? = null,
    onDjTrigger: (() -> Boolean)? = null
) {
    val hidden = autoHide?.isVisible?.not() ?: false
    val alpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(220),
        label = "bottomActionsFade"
    )
    // stringResource 只能在 @Composable 上下文里取，click{} 里用预取好的字符串
    val context = androidx.compose.ui.platform.LocalContext.current
    val djBlockedMsg = stringResource(R.string.settings_dj_manual_trigger_blocked)
    val djNoServiceMsg = stringResource(R.string.dj_reason_service_not_connected)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().alpha(alpha),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { autoHide?.poke(); onNavigateToLyrics() },
                enabled = !hidden,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Default.Lyrics, stringResource(R.string.player_lyrics), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = { autoHide?.poke(); onToggleEqualizer() },
                enabled = !hidden,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Equalizer, stringResource(R.string.title_dsp_equalizer),
                    tint = if (eqEnabled) AccentRed else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { autoHide?.poke(); onToggleFavorite() },
                enabled = !hidden,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    if (isCurrentSongFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null,
                    tint = if (isCurrentSongFavorite) AccentRed else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 【V8.212】DJ 立即切歌键：与设置页「立即触发」同一入口 triggerCrossfadeNow()，
            // 被挡时 Toast 说明原因并落调试日志（同设置页的处理，方便现场排查）。
            if (onDjTrigger != null) {
                IconButton(
                    onClick = {
                        autoHide?.poke()
                        val ok = onDjTrigger.invoke()
                        if (!ok) {
                            // 【V8.271】带上服务端给出的具体原因（旧版只有一句「未触发」）
                            // 【V8.274】服务未连接时也给一句明确原因；文案走 stringResource
                            val svc = com.sdw.music.player.core.audio.playback.MusicService.instance
                            val reason = svc?.lastManualTriggerReason.orEmpty()
                                .ifEmpty { if (svc == null) djNoServiceMsg else "" }
                            // 【V8.304】「已排队」不是拒绝：单独一句肯定口吻。
                            // 原来一律套 settings_dj_manual_trigger_blocked（「未触发 —— 检查：
                            // 总开关 / 正在过渡 / …」），与 reason「已排队…」自相矛盾，
                            // 让用户以为点击丢了（实际过渡结束会自动补切）。
                            val queued = svc?.lastManualTriggerQueued == true
                            Toast.makeText(
                                context,
                                when {
                                    queued -> context.getString(R.string.dj_queued_toast)
                                    reason.isNotEmpty() ->
                                        context.getString(R.string.dj_blocked_reason_fmt, djBlockedMsg, reason)
                                    else -> djBlockedMsg
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                            DebugLog.add(
                                "Player",
                                context.getString(
                                    R.string.dj_manual_ignored,
                                    reason.ifEmpty { context.getString(R.string.dj_reason_unknown) }
                                )
                            )
                        }
                    },
                    enabled = !hidden,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Bolt,
                        stringResource(R.string.player_dj_trigger_now),
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // 【V8.310 Beat Jump】往后跳 1 小节。长按 → 往前跳 1 小节。
                // 落点由 service 吸附到小节线（拍中间跳过去会"打滑"）。
                // 用 Box+combinedClickable 而非 IconButton —— 本项目 Compose 版本的
                // IconButton 没有 onLongClick 参数。
                if (onDjTrigger != null) {
                    val jumpToast = {
                        Toast.makeText(context, R.string.beat_jump_unavailable, Toast.LENGTH_SHORT).show()
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .combinedClickable(
                                enabled = !hidden,
                                onClick = {
                                    autoHide?.poke()
                                    if (com.sdw.music.player.core.audio.playback.MusicService.instance?.beatJump(1) != true) jumpToast()
                                },
                                onLongClick = {
                                    autoHide?.poke()
                                    if (com.sdw.music.player.core.audio.playback.MusicService.instance?.beatJump(-1) != true) jumpToast()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            stringResource(R.string.beat_jump),
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = { autoHide?.poke(); onShare() },
                enabled = !hidden,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Default.Share, stringResource(R.string.action_share), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        // 淡出后盖一层只吃点击不干别的：这一下只唤回，不会误触到下面的按钮。
        // 放在 Row 之后，Box 里后加入的子节点命中优先级更高，所以它会先于按钮被选中。
        if (hidden) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { autoHide?.poke() }
                    )
            )
        }
    }
}

/** EQ preset label chip (centered, shown above progress bar). */
@Composable
fun PlayerEqLabel(
    eqPresetName: String?,
    accentColor: Color,
    textAccentColor: Color,
    onClick: (() -> Unit)? = null,
    // [V8.71] hidden in immersive player mode
    visible: Boolean = true
) {
    if (eqPresetName != null && visible) {
        // 【V8.21】去边框/背景/指示灯 → 纯文字胶囊（与 DTS 一致，同排不杂乱）。
        // 带笔形小图标表示可点击进入 MSEB 设置。
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = eqPresetName,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = textAccentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // [V8.71] Gear icon removed: the whole label is already clickable
            // (see the Modifier.clickable above), so the icon was redundant clutter.
        }
    }
}

/** 【V8.19】DTS 环绕（M/S 声场）快捷开关胶囊 — 独立于 MSEB 其他调音 */
@Composable
fun PlayerDtsToggle(
    dtsOn: Boolean,
    accentColor: Color,
    textAccentColor: Color,
    onClick: () -> Unit
) {
    val shape = SDWShapes.Pill
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(shape)
            .background(
                if (dtsOn) accentColor.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // 【V8.20】去掉指示灯圆点（视觉更简洁）
        Text(
            text = stringResource(if (dtsOn) R.string.dts_on else R.string.dts_off),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = if (dtsOn) textAccentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 【V8.7】三行歌词：封面下、频谱表上。上一行/当前行(高亮)/下一行。 */
@Composable
fun InlineLyric3(
    lyricsLines: List<com.sdw.music.player.LyricLine>,
    currentIdx: Int,
    highlight: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    // [V8.126] 点歌词进全屏歌词页。null = 不可点（保持原行为）。
    onClick: (() -> Unit)? = null,
    // [V8.135] 播放界面跟随全屏歌词页的「逐字开关」：开启后当前行按词渐亮。
    wordByWord: Boolean = false,
    positionMs: Long = 0L,
    isPlaying: Boolean = false
) {
    // [V8.135] 帧级歌词时钟（仅叶子组件读取其 value，故每帧重组仅限当前行）。
    val wClock = rememberPlayerLyricClock(positionMs, isPlaying && wordByWord)
    val prevLine = if (currentIdx - 1 >= 0) lyricsLines.getOrNull(currentIdx - 1)?.text else null
    val curLine = lyricsLines.getOrNull(currentIdx)?.text
    val nextLine = if (currentIdx >= 0 && currentIdx + 1 < lyricsLines.size) lyricsLines.getOrNull(currentIdx + 1)?.text else null
    val noLyrics = curLine.isNullOrBlank() && nextLine.isNullOrBlank() && prevLine.isNullOrBlank()

    // V8.58 three-tier emphasis. Previously this block showed only the current line and
    // the next one, both similar in weight, which read as flat next to the full-screen
    // lyric view. Now: the current line is the largest and carries a soft accent glow,
    // while the neighbours recede in both size and alpha so the eye is always pulled to
    // the right place. Height is fixed so the surrounding layout never reflows.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!prevLine.isNullOrBlank()) {
            Text(
                text = prevLine,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 4f)
                ),
                color = color.copy(alpha = 0.30f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            )
        }
        if (!curLine.isNullOrBlank()) {
            AnimatedContent(
                targetState = curLine,
                transitionSpec = {
                    // V8.44 vertical slide springs, opacity tween
                    (slideInVertically(
                        animationSpec = spring(
                            dampingRatio = 0.72f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { it / 3 } +
                        fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            slideOutVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) { -it / 4 } +
                                fadeOut(animationSpec = tween(180))
                        )
                },
                label = "lyricFloat"
            ) { line ->
                Box(contentAlignment = Alignment.Center) {
                    // V8.64 - halo layer must be SHADOW ONLY.
                    // It used to paint the same glyphs a second time, at 18sp against the
                    // 17sp main line and at alpha 0.22. Two centred copies at different sizes
                    // never overlap exactly, so the active line showed a doubled, smeared
                    // edge (the "ghosting" behind the text). Drawing the glyphs transparent
                    // leaves just the wide accent shadow as a halo, and the size/lineHeight
                    // now match the main line exactly so the two cannot drift apart.
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 17.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                            shadow = Shadow(
                                color = color.copy(alpha = 0.55f),
                                offset = Offset.Zero,
                                blurRadius = 20f
                            )
                        ),
                        color = Color.Transparent,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    if (wordByWord && currentIdx in lyricsLines.indices) {
                        // [V8.135] 逐字高亮：当前行按词渐亮，与全屏歌词页同一算法、同一开关。
                        // 帧级时钟只在本行内部读取，每帧重组仅限这一行（父级不读该 State）。
                        InlineWordHighlight(
                            line = lyricsLines[currentIdx],
                            lineEndMs = lyricsLines.getOrNull(currentIdx + 1)?.timeMs ?: Long.MAX_VALUE,
                            clock = wClock,
                            color = color,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 17.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium,
                                shadow = Shadow(color = Color.Black.copy(alpha = 0.70f), offset = Offset(0f, 1f), blurRadius = 5f)
                            ),
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    } else {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 17.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium,
                                shadow = Shadow(color = Color.Black.copy(alpha = 0.70f), offset = Offset(0f, 1f), blurRadius = 5f)
                            ),
                            color = if (highlight) color else color.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
        if (!nextLine.isNullOrBlank()) {
            Text(
                text = nextLine,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 4f)
                ),
                color = color.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            )
        }
    }
}

/** Inline lyric line (shown below artist info in non-foldable layouts). */
@Composable
fun PlayerInlineLyric(
    lyricLine: String?,
    color: Color,
    modifier: Modifier = Modifier,
    // [V8.126] 点歌词进全屏歌词页。null = 不可点（保持原行为）。
    onClick: (() -> Unit)? = null
) {
    // 【V8.20】固定单行高度（约 1 行），超长省略号截断，杜绝歌词换行导致布局跳动
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (!lyricLine.isNullOrBlank()) {
            MarqueeText(
                text = lyricLine,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f
                    )
                ),
                color = color
            )
        }
    }
}

// ============================================================================
// [V8.135] 播放界面歌词「逐字高亮」
// 与全屏歌词页共用同一偏好（sdw_music_prefs / lyric_word_by_word）与同一淡入算法
// （buildWordSegments + wordByWordAlpha），两处观感一致。
// 设计要点：帧级时钟只在叶子组件内部读取，每帧重组仅限这一行歌词，
// 不会带动整页播放界面重组。
// ============================================================================

/**
 * 播放界面逐字单行歌词（Analog 模式单行 / 横屏单行）。
 * 开关关闭或调用方不启用时，仍走原 [PlayerInlineLyric]（跑马灯）路径，互不影响。
 */
@Composable
fun InlineLyricWord(
    line: LyricLine?,
    lineEndMs: Long,
    positionMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    isPlaying: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clock = rememberPlayerLyricClock(positionMs, isPlaying)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (line != null && line.text.isNotBlank()) {
            InlineWordHighlight(
                line = line,
                lineEndMs = lineEndMs,
                clock = clock,
                color = color,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f
                    )
                ),
                maxLines = maxLines
            )
        }
    }
}

/**
 * 逐字高亮文本本体：每个词从自己的起始时刻起在 WORD_FADE_MS 内由 0.35 渐亮到 1.0。
 * 无逐字时间戳的普通 LRC 行由 [buildWordSegments] 按行时长插值兜底（与全屏歌词页一致）。
 * 只读取 [clock]，因此每帧重组仅限本组件。
 */
@Composable
fun InlineWordHighlight(
    line: LyricLine,
    lineEndMs: Long,
    clock: State<Long>,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    val segments = remember(line, lineEndMs) { buildWordSegments(line, lineEndMs) }
    val pos = clock.value
    val annotated = buildAnnotatedString {
        for ((segText, onset) in segments) {
            withStyle(SpanStyle(color = color.copy(alpha = wordByWordAlpha(pos, onset)))) {
                append(segText)
            }
        }
    }
    Text(
        text = annotated,
        style = style,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * V8.135 帧级歌词时钟：上游 positionMs 约 1 秒才更新一次，直接拿它判「逐字亮到哪」
 * 会一秒一跳。这里以每个上游值为锚点、用帧时钟外推；大跳（seek/切歌）直接重锚，
 * 小偏差取 max(上游值, 已显示值) 单调续走（既保持不倒退，又持续前进）。
 * 与全屏歌词页同款：仅在播放中推进，暂停即冻结在当前词（不会继续「超前点亮」）。
 */
@Composable
private fun rememberPlayerLyricClock(anchorMs: Long, running: Boolean): State<Long> {
    val holder = remember { PlayerLyricClockHolder() }
    LaunchedEffect(anchorMs) { holder.onAnchor(anchorMs) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            holder.advance()
        }
    }
    return holder.state
}

private class PlayerLyricClockHolder {
    // 初值取锚点值，保证首帧就有内容（全屏页同款 holder 也是 mutableStateOf(0L) 但依赖 LaunchedEffect）。
    val state = mutableStateOf(0L)
    private var base = 0L
    private var anchorAt = 0L
    private var lastShown = 0L
    private var started = false

    fun onAnchor(ms: Long) {
        val now = SystemClock.elapsedRealtime()
        if (!started) {
            started = true
            base = ms; anchorAt = now; lastShown = ms; state.value = ms
            return
        }
        val estimate = base + (now - anchorAt)
        if (abs(ms - estimate) > 1500L) {
            // 大跳（seek / 切歌 / 长时间卡顿）：直接重锚。
            base = ms; anchorAt = now; lastShown = ms; state.value = ms
        } else {
            val keep = maxOf(ms, lastShown)
            base = keep; anchorAt = now
            if (keep > lastShown) { lastShown = keep; state.value = keep }
        }
    }

    fun advance() {
        if (!started) return
        val v = base + (SystemClock.elapsedRealtime() - anchorAt)
        if (v > lastShown) { lastShown = v; state.value = v }
    }
}

/** Duration formatter for PlayerScreen. */
internal fun formatDurationPlayer(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    else "%d:%02d".format(minutes, seconds)
}


// ============================================================================
// V3.3.4: DAC info capsule bar - DAC model | 48kHz / 24bit | Hi-Res gold badge
// Shown only in USB DAC exclusive mode (auto-hides when claim absent)
// ============================================================================


/** 【V8.21】跑马灯文本：单行不换行；文字超过可用宽度时，
 *  静止约 1.2s 后缓慢左滚至句尾停住（直到文字变化重置），完整可读不截断。
 *  不超长时静止居中；固定高度不影响布局。
 */
@Composable
fun MarqueeText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
    marqueeDurationMs: Int = 6000
) {
    var textW by remember { mutableStateOf(0) }
    var viewW by remember { mutableStateOf(0) }
    val overflow = viewW > 0 && textW > viewW
    val dist = (textW - viewW + 24f).coerceAtLeast(1f)

    val anim = remember(text) { Animatable(0f) }
    LaunchedEffect(text, overflow, dist) {
        if (overflow) {
            delay(1200)
            anim.animateTo(
                targetValue = -dist,
                animationSpec = tween(durationMillis = marqueeDurationMs, easing = LinearEasing)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewW = it.width },
        contentAlignment = if (overflow) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { result ->
                if (result.hasVisualOverflow) {
                    if (textW != result.size.width) textW = result.size.width
                } else if (textW != 0) textW = 0
            },
            modifier = Modifier.graphicsLayer {
                translationX = if (overflow) anim.value else 0f
            }
        )
    }
}


/** 【V8.21】歌曲规格分段着色标签：FLAC · 24bit · 96kHz · 4609kbps
 *  HiRes（≥24bit 或 ≥88.2kHz）：全段金色，格式加粗；
 *  普通：格式名 accent 色，参数 onSurfaceVariant，HiRes 徽标感不另加框。
 */
@Composable
fun SongSpecLabel(
    spec: com.sdw.music.player.core.audio.metadata.SongSpecReader.Spec?,
    accentColor: Color,
    textAccentColor: Color,
    modifier: Modifier = Modifier
) {
    if (spec == null) return
    // V8.85 - the plain-quality param segment used to be onSurfaceVariant, a fixed
    // mid grey (#8E8E93 in the dark scheme) that tracks the theme rather than the
    // artwork behind it, so at 11sp it washed out over bright covers. It is now a
    // bright neutral, and the whole label carries a shadow so it also survives busy
    // backgrounds (the same treatment the artist line already had).
    // Gold was #FFD54F (pale Material yellow); #E8C875 is the deeper amber the
    // project already uses for its WAV tag, so the two golds now agree.
    val gold = Color(0xFFE8C875)
    val hiRes = spec.isHiRes
    val paramColor = if (hiRes) gold
        else Color.White.copy(alpha = 0.80f)
    val fmtColor = if (hiRes) gold else textAccentColor
    val sep = " · "
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = fmtColor, fontWeight = if (hiRes) FontWeight.Bold else FontWeight.Medium)) {
            append(spec.format)
        }
        if (spec.bitDepth > 0) {
            withStyle(SpanStyle(color = paramColor)) { append(sep + "${spec.bitDepth}bit") }
        }
        if (spec.sampleRate > 0) {
            withStyle(SpanStyle(color = paramColor)) { append(sep + "${spec.sampleRate / 1000.0}kHz") }
        }
        if (spec.bitrate > 0) {
            withStyle(SpanStyle(color = paramColor)) { append(sep + "${spec.bitrate}kbps") }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                offset = Offset(0f, 0f),
                blurRadius = 6f
            )
        ),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun DacInfoBar(
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    onClick: (() -> Unit)? = null,
    // [V8.71] hidden in immersive player mode
    show: Boolean = true,
    modifier: Modifier = Modifier
) {
    var dacName by remember { mutableStateOf("") }
    var sr by remember { mutableIntStateOf(0) }
    var bits by remember { mutableIntStateOf(0) }
    var diagCount by remember { mutableIntStateOf(0) }
    
    // [V3.3.6] 只在 DAC 激活时轮询，避免主线程卡顿
    LaunchedEffect(Unit) {
        while (true) {
            val claimed = try { com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
            if (claimed) {
                dacName = com.sdw.music.player.core.audio.engine.UsbDacManager.getDacDisplayName()
                sr = com.sdw.music.player.core.audio.engine.UsbDacManager.queryActiveSampleRate()
                bits = com.sdw.music.player.core.audio.engine.UsbDacManager.queryActiveBits()
            } else { 
                dacName = ""; sr = 0; bits = 0
            }
            if (diagCount % 5 == 0) {
                android.util.Log.d("DacInfoBar", "claimed=$claimed dacName='$dacName' sr=$sr bits=$bits")
            }
            diagCount++
            // DAC 未激活时降低轮询频率（5秒）
            kotlinx.coroutines.delay(if (claimed) 1000L else 5000L)
        }
    }
    val dacBarVisible = dacName.isNotEmpty() && sr > 0

    val hiRes = sr >= 88_200 || bits >= 24
    val srText = if (sr % 1000 == 0) "${sr / 1000}kHz" else String.format("%.1fkHz", sr / 1000f)

    val infinite = rememberInfiniteTransition(label = "dacDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dacDotA"
    )
    val dotAlpha = if (isPlaying) pulse else 1f

    AnimatedVisibility(
        visible = dacBarVisible && show,
        modifier = modifier,
        // V8.58 size is spatial -> spring; opacity stays time-based.
        enter = fadeIn(tween(220)) + expandVertically(Motion.sizeSpatial()),
        exit = fadeOut(tween(180)) + shrinkVertically(Motion.sizeSnappy())
    ) {
    Row(
        modifier = Modifier
            .clip(SDWShapes.Pill)
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), SDWShapes.Pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(textAccentColor.copy(alpha = dotAlpha), CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            dacName,
            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = textAccentColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
        DacInfoDivider(textAccentColor)
        Text(
            "$srText / ${bits}bit",
            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = textAccentColor,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
        if (hiRes) {
            DacInfoDivider(textAccentColor)
            Box(
                Modifier
                    .clip(SDWShapes.Badge)
                    .background(Brush.verticalGradient(listOf(Color(0xFFF5D061), Color(0xFFC9A227))))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    stringResource(R.string.player_hires),
                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFF1A1200),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.3f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }
    }
}

@Composable
fun OutputDeviceBar(
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    // [V8.71] hidden in immersive player mode
    show: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var btName by remember { mutableStateOf("") }
    var btActive by remember { mutableStateOf(false) }

    // 轮询蓝牙输出状态（无 DAC 时才显示，频率低不影响性能）
    LaunchedEffect(Unit) {
        while (true) {
            // DAC 独占激活时蓝牙不可能同时输出，直接跳过
            val dacClaimed = try {
                com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed()
            } catch (_: Throwable) { false }
            if (!dacClaimed) {
                btActive = com.sdw.music.player.util.BluetoothOutput.isBluetoothActive(context)
                if (btActive) {
                    btName = com.sdw.music.player.util.BluetoothOutput.getActiveBluetoothName(context)
                } else {
                    btName = ""
                }
            } else {
                btActive = false
                btName = ""
            }
            android.util.Log.d("OutputDeviceBar", "dacClaimed=$dacClaimed btActive=$btActive btName='$btName'")
            kotlinx.coroutines.delay(2000L)
        }
    }

    // 无蓝牙输出 → 不显示
    val btBarVisible = btActive && btName.isNotEmpty()

    val infinite = rememberInfiniteTransition(label = "btDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "btDotA"
    )
    val dotAlpha = if (isPlaying) pulse else 1f

    AnimatedVisibility(
        visible = btBarVisible && show,
        modifier = modifier,
        // V8.58 size is spatial -> spring; opacity stays time-based.
        enter = fadeIn(tween(220)) + expandVertically(Motion.sizeSpatial()),
        exit = fadeOut(tween(180)) + shrinkVertically(Motion.sizeSnappy())
    ) {
    Row(
        modifier = Modifier
            .clip(SDWShapes.Pill)
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), SDWShapes.Pill)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(textAccentColor.copy(alpha = dotAlpha), CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            btName,
            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = textAccentColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
    }
    }
}

@Composable
private fun DacInfoDivider(color: Color) {
    Spacer(Modifier.width(9.dp))
    Box(Modifier.width(1.dp).height(10.dp).background(color.copy(alpha = 0.2f)))
    Spacer(Modifier.width(9.dp))
}

// ============================================================================
// Queue Sheet - ModalBottomSheet showing the full play queue
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    queue: List<Song>,
    currentSongId: Long,
    accentColor: Color,
    onSongClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onFlowSort: (() -> Unit)? = null,
    upNext: List<Pair<Song, String>> = emptyList(),
    modifier: Modifier = Modifier
) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bgColor = MaterialTheme.colorScheme.surface
    var longPressSong by remember { mutableStateOf<com.sdw.music.player.Song?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bgColor,
        contentColor = MaterialTheme.colorScheme.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // [V8.117] 去掉顶部 36dp 拖拽小短条（用户要求）；下滑关闭不受影响。
        dragHandle = null
    ) {
        // [V8.117] 面板跑在独立 Dialog 窗口，播放页的沉浸式对它无效 —— 单独隐藏状态栏。
        SheetImmersiveStatusBar()
        // 【V8.252】状态栏留白：状态栏隐藏失败/被系统强制显示（如从顶部下滑）时，
        // 头部的「N songs」计数不再被遮挡；状态栏成功隐藏时该 padding 为 0，无视觉变化。
        Column(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.title_play_queue), color = MaterialTheme.colorScheme.onBackground)
                }
                Text("${queue.size} songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 【V8.246】队列级自动流程排序（Rekordbox Auto-Flow）：以当前曲为锚按能量/调性重排
                if (onFlowSort != null) {
                    IconButton(onClick = onFlowSort, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.action_flow_sort_queue),
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 【V8.250】Automix 预览：接下来怎么接（歌 + 理由），djay 式
            if (upNext.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(
                        stringResource(R.string.queue_up_next_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor
                    )
                    Spacer(Modifier.height(4.dp))
                    upNext.forEachIndexed { i, (song, reason) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = accentColor.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // 【V8.5】打开队列自动定位到当前播放歌曲，不用手动滑动查找
            val listState = rememberLazyListState()
            val currentIndex = remember(queue, currentSongId) {
                queue.indexOfFirst { it.id == currentSongId }
            }
            LaunchedEffect(currentIndex, currentSongId) {
                if (currentIndex >= 0) {
                    listState.scrollToItem(currentIndex)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 32.dp)
            ) {
                itemsIndexed(queue) { idx, song ->
                    val isCurrent = song.id == currentSongId
                    val itemMod = Modifier.animateItemPlacement()
                    Row(
                        modifier = itemMod
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) accentColor.copy(alpha = 0.08f) else Color.Transparent
                            )
                                                        .combinedClickable(
                                onClick = {
                                    onSongClick(idx)
                                    onDismiss()
                                },
                                onLongClick = { longPressSong = song }
                            )
                            .padding(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left accent bar for current song
                        if (isCurrent) {
                            Spacer(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(48.dp)
                                    .background(
                                        accentColor,
                                        RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
                                    )
                            )
                        } else {
                            Spacer(Modifier.width(3.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        // Album art thumbnail
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(SDWShapes.Thumb),
                            contentAlignment = Alignment.Center
                        ) {
                            // [V8.93] 打底 + 无条件覆盖：扫描器给每行都写了非空 albumArtUri
                            // （无封面时是 albumart 虚拟表死链接），原先 isNotBlank() 恒真、
                            // else 里的默认 logo 永远走不到 —— 真封面解码失败后队列缩略图
                            // 就是一片空白（只剩 accent 0.06 的极浅底色）。
                            DefaultCoverImage(
                                songTitle = song.title,
                                songArtist = song.artist,
                                modifier = Modifier.fillMaxSize(),
                                shape = SDWShapes.Thumb,
                                tintColor = accentColor
                            )
                            // V8.66 list tier: 40dp queue thumb.
                            val painter = rememberSharedCoverPainter(song.albumArtUri)
                            Image(
                                painter = painter,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) accentColor else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                song.artist.ifBlank { stringResource(R.string.unknown_artist) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isCurrent) 0.7f else 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isCurrent) {
                            val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 0.4f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = null,
                                tint = accentColor.copy(alpha = pulseAlpha),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    // 【V8.7】长按添加歌单
    longPressSong?.let { song ->
        AddToPlaylistSheet(
            song = song,
            onDismiss = { longPressSong = null }
        )
    }

}
