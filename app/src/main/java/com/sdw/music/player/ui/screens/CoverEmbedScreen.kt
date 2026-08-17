package com.sdw.music.player.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.sdw.music.player.R
import com.sdw.music.player.Song
import com.sdw.music.player.core.audio.AudioCoverEmbedder
import com.sdw.music.player.core.audio.CoverDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 封面下载 & 嵌入工具页（整合自 CoverEmbed 独立应用）。
 * 列出无封面歌曲 → 单曲点嵌 / 一键批量嵌入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverEmbedScreen(
    songs: List<Song>,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 真正判定无封面：ContentResolver 能否从 albumArtUri 读出图片数据
    var noCoverSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var hasWriteAccess by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        )
    }

    var showAll by remember { mutableStateOf(false) }
    var embedding by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var embedAllRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }

    // 首次进入 + 每次 onResume（授权返回）时刷新写权限并重新扫描无封面列表
    fun refreshState() {
        hasWriteAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        scope.launch {
            scanning = true
            noCoverSongs = withContext(Dispatchers.IO) {
                songs.filter { s -> s.filePath.isNotBlank() && !hasAlbumArt(context, s.albumArtUri) }
            }
            scanning = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 首次进入也触发一次
        refreshState()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val displayed = if (showAll) songs.filter { it.filePath.isNotBlank() } else noCoverSongs

    // 跳转「所有文件访问」授权页
    fun openStoragePermission() {
        try {
            @Suppress("DEPRECATION")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: Exception) {}
    }

    // 单曲嵌入
    fun embedSingle(song: Song) {
        if (!hasWriteAccess) { openStoragePermission(); return }
        embedding = embedding + song.id
        scope.launch {
            try {
                val coverFile = withContext(Dispatchers.IO) { CoverDownloader.downloadCover(context, song.artist, song.album) }
                if (coverFile == null) {
                    Toast.makeText(context, R.string.cover_embed_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    val result = withContext(Dispatchers.IO) {
                        AudioCoverEmbedder.embedCover(context, song.filePath, coverFile.readBytes())
                    }
                    if (result is AudioCoverEmbedder.EmbedResult.Success) {
                        noCoverSongs = noCoverSongs.filter { it.id != song.id }
                        Toast.makeText(context, R.string.cover_embed_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.cover_embed_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.cover_embed_error, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                embedding = embedding - song.id
            }
        }
    }

    // 批量嵌入
    suspend fun embedAll(list: List<Song>, onProgress: (Int, Int) -> Unit) {
        var done = 0
        var errors = 0
        for ((idx, song) in list.withIndex()) {
            try {
                val coverFile = withContext(Dispatchers.IO) { CoverDownloader.downloadCover(context, song.artist, song.album) }
                if (coverFile != null) {
                    val result = withContext(Dispatchers.IO) {
                        AudioCoverEmbedder.embedCover(context, song.filePath, coverFile.readBytes())
                    }
                    if (result is AudioCoverEmbedder.EmbedResult.Success) {
                        done++
                        noCoverSongs = noCoverSongs.filter { it.id != song.id }
                    } else errors++
                } else errors++
            } catch (_: Exception) { errors++ }
            onProgress(done, errors)
            if (idx < list.size - 1) delay(800) // 防 iTunes 限流
        }
        Toast.makeText(context, context.getString(R.string.cover_embed_all_result, done, errors), Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cover_embed_title), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 按钮始终显示（有歌即可），点击时若没权限再跳授权
            if (displayed.isNotEmpty() && !embedAllRunning) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cover_embed_nocover_count, noCoverSongs.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (!hasWriteAccess) {
                                    openStoragePermission()
                                } else {
                                    scope.launch {
                                        embedAllRunning = true
                                        progress = ""
                                        try {
                                            embedAll(noCoverSongs) { done, err -> progress = "$done/$err" }
                                        } finally {
                                            embedAllRunning = false
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cover_embed_all))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 写权限引导横幅（Android 11+ 需 All Files Access）
            if (!hasWriteAccess) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.cover_embed_need_storage),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { openStoragePermission() }) {
                            Text(stringResource(R.string.cover_embed_grant))
                        }
                    }
                }
            }

            // 顶部开关：只看无封面 / 全部
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cover_embed_only_nocover),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = !showAll, onCheckedChange = { showAll = !it })
            }

            if (scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (embedAllRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (progress.isNotBlank()) {
                    Text(progress, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(displayed, key = { it.id }) { song ->
                    CoverEmbedRow(
                        song = song,
                        isEmbedding = song.id in embedding,
                        onClick = {
                            scope.launch { embedSingle(song) }
                        }
                    )
                }
                if (displayed.isEmpty() && !scanning) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.cover_embed_all_done),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 真查 albumArtUri 是否能读出图片（MediaStore 会为无封面 album 返回空流） */
private fun hasAlbumArt(context: android.content.Context, albumArtUri: String): Boolean {
    if (albumArtUri.isBlank()) return false
    return try {
        context.contentResolver.openInputStream(Uri.parse(albumArtUri))?.use { s ->
            s.read() != -1
        } ?: false
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun CoverEmbedRow(
    song: Song,
    isEmbedding: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isEmbedding) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (song.albumArtUri.isNotBlank()) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} · ${song.album}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isEmbedding) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (song.albumArtUri.isNotBlank()) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
