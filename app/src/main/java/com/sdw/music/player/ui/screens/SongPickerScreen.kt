package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.R
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.util.PinyinUtils
import com.sdw.music.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

@Composable

fun SongPickerScreen(

    playlistId: Long,

    existingSongIds: Set<Long>,

    onNavigateBack: () -> Unit,

    onSongsAdded: () -> Unit

) {

    var allSongs by remember { mutableStateOf(SongRepository.getSongs()) }

    var searchQuery by remember { mutableStateOf("") }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    var showConfirm by remember { mutableStateOf(false) }
    var longPressSong by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current



    val filteredSongs = remember(allSongs, searchQuery) {
        if (searchQuery.isBlank()) allSongs
        else allSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    // 【V8.7】A-Z 拼音首字母分组（中文歌名可跳转）；搜索激活时隐藏滑块
    val showSidebar = searchQuery.isBlank() && filteredSongs.size > 20
    val groupedItems = remember(filteredSongs) {
        val byKey = linkedMapOf<String, MutableList<Song>>()
        for (s in filteredSongs) {
            val key = PinyinUtils.getInitial(s.title).toString().uppercase().ifEmpty { "#" }
            byKey.getOrPut(key) { mutableListOf() }.add(s)
        }
        buildList {
            for ((key, songs) in byKey) {
                add(Pair(key, null as Song?))  // header marker
                songs.forEach { add(Pair(key, it)) }
            }
        }
    }
    val listState = rememberLazyListState()
    val headerIndices = remember(groupedItems) {
        groupedItems.mapIndexedNotNull { i, (k, s) -> if (s == null) i to k else null }
    }
    val activeHeader = remember {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            headerIndices.lastOrNull { it.first <= first }?.second ?: headerIndices.firstOrNull()?.second ?: "#"
        }
    }



    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Column {

                        Text(stringResource(R.string.title_add_songs), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)

                        Text(

                            if (selectedIds.isEmpty()) stringResource(R.string.picker_select_songs)

                            else "${selectedIds.size} selected",

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

                    if (selectedIds.isNotEmpty()) {

                        IconButton(onClick = { showConfirm = true }) {

                            Icon(Icons.Default.Check, stringResource(R.string.action_add), tint = MaterialTheme.colorScheme.primary)

                        }

                    }

                },

                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)

            )

        },

        containerColor = MaterialTheme.colorScheme.background

    ) { padding ->

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Search bar

            OutlinedTextField(

                value = searchQuery,

                onValueChange = { searchQuery = it },

                modifier = Modifier

                    .fillMaxWidth()

                    .padding(horizontal = 12.dp, vertical = 8.dp),

                placeholder = { Text(stringResource(R.string.search_songs), color = MaterialTheme.colorScheme.outlineVariant) },

                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.outlineVariant) },

                colors = OutlinedTextFieldDefaults.colors(

                    focusedTextColor = MaterialTheme.colorScheme.onBackground,

                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,

                    focusedBorderColor = MaterialTheme.colorScheme.primary,

                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),

                    cursorColor = MaterialTheme.colorScheme.primary,

                    focusedContainerColor = MaterialTheme.colorScheme.surface,

                    unfocusedContainerColor = MaterialTheme.colorScheme.surface

                ),

                shape = RoundedCornerShape(12.dp),

                singleLine = true

            )



            if (filteredSongs.isEmpty()) {

                Box(

                    modifier = Modifier.fillMaxSize(),

                    contentAlignment = Alignment.Center

                ) {

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {

                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(64.dp))

                        Spacer(Modifier.height(12.dp))

                        Text(stringResource(R.string.songlist_no_songs), color = MaterialTheme.colorScheme.onSurfaceVariant)

                    }

                }

            } else {

                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    groupedItems.forEach { (key, song) ->
                        if (song == null) {
                            item(key = "header_$key") {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                        itemsIndexed(listOf(song), key = { _, s -> s.id }) { _, songNonNull ->
                        val isSelected = selectedIds.contains(songNonNull.id)
                        val isAlreadyIn = existingSongIds.contains(songNonNull.id)



                        Card(

                            modifier = Modifier

                                .fillMaxWidth()

                                .padding(horizontal = 12.dp, vertical = 2.dp)

                                .combinedClickable(

                                    onClick = {

                                        if (!isAlreadyIn) {

                                            selectedIds = if (isSelected) {
                                            selectedIds - songNonNull.id
                                            } else {
                                            selectedIds + songNonNull.id
                                            }

                                        }

                                    },

                                    onLongClick = { longPressSong = songNonNull }

                                ),

                            shape = RoundedCornerShape(8.dp),

                            colors = CardDefaults.cardColors(

                                containerColor = when {

                                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

                                    isAlreadyIn -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)

                                    else -> MaterialTheme.colorScheme.surface

                                }

                            ),

                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

                        ) {

                            Row(

                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),

                                verticalAlignment = Alignment.CenterVertically

                            ) {

                                // Checkbox indicator

                                Box(

                                    modifier = Modifier

                                        .size(24.dp)

                                        .clip(RoundedCornerShape(6.dp))

                                        .background(

                                            when {

                                                isSelected -> MaterialTheme.colorScheme.primary

                                                isAlreadyIn -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

                                                else -> Color.Transparent

                                            }

                                        ),

                                    contentAlignment = Alignment.Center

                                ) {

                                    if (isSelected) {

                                        Icon(

                                            Icons.Default.Check,

                                            null,

                                            tint = Color.White,

                                            modifier = Modifier.size(16.dp)

                                        )

                                    }

                                }



                                Spacer(Modifier.width(12.dp))



                                // Album art

                                Box(
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DefaultCoverImage(
                                        songTitle = songNonNull.title,
                                        songArtist = songNonNull.artist,
                                        modifier = Modifier.fillMaxSize(),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    val painter = rememberAsyncImagePainter(
                                        model = remember(songNonNull.albumArtUri) {
                                            ImageRequest.Builder(context)
                                                .data(songNonNull.albumArtUri)
                                                .size(88, 88)
                                                .allowHardware(false)
                                                .crossfade(false)
                                                .build()
                                        },
                                        contentScale = ContentScale.Crop
                                    )
                                    Image(
                                        painter = painter,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }



                                Spacer(Modifier.width(12.dp))



                                Column(modifier = Modifier.weight(1f)) {

                                    Text(

                                        songNonNull.title,

                                        color = if (isAlreadyIn) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onBackground,

                                        style = MaterialTheme.typography.bodyMedium,

                                        maxLines = 1,

                                        overflow = TextOverflow.Ellipsis

                                    )

                                    Text(

                                        "${songNonNull.artist.ifBlank { "Unknown Artist" }} • ${songNonNull.album.ifBlank { stringResource(R.string.unknown_album) }}",

                                        color = MaterialTheme.colorScheme.outlineVariant,

                                        style = MaterialTheme.typography.bodySmall,

                                        maxLines = 1,

                                        overflow = TextOverflow.Ellipsis

                                    )

                                }



                                // Already in playlist indicator

                                if (isAlreadyIn) {

                                    Text(

                                        stringResource(R.string.picker_already_in),

                                        color = MaterialTheme.colorScheme.outlineVariant,

                                        style = MaterialTheme.typography.labelSmall

                                    )

                                }

                        }

                        }
                    }

                }

                }  // close forEach
                }  // close LazyColumn

                // 右侧 A-Z 滑块（Box overlay，与列表平级）
                if (showSidebar) {
                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"
                    val sidebarHeight = alphabet.length * 22
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .width(24.dp)
                            .height(sidebarHeight.dp)
                            .pointerInput(headerIndices) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        val idx = (offset.y / size.height * alphabet.length).toInt()
                                            .coerceIn(0, alphabet.length - 1)
                                        headerIndices.firstOrNull { it.second == alphabet[idx].toString() }?.first?.let { pos ->
                                            scope.launch { listState.scrollToItem(pos) }
                                        }
                                    },
                                    onVerticalDrag = { change, _ ->
                                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                                        val idx = (y / size.height * alphabet.length).toInt()
                                            .coerceIn(0, alphabet.length - 1)
                                        headerIndices.firstOrNull { it.second == alphabet[idx].toString() }?.first?.let { pos ->
                                            scope.launch { listState.scrollToItem(pos) }
                                        }
                                    }
                                )
                            },
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        alphabet.forEach { c ->
                            Text(
                                text = c.toString(),
                                fontSize = if (activeHeader.value == c.toString()) 11.sp else 9.sp,
                                fontWeight = if (activeHeader.value == c.toString()) FontWeight.Bold else FontWeight.Normal,
                                color = if (activeHeader.value == c.toString()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }

            }

        }




    }
    // Confirm dialog

    if (showConfirm) {

        AlertDialog(

            onDismissRequest = { showConfirm = false },

            title = { Text("Add ${selectedIds.size} songs?", color = MaterialTheme.colorScheme.onBackground) },

            text = { Text(stringResource(R.string.picker_will_add), color = MaterialTheme.colorScheme.onSurfaceVariant) },

            confirmButton = {

                TextButton(

                    onClick = {

                        val playlist = com.sdw.music.player.PlaylistManager.getPlaylist(playlistId)

                        if (playlist != null) {

                            com.sdw.music.player.PlaylistManager.addSongsToPlaylist(playlistId, selectedIds.toList())

                        }

                        showConfirm = false

                        onSongsAdded()

                    }

                ) {

                    Text(stringResource(R.string.action_add), color = MaterialTheme.colorScheme.primary)

                }

            },

            dismissButton = {

                TextButton(onClick = { showConfirm = false }) {

                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)

                }

            },

            containerColor = MaterialTheme.colorScheme.surface

        )

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
