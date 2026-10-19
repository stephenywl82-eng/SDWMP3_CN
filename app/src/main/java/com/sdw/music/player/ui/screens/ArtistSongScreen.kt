package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.ArtistAvatar
import com.sdw.music.player.ui.components.CountKind
import com.sdw.music.player.ui.components.CountText
import com.sdw.music.player.ui.components.DetailHero
import com.sdw.music.player.ui.components.LibraryRow
import com.sdw.music.player.ui.components.rememberSharedCoverPainter
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistSongScreen(
    artistName: String,
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val coverUri = songs.firstOrNull()?.albumArtUri.orEmpty()
    var longPressSong by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        topBar = {
            SDWTopBar(
                // V8.118 同专辑页：标题统一在 hero。
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = onPlayAll) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.action_play_all), tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // V8.118 头图统一到 DetailHero。
            // 歌手页原来是 200dp 满宽大图 + 单独一行「N songs」，比专辑页的 80dp 小封面
            // 高出一截、又比歌单页干脆没有头图 —— 三个详情页三种骨架。现在同一套：
            // 左侧 96dp 封面（有真封面就用、没有就用字母头像打底），右侧标题 + 首数 + 播放。
            DetailHero(
                title = artistName,
                coverTitle = artistName,
                coverArtist = "",
                coverUri = coverUri,
                coverSize = 96.dp,
                onPlayAll = onPlayAll,
                artistName = artistName,
                subtitle = {
                    CountText(songs.size, CountKind.SONGS)
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    ArtistSongItem(
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
 * 歌手页的歌曲行。
 *
 * V8.118 换成 LibraryRow。原先这一行没有时长、正在播放只在末尾插一个图标，和主曲库
 * 的「整行高亮 + 波形」完全两回事。现在统一：时长照常显示，正在播放时换成跳动波形。
 * 副标题从「专辑名」换成「艺术家」会让歌手机页跟专辑页分不清，改成沿用主曲库的信息结构。
 */
@Composable
private fun ArtistSongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LibraryRow(
        title = song.title,
        subtitle = song.album.ifBlank { stringResource(R.string.unknown_album) },
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
