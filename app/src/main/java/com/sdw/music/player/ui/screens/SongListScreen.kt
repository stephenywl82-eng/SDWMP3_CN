package com.sdw.music.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.sdw.music.player.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.res.painterResource
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.animation.CoverPosition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.ui.theme.*
import androidx.media3.common.Player
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

import com.sdw.music.player.MusicService


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
    var isSearching by remember { mutableStateOf(false) }
    var filterMode by remember { mutableStateOf("all") }
    val activeColor = if (accentColor != 0L) Color(accentColor) else MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentThreshold = System.currentTimeMillis() / 1000 - 7 * 24 * 3600

    // Filter songs by search + filter mode
    val displayedSongs = remember(songs, searchQuery, filterMode) {
        val base = if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
        when (filterMode) {
            "hires" -> base.filter { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC", "AIFF", "APE", "WAVPACK") }
            "recent" -> base.filter { it.lastPlayedAt > recentThreshold }
            "favorites" -> base.filter { it.isFavorite }
            else -> base
        }
    }

    // Count Hi-Res songs (FLAC / WAV / DSD)
    val hiresCount = remember(songs) { songs.count { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC") } }
    // Count recent (added within 7 days)
        val recentCount = remember(songs) { songs.count { it.lastPlayedAt > recentThreshold } }
    // Count favorites
    val favoriteCount = remember(songs) { songs.count { it.isFavorite } }

    // Group by first char (A-Z + #)
    val groupedSongs = remember(displayedSongs) {
        displayedSongs.groupBy { song ->
            val c = song.title.firstOrNull()?.uppercase()?.firstOrNull() ?: '#'
            if (c in 'A'..'Z') c else '#'
        }.toSortedMap()
    }
    val alphabet = ('A'..'Z').toList() + '#'
    val listState = rememberLazyListState()
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

        // === Tablet Master-Detail Layout ===
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            // Left: Song list + top bar (60%)
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                // === Top App Bar (compact for tablet left pane) ===
                TopAppBar(
                    title = {
                        if (isSearching) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.songlist_search_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        } else {
                            Column {
                                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
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
                    scope = scope
                )
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
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // === Top App Bar ===
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_songs), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                    } else {
                        Column {
                            Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                            Text(stringResource(R.string.songlist_song_count, songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (isSearching) {
                        IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, stringResource(R.string.songlist_close_search), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, stringResource(R.string.action_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToPlaylist) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.title_playlists), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToAlbum) {
                            Icon(Icons.Default.Album, stringResource(R.string.title_albums), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToArtist) {
                            Icon(Icons.Default.Person, stringResource(R.string.title_artists), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToFolder) {
                            Icon(Icons.Default.Folder, stringResource(R.string.title_folders), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, stringResource(R.string.action_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )

            // === Smart Tiles ===
            if (!isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmartTile(stringResource(R.string.songlist_tile_all), "${songs.size}", Icons.Default.MusicNote, MaterialTheme.colorScheme.primary, isSelected = filterMode == "all" || (filterMode != "hires" && filterMode != "recent" && filterMode != "favorites"), onClick = { filterMode = "all"; searchQuery = "" })
                    SmartTile(stringResource(R.string.songlist_tile_hires), "$hiresCount", Icons.Default.HighQuality, MaterialTheme.colorScheme.primary, isSelected = filterMode == "hires", onClick = { filterMode = "hires"; searchQuery = "" })
                    SmartTile(stringResource(R.string.songlist_tile_recent), "$recentCount", Icons.Default.Schedule, MaterialTheme.colorScheme.secondary, isSelected = filterMode == "recent", onClick = { filterMode = "recent" }) // TODO: genre filter not available
                    SmartTile(stringResource(R.string.songlist_tile_favorites), "$favoriteCount", Icons.Default.Favorite, AccentRed, isSelected = filterMode == "favorites", onClick = { filterMode = "favorites" })
                }
            }

            // === Scan Progress Bar (visible whenever scanning) ===
            val isScanning = scanStatus.startsWith("scanning") || (scanProgress > 0f && scanProgress < 1f)
            if (isScanning) {
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
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote,
                            null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (scanProgress > 0f) stringResource(R.string.songlist_scanning) else stringResource(R.string.songlist_no_songs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (scanProgress > 0f) stringResource(R.string.songlist_scanning) else stringResource(R.string.songlist_check_files),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        if (scanProgress <= 0f) {
                            Text("Status: $scanStatus", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = onRefresh) {
                                Text(stringResource(R.string.songlist_scan))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.dp),
                    contentPadding = PaddingValues(end = 32.dp),
                    state = listState
                ) {
                    groupedSongs.forEach { (letter, songsInGroup) ->
                        stickyHeader {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                            ) {
                                Text(letter.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                        itemsIndexed(songsInGroup, key = { _, song -> song.id }) { _, song ->
                            SongItem(
                                song = song,
                                index = 0,
                                isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                                accentColor = activeColor,
                                onClick = { onSongClick(song) },
                                onLongClick = { }
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
        if (displayedSongs.size > 20) {
            val sidebarHeight = alphabet.size * 22
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
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
                                else if (hasGroup) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.clickable(enabled = hasGroup) {
                            groupFirstIndices[letter]?.let { idx ->
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        }
                    )
                }
            }
        }

        // === Mini Player ===
        if (currentPlayingSong != null) {
            val miniCoverUri = currentPlayingSong.albumArtUri
            MiniPlayer(
                song = currentPlayingSong,
                isPlaying = isPlaying,
                accentColor = activeColor,
                coverUri = miniCoverUri,
                coverVisible = miniCoverVisible,
                positionMs = positionMs,
                durationMs = durationMs,
                onClick = onNavigateToPlayer,
                onCoverPositioned = { offset, size ->
                    sharedCoverState?.miniCoverPosition = CoverPosition(
                        windowOffset = offset,
                        size = size
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            // Device brand watermark when mini player is hidden
            Text(
                text = deviceName.ifEmpty { stringResource(R.string.brand_audio) },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Text(letter.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                itemsIndexed(songsInGroup, key = { _, song -> song.id }) { _, song ->
                    SongItem(
                        song = song,
                        index = 0,
                        isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                        accentColor = activeColor,
                        onClick = { onSongClick(song) },
                        onLongClick = { }
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
                            else if (hasGroup) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.clickable(enabled = hasGroup) {
                        groupFirstIndices[letter]?.let { idx ->
                            scope.launch { listState.animateScrollToItem(idx) }
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
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant
    
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
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




@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    accentColor: Color,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val textColor = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val subColor = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover art — DefaultCoverImage underneath, actual cover on top
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            DefaultCoverImage(song.title, song.artist, Modifier.size(48.dp), CircleShape)
            val painter = rememberAsyncImagePainter(
                model = remember(song.albumArtUri) {
                    ImageRequest.Builder(context)
                        .data(song.albumArtUri)
                        .size(96, 96)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                },
                contentScale = ContentScale.Crop
            )
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(Modifier.width(12.dp))
        
        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Duration
        Text(
            text = formatDuration(song.duration),
            style = MaterialTheme.typography.labelSmall,
            color = subColor
        )
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
    modifier: Modifier = Modifier
) {
    if (song == null) return
    
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
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
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
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f },
                contentAlignment = Alignment.Center
            ) {
                DefaultCoverImage(song.title, song.artist, Modifier.size(40.dp), RoundedCornerShape(4.dp))
                val painter = rememberAsyncImagePainter(
                    model = coverUri ?: song.albumArtUri,
                    contentScale = ContentScale.Crop
                )
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
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
                    text = song.artist,
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
                .clip(RoundedCornerShape(1.dp))
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
                    .clip(RoundedCornerShape(12.dp))
            ) {
                // DefaultCoverImage underneath, actual cover on top
                DefaultCoverImage(song.title, song.artist, Modifier.fillMaxSize(), RoundedCornerShape(12.dp))
                AsyncImage(
                    model = song.albumArtUri,
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
            text = song.artist,
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
