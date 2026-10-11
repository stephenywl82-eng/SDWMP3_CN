package com.sdw.music.player.ui.screens

import androidx.compose.foundation.*
import com.sdw.music.player.ui.components.SDWTopBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.sdw.music.player.Song
import com.sdw.music.player.splitArtists
import com.sdw.music.player.ui.components.AlphabetIndexBar
import com.sdw.music.player.ui.components.ArtistAvatar
import com.sdw.music.player.ui.components.LibraryRow
import com.sdw.music.player.ui.components.SectionHeader
import com.sdw.music.player.ui.components.springPress
import com.sdw.music.player.ui.theme.SDWSpace
import com.sdw.music.player.util.PinyinUtils
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistListScreen(
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    onArtistClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // 把每首歌的 artist 拆成多个独立歌手，各自归组（一首歌可能同时出现在多个歌手名下）
    // [V8.93] 兜底文案走资源：原先硬编码英文会凭空多出一个 "Unknown Artist" 歌手分组，
    // 与中文界面不一致，也和显示层判据对不上。
    val unknownArtistLabel = stringResource(R.string.unknown_artist)
    val allArtists = remember(songs, unknownArtistLabel) {
        val map = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            splitArtists(song.artist.ifBlank { unknownArtistLabel }).forEach { name ->
                map.getOrPut(name) { mutableListOf() }.add(song)
            }
        }
        map.mapValues { (_, v) -> v }
            .toList()
            .sortedBy { it.first.lowercase() }
    }

    // 搜索过滤（按歌手名）
    val filteredArtists = remember(allArtists, searchQuery) {
        if (searchQuery.isBlank()) allArtists
        else allArtists.filter { (name, _) -> name.contains(searchQuery, ignoreCase = true) }
    }

    val indexLetters = remember(filteredArtists) {
        val seen = mutableSetOf<Char>()
        val result = mutableListOf<String>()
        filteredArtists.forEach { (name, _) ->
            val ch = PinyinUtils.getInitial(name)
            if (seen.add(ch)) result += ch.toString()
        }
        result.sortedBy { it[0] }.toList()
    }

    // 【V8.144】显式 Map 类型，避免 JDK21 下推导出 API35 专有类型（Android13 闪退）。
    val grouped: Map<String, List<Pair<String, List<Song>>>> = remember(filteredArtists) {
        filteredArtists.groupBy { PinyinUtils.getInitial(it.first).toString() }
            .toList().sortedBy { it.first }.toMap()
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeLetter = remember { mutableStateOf(indexLetters.firstOrNull() ?: "#") }

    // Build flat list: header + items
    val flatItems = remember(grouped) {
        val items = mutableListOf<Pair<Boolean, Any>>() // true = header
        grouped.forEach { (letter, group) ->
            items += true to letter
            group.forEach { items += false to it }
        }
        items
    }

    // Track which header is at top of screen（向后回溯最近的 section header，修正高亮偏移）
    LaunchedEffect(listState.firstVisibleItemIndex) {
        val idx = listState.firstVisibleItemIndex
        if (idx in flatItems.indices) {
            // 从第一个可见项向后回溯，找到最近的 header
            for (i in idx downTo 0) {
                if (flatItems[i].first) {
                    activeLetter.value = flatItems[i].second as String
                    break
                }
            }
        }
    }

    Scaffold(
        topBar = {
            SDWTopBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.songlist_search_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                    } else {
                        Text(stringResource(R.string.title_artists), color = MaterialTheme.colorScheme.onBackground)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
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
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = SDWSpace.ListInline, vertical = 4.dp)
            ) {
                items(flatItems.size, key = { idx ->
                    val (isHeader, data) = flatItems[idx]
                    if (isHeader) "h_$data" else "a_${(data as Pair<String, Any>).first}"
                }) { idx ->
                    val (isHeader, data) = flatItems[idx]
                    if (isHeader) {
                        // 分组头与专辑页同源（LibraryKit.SectionHeader）。
                        SectionHeader(data as String)
                    } else {
                        val (artistName, artistData) = data as Pair<String, Any>
                        val songsList = artistData as List<Song>
                        ArtistItem(
                            artistName = artistName,
                            songCount = songsList.size,
                            onClick = { onArtistClick(artistName) }
                        )
                    }
                }
            }

            if (indexLetters.size > 1) {
                AlphabetIndexBar(
                    letters = indexLetters,
                    activeLetter = activeLetter.value,
                    onLetterTapped = { letter ->
                        activeLetter.value = letter
                        val targetIdx = flatItems.indexOfFirst { (h, d) -> h && d == letter }
                        if (targetIdx >= 0) {
                            scope.launch { listState.scrollToItem(targetIdx) }
                        }
                    },
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

/**
 * 歌手行。
 *
 * V8.118 接入统一设计语言：外壳换成 LibraryRow（与主曲库同一套玻璃 + 弹簧按压 + 16dp
 * 圆角），标题字重与主曲库对齐，歌曲数走 CountText 资源。前导位保留 ArtistAvatar ——
 * 歌手没有统一封面，用渐变字母头像比强行塞一张专辑图更诚实。
 */
@Composable
private fun ArtistItem(
    artistName: String,
    songCount: Int,
    onClick: () -> Unit
) {
    LibraryRow(
        title = artistName,
        subtitle = stringResource(R.string.count_songs, songCount),
        onClick = onClick,
        coverSize = 52.dp,
        showTrailing = false,
        leading = {
            ArtistAvatar(artistName = artistName, size = 52.dp)
        }
    )
}
