package com.sdw.music.player.core.audio

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sdw.music.player.MusicService
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.FileLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 杩炴帴灞傦細UI (ViewModel) ? MusicService (MediaSessionService)
 * 
 * 浣跨敤娴佺▼锛?
 * 1. UI 灞傚垱寤?PlayerConnection 瀹炰緥锛堟垨閫氳繃 Hilt 娉ㄥ叆锛?
 * 2. connect() 寤虹珛 MediaController 杩炴帴
 * 3. UI 閫氳繃 setSongs() SettingsPlaylists锛岄€氳繃 playSong() 鎾斁
 * 4. UI 閫氳繃 state flow 瑙傚療鎾斁鐘舵€佸彉鍖?
 */
class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connected = false
    private var pendingPlayIndex: Int = -1

    // 播放状态监听：订阅 MusicService.notifyPlayStateChanged 单一权威源
    // 通知栏/锁屏/切歌等任意来源的状态变更，都会反向同步到 UI 的 _isPlaying
    private val playStateChangedListener = object : MusicService.OnPlayStateChangedListener {
        override fun onPlayStateChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }
    }

    // 鎾斁鐘舵€?
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentSongIndex = MutableStateFlow(-1)
    val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _songList = MutableStateFlow<List<Song>>(emptyList())
    val songList: StateFlow<List<Song>> = _songList.asStateFlow()

    // 杩涘害鏇存柊 job
    private var positionJob: Job? = null

    // Oboe 鐙崰妯″紡鍏滃簳锛氱洿鎺ユ敹闆?MusicService.songChangedFlow锛堜笉鐢ㄦ帴鍙ｏ紝闃?R8 鎿﹂櫎锛?
    private var oboeSongJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // In Oboe/USB DAC mode, ExoPlayer is idle; don't let its callback override the actual state
            if (MusicService.instance?.isOboeDirectMode() == true || MusicService.instance?.isUsbExclusiveMode() == true) return
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                controller?.duration?.takeIf { it > 0 }?.let {
                    _durationMs.value = it
                }
            }
            if (state == Player.STATE_ENDED) {
                // 鑷姩涓嬩竴棣栵紙姝屽崟涓虹┖鍒欒烦杩囷級
                val size = _songList.value.size
                if (size > 0) {
                    val nextIndex = (_currentSongIndex.value + 1) % size
                    if (nextIndex > 0) playSong(nextIndex)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // [V3.3.6] DAC 模式下 ExoPlayer 只有单曲，index 无意义，跳过更新
            // 用实际 DAC claim 状态判断，而非设置开关
            if (com.sdw.music.player.core.audio.UsbDacManager.isClaimed()) return
            controller?.currentMediaItemIndex?.let { idx ->
                _currentSongIndex.value = idx
                if (idx in _songList.value.indices) {
                    _currentSong.value = _songList.value[idx]
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleEnabled: Boolean) {
            _shuffleEnabled.value = shuffleEnabled
            android.util.Log.d("PlayerConnection", "onShuffleModeEnabledChanged: $shuffleEnabled (from MediaController)")
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }
    }

    /**
     * 杩炴帴 MediaController 鍒?MusicService
     * 骞傜瓑锛氬凡杩炴帴鍒欒烦杩囷紝闃叉閲嶅鍒涘缓 MediaController
     */
    fun connect() {
        if (connected && controller != null) return
        // 鍏堟柇On鏃ц繛鎺ワ紝闃叉澶氬疄渚嬫畫鐣?
        disconnect()
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            connected = true
            MusicService.addPlayStateChangedListener(playStateChangedListener)
            // Oboe 模式兜底：MediaController.onMediaItemTransition 不触发
            // 直接收集 MusicService.StateFlow 同步元数据（不用接口，防 R8 擦除）
            startOboeSongSync()
            // [Phase 1] 反向同步 shuffle/repeat
            startShuffleRepeatSync()
            // 如果有待播请求，执行
            if (pendingPlayIndex >= 0) {
                playSong(pendingPlayIndex)
                pendingPlayIndex = -1
            }
            // 鍚屾鍒濆鐘舵€?
            controller?.let {
                _isPlaying.value = it.isPlaying
                _durationMs.value = it.duration.coerceAtLeast(0)
                // [V3.3.6] DAC mode: skip index update (ExoPlayer has single item in DAC mode)
                if (com.sdw.music.player.core.audio.UsbDacManager.isClaimed() != true) {
                    _currentSongIndex.value = it.currentMediaItemIndex
                }
                // [V3.3.6] DAC mode: skip restoredIdx (index meaningless)
                if (com.sdw.music.player.core.audio.UsbDacManager.isClaimed() != true) {
                    val restoredIdx = it.currentMediaItemIndex
                    val songs = _songList.value
                    if (restoredIdx in songs.indices) {
                        _currentSong.value = songs[restoredIdx]
                    } else {
                        val mediaItem = it.currentMediaItem
                        if (mediaItem != null) {
                            _currentSong.value = Song(
                                id = mediaItem.mediaId?.toLongOrNull() ?: -1L,
                                title = mediaItem.mediaMetadata.title?.toString() ?: "",
                                artist = mediaItem.mediaMetadata.artist?.toString() ?: "",
                                album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
                                duration = it.duration,
                                path = mediaItem.localConfiguration?.uri?.toString() ?: "",
                                albumArtUri = mediaItem.mediaMetadata.artworkUri?.toString() ?: ""
                            )
                        }
                    }
                }
                _shuffleEnabled.value = it.shuffleModeEnabled
                _repeatMode.value = it.repeatMode
                if (it.isPlaying) startPositionUpdates()
                // 銆恦7.XX銆戦噸杩炴椂灏濊瘯鎭㈠Playlists
                if (_songList.value.isEmpty() && it.mediaItemCount > 0) {
                    val recoveredSongs = mutableListOf<Song>()
                    for (i in 0 until it.mediaItemCount) {
                        val mi = it.getMediaItemAt(i)
                        if (mi != null) {
                            recoveredSongs.add(Song(
                                id = mi.mediaId?.toLongOrNull() ?: i.toLong(),
                                title = mi.mediaMetadata.title?.toString() ?: "",
                                artist = mi.mediaMetadata.artist?.toString() ?: "",
                                album = mi.mediaMetadata.albumTitle?.toString() ?: "",
                                duration = it.duration,  // approximate
                                path = mi.localConfiguration?.uri?.toString() ?: "",
                                albumArtUri = mi.mediaMetadata.artworkUri?.toString() ?: ""
                            ))
                        }
                    }
                    if (recoveredSongs.isNotEmpty()) {
                        _songList.value = recoveredSongs
                    }
                }
                // 銆愪慨澶嶃€戣繘绋嬭鏉€鍚?Service 宸查噸寤?鈫?controller 鏃?mediaItem
                // 浠?SharedPreferences 鎭㈠鎾斁鐘舵€佸苟閲嶆柊 setMediaItems
                // 銆愬叧閿慨澶嶃€戝欢杩?500ms 绛?MediaController 瀹屾垚鍒濆鍚屾
                if (it.mediaItemCount == 0 && _songList.value.isNotEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (controller?.isConnected == true && controller?.mediaItemCount == 0 && controller?.isPlaying == false && _songList.value.isNotEmpty()) {
                            restoreFromSavedState()
                        }
                    }, 500L)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * 鏂紑杩炴帴
     * 銆愪慨澶嶃€戜笉鍐嶈皟鐢?MediaController.releaseFuture()锛屼繚鐣欏簳灞傝繛鎺ャ€?
     * 鍘熷洜锛歁ediaSessionService 妫€娴嬪埌鏈€鍚庝竴涓?Controller 鏂紑浼氳嚜鍔?stopSelf()锛?
     * 瀵艰嚧鎵嬪娍杩斿洖妗岄潰鏃舵挱鏀句腑鏂€備繚鐣欒繛鎺ヨ Service 涓嶄細璇垽涓恒€屾棤瀹㈡埛绔€嶈€岃嚜姣併€?
     */
    fun disconnect() {
        if (!connected && controller == null) return
        stopPositionUpdates()
        oboeSongJob?.cancel()
        oboeSongJob = null
        shuffleSyncJob?.cancel()
        shuffleSyncJob = null
        repeatSyncJob?.cancel()
        repeatSyncJob = null
        try {
            controller?.removeListener(playerListener)
        } catch (e: Exception) {
            android.util.Log.w("PlayerConnection", "removeListener failed: ${e.message}")
        }
        controller = null
        controllerFuture = null
        connected = false
        MusicService.removePlayStateChangedListener(playStateChangedListener)
        pendingPlayIndex = -1
        // 涓嶅啀 releaseFuture 鈥?淇濇寔搴曞眰 MediaController 涓?Service 鐨勮繛鎺?
        // 閲嶈繛鏃?connect() 浼氬垱寤烘柊鐨?MediaController锛屾棫鐨勪細闅忚繘绋嬬敓鍛藉懆鏈熻嚜鐒堕噴鏀?
    }

    private fun startOboeSongSync() {
        oboeSongJob?.cancel()
        oboeSongJob = scope.launch {
            MusicService.songChangedFlow.collect { song ->
                FileLog.log("SONG", "songChangedFlow emit: ${song?.title} id=${song?.id} dur=${song?.duration}")
                if (song != null) _currentSong.value = song
            }
        }
    }

    // [Phase 1] 卡片/任意来源的 shuffle/repeat 改动 → 反向同步 UI（解决单向同步）
    private var shuffleSyncJob: Job? = null
    private var repeatSyncJob: Job? = null

    private fun startShuffleRepeatSync() {
        shuffleSyncJob?.cancel()
        repeatSyncJob?.cancel()
        shuffleSyncJob = scope.launch {
            MusicService.shuffleModeFlow.collect { enabled ->
                _shuffleEnabled.value = enabled
            }
        }
        repeatSyncJob = scope.launch {
            MusicService.repeatModeFlow.collect { mode ->
                _repeatMode.value = mode
            }
        }
    }

    /**
     * SettingsPlaylists锛堜笉绔嬪嵆鎾斁锛?
     */
    fun setSongs(songs: List<Song>, updateGlobal: Boolean = true) {
        _songList.value = songs
        // 鍚屾鍒?MusicService
        // 同步到 MusicService 的播放清单（同步写 servicePlaylist，确保紧接着的 playSong 用同一份列表；否则 USB/Oboe 模式会按旧列表错位放错歌）
        MusicService.instance?.setServicePlaylist(songs)
        scope.launch {
            if (updateGlobal) SongRepository.setSongs(songs)
        }
    }

    /** 缂栬緫姝屾洸鍏冩暟鎹悗鍚屾 Connection 鍐呯殑 Song 鍓湰锛岄槻姝?currentSong 鍥炶皟瑕嗙洊鏂版爣棰?*/
    fun updateSongInList(songId: Long, newTitle: String, newArtist: String) {
        _songList.value = _songList.value.map {
            if (it.id == songId) it.copy(title = newTitle, artist = newArtist) else it
        }
        val cur = _currentSong.value
        if (cur != null && cur.id == songId) {
            _currentSong.value = cur.copy(title = newTitle, artist = newArtist)
        }
    }

    /**
     * 鎾斁鎸囧畾绱㈠紩鐨勬瓕鏇?
     */
    fun playSong(index: Int) {
        val songs = _songList.value
        if (index !in songs.indices) return
        if (!connected) {
            pendingPlayIndex = index
            return
        }
        // 銆怴7.82銆慜boe妯″紡涓嬬洿鎺ヨ皟鐢∕usicService锛岀粫杩嘙ediaController锛圗xoPlayer绌洪棽涓嶅搷搴旓級
        if (MusicService.instance?.isUsbExclusiveMode() == true) {
            android.util.Log.i("PlayerConnection", "playSong: USB DAC mode, delegating to MusicService")
            MusicService.instance?.playSong(index)
            _currentSongIndex.value = index
            _currentSong.value = songs[index]
            return
        }

        if (MusicService.instance?.isOboeDirectMode() == true) {
            android.util.Log.d("PlayerConnection", "playSong: Oboe mode, delegating to MusicService")
            MusicService.instance?.playSong(index)
            _currentSongIndex.value = index
            _currentSong.value = songs[index]
            // 銆怴7.xx銆慜boe妯″紡ExoPlayer鏃犵姸鎬佸彉鍖栵紝涓嶄細瑙﹀彂onIsPlayingChanged锛屾墜鍔ㄥ惎鍔ㄨ繘搴︽洿鏂?
            return
        }
        controller?.let { ctrl ->
            val mediaItems = songs.mapIndexed { i, song ->
                MediaItem.Builder()
                    .setUri(song.path)
                    .setMediaId(song.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri?.let { android.net.Uri.parse(it) })
                        .build())
                    .build()
            }
            ctrl.setMediaItems(mediaItems, index, 0L)
            ctrl.prepare()
            ctrl.play()

            _currentSongIndex.value = index
            _currentSong.value = songs[index]
        }
    }

    /**
     * 鎾斁/鏆傚仠鍒囨崲
     */
    /**
     * 播放（幂等：已在播放则不重复；用服务实际状态判断，避免 UI StateFlow 滞后）
     */
    fun play() {
        val svc = MusicService.instance
        if (svc == null) {
            android.util.Log.w("PlayerConnection", "play: service is null")
            return
        }
        if (svc.isPlaying()) return
        if (svc.isUsbExclusiveMode()) {
            // 已持有 controller（暂停过）= 从暂停位置恢复；否则首次播放需 claim
            if (svc.getUsbDacController() != null) {
                svc.resume()
            } else {
                svc.playSong(_currentSongIndex.value)
            }
            return
        }
        if (svc.isOboeDirectMode()) {
            svc.resume()
            return
        }
        val ctrl = controller ?: return
        try {
            ctrl.play()
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "play failed", e)
        }
    }

    /**
     * 暂停（幂等：已暂停则不重复）
     */
    fun pause() {
        val svc = MusicService.instance ?: return
        if (!svc.isPlaying()) return
        if (svc.isUsbExclusiveMode()) {
            svc.pause()
            return
        }
        if (svc.isOboeDirectMode()) {
            svc.pause()
            return
        }
        val ctrl = controller ?: return
        try {
            ctrl.pause()
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "pause failed", e)
        }
    }

    /**
     * 播放/暂停切换
     */
    fun togglePlayPause() {
        // USB DAC Exclusive — trigger playSong for claim
        if (MusicService.instance?.isUsbExclusiveMode() == true) {
            val svc = MusicService.instance!!
            if (svc.isPlaying()) {
                svc.pause()
            } else if (svc.getUsbDacController() != null) {
                svc.resume()
            } else {
                android.util.Log.i("PlayerConnection", "togglePlayPause: USB DAC, triggering playSong")
                svc.playSong(_currentSongIndex.value)
            }
            return
        }

        // 銆怴7.82銆慜boe妯″紡涓嬬洿鎺ユ搷浣淢usicService锛岀粫杩嘙ediaController
        if (MusicService.instance?.isOboeDirectMode() == true) {
            val svc = MusicService.instance!!
            if (svc.isPlaying()) {
                svc.pause()
            } else {
                svc.resume()
            }
            return
        }
        val ctrl = controller
        if (ctrl == null) {
            android.util.Log.w("PlayerConnection", "togglePlayPause: controller is null, ignoring")
            return
        }
        try {
            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "togglePlayPause failed", e)
        }
    }

    /**
     * Previous
     */
    fun skipToPrevious() {
        if (MusicService.instance?.isUsbExclusiveMode() == true) {
            MusicService.instance?.playPrevious()
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            return
        }
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.playPrevious()
            // 銆怴7.xx銆慜boe鍒囨瓕鍚庢墜鍔ㄥ惎鍔ㄨ繘搴︽洿鏂?
            // 鍚屾鏇存柊 Connection 渚х殑姝屾洸绱㈠紩锛堝惁鍒橴I鏄剧ず鏃ф洸鐩俊鎭紝duration涓嶅锛?
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                // 浠?MusicService 鑾峰彇鐪熷疄 duration锛岃鐩栨棫缂撳瓨鐨?_durationMs
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            return
        }
        controller?.seekToPreviousMediaItem()
    }

    /**
     * 涓嬩竴鏇?
     */
    fun skipToNext() {
        if (MusicService.instance?.isUsbExclusiveMode() == true) {
            MusicService.instance?.playNext()
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            return
        }
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.playNext()
            // 銆怴7.xx銆慜boe鍒囨瓕鍚庢墜鍔ㄥ惎鍔ㄨ繘搴︽洿鏂?
            // 鍚屾鏇存柊 Connection 渚х殑姝屾洸绱㈠紩锛堝惁鍒橴I鏄剧ず鏃ф洸鐩俊鎭紝duration涓嶅锛?
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                // 浠?MusicService 鑾峰彇鐪熷疄 duration锛岃鐩栨棫缂撳瓨鐨?_durationMs
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            return
        }
        controller?.seekToNextMediaItem()
    }

    /**
     * 璺宠浆鍒版寚瀹氫綅缃紙姣锛?
     */
    fun seekTo(positionMs: Long) {
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.seekTo(positionMs)
            _currentPositionMs.value = positionMs
            return
        }
        // bypass MediaController (may be null or disconnected) — go straight to ExoPlayer
        MusicService.instance?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    /**
     * 鍒囨崲Shuffle鎾斁
     */
    fun setShuffleEnabled(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        MusicService.instance?.setShuffleMode(enabled)
    }

    /**
     * Settings寰幆妯″紡
     */
    fun setRepeatMode(mode: Int) {
        _repeatMode.value = mode
        MusicService.instance?.setRepeatMode(mode)
    }

    private var lastSaveTimeMs = 0L

    private fun startPositionUpdates() {
        stopPositionUpdates()
        android.util.Log.d("PC", "[PERF] startPositionUpdates called")
        FileLog.log("POS", "startPositionUpdates")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        positionJob = scope.launch {
            android.util.Log.d("PC", "[PERF] position loop started")
            var tick0 = true
            var tickN = 0
            while (isActive) {
                val screenOn = pm.isInteractive
                if (screenOn) {
                    // 銆怴7.82銆慜boe妯″紡涓嬩粠MusicService鐩存帴璇诲彇浣嶇疆锛岃€岄潪MediaController锛堝悗鑰呮潵鑷狤xoPlayer锛?
                    val svc = MusicService.instance
                    val isOboe = svc?.isOboeDirectMode() == true
                    if (tick0) {
                        tick0 = false
                        android.util.Log.d("PC", "[PERF] tick0: isOboe=$isOboe svc=${svc!=null} screenOn=$screenOn prepared=${svc?.oboeDirectPlayer?.isPrepared}")
                        FileLog.log("POS", "tick0 isOboe=$isOboe svc=${svc!=null} prepared=${svc?.oboeDirectPlayer?.isPrepared}")
                    }
                    if (isOboe && svc != null) {
                        val pos = svc.getCurrentPosition()
                        var dur = svc.getDuration()
                        if (dur <= 0) dur = _currentSong.value?.duration ?: 0  // fallback: MediaStore duration
                        if (tickN < 3) {
                            android.util.Log.d("PC", "[PERF] Oboe tick$tickN pos=$pos dur=$dur songDur=${_currentSong.value?.duration}")
                            FileLog.log("POS", "tick$tickN pos=$pos dur=$dur songDur=${_currentSong.value?.duration}")
                            tickN++
                        }
                        _currentPositionMs.value = pos
                        if (dur > 0) _durationMs.value = dur
                    } else {
                        controller?.let { ctrl ->
                            val pos = ctrl.currentPosition.coerceAtLeast(0)
                            val dur = ctrl.duration.coerceAtLeast(0)
                            _currentPositionMs.value = pos
                            if (dur > 0) _durationMs.value = dur
                        }
                    }
                    // Save鎾斁浣嶇疆鐢ㄤ簬杩涚▼鎭㈠
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTimeMs > 300_000L) {
                        try { MusicService.savePlaybackState() } catch (_: Exception) {}
                        lastSaveTimeMs = now
                    }
                    delay(1000) // 浜睆锛氭瘡绉掓洿鏂拌繘搴?
                } else {
                    // 鐔勫睆锛氬彧鍋歴ave妫€鏌ワ紝璺宠繃杩涘害鏇存柊鍜孲tateFlow鍐欏叆锛屽噺灏慍ompose閲嶇粍
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTimeMs > 300_000L) {
                        try { MusicService.savePlaybackState() } catch (_: Exception) {}
                        lastSaveTimeMs = now
                    }
                    delay(2000) // 鐔勫睆闄嶄綆妫€鏌ラ鐜?
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * 銆愪慨澶嶃€戜粠 SharedPreferences 鎭㈠鎾斁鐘舵€侊紙杩涚▼琚潃鍚庨噸鍚椂浣跨敤锛?
     * Service 閲嶅缓鍚?MediaController 鏃?mediaItem 鈫?浠嶴ave鐨勭姸鎬侀噸寤?
     */
    private fun restoreFromSavedState() {
        try {
            val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
            val savedSongId = prefs.getLong("last_song_id", -1L)
            if (savedSongId <= 0) return
            val savedPos = prefs.getLong("last_position_ms", 0L)
            val wasPlaying = prefs.getBoolean("was_playing", false)
            val songs = _songList.value
            val index = songs.indexOfFirst { it.id == savedSongId }
            if (index < 0) return

            val song = songs[index]
            val mediaItems = songs.mapIndexed { i, s ->
                MediaItem.Builder()
                    .setUri(s.path)
                    .setMediaId(s.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .setArtworkUri(s.albumArtUri?.let { android.net.Uri.parse(it) })
                        .build())
                    .build()
            }
            val ctrl = controller ?: return
            ctrl.setMediaItems(mediaItems, index, savedPos)
            ctrl.prepare()
            _currentSongIndex.value = index
            _currentSong.value = song
            _durationMs.value = song.duration
            android.util.Log.d("PlayerConnection",
                "restoreFromSavedState: song=${song.title}, pos=$savedPos, wasPlaying=$wasPlaying")
            // 鍙湪纭疄闇€瑕佹仮澶嶆挱鏀炬椂鎵嶈皟鐢?play()
            // [v10] No auto-play on app restart
            // if (wasPlaying) { ctrl.play(); }
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "restoreFromSavedState failed", e)
        }
    }

    /**
     * 娓呯悊璧勬簮锛圴iewModel onCleared 鏃惰皟鐢級
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}

