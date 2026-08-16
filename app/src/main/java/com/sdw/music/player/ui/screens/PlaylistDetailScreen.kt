package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.Playlist
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onNavigateBack: () -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onAddSongs: () -> Unit
) {
    var playlist by remember { mutableStateOf(PlaylistManager.getPlaylist(playlistId)) }
    var songs by remember { mutableStateOf(PlaylistManager.getPlaylistSongs(playlistId)) }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(playlistId) {
        playlist = PlaylistManager.getPlaylist(playlistId)
        songs = PlaylistManager.getPlaylistSongs(playlistId)
    }

    // Refresh when returning from SongPicker
    LaunchedEffect(Unit) {
        songs = PlaylistManager.getPlaylistSongs(playlistId)
        playlist = PlaylistManager.getPlaylist(playlistId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            playlist?.name ?: stringResource(R.string.title_playlists),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${songs.size} songs",
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
                actions = {
                    IconButton(onClick = onAddSongs) {
                        Icon(Icons.Default.Add, stringResource(R.string.title_add_songs), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddSongs,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.title_add_songs))
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.playlist_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                // Play all header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaySongs(songs) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            stringResource(R.string.playlist_play_all),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Play all (${songs.size})", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }

                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .combinedClickable(
                                onClick = { onPlaySongs(songs.subList(index, songs.size)) },
                                onLongClick = { showDeleteConfirm = song.id }
                            ),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album art
                            Box(
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                DefaultCoverImage(
                                    songTitle = song.title,
                                    songArtist = song.artist,
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = song.albumArtUri,
                                        contentScale = ContentScale.Crop
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist.ifBlank { "Unknown Artist" },
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Delete button
                            IconButton(
                                onClick = { showDeleteConfirm = song.id },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    stringResource(R.string.action_remove),
                                    tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation
    showDeleteConfirm?.let { songId ->
        val song = songs.find { it.id == songId }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.playlist_remove_from), color = MaterialTheme.colorScheme.onBackground) },
            text = {
                Text(
                    "Remove \"${song?.title ?: ""}\" from this playlist?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaylistManager.removeSongFromPlaylist(playlistId, songId)
                        songs = PlaylistManager.getPlaylistSongs(playlistId)
                        playlist = PlaylistManager.getPlaylist(playlistId)
                        showDeleteConfirm = null
                    }
                ) {
                    Text(stringResource(R.string.action_remove), color = AccentRed)
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
