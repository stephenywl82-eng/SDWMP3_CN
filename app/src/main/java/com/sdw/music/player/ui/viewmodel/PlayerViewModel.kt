package com.sdw.music.player.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.sdw.music.player.MusicService
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.core.audio.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.sdw.music.player.MemoryManager
import com.sdw.music.player.widget.MusicWidgetProvider
import com.sdw.music.player.lyric.LyricRepository
import com.sdw.music.player.core.audio.PlayerStateStore
import com.sdw.music.player.core.model.PlayerPersistedState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * MVI Intent 鈥?鐢ㄦ埛鎿嶄綔
 */
sealed class PlayerIntent {
    data class PlaySong(val index: Int) : PlayerIntent()
    data object PlayPause : PlayerIntent()
    data object Play : PlayerIntent()
    data object Pause : PlayerIntent()
    data object Next : PlayerIntent()
    data object Previous : PlayerIntent()
    data class SeekTo(val positionMs: Long) : PlayerIntent()
    data class SetShuffle(val enabled: Boolean) : PlayerIntent()
    data class SetRepeatMode(val mode: Int) : PlayerIntent()
    data class ToggleEqSheet(val show: Boolean) : PlayerIntent()
    data class SelectEqPreset(val presetId: String) : PlayerIntent()
    data class PlaySongList(val songs: List<com.sdw.music.player.Song>, val startIndex: Int = 0) : PlayerIntent()
    data object ToggleFavorite : PlayerIntent()
    data object DeleteCurrent : PlayerIntent()
    data object DeleteConfirmed : PlayerIntent()
    data object DeleteCancelled : PlayerIntent()
    data class SaveLyrics(val lyrics: String) : PlayerIntent()
    /** 外部打开音频文件：从 URI 创建临时 Song 并播放 */
    data class PlayUri(val uri: android.net.Uri, val title: String = "") : PlayerIntent()
}

/**
 * UI State 鈥?鎾斁鍣ㄧ姸鎬?
 */
data class PlayerState(
    val currentSongTitle: String = "",
    val currentSongArtist: String = "",
    val currentSongAlbum: String = "",
    val currentSongFormat: String = "",
    val currentSongAlbumArt: String? = null,
    val currentLyrics: String? = null,
    val currentSongId: Long = -1L,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val showEqSheet: Boolean = false,
    val accentColor: Long = 0L,  // 0 = no cover color, PlayerScreen falls back to system primary
    val coverColors: List<Int> = emptyList(),  // 5色 for Edge灯 (List has value equality, IntArray had ref equality causing false recomposition)
    val songCount: Int = 0,
    val songList: List<Song> = emptyList(),
    val queue: List<Song> = emptyList(),
    val scanStatus: String = "idle",
    val isCurrentSongFavorite: Boolean = false,
    val currentSongFilePath: String = "",
    val currentSongContentUri: String = "",
    val favoriteCount: Int = 0,
    val scanProgress: Float = 0f,
    // 系统级删除
    val pendingDeleteUri: String = "",
    val pendingDeleteSongId: Long = -1L
)

/**
 * Player ViewModel 鈥?MVI 鏋舵瀯锛岃繛鎺?Media3 MediaController
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
    private val playerStateStore: PlayerStateStore
) : AndroidViewModel(application) {

    companion object {
        @Volatile var skipInitScan = false
        private val accentColorRng = java.util.Random()
    }

    // === Hot flows: high-frequency updates that must NOT trigger full tree recomposition ===
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val connection = PlayerConnection(application.applicationContext)

    /** 切下一首或清空播放状态 */
    private fun skipOrClear() {
        if (SongRepository.getSongs().isNotEmpty()) {
            connection.setSongs(SongRepository.getSongs(), updateGlobal = true)
            // [V8.1] 同步 servicePlaylist，删除后 ExoPlayer 不会引用已删除歌曲
            MusicService.instance?.refreshServicePlaylist()
            connection.skipToNext()
        } else {
            if (_state.value.isPlaying) connection.togglePlayPause()
            _state.update { it.copy(
                currentSongTitle = "", currentSongArtist = "", currentSongAlbum = "",
                currentSongAlbumArt = null, currentLyrics = null, isPlaying = false,
                currentSongId = -1L, currentSongFilePath = "", currentSongContentUri = "",
                songList = emptyList(), songCount = 0
            )}
        }
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        android.util.Log.i("PlayerViewModel", "init: START")
        SongRepository.init(application)

        // === FAST PATH: restore from cache synchronously ===
        // Ensures UI has songs + cover before first frame, even after process death.
        val cachedSongs = SongRepository.getSongs()
        android.util.Log.i("PlayerViewModel", "init: FAST PATH cachedSongs.size=${cachedSongs.size}")
        if (cachedSongs.isNotEmpty()) {
            android.util.Log.i("PlayerViewModel", "init: ✓ FAST PATH restoring ${cachedSongs.size} songs from cache")
            _state.update { it.copy(
                songCount = cachedSongs.size, songList = cachedSongs,
                scanStatus = "done:${cachedSongs.size}", scanProgress = 1f
            ) }
            android.util.Log.i("PlayerViewModel", "init: after _state.update, _state.value.songList.size=${_state.value.songList.size}")
            syncRestoreOrAutoPlay(cachedSongs, "fast")
        } else {
            android.util.Log.w("PlayerViewModel", "init: ✗ FAST PATH empty cache, falling back to scan")
            _state.update { it.copy(scanStatus = "loading", scanProgress = 0.05f) }
        }

        // === SLOW PATH: background rescan (skip when cache already loaded) ===
        if (cachedSongs.isNotEmpty()) {
            android.util.Log.i("PlayerViewModel", "init: SKIP SLOW PATH (${cachedSongs.size} cached)")
        } else {
            viewModelScope.launch {
                try {
                    android.util.Log.i("PlayerViewModel", "init: SLOW PATH rescan starting...")
                    // 【V7.92】Snapshot SongRepository BEFORE rescan, not _state (unreliable)
                    val cachedBeforeScan = SongRepository.getSongs()
                    val scanned = SongRepository.rescanFromMediaStore(application.applicationContext)
                    android.util.Log.i("PlayerViewModel", "init: SLOW PATH rescan complete, scanned=${scanned.size}, cachedBefore=${cachedBeforeScan.size}")
                    SongRepository.syncFavoritesToSongs()
                    if (scanned.isNotEmpty() && scanned != cachedBeforeScan) {
                        android.util.Log.i("PlayerViewModel", "init: SLOW PATH updating with ${scanned.size} scanned songs")
                        _state.update { it.copy(
                            songCount = scanned.size, songList = scanned,
                            scanStatus = "done:${scanned.size}", scanProgress = 1f
                        ) }
                        syncRestoreOrAutoPlay(scanned, "scan")
                    } else if (scanned.isEmpty() && cachedBeforeScan.isNotEmpty()) {
                        android.util.Log.w("PlayerViewModel", "rescan returned empty, keeping cached ${cachedBeforeScan.size} songs")
                        _state.update { it.copy(scanStatus = "done:${cachedBeforeScan.size} (cached)", scanProgress = 1f) }
                    } else if (scanned.isEmpty() && cachedBeforeScan.isEmpty()) {
                        android.util.Log.w("PlayerViewModel", "init: SLOW PATH scanned empty, no songs on device")
                        _state.update { it.copy(scanStatus = "empty: no songs found", scanProgress = 1f) }
                    } else {
                        android.util.Log.i("PlayerViewModel", "init: SLOW PATH same data, no update needed")
                        _state.update { it.copy(scanStatus = "done:${cachedBeforeScan.size}", scanProgress = 1f) }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlayerViewModel", "rescan failed", e)
                    val c = _state.value.songList.size
                    _state.update { it.copy(
                        scanStatus = if (c > 0) "done:$c (cached)" else "error:${e.message}",
                        scanProgress = if (c > 0) 1f else 0f
                    ) }
                }
            }
        }

        // Collect scan progress from SongRepository
        viewModelScope.launch {
            try {
                SongRepository.scanProgress.collect { progress ->
                    _state.update { it -> it.copy(scanProgress = progress) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "scanProgress collect failed", e)
            }
        }

        // 鐩戝惉 PlayerConnection 鐘舵€佸彉鍖?
        viewModelScope.launch {
            try {
                connection.isPlaying.drop(1).collect { playing ->
                    _isPlaying.value = playing
                    _state.update { it.copy(isPlaying = playing) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "isPlaying collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                var n = 0
                connection.currentPositionMs.collect { pos ->
                    if (n < 5) { com.sdw.music.player.FileLog.log("VM", "positionMs=$pos"); n++ }
                    _positionMs.value = pos
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "currentPositionMs collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                var n = 0
                connection.durationMs.collect { dur ->
                    if (n < 5) { com.sdw.music.player.FileLog.log("VM", "durationMs=$dur"); n++ }
                    _durationMs.value = dur
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "durationMs collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                connection.currentSong.collect { song ->
                    song?.let {
                        val isFav = SongRepository.isFavorite(it.id)
                        // 【修复】切歌时清空旧Lyrics，防止异步Search慢导致显示旧Lyrics
                        _state.update { s ->
                            s.copy(
                                currentSongTitle = it.title,
                                currentSongArtist = it.artist,
                                currentSongAlbum = it.album,
                                currentSongFormat = it.format,
                                currentSongAlbumArt = it.albumArtUri,
                                currentSongId = it.id,
                                currentSongContentUri = it.path,
                                isCurrentSongFavorite = isFav,
                                currentSongFilePath = it.filePath.ifBlank { it.path },
                                currentLyrics = null  // 先清空，等异步Search回来再填
                            )
                        }
                        // 保存当前播放歌曲ID（用于杀进程后恢复）
                        playerStateStore.saveQuickState(it.id, _positionMs.value)
                        // 自动SearchLyrics
                        if (it.title.isNotBlank() && it.artist.isNotBlank()) {
                            viewModelScope.launch {
                                try {
                                    val result = LyricRepository.getInstance(application).autoMatch(it)
                                    val lyrics = result?.getBestLyrics()
                                    if (lyrics != null) {
                                        _state.update { s -> s.copy(currentLyrics = lyrics) }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        // 提取Cover强调色（优先级：内嵌封面 > 随机色）
                        viewModelScope.launch {
                            val coverUri = it.albumArtUri.takeIf { uri -> uri.isNotEmpty() }
                            
                            if (coverUri != null) {
                                val color = MemoryManager.extractAccentColor(application, coverUri, it.id)
                                if (color != null) {
                                    _state.update { s -> s.copy(accentColor = color.toLong() and 0xFFFFFFFFL) }
                                    MusicWidgetProvider.updateAllWidgets(application)
                                }
                                val colors = MemoryManager.extractCoverColors(application, coverUri, it.id)
                                if (colors != null) {
                                    _state.update { s -> s.copy(coverColors = colors.toList()) }
                                }
                            } else {
                                _state.update { s -> s.copy(accentColor = randomAccentColor()) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "currentSong collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                connection.shuffleEnabled.collect { enabled ->
                    _state.update { it.copy(shuffleEnabled = enabled) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "shuffleEnabled collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                connection.repeatMode.collect { mode ->
                    _state.update { it.copy(repeatMode = mode) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "repeatMode collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                connection.songList.collect { songs ->
                    // 忽略空列表，防止覆盖 FAST PATH 已加载的歌曲
                    if (songs.isNotEmpty()) {
                        _state.update { it.copy(songCount = songs.size) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "songList collect failed", e)
            }
        }

        // 杩炴帴 MediaController
        try {
            connection.connect()
        } catch (e: Exception) {
            android.util.Log.e("PlayerViewModel", "connect() failed", e)
        }
    }

    fun handleIntent(intent: PlayerIntent) {
        when (intent) {
            is PlayerIntent.PlaySong -> {
                connection.playSong(intent.index)
            }
            is PlayerIntent.PlayPause -> {
                connection.togglePlayPause()
            }
            is PlayerIntent.Play -> {
                connection.play()
            }
            is PlayerIntent.Pause -> {
                connection.pause()
            }
            is PlayerIntent.Next -> {
                connection.skipToNext()
            }
            is PlayerIntent.Previous -> {
                connection.skipToPrevious()
            }
            is PlayerIntent.SeekTo -> {
                connection.seekTo(intent.positionMs)
            }
            is PlayerIntent.SetShuffle -> {
                connection.setShuffleEnabled(intent.enabled)
            }
            is PlayerIntent.SetRepeatMode -> {
                connection.setRepeatMode(intent.mode)
            }
            is PlayerIntent.PlaySongList -> {
                connection.setSongs(intent.songs, updateGlobal = false)
                if (intent.songs.isNotEmpty()) {
                    connection.playSong(intent.startIndex)
                    _state.update { it.copy(queue = intent.songs, songCount = intent.songs.size) }
                }
            }
            is PlayerIntent.ToggleEqSheet -> {
                _state.update { it.copy(showEqSheet = intent.show) }
            }
            is PlayerIntent.SelectEqPreset -> {
                val ctx = getApplication<android.app.Application>().applicationContext
                com.sdw.music.player.EqualizerManager.applyPreset(intent.presetId, ctx)
            }
            is PlayerIntent.ToggleFavorite -> {
                val songId = _state.value.currentSongId
                if (songId > 0) {
                    val nowFav = SongRepository.toggleFavorite(songId)
                    _state.update { it.copy(isCurrentSongFavorite = nowFav, favoriteCount = SongRepository.getFavoriteCount()) }
                }
            }
            is PlayerIntent.DeleteCurrent -> {
                val st = _state.value
                val ctx = getApplication<Application>().applicationContext
                if (st.currentSongContentUri.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：交给 UI 层调用 MediaStore.createDeleteRequest 弹出系统确认对话框
                    _state.update { it.copy(
                        pendingDeleteUri = st.currentSongContentUri,
                        pendingDeleteSongId = st.currentSongId
                    )}
                } else {
                    // 旧版 Android 或没有 content URI：直接删
                    viewModelScope.launch {
                        try {
                            if (st.currentSongContentUri.isNotBlank()) {
                                ctx.contentResolver.delete(android.net.Uri.parse(st.currentSongContentUri), null, null)
                            }
                            if (st.currentSongFilePath.isNotBlank()) {
                                java.io.File(st.currentSongFilePath).delete()
                            }
                            SongRepository.removeSongById(st.currentSongId)
                            skipOrClear()
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerViewModel", "Delete song failed", e)
                        }
                    }
                }
            }
            is PlayerIntent.DeleteConfirmed -> {
                // 系统对话框确认后，从仓库移除并切歌
                viewModelScope.launch {
                    val songId = _state.value.pendingDeleteSongId
                    _state.update { it.copy(pendingDeleteUri = "", pendingDeleteSongId = -1L) }
                    SongRepository.removeSongById(songId)
                    skipOrClear()
                }
            }
            is PlayerIntent.DeleteCancelled -> {
                // 用户Cancel了系统对话框
                _state.update { it.copy(pendingDeleteUri = "", pendingDeleteSongId = -1L) }
            }
            is PlayerIntent.SaveLyrics -> {
                _state.update { it.copy(currentLyrics = intent.lyrics) }
            }
            is PlayerIntent.PlayUri -> {
                val song = buildSongFromUri(intent.uri, intent.title)
                connection.setSongs(listOf(song), updateGlobal = false)
                connection.playSong(0)
                _state.update { it.copy(queue = listOf(song), songCount = 1) }
            }
        }
    }

    /**
     * 鍒锋柊姝屾洸鍒楄〃锛堜粠 MediaStore 閲嶆柊鎵弿锛?
     */

    /** Scan a SAF folder URI for audio files */
    fun scanFolderUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(scanStatus = "scanning folder") }
            try {
                SongRepository.scanSafFolder(getApplication(), uri)
                val freshSongs = SongRepository.getSongs()
                _state.update { it.copy(songCount = freshSongs.size, songList = freshSongs, scanStatus = "done:${freshSongs.size}") }
                connection.setSongs(freshSongs, updateGlobal = false)
            } catch (e: Exception) {
                _state.update { it.copy(scanStatus = "error:${e.message}") }
            }
        }
    }

    fun refreshSongs() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(scanStatus = "scanning", scanProgress = 0f) }
                val songs = SongRepository.rescanFromMediaStore(getApplication())
                if (songs.isEmpty() && SongRepository.hasData()) {
                    val cached = SongRepository.getSongs()
                    _state.update { it.copy(
                        songCount = cached.size, songList = cached,
                        scanStatus = "done:${cached.size} (from cache)", scanProgress = 1f
                    ) }
                    return@launch
                }
                SongRepository.setSongs(songs)
                connection.setSongs(songs, updateGlobal = false)
                _state.update { it.copy(
                    songCount = songs.size, songList = songs,
                    scanStatus = "done:${songs.size}", scanProgress = 1f
                ) }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "refreshSongs failed", e)
                if (SongRepository.hasData()) {
                    val cached = SongRepository.getSongs()
                    _state.update { it.copy(
                        songCount = cached.size, songList = cached,
                        scanStatus = "done:${cached.size} (from cache)", scanProgress = 1f
                    ) }
                } else {
                    _state.update { it.copy(scanStatus = "error:${e.message}", scanProgress = 0f) }
                }
            }
        }
    }

    /** 杀进程恢复：找到上次播放的歌曲并下发到 Service 播放 */
    private fun syncRestoreOrAutoPlay(songs: List<Song>, tag: String) {
        val persisted = playerStateStore.loadStateBlocking()
        if (persisted.lastSongId <= 0) return
        val idx = songs.indexOfFirst { it.id == persisted.lastSongId }
        if (idx < 0) return
        android.util.Log.d("PlayerViewModel", "restoreOrAutoPlay[$tag]: found ${songs[idx].title} at index $idx")
        connection.setSongs(songs, updateGlobal = true)
        // Read auto-play preference
        val autoPlayEnabled = getApplication<android.app.Application>()
            .getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("auto_play_on_launch", false)
        if (autoPlayEnabled) {
            connection.playSong(idx)
            MusicService.instance?.refreshServicePlaylist()
            _isPlaying.value = true
        }
        val song = songs[idx]
        val isFav = SongRepository.isFavorite(song.id)
        // 恢复播放态仅当 auto-play 开启；否则保持空态，避免迷你条残留上次歌曲的静止倒计时
        if (autoPlayEnabled) {
            _state.update { s ->
                s.copy(
                    songList = songs, songCount = songs.size,
                    currentSongTitle = song.title,
                    currentSongArtist = song.artist,
                    currentSongAlbum = song.album,
                    currentSongFormat = song.format,
                    currentSongAlbumArt = song.albumArtUri,
                    currentSongId = song.id,
                    currentSongContentUri = song.path,
                    currentSongFilePath = song.filePath.ifBlank { song.path },
                    isPlaying = true,
                    isCurrentSongFavorite = isFav,
                    currentLyrics = null
                )
            }
            // 异步搜歌词
            if (song.title.isNotBlank() && song.artist.isNotBlank()) {
                viewModelScope.launch {
                    try {
                        val result = LyricRepository.getInstance(getApplication()).autoMatch(song)
                        val lyrics = result?.getBestLyrics()
                        if (lyrics != null) {
                            _state.update { s -> s.copy(currentLyrics = lyrics) }
                        }
                    } catch (_: Exception) {}
                }
            }
        } else {
            // 不自动播放：只同步歌曲库，不恢复任何播放态（保持 currentSongId=-1 清空迷你条）
            _state.update { s ->
                s.copy(
                    songList = songs, songCount = songs.size,
                    isPlaying = false
                )
            }
        }

        // Restore shuffle and repeat mode from DataStore
        if (persisted.shuffleEnabled != _state.value.shuffleEnabled) {
            connection.setShuffleEnabled(persisted.shuffleEnabled)
        }
        if (persisted.repeatMode != _state.value.repeatMode) {
            connection.setRepeatMode(persisted.repeatMode)
        }
        _state.update { s ->
            s.copy(
                shuffleEnabled = persisted.shuffleEnabled,
                repeatMode = persisted.repeatMode
            )
        }

        // Restore position ONLY when auto-play actually resumes playback.
        // Otherwise keep position=0 so the mini player does not show a stale
        // frozen countdown/progress from the previous session.
        if (autoPlayEnabled && persisted.playbackPositionMs > 1000) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // wait for playback to start
                connection.seekTo(persisted.playbackPositionMs)
            }
        }
    }

    /** 从 URI 构建临时 Song 对象用于外部文件打开 */
    private fun buildSongFromUri(uri: android.net.Uri, title: String): Song {
        val ctx = getApplication<Application>().applicationContext
        var songTitle = title
        var songArtist = ""
        var songAlbum = ""
        var songDuration = 0L
        if (uri.scheme == "content") {
            try {
                val cursor = ctx.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val titleIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE)
                        val artistIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.ARTIST)
                        val albumIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.ALBUM)
                        val durationIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DURATION)
                        if (titleIdx >= 0 && songTitle.isBlank()) songTitle = c.getString(titleIdx) ?: ""
                        if (artistIdx >= 0) songArtist = c.getString(artistIdx) ?: ""
                        if (albumIdx >= 0) songAlbum = c.getString(albumIdx) ?: ""
                        if (durationIdx >= 0) songDuration = c.getLong(durationIdx)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "buildSongFromUri query failed", e)
            }
        }
        val displayTitle = if (songTitle.isNotBlank()) songTitle else uri.lastPathSegment ?: "Unknown"
        return Song(
            id = -(System.nanoTime() and Long.MAX_VALUE), // 临时负 ID 避免与媒体库冲突
            title = displayTitle,
            artist = songArtist.ifBlank { "Unknown Artist" },
            album = songAlbum.ifBlank { "Unknown Album" },
            duration = songDuration,
            path = uri.toString(),
            albumArtUri = "",
            filePath = uri.toString()
        )
    }

    /** Save current playback state to DataStore. */
    private fun persistCurrentState() {
        val s = _state.value
        viewModelScope.launch {
            try {
                playerStateStore.saveState(
                    songId = s.currentSongId,
                    shuffleEnabled = s.shuffleEnabled,
                    repeatMode = s.repeatMode,
                    positionMs = _positionMs.value,
                    queueIds = s.songList.map { it.id }
                )
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "persistState failed", e)
            }
        }
    }

    /** Periodic position save during playback. */
    private var lastPersistPositionMs = 0L
    fun onPositionTick(positionMs: Long) {
        if (kotlin.math.abs(positionMs - lastPersistPositionMs) < 5000) return
        lastPersistPositionMs = positionMs
        val s = _state.value
        if (s.currentSongId > 0) {
            viewModelScope.launch {
                try {
                    playerStateStore.saveQuickState(s.currentSongId, positionMs)
                } catch (_: Exception) {}
            }
        }
    }

    private fun randomAccentColor(): Long {
        val hue = accentColorRng.nextFloat() * 360f
        val saturation = 0.6f + accentColorRng.nextFloat() * 0.4f
        val value = 0.7f + accentColorRng.nextFloat() * 0.3f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)).toLong() and 0xFFFFFFFFL
    }

    override fun onCleared() {
        super.onCleared()
        persistCurrentState()
        connection.release()
    }
}


