package com.sdw.music.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.foundation.Image
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.R
import com.sdw.music.player.Song
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.Playlist

/**
 * 长按歌曲 -> 添加到歌单 的统一底部弹窗。
 * 顶部显示歌曲信息；支持"新建歌单"（输入名字）与选择现有歌单。
 * 已在歌单中的歌曲显示 ✓ 标记，再次点击不重复添加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    song: Song,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // 歌单列表 —— 用 mutableStateList 包一层，创建后立即刷新
    val playlists = remember { mutableStateListOf<Playlist>().apply { addAll(PlaylistManager.getPlaylists()) } }
    val existingIds = remember(song.id, playlists) {
        playlists.filter { song.id in it.songIds }.map { it.id }.toSet()
    }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // ── 歌曲信息 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    DefaultCoverImage(song.title, song.artist, Modifier.fillMaxSize(), RoundedCornerShape(6.dp))
                    val painter = rememberAsyncImagePainter(
                        model = remember(song.albumArtUri) {
                            ImageRequest.Builder(context)
                                .data(song.albumArtUri)
                                .size(88, 88)
                                .allowHardware(false)
                                .crossfade(false)
                                .build()
                        },
                        contentScale = ContentScale.Crop
                    )
                    Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.ifBlank { "Unknown Artist" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

            // ── 新建歌单入口 ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showCreate = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.playlist_create_new), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            }

            // ── 现有歌单列表 ──
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.playlist_no_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    stringResource(R.string.playlist_add_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        val contains = song.id in pl.songIds
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = !contains) {
                                    if (PlaylistManager.addSongToPlaylist(pl.id, song.id)) {
                                        playlists[playlists.indexOfFirst { it.id == pl.id }] =
                                            playlists.first { it.id == pl.id }.copy(songIds = playlists.first { it.id == pl.id }.songIds + song.id)
                                        toast = context.getString(R.string.playlist_added_to, pl.name)
                                    } else {
                                        toast = context.getString(R.string.playlist_already_in, pl.name)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(pl.name, color = if (contains) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (contains) {
                                Icon(Icons.Default.Check, stringResource(R.string.playlist_in_playlist), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            } else {
                                Text("${pl.songIds.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            // ── 提示 ──
            toast?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
    }

    // ── 新建歌单对话框 ──
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.playlist_create_new), color = MaterialTheme.colorScheme.onBackground) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.playlist_name_hint), color = MaterialTheme.colorScheme.outlineVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val pl = PlaylistManager.createPlaylist(newName.trim())
                        PlaylistManager.addSongToPlaylist(pl.id, song.id)
                        playlists.add(0, pl)
                        newName = ""
                        showCreate = false
                        toast = context.getString(R.string.playlist_added_to, pl.name)
                    }
                ) {
                    Text(stringResource(R.string.action_create), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
