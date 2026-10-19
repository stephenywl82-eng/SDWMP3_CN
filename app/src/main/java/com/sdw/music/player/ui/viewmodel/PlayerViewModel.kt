package com.sdw.music.player.ui.viewmodel
import com.sdw.music.player.core.audio.dsp.EqualizerManager
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.playback.PlayerConnection
import com.sdw.music.player.core.audio.playback.PlayerStateStore

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.sdw.music.player.Song
import com.sdw.music.player.util.LyricoResultExtras
import com.sdw.music.player.util.hasLyricoEditExtras
import com.sdw.music.player.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.sdw.music.player.MemoryManager
import com.sdw.music.player.widget.MusicWidgetProvider
import com.sdw.music.player.lyric.LyricRepository
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
    /** 【V8.244】按能量/调性自动流程排序当前队列（以当前曲为锚） */
    data object ReorderQueueByFlow : PlayerIntent()
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
    // 【V8.207】accentColor 是从哪张封面提的（提取成功或已确认提不到，都记为这首歌）。
    // 交叉淡化交接后 UI 靠它判断"新歌主色到手了没有"：在那之前继续挂 B 轨预读色，
    // 否则会先闪回 A 轨的旧主色、几百毫秒后再跳到 B 的色。
    val accentColorSource: String? = null,
    val coverColors: List<Int> = emptyList(),  // 5色 for Edge灯 (List has value equality, IntArray had ref equality causing false recomposition)
    // 【V8.187】上面这 5 档是从哪张封面提的。UI 侧靠它判断"新歌的调色板到手了没有"：
    // 提取是异步的，切歌瞬间 coverUri 已变而 coverColors 还是上一首的旧值，没有它就分不清新旧。
    val coverColorsSource: String? = null,
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

    /**
     * V8.87 Kick off the WAV-cover heal pass and push the result into the UI state.
     *
     * Background only: the pass stats + parses at most one small ID3 chunk per WAV and
     * caches the verdict on disk, so repeat launches are effectively free. If nothing was
     * filled in the state is left untouched, so there is no gratuitous recomposition.
     */
    private fun launchWavCoverHeal() {
        // Note: the constructor's `application` param is only visible in property
        // initializers and init blocks, so a member function must go through getApplication().
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            try {
                val fixed = SongRepository.healWavMetadata(ctx)
                if (fixed > 0) {
                    val list = SongRepository.getSongs()
                    android.util.Log.i("PlayerViewModel", "wavMetaHeal: $fixed filled, refreshing ${list.size} songs")
                    _state.update { it.copy(songList = list) }
                    // [V8.90] B: 同步内存旁路 —— currentSong / servicePlaylist 持的是 heal 之前的
                    // 快照，通知栏 / MediaSession 投影 / 桌面小部件读的就是它们；不同步会出现
                    // 「列表有封面、通知栏没有」。按 id 原地刷新，不动队列顺序。
                    try {
                        MusicService.instance?.refreshMetadata()
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerViewModel", "wavMetaHeal: refreshMetadata failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "wavMetaHeal failed", e)
            }
        }
    }

    init {
        android.util.Log.i("PlayerViewModel", "init: START")
        SongRepository.init(application)

        // [V8.214] 缓存恢复已移出主线程：等 songsFlow 给出缓存（最多 5s）即走 FAST PATH；
        // 超时/无缓存则退回 MediaStore 全量扫描（SLOW PATH）。全程不阻塞主线程 → 不再开屏「没有响应」。
        viewModelScope.launch {
            val cachedSongs = withTimeoutOrNull(5000) { SongRepository.songsFlow.first { it.isNotEmpty() } }
                ?: SongRepository.getSongs()
            android.util.Log.i("PlayerViewModel", "init: FAST PATH cachedSongs.size=${cachedSongs.size}")
            if (cachedSongs.isNotEmpty()) {
                android.util.Log.i("PlayerViewModel", "init: ✓ FAST PATH restoring ${cachedSongs.size} songs from cache")
                _state.update { it.copy(
                    songCount = cachedSongs.size, songList = cachedSongs,
                    scanStatus = "done:${cachedSongs.size}", scanProgress = 1f
                ) }
                android.util.Log.i("PlayerViewModel", "init: after _state.update, _state.value.songList.size=${_state.value.songList.size}")
                syncRestoreOrAutoPlay(cachedSongs, "fast")
                // [V8.87] Cached library: fill in WAV covers the platform cannot report.
                launchWavCoverHeal()
            } else {
                android.util.Log.w("PlayerViewModel", "init: ✗ FAST PATH empty cache, falling back to scan")
                _state.update { it.copy(scanStatus = "loading", scanProgress = 0.05f) }
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
                // [V8.87] Freshly scanned library: fill in WAV covers.
                launchWavCoverHeal()
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
                        // V8.92 提取Cover强调色；提取不到就**显式回退主题色**（accentColor = 0L）。
                        // 旧实现在 extractAccentColor 返回 null 时什么都不做，accentColor 会保留
                        // 上一首的残留色 —— 而扫描器给每行都写了非空 albumArtUri（专辑未知时是
                        // .../albumart/null 这种取不到图的死 URI），所以 coverUri 几乎恒非空，
                        // randomAccentColor 分支永不触发，表现就是「无封面歌的强调色不跟主题」。
                        viewModelScope.launch {
                            val coverUri = it.albumArtUri.takeIf { uri -> SongRepository.hasRealCover(uri) }
                            var gotColor = false
                            if (coverUri != null) {
                                val color = MemoryManager.extractAccentColor(application, coverUri, it.id)
                                if (color != null) {
                                    gotColor = true
                                    _state.update { s -> s.copy(
                                        accentColor = color.toLong() and 0xFFFFFFFFL,
                                        accentColorSource = coverUri
                                    ) }
                                    MusicWidgetProvider.updateAllWidgets(application)
                                }
                                val colors = MemoryManager.extractCoverColors(application, coverUri, it.id)
                                // 【V8.178】提取失败也清空：保留旧值会让 VU 表/边缘光带
                                // 一直挂在上首歌的调色板上（用户看到的"颜色不跟歌"）。
                                _state.update { s -> s.copy(
                                    coverColors = colors?.toList() ?: emptyList(),
                                    coverColorsSource = coverUri
                                ) }
                            }
                            if (!gotColor) {
                                // 0L = 交给 UI 侧取主题色（PlayerScreen 用全局 primary，
                                // LyricFullscreenScreen 有同样的 0L 保护）
                                // 【V8.207】accentColorSource 照样记成这首歌：UI 要的是
                                // "这首歌算过了"，没算出颜色也是一种结果，不能让画面一直挂旧色。
                                _state.update { s -> s.copy(
                                    accentColor = 0L,
                                    accentColorSource = coverUri,
                                    coverColors = emptyList(),
                                    coverColorsSource = null
                                ) }
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
            is PlayerIntent.ReorderQueueByFlow -> {
                reorderQueueByFlow()
            }
            is PlayerIntent.ToggleEqSheet -> {
                _state.update { it.copy(showEqSheet = intent.show) }
            }
            is PlayerIntent.SelectEqPreset -> {
                val ctx = getApplication<android.app.Application>().applicationContext
                com.sdw.music.player.core.audio.dsp.EqualizerManager.applyPreset(intent.presetId, ctx)
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
                // [V8.138] 保存内嵌歌词会整文件重写，媒体库封面索引随之失效；LyricRepository
                // 已在我们前方把容器内封面重新抽回并写进仓库。这里把新封面同步到 UI 状态 +
                // 通知栏 / 小部件，否则用户停在播放页会觉得「封面被删了，只剩 logo」。
                viewModelScope.launch {
                    try {
                        val fresh = SongRepository.getSongs()
                        val id = _state.value.currentSongId
                        val song = fresh.find { it.id == id } ?: return@launch
                        val art = song.albumArtUri
                        if (art.isNotBlank() && art != _state.value.currentSongAlbumArt) {
                            _state.update { it.copy(currentSongAlbumArt = art, songList = fresh) }
                            try { MusicService.instance?.refreshMetadata() } catch (_: Exception) {}
                            val coverUri = art.takeIf { SongRepository.hasRealCover(it) }
                            if (coverUri != null) {
                                val color = MemoryManager.extractAccentColor(getApplication(), coverUri, song.id)
                                if (color != null) {
                                    _state.update { s -> s.copy(
                                        accentColor = color.toLong() and 0xFFFFFFFFL,
                                        accentColorSource = coverUri
                                    ) }
                                }
                                val colors = MemoryManager.extractCoverColors(getApplication(), coverUri, song.id)
                                _state.update { s -> s.copy(
                                    coverColors = colors?.toList() ?: emptyList(),
                                    coverColorsSource = coverUri
                                ) }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PlayerViewModel", "SaveLyrics art refresh failed", e)
                    }
                }
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

    /** 【V8.244】自动流程排序：把当前播放队列按能量/调性重排（以当前曲为锚）。 */
    fun reorderQueueByFlow() {
        connection.reorderQueueByFlow()
        // reorderQueueByFlow() 已把重排结果同步进 PlayerConnection.songList
        val updated = connection.songList.value
        if (updated.isNotEmpty()) {
            _state.update { it.copy(queue = updated, songCount = updated.size) }
        }
    }

    /** 【V8.250】Automix 预览：接下来怎么接（歌 + 理由），只读。 */
    fun previewUpNext(count: Int = 3): List<Pair<Song, String>> = connection.previewUpNext(count)

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

    /**
     * Lyrico 等外部编辑器保存后，用其回传的标签 extras 就地更新内存中的歌曲，
     * 避免整库重扫 MediaStore。仅覆盖 extras 中存在的非空字段。
     */
    fun applyExternalTagEdits(
        songId: Long,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
    ) {
        if (songId <= 0) return
        _state.update { st ->
            val newList = st.songList.map { s ->
                if (s.id == songId) s.copy(
                    title = title ?: s.title,
                    artist = artist ?: s.artist,
                    album = album ?: s.album,
                    genre = genre ?: s.genre,
                ) else s
            }
            val cur = if (st.currentSongId == songId) st.copy(
                currentSongTitle = title ?: st.currentSongTitle,
                currentSongArtist = artist ?: st.currentSongArtist,
                currentSongAlbum = album ?: st.currentSongAlbum,
            ) else st
            cur.copy(songList = newList)
        }
    }

    /**
     * Lyrico（或任意外部标签编辑器）编辑完歌曲返回时调用。
     *
     * [extras] 携带回传的新标签值（Lyrico PR #196+ 支持 result-extras 时）走就地更新；
     * 为 null 时退化为整库重扫以拾取改动。两种情况都会**直接从被改写的文件重新抽取内嵌封面**
     * 写进自有缓存（key 含 size+mtime → 每次编辑都得到新 URI），从而让播放器即时显示新封面，
     * 而不依赖平台媒体库索引（其 albumart URI 改写后字符串不变，Coil 会一直喂旧图）。
     */
    fun onExternalTagEditReturned(songId: Long, filePath: String, extras: Bundle?) {
        if (songId <= 0) return
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>().applicationContext
                val fp = filePath.ifBlank { "" }
                // ① 文本字段
                if (extras != null && hasLyricoEditExtras(extras)) {
                    val str: (String) -> String? = { extras.getString(it)?.takeIf { v -> v.isNotBlank() } }
                    applyExternalTagEdits(
                        songId = songId,
                        title = str(LyricoResultExtras.TITLE),
                        artist = str(LyricoResultExtras.ARTIST),
                        album = str(LyricoResultExtras.ALBUM),
                        genre = str(LyricoResultExtras.GENRE),
                    )
                } else {
                    // 退化：重扫库，拾取标题/歌手/专辑等改动并刷新 songList + 当前播放文本
                    val scanned = SongRepository.rescanFromMediaStore(ctx)
                    if (scanned.isNotEmpty()) {
                        SongRepository.setSongs(scanned)
                        connection.setSongs(scanned, updateGlobal = false)
                        val id = songId
                        _state.update { st ->
                            val fresh = scanned.find { it.id == id }
                            val newList = st.songList.map { s -> if (s.id == id) (fresh ?: s) else s }
                            val cur = if (st.currentSongId == id) st.copy(
                                currentSongTitle = fresh?.title ?: st.currentSongTitle,
                                currentSongArtist = fresh?.artist ?: st.currentSongArtist,
                                currentSongAlbum = fresh?.album ?: st.currentSongAlbum,
                            ) else st
                            cur.copy(songList = newList)
                        }
                    }
                }
                // ② 封面：从被改写的文件重新抽内嵌图。freshArt 为 null 表示封面已被删除
                //    （WAV/FLAC 删封面时 reader 返回 null），需把 currentSongAlbumArt 置空让播放页
                //    回退默认封面，而不能保留旧图；有图则就地更新。
                val freshArt = SongRepository.refreshArtAfterRewrite(ctx, fp)
                // 无封面（删除）时用空串清掉 albumArtUri/currentSongAlbumArt，与"无封面=空 URI"约定一致，
                // resolveCoverUri 会据此回退默认封面；有图则就地更新。
                val art = freshArt ?: ""
                _state.update { st ->
                    val id = st.songList.find { it.filePath == fp }?.id ?: songId
                    val newList = st.songList.map { s -> if (s.id == id) s.copy(albumArtUri = art) else s }
                    val cur = if (st.currentSongId == id) st.copy(currentSongAlbumArt = art) else st
                    cur.copy(songList = newList)
                }
                val coverUri = freshArt.takeIf { SongRepository.hasRealCover(it) }
                if (coverUri != null) {
                    val color = MemoryManager.extractAccentColor(ctx, coverUri, songId)
                    if (color != null) {
                        _state.update { s -> s.copy(
                            accentColor = color.toLong() and 0xFFFFFFFFL,
                            accentColorSource = coverUri
                        ) }
                    }
                    val colors = MemoryManager.extractCoverColors(ctx, coverUri, songId)
                    _state.update { s -> s.copy(
                        coverColors = colors?.toList() ?: emptyList(),
                        coverColorsSource = coverUri
                    ) }
                } else {
                    // 无封面：清掉强调色/调色板源，回到默认
                    _state.update { s -> s.copy(
                        coverColors = emptyList(),
                        coverColorsSource = null
                    ) }
                }
                try { MusicService.instance?.refreshMetadata() } catch (_: Exception) {}
            } catch (e: Exception) {
                android.util.Log.w("PlayerViewModel", "onExternalTagEditReturned failed", e)
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
        // file:// URI 几乎都来自文件管理器 / 「用其他应用打开」，但播放器要的是真实文件系统路径：
        // 把 "file:///sdcard/x.flac" 直接丢给 fopen 只会得到 File does not exist。
        // content:// 那边能靠 contentResolver 查到 _data，这里只能退回 uri.path。
        val resolvedPath = if (uri.scheme == "file") runCatching {
            uri.path ?: uri.toString()
        }.getOrDefault(uri.toString()) else uri.toString()
        return Song(
            id = -(System.nanoTime() and Long.MAX_VALUE), // 临时负 ID 避免与媒体库冲突
            title = displayTitle,
            artist = SongRepository.sanitizeArtist(songArtist),
            album = SongRepository.sanitizeAlbum(songAlbum),
            duration = songDuration,
            path = resolvedPath,
            albumArtUri = "",
            filePath = resolvedPath
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

    override fun onCleared() {
        super.onCleared()
        persistCurrentState()
        connection.release()
    }
}


