package com.sdw.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.sdw.music.player.widget.MusicWidgetProvider
import com.sdw.music.player.widget.MusicWidgetProvider3x2
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sdw.music.player.core.audio.helpers.VolumeGuard
import com.sdw.music.player.core.audio.helpers.VisualizerManager
import com.sdw.music.player.core.audio.UsbDacManager
import com.sdw.music.player.core.audio.UsbDacPlaybackController
import com.sdw.music.player.core.audio.DebugLog
import com.sdw.music.player.R
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    // 
    private val visualizerManager by lazy {
        VisualizerManager(
            getPlayer = { mediaSession?.player },
            getFftCallback = { fftCallback },
            tag = TAG
        )
    }
    // ProcessLifecycleOwner tracks app foreground/background reliably
    // (ActivityLifecycleCallbacks can fire too late �?Service starts after Activity resumes)
    // Delayed release to avoid flicker during activity transitions or brief screen-off
    private var isAppForeground = false
    private val visualizerReleaseTask = Runnable {
        if (!isAppForeground) {
            Log.d(TAG, "Delayed Visualizer release (background confirmed)")
            visualizerManager.release()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "MusicService"
    private var stopDelayRunnable: Runnable? = null
    private var isDestroyed = false

    // URB wedge watchdog: monitor native stream health, force-recover on stall
    private val dacHealthRunnable = object : Runnable {
        private var wedgeSeenAt = 0L
        override fun run() {
            if (isDestroyed || usbDacController?.isPlaying != true || !UsbDacManager.isClaimed()) {
                handler.postDelayed(this, 5000)
                return
            }
            val stats = UsbDacManager.getStats() ?: run { handler.postDelayed(this, 5000); return }
            if (stats.contains("Health=wedge")) {
                val now = System.currentTimeMillis()
                if (wedgeSeenAt == 0L) wedgeSeenAt = now
                if (now - wedgeSeenAt > 3000) {
                    Log.w(TAG, "DAC wedge detected (2+ polls), forceReset…")
                    DebugLog.add(TAG, "DAC wedge → forceReset")
                    usbDacController?.stop()
                    UsbDacManager.forceReset()
                    wedgeSeenAt = 0L
                    tryClaimUsbDac()
                    if (currentSong != null) playSong(currentIndex)
                }
            } else {
                wedgeSeenAt = 0L
            }
            handler.postDelayed(this, 5000)
        }
    }

    // Sleep timer
    @Volatile private var sleepTimerEndAt = 0L
    private var sleepTimerMinutes: Long = 0L
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val remaining = sleepTimerEndAt - System.currentTimeMillis()
            if (sleepTimerEndAt == 0L || remaining <= 0) {
                if (sleepTimerEndAt > 0L && remaining <= 0 && isPlaying()) {
                    Log.i(TAG, "Sleep timer elapsed, pausing")
                    DebugLog.add(TAG, "\u23f0 Sleep timer → pause")
                    pause()
                    notifySleepTimerEnded()
                }
                sleepTimerEndAt = 0L
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun notifySleepTimerEnded() {
        val ctx = applicationContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("\u23f0 Sleep Timer")
            .setContentText("Playback paused after $sleepTimerMinutes min")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        try { manager?.notify(999, n) } catch (_: Exception) {}
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerMinutes = minutes.toLong()
        sleepTimerEndAt = System.currentTimeMillis() + minutes * 60000L
        handler.removeCallbacks(sleepTimerRunnable)
        handler.post(sleepTimerRunnable)
        Log.i(TAG, "Sleep timer set: ${minutes}min")
        DebugLog.add(TAG, "\u23f0 Sleep timer: ${minutes} min")
    }

    fun cancelSleepTimer() {
        sleepTimerEndAt = 0L
        handler.removeCallbacks(sleepTimerRunnable)
        DebugLog.add(TAG, "\u23f0 Sleep timer cancelled")
    }

    fun getSleepTimerRemainingMs(): Long {
        if (sleepTimerEndAt == 0L) return 0L
        return (sleepTimerEndAt - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun isSleepTimerActive(): Boolean = sleepTimerEndAt > 0L

    // 
    private val volumeGuard by lazy {
        VolumeGuard(this, this::pause, this::resume, this::isPlaying) { pct ->
            // [V8.x] System volume key �� USB DAC digital gain
            val claimed = com.sdw.music.player.core.audio.UsbDacManager.isClaimed()
            DebugLog.add(TAG, "VolumeGuard callback: pct=$pct claimed=$claimed")
            if (claimed) {
                UsbDacManager.setVolume(pct)
            }
        }
    }

    // 
    var oboeDirectPlayer: OboeDirectPlayer? = null
    private val oboeSwitchLock = Any()  // 串行化切歌线程，防 native 全局状态并发损坏
    private var useOboeDirect: Boolean = false

    // USB DAC Exclusive mode controller
    private var usbDacController: UsbDacPlaybackController? = null
    private var dacPlayGeneration: Int = 0  // [V4.0.1] invalidate stale onCompletion
    private var dacWakeLock: PowerManager.WakeLock? = null  // [V4.0.2] prevent CPU deep-sleep in Doze mode

    // 
    private var dspEqEnabled: Boolean = false

    // [V8.x] AudioDeviceCallback: detect USB DAC hotplug -> restart Oboe Exclusive
    @Volatile  // [V4.1.x] Oboe pause/resume state �� avoids JNI isPlaying lag in getPlayWhenReady()
    private var oboePlayWhenReady: Boolean = true
    private var oboeUsbGuardMs: Long = 0L
    private var oboeSuppressUsbRestart: Boolean = false  // [V8.2] block USB restarts when Oboe is already playing
    private val usbDacCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (System.currentTimeMillis() < oboeUsbGuardMs) return  // startup race guard
            if (oboeSuppressUsbRestart) return  // Oboe already running, don&apos;t restart
            val hasUsbDac = addedDevices.any { d ->
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            if (!hasUsbDac) return
            // USB DAC Exclusive mode takes priority
            if (isUsbExclusiveMode()) {
                Log.i(TAG, "USB DAC detected in exclusive mode, attempting claim")
                tryClaimUsbDac()
                return
            }
            if (!isOboeDirectMode() || oboeDirectPlayer?.isPlaying != true) return
            Log.i(TAG, "USB DAC detected, restarting Oboe stream for Exclusive attempt")
            val savedPos = oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
            val idx = currentIndex
            handler.postDelayed({
                playSong(idx)
                if (savedPos > 1000) {
                    handler.postDelayed({
                        oboeDirectPlayer?.seekTo(savedPos)
                    }, 400)
                }
            }, 800)
        }
    }

    internal fun isUsbExclusiveMode(): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        return prefs.getBoolean("usb_exclusive", false)
    }
    
    // [V3.3.6] Public getter for AudioDiagnosticScreen
    fun getUsbDacController(): UsbDacPlaybackController? = usbDacController

    private fun releaseUsbDacController() {
        handler.removeCallbacks(dacHealthRunnable)
        DebugLog.add(TAG, "releaseUsbDac: stopDecode (keep DAC claim)")
        usbDacController?.stopDecode()
        usbDacController = null
        releaseDacWakeLock()
    }

    private fun acquireDacWakeLock() {
        if (dacWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            dacWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDWMP3:USB_DAC")
        }
        if (dacWakeLock?.isHeld == false) {
            dacWakeLock?.acquire()
            Log.w(TAG, "WakeLock acquired for USB DAC")
        }
    }

    private fun releaseDacWakeLock() {
        if (dacWakeLock?.isHeld == true) {
            dacWakeLock?.release()
            Log.w(TAG, "WakeLock released")
        }
    }

    private fun tryClaimUsbDac() {
        if (!isUsbExclusiveMode()) return
        val dacs = UsbDacManager.findDacs()
        if (dacs.isNotEmpty()) {
            Log.d(TAG, "USB DAC detected, requesting permission for ${dacs.first().name}")
            // Permission callback is handled internally by UsbDacManager
            val usbManager = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            if (usbManager != null) {
                val device = usbManager.deviceList.values.find { it.vendorId == dacs.first().vid && it.productId == dacs.first().pid }
                if (device != null) {
                    UsbDacManager.requestPermission(device, this@MusicService)
                }
            }
        }
    }

    // [V8.x] Cached to avoid SharedPreferences I/O on every call (was causing UI jank in DAC mode)
    private var cachedOboeMode: Boolean = false
    private var oboeModeCacheValid: Boolean = false

    // =====================================================================
    // [Phase 1] MusicPlayerState: SimpleBasePlayer as MediaSession shell.
    // 状态机「收归国有」：getState() 是纯投影，只读 MusicService 权威字段，
    // 不再依赖 ExoPlayer 状态。命令经 handleXxx 路由到 MusicService 权威方法。
    // =====================================================================
    private val musicPlayerState: MusicPlayerState by lazy {
        MusicPlayerState(Looper.getMainLooper())
    }

    private inner class MusicPlayerState(
        looper: Looper
    ) : SimpleBasePlayer(looper) {

        /** [Phase 1] 供 MusicService 权威方法在状态变更后主动刷新投影 */
        fun refresh() {
            try { invalidateState() } catch (_: Exception) {}
        }

        override fun getState(): State {
            val song = MusicService.currentSong
            val songs = this@MusicService.servicePlaylist.ifEmpty { SongRepository.getSongs() }

            // ⚠ inner class 方法/字段名会遮蔽 SimpleBasePlayer 的同名 getter（getRepeatMode()
            // → 属性 repeatMode、getShuffleModeEnabled() → 属性 shuffleModeEnabled 等），
            // 必须用 this@MusicService 显式限定，否则 getRepeatMode()→verifyAndInit→getState()
            // 无限递归 → OOM
            val playing = this@MusicService.isPlaying()

            val playbackState = when {
                song == null -> Player.STATE_IDLE
                else -> Player.STATE_READY
            }

            val posMs = this@MusicService.getCurrentPosition().coerceAtLeast(0L)
            val posSupplier = if (playing)
                SimpleBasePlayer.PositionSupplier.getExtrapolating(posMs, 1f)
            else
                SimpleBasePlayer.PositionSupplier.getConstant(posMs)

            val commands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SET_REPEAT_MODE,
                    // 读取命令：系统媒体卡片渲染进度条/active item 的前提
                    // （缺这两个会导致 PlaybackStateCompat position=-1、active item id=-1）
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_MEDIA_ITEMS_METADATA
                )
                .build()

            // 当前播放歌曲的 duration 优先走真实引擎 getDuration()（DAC/Oboe），
            // 因 MediaStore 扫描的 Song.duration 可能为 0，导致进度条不显示
            val liveDuration = this@MusicService.getDuration()
            val playlist = songs.map { s ->
                val dur = if (s.id == song?.id && liveDuration > 0) liveDuration else s.duration
                SimpleBasePlayer.MediaItemData.Builder(s.id)
                    .setMediaItem(buildMediaItem(s))
                    .setDurationUs((dur.coerceAtLeast(0L)) * 1000L)
                    .build()
            }

            val safeIndex = MusicService.currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
            return State.Builder()
                .setPlaybackState(playbackState)
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setAvailableCommands(commands)
                .setShuffleModeEnabled(MusicService.isShuffleMode)
                .setRepeatMode(MusicService.repeatMode)
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(safeIndex)
                .setContentPositionMs(posSupplier)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) this@MusicService.resume()
            else this@MusicService.pause()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int
        ): ListenableFuture<*> {
            val songs = this@MusicService.servicePlaylist.ifEmpty { SongRepository.getSongs() }
            if (mediaItemIndex != MusicService.currentIndex && mediaItemIndex in songs.indices) {
                this@MusicService.playSong(mediaItemIndex)
            } else {
                this@MusicService.seekTo(positionMs)
            }
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetShuffleModeEnabled(enabled: Boolean): ListenableFuture<*> {
            this@MusicService.setShuffleMode(enabled)
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            this@MusicService.setRepeatMode(repeatMode)
            invalidateState()
            return Futures.immediateVoidFuture()
        }
    }

    private fun buildMediaItem(song: Song): MediaItem {
        val artworkUri = if (song.albumArtUri.isNotEmpty()) android.net.Uri.parse(song.albumArtUri) else null
        val displayArtist = if (song.artist.isNullOrBlank() || song.artist == "Unknown Artist") getString(R.string.app_name_moto) else song.artist
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.path)
            .setMimeType(getMimeType(song.format))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(displayArtist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
    }

    private fun refreshOboeModeCache() {
        val mode = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("audio_output", "AAudio (Direct)") ?: "AAudio (Direct)"
        val loaded = OboeDirectPlayer.nativeLibLoaded
        cachedOboeMode = (mode == "Oboe Exclusive" || mode == "Oboe" || mode == "AAudio (Direct)") && loaded
        oboeModeCacheValid = true
    }

    internal fun isOboeDirectMode(): Boolean {
        if (!oboeModeCacheValid) refreshOboeModeCache()
        return cachedOboeMode
    }
    // [V8.x] Album art LruCache �� avoids repeated disk I/O on every notification refresh
    private val coverCache = android.util.LruCache<String, android.graphics.Bitmap>(4)
    private fun loadCoverAsync(uri: String, onLoaded: (android.graphics.Bitmap?) -> Unit) {
        try {
            val resolvedUri = android.net.Uri.parse(uri)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(resolvedUri)?.use { s -> android.graphics.BitmapFactory.decodeStream(s, null, options) }
            val scale = maxOf(options.outWidth, options.outHeight) / 512
            val options2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = if (scale > 1) scale else 1 }
            val bitmap = contentResolver.openInputStream(resolvedUri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, options2)
            }
            if (bitmap != null) {
                try { coverCache.put(uri, bitmap) } catch (_: Exception) { }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onLoaded(bitmap) }
        } catch (_: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onLoaded(null) }
        }
    }

    /** ??V7.XXEqualizeronAudioSessionIdChanged */
    

    // 
    /** ??V7.XX audioSessionId??Equalizer*/
    /** [Phase C] ExoPlayer 已删除，无 AudioTrack session（Oboe 模式走 DSP） */
    fun getAudioSessionId(): Int = 0

    // 
    private var servicePlaylist: List<Song> = emptyList()
    private var _originalPlaylist: List<Song> = emptyList()  // [V3.3.2]

    // 
    private var oboeFailureCount = 0
    private val OBOE_MAX_FAILURES = 3

    //  settings SharedPreferences Output Mode?��
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** ??v4.77��(?�� BottomSheet)*/
    fun getServicePlaylist(): List<Song> = servicePlaylist

    // FFT 
    private var fftCallback: ((ByteArray) -> Unit)? = null

    // [v7.122] Auto-map system standby bucket to idle_level
    private var standbyBucketReceiver: BroadcastReceiver? = null

    // 
    

    /**
     * ??Steven v1.6��
     *  Fragment/Adapter ,
     * ?��,
     */
    interface OnCurrentSongChangedListener {
        fun onCurrentSongChanged(song: Song?)
    }

    interface OnPlayStateChangedListener {
        fun onPlayStateChanged(isPlaying: Boolean)
    }

    /**
     * ??v5.58 format Back MIME type
     * ExoPlayer  MIME type 
     */
    private fun getMimeType(format: String): String {
        return when (format.uppercase()) {
            "FLAC" -> "audio/flac"
            "OPUS" -> "audio/ogg"    // Opus ?? Ogg 
            "OGG" -> "audio/ogg"
            "WAV" -> "audio/wav"
            "AAC" -> "audio/aac"
            "M4A" -> "audio/mp4"
            "MP3" -> "audio/mpeg"
            else -> "audio/*"        // 
        }
    }

    companion object {
        // 
        var oboeFlowTrace: String = "��On?"
            private set

        // 
        private val _songChangedFlow = kotlinx.coroutines.flow.MutableStateFlow<Song?>(null)
        val songChangedFlow: kotlinx.coroutines.flow.StateFlow<Song?> = _songChangedFlow

        private val _themeColorFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
        val themeColorFlow: kotlinx.coroutines.flow.StateFlow<Int> = _themeColorFlow

        // [Phase 1] shuffle/repeat 单向权威流（卡片改动 → 推送 UI，与 songChangedFlow 同机制）
        private val _shuffleModeFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val shuffleModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _shuffleModeFlow

        private val _repeatModeFlow = kotlinx.coroutines.flow.MutableStateFlow(Player.REPEAT_MODE_OFF)
        val repeatModeFlow: kotlinx.coroutines.flow.StateFlow<Int> = _repeatModeFlow

        /** ��? -  handler.post  */
        private val songChangedListeners = mutableListOf<OnCurrentSongChangedListener>()
    private val playStateChangedListeners = mutableListOf<OnPlayStateChangedListener>()

        fun addSongChangedListener(listener: OnCurrentSongChangedListener) {
            synchronized(songChangedListeners) {
                if (!songChangedListeners.contains(listener)) {
                    songChangedListeners.add(listener)
                }
            }
        }

        fun removeSongChangedListener(listener: OnCurrentSongChangedListener) {
            synchronized(songChangedListeners) {
                songChangedListeners.remove(listener)
            }
        }

        /** ����?() */
        private fun notifySongChanged(song: Song?) {
            com.sdw.music.player.FileLog.log("SVC", "notifySongChanged: ${song?.title} id=${song?.id}")
            _songChangedFlow.value = song  // 
            // [Phase 1] 同步刷新 MediaSession 状态机（歌名/封面/时长/播放列表变化）
            try { instance?.musicPlayerState?.refresh() } catch (_: Exception) {}
            // Save SharedPreferences
            if (song != null) {
                savePlaybackState()
                // "最近"
                SongRepository.recordPlayed(song.id)
                // Cover download is on-demand only (menu → Download Cover), not triggered on every track switch
            }
            synchronized(songChangedListeners) {
                songChangedListeners.forEach { listener ->
                    try { listener.onCurrentSongChanged(song) } catch (_: Exception) {}
                }
            }
            // 
            try { MusicWidgetProvider.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            try { MusicWidgetProvider3x2.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
    
            // [V8.x] DACsongChangednotification
            instance?.updateNotification()
        }

        fun addPlayStateChangedListener(listener: OnPlayStateChangedListener) {
            synchronized(playStateChangedListeners) {
                if (!playStateChangedListeners.contains(listener)) {
                    playStateChangedListeners.add(listener)
                }
            }
        }

        fun removePlayStateChangedListener(listener: OnPlayStateChangedListener) {
            synchronized(playStateChangedListeners) {
                playStateChangedListeners.remove(listener)
            }
        }

        /** ����(/) */
        @Volatile private var stoppedByIdlePolicy = false
        @Volatile private var manualPause = false

        private fun notifyPlayStateChanged(isPlaying: Boolean) {
            // [v7.113] update last known state for widget query (handles Oboe JNI lag)
            lastKnownPlayingState = isPlaying
            // save before notifying so listeners see consistent state
            savePlaybackState()
            // [Phase 1] 同步刷新 MediaSession 状态机（SimpleBasePlayer 投影）
            try { instance?.musicPlayerState?.refresh() } catch (_: Exception) {}
            synchronized(playStateChangedListeners) {
                playStateChangedListeners.forEach { listener ->
                    try { listener.onPlayStateChanged(isPlaying) } catch (_: Exception) {}
                }
            }
            // update widgets
            try { MusicWidgetProvider.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            try { MusicWidgetProvider3x2.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            // [v7.121] delay stop foreground when paused; cancel if resumed
            val inst = instance ?: return
            inst.stopDelayRunnable?.let { inst.handler.removeCallbacks(it) }
            val r = Runnable {
                val i = instance ?: return@Runnable
                if (!i.isPlaying()) {
                    Log.d(inst.TAG, "Idle timeout reached, stopping foreground service")
                    i.stopForeground(STOP_FOREGROUND_REMOVE)
                    i.stopSelf()
                    stoppedByIdlePolicy = true
                }
            }
            inst.stopDelayRunnable = r
            // Manual pause: long idle (30 min). Auto-stop (song end): short idle from prefs.
            val isManual = manualPause
            val idleMs = if (isManual) {
                1_800_000L  // 30 min for manual pause
            } else {
                when (inst.getSharedPreferences("sdw_music_prefs", MODE_PRIVATE).getString("idle_level", "频繁")) {
                    "常用" -> 1_800_000L  // 30 min
                    "频繁" -> 300_000L       // 5 min
                    "偶尔" -> 3_000L             // 3 sec
                    "受限" -> 0L            // immediate
                    else -> 300_000L
                }
            }
            if (idleMs == 0L) {
                Log.d(inst.TAG, "Idle level Restricted, stopping immediately")
                inst.stopForeground(STOP_FOREGROUND_REMOVE)
                stoppedByIdlePolicy = true
                inst.stopSelf()
                Log.d(inst.TAG, "stopSelf() called after Restricted idle policy")
            } else {
                inst.handler.postDelayed(r, idleMs)
            }
        }
        const val CHANNEL_ID = "music_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CLOSE = "ACTION_CLOSE"
        const val ACTION_SHUFFLE = "com.sdw.music.player.ACTION_SHUFFLE"
        const val ACTION_PREV = "com.sdw.music.player.ACTION_PREV"
        const val ACTION_NEXT = "com.sdw.music.player.ACTION_NEXT"
        const val ACTION_PLAY_PAUSE = "com.sdw.music.player.ACTION_PLAY_PAUSE"
        private const val PREFS_PLAYBACK = "playback_state"
        private const val KEY_SONG_ID = "last_song_id"
        private const val KEY_SONG_PATH = "last_song_path"
        private const val KEY_SONG_TITLE = "last_song_title"
        private const val KEY_SONG_ARTIST = "last_song_artist"
        private const val KEY_ALBUM_ART_URI = "last_album_art_uri"
        private const val KEY_POSITION = "last_position_ms"
        private const val KEY_WAS_PLAYING = "was_playing"

        /** Save SharedPreferences*/
        fun savePlaybackState() {
            val ctx = instance ?: return
            val song = currentSong ?: return
            val prefs = ctx.getSharedPreferences(PREFS_PLAYBACK, MODE_PRIVATE)
            val pos = try {
                if (instance?.isOboeDirectMode() == true) {
                    instance?.oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
                } else {
                    0L
                }
            } catch (_: Exception) { 0L }
            val isPlaying = try {
                when {
                    instance?.isOboeDirectMode() == true -> instance?.oboePlayWhenReady ?: false
                    else -> false
                }
            } catch (_: Exception) { false }
            prefs.edit()
                .putLong(KEY_SONG_ID, song.id)
                .putString(KEY_SONG_PATH, song.path)
                .putString(KEY_SONG_TITLE, song.title)
                .putString(KEY_SONG_ARTIST, song.artist)
                .putString(KEY_ALBUM_ART_URI, song.albumArtUri.takeIf { it.isNotEmpty() } ?: "")
                .putLong(KEY_POSITION, pos)
                .putBoolean(KEY_WAS_PLAYING, isPlaying)
                .apply()
            android.util.Log.d("MusicService", "Playback state saved: id=${song.id}, pos=$pos, playing=$isPlaying")
        }
        var currentSong: Song? = null
            private set
        var currentIndex: Int = 0
            private set
        var isShuffleMode: Boolean = false
            private set
        var repeatMode: Int = Player.REPEAT_MODE_OFF
            private set

        // 
        var playlistSource: String = "All Songs"
            private set

        // 
        var instance: MusicService? = null
            private set

        // 
        var themeColor: Int = 0
            set(value) {
                field = value
                _themeColorFlow.value = value  // 
            }


        // [v7.113] ��Ƶ�������?
        private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
        private var hasAudioFocus = false

        fun requestAudioFocusIfNeeded(ctx: android.content.Context) {
            // Oboe Exclusive mode doesn't need AudioFocus (AAudio manages it independently);
            // requesting Exclusive AAudio triggers spurious focus loss -> don't request.
            if (instance?.isOboeDirectMode() == true) return
            if (hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audioFocusListener == null) {
                audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasAudioFocus = false
                            val svc = instance
                            if (svc != null && !svc.isOboeDirectMode()) {
                                svc.pause()
                                android.util.Log.d("MusicService", "Audio focus lost, pausing (ExoPlayer mode)")
                            } else {
                                // Oboe mode: don't pause (Oboe streams bypass AudioFocus)
                                svc?.wasPlayingBeforeFocusLoss = true
                                android.util.Log.d("MusicService", "Audio focus lost, ignoring (Oboe mode)")
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            val svc = instance
                            if (svc?.isPlaying() != true && svc?.wasPlayingBeforeFocusLoss == true) {
                                svc?.resume()
                                svc?.wasPlayingBeforeFocusLoss = false
                            }
                            android.util.Log.d("MusicService", "Audio focus regained")
                        }
                    }
                }
            }
            val result = am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            android.util.Log.d("MusicService", "requestAudioFocus: ${if (hasAudioFocus) "granted" else "denied"}")
        }

        fun abandonAudioFocus(ctx: android.content.Context) {
            if (instance?.isOboeDirectMode() == true) return  // Oboe mode: never requested focus
            if (!hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioFocusListener?.let { am.abandonAudioFocus(it) }
            hasAudioFocus = false
            android.util.Log.d("MusicService", "Audio focus abandoned")
        }

        // [v7.113] ��¼���㶪ʧǰ�Ĳ���״̬
        var wasPlayingBeforeFocusLoss = false
            private set

        // [v7.113] ���µĲ���״̬����Widget��ѯ������Oboe JNI�ӳ٣�
        var lastKnownPlayingState: Boolean = false
            private set
    }

    // 
    // Media3 ��?, updateNotification()
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // 
        if (isDestroyed) return
        Log.d(TAG, "onUpdateNotification called, currentSong=${currentSong?.title}, startInForeground=$startInForegroundRequired")
        // ��
        if (currentSong != null) {
            updateNotification()
            Log.d(TAG, "Custom notification updated via onUpdateNotification")
        }
        //  super, Media3 ��?
    }

    // [v7.113] ���㶪ʧǰ�Ƿ��ڲ���
    private var wasPlayingBeforeFocusLoss = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Service starting")

        // [v7.xxx] Use ProcessLifecycleOwner to track app foreground/background
        // It initializes during Application.onCreate (before any Activity/Service),
        // unlike ActivityLifecycleCallbacks which fire too late if Service starts after Activity resumes
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(visualizerReleaseTask)
                isAppForeground = true
                Log.d(TAG, "App foreground (ProcessLifecycle), re-setup Visualizer")
                if (fftCallback != null && !visualizerManager.isReady()) {
                    handler.postDelayed({ visualizerManager.setup() }, 300)
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                isAppForeground = false
                Log.d(TAG, "App background (ProcessLifecycle), scheduling Visualizer release in 2s")
                handler.postDelayed(visualizerReleaseTask, 2000)
            }
        })

        // [V3.3.7] ����ʱ��ͣ FFT ���ӻ������� CPU ���ģ���Ӱ�� DAC ���ţ�
        registerScreenOffReceiver()
        registerPhoneStateListener()

        createNotificationChannel()

        // 
        val prefs = getSharedPreferences("MusicPlayer", MODE_PRIVATE)
        isShuffleMode = prefs.getBoolean("shuffle_mode", false)
        repeatMode = prefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF)
        Log.d(TAG, "Restored shuffle mode: $isShuffleMode, repeat=$repeatMode")

        // :,
        // 
        // First MediaSession: Shuffle + Close custom actions only.
        // System handles Play/Pause/Prev/Next via ForwardingPlayer standard commands.
        mediaSession = MediaSession.Builder(this, musicPlayerState)
            .setCallback(object : MediaSession.Callback {
                // no custom play/pause/prev/next — let system default buttons work
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                        
                        
                        
                        .build()


                    val playerCommands = musicPlayerState.availableCommands
























                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setCustomLayout(ImmutableList.of(
                            CommandButton.Builder()
                                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build(),
                            CommandButton.Builder()
                                .setDisplayName(getString(R.string.notif_close))
                                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build()
                        ))  // custom layout: Shuffle + Close only
                        // 3:Previous/
                        // [V3.3.2] Use system default MediaNotification buttons - removed custom layout to avoid duplicates
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_CLOSE -> {
                            Log.d(TAG, "Close button pressed, performing hard exit")
                            performHardExit()
                        }














                        ACTION_SHUFFLE -> {
                            toggleShuffle()
                            //  session( mediaSession,Refresh customLayout
                            val shuffleButton = CommandButton.Builder()
                                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build()
                            val closeButton = CommandButton.Builder()
                                .setDisplayName(getString(R.string.notif_close))
                                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build()
                            session.setCustomLayout(ImmutableList.of(shuffleButton, closeButton))
                            // Refresh shuffle 
                            updateNotification()
                            Log.d(TAG, "Shuffle toggled from media session: isShuffleMode=$isShuffleMode")
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setSessionActivity(createPendingIntent())
            .build()

        instance = this
        refreshOboeModeCache()  // [V8.x] Init DAC mode cache on startup
        Log.d(TAG, "MediaSession created")

        // USB DAC Exclusive: init if enabled
        if (isUsbExclusiveMode()) {
            UsbDacManager.init(this)
            Log.d(TAG, "USB DAC Exclusive mode initialized")
        }

        // [V8.x] Register USB DAC hotplug callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.registerAudioDeviceCallback(usbDacCallback, handler)
            Log.d(TAG, "AudioDeviceCallback registered for USB DAC detection")
        }

        // [v7.113] �״�������Ƶ���㣨����ʱ����������
        requestAudioFocusIfNeeded(this)

        // 
        volumeGuard.register()

        // Output Mode?��apply 
        val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            // [Phase C] audio_output 切换已移除（ExoPlayer 删除，固定 AAudio/Oboe 直连）
            if (key == "usb_exclusive") {
                val enabled = sharedPrefs.getBoolean("usb_exclusive", false)
                handler.post {
                    if (enabled) {
                        // [V4.0.2] Seamless switch: save position, stop ExoPlayer, play via USB DAC
                        val savedPos = oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
                        val savedSong = currentSong
                        val savedIdx = currentIndex
                        releaseUsbDacController()
                        UsbDacManager.stopAndRelease()
                        UsbDacManager.init(this@MusicService)
                        tryClaimUsbDac()
                        if (savedSong != null && usbDacController != null && UsbDacManager.isClaimed()) {
                            handler.postDelayed({
                                playSong(savedIdx)
                                usbDacController?.seekTo(savedPos)
                                Log.w(TAG, "Switched to USB DAC mode, restored ${savedSong.title} @ ${savedPos}ms")
                            }, 200)  // wait for claim to stabilize
                        }
                    } else {
                        // [V4.0.2] Seamless switch: save position, release USB DAC, restore via AAudio/Oboe
                        // 非 DAC 模式 = 纯 AAudio/Oboe 直连，不恢复到 ExoPlayer 发声
                        val savedPos = usbDacController?.audiblePositionMs ?: 0L
                        val savedSong = currentSong
                        val savedIdx = currentIndex
                        releaseUsbDacController()
                        UsbDacManager.stopAndRelease()
                        if (savedSong != null) {
                            val allSongs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
                            playSongOboeDirect(savedIdx, allSongs)
                            if (savedPos > 1000) {
                                handler.postDelayed({ oboeDirectPlayer?.seekTo(savedPos) }, 400)
                            }
                            Log.w(TAG, "Switched to AAudio/Oboe mode, restored ${savedSong.title} @ ${savedPos}ms")
                        }
                    }
                }
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsPrefsListener)
    }

    // 
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [v7.122] If service was stopped by idle policy (Restricted), don't resurface
        // unless user explicitly tries to play (e.g. from notification PLAY button)
        if (stoppedByIdlePolicy) {
            if (intent?.action == "com.sdw.music.player.PLAY") {
                stoppedByIdlePolicy = false
                Log.d(TAG, "onStartCommand: user wants to play, clearing idle stop flag")
            } else {
                Log.d(TAG, "onStartCommand: was stopped by idle policy, ignoring restart")
                return START_NOT_STICKY
            }
        }

        // 
        // ��
        if (currentSong == null) {
            val emptyNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name_moto))
                .setContentText("Preparing...")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID, emptyNotification)
            Log.d(TAG, "Started foreground with empty notification")
        }

        when (intent?.action) {
            "com.sdw.music.player.ACTION_SHUFFLE" -> {
                toggleShuffle()
                updateNotification()  // Refresh
                Log.d(TAG, "Shuffle toggled from notification: isShuffleMode=$isShuffleMode")
            }
            "com.sdw.music.player.PREV" -> playPrevious()
            "com.sdw.music.player.NEXT" -> playNext()
            "com.sdw.music.player.PLAY" -> resume()
            "com.sdw.music.player.PAUSE" -> pause()
        }
        return START_STICKY
    }

    // 
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * y?��On MainActivity (SINGLE_TOP )
     *  TaskStackBuilder ?? ?? Service ,
     *  Activity  ?? ViewModel  ?? connect()  ?? Playing?? + 
     */
    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // 
    //  SongRepository ��?(Back?Folders)
    /**
     * ??Steven v1.51 Service Playlists +  ExoPlayer
     * keepCurrentPosition=true ( onResume ?��??��?)
     */
    /**
     * SettingsPlaylists??
     * @param songs ��?
     * @param updateGlobal  SongRepository ��?(��?=true,Folders=false)
     */
    fun setSongs(songs: List<Song>, updateGlobal: Boolean = true, source: String = "All Songs") {
        Log.d(TAG, "setSongs: ${songs.size} songs ?? servicePlaylist, source=$source, updateGlobal=$updateGlobal")
        _originalPlaylist = songs
        servicePlaylist = songs
        playlistSource = source
        if (updateGlobal) {
            SongRepository.setSongs(songs)
        }
    }

    // [V8.1] sync servicePlaylist with SongRepository after delete
    fun refreshServicePlaylist() {
        servicePlaylist = SongRepository.getSongs()
        playlistSource = "All Songs"
    }

    /** ͬ�������嵥�� servicePlaylist�������ڴ��ֶΣ��������� I/O����ȷ������ŵ� playSong ��ͬһ���б�
     *  ���� USB/Oboe ģʽ�� playSong(index) �ᰴ�� servicePlaylist ��λ�Ŵ�� */
    fun setServicePlaylist(songs: List<Song>, source: String = "All Songs") {
        _originalPlaylist = songs  // [V3.3.2]
        servicePlaylist = songs
        playlistSource = source
    }

    fun playSong(index: Int) {
        // [v7.113] ��ʼ����ʱ������Ƶ����
        requestAudioFocusIfNeeded(this)

        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        Log.d(TAG, "playSong: index=$index, songsCount=${songs.size}, playlistSource=$playlistSource")

        if (index < 0 || index >= songs.size) {
            Log.e(TAG, "Invalid index: $index")
            return
        }

        // [V6.1] USB DAC routing: 3 paths (prioritized)
        // Path 1 — USB Host Exclusive (Bit-Perfect): known-good DACs like TTGK 33C0
        // Path 2 — Oboe System Route: unknown/problem DACs → Oboe without setDeviceId,
        //          Android auto-routes to USB_HEADSET via kernel driver (Resonāda-style)
        // Path 3 — ExoPlayer (SRC fallback): no DAC, Bluetooth, or 44.1k-family on broken DACs
        if (isUsbExclusiveMode()) {
            dacPlayGeneration++  // [V4.0.1] invalidate stale onCompletion

            // Detect DAC and decide routing
            UsbDacManager.findDacs()
            val device = UsbDacManager.getDacDevice()
            DebugLog.add(TAG, "playSong[$index]: usbExclusive=true, device=${device?.productId?.toString(16)}")

            if (device == null) {
                DebugLog.add(TAG, "playSong[$index]: no DAC, fallback Exo")
                playSongFallbackExo(index, songs)
                return
            }

            val dacProfile = DacProfile.find(device.vendorId, device.productId)

            // Path 2: unknown/problem DAC → Oboe system-route (with BIT_PERFECT API on Android 14+)
            if (dacProfile.useSystemRoute) {
                DebugLog.add(TAG, "playSong[$index]: DAC ${dacProfile.name} → Oboe system-route")
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                UsbDacManager.dumpDacInfo(device, audioManager)
                val srcRate = UsbDacManager.getSourceSampleRate(songs[index])
                val is44k = srcRate in setOf(44100, 88200, 176400, 352800)
                if (dacProfile.lacks44k1Clock && is44k) {
                    DebugLog.add(TAG, "playSong[$index]: ${dacProfile.name} lacks 44.1k clock, ExoPlayer SRC")
                    playSongFallbackExo(index, songs)
                    return
                }
                // [v6.2] Try BIT_PERFECT API (Android 14+) — tells framework to bypass
                // mixer/SRC/DSP for this USB DAC, using kernel usb_quirks for tolerance
                if (dacProfile.tryBitPerfectApi && android.os.Build.VERSION.SDK_INT >= 34) {
                    try {
                        val usbDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        val usbInfo = usbDevices.find {
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                        }
                        if (usbInfo != null) {
                            val af = android.media.AudioFormat.Builder()
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                                .setSampleRate(srcRate.coerceAtLeast(44100))
                                .build()
                            val audioAttrs = android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                            val attrs = android.media.AudioMixerAttributes.Builder(af).build()
                            audioManager.setPreferredMixerAttributes(audioAttrs, usbInfo, attrs)
                            DebugLog.add(TAG, "playSong[$index]: BIT_PERFECT API OK (${srcRate}Hz 16bit)")
                        } else {
                            DebugLog.add(TAG, "playSong[$index]: no USB AudioDeviceInfo for BIT_PERFECT, fallback Oboe")
                        }
                    } catch (e: Exception) {
                        DebugLog.add(TAG, "playSong[$index]: BIT_PERFECT API failed: ${e.javaClass.simpleName}, fallback Oboe")
                    }
                }
                // Route to Oboe system-path (no setDeviceId — Android auto-routes to USB)
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                refreshOboeModeCache()
                playSongOboeDirect(index, songs)
                notifySongChanged(songs[index])
                return
            }

            // Path 1: known-good DAC → USB Host Exclusive Bit-Perfect
            val dacClaimed = UsbDacManager.isClaimed()
            val dacRunning = UsbDacManager.isStreaming()
            var dacStreaming = dacRunning
            DebugLog.add(TAG, "playSong[$index]: ${dacProfile.name} Bit-Perfect, claimed=$dacClaimed running=$dacRunning")

            // First play: claim DAC if not yet claimed
            if (!dacClaimed) {
                val song = songs[index]
                val srcRate = UsbDacManager.getSourceSampleRate(song)
                val is44kFamily = srcRate in setOf(44100, 88200, 176400, 352800)
                if (dacProfile.lacks44k1Clock && is44kFamily) {
                    DebugLog.add(TAG, "playSong[$index]: DAC ${dacProfile.name} lacks 44.1k clock, route to ExoPlayer")
                    playSongFallbackExo(index, songs)
                    return
                }
                DebugLog.add(TAG, "playSong[$index]: claiming DAC sr=$srcRate bits=${dacProfile.wireBits}")
                if (!UsbDacManager.claimAndStart(device, srcRate, 2, dacProfile.wireBits)) {
                    DebugLog.add(TAG, "playSong[$index]: claim FAIL, fallback Oboe system-route (Salt Player style)")
                    // Salt Player fallback: Oboe without setDeviceId, kernel USB driver auto-routes
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                    refreshOboeModeCache()
                    playSongOboeDirect(index, songs)
                    notifySongChanged(songs[index])
                    return
                }
                DebugLog.add(TAG, "playSong[$index]: DAC claimed, waiting for controller prebuffer")
            } else if (!dacRunning) {
                // [fix] claim held but stream stopped (EOS auto-next): restart stream
                // WITHOUT re-claim. pauseStream() already stopped streamLoop after EOS drain;
                // re-claim would hit EBUSY (old connection still open). Let
                // play(streamAlreadyRunning=false) reset ring + startStreaming().
                DebugLog.add(TAG, "playSong[$index]: claim held, stream stopped (EOS) — restart stream, no reclaim")
                dacStreaming = false
            } else {
                // Subsequent plays: check if sample rate changed vs current DAC stream
                val song = songs[index]
                val oldRate = UsbDacManager.activeSampleRate
                val newRate = UsbDacManager.getSourceSampleRate(song)
                val is44kFamily = newRate in setOf(44100, 88200, 176400, 352800)
                if (dacProfile.lacks44k1Clock && is44kFamily) {
                    DebugLog.add(TAG, "playSong[$index]: DAC ${dacProfile.name} lacks 44.1k clock, route to ExoPlayer")
                    releaseUsbDacController()
                    UsbDacManager.stopAndRelease()
                    playSongFallbackExo(index, songs)
                    return
                }
                val rateChanged = (oldRate > 0 && newRate > 0 && newRate != oldRate)
                if (rateChanged) {
                    // [v6.0.2] Cross-rate switch: full release + reclaim for clean clock re-negotiation
                    DebugLog.add(TAG, "playSong[$index]: cross-rate ${oldRate}→${newRate}, full release+reclaim")
                    releaseUsbDacController()
                    UsbDacManager.stopAndRelease()
                    UsbDacManager.findDacs()
                    val device = UsbDacManager.getDacDevice()
                    val bits = DacProfile.wireBitsFor(device?.vendorId ?: 0, device?.productId ?: 0)
                    if (device != null && UsbDacManager.claimAndStart(device, newRate, 2, bits)) {
                        DebugLog.add(TAG, "playSong[$index]: cross-rate reclaim OK")
                        // [fix] fresh claim = stream NOT running yet; must let play() call startStreaming
                        dacStreaming = false
                    } else {
                        DebugLog.add(TAG, "playSong[$index]: cross-rate reclaim FAIL, fallback Oboe system-route (Salt Player style)")
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                        refreshOboeModeCache()
                        playSongOboeDirect(index, songs)
                        notifySongChanged(songs[index])
                        return
                    }
                } else {
                    // [v6.0.12] keep-claim same-rate: stop flacMonitor only, don't pause/reset native stream
                    usbDacController?.stopMonitor()
                    usbDacController = null
                    DebugLog.v(TAG, "playSong[$index]: DAC already streaming, monitor killed for instant switch")
                }
            }

            val currentSong = songs[index]
            val filePath = currentSong.filePath.ifEmpty { currentSong.path }
            val actualPath = if (filePath.startsWith("content://")) {
                resolveContentUriToPath(filePath)
            } else filePath
            if (actualPath != null) {
                acquireDacWakeLock()
                val controller = UsbDacPlaybackController(
                    onCompletion = {
                        DebugLog.add(TAG, "USB DAC: song complete, playing next")
                        val capturedGen = dacPlayGeneration
                        handler.post {
                            if (dacPlayGeneration == capturedGen) playNext()
                            else android.util.Log.w(TAG, "USB DAC: stale onCompletion ignored (gen=$capturedGen, current=$dacPlayGeneration)")
                        }
                    },
                    onError = { msg ->
                        DebugLog.add(TAG, "USB DAC error: $msg")
                        handler.post { playSongFallbackExo(index, songs) }
                    }
                )
                // [V3.3.4] flac/wav: open() reads the true rate itself (STREAMINFO/RIFF);
                // skip the redundant MediaExtractor probe (hundreds of ms per track switch)
                val srcRate = if (actualPath.endsWith(".flac", true) || actualPath.endsWith(".wav", true)) 0
                              else UsbDacManager.getSourceSampleRate(currentSong)
                DebugLog.add(TAG, "USB DAC: opening ${currentSong.title} srcRate=$srcRate (0=self-detect)")
                if (controller.open(actualPath, srcRate, dacChannels = 2)) {
                    usbDacController = controller
                    handler.removeCallbacks(dacHealthRunnable)
                    handler.postDelayed(dacHealthRunnable, 5000)
            val dacProfile = DacProfile.find(UsbDacManager.getDacDevice()?.vendorId ?: 0, UsbDacManager.getDacDevice()?.productId ?: 0)
            controller.playbackWireBits = dacProfile.wireBits
                    controller.play(streamAlreadyRunning = dacStreaming)
                    // Apply system media volume to native DAC (USB bypasses Android mixer)
                    val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                    if (am != null) {
                        val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                  am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                        UsbDacManager.setVolume(pct)
                    }
                    // Restore MSEB on DAC path (reclaim/切歌后 native EQ 会丢失，重设一次)
                    if (MsebCalculator.isEnabled(this@MusicService)) {
                        val msebParams = MsebCalculator.load(this@MusicService)
                        if (!msebParams.isFlat) {
                            UsbDacManager.setDspEnabled(true)
                            UsbDacManager.setDspEq5Band(
                                MsebCalculator.calculateGains(msebParams),
                                MsebCalculator.BAND_FREQS
                            )
                            dspEqEnabled = true
                        }
                    }
                    currentSong.let { song -> MusicService.currentSong = song; currentIndex = index }
                    // ��V3.2.8��DAC ��֧�� return ���ߺ��� V8.1 playlist ͬ����
                    // MediaSession �ﻹ�Ǿ� MediaItem �� ϵͳý�忨Ƭ������ͬ�������ﲹ
                    syncSessionMediaItem(index, songs)
                    notifyPlayStateChanged(true)
                    updateNotification()
                    notifySongChanged(songs[index])
                    return
                } else { DebugLog.add(TAG, "USB DAC: controller.open FAIL, fallback Exo") }
            }
        }

        // 
        // Always Oboe/AAudio Direct - no ExoPlayer fallback
        playSongOboeDirect(index, songs)
        notifySongChanged(songs[index])
    }

    fun playSongById(songId: Long, allSongs: List<Song>) {
        val songs = servicePlaylist.ifEmpty { allSongs }

        // Playlists
        var index = songs.indexOfFirst { it.id == songId }

        if (index != -1) {
            // Playlists??,
            Log.d(TAG, "playSongById: found in current playlist at $index (source=$playlistSource)")
            playSong(index)
        } else {
            // Playlists(Folders?����?),?����?
            Log.d(TAG, "playSongById: not in current playlist, switching to full list")
            setSongs(allSongs, updateGlobal = true, source = "All Songs")
            index = allSongs.indexOfFirst { it.id == songId }
            if (index != -1) {
                playSong(index)
            } else {
                Log.e(TAG, "playSongById: song not found in any list! id=$songId")
            }
        }
    }

    // 
    // ==Splitted Oboe blocking ops to background thread=============================
    // V7.123: moved stop/open/play off UI thread so UI never freezes
    // ====================================================================

    private fun playSongOboeDirect(index: Int, songs: List<Song>) {
        val song = songs[index]
        currentIndex = index
        currentSong = song
        oboeFlowTrace = "1W9 Oboe starting: ${song.title}"
        Log.i(TAG, "playSongOboeDirect: ${song.title}")

        // Notify UI that we're loading
        handler.post {
            updateNotification()
        }

        oboeFlowTrace = "1F5 launching bg thread..."

        Thread {
            synchronized(oboeSwitchLock) {  // 串行化切歌，防并发 native 状态损坏
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

                // Stop previous (background thread - nativeStop() internally syncs via join+close+reset)
                oboeDirectPlayer?.stop()

                // Create new player
                val newPlayer = OboeDirectPlayer(this@MusicService)
                oboeDirectPlayer = newPlayer

                Log.i(TAG, "Oboe: Exclusive mode (bg thread)")
                oboeFlowTrace = "2F0E initializing (libLoaded=${OboeDirectPlayer.nativeLibLoaded})"

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
                newPlayer.setSampleRateNative(nativeSampleRate)
                // Per-stream routing: only this track goes to USB DAC, system sounds stay on speaker
                val usbOutDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .find { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }
                if (usbOutDevice != null) {
                    Log.i(TAG, "Oboe: per-stream USB routing via setDeviceId(${usbOutDevice.id})")
                    newPlayer.setOutputDevice(usbOutDevice.id)
                }
                newPlayer.resetClipStats()
                newPlayer.onCompletion = {
                    Log.i(TAG, "OboeDirect: song completed, playing next")
                    handler.post { playNext() }
                }
                newPlayer.onPlayStateChanged = { isPlaying ->
                    handler.post {
                        if (!isDestroyed) {
                            notifyPlayStateChanged(isPlaying)
                            updateNotification()
                        }
                    }
                }
                newPlayer.onError = { msg ->
                    oboeFlowTrace = "274C error: $msg, fallback to ExoPlayer"
                    Log.e(TAG, "OboeDirect error: $msg")
                    oboeFailureCount++
                    handler.post { playSongFallbackExo(index, songs) }
                }

                val filePath = song.filePath.ifEmpty { song.path }
                val actualPath = if (filePath.startsWith("content://")) {
                    resolveContentUriToPath(filePath)
                } else {
                    filePath
                }

                if (actualPath == null) {
                    oboeFlowTrace = "274C cannot resolve path, fallback to ExoPlayer"
                    Log.e(TAG, "Cannot resolve path for: $filePath")
                    handler.post { playSongFallbackExo(index, songs) }
                    return@Thread
                }

                oboeFlowTrace = "3F50C opening..."
                val opened = newPlayer.open(actualPath)
                Log.i(TAG, "oboeDirectPlayer.open() = $opened, actualPath=$actualPath")
                if (!opened) {
                    oboeFailureCount++
                    oboeFlowTrace = "274C open failed, fallback to ExoPlayer (failures=$oboeFailureCount)"
                    Log.e(TAG, "OboeDirect failed to open: $actualPath")
                    handler.post { playSongFallbackExo(index, songs) }
                    return@Thread
                }

                oboeFlowTrace = "4F3B5 playing..."
                var played = newPlayer.play()
                Log.i(TAG, "oboeDirectPlayer.play() = $played")
                if (!played) {
                    Thread.sleep(150)
                    played = newPlayer.play()
                    Log.i(TAG, "oboeDirectPlayer retry play() = $played")
                }
                if (!played) {
                    oboeFailureCount++
                    oboeFlowTrace = "274C play failed, fallback to ExoPlayer (failures=$oboeFailureCount)"
                    Log.e(TAG, "OboeDirect failed to play: $actualPath")
                    handler.post { playSongFallbackExo(index, songs) }
                    return@Thread
                }

                // Oboe succeeded, post UI updates back to main thread
                oboeFailureCount = 0
                oboeUsbGuardMs = System.currentTimeMillis() + 8000L  // [V8.1] block USB-DAC race for 8s
                oboeSuppressUsbRestart = true  // [V8.2] prevent Oboe restart loop after first song
                oboeFlowTrace = "2705 Oboe OK (mode=${newPlayer.getDspMode()?.displayName}, exclusive=${newPlayer.isExclusiveMode()})"

                handler.post {
                    currentSong = song
                    currentIndex = index
                    volumeGuard.resetMuteState()
                    oboeFailureCount = 0

                    val dspModeSp = getSharedPreferences("dsp_mode", MODE_PRIVATE)
                    val savedDspMode = dspModeSp.getInt("mode", -1)
                    setDspMode(savedDspMode)
                    Log.i(TAG, "DSP mode restored: ${when (savedDspMode) { -1 -> "OFF"; 1 -> "CAT_MODE"; else -> "STEVEN_SPECIAL" }}")
                    EqualizerManager.restoreSettings(this@MusicService)

                    // Restore MSEB if active (new OboeDirectPlayer resets native Biquad to zero)
                    if (MsebCalculator.isEnabled(this@MusicService)) {
                        val msebParams = MsebCalculator.load(this@MusicService)
                        if (!msebParams.isFlat) {
                            oboeDirectPlayer?.setDspEnabled(true)
                            oboeDirectPlayer?.setDspEq5Band(
                                MsebCalculator.calculateGains(msebParams),
                                MsebCalculator.BAND_FREQS
                            )
                            dspEqEnabled = true
                        }
                    }

                    // [V8.1] Always sync ExoPlayer playlist so ForwardingPlayer.getCurrentMediaItem()
                    // returns correct metadata (system notification / lock screen / car / Wear OS).
                    // In Oboe mode: update MediaItem without prepare() �?avoids CPU waste on
                    // parallel MediaCodec decoding since audio is driven by OboeDirectPlayer.


                    notifyPlayStateChanged(true)
                    notifySongChanged(song)
                    updateNotification()

                    handler.postDelayed({
                        if (fftCallback != null && !visualizerManager.isReady()) { visualizerManager.setup() }
                    }, 500)

                    val sampleRate = newPlayer.getSampleRate() ?: 0
                    val nativeRate = newPlayer.getSampleRateNative() ?: 0
                    val bitPerfect = sampleRate == nativeRate
                    val clipInfo = newPlayer.getClipDebugInfo() ?: ""
                    Log.i(TAG, "OboeDirect playing: ${song.title}, rate=${sampleRate}Hz, native=${nativeRate}Hz, bitPerfect=$bitPerfect, exclusive=${newPlayer.isExclusiveMode()}, dspMode=${newPlayer.getDspMode()?.displayName}, $clipInfo")
                }
            }
        }.start()
    }


    /** ??V7.17??Oboe  ExoPlayer () */
    /** [Phase C] ExoPlayer 已删除：fallback 统一停播（Oboe/AAudio 直连失败即停止） */
    private fun playSongFallbackExo(index: Int, songs: List<Song>) {
        releaseDacWakeLock()
        val song = songs[index]
        oboeDirectPlayer?.stop()
        currentSong = song
        currentIndex = index
        notifyPlayStateChanged(false)
        notifySongChanged(song)
        updateNotification()
    }


    /** ??v6.25??Apply DSP Biquad EQ in Oboe callback if "Steven Special" preset is active */
    private fun applyDspEqIfNeeded() {
        val eqPresetId = EqualizerManager.getCurrentPresetId(this)
        if (eqPresetId == "steven_special" && oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(
                enabled = true,
                highShelfFreq = 8000f, highShelfDb = 2.0f, highShelfQ = 0.707f,
                peakingFreq = 12000f, peakingDb = 2.0f, peakingQ = 2.0f,
                preGainDb = 0.0f
            )
            Log.i(TAG, "DSP EQ enabled: Steven Special (High-Shelf 8kHz/+2dB + Air 12kHz/+2dB)")
        } else {
            oboeDirectPlayer?.setDspEq(enabled = false)
        }
    }

    /** ??v6.29??DSP EQ On??( EQ preset) */
    fun setDspEqEnabled(enabled: Boolean) {
        dspEqEnabled = enabled
        // 
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEnabled(enabled)
            Log.i(TAG, "DSP EQ ${if (enabled) "enabled" else "disabled"} via toggle")
        }
    }

    /** DAC 独占模式是否真正激活（已 claim） */
    fun isDacActive(): Boolean = isUsbExclusiveMode() && UsbDacManager.isClaimed()

    /** 应用 MSEB 5 段 EQ — 根据当前播放模式路由到 Oboe 或 USB DAC 链路（共用同一套 Biquad） */
    fun applyMsebEq(gainsDb: FloatArray, freqsHz: FloatArray?) {
        dspEqEnabled = true
        if (isDacActive()) {
            UsbDacManager.setDspEnabled(true)
            UsbDacManager.setDspEq5Band(gainsDb, freqsHz)
        } else {
            oboeDirectPlayer?.setDspEnabled(true)
            oboeDirectPlayer?.setDspEq5Band(gainsDb, freqsHz)
        }
    }

    /** 关闭 MSEB / 重置为平坦 — 路由到当前播放模式对应的链路 */
    fun resetMsebEq() {
        dspEqEnabled = false
        if (isDacActive()) {
            UsbDacManager.resetDspEq5Band()
            UsbDacManager.setDspEnabled(false)
        } else {
            oboeDirectPlayer?.resetDspEq5Band()
            oboeDirectPlayer?.setDspEnabled(false)
        }
    }

    /** ??v6.29??DSP EQ ��,?? SharedPreferences  */
    fun setCustomDspEq(
        enabled: Boolean,
        highShelfFreq: Float, highShelfDb: Float, highShelfQ: Float,
        peakingFreq: Float, peakingDb: Float, peakingQ: Float,
        preGainDb: Float
    ) {
        dspEqEnabled = enabled
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(enabled, highShelfFreq, highShelfDb, highShelfQ,
                peakingFreq, peakingDb, peakingQ, preGainDb)
        }
        // 
        getSharedPreferences("dsp_eq", MODE_PRIVATE).edit().apply {
            putBoolean("enabled", enabled)
            putFloat("hs_freq", highShelfFreq)
            putFloat("hs_db", highShelfDb)
            putFloat("hs_q", highShelfQ)
            putFloat("pk_freq", peakingFreq)
            putFloat("pk_db", peakingDb)
            putFloat("pk_q", peakingQ)
            putFloat("pre_gain_db", preGainDb)
            apply()
        }
    }

    /** ??V7.04 SharedPreferences  DSP EQ */
    fun restoreCustomDspEq() {
        val sp = getSharedPreferences("dsp_eq", MODE_PRIVATE)
        val enabled = sp.getBoolean("enabled", false)
        if (!enabled) return
        val hsFreq = sp.getFloat("hs_freq", 8000f)
        val hsDb = sp.getFloat("hs_db", 0f)
        val hsQ = sp.getFloat("hs_q", 0.707f)
        val pkFreq = sp.getFloat("pk_freq", 12000f)
        val pkDb = sp.getFloat("pk_db", 0f)
        val pkQ = sp.getFloat("pk_q", 2.0f)
        val preGainDb = sp.getFloat("pre_gain_db", 0f)
        dspEqEnabled = true
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(true, hsFreq, hsDb, hsQ, pkFreq, pkDb, pkQ, preGainDb)
            Log.i(TAG, "DSP EQ restored from prefs: HS=${hsFreq}Hz/${hsDb}dB + Peak=${pkFreq}Hz/${pkDb}dB + preGain=${preGainDb}dB")
        }
    }

    /** ??V7.04Save?? DSP EQ (?? UI ) */
    fun getSavedDspEqParams(): android.os.Bundle? {
        val sp = getSharedPreferences("dsp_eq", MODE_PRIVATE)
        if (!sp.getBoolean("enabled", false)) return null
        return android.os.Bundle().apply {
            putFloat("hs_freq", sp.getFloat("hs_freq", 8000f))
            putFloat("hs_db", sp.getFloat("hs_db", 0f))
            putFloat("pk_freq", sp.getFloat("pk_freq", 12000f))
            putFloat("pk_db", sp.getFloat("pk_db", 0f))
            putFloat("pre_gain_db", sp.getFloat("pre_gain_db", 0f))
        }
    }

    fun isDspEqEnabled(): Boolean = dspEqEnabled

    /** ??V7.0 OboeDirectPlayer (?? UI  */
    fun getOboePlayer(): OboeDirectPlayer? = oboeDirectPlayer

    /** ??V7.0??Settings DSP Mode:-1 = OFF,0 = Steven Special,1 = Cat Mode */
    fun setDspMode(mode: Int) {
        // 
        getSharedPreferences("dsp_mode", MODE_PRIVATE).edit().putInt("mode", mode).apply()
        oboeDirectPlayer?.setDspMode(
            when (mode) {
                -1 -> OboeDirectPlayer.DspMode.OFF
                1 -> OboeDirectPlayer.DspMode.CAT_MODE
                else -> OboeDirectPlayer.DspMode.STEVEN_SPECIAL
            }
        )
        Log.i(TAG, "DSP mode set to: ${when (mode) { -1 -> "OFF"; 1 -> "CAT_MODE"; else -> "STEVEN_SPECIAL" }}")
    }

    /** ??V7.0 DSP Mode:-1 = OFF,0 = Steven Special,1 = Cat Mode */
    fun getDspMode(): Int {
        return try {
            val mode = oboeDirectPlayer?.getDspMode()
            when (mode) {
                OboeDirectPlayer.DspMode.OFF -> -1
                OboeDirectPlayer.DspMode.CAT_MODE -> 1
                else -> 0
            }
        } catch (_: Exception) { -1 }
    }

    // 

    /** ??V7.05 - softClip ,*/
    fun setNightMode(enabled: Boolean) {
        oboeDirectPlayer?.setNightMode(enabled)
    }

    fun isNightMode(): Boolean {
        return oboeDirectPlayer?.isNightMode() ?: false
    }

    fun toggleNightMode() {
        oboeDirectPlayer?.toggleNightMode()
    }

    private fun resolveContentUriToPath(uri: String): String? {
        // Try to get file path from content URI
        // For local files managed by MediaStore, the DATA column still contains the file path
        try {
            val contentUri = android.net.Uri.parse(uri)
            if (contentUri.scheme == "file") {
                return contentUri.path
            }
            // For content:// URIs, query MediaStore
            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
            val cursor = contentResolver.query(contentUri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val path = it.getString(0)
                    if (!path.isNullOrEmpty()) return path
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveContentUriToPath failed: ${e.message}")
        }
        return null
    }

    // [v6.0.17] Oboe pause: nativePause only stops AAudio, NDK decoder keeps dumping into ring buffer
    // causing overflow data loss resume silent. Fix: stop decoder+stream, resume via reopen+seek.
    private var oboePausePosMs: Long = 0L

    fun pause() {
        try {
            manualPause = true
            Log.d(TAG, "pause() called, usbDacController=${usbDacController != null}")
            if (usbDacController != null) {
                usbDacController?.pause()
                notifyPlayStateChanged(false)
            } else if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                oboePausePosMs = oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
                oboeDirectPlayer?.pause()
                oboePlayWhenReady = false
                notifyPlayStateChanged(false)
            } else {
                // [Phase C] 非 DAC/非 Oboe：无 ExoPlayer，直接置停止状态
                Log.d(TAG, "pause(): no active engine (isOboeDirectMode=${isOboeDirectMode()}, oboePlaying=${oboeDirectPlayer?.isPlaying})")
                oboePlayWhenReady = false
                notifyPlayStateChanged(false)
            }
            // [v7.113] ��ͣʱ�ͷ���Ƶ���㣬��ϵͳ��������
            // 焦点丢失触发的暂停不主动 abandon（保留焦点，等系统回发 GAIN 自动恢复）
                abandonAudioFocus(this)
            Log.d(TAG, "Paused")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "pause crash: ${e.message}", e)
        }
    }

    fun resume() {
        try {
            manualPause = false
            requestAudioFocusIfNeeded(this)
            if (usbDacController != null) {
                usbDacController?.resume()
                oboePlayWhenReady = true
                onOboeResumeSuccess()
            } else if (isOboeDirectMode()) {
                // Oboe 已在播放则幂等返回，避免落到 else 激活 ExoPlayer（双引擎发声根因）
                if (oboeDirectPlayer?.isPlaying == true) {
                    oboePlayWhenReady = true
                    return
                }
                // stop() released decoder; reopen file at saved position
                val song = currentSong
                if (song != null) {
                    val path = song.filePath.ifEmpty { song.path }
                    val actual = if (path.startsWith("content://")) resolveContentUriToPath(path) else path
                    if (actual != null && oboeDirectPlayer?.open(actual) == true) {
                        oboeDirectPlayer?.seekTo(oboePausePosMs)
                        oboeDirectPlayer?.play()
                        oboePlayWhenReady = true
                        onOboeResumeSuccess()
                        return
                    }
                }
                Log.w(TAG, "Oboe resume: reopen failed, NOT falling back to ExoPlayer (AAudio-only mode)")
                // [V8.x] 非 DAC 模式：纯 AAudio/Oboe 直连，绝不 resume ExoPlayer 发声
                oboePlayWhenReady = false
                notifyPlayStateChanged(false)
                updateNotification()
                return
            } else {
                // 非 DAC 且非 Oboe（理论上到不了这里，仅当音频输出切到非 AAudio 才可能）
                Log.w(TAG, "resume: not DAC/Oboe, no ExoPlayer fallback (AAudio-only mode)")
                oboePlayWhenReady = false
                notifyPlayStateChanged(false)
                updateNotification()
                return
            }
            Log.d(TAG, "Resumed")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "resume crash: ${e.message}", e)
            // 非 DAC 模式不再 resume ExoPlayer；仅 DAC 模式才保留兜底
            oboePlayWhenReady = false
            notifyPlayStateChanged(false)
        }
    }

    private fun onOboeResumeSuccess() {
        notifyPlayStateChanged(true)
        updateNotification()
        Log.d(TAG, "Oboe resumed OK")
    }

    fun isPlaying(): Boolean {
        if (usbDacController?.isPlaying == true) return true
        if (oboeDirectPlayer?.isPlaying == true) return true
        return false
    }


    fun getCurrentPosition(): Long {
        // [V8.x] USB DAC: audible position = decode position - ring buffer depth
        usbDacController?.let { if (it.isPlaying || it.audiblePositionMs >= 0) return it.audiblePositionMs }
        if (oboeDirectPlayer?.isPrepared == true) return oboeDirectPlayer?.getCurrentPositionMs() ?: 0
        return 0
    }


    fun getDuration(): Long {
        usbDacController?.let { return it.durationMs }
        if (oboeDirectPlayer?.isPrepared == true) return oboeDirectPlayer?.getDurationMs() ?: 0
        return 0
    }


    fun seekTo(position: Long) {
        if (usbDacController != null) {
            usbDacController?.seekTo(position)
            Log.d(TAG, "seekTo DAC: $position")
        } else if (oboeDirectPlayer?.isPrepared == true) {
            oboeDirectPlayer?.seekTo(position)
            oboeDirectPlayer?.resetDspEq()  // Reset filter state on seek
            oboeDirectPlayer?.resetClipStats()
        }
        // [Phase C] 播放界面 seek 后同步 SimpleBasePlayer 投影，否则媒体卡片 position 不刷新（单向同步）
        try { musicPlayerState.refresh() } catch (_: Exception) {}
    }


    fun playNext() {
        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        DebugLog.add(TAG, "playNext: servicePlaylist=${servicePlaylist.size} songs=${songs.size} curIdx=$currentIndex shuffle=$isShuffleMode")
        if (songs.isEmpty()) return

        // [V3.3.22] REMOVED direct stopDecode() here �� releaseUsbDacController() in playSong() handles it
        // V3.3.4: isShuffleMode is the single source of truth (ExoPlayer flag can be stale)
        // V3.3.8: �����־ȷ�����״̬
        // [V4.0.1] Find real position by currentSong.id, not currentIndex (may be stale)
        val currentId = currentSong?.id
        val realIdx = if (currentId != null) songs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                       else currentIndex
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            playSong(realIdx)
            return
        }
        android.util.Log.d(TAG, "playNext: realIdx=$realIdx isShuffleMode=$isShuffleMode")
        val nextIndex = if (isShuffleMode) {
            if (songs.size <= 1) 0 else (0 until songs.size).filter { it != realIdx }.random()
        } else {
            (realIdx + 1) % songs.size
        }
        android.util.Log.d(TAG, "playNext: nextIndex=$nextIndex (random=${isShuffleMode && songs.size > 1})")
        playSong(nextIndex)
    }

    fun playPrevious() {
        // [V3.3.22] REMOVED direct stopDecode() �� releaseUsbDacController() in playSong() handles it
        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        if (songs.isEmpty()) return

        // [V4.0.1] Find real position by currentSong.id, not currentIndex (may be stale)
        val currentId = currentSong?.id
        val realIdx = if (currentId != null) songs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                       else currentIndex
        val prevIndex = if (realIdx > 0) realIdx - 1 else songs.size - 1
        playSong(prevIndex)
    }

    /**
     * ??Steven v1.6??Shuffle��? -  ExoPlayer  shuffleModeEnabled
     * Settings?? seekToNext() / seekToPrevious() Shuffle
     */
    fun toggleShuffle(): Boolean {
        // V3.3.4: delegate to setShuffleMode - was a second desynced implementation
        // with duplicated playlist-shuffle blocks. servicePlaylist now always keeps
        // original order; shuffle is handled by random pick in playNext().
        setShuffleMode(!isShuffleMode)
        return isShuffleMode
    }

    fun setShuffleMode(enabled: Boolean) {
        isShuffleMode = enabled
        _shuffleModeFlow.value = enabled
        android.util.Log.d(TAG, "setShuffleMode: enabled=$enabled, isShuffleMode=$isShuffleMode")

        // [Phase 1] 同步 MediaSession 状态机（固定卡片 shuffle 图标/状态）
        try { musicPlayerState.refresh() } catch (_: Exception) {}

        // 
        mediaSession?.let { session ->
            val shuffleButton = CommandButton.Builder()
                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            val closeButton = CommandButton.Builder()
                .setDisplayName(getString(R.string.notif_close))
                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            session.setCustomLayout(ImmutableList.of(shuffleButton, closeButton))
        }

        // Refresh
        updateNotification()

        // 
        getSharedPreferences("MusicPlayer", MODE_PRIVATE).edit()
            .putBoolean("shuffle_mode", enabled).apply()
    }

    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        _repeatModeFlow.value = mode
        getSharedPreferences("MusicPlayer", MODE_PRIVATE).edit()
            .putInt("repeat_mode", mode).apply()
        // [Phase 1] 同步 MediaSession 状态机（固定卡片 repeat 状态）
        try { musicPlayerState.refresh() } catch (_: Exception) {}
        android.util.Log.d(TAG, "setRepeatMode: mode=$mode")
    }

    // 

    /**
     * ??Steven ,LifecycleRegistry 
     * :��,
     */
    private fun performHardExit() {
        try {
            Log.d(TAG, "=== performHardExit: Starting hard exit ===")

            // [V3.3.3] Stop USB DAC / Oboe native threads before teardown (avoid killing process with live URB threads)
            try { usbDacController?.stopDecode(); usbDacController = null } catch (_: Exception) {}
            try { UsbDacManager.stopAndRelease() } catch (_: Exception) {}
            try { oboeDirectPlayer?.stop() } catch (_: Exception) {}

            // 1. :
            // release() ��
            // 2.  Visualizer
            visualizerManager.release()

            // 3. y
            mediaSession?.release()
            mediaSession = null

            // 4. 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            // 5. 
            instance = null
            currentSong = null

            // 6. 
            stopSelf()

            Log.d(TAG, "=== performHardExit: Clean exit completed ===")

            // 7. ��:,
            android.os.Process.killProcess(android.os.Process.myPid())

        } catch (e: Exception) {
            Log.e(TAG, "performHardExit error: ${e.message}")
            // ,��??
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW  // LOW = ,
            ).apply {
                description = "Music playback controls"
                setSound(null, null)  // 
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created with IMPORTANCE_DEFAULT")
        }
    }

    // ��V3.2.8��DAC ģʽ��ͬ�� MediaSession �� MediaItem��ϵͳý�忨Ƭ����/���������
    // session ��� metadata������ NotificationCompat �� title����ͬ������ʾ�ɸ�
    private fun syncSessionMediaItem(index: Int, songs: List<Song>) {
        // [Phase C] Removed: SimpleBasePlayer.getState() owns MediaSession metadata
    }


    private fun updateNotification() {
        try {
            val song = currentSong ?: return
            val player = mediaSession?.player ?: return
            val session = mediaSession ?: return
        // 
        // TaskStackBuilder ?? Service  ?? Activity  ?? 
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // [V8.x] Use lastKnownPlayingState instead of player.isPlaying
        // Oboe JNI isPlaying has lag; lastKnownPlayingState is set synchronously
        // in notifyPlayStateChanged �?reflects caller intent immediately
        val isPlaying = this.isPlaying()

        // 
        // getBroadcast  BroadcastReceiver,�� ?? 
        // getService  Intent  MusicService.onStartCommand()
        val prevIntent = PendingIntent.getService(
            this, 0, Intent(this, MusicService::class.java).apply { action = "com.sdw.music.player.PREV" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2, Intent(this, MusicService::class.java).apply { action = if (isPlaying) "com.sdw.music.player.PAUSE" else "com.sdw.music.player.PLAY" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, MusicService::class.java).apply { action = "com.sdw.music.player.NEXT" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Cover??
                // [V8.x] Cached album art �� no disk I/O on main thread (prev jank in DAC mode)
        val artBitmap = if (song.albumArtUri.isNotEmpty()) {
            try { coverCache.get(song.albumArtUri) } catch (_: Exception) { null }
        } else null
        // Cover download is triggered by notifySongChanged on track switch only, not here

val displayArtist = if (song.artist.isNullOrBlank() || song.artist == "Unknown Artist") {
            getString(R.string.app_name_moto)
        } else {
            song.artist
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(displayArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            // 
            // ��V3.2.8��ɾ���ֶ� addAction��Android 13+ ϵͳý�忨Ƭ�Զ��� MediaSession
            // ���ɰ�ť���ֶ� action �������½��ظ���ʾ����/��һ����

        // [V10] Shuffle + Close notification actions (shown in collapsed view)
        val shufflePendingIntent = PendingIntent.getService(
            this, 4, Intent(this, MusicService::class.java).apply { action = "com.sdw.music.player.ACTION_SHUFFLE" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closePendingIntent = PendingIntent.getService(
            this, 5, Intent(this, MusicService::class.java).apply { action = "ACTION_CLOSE" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notificationBuilder.addAction(
            if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle,
            if (isShuffleMode) "Shuffle ON" else "Shuffle",
            shufflePendingIntent
        )
        notificationBuilder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.notif_close),
            closePendingIntent
        )

        @Suppress("DEPRECATION")
        notificationBuilder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
            )

        // [V8.x] Use cached artBitmap (or null if not yet loaded �� async load fills cache next time)
        if (artBitmap != null) {
            notificationBuilder.setLargeIcon(artBitmap as android.graphics.Bitmap)
        }

        val notification = notificationBuilder.build()

        startForeground(NOTIFICATION_ID, notification)
        // 
        // startForeground 
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification updated: title=${song.title}, hasArt=${artBitmap != null}, isPlaying=$isPlaying, shuffleMode=$isShuffleMode, actions=${notification.actions?.size}, playerCommands=${player.availableCommands}")
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification error (service may be dying): ${e.message}")
        }
    }

    // 
    

    // 
    

    override fun onDestroy() {
        isDestroyed = true
        Log.d(TAG, "onDestroy - Service being destroyed")

        // [V3.3.7] ע����Ļ״̬������
        unregisterScreenOffReceiver()
        unregisterStandbyBucketReceiver()
        unregisterPhoneStateListener()

        // Save
        savePlaybackState()
        // 
        SongRepository.persistNow()

        // USB DAC Exclusive cleanup
        releaseUsbDacController()
        if (isUsbExclusiveMode()) {
            UsbDacManager.stopAndRelease()
        }

        // 
        try {
            oboeDirectPlayer?.stop()
            oboeDirectPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "oboeDirect stop error: ${e.message}")
        }
        // [v7.113] �ͷ���Ƶ����
        abandonAudioFocus(this)

        instance = null
        visualizerManager.release()
        volumeGuard.unregister()

        // Cancel SharedPreferences 
        settingsPrefsListener?.let {
            getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it)
        }

        // [V8.x] Unregister USB DAC hotplug callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.unregisterAudioDeviceCallback(usbDacCallback)
            } catch (_: Exception) {}
        }

        // [v7.122] Unregister standby bucket listener
        try { unregisterStandbyBucketReceiver() } catch (_: Exception) {}

        // Cancel any pending Visualizer release
        handler.removeCallbacks(visualizerReleaseTask)

        // 
        EqualizerManager.release()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: task removed, isPlaying=${isPlaying()}")
        if (!isPlaying() || currentSong == null) {
            // 
            Log.d(TAG, "onTaskRemoved: not playing, stopping service")
            stopSelf()
        } else {
            //  Service  stopSelf()
            Log.d(TAG, "onTaskRemoved: still playing, keeping service alive")
        }
    }

    /**
     * Settings FFT 
     */
    fun setFftCallback(callback: ((ByteArray) -> Unit)?) {
        fftCallback = callback
        if (callback != null) visualizerManager.retry()
    }
    /**
     * ??v4.76Visualizer 
     */
    fun isVisualizerReady(): Boolean = visualizerManager.isReady()

    // [v7.122] Register receiver to auto-map system standby bucket to idle_level
    private fun registerStandbyBucketReceiver() {
        if (standbyBucketReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyStandbyBucketToIdleLevel()
            }
        }
        try {
            // ACTION_APPLICATION_STANDBY_BUCKET_CHANGED exposed as SDK constant from API 31
            @Suppress("InlinedApi")
            val filter = IntentFilter("android.os.action.APPLICATION_STANDBY_BUCKET_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnsafeRegisteredReceiver")
                registerReceiver(receiver, filter)
            }
            standbyBucketReceiver = receiver
            Log.d(TAG, "Standby bucket receiver registered")
            // Also apply immediately on registration
            applyStandbyBucketToIdleLevel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register standby bucket receiver: ${e.message}")
        }
    }

    private fun unregisterStandbyBucketReceiver() {
        try {
            standbyBucketReceiver?.let {
                unregisterReceiver(it)
                standbyBucketReceiver = null
                Log.d(TAG, "Standby bucket receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister standby bucket receiver: ${e.message}")
        }
    }

    // [V3.3.7] ������ͣ FFT ���ӻ������� CPU ����
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isScreenOn = true

    // 电话状态监听：来电/通话中暂停播放，挂断后自动恢复（不依赖 AudioFocus，避免 Oboe 假性焦点丢失）
    private var phoneStateListener: PhoneStateListener? = null
    private var wasPlayingBeforeCall = false

    private fun registerPhoneStateListener() {
        if (phoneStateListener != null) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            // 来电/接听/拨出：若正在播放则暂停
                            if (isPlaying()) {
                                wasPlayingBeforeCall = true
                                pause()
                                Log.d(TAG, "Call active (state=$state), pausing playback")
                            }
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            // 通话结束：若通话前在播放则恢复
                            if (wasPlayingBeforeCall && !isPlaying()) {
                                wasPlayingBeforeCall = false
                                resume()
                                Log.d(TAG, "Call ended, resuming playback")
                            }
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            phoneStateListener = listener
            Log.d(TAG, "Phone state listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register phone state listener: ${e.message}")
        }
    }

    private fun unregisterPhoneStateListener() {
        try {
            phoneStateListener?.let {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                tm?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
            Log.d(TAG, "Phone state listener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister phone state listener: ${e.message}")
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        Log.d(TAG, "Screen OFF, pausing Visualizer (DAC playback continues)")
                        // [V3.3.7] ����ʱ�ͷ� Visualizer����ʡ CPU
                        // ע�⣺DAC �����̲߳���Ӱ��
                        if (visualizerManager.isReady()) {
                            visualizerManager.release()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        Log.d(TAG, "Screen ON, resuming Visualizer")
                        // ����ʱ�ָ� Visualizer�������Ҫ��
                        if (fftCallback != null && !visualizerManager.isReady() && isAppForeground) {
                            handler.postDelayed({ visualizerManager.setup() }, 300)
                        }
                    }
                }
            }
        }
        
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnsafeRegisteredReceiver")
                registerReceiver(receiver, filter)
            }
            screenOffReceiver = receiver
            Log.d(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen state receiver: ${e.message}")
        }
    }

    private fun unregisterScreenOffReceiver() {
        try {
            screenOffReceiver?.let {
                unregisterReceiver(it)
                screenOffReceiver = null
                Log.d(TAG, "Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen state receiver: ${e.message}")
        }
    }

    // [v7.122] Read system standby bucket and write corresponding idle_level
    private fun applyStandbyBucketToIdleLevel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val bucket = usm.appStandbyBucket
            val mappedLevel = when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> null  // don't override, user may have set a preference
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "常用"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "频繁"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "偶尔"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "受限"
                else -> "偶尔"
            }
            if (mappedLevel != null) {
                val prefs = getSharedPreferences("sdw_music_prefs", MODE_PRIVATE)
                val current = prefs.getString("idle_level", "频繁") ?: "频繁"
                if (current != mappedLevel) {
                    prefs.edit().putString("idle_level", mappedLevel).apply()
                    Log.d(TAG, "Auto-set idle_level to $mappedLevel (system bucket=$bucket)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply standby bucket: ${e.message}")
        }
    }
}






