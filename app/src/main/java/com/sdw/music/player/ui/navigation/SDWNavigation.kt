package com.sdw.music.player.ui.navigation
import com.sdw.music.player.core.audio.playback.MusicService

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import com.sdw.music.player.ui.animation.SharedElementDestination
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.sdw.music.player.util.buildLyricoEditIntent
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.animation.PageReveal
import com.sdw.music.player.ui.animation.Motion
import com.sdw.music.player.ui.screens.*
import com.sdw.music.player.SongRepository
import com.sdw.music.player.Song
import com.sdw.music.player.splitArtists
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.R
import com.sdw.music.player.ui.screens.LyricFullscreenScreen
import com.sdw.music.player.ui.screens.PlaylistDetailScreen
import com.sdw.music.player.ui.screens.LyricSearchScreen
import com.sdw.music.player.ui.screens.getMotorolaDeviceName
import com.sdw.music.player.ui.viewmodel.PlayerIntent
import com.sdw.music.player.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SDWNavHost(
    navController: NavHostController = rememberNavController(),
    openPlayer: MutableState<Boolean>? = null,
    externalAudioUri: androidx.compose.runtime.MutableState<android.net.Uri?>? = null
) {
    val vm: PlayerViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val isPlaying = state.isPlaying
    // 高频 tick 状态：只持有 State 对象引用，不在此处读 .value，避免每秒 tick 拖垮整个 NavHost 重组
    val navPositionMs = vm.positionMs.collectAsState()
    val navDurationMs = vm.durationMs.collectAsState()
    val sharedCoverState = remember { SharedCoverState() }

    // 点歌/切歌播放：栈里已有 Player 时直接弹回它（避免重复 push 导致返回栈越来越深），否则新建
    //
    // [V8.100] 修「双击迷你条要按两次返回 / 闪一下」的根因：
    // popBackStack(route, inclusive = false) 的返回值判据是「是否真的弹掉了至少一个条目」，
    // 不是「栈里有没有这个 route」。当 Player 恰好就在栈顶时，它上面没有条目可弹，
    // popBackStackInternal 收集的 popOperations 为空 → 直接 return false
    // （NavController.kt:656-682：只有 inclusive || !hasRoute 时才会 add 进 popOperations）。
    // 于是这行兜底把「Player 已在栈顶」误判成「栈里没有 Player」，又 navigate 了一次，
    // 把 Player 压进栈两遍：双击迷你条 = 两次 onClick = [SongList, Player, Player]，
    // 返回一次只是从上面那个 Player 换到下面那个（同屏，看着像闪一下），第二次才回主界面。
    // 正解：先问 currentDestination 是不是 Player —— 这才是「播放器已经开着」的真判据。
    val navigateToPlayer: () -> Unit = {
        if (navController.currentDestination?.route != Screen.Player.route) {
            if (!navController.popBackStack(Screen.Player.route, inclusive = false)) {
                navController.navigate(Screen.Player.route) {
                    // 兜底：导航事务时序万一绕过上面的判断，也不许栈顶出现第二个 Player
                    launchSingleTop = true
                }
            }
        }
    }

    // Remove verbose recomposition log to reduce CPU overhead
    // android.util.Log.i("SDWNavHost", "state.songList.size=...")

    // Detect large screen — never split in portrait, only landscape + width >= 600dp
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current

    // 【Lyrico 联动】长按歌曲 → 用 Lyrico 编辑当前曲目元数据。
    // 若 Lyrico 回传了编辑后的标签 extras（上游 PR #196 新增），就地更新内存中的歌曲，免去整库重扫；
    // 否则（老版 Lyrico 未回传 extras）回退到整库重扫。
    val pendingLyricoSong = remember { mutableStateOf<Song?>(null) }
    val lyricoEditLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val song = pendingLyricoSong.value
        pendingLyricoSong.value = null
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (song != null) {
                // 统一入口：文本字段按 extras 就地更新，封面无论新旧 Lyrico 都直接从被改写文件
                // 重新抽取（得到新 URI），即时刷新播放器封面，不依赖平台 albumart 索引。
                vm.onExternalTagEditReturned(song.id, song.filePath.ifBlank { song.path }, result.data?.extras)
            } else {
                vm.refreshSongs()
            }
        }
    }
    fun openInLyrico(song: Song) {
        val filePath = song.filePath.ifBlank { song.path }
        val intent = buildLyricoEditIntent(context, filePath)
        when {
            intent == null -> Toast.makeText(context, R.string.lyrico_edit_unsupported, Toast.LENGTH_SHORT).show()
            intent.resolveActivity(context.packageManager) == null ->
                Toast.makeText(context, R.string.lyrico_not_installed, Toast.LENGTH_LONG).show()
            else -> {
                pendingLyricoSong.value = song
                lyricoEditLauncher.launch(intent)
            }
        }
    }
    val isTablet = remember(config) {
        val displayMetrics = context.resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        val heightDp = displayMetrics.heightPixels / displayMetrics.density
        widthDp >= 600f && widthDp >= heightDp
    }

    // Handle notification tap: navigate to PlayerScreen
    if (openPlayer != null) {
        // 冷启动时 onNewIntent 已经把 shouldOpenPlayer 置成 true，但这一帧的
        // LaunchedEffect 可能先于 onNewIntent 跑完；只盯 openPlayer 就会漏掉这次
        // 「从外部打开文件」，结果是 Activity 起来了、曲子没播。把待播的 uri 也加进 key。
        LaunchedEffect(openPlayer.value, externalAudioUri?.value) {
            val uri = externalAudioUri?.value
            if (openPlayer.value) {
                openPlayer.value = false
                // If external audio URI is pending, dispatch PlayUri intent
                if (uri != null) {
                    externalAudioUri.value = null
                    vm.handleIntent(PlayerIntent.PlayUri(uri))
                }
                navController.navigate(Screen.Player.route) {
                    popUpTo(Screen.SongList.route) { inclusive = false }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        // 全局壁纸背景层：垫在 NavHost 底下（播放器路由豁免，播放器用封面背景）
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        if (currentRoute != Screen.Player.route && currentRoute != Screen.LyricFullscreen.route) {
            com.sdw.music.player.ui.components.WallpaperBackground()
        }
        // [V8.52] Official shared-element host: the cover flight is now animated by
        // Compose inside the navigation transition, not by a drawn-on-top overlay.
        SharedTransitionLayout {
        val sharedTransitionScope = this
        // 【V9.02】离开播放页（返回主界面 / 进入子页）时触发 sharedCoverState.exit()，
        // 置 isAnimating=true，让播放页 60Hz 重活（VU/装饰相位）在返回转场期间暂停，
        // 修复：① 返回主界面动画卡顿；② 返回后首次点击失效（需双击）。
        // 覆盖「返回按钮」与「系统/预测返回手势」两种退出方式（enter() 在 onNavigateToPlayer 调，
        // 只处理进入；exit() 此前全工程无人调用，故返回时 isAnimating 从不置 true，暂停逻辑形同虚设）。
        DisposableEffect(navController) {
            var prevRoute: String? = null
            val listener = { _: androidx.navigation.NavController, destination: androidx.navigation.NavDestination, _: android.os.Bundle? ->
                val newRoute = destination.route
                if (prevRoute == Screen.Player.route && newRoute != Screen.Player.route) {
                    sharedCoverState.exit()
                }
                prevRoute = newRoute
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
        NavHost(
            navController = navController,
            startDestination = Screen.SongList.route,
            // V8.60 fade-through. The old version slid the incoming page a quarter of the
            // width while the outgoing slid a fifth and both cross-faded, so for ~350ms two
            // translucent pages were on screen at different offsets - the outgoing page's
            // edge was the vertical seam the user saw. Now the outgoing page fades out fast
            // on a much shorter slide, the incoming waits ~140ms and then travels only a
            // sixth of the width. Only one page is ever readable, and the push is longer so
            // it reads as motion instead of a cut.
            enterTransition = {
                slideInHorizontally(Motion.navSlideIn) { it / 6 } + fadeIn(Motion.navFadeIn)
            },
            exitTransition = {
                slideOutHorizontally(Motion.navSlideOut) { -it / 10 } + fadeOut(Motion.navFadeOut)
            },
            // [V8.97] Pop direction uses springs, not the push tweens: predictive back is a
            // pop, and the release must carry the finger's velocity (see Motion.navPopSlideIn).
            // Fade specs are untouched, so the V8.60 fade-through gap still holds (outgoing
            // invisible at 180ms, incoming starts at 200ms - the two pages never overlap).
            popEnterTransition = {
                slideInHorizontally(Motion.navPopSlideIn) { -it / 6 } + fadeIn(Motion.navFadeIn)
            },
            popExitTransition = {
                slideOutHorizontally(Motion.navPopSlideOut) { it / 10 } + fadeOut(Motion.navFadeOut)
            }
        ) {
            composable(
                Screen.SongList.route,
                exitTransition = { fadeOut(Motion.navFadeOut) },
                popEnterTransition = { fadeIn(tween(280, easing = Motion.emphasized)) }
            ) {PageReveal {
                // Root back: minimize only when SongList is current route
                // (prevent intercepting back during navigation transitions)
                val rootContext = LocalContext.current
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                BackHandler(enabled = currentRoute == Screen.SongList.route) {
                    (rootContext as? ComponentActivity)?.moveTaskToBack(true)
                }

                val currentPlayingSong = remember(state.currentSongId, state.songList) {
                    state.songList.find { it.id == state.currentSongId }
                }
                SharedElementDestination(
                    sharedScope = sharedTransitionScope,
                    visibilityScope = this
                ) {
                SongListScreen(
                    songs = state.songList,
                    currentPlayingSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    accentColor = state.accentColor,
                    sharedCoverState = sharedCoverState,
                    // [V8.52] The mini cover is the return destination of the shared-element
                    // flight, so it must stay visible for the whole transition.
                    miniCoverVisible = true,
                    deviceName = getMotorolaDeviceName() ?: Build.MODEL,
                    onSongClick = { song ->
                        val index = state.songList.indexOfFirst { it.id == song.id }
                        if (index >= 0) {
                            // [V8.84] The tapped row (not the mini bar) is the flight source.
                            sharedCoverState.rowFlightSongId = song.id
                            vm.handleIntent(PlayerIntent.PlaySongList(state.songList, startIndex = index))
                            navigateToPlayer()
                        }
                    },
                    onNavigateToPlayer = {
                        // [V8.100] 幂等：Player 已是当前目的地时整个动作都不做。
                        // 双击迷你条会触发两次 onClick，这里若不拦，第二次会再跑一次
                        // sharedCoverState.enter() → 封面飞行动画重启 → 肉眼看到「闪一下」。
                        if (navController.currentDestination?.route != Screen.Player.route) {
                            // [V8.84] Tapping the bar hands the cover key back to the mini bar.
                            sharedCoverState.rowFlightSongId = null
                            sharedCoverState.enter()
                            navigateToPlayer()
                        }
                    },
                    onNavigateToFolder = { navController.navigate(Screen.FolderList.route) },
                    onNavigateToPlaylist = { navController.navigate(Screen.PlaylistList.route) },
                    onNavigateToAlbum = { navController.navigate(Screen.AlbumList.route) },
                    onNavigateToArtist = { navController.navigate(Screen.ArtistList.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onRefresh = { vm.refreshSongs() },
                    scanStatus = state.scanStatus,
                    scanProgress = state.scanProgress,
                    isTablet = isTablet,
                    onNavigateBack = { navController.popBackStack() },
                    // Tablet detail pane controls
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode = state.repeatMode,
                    eqEnabled = state.showEqSheet,
                    isCurrentSongFavorite = state.isCurrentSongFavorite,
                    positionMs = navPositionMs,
                    durationMs = navDurationMs,
                    onPlay = { vm.handleIntent(PlayerIntent.Play) },
                    onPause = { vm.handleIntent(PlayerIntent.Pause) },
                    onPrevious = { vm.handleIntent(PlayerIntent.Previous) },
                    onNext = { vm.handleIntent(PlayerIntent.Next) },
                    onSeekTo = { vm.handleIntent(PlayerIntent.SeekTo(it)) },
                    onToggleShuffle = { vm.handleIntent(PlayerIntent.SetShuffle(!state.shuffleEnabled)) },
                    onCycleRepeat = {
                        val nextMode = when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        vm.handleIntent(PlayerIntent.SetRepeatMode(nextMode))
                    },
                    onToggleFavorite = { vm.handleIntent(PlayerIntent.ToggleFavorite) },
                    onToggleEqualizer = {
                        navController.navigate(Screen.Equalizer.route)
                    },
                    onShare = {
                        try {
                            val filePath = state.currentSongFilePath
                            if (filePath.isBlank()) return@SongListScreen
                            val uri: android.net.Uri? = if (filePath.startsWith("content://")) {
                                android.net.Uri.parse(filePath)
                            } else {
                                val file = java.io.File(filePath)
                                if (file.exists()) {
                                    try {
                                        FileProvider.getUriForFile(context, context.packageName + ".provider", file)
                                    } catch (e: IllegalArgumentException) {
                                        android.util.Log.w("SDWNav", "FileProvider failed, trying raw path", e)
                                        android.net.Uri.fromFile(file)
                                    }
                                } else null
                            }
                            uri?.let {
                                val shareIntent = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, it)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, state.currentSongTitle.ifBlank { context.getString(R.string.label_share_audio) }))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SDWNav", "Share failed", e)
                        }
                    },
                    onEditWithLyrico = { song -> openInLyrico(song) },
                    onNavigateToLyrics = { navController.navigate(Screen.LyricFullscreen.route) }
                )
                }
            }}

            composable(
                Screen.Player.route,
                // 【V8.5】进入播放器：轻量淡入（约 200ms），把舞台让给 SharedCoverOverlay 封面飞行动画，
                // 避免整页滑动与封面动画双轨抢注意力。返回时轻快下滑 + 淡出。
                // V8.60 the player page fades against the flying cover, so its fade has to
                // be long enough to cover the whole shared-element flight instead of
                // finishing early and leaving the page visibly "arrived" mid-flight.
                enterTransition = { fadeIn(Motion.navPlayerFadeIn) },
                exitTransition = { slideOutVertically(Motion.navSlideOut) { it / 6 } + fadeOut(Motion.navPlayerFadeOut) },
                popEnterTransition = { fadeIn(Motion.navPlayerFadeIn) },
                // [V8.84] Going back, the player shrinks toward the list instead of
                // sliding off the top edge: the page behind it becomes visible around the
                // edges, so the two screens read as related rather than as a cut.
                popExitTransition = {
                    scaleOut(targetScale = 0.90f, animationSpec = Motion.playerPopScale) +
                        fadeOut(Motion.navPlayerPopFadeOut)
                }
            ) {PageReveal {
                SharedElementDestination(
                    sharedScope = sharedTransitionScope,
                    visibilityScope = this,
                    dragProgress = sharedCoverState.dragProgress
                ) {
                // Collect hot flows inside player scope to isolate from song list recomposition
                val positionMs by vm.positionMs.collectAsState()
                val durationMs by vm.durationMs.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current

                // === 系统级删除（Android 11+） ===
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val systemDeleteLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult()
                    ) { result ->
                        if (result.resultCode == android.app.Activity.RESULT_OK) {
                            vm.handleIntent(PlayerIntent.DeleteConfirmed)
                        } else {
                            vm.handleIntent(PlayerIntent.DeleteCancelled)
                        }
                    }

                    LaunchedEffect(state.pendingDeleteUri) {
                        if (state.pendingDeleteUri.isNotBlank()) {
                            try {
                                val uris = listOf(Uri.parse(state.pendingDeleteUri))
                                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                                systemDeleteLauncher.launch(
                                    IntentSenderRequest.Builder(pi.intentSender).build()
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("SDWNav", "createDeleteRequest failed: ${e.message}", e)
                                vm.handleIntent(PlayerIntent.DeleteConfirmed)
                            }
                        }
                    }
                }

                // [V8.97] 手搓的 PredictiveBackHandler 已移除。
                // 原因：navigation-compose 2.8.0 起 NavHost 内部会自行注册预测返回回调并
                // 用 SeekableTransitionState 跟手 seek 转场（封面共享元素也随 seek 一起走）。
                // 而 OnBackPressedDispatcher 把生命周期事件只发给「最后注册且 enabled」的回调，
                // 路由内后注册的手搓 handler 会顶掉 NavHost 内建的那个 —— 正是 PlayerScreen.kt
                // [V8.81] 注释记录过的同一个坑。删掉它，跟手能力全部交回官方实现。
                // 保留 sharedCoverState.dragProgress / LocalCoverDragProgress 通路（默认 0f，
                // 无副作用），若日后官方跟手观感不佳可在此处重新驱动。
                // [V9.03] 用户实测安卓 17 上官方跟手观感不佳（封面不跟手），在此处重新驱动：
                // 把系统返回手势进度喂给 dragProgress，经 LocalCoverDragProgress → PlayerComponents
                // 让封面随手指下移（与 App 内手拖封面同源管线）。松手提交时 pop 回主界面由官方
                // 共享元素收尾飞行；手势取消则归位。
                PredictiveBackHandler { progress ->
                    try {
                        progress.collect { event -> sharedCoverState.dragProgress = event.progress }
                        // 手势提交完成：封面已随手指到位，正常 pop，官方共享元素收尾
                        sharedCoverState.dragProgress = 0f
                        navController.popBackStack()
                    } catch (e: CancellationException) {
                        // 手势取消：封面归位
                        sharedCoverState.dragProgress = 0f
                    }
                }

                PlayerScreen(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    state = state,
                    isPlaying = isPlaying,
                    sharedCoverState = sharedCoverState,
                    fullCoverVisible = !sharedCoverState.isAnimating || sharedCoverState.isEntering,
                    onPlay = { vm.handleIntent(PlayerIntent.Play) },
                    onPause = { vm.handleIntent(PlayerIntent.Pause) },
                    onNext = { vm.handleIntent(PlayerIntent.Next) },
                    onPrevious = { vm.handleIntent(PlayerIntent.Previous) },
                    onSeekTo = { vm.handleIntent(PlayerIntent.SeekTo(it)) },
                    onToggleShuffle = { vm.handleIntent(PlayerIntent.SetShuffle(!state.shuffleEnabled)) },
                    onReorderQueueByFlow = { vm.reorderQueueByFlow() },
                    onPreviewUpNext = { vm.previewUpNext() },
                    onLyricoExternalEditReturned = { id, fp, ex ->
                        vm.onExternalTagEditReturned(id, fp, ex)
                    },
                    onCycleRepeat = {
                        val nextMode = when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        vm.handleIntent(PlayerIntent.SetRepeatMode(nextMode))
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToLyrics = {
                        navController.navigate(Screen.LyricFullscreen.route)
                    },
                    onNavigateToAudioDiagnostic = {
                        navController.navigate(Screen.AudioDiagnostic.route)
                    },
                    onNavigateToMseb = {
                        navController.navigate(Screen.Mseb.route)
                    },
                    onToggleFavorite = { vm.handleIntent(PlayerIntent.ToggleFavorite) },
                    onToggleEqualizer = {
                        navController.navigate(Screen.Equalizer.route)
                    },
                    onShare = {
                        try {
                            val filePath = state.currentSongFilePath
                            if (filePath.isBlank()) return@PlayerScreen
                            val uri: Uri? = if (filePath.startsWith("content://")) {
                                Uri.parse(filePath)
                            } else {
                                val file = java.io.File(filePath)
                                if (file.exists()) {
                                    try {
                                        FileProvider.getUriForFile(context, context.packageName + ".provider", file)
                                    } catch (e: IllegalArgumentException) {
                                        android.util.Log.w("SDWNav", "FileProvider failed, trying raw path", e)
                                        Uri.fromFile(file)
                                    }
                                } else null
                            }
                            uri?.let {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, it)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, state.currentSongTitle.ifBlank { context.getString(R.string.label_share_audio) }))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SDWNav", "Share failed", e)
                        }
                    },
                    onDeleteSong = {
                        vm.handleIntent(PlayerIntent.DeleteCurrent)
                    },
                    onNavigateToAlbum = { albumName ->
                        when {
                            albumName.isNotBlank() && !SongRepository.isPlaceholderAlbum(albumName) ->
                                navController.navigate(Screen.AlbumSong.createRoute(albumName))
                            else -> navController.navigate(Screen.AlbumList.route)
                        }
                    },
                    onNavigateToArtist = { artistName ->
                        // [V8.93] 空/占位歌手不进歌手页：原先 <unknown> 能穿过去，进去是空列表
                        if (artistName.isNotBlank() && !SongRepository.isPlaceholderArtist(artistName)) {
                            navController.navigate(Screen.ArtistSong.createRoute(artistName))
                        }
                    },
                    audioSessionId = MusicService.instance?.getAudioSessionId() ?: 0,
                    onPlayQueueIndex = { index -> vm.handleIntent(PlayerIntent.PlaySong(index)) },
                    onDismiss = {
                        // [V8.52] The official shared element flies the cover back; there
                        // is no overlay left to await, so pop immediately.
                        navController.popBackStack()
                    }
                )
                }
            }}

            composable(Screen.FolderList.route) {PageReveal {
                FolderListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPlaySongs = { songs ->
                        vm.handleIntent(PlayerIntent.PlaySongList(songs))
                        navigateToPlayer()
                    },
                    onOpenFolder = { path ->
                        navController.navigate(Screen.FolderDetail.createRoute(path))
                    }
                )
            }}
            composable(Screen.FolderDetail.route) { backStackEntry ->PageReveal {
                val rawToken = backStackEntry.arguments?.getString("token") ?: ""
                val folderPath = rawToken.toIntOrNull()
                    ?.let { com.sdw.music.player.FolderPathRegistry.get(it) }
                    ?: ""
                FolderDetailScreen(
                    folderPath = folderPath,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFolder = { path ->
                        navController.navigate(Screen.FolderDetail.createRoute(path))
                    },
                    onPlaySongs = { songs, startIndex ->
                        vm.handleIntent(PlayerIntent.PlaySongList(songs, startIndex = startIndex))
                        navigateToPlayer()
                    }
                )
            }}
            composable(Screen.PlaylistList.route) {PageReveal {
                PlaylistListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                    },
                    onPlaySongs = { songs ->
                        vm.handleIntent(PlayerIntent.PlaySongList(songs))
                        navigateToPlayer()
                    }
                )
            }}
            composable(Screen.Settings.route) {PageReveal {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAudioDiagnostic = { navController.navigate(Screen.AudioDiagnostic.route) },
                    onNavigateToAudioQuality = { navController.navigate(Screen.AudioQuality.route) },
                    onNavigateToCoverEmbed = { navController.navigate(Screen.CoverEmbed.route) },
                    onBpmScanned = { vm.refreshSongs() }
                )
            }}
            composable(Screen.CoverEmbed.route) {PageReveal {
                CoverEmbedScreen(
                    songs = state.songList,
                    onNavigateBack = { navController.popBackStack() }
                )
            }}
            composable(Screen.AudioQuality.route) {PageReveal {
                AudioQualityScreen(
                    onNavigateBack = { navController.popBackStack() },
                    songs = state.songList,
                    onPlaySong = { song ->
                        val songs = state.songList
                        val idx = songs.indexOfFirst { it.id == song.id }
                        if (idx >= 0) {
                            vm.handleIntent(PlayerIntent.PlaySongList(songs, startIndex = idx))
                            navigateToPlayer()
                        }
                    }
                )
            }}
            composable(Screen.LyricFullscreen.route) {PageReveal {
                val lyricsPositionMs by vm.positionMs.collectAsState()
                val lyricsIsPlaying by vm.isPlaying.collectAsState()
                LyricFullscreenScreen(
                    songId = state.currentSongId,
                    songTitle = state.currentSongTitle,
                    songArtist = state.currentSongArtist,
                    albumArt = state.currentSongAlbumArt,
                    accentColor = state.accentColor,
                    positionMs = lyricsPositionMs,
                    isPlaying = lyricsIsPlaying,
                    onSeekTo = { pos -> vm.handleIntent(PlayerIntent.SeekTo(pos)) },
                    onNavigateBack = { navController.popBackStack() },
                    onPrevious = { vm.handleIntent(PlayerIntent.Previous) },
                    onNext = { vm.handleIntent(PlayerIntent.Next) },
                    onLyricsSaved = { lyrics -> vm.handleIntent(PlayerIntent.SaveLyrics(lyrics)) }
                )
            }}
            composable(Screen.PlaylistDetail.route) { backStackEntry ->PageReveal {
                val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull() ?: 0L
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onNavigateBack = { navController.popBackStack() },
                    onPlaySongs = { songs ->
                        if (songs.isNotEmpty()) vm.handleIntent(PlayerIntent.PlaySongList(songs, startIndex = 0))
                        navigateToPlayer()
                    },
                    onAddSongs = { navController.navigate(Screen.SongPicker.createRoute(playlistId)) }
                )
            }}
            composable(Screen.SongPicker.route) { backStackEntry ->PageReveal {
                val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull() ?: 0L
                val playlist = PlaylistManager.getPlaylist(playlistId)
                SongPickerScreen(
                    playlistId = playlistId,
                    existingSongIds = playlist?.songIds?.toSet() ?: emptySet(),
                    onNavigateBack = { navController.popBackStack() },
                    onSongsAdded = { navController.popBackStack() }
                )
            }}
            composable(Screen.Equalizer.route) {PageReveal {
                EqualizerScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToMseb = { navController.navigate(Screen.Mseb.route) },
                    onNavigateToParametricEq = { navController.navigate(Screen.ParametricEq.route) }
                )
            }}
            composable(Screen.Mseb.route) {PageReveal {
                MsebScreen(onBack = { navController.popBackStack() })
            }}
            composable(Screen.ParametricEq.route) {PageReveal {
                ParametricEqScreen(onBack = { navController.popBackStack() })
            }}
            composable(Screen.AudioDiagnostic.route) {PageReveal {
                AudioDiagnosticScreen(onNavigateBack = { navController.popBackStack() })
            }}
            composable(Screen.LyricSearch.route) { backStackEntry ->PageReveal {
                val songId = backStackEntry.arguments?.getString("songId")?.toLongOrNull() ?: 0L
                val title = backStackEntry.arguments?.getString("songTitle") ?: ""
                val artist = backStackEntry.arguments?.getString("songArtist") ?: ""
                LyricSearchScreen(
                    songId = songId,
                    songTitle = title,
                    songArtist = artist,
                    onNavigateBack = { navController.popBackStack() }
                )
            }}

            // === Album Views ===
            composable(Screen.AlbumList.route) {PageReveal {
                val groupedAlbums = remember(state.songList) {
                    state.songList
                        // [V8.93] 占位专辑不进专辑墙
                        .filter { it.album.isNotBlank() && !SongRepository.isPlaceholderAlbum(it.album) }
                        .groupBy { it.album }
                        .toList()
                        .sortedBy { it.first }
                }
                AlbumListScreen(
                    albums = groupedAlbums,
                    onAlbumClick = { albumName ->
                        navController.navigate(Screen.AlbumSong.createRoute(albumName))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }}

            composable(Screen.AlbumSong.route) { backStackEntry ->PageReveal {
                val rawAlbumName = backStackEntry.arguments?.getString("albumName") ?: ""
                val albumName = java.net.URLDecoder.decode(rawAlbumName, "UTF-8")
                val filtered = remember(albumName, state.songList) {
                    state.songList.filter { it.album == albumName }
                }
                AlbumSongScreen(
                    albumName = albumName,
                    songs = filtered,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onSongClick = { song ->
                        val index = filtered.indexOfFirst { it.id == song.id }
                        if (index >= 0) vm.handleIntent(PlayerIntent.PlaySongList(filtered, startIndex = index))
                        navigateToPlayer()
                    },
                    onPlayAll = {
                        vm.handleIntent(PlayerIntent.PlaySongList(filtered))
                        navigateToPlayer()
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }}

            // === Artist Views ===
            composable(Screen.ArtistList.route) {PageReveal {
                ArtistListScreen(
                    songs = state.songList,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onArtistClick = { artistName ->
                        navController.navigate(Screen.ArtistSong.createRoute(artistName))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }}

            composable(Screen.ArtistSong.route) { backStackEntry ->PageReveal {
                val rawArtistName = backStackEntry.arguments?.getString("artistName") ?: ""
                val artistName = java.net.URLDecoder.decode(rawArtistName, "UTF-8")
                val filtered = remember(artistName, state.songList) {
                    state.songList.filter { splitArtists(it.artist).any { a -> a == artistName } }
                }
                ArtistSongScreen(
                    artistName = artistName,
                    songs = filtered,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onSongClick = { song ->
                        val index = filtered.indexOfFirst { it.id == song.id }
                        if (index >= 0) vm.handleIntent(PlayerIntent.PlaySongList(filtered, startIndex = index))
                        navigateToPlayer()
                    },
                    onPlayAll = {
                        vm.handleIntent(PlayerIntent.PlaySongList(filtered))
                        navigateToPlayer()
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }}
        }

        // [V8.52] The hand-rolled SharedCoverOverlay is retired: the real
        // shared element (SharedTransitionLayout + sharedElement) flies the cover
        // inside the navigation transition itself.
        }
    }
}