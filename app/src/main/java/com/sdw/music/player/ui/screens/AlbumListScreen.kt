package com.sdw.music.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.sdw.music.player.Song
import com.sdw.music.player.MusicService
import com.sdw.music.player.R
import com.sdw.music.player.ui.components.AlphabetIndexBar
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.theme.*
import com.sdw.music.player.util.PinyinUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    albums: List<Pair<String, List<Song>>>,
    onAlbumClick: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Sort by pinyin, then group by first letter
    val grouped = remember(albums) {
        albums.sortedBy { (name, _) -> name.lowercase() }
            .groupBy { PinyinUtils.getInitial(it.first).toString() }
            .toSortedMap()
    }

    val indexLetters = remember(grouped) { grouped.keys.toList() }

    // Precompute flat item indices for scroll-to-letter
    val sectionIndices = remember(grouped) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        grouped.forEach { (letter, group) ->
            map[letter] = idx
            idx += 1 + group.size // header + items
        }
        map
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val activeLetter = remember { mutableStateOf(indexLetters.firstOrNull() ?: "#") }

    // Track visible section
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        val visibleIdx = gridState.firstVisibleItemIndex
        // Walk backward to find the nearest visible section header
        for ((letter, headerIdx) in sectionIndices) {
            if (headerIdx <= visibleIdx) {
                activeLetter.value = letter
            } else break
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_albums), color = MaterialTheme.colorScheme.onBackground) },
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (letter, group) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(letter)
                    }
                    items(group, key = { it.first }) { (albumName, songs) ->
                        AlbumGridItem(
                            albumName = albumName,
                            songCount = songs.size,
                            coverUri = songs.firstOrNull()?.albumArtUri.orEmpty()
                        ) { onAlbumClick(albumName) }
                    }
                }
            }

            if (indexLetters.size > 1) {
                AlphabetIndexBar(
                    letters = indexLetters,
                    activeLetter = activeLetter.value,
                    onLetterTapped = { letter ->
                        activeLetter.value = letter
                        val targetIdx = sectionIndices[letter]
                        if (targetIdx != null) {
                            scope.launch { gridState.scrollToItem(targetIdx) }
                        }
                    },
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(letter: String) {
    Text(
        text = letter,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun AlbumGridItem(
    albumName: String,
    songCount: Int,
    coverUri: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        ) {
            // DefaultCoverImage underneath, actual cover on top
            DefaultCoverImage(
                songTitle = albumName,
                songArtist = "",
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            )
            if (coverUri.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri).size(480).crossfade(true).build(),
                    contentDescription = albumName,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))))
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(albumName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$songCount songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}