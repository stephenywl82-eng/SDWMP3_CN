package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.metadata.AudioCoverEmbedder
import com.sdw.music.player.core.audio.metadata.CoverDownloader

import android.content.Intent
import com.sdw.music.player.ui.components.ExpressiveSwitch
import com.sdw.music.player.ui.components.SDWTopBar
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import com.sdw.music.player.ui.theme.SDWShape
import com.sdw.music.player.ui.theme.SDWSpace
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.sdw.music.player.R
import com.sdw.music.player.ui.components.AddToPlaylistSheet
import com.sdw.music.player.ui.components.rememberSharedCoverPainter
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import com.sdw.music.player.ui.components.ExpressiveWavyProgress
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateContentSize

/**
 * 封面下载 & 嵌入工具页（整合自 CoverEmbed 独立应用）。
 * 列出无封面歌曲 → 单曲点嵌 / 一键批量嵌入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
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
    var longPressSong by remember { mutableStateOf<Song?>(null) }
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
                        // 【V8.107】区分「该格式暂不支持」与「嵌入失败」
                        val fmt = (result as? AudioCoverEmbedder.EmbedResult.Failure)?.unsupportedFormat
                        if (fmt != null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.cover_embed_unsupported, fmt.uppercase()),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(context, R.string.cover_embed_failed, Toast.LENGTH_SHORT).show()
                        }
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
            SDWTopBar(
                title = { Text(stringResource(R.string.cover_embed_title), color = MaterialTheme.colorScheme.onBackground) },
                // [P1 · M3 Expressive] 原来是手写裸 IconButton（飘着的箭头），跟全站顶栏的
                // tonal 容器键两套语言。SDWTopBar 的默认 navigationIcon 就是统一形态，直接沿用。
                onNavigateBack = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 按钮始终显示（有歌即可），点击时若没权限再跳授权
            if (displayed.isNotEmpty() && !embedAllRunning) {
                // [P1 · M3 Expressive] 底栏原来是「实色 Surface」——列表滚到下面时是一块死板的
                // 实色板压在内容上。改成浮起一档的玻璃面板：圆角只取上边两角（贴着屏幕底沿，
                // 下两角圆了会露出背景缝），fillBoost 拉高让它始终读得清。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(topStart = SDWShape.Panel, topEnd = SDWShape.Panel),
                            elevation = 8.dp,
                            fillBoost = 0.30f
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SDWSpace.Gutter, vertical = 10.dp),
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .animateContentSize()
        ) {
            // 写权限引导横幅（Android 11+ 需 All Files Access）
            if (!hasWriteAccess) {
                // [P1 · M3 Expressive] 原来是一整条实色 errorContainer 通栏 —— 全站唯一一块
                // 「不透明的纯色板」。改成 error 色调的玻璃卡：accent 取 error，顶边高光跟着
                // 变红，警告语义还在，但和页面其它玻璃面是同一种材质。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .glassSurface(
                            shape = SDWShapes.Row,
                            accent = MaterialTheme.colorScheme.error,
                            accentEdge = true,
                            fillBoost = 0.12f
                        )
                        .padding(horizontal = SDWSpace.Card, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cover_embed_need_storage),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { openStoragePermission() }) {
                        Text(stringResource(R.string.cover_embed_grant))
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
                ExpressiveSwitch(checked = !showAll, onCheckedChange = { showAll = !it })
            }

            // [P2 · M3 Expressive] 线性进度条 → 波浪进度条（不确定进度形态）。
            // 线性条是 M3 的老形态；全站播放条已经是波浪，这里还是一根直棍，两种进度语言。
            // 波浪条给 16dp 高让波有起伏空间，底槽打开（要读得出"在跑"）。
            if (scanning) {
                ExpressiveWavyProgress(
                    fraction = 0f,
                    color = MaterialTheme.colorScheme.primary,
                    active = true,
                    indeterminate = true,
                    showTrack = true,
                    amplitude = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .padding(horizontal = SDWSpace.Gutter, vertical = 6.dp)
                )
            }

            if (embedAllRunning) {
                ExpressiveWavyProgress(
                    fraction = 0f,
                    color = MaterialTheme.colorScheme.primary,
                    active = true,
                    indeterminate = true,
                    showTrack = true,
                    amplitude = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .padding(horizontal = SDWSpace.Gutter, vertical = 6.dp)
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
                        },
                        onLongClick = { longPressSong = song }
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

    // 【V8.7】长按添加歌单
    longPressSong?.let { song ->
        AddToPlaylistSheet(
            song = song,
            onDismiss = { longPressSong = null }
        )
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CoverEmbedRow(
    song: Song,
    isEmbedding: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            // [V8.115] 行统一到主界面玻璃语言（原先无卡片，直接贴背景）。
            .glassSurface(shape = SDWShapes.Row, fillBoost = 0f)
            .springPress(rowInteraction, pressedScale = 0.975f)
            .combinedClickable(
                interactionSource = rowInteraction,
                indication = null,
                enabled = !isEmbedding,
                onClick = { onClick() },
                onLongClick = { if (!isEmbedding) onLongClick() }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(SDWShapes.Thumb)) {
            // V8.69 - 48dp row: this was decoding the source art unbounded.
            val rowPainter = rememberSharedCoverPainter(
                uri = song.albumArtUri, contentScale = ContentScale.Crop, big = false
            )
            // [V8.94] 规则：有封面就显示封面，只有没封面才显示 logo。
            // 原先 if/else 二选一，无封面走 MusicNote 图标 —— 不符合规则；而本界面列出的
            // 恰恰是「有 albumId 但取不到图」的批次。改为 logo 打底 + 封面无条件叠放。
            DefaultCoverImage(
                songTitle = song.title,
                songArtist = song.artist,
                modifier = Modifier.fillMaxSize(),
                shape = SDWShapes.Thumb
            )
            Image(
                painter = rowPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist.ifBlank { stringResource(R.string.unknown_artist) }} · ${song.album.ifBlank { stringResource(R.string.unknown_album) }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isEmbedding) {
            LoadingIndicator(Modifier.size(20.dp))
        } else if (SongRepository.hasRealCover(song.albumArtUri)) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
