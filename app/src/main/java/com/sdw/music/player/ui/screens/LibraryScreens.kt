package com.sdw.music.player.ui.screens

// [UI-TIDY] 原名 PlaceholderScreens.kt，有误导性：这里装的是真实页面（文件夹列表 / 文件夹详情 / 歌单列表），
// 不是占位页。改名为 LibraryScreens.kt。
import androidx.compose.foundation.clickable
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import coil.compose.AsyncImage
import com.sdw.music.player.ui.theme.DarkBg
import com.sdw.music.player.ui.theme.DarkSurface
import com.sdw.music.player.ui.theme.DarkCard
import com.sdw.music.player.ui.theme.TextPrimary
import com.sdw.music.player.ui.theme.TextSecondary
import androidx.compose.foundation.combinedClickable
import com.sdw.music.player.ui.theme.AccentRed
import com.sdw.music.player.ui.theme.TextTertiary
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.rememberSharedCoverPainter
import com.sdw.music.player.SongRepository
import com.sdw.music.player.Playlist
import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.PlaylistManager
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.CountKind
import com.sdw.music.player.ui.components.CountText
import com.sdw.music.player.ui.components.LibraryEmptyState
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import com.sdw.music.player.ui.theme.itemTitleStyle
import androidx.compose.foundation.interaction.MutableInteractionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onNavigateBack: () -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit,
    onOpenFolder: (String) -> Unit
) {
    var foldersVersion by remember { mutableStateOf(0L) }
    var longPressSong by remember { mutableStateOf<com.sdw.music.player.Song?>(null) }
    // Observe foldersVersion so folder list + counts refresh after scan/delete
    LaunchedEffect(Unit) {
        com.sdw.music.player.SongRepository.foldersVersion.collect { v ->
            foldersVersion = v
        }
    }

    // 根视图：列出所有存储卷根（主存储 + SD 卡），点进去才是目录树
    val volumeRoots = remember(foldersVersion) {
        com.sdw.music.player.SongRepository.getStorageVolumeRoots()
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_folders), color = MaterialTheme.colorScheme.onBackground)
                        CountText(volumeRoots.size, CountKind.LOCATIONS)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {}
    ) { padding ->
        if (volumeRoots.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                LibraryEmptyState(
                    icon = Icons.Default.Folder,
                    message = stringResource(R.string.placeholder_no_music_folder),
                    hint = stringResource(R.string.placeholder_scan_first)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(volumeRoots, key = { _, r -> r }) { _, root ->
                    val directSongCount = remember(root, foldersVersion) {
                        com.sdw.music.player.SongRepository.getDirectSongsIn(root).size
                    }
                    val subfolderCount = remember(root, foldersVersion) {
                        com.sdw.music.player.SongRepository.getSubfoldersOf(root).size
                    }
                    val totalSongCount = remember(root, foldersVersion) {
                        com.sdw.music.player.SongRepository.getSongsInFolder(root).size
                    }
                    val rootLabel = rootName(root)

                    // [V8.115] 统一玻璃语言：Card -> glassSurface + 弹簧按压。
                    val folderInteraction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                            .springPress(folderInteraction, pressedScale = 0.975f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = folderInteraction
                                ) { onOpenFolder(root) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(SDWShapes.Thumb).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    rootLabel,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.count_songs_and_folders, directSongCount, subfolderCount),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    root,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // 【V8.7】长按添加歌单
    longPressSong?.let { song ->
        com.sdw.music.player.ui.components.AddToPlaylistSheet(
            song = song,
            onDismiss = { longPressSong = null }
        )
    }
}

/** 存储卷根显示名：主存储 → 内部存储，SD 卡 → SD 卡 */
@Composable
private fun rootName(root: String): String {
    val internal = stringResource(R.string.folder_root_internal)
    val sdcard = stringResource(R.string.folder_root_sdcard)
    val lower = root.lowercase()
    return when {
        lower.startsWith("/storage/emulated/0") -> internal
        else -> sdcard
    }
}

// === Playlist List Screen ===
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistListScreen(
    onNavigateBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit,
    onAddToPlaylist: (String) -> Unit = {}
) {
    var playlists by remember {
        mutableStateOf<List<Playlist>>(PlaylistManager.getPlaylists())
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_playlists), color = MaterialTheme.colorScheme.onBackground)
                        CountText(playlists.size, CountKind.PLAYLISTS)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    newPlaylistName = ""
                    showCreateDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.placeholder_new_playlist))
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.placeholder_no_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.placeholder_create_hint),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed<Playlist>(playlists, key = { _, pl: Playlist -> pl.id }) { _, playlist ->
                    val previewSongs = remember(playlist.id) {
                        PlaylistManager.getPlaylistSongs(playlist.id)
                    }
                    val coverUri = remember(playlist.id) {
                        previewSongs.firstOrNull { SongRepository.hasRealCover(it.albumArtUri) }?.albumArtUri ?: ""
                    }

                    // [V8.115] 统一玻璃语言。
                    val plInteraction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                            .springPress(plInteraction, pressedScale = 0.975f)
                            .combinedClickable(
                                interactionSource = plInteraction,
                                indication = null,
                                onClick = {
                                    onOpenPlaylist(playlist.id)
                                },
                                onLongClick = {
                                    showDeleteConfirm = playlist.id
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // [V8.93] 两张图必须叠在同一个 Box 里。V8.92 少了 Box，logo 与真封面
                            // 成了 Row 的兄弟节点、并排显示 —— 表现就是「一行两个封面」。判据同时
                            // 换成 hasRealCover：扫描器给每行都写了非空 albumArtUri（无封面时是
                            // albumart 虚拟表死链接），isNotBlank() 恒真。
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(SDWShapes.Thumb),
                                contentAlignment = Alignment.Center
                            ) {
                                DefaultCoverImage(
                                    songTitle = playlist.name,
                                    songArtist = "",
                                    modifier = Modifier.fillMaxSize(),
                                    shape = SDWShapes.Thumb,
                                    overlayAlpha = 0.2f
                                )
                                if (SongRepository.hasRealCover(coverUri)) {
                                    Image(
                                        painter = rememberSharedCoverPainter(coverUri),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = itemTitleStyle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                CountText(playlist.songIds.size, CountKind.SONGS)
                            }

                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { showDeleteConfirm = playlist.id }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.title_new_playlist), color = MaterialTheme.colorScheme.onBackground) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text(stringResource(R.string.playlist_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            PlaylistManager.createPlaylist(newPlaylistName)
                            playlists = PlaylistManager.getPlaylists()
                            showCreateDialog = false
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_create), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    showDeleteConfirm?.let { playlistId ->
        val pl = playlists.find { it.id == playlistId }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.title_delete_playlist), color = MaterialTheme.colorScheme.onBackground) },
            text = {
                Text(
                    stringResource(R.string.playlist_delete_confirm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaylistManager.deletePlaylist(playlistId)
                        playlists = PlaylistManager.getPlaylists()
                        showDeleteConfirm = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// === Folder Detail Screen（目录树导航：子文件夹在上、歌曲在下） ===
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
    folderPath: String,
    onNavigateBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>, Int) -> Unit
) {
    var foldersVersion by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        com.sdw.music.player.SongRepository.foldersVersion.collect { v ->
            foldersVersion = v
        }
    }

    val subfolders = remember(folderPath, foldersVersion) {
        com.sdw.music.player.SongRepository.getSubfoldersOf(folderPath)
    }
    val directSongs = remember(folderPath, foldersVersion) {
        com.sdw.music.player.SongRepository.getDirectSongsIn(folderPath)
    }
    // 播放本文件夹（含所有子目录）
    val allSongs = remember(folderPath, foldersVersion) {
        com.sdw.music.player.SongRepository.getSongsInFolder(folderPath)
    }

    val folderName = folderPath.substringAfterLast('/')
    var longPressSong by remember { mutableStateOf<com.sdw.music.player.Song?>(null) }
    var longPressFolder by remember { mutableStateOf<String?>(null) }
    // 长按的文件夹的递归歌曲集（弹批量加歌单用）；key 含 longPressFolder 保证切换长按对象时重算
    val longPressFolderSongs = remember(folderPath, foldersVersion, longPressFolder) {
        longPressFolder?.let { com.sdw.music.player.SongRepository.getSongsInFolder(it) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = {
                    Column {
                        Text(folderName, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.count_songs_and_folders, directSongs.size, subfolders.size),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (allSongs.isNotEmpty()) {
                        IconButton(onClick = { onPlaySongs(allSongs, 0) }) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.placeholder_play_folder), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (subfolders.isEmpty() && directSongs.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Default.MusicNote,
                message = stringResource(R.string.playlist_no_folders)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }

                // === 子文件夹（可继续向下点，无限层级） ===
                if (subfolders.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.folder_subfolders),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(subfolders, key = { _, s -> s }) { _, sub ->
                        val subName = sub.substringAfterLast('/')
                        val subSongCount = remember(sub, foldersVersion) {
                            com.sdw.music.player.SongRepository.getSongsInFolder(sub).size
                        }
                        // [V8.115] 统一玻璃语言。
                        val subInteraction = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                                .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
                                .springPress(subInteraction, pressedScale = 0.975f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = subInteraction,
                                        indication = null,
                                        onClick = { onOpenFolder(sub) },
                                        onLongClick = { longPressFolder = sub }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(subName, color = MaterialTheme.colorScheme.onBackground, style = itemTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    CountText(subSongCount, CountKind.SONGS)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // === 本目录直接歌曲 ===
                if (directSongs.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.folder_songs),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(directSongs, key = { _, s -> s.id }) { index, song ->
                        com.sdw.music.player.ui.screens.SongItem(
                            song = song,
                            index = index,
                            isPlaying = false,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                val clickedIdx = directSongs.indexOfFirst { it.id == song.id }
                                onPlaySongs(directSongs, if (clickedIdx >= 0) clickedIdx else 0)
                            },
                            onLongClick = { longPressSong = song }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
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

    // 【V8.35】长按子文件夹 -> 批量添加该文件夹（含子目录）全部歌曲到歌单
    if (longPressFolder != null && longPressFolderSongs.isNotEmpty()) {
        AddToPlaylistSheet(
            song = longPressFolderSongs.first(),
            onDismiss = { longPressFolder = null },
            songs = longPressFolderSongs,
            title = longPressFolder?.substringAfterLast('/')
        )
    }

}


