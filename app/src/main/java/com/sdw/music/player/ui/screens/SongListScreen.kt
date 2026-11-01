package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.metadata.SongSpecReader
import com.sdw.music.player.core.SearchHistoryStore

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
import com.sdw.music.player.ui.components.AmbientFlowBackground
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import com.sdw.music.player.ui.components.ExpressiveWavyProgress
import com.sdw.music.player.ui.components.SDWAlertDialog
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.sdw.music.player.R
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
import com.kyant.backdrop.glassHighlight
import com.kyant.backdrop.glassInnerShadow
import com.kyant.backdrop.liquidGlassLens
import com.kyant.backdrop.internal.chain
import com.sdw.music.player.ui.theme.LocalWallpaperEffectiveLight
import androidx.media3.common.Player
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*

import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.BpmBadge
import com.sdw.music.player.ui.components.rememberDeviceTilt
import com.sdw.music.player.ui.components.rememberSharedCoverPainter


// V8.62/V8.63 - "Go to top" and "go to letter" must SNAP, never animate.
// LazyListState.animateScrollToItem() is not a jump: it walks the list in chained
// 2500dp springs and teleports only in 100-item hops, so it visibly crawls even
// over a short distance. scrollToItem() is the instant jump - always use it here.

/**
 * [2026-10-29 · M3 Expressive] 主界面顶栏统一键。
 *
 * 全站其它页顶栏用的都是 FilledTonalIconButton（带容器的 tonal 键），只有主界面是一排
 * "飘着的裸图标" —— 同一个 App 两套顶栏键语言，而且裸图标在玻璃背景上边界发虚。
 * 统一到这个组件后，容器形状走 IconButtonDefaults.shapes()（M3 Expressive 的 morphing
 * 形状），图标 20dp、容器默认 40dp —— 比裸 IconButton 的 48dp 触摸框还小一点，
 * 加了容器也不会让顶栏更挤。
 */
@Composable
private fun TopBarTonalIcon(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(contentColor = contentColor)
    ) {
        Icon(imageVector, contentDescription, modifier = Modifier.size(20.dp))
    }
}

/**
 * V8.47 - Library sort menu: Title (A-Z) / Recently added / File name / Duration / Format.
 *
 * [2026-10-29 · M3 Expressive] DropdownMenu → ModalBottomSheet。
 * 旧实现是右上角垂下的一根窄长条：5 个选项挤成一列密集菜单，往下还压住两行歌名，
 * 而且是"点了才知道当前选的是哪个"。改成底部抽屉后：28dp 大圆角从底部滑出，行高
 * 拉到 56dp、正文 bodyLarge，选中项走 primaryContainer 实心底 + Check 图标，单选语义
 * 一眼可见，单手也够得着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenuButton(
    sortMode: String,
    onSortModeChange: (String) -> Unit,
    tint: Color
) {
    var showSheet by remember { mutableStateOf(false) }
    FilledTonalIconButton(
        onClick = { showSheet = true },
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(contentColor = tint)
    ) {
        Icon(Icons.Default.Sort, stringResource(R.string.songlist_sort_by))
    }
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    stringResource(R.string.songlist_sort_by),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                listOf(
                    "title" to R.string.songlist_sort_title,
                    "added" to R.string.songlist_sort_added,
                    "filename" to R.string.songlist_sort_filename,
                    "duration" to R.string.songlist_sort_duration,
                    // [2026-10-25] 用户要求增加按格式分类（FLAC/WAV/MP3 分组）
                    "format" to R.string.songlist_sort_format
                ).forEach { (mode, labelRes) ->
                    SheetOptionRow(
                        label = stringResource(labelRes),
                        icon = if (sortMode == mode) Icons.Default.Check else Icons.Default.Sort,
                        selected = sortMode == mode,
                        onClick = { showSheet = false; onSortModeChange(mode) }
                    )
                }
            }
        }
    }
}

/**
 * [2026-10-29 · M3 Expressive] 底部抽屉里的通用选项行。
 * 行高 56dp、正文 bodyLarge、圆角 20dp（SDWShapes.Row）——和全站玻璃行同一套圆角阶梯，
 * 不再是 DropdownMenuItem 那种又矮又密的"系统调试菜单"观感。
 */
@Composable
private fun SheetOptionRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    iconTintUnselected: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = SDWShapes.Row,
        color = if (selected) cs.primaryContainer else Color.Transparent,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = if (selected) cs.onPrimaryContainer else iconTintUnselected,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * [2026-10-25] 搜索历史面板（用户要求：搜索框点击后有搜索记录）。
 * 仅在搜索态且输入为空时显示。点条目回填搜索词；单条可删；一键清空。
 */
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearAll, enabled = history.isNotEmpty()) {
                Text(
                    stringResource(R.string.search_history_clear_all),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.search_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 24.dp)
            )
        } else {
            history.forEach { q ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SDWShapes.Pill)
                        .clickable { onPick(q) }
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        q,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = { onRemove(q) }, modifier = Modifier.size(32.dp), shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.search_history_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
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
    // [2026-10-25] 长按菜单「删除」：调用方走 VM（Android 11+ 弹系统确认框，旧版直接删）
    onDeleteSong: (Song) -> Unit = {},
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
    // 【Lyrico 联动】长按歌曲菜单里的「用 Lyrico 编辑」入口
    onEditWithLyrico: (Song) -> Unit = {},
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
    // [2026-10-25] 删除二次确认：长按菜单点「删除」后先弹框，确认才真删（不可恢复操作）
    var songToDelete by remember { mutableStateOf<Song?>(null) }
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

    // [2026-10-25] 搜索历史（用户要求：搜索框点击后有搜索记录）。
    // 内存列表 + SharedPreferences 双写；commitSearch 在「IME 搜索键 / 点结果 / 退出搜索」三处调用。
    val searchHistory = remember { mutableStateListOf<String>() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(isSearching) {
        if (isSearching && searchHistory.isEmpty()) {
            searchHistory.addAll(SearchHistoryStore.load(context))
        }
    }
    fun commitSearch(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        SearchHistoryStore.add(context, query)
        val dedup = searchHistory.distinctBy { it.lowercase() }.toMutableList()
        dedup.removeAll { it.equals(query, ignoreCase = true) }
        dedup.add(0, query)
        val capped = dedup.take(SearchHistoryStore.MAX)
        searchHistory.clear()
        searchHistory.addAll(capped)
    }

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
            // [2026-10-25] 按格式分组：FLAC/WAV/MP3 各自聚在一起，组内按标题
            "format" -> filtered.sortedWith(
                compareBy<Song> { it.format.uppercase() }.thenBy { it.title.lowercase() })
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
            // [2026-10-25] 返回退出搜索时同样记历史
            commitSearch(searchQuery)
            isSearching = false
            searchQuery = ""
        }

        // === Tablet Master-Detail Layout ===
        // [V8.99] The navigation-bar inset is consumed here, at the page root. This screen is the
        // only top-level screen built WITHOUT a Scaffold, so nothing was insetting the
        // gesture / 3-button navigation bar and bottom-anchored content sat underneath it.
        Box(modifier = Modifier.fillMaxSize()) {
            // [氛围背景] 同手机分支：封面取色色斑漂移，垫在双栏之下
            AmbientFlowBackground(currentSong = currentPlayingSong)
            Row(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .navigationBarsPadding()
            ) {
            // Left: Song list + top bar (60%)
            // [2026-10-31] 去掉原本铺满整列的半透明「淡色框」：它无圆角、贴着屏幕边缘、与右侧
            // 详情面板（16dp 外框）边距不对齐，观感割裂。改为与右栏同套 16dp 外框边距，
            // 让列表卡片直接浮在氛围背景上（与手机端一致），由内部圆角玻璃卡片承担分区。
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // === Top App Bar (compact for tablet left pane) ===
                SDWTopBar(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    title = {
                        if (isSearching) {
                            // [M3 Expressive · 容器式搜索框] OutlinedTextField → TextField（filled 容器、无边框、surfaceContainerHighest 底色）
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.songlist_search_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    cursorColor = MaterialTheme.colorScheme.tertiary,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
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
                        // [2026-10-29 · M3 Expressive] 裸 IconButton → FilledTonalIconButton。
                        // 全站其它页顶栏都是带容器的 tonal 键，只有主界面是一排"飘着的裸图标"，
                        // 同一个 App 两套顶栏键。tonal 默认 40dp 容器，比裸 IconButton 的
                        // 48dp 触摸框还小一点，不会让顶栏更挤。
                        TopBarTonalIcon(
                            onClick = { detailPaneVisible = false },
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.songlist_close_details)
                        )
                        if (isSearching) {
                            TopBarTonalIcon(
                                onClick = { isSearching = false; searchQuery = "" },
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.songlist_close_search),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            TopBarTonalIcon(
                                onClick = { isSearching = true },
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                            SortMenuButton(
                                sortMode = sortMode,
                                onSortModeChange = { setSortMode(it) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TopBarTonalIcon(
                                onClick = onRefresh,
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                            TopBarTonalIcon(
                                onClick = onNavigateToPlaylist,
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = stringResource(R.string.title_playlists)
                            )
                            TopBarTonalIcon(
                                onClick = onNavigateToSettings,
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_settings)
                            )
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
                        SmartTile(stringResource(R.string.songlist_tile_favorites), "$favoriteCount", Icons.Default.Favorite, MaterialTheme.colorScheme.error, isSelected = filterMode == "favorites", onClick = { filterMode = "favorites" })
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
        // 【液态玻璃】胶囊尺寸模糊层：把全屏 backdropLayer 平移 -miniShift 录下迷你条正后方的列表，
        // 喂给 Kyant Backdrop 的 lens 折射（liquidGlassLens），得到真·液态玻璃而非便携玻璃。
        // 仅 API31+ 才有真模糊，低版本传 null 走原便携玻璃。
        val capsuleLayer = if (realBlurSupported) rememberGraphicsLayer() else null
        // 【性能修复】全屏 BlurEffect 图层只在「列表内容真变了」时重录，
        // 不再被正在播放行的 60fps 无限动画（光晕呼吸 / 跳动条）每帧触发整列重模糊。
        // 否则空闲时 GPU 常驻 ~39%，飞回列表的转场动画抢不到预算而掉帧。
        // 用 AtomicBoolean 而非 State：在 draw 阶段读改写不会触发重组，避免重录→重组→再重录的死循环。
        val backdropDirty = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
        LaunchedEffect(listState) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.distinctUntilChanged().collect { backdropDirty.set(true) }
        }
        LaunchedEffect(currentPlayingSong) { backdropDirty.set(true) }
        // 【性能修复·转场】共享元素飞行（进/出播放页）期间冻结列表的 60fps 无限动画，
        // 否则正在播放行的光晕呼吸 + 跳动条会驱动整列每帧重绘，与飞行动画抢 GPU 致顿挫。
        // coverAnimating 翻转只在转场起止两次触发重组（替代持续 60fps 重绘）。
        val coverAnimating = sharedCoverState?.isAnimating == true
        LaunchedEffect(sharedCoverState?.isAnimating) {
            // 转场结束后刷新一次全屏模糊，覆盖转场期间可能的列表滚动/在播行变化。
            if (sharedCoverState?.isAnimating != true) backdropDirty.set(true)
        }
        val blurRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
        if (realBlurSupported) {
            SideEffect {
                // 【V9.02 性能】转场（飞入/飞出播放页）期间冻结全屏模糊：返回时播放页整页
                // scaleOut+fadeOut 转场与列表模糊同时渲染会超出弱 GPU 帧预算，导致飞回动画掉帧。
                // 转场中直接画已录好的清晰图层（省掉每帧全屏 22dp 高斯模糊）；coverAnimating
                // 翻转触发一次重组即恢复模糊。转场时迷你条本就被播放页盖住，几乎不可见，无感。
                backdropLayer.renderEffect = if (coverAnimating) null
                    else BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
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
            // [氛围背景] 封面 Palette 取色 → 3 个 RadialGradient 色斑缓慢漂移（RawS RawFlowBackground 同款）。
            // 垫在列表与迷你条之下：取色走 MemoryManager 缓存 + 后台线程，组合阶段零解码；
            // 页面退后台自动冻结漂移。无在播歌曲时不组合，零开销。
            AmbientFlowBackground(currentSong = currentPlayingSong)
        // [V8.77] 搜索态下系统返回键先退出搜索回到主界面，而不是把 App 最小化。
        // 位置放在平板详情面板的 BackHandler 之后，组合更晚 → 优先级更高，
        // 保证「搜索 + 详情面板同时开着」时先关搜索。非搜索态不拦截，保留系统默认行为。
        BackHandler(enabled = isSearching) {
            // [2026-10-25] 返回退出搜索时同样记历史
            commitSearch(searchQuery)
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
                        if (backdropDirty.get()) {
                            val contentScope = this
                            backdropLayer.record { contentScope.drawContent() }
                            backdropDirty.set(false)
                        }
                        drawContent()
                    } else Modifier
                )
        ) {
            // === Top App Bar ===
            SDWTopBar(
                modifier = Modifier.padding(horizontal = 12.dp),
                title = {
                    if (isSearching) {
                        // [M3 Expressive · 容器式搜索框] OutlinedTextField → TextField（filled 容器、无边框、surfaceContainerHighest 底色）
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_songs), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            // [2026-10-25] 软键盘「搜索」键：落一条历史 + 收键盘看结果
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                commitSearch(searchQuery)
                                focusManager.clearFocus()
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.tertiary,
                                // [2026-10-29] 搜索框也走玻璃：实色容器在浅色分支下是一块"白条"，
                                // 与卡片/胶囊抢视觉重点。降成半透明玻璃容器 + 主题描边。
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // [V8.76] 折叠式标题：下滑时字号/不透明度随折叠进度收缩，标题吸顶变紧凑，
                            // 释放纵向空间。100% 展开时仍是原来的大标题观感。
                            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                            val baseFontSize = when {
                                cfg.screenWidthDp < 400 -> 28.sp
                                cfg.screenWidthDp < 600 -> 32.sp
                                else -> 34.sp
                            }
                            val collapse = topBarScroll.state.collapsedFraction
                            val titleFontSize = baseFontSize * (1f - 0.22f * collapse)
                            Text(
                                stringResource(R.string.brand_name),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                fontSize = titleFontSize,
                                lineHeight = titleFontSize * 1.1f,
                                letterSpacing = (-0.5).sp,
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
                        TopBarTonalIcon(
                            onClick = {
                                // [2026-10-25] 退出搜索时把本次关键词记进历史
                                commitSearch(searchQuery)
                                isSearching = false; searchQuery = ""
                            },
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.songlist_close_search),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        SortMenuButton(
                            sortMode = sortMode,
                            onSortModeChange = { setSortMode(it) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // [fix] 顶部入口收进「更多」菜单，避免 7 个图标把标题空间挤光导致应用名被遮挡
                        // [2026-10-29 · M3 Expressive] DropdownMenu → ModalBottomSheet：
                        // 旧实现还得靠 offset = DpOffset(x = -80.dp) 手动往左挪才不出屏，
                        // 5 项挤成窄长条。改成底部抽屉，行高 56dp、单手可达。
                        var showMoreSheet by remember { mutableStateOf(false) }
                        FilledTonalIconButton(
                            onClick = { showMoreSheet = true },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                        }
                        if (showMoreSheet) {
                            ModalBottomSheet(
                                onDismissRequest = { showMoreSheet = false },
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                        .padding(bottom = 16.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.action_more),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    SheetOptionRow(
                                        label = stringResource(R.string.action_refresh),
                                        icon = Icons.Default.Refresh,
                                        selected = false,
                                        onClick = { showMoreSheet = false; onRefresh() }
                                    )
                                    SheetOptionRow(
                                        label = stringResource(R.string.title_playlists),
                                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                                        selected = false,
                                        onClick = { showMoreSheet = false; onNavigateToPlaylist() }
                                    )
                                    SheetOptionRow(
                                        label = stringResource(R.string.title_albums),
                                        icon = Icons.Default.Album,
                                        selected = false,
                                        onClick = { showMoreSheet = false; onNavigateToAlbum() }
                                    )
                                    SheetOptionRow(
                                        label = stringResource(R.string.title_artists),
                                        icon = Icons.Default.Person,
                                        selected = false,
                                        onClick = { showMoreSheet = false; onNavigateToArtist() }
                                    )
                                    SheetOptionRow(
                                        label = stringResource(R.string.title_folders),
                                        icon = Icons.Default.Folder,
                                        selected = false,
                                        onClick = { showMoreSheet = false; onNavigateToFolder() }
                                    )
                                }
                            }
                        }
                        FilledTonalIconButton(
                            onClick = onNavigateToSettings,
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.Settings, stringResource(R.string.action_settings))
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
            //
            // [2026-10-28] 与状态栏留呼吸感：顶栏折叠时这个胶囊会被顶到状态栏下沿，
            // 「Search…」二字几乎贴住时间/挖孔。按顶栏折叠进度补回一段状态栏高度的
            // 间距；顶栏展开时也给 8dp 固定呼吸位（状态栏离得更远，多一点无妨）。
            val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getBottom(this).toDp() }
            val searchTopGap = maxOf(statusBarHeight * topBarScroll.state.collapsedFraction, 8.dp)
            if (!isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp + searchTopGap, bottom = 2.dp)
                        .clip(SDWShapes.Pill)
                        // [2026-10-25] 用户要求搜索框更透：0.55→0.25，让氛围背景透出来
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f), SDWShapes.Pill)
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
                    SmartTile(stringResource(R.string.songlist_tile_favorites), "$favoriteCount", Icons.Default.Favorite, MaterialTheme.colorScheme.error, isSelected = filterMode == "favorites", pill = true, onClick = { filterMode = "favorites" })
                }
            }

            // === [2026-10-25] 搜索历史面板：搜索态且输入为空时占据 Smart Tiles 的位置 ===
            if (isSearching && searchQuery.isBlank()) {
                SearchHistoryPanel(
                    history = searchHistory,
                    onPick = { q -> searchQuery = q },
                    onRemove = { q ->
                        SearchHistoryStore.remove(context, q)
                        searchHistory.remove(q)
                    },
                    onClearAll = {
                        SearchHistoryStore.clear(context)
                        searchHistory.clear()
                    }
                )
            }

            // === Scan Progress Bar (visible whenever scanning) ===
            // [P2 · M3 Expressive] 线性直条 → 波浪条：全站进度语言统一（播放条已是波浪）。
            // 扫描是「有确定比例」的进度，所以这里传真实 fraction、不开 indeterminate。
            if (isScanningNow) {
                ExpressiveWavyProgress(
                    fraction = scanProgress,
                    color = MaterialTheme.colorScheme.primary,
                    active = true,
                    showTrack = true,
                    amplitude = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .padding(horizontal = 16.dp)
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
            // [2026-10-31] 列表直接浮在氛围背景上（透明容器，无「淡色框」）；内部单首歌取消独立
            // 玻璃卡片（flat=true → 干净平铺行 + 点击水波纹），消除卡片堆叠噪声。A-Z 索引与
            // 迷你条按原边距各自定位。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // [2026-10-28] 主卡片改为「透明玻璃」：左右内收、右侧给 A-Z 索引留独立轨道，
                    // 卡片左缘 16dp、右缘 52dp（索引栏占右侧 8~28dp，与卡片右缘恒有 24dp 空隙）；
                    // 底部留白 104dp 让 Mini-Player 浮岛与主容器拉开间距。
                    .padding(start = 16.dp, end = 52.dp)
                    .padding(bottom = 104.dp),
                shape = RoundedCornerShape(28.dp),
                // [2026-10-31] 去掉半透明「淡色框」：列表直接浮在氛围背景上（透明容器），A-Z 与迷你条按原边距定位。
                color = Color.Transparent,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                if (displayedSongs.isEmpty() && !isSearching) {
                    LibraryEmptyState(
                        filterMode = filterMode,
                        isScanning = isScanningNow,
                        onRefresh = onRefresh,
                        onClearFilter = { filterMode = "all"; searchQuery = "" }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 外层已是大圆角容器，内部只留紧凑内边距。
                        // [2026-10-28] 右侧不再需要给索引栏留位：索引栏已移到大容器外侧，
                        // 内部只留 12dp 的行尾呼吸，避免歌名/时长贴边。
                        contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
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
                                                        "format" -> R.string.songlist_sort_format
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
                                SongItem(
                                    song = song,
                                    index = 0,
                                    isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                                    accentColor = activeColor,
                                    onClick = {
                                        // [2026-10-25] 搜索态点结果：该关键词记入历史
                                        if (isSearching) commitSearch(searchQuery)
                                        onSongClick(song)
                                    },
                                    onLongClick = { longPressSong = song },
                                    coverFlight = sharedCoverState?.rowFlightSongId == song.id,
                                    coverAnimating = coverAnimating,
                                    flat = true,
                                    modifier = Modifier
                                )
                            }
                        }
                    }
                }
            }
            // [2026-10-28] 原来的「N songs」歌曲计数删掉：它落在列表容器底部的 88dp 悬浮带里
            // （那是 Mini-Player 浮岛的位置），于是文字直接被迷你条压住、糊成一团。
            // 顶部胶囊行已经给出各分类的数量，信息不重复。
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
                    // [2026-10-28 修订] 索引栏待在大容器外侧的留白带里：右边距 8dp、栏宽
                    // 20dp → 占屏幕右侧 8~28dp，与容器右缘（52dp）恒有 24dp 干净空隙。
                    .padding(end = 8.dp)
                    .width(20.dp)
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
                    capsuleLayer = capsuleLayer,
                    coverAnimating = coverAnimating,
                    blurRadiusPx = blurRadiusPx,
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

    // 【V8.111b】长按菜单背景模糊：ModalBottomSheet 自带 50% 黑 scrim 但未模糊，
    // 背后列表文字会透上来与菜单文字重叠看不清。在列表之上、弹层之下铺一层已录好的
    // 全屏模糊快照 backdropLayer（迷你条同款，API31+ 才有真模糊），由 scrim 压暗 → 毛玻璃透出列表。
    // 低版本（无 RenderEffect）保持原 50% 黑 scrim，不退化。
    if (longPressSong != null && realBlurSupported) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent { drawLayer(backdropLayer) }
        )
    }
    LaunchedEffect(longPressSong) {
        // 弹层打开瞬间刷新一次列表快照，保证模糊层是当前的列表。
        if (longPressSong != null) backdropDirty.set(true)
    }

    // 【V8.7】长按添加歌单
    longPressSong?.let { song ->
        AddToPlaylistSheet(
            song = song,
            onDismiss = { longPressSong = null },
            onEditWithLyrico = onEditWithLyrico,
            // [2026-10-25] 长按菜单「删除」入口：先关菜单，再弹二次确认
            onDelete = { songToDelete = song }
        )
    }

    // [2026-10-25] 删除二次确认（不可恢复操作，必须拦一道）
    songToDelete?.let { song ->
        // [P2 · M3 Expressive] 全站弹窗统一到 SDWAlertDialog（玻璃容器 + Panel 圆角）
        SDWAlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text(stringResource(R.string.song_delete_confirm_title)) },
            text = { Text(stringResource(R.string.song_delete_confirm_message, song.title)) },
            confirmButton = {
                TextButton(onClick = {
                    songToDelete = null
                    onDeleteSong(song)
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
                    val itemMod = Modifier
                    SongItem(
                        song = song,
                        index = 0,
                        isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                        accentColor = activeColor,
                        onClick = { onSongClick(song) },
                        onLongClick = { onLongClick(song) },
                        flat = true,
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


/**
 * [2026-10-31] 选中态主题色降饱和：把强调色朝同亮度的灰混合，保留色相、降低彩度，
 * 避免选中 Smart Tile 时整颗胶囊过艳。amount=0 原色，1 全灰。
 */
private fun Color.softenSaturation(amount: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lum = (max + min) / 2f
    return Color(
        red = red + (lum - red) * amount,
        green = green + (lum - green) * amount,
        blue = blue + (lum - blue) * amount,
        alpha = alpha
    )
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
    // [2026-10-31] 选中态用降饱和后的强调色，避免整颗胶囊过艳（保留色相、彩度降到 60%）。
    val accent = if (isSelected) color.softenSaturation(0.4f) else color
    val contentColor = when {
        isSelected -> accent
        !onWallpaper -> MaterialTheme.colorScheme.onSurfaceVariant
        wpLight -> Color.Black.copy(alpha = 0.78f)
        else -> Color.White.copy(alpha = 0.85f)
    }
    // V8.59 - Android 17 look: the filter chips are glass panes with spring feedback
    // instead of flat 8dp blocks. Count sits under the icon; selected borrows the tile
    // colour for both the wash and the lit edge.
    val interaction = remember { MutableInteractionSource() }

    // [M3 Expressive · Phase 3] 真·Expressive Chip：选中/未选中必须是**两块不同的实心色**，
    // 形成色块冲突，而不是同一层薄玻璃上改个描边——旧实现的选中态只有 0.20 alpha，
    // 四颗胶囊滚起来糊成一片，完全没有"我选了哪个"。
    //   选中   = 该 tile 的主色容器（primaryContainer 级别填充 + 主色实心描边）
    //   未选中 = surfaceContainerHigh 中性块（比页面底色高一级，看得见但不出戏）
    val tileShape = if (pill) SDWShapes.Pill else SDWShapes.Row
    val tileFill = when {
        isSelected -> accent.copy(alpha = if (onWallpaper) 0.85f else 0.5f)
        onWallpaper -> (if (wpLight) Color.White else Color.Black).copy(alpha = 0.24f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
    }
    val tileBorder = if (isSelected) accent.copy(alpha = 0.95f)
                     else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            // [V8.78] pill 也走等分：原来固定 112dp x 4 + 间距 = ~480dp，超出手机可用宽度
            // （427dp），配 horizontalScroll 就呈现「第 4 颗半露在右边缘」。等分后四颗完整占满。
            .weight(1f)
            // Expressive 的 chip 更"有体量"：48 → 52dp
            .height(if (pill) 52.dp else 56.dp)
            .then(
                if (pill) {
                    Modifier
                        .clip(tileShape)
                        .background(tileFill, tileShape)
                        .border(
                            width = if (isSelected) 1.6.dp else 1.dp,
                            color = tileBorder,
                            shape = tileShape
                        )
                } else {
                    Modifier.glassSurface(
                        shape = SDWShapes.Row,
                        accent = accent,
                        selected = isSelected,
                        // V8.68 - shadow() opens a graphics layer whose shadow silhouette showed
                        // through the translucent fill of a 56dp tile as an inset rectangle.
                        elevation = 0.dp
                    )
                }
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
                    // Expressive：选中态字重拉满，与未选中形成字重落差
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.ExtraBold
                                 else androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (isSelected) (if (onWallpaper) accent else MaterialTheme.colorScheme.onSurface) else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    color = if (isSelected) (if (onWallpaper) accent else MaterialTheme.colorScheme.onSurface) else contentColor.copy(alpha = 0.72f),
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
                isFilter -> FilledTonalButton(onClick = onClearFilter) {
                    Text(stringResource(R.string.songlist_tile_all))
                }
                else -> FilledTonalButton(onClick = onRefresh) {
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
    // [V8.117b] 转场（进/出播放页）期间为 true：让正在播放行的跳动条冻结，不再 60fps
    // 驱动整列每帧重绘、与共享元素飞行动画抢 GPU。由 SongListScreen 在调用处传入。
    coverAnimating: Boolean = false,
    // [M3 Expressive · 主界面区块化] flat=true：去掉每行独立玻璃卡片，改成干净平铺行
    // （仅点击有水波纹、正在播放行保留微弱强调底色 + 描边），用于「大区块容器」内部。
    flat: Boolean = false,
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
            .then(
                if (flat) {
                    // [M3 Expressive · 主界面区块化] 干净平铺行：取消独立卡片底，仅播放行带微弱强调底色，
                    // 其余行完全融进大区块容器，消除几十张卡片堆叠的视觉噪声。
                    Modifier.then(
                        if (isPlaying) Modifier.background(
                            color = accentColor.copy(alpha = 0.12f),
                            shape = rowShape
                        ) else Modifier
                    )
                } else {
                    Modifier.glassSurface(
                        shape = rowShape,
                        accent = accentColor,
                        selected = isPlaying,
                        elevation = 0.dp,
                        fillBoost = if (onWallpaper) 0f else 0.04f
                    )
                }
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
                indication = if (flat) LocalIndication.current else null,
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
                NowPlayingBars(accentColor, animated = !coverAnimating)
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
    // 【液态玻璃】胶囊尺寸模糊层（已用 miniShift 对齐），非空时迷你条走真·折射玻璃。
    capsuleLayer: GraphicsLayer? = null,
    // 共享元素飞行（进/出播放页）期间冻结全屏模糊，这里同步冻结胶囊层折射，避免转场掉帧。
    coverAnimating: Boolean = false,
    blurRadiusPx: Float = 0f,
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
    var capsuleSize by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // [玻璃增强] 陀螺仪倾斜驱动高光角度：手机倾斜时迷你条边缘的流光跟着流动
    // （HyperOS 标志效果，实现适配自 Miuix DeviceTilt；传感器仅在界面可见时注册）
    // 刻意**不用 by 解包**：State 在下面的 draw 阶段 lambda（glassHighlight 的 angle）里读取，
    // 这样传感器 50fps 更新只会 invalidate draw，不会重组整条 MiniPlayer。
    val deviceTilt = rememberDeviceTilt(smoothing = 0.12f)
    SideEffect {
        capsuleLayer?.let { layer ->
            if (capsuleSize != IntSize.Zero) {
                val blur = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
                val lens = liquidGlassLens(
                    Size(capsuleSize.width.toFloat(), capsuleSize.height.toFloat()),
                    with(density) { 26.dp.toPx() },
                    with(density) { 18.dp.toPx() },
                    with(density) { 20.dp.toPx() }
                )
                layer.renderEffect = if (coverAnimating) null else (lens?.let { blur.chain(it) } ?: blur)
                layer.compositingStrategy = CompositingStrategy.Offscreen
            }
        }
    }
    // [V8.112] 模糊层之上再压的一层极薄主题色薄纱：统一色调、压住过碎的细节，
    // 读起来是透亮的液态玻璃而不是"一块死板的黑/白"。
    val blurVeil = MaterialTheme.colorScheme.surface.copy(alpha = 0.07f)
    // [M3 Expressive · Phase 3] 真·Floating Pill：悬浮感来自「抬升的实心色块 + 投影」，
    // 不是「半透明玻璃压在列表上」。旧值 surface@0.55 太透，背后列表会穿上来与迷你条
    // 叠影，读起来就是"穿模"。改用 surfaceContainerHighest（比列表底色高两级）@0.88，
    // 先把条子变成一块明确的抬升表面，玻璃光泽再叠在上面。
    // [V8.113] 必须在 Composable 作用域先取值，drawWithContent 的 lambda 里不能调用
    // @Composable 的 MaterialTheme.colorScheme（会报编译错）。
    // [2026-10-29 · 真·液体玻璃] 0.88 的实心底是为了压住背后列表的"穿模"，代价就是
    // 看起来像一条不透的灰块。底盘锁深色 + 底下已有真·高斯模糊层后，这里可以放心减薄
    // 到 0.34：背后是糊开的列表（不再是能读的文字），透出来才有液态玻璃的通透与折射感。
    val miniBase = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.34f)
    // 真·胶囊：半径取条高的一半级别（32dp），而不是通用 Panel 的 26dp。
    val miniShape = RoundedCornerShape(32.dp)
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            // [V8.109] 悬浮毛玻璃胶囊：四周留呼吸间距（左右 16、上下不对称让底部更重），
            // pane fill 加厚、圆角收到胶囊级、微投影抬高，读起来是浮在列表之上的独立一层。
            // （真·背景模糊需 API31+，minSdk 24 会在大量机型静默失效，故走便携版玻璃。）
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp)
            // V8.59 - the mini player is the most floaty element on the main screen, so it
            // keeps a real shadow and gets the accent-lit edge.
            // [M3 Expressive · Phase 3] 抬高到 32dp：悬浮的胶囊需要一份"敢投影"的 elevation，
            // 22dp 在抬升后的实心底上几乎看不出来。
            .glassSurface(
                shape = miniShape,
                accent = accentColor,
                // [2026-10-31] 液态玻璃感：阴影减薄（32→18dp），让胶囊读起来是浮在列表上的
                // 薄玻璃，而不是压着一道深投影的实心条。
                elevation = 18.dp,
                accentEdge = true,
                fillBoost = 0.09f,
                // [V8.112] 底下已贴真·模糊内容时，自身底色降到极薄薄纱（见 Glass.kt）。
                blurBacked = backdropLayer != null
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .onGloballyPositioned { coords ->
                miniShift = coords.positionInRoot() - containerOrigin
                capsuleSize = coords.size
            }
            .then(
                // [V8.113] 胶囊底层始终铺一层不透明磨砂底，挡住背后常驻的壁纸/主题渐变，
                // 否则迷你条（自身极薄填充）会把背景透上来，与模糊列表叠影。
                // API31+ 在此底之上再贴真·模糊列表；低版本只显示这层磨砂底。
                Modifier.drawWithContent {
                    // [V8.112b] 按节点真实尺寸直接裁胶囊（与 glassSurface 的 miniShape 32dp 对齐），
                    // 避免四周留出未糊的生硬竖条。
                    val rad = 32.dp.toPx()
                    val capsule = Path().apply {
                        addRoundRect(
                            RoundRect(0f, 0f, size.width, size.height, CornerRadius(rad, rad))
                        )
                    }
                    clipPath(capsule) {
                        // [2026-10-25] 半透明磨砂底（0.42）：不再死挡背景，让氛围渐变 +
                        // 模糊列表一起透上来 = 磨砂玻璃观感（旧值 0.95 是全黑一块的根源）。
                        drawPath(capsule, miniBase)
                        if (capsuleLayer != null && backdropLayer != null) {
                            // 【液态玻璃】把全屏 backdropLayer 平移 -miniShift 裁出迷你条正后方列表，
                            // 录进胶囊尺寸层，drawLayer 时叠加 blur+lens 折射（见 SideEffect）。
                            capsuleLayer.record(
                                size = IntSize(size.width.toInt(), size.height.toInt())
                            ) {
                                translate(left = -miniShift.x, top = -miniShift.y) {
                                    drawLayer(backdropLayer)
                                }
                            }
                            drawLayer(capsuleLayer)
                        } else if (backdropLayer != null) {
                            translate(left = -miniShift.x, top = -miniShift.y) {
                                drawLayer(backdropLayer)
                            }
                        }
                    }
                    // [V8.112] 薄纱压在模糊层之上、内容之下：色调统一 + 提升可读性
                    drawPath(capsule, blurVeil)
                    drawContent()
                }
            )
            // [玻璃增强] 完整版 Kyant：内阴影（玻璃厚度感）+ 倾斜驱动的高光流光。
            // 两个 Element 都不依赖 Backdrop 模型，画在内容之上；API<31 自动降级为零开销。
            // 强度参数走 GlassEnhancements 默认值（高光 2dp 宽、内阴影 alpha 0.45）：
            // 库自带默认（0.5dp / 0.15）经 alpha 相乘后实际接近不可见，这里已放大。
            .glassInnerShadow(shape = { miniShape })
            .glassHighlight(
                shape = { miniShape },
                // 在 draw 阶段求值（见上：避免 50fps 重组）。
                // roll 为弧度，转成角度；倾斜 ±30° 时高光随之扫过边缘一圈。
                angle = { 45f + deviceTilt.value.roll * (180f / kotlin.math.PI.toFloat()) }
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
            isPlaying = isPlaying,
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
            onShare = onShare,
            accentColor = accentColor
        )
    }
}
