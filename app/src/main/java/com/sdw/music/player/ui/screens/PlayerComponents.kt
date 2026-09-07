package com.sdw.music.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import com.sdw.music.player.Song
import com.sdw.music.player.splitArtists
import com.sdw.music.player.R
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.exp

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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
        }
        Text("", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
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
                        text = { Text(stringResource(R.string.player_delete_song), color = Color.Red) },
                        onClick = { onDismissMenu(); onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
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
    // 【V8.5】crossfade 交接期间换碟瞬时完成（不做 350ms 换碟动画，B 已通过叠加层全显，避免“变两次”跳变）
    instantCoverSwitch: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(glowSizeDp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { onClick() }
                )
            }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (total < -100f) onSwipeNext()
                        else if (total > 100f) onSwipePrevious()
                        total = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        total += dragAmount
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Glow ring behind the art
        Box(
            modifier = Modifier
                .size(glowSizeDp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.08f))
        )
        // 始终旋转（角度连续累积，暂停/切歌不归零），换碟动画发生在旋转层内部
        Box(
            modifier = Modifier
                .size(sizeDp)
                .rotate(rotation)
        ) {
            if (artUri.isNullOrEmpty()) {
                DefaultCoverImage(songTitle, songArtist, Modifier.size(sizeDp))
            } else {
                // CD 换碟：旧碟缩小淡出、新碟放大淡入（绕中心缩放，旋转无关，方向稳定）
                // crossfade 交接期间（instantCoverSwitch=true）瞬时完成，不做换碟动画
                AnimatedContent(
                    targetState = artUri,
                    transitionSpec = {
                        if (instantCoverSwitch) {
                            (fadeIn(tween(0)) + scaleIn(tween(0), initialScale = 1f))
                                .togetherWith(
                                    fadeOut(tween(0)) + scaleOut(tween(0), targetScale = 1f)
                                )
                        } else {
                            // 【V8.5】换碟改为交叉淡化：旧碟淡出同时新碟淡入（无空窗、无缩放跳变），
                            // 观感更接近“两张封面平滑过渡”，而非旧碟缩走再放新碟
                            (fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                                    scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.92f))
                                .togetherWith(
                                    fadeOut(tween(320, easing = FastOutSlowInEasing)) +
                                            scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = 1.02f)
                                )
                        }
                    },
                    label = "coverSwitch"
                ) { uri ->
                    Box(modifier = Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
                        DefaultCoverImage(songTitle, songArtist, Modifier.size(sizeDp))
                        val coverPainter = rememberAsyncImagePainter(
                            model = uri,
                            contentScale = ContentScale.Crop
                        )
                        Image(
                            painter = coverPainter,
                            contentDescription = stringResource(R.string.player_album_art),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(sizeDp)
                                .clip(CircleShape)
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
                val overlayAlpha = remember(fadeOverlayUri) { Animatable(0f) }
                LaunchedEffect(fadeOverlayUri) {
                    overlayAlpha.animateTo(
                        1f,
                        animationSpec = tween(fadeOverlayDurationMs.coerceIn(1000, 15000), easing = LinearEasing)
                    )
                }
                Box(modifier = Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
                    val overlayPainter = rememberAsyncImagePainter(
                        model = fadeOverlayUri,
                        contentScale = ContentScale.Crop
                    )
                    Image(
                        painter = overlayPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(sizeDp)
                            .clip(CircleShape),
                        alpha = overlayAlpha.value
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
        if (format.isNotEmpty()) {
            Surface(
                modifier = Modifier.padding(start = 8.dp),
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Text(
                    format.uppercase(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(shadow = artistShadow),
                    color = Color.White
                )
            }
        }
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
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
        color = MaterialTheme.colorScheme.onBackground,
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
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(progressFraction) }
    var isDragging by remember { mutableStateOf(false) }

    // Sync external progress into slider when not dragging
    LaunchedEffect(progressFraction) {
        if (!isDragging) {
            sliderValue = progressFraction.coerceIn(0f, 1f)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (durationMs > 0) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    isDragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onSeekTo(sliderValue)
                    isDragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDurationPlayer(positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f))
            Text(formatDurationPlayer(durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f))
        }
    }
}

/** Playback control bar: shuffle, prev, play, pause, next, repeat. */
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
    playButtonSize: androidx.compose.ui.unit.Dp = 64.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                if (shuffleEnabled) Icons.Default.ShuffleOn else Icons.Default.Shuffle,
                null,
                tint = if (shuffleEnabled) accentColor else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.width(6.dp))
        // Play/Pause 标准切换 — isPlaying 现走 MusicService 权威状态（DAC/Oboe 真实值），
        // 不再依赖 ExoPlayer JNI 延迟，可以安全按状态切换图标
        val playInteraction = remember { MutableInteractionSource() }
        val isPressed by playInteraction.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) 0.90f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "playPress"
        )
        FilledIconButton(
            onClick = { if (isPlaying) onPause() else onPlay() },
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
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                modifier = Modifier.size(playButtonSize * 0.45f)
            )
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = onCycleRepeat) {
            val (icon, tintColor) = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to accentColor
                Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to accentColor
                else -> Icons.Default.Repeat to Color.White
            }
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        }
    }
}

/** Bottom action bar: lyrics, equalizer, favorite, share. */
@Composable
fun PlayerBottomActions(
    eqEnabled: Boolean,
    isCurrentSongFavorite: Boolean,
    onNavigateToLyrics: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    // EQ always active - Oboe native DSP supports full EQ
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(
            onClick = onNavigateToLyrics,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(Icons.Default.Lyrics, null, tint = Color.White)
        }
        IconButton(
            onClick = onToggleEqualizer,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(
                Icons.Default.Equalizer, null,
                tint = if (eqEnabled) AccentRed else Color.White
            )
        }
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(
                if (isCurrentSongFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                null,
                tint = if (isCurrentSongFavorite) AccentRed else Color.White
            )
        }
        IconButton(
            onClick = onShare,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(Icons.Default.Share, null, tint = Color.White)
        }
    }
}

/** EQ preset label chip (centered, shown above progress bar). */
@Composable
fun PlayerEqLabel(
    eqPresetName: String?,
    accentColor: Color,
    textAccentColor: Color,
    onClick: (() -> Unit)? = null
) {
    if (eqPresetName != null) {
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
            if (onClick != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = textAccentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/** 【V8.19】DTS 环绕（M/S 声场）快捷开关胶囊 — 独立于 MSEB 其他调音 */
@Composable
fun PlayerDtsToggle(
    dtsOn: Boolean,
    accentColor: Color,
    textAccentColor: Color,
    onClick: () -> Unit,
    onNavigateToMseb: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(50)
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
        if (onNavigateToMseb != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = if (dtsOn) textAccentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp).clickable(onClick = onNavigateToMseb)
            )
        }
    }
}

/** 【V8.7】三行歌词：封面下、频谱表上。上一行/当前行(高亮)/下一行。 */
@Composable
fun InlineLyric3(
    lyricsLines: List<com.sdw.music.player.LyricLine>,
    currentIdx: Int,
    highlight: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val curLine = lyricsLines.getOrNull(currentIdx)?.text
    val nextLine = if (currentIdx >= 0 && currentIdx + 1 < lyricsLines.size) lyricsLines.getOrNull(currentIdx + 1)?.text else null
    val noLyrics = curLine.isNullOrBlank() && nextLine.isNullOrBlank()

    // 【V8.22】当前行自动折行最多2行+下一句1行：长句完整可读，固定 64dp 不跳动
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 当前行（高亮：accent 色 + 略大；长句自动折行，最多2行，超长省略）
        if (!curLine.isNullOrBlank()) {
            Text(
                text = curLine,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 1f), blurRadius = 6f)
                ),
                color = if (highlight) color else color.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
        // 下一句（1行，超长省略）
        if (!nextLine.isNullOrBlank()) {
            Text(
                text = nextLine,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 4f)
                ),
                color = color.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
        }
    }
}

/** Inline lyric line (shown below artist info in non-foldable layouts). */
@Composable
fun PlayerInlineLyric(
    lyricLine: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    // 【V8.20】固定单行高度（约 1 行），超长省略号截断，杜绝歌词换行导致布局跳动
    Box(
        modifier = modifier.fillMaxWidth().height(28.dp),
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
    spec: com.sdw.music.player.core.audio.SongSpecReader.Spec?,
    accentColor: Color,
    textAccentColor: Color,
    modifier: Modifier = Modifier
) {
    if (spec == null) return
    val gold = Color(0xFFFFD54F)
    val hiRes = spec.isHiRes
    val paramColor = if (hiRes) gold
        else MaterialTheme.colorScheme.onSurfaceVariant
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
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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
    modifier: Modifier = Modifier
) {
    var dacName by remember { mutableStateOf("") }
    var sr by remember { mutableIntStateOf(0) }
    var bits by remember { mutableIntStateOf(0) }
    var diagCount by remember { mutableIntStateOf(0) }
    
    // [V3.3.6] 只在 DAC 激活时轮询，避免主线程卡顿
    LaunchedEffect(Unit) {
        while (true) {
            val claimed = try { com.sdw.music.player.core.audio.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
            if (claimed) {
                dacName = com.sdw.music.player.core.audio.UsbDacManager.getDacDisplayName()
                sr = com.sdw.music.player.core.audio.UsbDacManager.queryActiveSampleRate()
                bits = com.sdw.music.player.core.audio.UsbDacManager.queryActiveBits()
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
    if (dacName.isEmpty() || sr <= 0) return

    val hiRes = sr >= 88_200 || bits >= 24
    val srText = if (sr % 1000 == 0) "${sr / 1000}kHz" else String.format("%.1fkHz", sr / 1000f)

    val infinite = rememberInfiniteTransition(label = "dacDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dacDotA"
    )
    val dotAlpha = if (isPlaying) pulse else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(50))
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
                    .clip(RoundedCornerShape(3.dp))
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

@Composable
fun OutputDeviceBar(
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
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
                com.sdw.music.player.core.audio.UsbDacManager.isClaimed()
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
    if (!btActive || btName.isEmpty()) return

    val infinite = rememberInfiniteTransition(label = "btDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "btDotA"
    )
    val dotAlpha = if (isPlaying) pulse else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(50))
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
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor.copy(alpha = 0.35f))
            )
        }
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
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
                    Text(stringResource(R.string.title_play_queue), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                }
                Text("${queue.size} songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
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
                    Row(
                        modifier = Modifier
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (song.albumArtUri.isNotBlank()) {
                                val painter = rememberAsyncImagePainter(
                                    model = song.albumArtUri,
                                    contentScale = ContentScale.Crop
                                )
                                Image(
                                    painter = painter,
                                    contentDescription = song.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                DefaultCoverImage(
                                    songTitle = song.title,
                                    songArtist = song.artist,
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(6.dp)
                                )
                            }
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
                                song.artist,
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
