package com.sdw.music.player.ui.screens

import androidx.compose.animation.animateColorAsState
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.sdw.music.player.ui.components.NowPlayingBars
import com.sdw.music.player.ui.components.NowPlayingHalo
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.sdw.music.player.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.res.painterResource
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.sharedCoverArt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.ui.theme.*
import com.sdw.music.player.ui.theme.LocalWallpaperEffectiveLight
import androidx.media3.common.Player
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sdw.music.player.core.audio.SongSpecReader
import androidx.compose.ui.platform.LocalContext

import com.sdw.music.player.MusicService
import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.BpmBadge
import com.sdw.music.player.ui.components.rememberSharedCoverPainter


// V8.62/V8.63 - "Go to top" and "go to letter" must SNAP, never animate.
// LazyListState.animateScrollToItem() is not a jump: it walks the list in chained
// 2500dp springs and teleports only in 100-item hops, so it visibly crawls even
// over a short distance. scrollToItem() is the instant jump - always use it here.

/** V8.47 - Library sort menu: Title (A-Z) / Recently added / File name / Duration. */
@Composable
private fun SortMenuButton(
    sortMode: String,
    onSortModeChange: (String) -> Unit,
    tint: Color
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, stringResource(R.string.songlist_sort_by), tint = tint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "title" to R.string.songlist_sort_title,
                "added" to R.string.songlist_sort_added,
                "filename" to R.string.songlist_sort_filename,
                "duration" to R.string.songlist_sort_duration
            ).forEach { (mode, labelRes) ->
                val selected = sortMode == mode
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    leadingIcon = {
                        Icon(
                            if (selected) Icons.Default.Check else Icons.Default.Sort,
                            null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = { expanded = false; onSortModeChange(mode) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongListScreen(
    songs: List<Song>,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    accentColor: Long,
    sharedCoverState: SharedCoverState? = null,
    miniCoverVisible: Boolean = true,
    deviceName: String = "",
    isTablet: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onSongClick: (Song) -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToFolder: () -> Unit,
    onNavigateToPlaylist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: () -> Unit = {},
    onNavigateToArtist: () -> Unit = {},
    onRefresh: () -> Unit = {},
    scanStatus: String = "idle",
    scanProgress: Float = 0f,
    // Tablet detail pane controls
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onToggleEqualizer: () -> Unit = {},
    onShare: () -> Unit = {},
    onNavigateToLyrics: () -> Unit = {},
    eqEnabled: Boolean = false,
    shuffleEnabled: Boolean = false,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    isCurrentSongFavorite: Boolean = false,
    positionMs: androidx.compose.runtime.State<Long>,
    durationMs: androidx.compose.runtime.State<Long>
) {
    var searchQuery by remember { mutableStateOf("") }
    var longPressSong by remember { mutableStateOf<Song?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    // [V8.77] 打开搜索时自动聚焦输入框 → 系统自动弹起输入法（不再需要用户手动点输入框）
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            // 等一帧，确保 OutlinedTextField 已经进入组合再请求焦点
            kotlinx.coroutines.delay(80)
            runCatching { searchFocus.requestFocus() }
        }
    }
    var filterMode by remember { mutableStateOf("all") }
    // 【V8.205】封面取色降饱和：列表里的播放键/强调条跟播放页用同一套收敛口径。
    val activeColor = CoverTone.calm(
        if (accentColor != 0L) Color(accentColor) else MaterialTheme.colorScheme.primary
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentThreshold = System.currentTimeMillis() / 1000 - 7 * 24 * 3600

    // [V8.47] Library sort mode: title / added / filename / duration (persisted)
    val sortPrefs = remember { context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE) }
    var sortMode by remember { mutableStateOf(sortPrefs.getString("songlist_sort_mode", "title") ?: "title") }
    fun setSortMode(m: String) {
        sortMode = m
        sortPrefs.edit().putString("songlist_sort_mode", m).apply()
    }
    val alphabetical = sortMode == "title"

    // Filter songs by search + filter mode, then apply the library sort mode
    val displayedSongs = remember(songs, searchQuery, filterMode, sortMode) {
        val base = if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
        val filtered = when (filterMode) {
            "hires" -> base.filter { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC", "AIFF", "APE", "WAVPACK") }
            "recent" -> base.filter { it.lastPlayedAt > recentThreshold }
            "favorites" -> base.filter { it.isFavorite }
            else -> base
        }
        when (sortMode) {
            // MediaStore DATE_ADDED (falls back to file lastModified), newest first
            "added" -> filtered.sortedWith(
                compareByDescending<Song> { it.dateAdded }.thenBy { it.title.lowercase() })
            // MediaStore gives content:// URIs as `path`; only `filePath` is a real file
            // path, and it can be empty for SAF-scanned songs -> fall back to the title.
            "filename" -> filtered.sortedBy {
                val fp = it.filePath
                if (fp.isEmpty()) it.title.lowercase()
                else fp.substringAfterLast('/').lowercase()
            }
            "duration" -> filtered.sortedWith(
                compareByDescending<Song> { it.duration }.thenBy { it.title.lowercase() })
            else -> filtered.sortedBy { it.title.lowercase() }
        }
    }

    // Count Hi-Res songs (FLAC / WAV / DSD)
    val hiresCount = remember(songs) { songs.count { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC") } }
    // Count recent (added within 7 days)
        val recentCount = remember(songs) { songs.count { it.lastPlayedAt > recentThreshold } }
    // Count favorites
    val favoriteCount = remember(songs) { songs.count { it.isFavorite } }
    // [V8.57] Honest "a scan is running" predicate. scanProgress sits at 1f once a scan
    // COMPLETES, so the old "scanProgress > 0f" test stayed true forever after the first
    // scan and made an empty FILTER render the full-screen "Scanning…" copy.
    val isScanningNow = scanStatus.startsWith("scanning") || (scanProgress > 0f && scanProgress < 1f)

    // Group by first char (A-Z + #). [V8.47] Only the title sort is alphabetical; other
    // modes use one unlabeled group ('*') so the headers/intent index stay consistent.
    // 【V8.144】显式标注 Map 类型：否则 A 分支 SortedMap 与 B 分支 LinkedHashMap 的
    // 最小上界在 JDK21 下会推导成 java.util.SequencedMap（仅 API35 存在），
    // Android 13 上启动即 NoClassDefFoundError 闪退。
    val groupedSongs: Map<Char, List<Song>> = remember(displayedSongs, alphabetical) {
        if (alphabetical) {
            displayedSongs.groupBy { song ->
                val c = song.title.firstOrNull()?.uppercase()?.firstOrNull() ?: '#'
                if (c in 'A'..'Z') c else '#'
            }.toList().sortedBy { it.first }.toMap()
        } else {
            mapOf<Char, List<Song>>('*' to displayedSongs)
        }
    }
    val alphabet = ('A'..'Z').toList() + '#'
    val listState = rememberLazyListState()
    // [V8.76] 顶部标题随滚动折叠吸顶（下滑时标题平滑缩小并吸顶，腾出纵向空间）
    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val groupFirstIndices = remember(groupedSongs) {
        val map = mutableMapOf<Char, Int>()
        var idx = 0
        groupedSongs.forEach { (letter, songsInGroup) ->
            map[letter] = idx
            idx += songsInGroup.size + 1 // +1 for header
        }
        map
    }

    // Track current visible group from scroll position
    val currentGroupLetter by remember {
        derivedStateOf {
            val visibleIdx = listState.firstVisibleItemIndex
            var best: Char = alphabet.firstOrNull() ?: 'A'
            groupFirstIndices.forEach { (letter, startIdx) ->
                if (startIdx <= visibleIdx) best = letter
            }
            best
        }
    }

    // Tablet detail pane visibility — dismissible via back arrow
    var detailPaneVisible by remember { mutableStateOf(currentPlayingSong != null) }
    // Auto-show detail pane when a new song starts playing
    LaunchedEffect(currentPlayingSong?.id) {
        if (currentPlayingSong != null) detailPaneVisible = true
    }

    if (isTablet && detailPaneVisible && currentPlayingSong != null) {
        // System back in tablet TwoPane: dismiss detail pane instead of minimizing
        BackHandler { detailPaneVisible = false }
        // [V8.77] 平板：搜索态下返回键先退出搜索（组合更晚 → 优先级更高）
        BackHandler(enabled = isSearching) {
            isSearching = false
            searchQuery = ""
        }

        // === Tablet Master-Detail Layout ===
        // [V8.99] The navigation-bar inset is consumed here, at the page root. This screen is the
        // only top-level screen built WITHOUT a Scaffold, so nothing was insetting the
        // gesture / 3-button navigation bar and bottom-anchored content sat underneath it.
        Row(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .navigationBarsPadding()
        ) {
            // Left: Song list + top bar (60%)
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                // === Top App Bar (compact for tablet left pane) ===
                SDWTopBar(
                    title = {
                        if (isSearching) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.songlist_search_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.songlist_song_count, songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { detailPaneVisible = false }) {
                            Icon(Icons.Default.Close, stringResource(R.string.songlist_close_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSearching) {
                            IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                Icon(Icons.Default.Close, stringResource(R.string.songlist_close_search), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.action_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SortMenuButton(
                                sortMode = sortMode,
                                onSortModeChange = { setSortMode(it) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onNavigateToPlaylist) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.title_playlists), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(Icons.Default.Settings, stringResource(R.string.action_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // === Smart Tiles ===
                if (!isSearching) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmartTile(stringResource(R.string.songlist_tile_all), "${songs.size}", Icons.Default.MusicNote, MaterialTheme.colorScheme.primary, isSelected = filterMode == "all" || (filterMode != "hires" && filterMode != "recent" && filterMode != "favorites"), onClick = { filterMode = "all"; searchQuery = "" })
                        SmartTile(stringResource(R.string.songlist_tile_hires), "$hiresCount", Icons.Default.HighQuality, MaterialTheme.colorScheme.primary, isSelected = filterMode == "hires", onClick = { filterMode = "hires"; searchQuery = "" })
                        SmartTile(stringResource(R.string.songlist_tile_recent), "$recentCount", Icons.Default.Schedule, MaterialTheme.colorScheme.secondary, isSelected = filterMode == "recent", onClick = { filterMode = "recent" })
                        SmartTile(stringResource(R.string.songlist_tile_favorites), "$favoriteCount", Icons.Default.Favorite, AccentRed, isSelected = filterMode == "favorites", onClick = { filterMode = "favorites" })
                    }
                }

                if (displayedSongs.isEmpty()) {
                    LibraryEmptyState(
                        filterMode = filterMode,
                        isScanning = isScanningNow,
                        onRefresh = onRefresh,
                        onClearFilter = { filterMode = "all"; searchQuery = "" }
                    )
                } else {
                    TabletSongListContent(
                        songs = displayedSongs,
                        groupedSongs = groupedSongs,
                        alphabet = alphabet,
                        groupFirstIndices = groupFirstIndices,
                        currentGroupLetter = currentGroupLetter,
                        listState = listState,
                        currentPlayingSong = currentPlayingSong,
                        isPlaying = isPlaying,
                        activeColor = activeColor,
                        onSongClick = { song ->
                            detailPaneVisible = true
                            onSongClick(song)
                        },
                        onLongClick = { song -> longPressSong = song },
                        sortMode = sortMode,
                        scope = scope
                    )
                }
            }

            // Right: Now Playing detail (40%) — full player controls
            TabletPlayerDetailPanel(
                currentPlayingSong = currentPlayingSong,
                isPlaying = isPlaying,
                accentColor = activeColor,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = isCurrentSongFavorite,
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekTo = onSeekTo,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onNavigateToPlayer = onNavigateToPlayer,
                positionMs = positionMs,
                durationMs = durationMs,
                modifier = Modifier.weight(0.4f).fillMaxHeight().padding(16.dp)
            )
        }
    } else {
        // [V8.99] Same inset as the tablet branch. Without it the mini player (aligned
        // BottomCenter in this Box) is overlapped by the 3-button navigation bar, and the
        // list's bottom padding is measured from the wrong edge too.
        // [V8.111] 真·背景模糊（API 31+ RenderEffect）：把列表内容录进一块 GraphicsLayer，
        // 迷你播放条把它裁成胶囊、平移对齐后贴出 = 透过毛玻璃看列表的真模糊。
        // minSdk 24：低版本 backdropLayer 传 null，条子保持原有便携玻璃，行为与旧版一致。
        val realBlurSupported = android.os.Build.VERSION.SDK_INT >= 31
        val backdropLayer = rememberGraphicsLayer()
        val blurRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
        if (realBlurSupported) {
            SideEffect {
                backdropLayer.renderEffect = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
                backdropLayer.compositingStrategy = CompositingStrategy.Offscreen
            }
        }
        var backdropContainerOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .navigationBarsPadding()
                .onGloballyPositioned { coords -> backdropContainerOrigin = coords.positionInRoot() }
        ) {
        // [V8.77] 搜索态下系统返回键先退出搜索回到主界面，而不是把 App 最小化。
        // 位置放在平板详情面板的 BackHandler 之后，组合更晚 → 优先级更高，
        // 保证「搜索 + 详情面板同时开着」时先关搜索。非搜索态不拦截，保留系统默认行为。
        BackHandler(enabled = isSearching) {
            isSearching = false
            searchQuery = ""
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarScroll.nestedScrollConnection)
                .then(
                    // [V8.111] 只在迷你条可见（有正在播放的歌）时录，无歌时零开销。
                    if (realBlurSupported && currentPlayingSong != null) Modifier.drawWithContent {
                        val contentScope = this
                        backdropLayer.record { contentScope.drawContent() }
                        drawContent()
                    } else Modifier
                )
        ) {
            // === Top App Bar ===
            SDWTopBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_songs), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // [V8.76] 折叠式标题：下滑时字号/不透明度随折叠进度收缩，标题吸顶变紧凑，
                            // 释放纵向空间。100% 展开时仍是原来的大标题观感。
                            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                            val baseFontSize = when {
                                cfg.screenWidthDp < 400 -> 20.sp
                                cfg.screenWidthDp < 600 -> 22.sp
                                else -> 24.sp
                            }
                            val collapse = topBarScroll.state.collapsedFraction
                            val titleFontSize = baseFontSize * (1f - 0.22f * collapse)
                            Text(
                                stringResource(R.string.brand_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontSize = titleFontSize,
                                lineHeight = titleFontSize * 1.15f,
                                color = MaterialTheme.colorScheme.onBackground.copy(
                                    alpha = 1f - 0.25f * collapse
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    if (isSearching) {
                        IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, stringResource(R.string.songlist_close_search), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        SortMenuButton(
                            sortMode = sortMode,
                            onSortModeChange = { setSortMode(it) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // [fix] 顶部入口收进「更多」菜单，避免 7 个图标把标题空间挤光导致应用名被遮挡
                        var moreExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { moreExpanded = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                            offset = DpOffset(x = (-80).dp, y = 0.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { moreExpanded = false; onRefresh() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.title_playlists)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { moreExpanded = false; onNavigateToPlaylist() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.title_albums)) },
                                leadingIcon = { Icon(Icons.Default.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { moreExpanded = false; onNavigateToAlbum() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.title_artists)) },
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { moreExpanded = false; onNavigateToArtist() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.title_folders)) },
                                leadingIcon = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { moreExpanded = false; onNavigateToFolder() }
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, stringResource(R.string.action_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = topBarScroll
            )

            // [V8.76] 常驻胶囊搜索栏：搜索从图标行移到这里，点一下展开输入，吞吐更快，
            // 顶部图标从 4 个收到 3 个。搜索激活时胶囊变成输入框（原有逻辑不变）。
            if (!isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(SDWShapes.Pill)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), SDWShapes.Pill)
                        .clickable { isSearching = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.songlist_search_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    // [V8.77] 删掉右侧的麦克风与 MoreVert：两个都是纯装饰、没有 clickable，
                    // 麦克风尤其误导（语音搜索并未实现）。
                }
            }

            // === Smart Tiles ===
            if (!isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    // [V8.77] 等分占满一行，去掉横向滚动：原来固定 112dp + horizontalScroll
                    // 会让第 4 颗胶囊半露在右边缘，用户不接受半露。
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // [V8.78] 等分占满一行、不滚动，且保留文字标签。
                    // pill = true 决定用「文字 + 数字」的单行胶囊形态（V8.77 曾误删 pill
                    // 导致四个胶囊的文字全部消失，只剩图标和数字）。
                    SmartTile(stringResource(R.string.songlist_tile_all), "${songs.size}", Icons.Default.MusicNote, MaterialTheme.colorScheme.primary, isSelected = filterMode == "all" || (filterMode != "hires" && filterMode != "recent" && filterMode != "favorites"), pill = true, onClick = { filterMode = "all"; searchQuery = "" })
                    SmartTile(stringResource(R.string.songlist_tile_hires), "$hiresCount", Icons.Default.HighQuality, MaterialTheme.colorScheme.primary, isSelected = filterMode == "hires", pill = true, onClick = { filterMode = "hires"; searchQuery = "" })
                    SmartTile(stringResource(R.string.songlist_tile_recent), "$recentCount", Icons.Default.Schedule, MaterialTheme.colorScheme.secondary, isSelected = filterMode == "recent", pill = true, onClick = { filterMode = "recent" })
                    SmartTile(stringResource(R.string.songlist_tile_favorites), "$favoriteCount", Icons.Default.Favorite, AccentRed, isSelected = filterMode == "favorites", pill = true, onClick = { filterMode = "favorites" })
                }
            }

            // === Scan Progress Bar (visible whenever scanning) ===
            if (isScanningNow) {
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
                Text(
                    stringResource(R.string.songlist_scanning_progress, (scanProgress * 100).toInt()),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // === Song List ===
            if (displayedSongs.isEmpty() && !isSearching) {
                LibraryEmptyState(
                    filterMode = filterMode,
                    isScanning = isScanningNow,
                    onRefresh = onRefresh,
                    onClearFilter = { filterMode = "all"; searchQuery = "" }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    // [V8.112] 预留从 modifier.padding 改到 contentPadding：modifier.padding 会把
                    // LazyColumn 自身缩短，列表内容永远到不了迷你条底下，真·背景模糊里只糊到一块
                    // 空底色（就是你看到的生硬黑/白）。改 contentPadding 后内容可以一路滚到条子底下，
                    // 模糊层里就能看到真实滚动的歌曲行/封面 = iOS 液态玻璃；静止时最后一行仍停在
                    // 条子上方 96dp，不被遮挡。
                    contentPadding = PaddingValues(end = 32.dp, bottom = 96.dp),
                    state = listState
                ) {
                    groupedSongs.forEach { (letter, songsInGroup) ->
                        stickyHeader {
                            // V8.61 - the header is now ALWAYS present. Non-title sorts put
                            // every song in one '*' group and the old guard skipped the header,
                            // which also removed the only double-tap-to-top target.
                            // V8.59 - glass strip instead of an opaque band. fillBoost keeps
                            // the letter readable while rows slide underneath it.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                                    .pointerInput(letter) {
                                        detectTapGestures(onDoubleTap = {
                                            scope.launch { listState.scrollToItem(0) }
                                        })
                                    }
                            ) {
                                        // [V8.83] 分组头改为「accent 竖条 + 字母」，去掉玻璃底与描边。
                                        // 原来每个分组头都是带 accent 描边的圆角玻璃条，铺满整行叠在列表上，
                                        // 观感像一个个框、偏杂乱。竖条规格与「播放队列 / 正在播放行」的
                                        // 当前曲标识统一：3dp 宽、圆角 2dp、accent 色。
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .height(14.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        SDWShapes.Badge
                                                    )
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (letter == '*') stringResource(
                                                    when (sortMode) {
                                                        "added" -> R.string.songlist_sort_added
                                                        "filename" -> R.string.songlist_sort_filename
                                                        "duration" -> R.string.songlist_sort_duration
                                                        else -> R.string.songlist_sort_title
                                                    }
                                                ) else letter.toString(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                            }
                        }
                        itemsIndexed(songsInGroup, key = { _, song -> song.id }) { _, song ->
                            val itemMod = Modifier.animateItemPlacement()
                            SongItem(
                                song = song,
                                index = 0,
                                isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                                accentColor = activeColor,
                                onClick = { onSongClick(song) },
                                onLongClick = { longPressSong = song },
                                coverFlight = sharedCoverState?.rowFlightSongId == song.id,
                                modifier = itemMod
                            )
                        }
                    }
                }
            }

            // === Song Count ===
            if (displayedSongs.isNotEmpty()) {
                Text(
                    "${displayedSongs.size} songs",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 76.dp)
                )
            }
        }

        // === A-Z Sidebar (scroll-aware) ===
        if (alphabetical && displayedSongs.size > 20) {
            val sidebarHeight = alphabet.size * 22
            // [V8.82] 索引栏整体下移。屏幕上居中时，它的上沿正好压在筛选胶囊下边、
            // 下沿却在迷你播放条上方留出近 140px 空档，看起来“太高”。
            // 实测可用列表带中心在 y~1398，而索引栏中心在 y~1329（高 69px）,
            // 故补 30.dp(=84px) 向下偏置落在列表带中心上，并略带向下余韵。
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = 30.dp)
                    .padding(end = 4.dp)
                    .width(24.dp)
                    .height(sidebarHeight.dp)
                    .pointerInput(alphabet) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val idx = (offset.y / size.height * alphabet.size).toInt()
                                    .coerceIn(0, alphabet.size - 1)
                                groupFirstIndices[alphabet[idx]]?.let { pos ->
                                    scope.launch { listState.scrollToItem(pos) }
                                }
                            },
                            onVerticalDrag = { change, _ ->
                                val y = change.position.y.coerceIn(0f, size.height.toFloat())
                                val idx = (y / size.height * alphabet.size).toInt()
                                    .coerceIn(0, alphabet.size - 1)
                                groupFirstIndices[alphabet[idx]]?.let { pos ->
                                    scope.launch { listState.scrollToItem(pos) }
                                }
                            }
                        )
                    },
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                alphabet.forEach { letter ->
                    val hasGroup = letter in groupedSongs
                    val isCurrent = letter == currentGroupLetter
                    Text(
                        text = letter.toString(),
                        fontSize = if (isCurrent) 11.sp else 9.sp,
                        fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                                     else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isCurrent) activeColor
                                else if (hasGroup) {
                                    when {
                                        !WallpaperManager.hasWallpaper() -> MaterialTheme.colorScheme.onSurfaceVariant
                                        LocalWallpaperEffectiveLight.current -> Color.Black.copy(alpha = 0.78f)
                                        else -> Color.White.copy(alpha = 0.9f)
                                    }
                                } else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        // [V8.76] 字母在 24dp 索引栏内居中：之前 Text 是 wrap-content，
                        // 窄字母（I/J）贴左，视觉不成一列。
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = hasGroup) {
                                groupFirstIndices[letter]?.let { idx ->
                                    scope.launch { listState.scrollToItem(idx) }
                                }
                            }
                    )
                }
            }
        }

        // === Mini Player ===
        // 【V8.5】出现/消失动画：底部滑入 + 渐显；消失下滑 + 渐隐。
        // 用 lastSong 保留最后非空歌曲，保证 exit 动画期间仍有内容可渲染
        var lastMiniSong by remember { mutableStateOf<Song?>(null) }
        if (currentPlayingSong != null) lastMiniSong = currentPlayingSong
        AnimatedVisibility(
            visible = currentPlayingSong != null,
            // [V8.44] slide is spatial -> spring; opacity stays tween
            enter = slideInVertically(
                spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { it } + fadeIn(tween(300, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) { it } + fadeOut(tween(220, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            lastMiniSong?.let { song ->
                val miniCoverUri = song.albumArtUri
                MiniPlayer(
                    song = song,
                    isPlaying = isPlaying,
                    accentColor = activeColor,
                    coverUri = miniCoverUri,
                    coverVisible = miniCoverVisible,
                    coverFlightEnabled = sharedCoverState?.rowFlightSongId == null,
                    backdropLayer = if (realBlurSupported) backdropLayer else null,
                    containerOrigin = backdropContainerOrigin,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onClick = onNavigateToPlayer,
                    onCoverPositioned = { offset, size ->
                        sharedCoverState?.miniCoverPosition = CoverPosition(
                            windowOffset = offset,
                            size = size
                        )
                    }
                )
            }
        }
        // 水印：迷你条隐藏时显示，带淡入淡出（与迷你条互斥平滑过渡）
        AnimatedVisibility(
            visible = currentPlayingSong == null,
            enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text(
                text = deviceName.ifEmpty { stringResource(R.string.brand_audio) },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

    // 【V8.7】长按添加歌单
    longPressSong?.let { song ->
        AddToPlaylistSheet(
            song = song,
            onDismiss = { longPressSong = null }
        )
    }
    }
}
}



/**
 * Song list content for tablet/foldable master-detail layout.
 * Shows a grouped A-Z list with an alphabet sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletSongListContent(
    songs: List<Song>,
    groupedSongs: Map<Char, List<Song>>,
    alphabet: List<Char>,
    groupFirstIndices: Map<Char, Int>,
    currentGroupLetter: Char,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    activeColor: Color,
    onSongClick: (Song) -> Unit,
    onLongClick: (Song) -> Unit,
    sortMode: String,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(end = 32.dp),
            state = listState
        ) {
            groupedSongs.forEach { (letter, songsInGroup) ->
                stickyHeader {
                    // V8.61 - always present (see phone branch): keeps double-tap-to-top alive.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .pointerInput(letter) {
                                detectTapGestures(onDoubleTap = {
                                    scope.launch { listState.scrollToItem(0) }
                                })
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(
                                    shape = SDWShapes.Thumb,
                                    accent = MaterialTheme.colorScheme.primary,
                                    accentEdge = true,
                                    fillBoost = 0.30f
                                )
                        ) {
                            Text(text = if (letter == '*') stringResource(
                                        when (sortMode) {
                                            "added" -> R.string.songlist_sort_added
                                            "filename" -> R.string.songlist_sort_filename
                                            "duration" -> R.string.songlist_sort_duration
                                            else -> R.string.songlist_sort_title
                                        }
                                    ) else letter.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                    }
                }
                itemsIndexed(songsInGroup, key = { _, song -> song.id }) { _, song ->
                    val itemMod = Modifier.animateItemPlacement()
                    SongItem(
                        song = song,
                        index = 0,
                        isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                        accentColor = activeColor,
                        onClick = { onSongClick(song) },
                        onLongClick = { onLongClick(song) },
                        modifier = itemMod
                    )
                }
            }
        }

        // A-Z Sidebar
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(24.dp)
                .fillMaxHeight(0.8f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            alphabet.forEach { letter ->
                val hasGroup = letter in groupedSongs
                val isCurrent = letter == currentGroupLetter
                Text(
                    text = letter.toString(),
                    fontSize = if (isCurrent) 11.sp else 9.sp,
                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                                 else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isCurrent) activeColor
                            else if (hasGroup) {
                                when {
                                    !WallpaperManager.hasWallpaper() -> MaterialTheme.colorScheme.onSurfaceVariant
                                    LocalWallpaperEffectiveLight.current -> Color.Black.copy(alpha = 0.78f)
                                    else -> Color.White.copy(alpha = 0.9f)
                                }
                            } else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    // [V8.76] 同上：字母在索引栏内居中
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hasGroup) {
                            groupFirstIndices[letter]?.let { idx ->
                                scope.launch { listState.scrollToItem(idx) }
                            }
                        }
                )
            }
        }
    }

}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remMin = minutes % 60
        "%d:%02d:%02d".format(hours, remMin, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}


@Composable
private fun RowScope.SmartTile(
    label: String,
    count: String,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    // [V8.76] pill = true 时不再等分占满一行，而是固定宽度的小药丸，可横向滑动。
    pill: Boolean = false,
    onClick: () -> Unit
) {
    // 【2026-09-04】壁纸模式自适应：亮壁纸深字/暗壁纸白字 + 玻璃底
    val wpLight = LocalWallpaperEffectiveLight.current
    val onWallpaper = WallpaperManager.hasWallpaper()
    val contentColor = when {
        isSelected -> color
        !onWallpaper -> MaterialTheme.colorScheme.onSurfaceVariant
        wpLight -> Color.Black.copy(alpha = 0.78f)
        else -> Color.White.copy(alpha = 0.85f)
    }
    // V8.59 - Android 17 look: the filter chips are glass panes with spring feedback
    // instead of flat 8dp blocks. Count sits under the icon; selected borrows the tile
    // colour for both the wash and the lit edge.
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            // [V8.78] pill 也走等分：原来固定 112dp x 4 + 间距 = ~480dp，超出手机可用宽度
            // （427dp），配 horizontalScroll 就呈现「第 4 颗半露在右边缘」。等分后四颗完整占满。
            .weight(1f)
            .height(if (pill) 48.dp else 56.dp)
            .glassSurface(
                shape = if (pill) SDWShapes.Pill else SDWShapes.Row,
                accent = color,
                selected = isSelected,
                // V8.68 - shadow() opens a graphics layer whose shadow silhouette showed
                // through the translucent fill of a 56dp tile as an inset rectangle.
                // List rows never had it (elevation 0) and never showed the artefact.
                elevation = 0.dp
            )
            // [V8.76] 未选中胶囊补一层极淡磨砂描边：原来未选中的暗底太弱，看不出可以横向滑动
            .then(
                if (pill && !isSelected) {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (onWallpaper) 0.16f else 0.55f),
                            1f to Color.White.copy(alpha = if (onWallpaper) 0.06f else 0.18f)
                        ),
                        shape = SDWShapes.Pill
                    )
                } else Modifier
            )
            .springPress(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (pill) {
            // [V8.78] 等分后每颗只有约 95dp，图标(15dp)+两个间距(12dp)会把文字挤掉。
            // 去掉小图标，保留「文字 + 数字」——文字识别度比图标高，且数字本来就是主角。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = if (isSelected) contentColor else contentColor.copy(alpha = 0.72f),
                    maxLines = 1
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            }
        }
    }
}
/** Lossless / Hi-Res 判定集合（与 hires 过滤保持一致口径） */
private val LOSSLESS_FORMATS = setOf("FLAC", "WAV", "DSD", "ALAC", "AIFF", "APE", "WAVPACK", "M4A")


@Composable
private fun LibraryEmptyState(
    filterMode: String,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFilter = filterMode != "all"
    Box(
        modifier = modifier.fillMaxSize().padding(bottom = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (filterMode == "favorites") Icons.Default.Favorite else Icons.Default.MusicNote,
                null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    isScanning -> stringResource(R.string.songlist_scanning)
                    filterMode == "favorites" -> stringResource(R.string.songlist_no_favorites)
                    filterMode == "hires" -> stringResource(R.string.songlist_no_hires)
                    filterMode == "recent" -> stringResource(R.string.songlist_no_recent)
                    else -> stringResource(R.string.songlist_no_songs)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                when {
                    isScanning -> stringResource(R.string.songlist_scanning)
                    isFilter -> stringResource(R.string.songlist_filter_hint)
                    else -> stringResource(R.string.songlist_check_files)
                },
                color = MaterialTheme.colorScheme.outlineVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            when {
                isScanning -> Unit
                isFilter -> OutlinedButton(onClick = onClearFilter) {
                    Text(stringResource(R.string.songlist_tile_all))
                }
                else -> OutlinedButton(onClick = onRefresh) {
                    Text(stringResource(R.string.songlist_scan))
                }
            }
        }
    }
}





@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    accentColor: Color,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    // [V8.84] true only for the row the user tapped: it becomes the source end of the
    // shared-element flight, so the flight starts from the row itself (not the mini bar).
    coverFlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // [V8.76] 音质小标：懒读文件头拿真实位深/采样率（SongSpecReader 有 path→spec 缓存，
    // 同一首歌只解析一次），放在 IO 线程，滚动时不阻塞主线程；只有组合到这一行才会触发。
    var hiRes by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id) {
        val path = song.filePath.ifBlank { song.path }
        val cached = if (path.isNotBlank()) SongSpecReader.getCached(path) else null
        hiRes = if (cached != null) {
            cached.isHiRes || cached.format.uppercase() in LOSSLESS_FORMATS
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val sp = SongSpecReader.specFor(song)
                    sp != null && (sp.isHiRes || sp.format.uppercase() in LOSSLESS_FORMATS)
                } catch (_: Throwable) { false }
            }
        }
    }
    // 【2026-09-04】壁纸模式自适应：亮壁纸→深字（配白描边），暗壁纸→白字
    val wpLight = LocalWallpaperEffectiveLight.current
    val onWallpaper = WallpaperManager.hasWallpaper()
    val textColor = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val subColor = when {
        !onWallpaper && isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        !onWallpaper -> MaterialTheme.colorScheme.onSurfaceVariant
        wpLight -> Color.Black.copy(alpha = if (isPlaying) 0.9f else 0.72f)
        else -> Color.White.copy(alpha = if (isPlaying) 0.92f else 0.78f)
    }
    
    // V8.59 - rows become glass panes. The playing row melts into the accent colour so
    // the list still shows where playback is; every other row is a faint neutral wash that
    // lifts it off the background without turning the list into a wall of cards.
    // elevation = 0.dp is deliberate: shadows on thousands of rows would each cost a
    // graphics layer, the hairline edge carries the depth instead.
    val rowShape = SDWShapes.Row
    val interaction = remember { MutableInteractionSource() }
    // [V8.84] Row-side shared-element source. Called unconditionally (the shared-element
    // modifier is a no-op without the scopes) and applied only to the tapped row, so the
    // mini bar and a list row can never both claim the same key.
    val rowSharedCover = Modifier.sharedCoverArt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .glassSurface(
                shape = rowShape,
                accent = accentColor,
                selected = isPlaying,
                elevation = 0.dp,
                fillBoost = if (onWallpaper) 0f else 0.04f
            )
            // [V8.109] 正在播放行加一圈琥珀金描边光晕：上缘亮、下缘隐，读起来是
            // "这行是活的"，而不是一块被选中的色块。非播放行零开销（Modifier 直传）。
            .then(
                if (isPlaying) Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(accentColor.copy(alpha = 0.60f), accentColor.copy(alpha = 0.10f))
                    ),
                    shape = rowShape
                ) else Modifier
            )
            .springPress(interaction, pressedScale = 0.975f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover art — DefaultCoverImage underneath, actual cover on top
        // V8.76 正在播放：封面外圈加一圈微弱呼吸光晕（只在这一行组合，其它行零开销）。
        // V8.118 封面从圆形统一为圆角方（SDWShape.Thumb 8dp）：专辑/歌手/歌单页早就
        // 是圆角方，只有主曲库是圆形 —— 同一个 App 两种封面语言。选圆角方的理由见
        // Tokens.kt 注释：封面是方图，圆形裁角；本地播放器的气质也偏几何而非社交。
        val coverShape = RoundedCornerShape(SDWShape.Thumb)
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            // V8.76 光晕放在封面 Box 内部：它是 56dp > 48dp 的装饰层，必须溢出绘制而
            // 不参与 Row 的布局。之前作为 Row 的兄弟节点会占 56dp 宽，把封面和文字整体右推。
            if (isPlaying) NowPlayingHalo(accentColor, coverShape)
            DefaultCoverImage(song.title, song.artist, Modifier.size(48.dp), coverShape)
            // [V8.56] Same shared request as the player; a list row and the player showing
            // the same album no longer decode two copies of one picture.
            // V8.66 list tier: a row draws at 48dp, so it must not decode the 260dp
            // flight bitmap (that was ~2.1MB per row at 450dpi).
            val painter = rememberSharedCoverPainter(song.albumArtUri)
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (coverFlight) rowSharedCover else Modifier)
                    .clip(coverShape),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(Modifier.width(12.dp))
        
        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                // [V8.109] 歌名加粗一档（SemiBold），与下方更小号的歌手形成层次。
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (song.bpm > 0) {
                Spacer(Modifier.height(3.dp))
                BpmBadge(song.bpm)
            }
        }
        
        // Duration + 音质小标（HR / Lossless）
        Column(horizontalAlignment = Alignment.End) {
            // [V8.109] 正在播放的行把时长换成跳动的三根小波形，静态时长让位给"在播"。
            if (isPlaying) {
                NowPlayingBars(accentColor)
            } else {
                Text(
                    text = formatDuration(song.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = subColor
                )
            }
            if (hiRes) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.songlist_hr_badge),
                    fontSize = 8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                    modifier = Modifier
                        .clip(SDWShapes.Badge)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isPlaying) 0.18f else 0.10f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}




@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    coverUri: String? = null,
    coverVisible: Boolean = true,
    positionMs: androidx.compose.runtime.State<Long>,
    durationMs: androidx.compose.runtime.State<Long>,
    onClick: () -> Unit,
    onCoverPositioned: ((Offset, Size) -> Unit)? = null,
    // [V8.84] The mini bar yields the cover key while a list row owns the flight.
    coverFlightEnabled: Boolean = true,
    // [V8.111] 真·背景模糊：主界面传入已录好列表内容的图层，null = 低版本不做（保持便携玻璃）。
    backdropLayer: GraphicsLayer? = null,
    containerOrigin: Offset = Offset.Zero,
    modifier: Modifier = Modifier
) {
    if (song == null) return
    
    // [V8.52] Source end of the official shared-element flight to the full player.
    // [V8.84] ...as long as this bar owns the cover key. When the user taps a list row the
    // row takes it over, otherwise both would be a source for one key.
    val sharedCover = if (coverFlightEnabled) Modifier.sharedCoverArt() else Modifier
    // [V8.54] Coil sizes every request to the canvas it is drawn into, so this 40dp cover
    // used to decode at 40dp and the shared-element flight then stretched those few
    // hundred pixels up to the player's ~240dp - visibly soft. Decode once at a size that
    // covers the full-player target and let the flight downscale instead of upscale.
    val context = LocalContext.current
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "miniPlayerPress"
    )
    
    val posMs = positionMs.value
    val durMs = durationMs.value
    val hasDuration = durMs > 0
    val remainingMs = if (hasDuration) (durMs - posMs).coerceIn(0L, durMs) else 0L
    val remainingText = if (hasDuration) {
        val totalSec = remainingMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        "-$min:${sec.toString().padStart(2, '0')}"
    } else ""
    val progressFraction = if (hasDuration) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    // [V8.111] 条子在 root 里的位置 - 容器原点 = 图层内的平移量（把背景层对齐到条子下面）
    var miniShift by remember { mutableStateOf(Offset.Zero) }
    // [V8.112] 模糊层之上再压的一层极薄主题色薄纱：统一色调、压住过碎的细节，
    // 读起来是透亮的液态玻璃而不是"一块死板的黑/白"。
    val blurVeil = MaterialTheme.colorScheme.surface.copy(alpha = 0.16f)
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            // [V8.109] 悬浮毛玻璃胶囊：四周留呼吸间距（左右 16、上下不对称让底部更重），
            // pane fill 加厚、圆角收到胶囊级、微投影抬高，读起来是浮在列表之上的独立一层。
            // （真·背景模糊需 API31+，minSdk 24 会在大量机型静默失效，故走便携版玻璃。）
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            // V8.59 - the mini player is the most floaty element on the main screen, so it
            // keeps a real shadow and gets the accent-lit edge. Radius bumped to 22dp to sit
            // with the new 16dp row panes.
            .glassSurface(
                shape = SDWShapes.Panel,
                accent = accentColor,
                elevation = 22.dp,
                accentEdge = true,
                fillBoost = 0.09f,
                // [V8.112] 底下已贴真·模糊内容时，自身底色降到极薄薄纱（见 Glass.kt）。
                blurBacked = backdropLayer != null
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .onGloballyPositioned { coords -> miniShift = coords.positionInRoot() - containerOrigin }
            .then(
                if (backdropLayer != null) Modifier.drawWithContent {
                    // [V8.112b] 修：.padding(16/6/10) 在本节点之前执行，node 的 size 已经是**内层**
                    // 尺寸，再各边内缩 16/6/10 就多缩了一圈 → 胶囊四周留出 ~16dp 未糊的生硬竖条。
                    // 这里按节点真实尺寸直接裁胶囊（与 glassSurface 的 RoundedCornerShape(26dp) 对齐）。
                    val rad = 26.dp.toPx()
                    val capsule = Path().apply {
                        addRoundRect(
                            RoundRect(0f, 0f, size.width, size.height, CornerRadius(rad, rad))
                        )
                    }
                    clipPath(capsule) {
                        translate(left = -miniShift.x, top = -miniShift.y) {
                            drawLayer(backdropLayer)
                        }
                    }
                    // [V8.112] 薄纱压在模糊层之上、内容之下：色调统一 + 提升可读性
                    drawPath(capsule, blurVeil)
                    drawContent()
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small cover — centered in row, DefaultCoverImage underneath
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(sharedCover)
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f },
                contentAlignment = Alignment.Center
            ) {
                DefaultCoverImage(song.title, song.artist, Modifier.size(40.dp), SDWShapes.Badge)
                val miniCoverUri = coverUri ?: song.albumArtUri
                // [V8.56] Shared request: byte-for-byte identical to the full player's, so
                // the flight scales a bitmap that is ALREADY in the memory cache instead of
                // decoding a second copy on the fly (that decode was the stutter).
                // V8.66 flight tier: this is the origin of the shared-element flight, so
                // it must match the full player's bitmap exactly.
                val painter = rememberSharedCoverPainter(miniCoverUri, big = true)
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(SDWShapes.Badge)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            val sz = coords.size
                            onCoverPositioned?.invoke(
                                Offset(pos.x, pos.y),
                                Size(sz.width.toFloat(), sz.height.toFloat())
                            )
                        },
                    contentScale = ContentScale.Crop
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Title + Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(Modifier.width(8.dp))
            
            // Remaining time countdown
            Text(
                text = remainingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(2.dp)
                .clip(SDWShapes.Badge)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction)
                    .fillMaxHeight()
                    .background(accentColor)
            )
        }
        
        Spacer(Modifier.height(6.dp))
    }
}

// ============================================================================
// Tablet Detail Pane — full player controls
// ============================================================================

@Composable
private fun TabletPlayerDetailPanel(
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    eqEnabled: Boolean,
    isCurrentSongFavorite: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    positionMs: androidx.compose.runtime.State<Long>,
    durationMs: androidx.compose.runtime.State<Long>,
    modifier: Modifier = Modifier
) {
    val song = currentPlayingSong ?: return
    val posMs = positionMs.value
    val durMs = durationMs.value
    val progressFraction = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Cover art — keep previous cover visible during transition using Crossfade
        Crossfade(targetState = song.id, animationSpec = tween(350), label = "cover") { _ ->
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(SDWShapes.Media)
            ) {
                // DefaultCoverImage underneath, actual cover on top
                DefaultCoverImage(song.title, song.artist, Modifier.fillMaxSize(), SDWShapes.Media)
                // V8.66 big tier: this panel draws the cover at 160dp (450px at 450dpi),
                // so the 160px list tier would be visibly soft here.
                Image(
                    painter = rememberSharedCoverPainter(song.albumArtUri, big = true),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Song info
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Progress bar
        PlayerProgress(
            progressFraction = progressFraction,
            durationMs = durMs,
            positionMs = posMs,
            accentColor = accentColor,
            onSeekTo = { fraction ->
                onSeekTo((fraction * durMs).toLong())
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )

        // Playback controls
        PlayerControlBar(
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            isPlaying = isPlaying,
            accentColor = accentColor,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onPlay = onPlay,
            onPause = onPause,
            onNext = onNext,
            onCycleRepeat = onCycleRepeat
        )

        // EQ label + bottom actions
        PlayerEqLabel(
            eqPresetName = if (eqEnabled) "EQ" else null,
            accentColor = accentColor,
            textAccentColor = accentColor
        )

        PlayerBottomActions(
            eqEnabled = eqEnabled,
            isCurrentSongFavorite = isCurrentSongFavorite,
            onNavigateToLyrics = onNavigateToLyrics,
            onToggleEqualizer = onToggleEqualizer,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare
        )
    }
}
