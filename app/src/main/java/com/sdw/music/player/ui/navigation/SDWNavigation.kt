package com.sdw.music.player.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sdw.music.player.ui.animation.SharedCoverOverlay
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.screens.*
import com.sdw.music.player.SongRepository
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.R
import com.sdw.music.player.MusicService
import com.sdw.music.player.ui.screens.LyricFullscreenScreen
import com.sdw.music.player.ui.screens.PlaylistDetailScreen
import com.sdw.music.player.ui.screens.LyricSearchScreen
import com.sdw.music.player.ui.screens.getMotorolaDeviceName
import com.sdw.music.player.ui.viewmodel.PlayerIntent
import com.sdw.music.player.ui.viewmodel.PlayerViewModel

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

    // Remove verbose recomposition log to reduce CPU overhead
    // android.util.Log.i("SDWNavHost", "state.songList.size=...")

    // Detect large screen — never split in portrait, only landscape + width >= 600dp
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = remember(config) {
        val displayMetrics = context.resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        val heightDp = displayMetrics.heightPixels / displayMetrics.density
        widthDp >= 600f && widthDp >= heightDp
    }

    // Handle notification tap: navigate to PlayerScreen
    if (openPlayer != null) {
        LaunchedEffect(openPlayer.value) {
            if (openPlayer.value) {
                openPlayer.value = false
                // If external audio URI is pending, dispatch PlayUri intent
                val uri = externalAudioUri?.value
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
        NavHost(
            navController = navController,
            startDestination = Screen.SongList.route
        ) {
            composable(
                Screen.SongList.route,
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) }
            ) {
                // Root back: minimize only when SongList is current route
                // (prevent intercepting back during navigation transitions)
                val rootContext = LocalContext.current
                BackHandler {
                    if (navController.currentBackStackEntry?.destination?.route == Screen.SongList.route) {
                        (rootContext as? ComponentActivity)?.moveTaskToBack(true)
                    }
                }

                val currentPlayingSong = remember(state.currentSongId, state.songList) {
                    state.songList.find { it.id == state.currentSongId }
                }
                SongListScreen(
                    songs = state.songList,
                    currentPlayingSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    accentColor = state.accentColor,
                    sharedCoverState = sharedCoverState,
                    miniCoverVisible = !sharedCoverState.isAnimating || !sharedCoverState.isEntering,
                    deviceName = getMotorolaDeviceName() ?: Build.MODEL,
                    onSongClick = { song ->
                        val index = state.songList.indexOfFirst { it.id == song.id }
                        if (index >= 0) {
                            vm.handleIntent(PlayerIntent.PlaySongList(state.songList, startIndex = index))
                            navController.navigate(Screen.Player.route)
                        }
                    },
                    onNavigateToPlayer = {
                        sharedCoverState.enter()
                        navController.navigate(Screen.Player.route)
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
                    onNavigateToLyrics = { navController.navigate(Screen.LyricFullscreen.route) }
                )
            }

            composable(
                Screen.Player.route,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(150)) }
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
                            albumName.isNotBlank() && albumName != context.getString(R.string.label_unknown_album) ->
                                navController.navigate(Screen.AlbumSong.createRoute(albumName))
                            else -> navController.navigate(Screen.AlbumList.route)
                        }
                    },
                    onNavigateToArtist = { artistName ->
                        if (artistName.isNotBlank()) {
                            navController.navigate(Screen.ArtistSong.createRoute(artistName))
                        }
                    },
                    audioSessionId = MusicService.instance?.getAudioSessionId() ?: 0,
                    onPlayQueueIndex = { index -> vm.handleIntent(PlayerIntent.PlaySong(index)) },
                    onDismiss = {
                        sharedCoverState.exit()
                        sharedCoverState.onAnimationEnd = {
                            navController.popBackStack()
                        }
                    }
                )
            }

            composable(Screen.FolderList.route) {
                FolderListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPlaySongs = { songs ->
                        vm.handleIntent(PlayerIntent.PlaySongList(songs))
                        navController.navigate(Screen.Player.route)
                    }
                )
            }
            composable(Screen.PlaylistList.route) {
                PlaylistListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                    },
                    onPlaySongs = { songs ->
                        vm.handleIntent(PlayerIntent.PlaySongList(songs))
                        navController.navigate(Screen.Player.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAudioDiagnostic = { navController.navigate(Screen.AudioDiagnostic.route) },
                    onNavigateToAudioQuality = { navController.navigate(Screen.AudioQuality.route) }
                )
            }
            composable(Screen.AudioQuality.route) {
                AudioQualityScreen(
                    onNavigateBack = { navController.popBackStack() },
                    songs = state.songList,
                    onPlaySong = { song ->
                        val songs = state.songList
                        val idx = songs.indexOfFirst { it.id == song.id }
                        if (idx >= 0) {
                            vm.handleIntent(PlayerIntent.PlaySongList(songs, startIndex = idx))
                            navController.navigate(Screen.Player.route)
                        }
                    }
                )
            }
            composable(Screen.LyricFullscreen.route) {
                val lyricsPositionMs by vm.positionMs.collectAsState()
                LyricFullscreenScreen(
                    songId = state.currentSongId,
                    songArtist = state.currentSongArtist,
                    accentColor = state.accentColor,
                    positionMs = lyricsPositionMs,
                    onSeekTo = { pos -> vm.handleIntent(PlayerIntent.SeekTo(pos)) },
                    onNavigateBack = { navController.popBackStack() },
                    onLyricsSaved = { lyrics -> vm.handleIntent(PlayerIntent.SaveLyrics(lyrics)) }
                )
            }
            composable(Screen.PlaylistDetail.route) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull() ?: 0L
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    onNavigateBack = { navController.popBackStack() },
                    onPlaySongs = { songs ->
                        if (songs.isNotEmpty()) vm.handleIntent(PlayerIntent.PlaySongList(songs, startIndex = 0))
                        navController.navigate(Screen.Player.route)
                    },
                    onAddSongs = { navController.navigate(Screen.SongPicker.createRoute(playlistId)) }
                )
            }
            composable(Screen.SongPicker.route) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull() ?: 0L
                val playlist = PlaylistManager.getPlaylist(playlistId)
                SongPickerScreen(
                    playlistId = playlistId,
                    existingSongIds = playlist?.songIds?.toSet() ?: emptySet(),
                    onNavigateBack = { navController.popBackStack() },
                    onSongsAdded = { navController.popBackStack() }
                )
            }
            composable(Screen.Equalizer.route) {
                EqualizerScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToMseb = { navController.navigate(Screen.Mseb.route) }
                )
            }
            composable(Screen.Mseb.route) {
                MsebScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.AudioDiagnostic.route) {
                AudioDiagnosticScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.LyricSearch.route) { backStackEntry ->
                val songId = backStackEntry.arguments?.getString("songId")?.toLongOrNull() ?: 0L
                val title = backStackEntry.arguments?.getString("songTitle") ?: ""
                val artist = backStackEntry.arguments?.getString("songArtist") ?: ""
                LyricSearchScreen(
                    songId = songId,
                    songTitle = title,
                    songArtist = artist,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // === Album Views ===
            composable(Screen.AlbumList.route) {
                val groupedAlbums = remember(state.songList) {
                    state.songList
                        .filter { it.album.isNotBlank() }
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
            }

            composable(Screen.AlbumSong.route) { backStackEntry ->
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
                        navController.navigate(Screen.Player.route)
                    },
                    onPlayAll = {
                        vm.handleIntent(PlayerIntent.PlaySongList(filtered))
                        navController.navigate(Screen.Player.route)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // === Artist Views ===
            composable(Screen.ArtistList.route) {
                ArtistListScreen(
                    songs = state.songList,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onArtistClick = { artistName ->
                        navController.navigate(Screen.ArtistSong.createRoute(artistName))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.ArtistSong.route) { backStackEntry ->
                val rawArtistName = backStackEntry.arguments?.getString("artistName") ?: ""
                val artistName = java.net.URLDecoder.decode(rawArtistName, "UTF-8")
                val filtered = remember(artistName, state.songList) {
                    state.songList.filter { it.artist == artistName }
                }
                ArtistSongScreen(
                    artistName = artistName,
                    songs = filtered,
                    currentSongId = state.currentSongId,
                    isPlaying = isPlaying,
                    onSongClick = { song ->
                        val index = filtered.indexOfFirst { it.id == song.id }
                        if (index >= 0) vm.handleIntent(PlayerIntent.PlaySongList(filtered, startIndex = index))
                        navController.navigate(Screen.Player.route)
                    },
                    onPlayAll = {
                        vm.handleIntent(PlayerIntent.PlaySongList(filtered))
                        navController.navigate(Screen.Player.route)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // === Shared Element 覆盖层 ===
        // 条件：正在动画中（进入或退出），且 Mini 位置已捕获
        if (sharedCoverState.isAnimating && sharedCoverState.miniCoverPosition.size.width > 0f) {
            SharedCoverOverlay(
                state = sharedCoverState,
                albumArtUri = state.currentSongAlbumArt ?: "",
                screenHeightPx = screenHeightPx,
                onAnimationFinished = {
                    sharedCoverState.onAnimationEnd?.invoke()
                    sharedCoverState.onAnimationEnd = null
                },
                defaultCover = { modifier ->
                    com.sdw.music.player.ui.components.DefaultCoverImage(
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        modifier = modifier
                    )
                }
            )
        }
    }
}