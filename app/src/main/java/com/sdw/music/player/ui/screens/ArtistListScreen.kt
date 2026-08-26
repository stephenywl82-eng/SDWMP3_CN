package com.sdw.music.player.ui.screens

import androidx.compose.foundation.*
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
import com.sdw.music.player.ui.theme.*
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
    val allArtists = remember(songs) {
        val map = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            splitArtists(song.artist.ifBlank { "Unknown Artist" }).forEach { name ->
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

    val grouped = remember(filteredArtists) {
        filteredArtists.groupBy { PinyinUtils.getInitial(it.first).toString() }
            .toSortedMap()
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
            TopAppBar(
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(flatItems.size, key = { idx ->
                    val (isHeader, data) = flatItems[idx]
                    if (isHeader) "h_$data" else "a_${(data as Pair<String, Any>).first}"
                }) { idx ->
                    val (isHeader, data) = flatItems[idx]
                    if (isHeader) {
                        Text(
                            text = data as String,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
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

@Composable
private fun ArtistItem(
    artistName: String,
    songCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 首字母炫彩头像：渐变 + 玻璃高光 + 描边（不用封面）
        ArtistAvatar(
            artistName = artistName,
            size = 52.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artistName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$songCount songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(20.dp))
    }
}
