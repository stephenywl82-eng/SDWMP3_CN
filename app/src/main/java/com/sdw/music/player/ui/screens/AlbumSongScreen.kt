package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.CountKind
import com.sdw.music.player.ui.components.CountText
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.DetailHero
import com.sdw.music.player.ui.components.LibraryRow
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumSongScreen(
    albumName: String,
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var longPressSong by remember { mutableStateOf<Song?>(null) }
    val coverUri = songs.firstOrNull()?.albumArtUri.orEmpty()

    Scaffold(
        topBar = {
            SDWTopBar(
                // V8.118 详情页标题统一放在 hero（内容区），topBar 只留返回与操作键。
                // 以前专辑名同时出现在 topBar 和头图里，两处同一个词，滚起来像重影。
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // V8.118 头图统一到 DetailHero：与歌手页、歌单页同一骨架。
            // 播放入口从 topBar 的 TextButton 挪到这里——详情页的动作区应该在内容区，
            // 而不是挤在标题栏里跟返回键抢位置。
            DetailHero(
                title = albumName,
                coverTitle = albumName,
                coverArtist = "",
                coverUri = coverUri,
                coverSize = 96.dp,
                onPlayAll = onPlayAll,
                subtitle = {
                    CountText(songs.size, CountKind.SONGS)
                }
            )

            // Song list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    AlbumSongItem(
                        song = song,
                        isPlaying = song.id == currentSongId && isPlaying,
                        onClick = { onSongClick(song) },
                        onLongClick = { longPressSong = song },
                        modifier = Modifier.animateItemPlacement()
                    )
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
}

/**
 * 专辑内的歌曲行。
 *
 * V8.118 换成 LibraryRow，与歌单页、主曲库完全同形：正在播放的行不再只是把歌名染成
 * 主色，而是整行主色水洗 + 上缘光晕 + 时长换成跳动波形 —— 播到哪首一眼可见。
 */
@Composable
private fun AlbumSongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
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
        showDuration = song.duration > 0,
        showNowPlayingBars = isPlaying,
        durationMs = song.duration,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
    )
}
