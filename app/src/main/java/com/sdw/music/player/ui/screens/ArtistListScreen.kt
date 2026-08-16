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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.sdw.music.player.Song
import com.sdw.music.player.ui.components.AlphabetIndexBar
import com.sdw.music.player.ui.components.DefaultCoverImage
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
    val allArtists = remember(songs) {
        songs.groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .mapValues { (_, v) -> v to v.firstOrNull()?.albumArtUri.orEmpty() }
            .toList()
            .sortedBy { it.first.lowercase() }
    }

    val indexLetters = remember(allArtists) {
        val seen = mutableSetOf<Char>()
        val result = mutableListOf<String>()
        allArtists.forEach { (name, _) ->
            val ch = PinyinUtils.getInitial(name)
            if (seen.add(ch)) result += ch.toString()
        }
        result.sortedBy { it[0] }.toList()
    }

    val grouped = remember(allArtists) {
        allArtists.groupBy { PinyinUtils.getInitial(it.first).toString() }
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

    // Track which header is at top of screen
    LaunchedEffect(listState.firstVisibleItemIndex) {
        val idx = listState.firstVisibleItemIndex
        if (idx in flatItems.indices && flatItems[idx].first) {
            activeLetter.value = flatItems[idx].second as String
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_artists), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
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
                        val pair = artistData as Pair<List<Song>, String>
                        ArtistItem(
                            artistName = artistName,
                            songCount = pair.first.size,
                            coverUri = pair.second,
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
    coverUri: String,
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
        // Circular artist avatar with cover or default
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // DefaultCoverImage underneath, actual cover on top
            DefaultCoverImage(
                songTitle = artistName,
                songArtist = "",
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape
            )
            if (coverUri.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri).size(256).crossfade(true).build(),
                    contentDescription = artistName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
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
