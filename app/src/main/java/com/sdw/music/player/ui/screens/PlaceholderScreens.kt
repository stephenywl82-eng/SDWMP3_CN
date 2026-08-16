package com.sdw.music.player.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.sdw.music.player.SongRepository
import com.sdw.music.player.Playlist
import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.PlaylistManager
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onNavigateBack: () -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit
) {
    var foldersVersion by remember { mutableStateOf(0L) }
    // Observe foldersVersion so folder list + counts refresh after scan/delete
    LaunchedEffect(Unit) {
        com.sdw.music.player.SongRepository.foldersVersion.collect { v ->
            foldersVersion = v
        }
    }
    val folders = remember(foldersVersion) {
        com.sdw.music.player.SongRepository.getFolders()
    }
    var selectedFolder by remember { mutableStateOf<com.sdw.music.player.Folder?>(null) }
    val accentPurple = MaterialTheme.colorScheme.primary

    // If a folder is selected, show its songs
    if (selectedFolder != null) {
        FolderSongsView(
            folder = selectedFolder!!,
            onBack = { selectedFolder = null },
            onPlaySongs = onPlaySongs
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_folders), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${folders.size} folders",
                            color = MaterialTheme.colorScheme.outlineVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {}
    ) { padding ->
        if (folders.isEmpty()) {
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
                    Text(stringResource(R.string.placeholder_no_music_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.placeholder_scan_first), color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(folders, key = { _, f -> f.path }) { index, folder ->
                    val folderAlbumArt = remember(folder.path) {
                        val songs = com.sdw.music.player.SongRepository.getSongsInFolder(folder.path)
                        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() }?.albumArtUri ?: ""
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { selectedFolder = folder }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (folderAlbumArt.isNotBlank()) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    coil.compose.AsyncImage(
                                        model = folderAlbumArt,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                DefaultCoverImage(
                                    songTitle = folder.name,
                                    songArtist = "",
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    overlayAlpha = 0.2f
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${folder.songCount} songs",
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    folder.path,
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
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_playlists), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)
                        Text(
                            playlists.size.toString() + " playlists",
                            color = MaterialTheme.colorScheme.outlineVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                    Text("Tap + to create", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.bodySmall)
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
                        previewSongs.firstOrNull { it.albumArtUri.isNotBlank() }?.albumArtUri ?: ""
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .combinedClickable(
                                onClick = {
                                    onOpenPlaylist(playlist.id)
                                },
                                onLongClick = {
                                    showDeleteConfirm = playlist.id
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (coverUri.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    coil.compose.AsyncImage(
                                        model = coverUri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                DefaultCoverImage(
                                    songTitle = playlist.name,
                                    songArtist = "",
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    overlayAlpha = 0.2f
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    playlist.songIds.size.toString() + " songs",
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
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

// === Folder Songs View (inline within FolderListScreen) ===
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSongsView(
    folder: com.sdw.music.player.Folder,
    onBack: () -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit
) {
    val songs = remember(folder.path) {
        com.sdw.music.player.SongRepository.getSongsInFolder(folder.path)
    }
    var searchQuery by remember { mutableStateOf("") }
    val displayedSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folder.name, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${songs.size} songs", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (songs.isNotEmpty()) {
                        IconButton(onClick = { onPlaySongs(songs) }) {
                            Icon(Icons.Default.PlayArrow, "Play All", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (displayedSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.playlist_no_folders), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(displayedSongs, key = { _, s -> s.id }) { index, song ->
                    com.sdw.music.player.ui.screens.SongItem(
                        song = song,
                        index = index,
                        isPlaying = false,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = { onPlaySongs(listOf(song)) },
                        onLongClick = { }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}


