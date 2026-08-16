package com.sdw.music.player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.Song
import com.sdw.music.player.R
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.DefaultCoverImage
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



    val filteredSongs = remember(allSongs, searchQuery) {

        if (searchQuery.isBlank()) allSongs

        else allSongs.filter {

            it.title.contains(searchQuery, ignoreCase = true) ||

            it.artist.contains(searchQuery, ignoreCase = true) ||

            it.album.contains(searchQuery, ignoreCase = true)

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

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

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

                LazyColumn(

                    modifier = Modifier.fillMaxSize(),

                    contentPadding = PaddingValues(bottom = 16.dp)

                ) {

                    item { Spacer(Modifier.height(4.dp)) }

                    itemsIndexed(filteredSongs, key = { _, s -> s.id }) { _, song ->

                        val isSelected = selectedIds.contains(song.id)

                        val isAlreadyIn = existingSongIds.contains(song.id)



                        Card(

                            modifier = Modifier

                                .fillMaxWidth()

                                .padding(horizontal = 12.dp, vertical = 2.dp)

                                .combinedClickable(

                                    onClick = {

                                        if (!isAlreadyIn) {

                                            selectedIds = if (isSelected) {

                                                selectedIds - song.id

                                            } else {

                                                selectedIds + song.id

                                            }

                                        }

                                    },

                                    onLongClick = {}

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

                                    modifier = Modifier

                                        .size(44.dp)

                                        .clip(RoundedCornerShape(6.dp))

                                        .background(MaterialTheme.colorScheme.surfaceVariant),

                                    contentAlignment = Alignment.Center

                                ) {

                                    val imgPainter = rememberAsyncImagePainter(

                                        model = song.albumArtUri,

                                        contentScale = ContentScale.Crop

                                    )

                                    val imgState = imgPainter.state

                                    if (imgState is AsyncImagePainter.State.Loading ||

                                        imgState is AsyncImagePainter.State.Error

                                    ) {

                                        DefaultCoverImage(

                                            songTitle = song.title,

                                            songArtist = song.artist,

                                            modifier = Modifier.fillMaxSize(),

                                            shape = RoundedCornerShape(6.dp)

                                        )

                                    } else {

                                        Image(

                                            painter = imgPainter,

                                            contentDescription = null,

                                            modifier = Modifier.fillMaxSize(),

                                            contentScale = ContentScale.Crop

                                        )

                                    }

                                }



                                Spacer(Modifier.width(12.dp))



                                Column(modifier = Modifier.weight(1f)) {

                                    Text(

                                        song.title,

                                        color = if (isAlreadyIn) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onBackground,

                                        style = MaterialTheme.typography.bodyMedium,

                                        maxLines = 1,

                                        overflow = TextOverflow.Ellipsis

                                    )

                                    Text(

                                        "${song.artist.ifBlank { "Unknown Artist" }} • ${song.album.ifBlank { stringResource(R.string.unknown_album) }}",

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

}


