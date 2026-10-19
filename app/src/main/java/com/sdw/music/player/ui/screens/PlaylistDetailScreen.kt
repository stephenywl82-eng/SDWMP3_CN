package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.ui.components.SDWTopBar
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.Playlist
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.rememberSharedCoverPainter
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.CountKind
import com.sdw.music.player.ui.components.CountText
import com.sdw.music.player.ui.components.DetailHero
import com.sdw.music.player.ui.components.LibraryEmptyState
import com.sdw.music.player.ui.components.LibraryRow
import com.sdw.music.player.Song
import com.sdw.music.player.core.SongSorter
import com.sdw.music.player.core.EnergyColor
import com.sdw.music.player.ui.components.BpmBadge
import com.sdw.music.player.ui.components.springPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import com.sdw.music.player.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    currentSongId: Long,
    isPlaying: Boolean,
    onNavigateBack: () -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onAddSongs: () -> Unit
) {
    var playlist by remember { mutableStateOf(PlaylistManager.getPlaylist(playlistId)) }
    var songs by remember { mutableStateOf(PlaylistManager.getPlaylistSongs(playlistId)) }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }
    var longPressSong by remember { mutableStateOf<Song?>(null) }
    var showSongMenu by remember { mutableStateOf(false) }
    var bpmSort by remember { mutableStateOf(false) }
    // 【V8.244】能量/调性「自动流程排序」开关（Rekordbox Auto-Flow 思路）
    var flowSort by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        playlist = PlaylistManager.getPlaylist(playlistId)
        songs = PlaylistManager.getPlaylistSongs(playlistId)
    }

    // Refresh when returning from SongPicker
    LaunchedEffect(Unit) {
        songs = PlaylistManager.getPlaylistSongs(playlistId)
        playlist = PlaylistManager.getPlaylist(playlistId)
    }

    val displayedSongs = remember(songs, bpmSort, flowSort) {
        when {
            flowSort -> SongSorter.flowSort(songs)
            bpmSort -> SongSorter.bpmSmoothSort(songs)
            else -> songs
        }
    }

    val heroName = playlist?.name.orEmpty()

    Scaffold(
        topBar = {
            SDWTopBar(
                // V8.118 同专辑页/歌手页：标题统一在 hero，topBar 只留返回与操作键。
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    // 【v8.13】BPM 平滑排序开关（扫描入口在 设置 → 扫描 BPM）
                    IconButton(onClick = { bpmSort = !bpmSort; if (bpmSort) flowSort = false }) {
                        Icon(
                            Icons.Default.Sort,
                            stringResource(R.string.playlist_sort_bpm),
                            tint = if (bpmSort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    // 【V8.244】能量/调性自动流程排序
                    IconButton(onClick = { flowSort = !flowSort; if (flowSort) bpmSort = false }) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            stringResource(R.string.playlist_sort_flow),
                            tint = if (flowSort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    IconButton(onClick = onAddSongs) {
                        Icon(Icons.Default.Add, stringResource(R.string.title_add_songs), tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (songs.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Default.MusicNote,
                message = stringResource(R.string.playlist_empty)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                // V8.118 补上头图：歌单原来是三个详情页里唯一没有 hero 的，
                // 打开一个歌单看不到它的封面，只能看到一行「Play all」。
                // 现在与专辑页、歌手页同一骨架。
                item {
                    DetailHero(
                        title = heroName,
                        coverTitle = heroName,
                        coverArtist = "",
                        coverUri = songs.firstOrNull()?.albumArtUri.orEmpty(),
                        coverSize = 96.dp,
                        onPlayAll = { onPlaySongs(displayedSongs) },
                        subtitle = { CountText(songs.size, CountKind.SONGS) }
                    )
                }

                itemsIndexed(displayedSongs, key = { _, s -> s.id }) { index, song ->
                    PlaylistSongItem(
                        song = song,
                        isPlaying = song.id == currentSongId && isPlaying,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onPlaySongs(displayedSongs.subList(index, displayedSongs.size)) },
                        onLongClick = {
                            longPressSong = song
                            showSongMenu = true
                        },
                        onRemove = { showDeleteConfirm = song.id },
                        modifier = Modifier.animateItemPlacement()
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
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

    // 【V8.7】长按：菜单（添加到其他歌单 / 从本歌单移除）
    longPressSong?.let { song ->
        if (showSongMenu) {
            AlertDialog(
                onDismissRequest = {
                    showSongMenu = false
                    longPressSong = null
                },
                title = { Text(song.title, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                showSongMenu = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, stringResource(R.string.action_add), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.playlist_add_to), color = MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(
                            onClick = {
                                showSongMenu = false
                                longPressSong = null
                                PlaylistManager.removeSongFromPlaylist(playlistId, song.id)
                                songs = PlaylistManager.getPlaylistSongs(playlistId)
                                playlist = PlaylistManager.getPlaylist(playlistId)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = AccentRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_remove), color = AccentRed)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showSongMenu = false
                        longPressSong = null
                    }) {
                        Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }

        if (!showSongMenu) {
            AddToPlaylistSheet(
                song = song,
                onDismiss = { longPressSong = null }
            )
        }
    }
}

/**
 * 歌单内的歌曲行。
 *
 * V8.118 以前这个 Row 是套在另一个 Row 里面的 —— 外层负责玻璃底、内层再放内容和按钮，
 * 两次 padding 叠在一起，行内实际留白比主曲库多出一圈。换成 LibraryRow 后外层由它统一
 * 处理，歌单页的歌曲行终于和主曲库、专辑页、歌手页是同一个东西。
 */
@Composable
private fun PlaylistSongItem(
    song: Song,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    LibraryRow(
        title = song.title,
        subtitle = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
        coverTitle = song.title,
        coverArtist = song.artist,
        coverUri = song.albumArtUri,
        coverSize = 48.dp,
        isPlaying = isPlaying,
        accent = accent,
        showDuration = false,
        showNowPlayingBars = isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        trailing = {
            // 【V8.244】Rekordbox 式「颜色 + 能量」标签：色块=调性色，数字=能量等级(1..5)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(EnergyColor.colorInt(song)).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = EnergyColor.energyOf(song).toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            if (song.bpm > 0) {
                Spacer(Modifier.width(6.dp))
                BpmBadge(song.bpm)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Delete,
                stringResource(R.string.action_remove),
                tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onRemove() }
            )
        }
    )
}
