package com.sdw.music.player.ui.screens
import com.sdw.music.player.core.audio.metadata.LyricsEmbedder

import android.content.Intent
import com.sdw.music.player.ui.components.ExpressiveSwitch
import com.sdw.music.player.ui.theme.sectionTitleStyle
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import androidx.compose.ui.unit.sp
import com.sdw.music.player.LrcParser
import com.sdw.music.player.R
import com.sdw.music.player.LyricLine
import com.sdw.music.player.ui.components.LyricViewCompose
import com.sdw.music.player.ui.components.SheetImmersiveStatusBar
import com.sdw.music.player.ui.components.ImmersiveSystemBars
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.lyric.LyricRepository
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricFullscreenScreen(
    songId: Long,
    songTitle: String,
    songArtist: String,
    albumArt: String?,
    accentColor: Long,
    positionMs: Long,
    isPlaying: Boolean = true,
    onSeekTo: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onLyricsSaved: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // V8.92 accentColor = 0L 表示「无封面色，走主题色」。直接 Color(0L) 会得到全透明，
    // 歌词高亮/开关/按钮全部看不见 —— 必须与 PlayerScreen/SongListScreen 一样回退 primary。
    // 【V8.205】同上：封面取色降饱和，色相原样保留。
    val accent = CoverTone.calm(
        if (accentColor != 0L) Color(accentColor) else MaterialTheme.colorScheme.primary
    )
    // LyricViewCompose 收的是 ARGB Int，不能用 Color.value（那是打包后的 ULong）
    val accentArgb = accent.toArgb()
    // [V8.94] 判据改用 hasRealCover：扫描器给每行都写非空 albumArtUri（无封面时是 albumart
    // 虚拟表死链接），isNotBlank() 恒真 → 「无封面」兜底的 accent 渐变背景永远走不到，
    // 且小封面位拿死链接去解码 → 空白。
    val coverUri = remember(albumArt) { albumArt?.takeIf { SongRepository.hasRealCover(it) } }

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
    var showClearConfirm by remember { mutableStateOf(false) }
    // [V8.129] 内嵌写标签需要「所有文件访问」；未授权时给出引导（历史上是静默失败）
    var showStorageGuide by remember { mutableStateOf(false) }

    val effectiveSource = if (isManualSource) selectedSource else "auto"

    // 歌词显示偏好（字号/行数）
    val lyricPrefs = remember { context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE) }
    val lyricFontSize = lyricPrefs.getInt("lyric_font_size", 28)
    val lyricVisibleLines = lyricPrefs.getInt("lyric_visible_lines", 7)
    // [V8.121] 逐字显示开关（增强型 LRC 行内 <mm:ss.xx> 逐字时间戳才生效）
    var wordByWord by remember { mutableStateOf(lyricPrefs.getBoolean("lyric_word_by_word", false)) }

    // [V8.126] 沉浸：隐藏状态栏 + 导航栏。
    // 旧实现用 `view.context as? Activity` 裸 cast 找窗口——本页在主窗口里，view.context
    // 是 ContextThemeWrapper，cast 直接失败 -> 整个 DisposableEffect 静默 no-op，
    // 状态栏永远不隐藏（不崩不报错，与播放页 V8.125 同源 bug）。
    // 统一改用 ImmersiveSystemBars：从 window.decorView 取 controller + ContextWrapper
    // 链找 Activity + 窗口获焦时重新 hide（弹层关闭也压得住）。
    ImmersiveSystemBars(hideNavigationBar = true)

    // 加载Lyrics
    LaunchedEffect(songId, effectiveSource) {
        if (songId <= 0) return@LaunchedEffect
        isLoading = true
        lyricLines = emptyList()
        rawLrcContent = ""
        // 底部来源标签：先显示所选来源（网络源如 LRCLIB 超时/异常也不空白），命中后覆盖为实际来源
        val sourceDisplay = when (effectiveSource) {
            "auto" -> "Auto"
            "local" -> "Local"
            "embedded" -> "Embedded"
            "lrclib" -> "LRCLIB"
            else -> effectiveSource
        }
        sourceLabel = sourceDisplay
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
    // [V8.97] 普通 BackHandler 已移除：它会把返回回调吃掉，使本页在预测返回手势下
    // 完全不跟手（普通 BackHandler 不参与 seek）。交给 NavHost 内建预测返回，
    // 返回行为仍由 NavHost 的 popBackStack 完成；顶部返回按钮走 onNavigateBack 不受影响。

    // 全屏歌词固定深色配色：歌词文字/开关都是白色设计，浅色模式下必须锁深色
    MaterialTheme(colorScheme = SDWDarkColorScheme) {
    // === 【V8.428】3 秒无操作后隐藏顶部操作栏 + 底部切歌按钮，只留歌词 ===
    // 设置开关（sdw_music_prefs，与设置页"歌词显示"分组同一份）：
    // 关掉后控件常驻，行为回到 V8.427 及之前。
    val uiPrefs = remember(context) {
        context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
    }
    val autoHideEnabled = uiPrefs.getBoolean("lyric_controls_auto_hide", true)
    // interaction 每次"用户碰了一下屏幕"就 +1，作为 3 秒定时器的重启信号；
    // 用它当 LaunchedEffect 的 key，天然实现"重新出现 → 重新计时"。
    var interaction by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(interaction, autoHideEnabled) {
        controlsVisible = true
        if (!autoHideEnabled) return@LaunchedEffect   // 关了开关就一直显示
        delay(3_000)
        controlsVisible = false
    }
    // 歌词的上下留白跟着一起收：原本 76dp/136dp 是专门给操作栏和底部按钮留的位，
    // 控件藏起来还留着空位，歌词会显得偏小偏上。
    val lyricTopPad by animateDpAsState(
        if (controlsVisible) 76.dp else 12.dp, tween(220)
    )
    val lyricBottomPad by animateDpAsState(
        if (controlsVisible) 136.dp else 24.dp, tween(220)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 任意点击都算"操作" → 控件立刻回来。
            // requireUnconsumed = false：歌词行的点击跳转、顶部按钮的点击都照常生效，
            // 这里只在事件被子节点消费前"偷听"一下按下，不抢。
            //写法照抄 PlayerComponents 的滑动手势（awaitEachGesture + awaitFirstDown）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    interaction++
                }
            }
    ) {
        // === 背景层：封面模糊 + 渐变压暗，无封面时用 accent 渐变兜底 ===
        if (coverUri != null) {
            // V8.69 - this was the worst unbounded decode in the app: a full-screen
            // (1200x2670 = 12.8MB) bitmap decoded raw and then thrown under a 42dp blur,
            // which destroys that detail anyway. Decode to a third of the width instead.
            val lyricBgPx = remember(context) {
                (context.resources.displayMetrics.widthPixels / 3).coerceAtLeast(320)
            }
            Image(
                painter = rememberAsyncImagePainter(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(coverUri)
                        .size(lyricBgPx)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(42.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.55f
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = 0.30f),
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
        // 压暗渐变：保证歌词可读性
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.60f)
                        )
                    )
                )
        )
        when {
            isLoading -> CircularProgressIndicator(
                color = accent,
                modifier = Modifier.align(Alignment.Center)
            )
            lyricLines.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.player_no_lyrics),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
            }
            else -> {
                LyricViewCompose(
                    lyrics = lyricLines,
                    positionMs = positionMs,
                    themeColor = accentArgb,
                    onLineClick = { line -> onSeekTo(line.timeMs) },
                    showProgressBar = true,
                    fontSize = lyricFontSize,
                    visibleLines = lyricVisibleLines,
                    wordByWord = wordByWord,
                    isPlaying = isPlaying,
                    fadeColor = Color.Black,
                    showTopFade = false,
                    showBottomFade = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()  // 顶部避开状态栏
                        // [V8.130] 本页 V8.126 起沉浸（statusBars 被 hide -> statusBarsPadding() 归 0），
                        // 顶部内容会顶进摄像头挖孔。displayCutout 是独立物理 inset，隐藏状态栏
                        // 后依然有效，必须单独避让（与 PlayerScreen/SongListScreen 同写法）。
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        // 【V8.39】顶部留白：顶部操作栏（返回+封面36dp+歌名/歌手+开关）是半透明叠加层，
                        // 歌词若不预留会顶进歌名区域造成叠加。预留 76dp 让首行歌词落在操作栏下方。
                        .padding(top = lyricTopPad)   // 【V8.428】随控件显隐动画切换（76↔12dp）
                        .padding(bottom = lyricBottomPad)  // 同上（136↔24dp）
                )
            }
        }

        // === 顶部操作栏（半透明叠加，不保留空白）===
        // 不使用渐变背景，直接半透明Row覆盖在Lyrics上方
        // 【V8.428】AnimatedVisibility：淡出后节点被真正移除，不会"隐形但照样吃点击"。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.displayCutout)  // [V8.130] 顶部操作栏同样避开挖孔
                .glassSurface(shape = SDWShapes.Panel, accent = accent)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
            }

            Spacer(Modifier.width(4.dp))

            // 歌曲信息卡（封面 + 歌名 + 歌手），内嵌在操作栏中避免与返回键重叠
            Row(
                modifier = Modifier
                    .weight(1f)  // [V8.121] 歌曲信息块吸收剩余宽度并优先收缩，避免歌名长时挤扁右侧开关
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    // [V8.94] 规则：有封面显示封面，没封面显示 logo。
                    // 原先 if/else 二选一（无封面走 accent 色块 + MusicNote）不符合规则。
                    // 改为 logo 打底 + 封面无条件叠放，取不到图自然露出 logo。
                    DefaultCoverImage(
                        songTitle = songTitle,
                        songArtist = songArtist,
                        modifier = Modifier.fillMaxSize(),
                        shape = SDWShapes.Thumb,
                        tintColor = accent
                    )
                    if (coverUri != null) {
                        Image(
                            painter = rememberAsyncImagePainter(coverUri),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(SDWShapes.Thumb),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = songTitle.ifEmpty { stringResource(R.string.title_fullscreen_lyrics) },
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.8f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                blurRadius = 4f
                            )
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (songArtist.isNotBlank()) {
                        Text(
                            text = songArtist,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall.copy(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                    blurRadius = 4f
                                )
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // 手动/自动切换
            Text(
                text = stringResource(R.string.lyrics_auto),
                color = if (!isManualSource) accent else Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontWeight = if (!isManualSource) FontWeight.SemiBold else FontWeight.Normal
            )
            ExpressiveSwitch(
                checked = isManualSource,
                onCheckedChange = { isManualSource = it },
                trackOnColor = accent,
                iconOnColor = accent,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = stringResource(R.string.lyrics_manual),
                color = if (isManualSource) accent else Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontWeight = if (isManualSource) FontWeight.SemiBold else FontWeight.Normal
            )

            Spacer(Modifier.width(4.dp))

            // [V8.121] 逐字显示开关
            Text(
                text = stringResource(R.string.lyrics_word_by_word),
                color = if (wordByWord) accent else Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontWeight = if (wordByWord) FontWeight.SemiBold else FontWeight.Normal
            )
            ExpressiveSwitch(
                checked = wordByWord,
                onCheckedChange = {
                    wordByWord = it
                    lyricPrefs.edit().putBoolean("lyric_word_by_word", it).apply()
                },
                trackOnColor = accent,
                iconOnColor = accent,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Spacer(Modifier.width(4.dp))

            // 编辑按钮
            FilledTonalIconButton(
                onClick = {
                    editingLrcText = rawLrcContent
                    showEditDialog = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White
                ),
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.Edit, stringResource(R.string.title_edit_lyrics), modifier = Modifier.size(16.dp))
            }
        }

        // 手动模式下显示来源选择（底部叠加）
        if (isManualSource) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 108.dp)  // 上移避开底部切歌按钮
                    .glassSurface(shape = SDWShapes.Row, accent = accent)
                    .clickable { showSourceSheet = true }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = sourceLabel,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                            blurRadius = 3f
                        )
                    )
                )
            }
        }
        }   // 【V8.428】AnimatedVisibility（顶部操作栏）结束

        // === 底部切歌按钮（上一曲/下一曲，小尺寸左右分居，与顶部操作栏风格统一）===
        // 【V8.428】两个切歌按钮一起淡出：3 秒的意义就是画面只剩歌词。
        // 左：上一曲
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
        FilledTonalIconButton(
            onClick = onPrevious,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White
            ),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 24.dp, bottom = 20.dp)
                .size(38.dp)
        ) {
            Icon(
                Icons.Default.SkipPrevious, stringResource(R.string.player_previous),
                modifier = Modifier.size(20.dp),
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        }   // 【V8.428】AnimatedVisibility（左）结束
        // 右：下一曲
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
        FilledTonalIconButton(
            onClick = onNext,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White
            ),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 20.dp)
                .size(38.dp)
        ) {
            Icon(
                Icons.Default.SkipNext, stringResource(R.string.player_next),
                modifier = Modifier.size(20.dp),
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        }   // 【V8.428】AnimatedVisibility（右）结束
    }

    // === Lyrics来源选择 BottomSheet ===
    if (showSourceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            containerColor = Color(0xFF141414),
            tonalElevation = 0.dp,
            // [V8.117] 去掉顶部拖拽小短条；下滑关闭不受影响。
            dragHandle = null
        ) {
            // [V8.117] 独立 Dialog 窗口需自行隐藏状态栏。
            SheetImmersiveStatusBar()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lyrics_select_source),
                color = Color.White,
                style = sectionTitleStyle(),
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
                            color = if (isSel) accent else Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.weight(1f))
                        if (isSel)
                            Text("\u2713", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
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
                    Text(stringResource(R.string.title_edit_lyrics), color = MaterialTheme.colorScheme.onBackground)
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
                                    // [V8.129] .lrc 已保存成功；若音频内嵌标签因缺「所有文件访问」写不进去，
                                    // 明确提示并引导授权（否则用户只看到「没写入内嵌」，不知原因）。
                                    val blocked = withContext(Dispatchers.IO) { needsAllFilesAccess(song!!) }
                                    // 重新解析并显示
                                    withContext(Dispatchers.Main) {
                                        rawLrcContent = editingLrcText
                                        lyricLines = LrcParser.parse(editingLrcText)
                                        sourceLabel = context.getString(R.string.lyrics_local_edit)
                                        onLyricsSaved?.invoke(editingLrcText)
                                        Toast.makeText(context, R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
                                        if (blocked) showStorageGuide = true
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
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_save), color = Color.White)
                }
            },
            dismissButton = {
                Row {
                    // [V8.117] 清除歌词：删除内嵌标签 + 本地 .lrc，并忽略内嵌（错歌词删得掉了）
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = !isSaving,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_clear))
                    }
                    TextButton(
                        onClick = { showEditDialog = false },
                        enabled = !isSaving,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }

    // === [V8.117] 清除歌词二次确认 ===
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) showClearConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    stringResource(R.string.lyrics_clear_confirm_title),
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Text(
                    stringResource(R.string.lyrics_clear_confirm_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isSaving) return@TextButton
                        isSaving = true
                        scope.launch {
                            val s = SongRepository.getSongs().find { it.id == songId }
                            val ok = if (s != null) {
                                LyricRepository.getInstance(context).deleteLyrics(s)
                            } else false
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    rawLrcContent = ""
                                    lyricLines = emptyList()
                                    sourceLabel = ""
                                    onLyricsSaved?.invoke("")
                                    Toast.makeText(context, R.string.lyrics_cleared, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, R.string.lyrics_save_failed, Toast.LENGTH_SHORT).show()
                                }
                                isSaving = false
                                showClearConfirm = false
                                showEditDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // === [V8.129] 内嵌写入需要「所有文件访问」授权 ===
    if (showStorageGuide) {
        AlertDialog(
            onDismissRequest = { showStorageGuide = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.lyric_write_tag_need_permission), color = MaterialTheme.colorScheme.onBackground) },
            text = {
                Text(
                    stringResource(R.string.lyric_write_tag_explain),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStorageGuide = false
                        try {
                            @Suppress("DEPRECATION")
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = accent)
                ) {
                    Text(stringResource(R.string.action_grant_all_files))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStorageGuide = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.85f))
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    }
}

// [V8.129] 该歌曲的内嵌标签写入是否被「所有文件访问」权限挡住。
// 只有 LyricsEmbedder 支持的格式（flac/mp3/wav）才有意义；.lrc 不受此限制。
private fun needsAllFilesAccess(song: Song): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    if (Environment.isExternalStorageManager()) return false
    val path = song.filePath.ifBlank { song.path }
    if (path.isBlank() || path.startsWith("content://")) return false
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext == "flac" || ext == "mp3" || ext == "wav"
}

