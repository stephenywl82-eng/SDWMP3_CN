package com.sdw.music.player.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.LrcParser
import com.sdw.music.player.R
import com.sdw.music.player.LyricLine
import com.sdw.music.player.ui.components.LyricViewCompose
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.lyric.LyricRepository
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricFullscreenScreen(
    songId: Long,
    songArtist: String,
    accentColor: Long,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    onLyricsSaved: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(accentColor)

    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var rawLrcContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var sourceLabel by remember { mutableStateOf("") }

    // Lyrics源选择
    var isManualSource by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf("auto") }
    var showSourceSheet by remember { mutableStateOf(false) }
    val availableSources = remember { LyricRepository.getInstance(context).getAvailableProviderOptions() }

    // 编辑状态
    var showEditDialog by remember { mutableStateOf(false) }
    var editingLrcText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val effectiveSource = if (isManualSource) selectedSource else "auto"

    // === 沉浸式：隐藏状态栏 + 导航栏 ===
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = (view.context as? Activity) ?: return@DisposableEffect onDispose {}
        val window = activity.window ?: return@DisposableEffect onDispose {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        onDispose {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    window.setDecorFitsSystemWindows(true)
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            } catch (_: Exception) {}
        }
    }

    // 加载Lyrics
    LaunchedEffect(songId, effectiveSource) {
        if (songId <= 0) return@LaunchedEffect
        isLoading = true
        lyricLines = emptyList()
        rawLrcContent = ""
        sourceLabel = ""
        try {
            val repo = LyricRepository.getInstance(context)
            val song = SongRepository.getSongs().find { it.id == songId }
            if (song != null) {
                val result = repo.matchFromSpecificProvider(effectiveSource, song)
                if (result != null) {
                    val bestLrc = result.getBestLyrics()
                    if (!bestLrc.isNullOrBlank()) {
                        rawLrcContent = bestLrc
                        lyricLines = LrcParser.parse(bestLrc)
                        sourceLabel = result.getSourceDisplayName()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LyricScreen", "Failed to load lyrics from[$effectiveSource]", e)
        }
        isLoading = false
    }

    // 背景：MD3 Surface
    // 系统返回键
    BackHandler(onBack = onNavigateBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = accent,
                modifier = Modifier.align(Alignment.Center)
            )
            lyricLines.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.player_no_lyrics), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                LyricViewCompose(
                    lyrics = lyricLines,
                    positionMs = positionMs,
                    themeColor = accentColor.toInt(),
                    onLineClick = { line -> onSeekTo(line.timeMs) },
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()  // 顶部避开状态栏
                        .let { mod ->
                            if (isManualSource && sourceLabel.isNotEmpty())
                                mod.padding(bottom = 52.dp)  // 给底部标签留出空间
                            else mod
                        }
                )
            }
        }

        // === 顶部操作栏（半透明叠加，不保留空白）===
        // 不使用渐变背景，直接半透明Row覆盖在Lyrics上方
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground)
            }

            Spacer(Modifier.width(4.dp))

            // 歌手名（居中）
            Text(
                text = songArtist.ifEmpty { stringResource(R.string.title_fullscreen_lyrics) },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // 手动/自动切换
            Text(
                text = stringResource(R.string.lyrics_auto),
                color = if (!isManualSource) accent else MaterialTheme.colorScheme.outlineVariant,
                fontSize = 11.sp,
                fontWeight = if (!isManualSource) FontWeight.SemiBold else FontWeight.Normal
            )
            Switch(
                checked = isManualSource,
                onCheckedChange = { isManualSource = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.3f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.height(22.dp).padding(horizontal = 2.dp)
            )
            Text(
                text = stringResource(R.string.lyrics_manual),
                color = if (isManualSource) accent else MaterialTheme.colorScheme.outlineVariant,
                fontSize = 11.sp,
                fontWeight = if (isManualSource) FontWeight.SemiBold else FontWeight.Normal
            )

            Spacer(Modifier.width(8.dp))

            // 编辑按钮
            FilledTonalIconButton(
                onClick = {
                    editingLrcText = rawLrcContent
                    showEditDialog = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = accent.copy(alpha = 0.18f),
                    contentColor = accent
                ),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Edit, stringResource(R.string.title_edit_lyrics), modifier = Modifier.size(18.dp))
            }
        }

        // 手动模式下显示来源选择（底部叠加）
        if (isManualSource && sourceLabel.isNotEmpty()) {
            Surface(
                color = accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clickable { showSourceSheet = true }
            ) {
                Text(
                    text = sourceLabel,
                    color = accent.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }

    // === Lyrics来源选择 BottomSheet ===
    if (showSourceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lyrics_select_source),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(4.dp))
            for ((id, name) in availableSources) {
                val isSel = selectedSource == id
                Surface(
                    color = if (isSel) accent.copy(alpha = 0.18f) else Color.Transparent,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedSource = id
                        showSourceSheet = false
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = name,
                            color = if (isSel) accent else MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.weight(1f))
                        if (isSel)
                            Text("\u2713", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // === 编辑Lyrics对话框 ===
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSaving) showEditDialog = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.title_edit_lyrics), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = sourceLabel,
                        color = accent.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.lyrics_format_hint),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = editingLrcText,
                        onValueChange = { editingLrcText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 280.dp, max = 420.dp),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 22.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            cursorColor = accent,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                        ),
                        maxLines = 20
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isSaving) return@Button
                        isSaving = true
                        scope.launch {
                            // 优先从缓存取，找不到则查 MediaStore
                            var song = SongRepository.getSongs().find { it.id == songId }
                            if (song == null) {
                                // 缓存无数据时从 MediaStore 补全 path
                                var path = ""
                                var title = ""
                                var artist = ""
                                var duration = 0L
                                try {
                                    val uri = android.content.ContentUris.withAppendedId(
                                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId
                                    )
                                    context.contentResolver.query(uri, arrayOf(
                                        MediaStore.Audio.Media.DATA,
                                        MediaStore.Audio.Media.TITLE,
                                        MediaStore.Audio.Media.ARTIST,
                                        MediaStore.Audio.Media.DURATION
                                    ), null, null, null)?.use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            path = cursor.getString(0) ?: ""
                                            title = cursor.getString(1) ?: ""
                                            artist = cursor.getString(2) ?: ""
                                            duration = cursor.getLong(3)
                                        }
                                    }
                                } catch (_: Exception) { }
                                // Fallback: 即使用作 title/artist 兜底
                                song = Song(
                                    id = songId,
                                    title = title,
                                    artist = artist,
                                    album = "", albumArtUri = "",
                                    path = path, filePath = path, duration = duration
                                )
                            }
                            if (song != null) {
                                val repo = LyricRepository.getInstance(context)
                                val saved = repo.saveLyricsDirectly(song!!, editingLrcText)
                                if (saved != null) {
                                    // 重新解析并显示
                                    withContext(Dispatchers.Main) {
                                        rawLrcContent = editingLrcText
                                        lyricLines = LrcParser.parse(editingLrcText)
                                        sourceLabel = context.getString(R.string.lyrics_local_edit)
                                        onLyricsSaved?.invoke(editingLrcText)
                                        Toast.makeText(context, R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, R.string.lyrics_save_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            isSaving = false
                            showEditDialog = false
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.background,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_save), color = MaterialTheme.colorScheme.background)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditDialog = false },
                    enabled = !isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

