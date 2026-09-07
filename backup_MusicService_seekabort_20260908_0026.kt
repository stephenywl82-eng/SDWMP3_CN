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
import com.sdw.music.player.core.audio.BtCodecTracker
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

    // 【Crossfade】顺序自动交叉淡化（只 Oboe 路径，方案 B：先 mix 再 DSP）
    private var crossfadeEnabled = false
    private var crossfadeDurationMs = 5000
    @Volatile private var crossfadeBusy = false  // 一次 crossfade 进行中（防重复触发）
    private var crossfadeNextIndex = -1  // 已预加载的下一首索引（crossfade 完成后切元数据）
    // 【能量切点】crossfade 触发窗口内的能量低谷检测（避开鼓点/高潮硬叠打架）
    private var xfadeDipCount = 0        // 连续低谷采样计数（200ms/采样）
    private var xfadePeakRms = 0f        // 窗口内能量峰值（带衰减）
    // 【V8.9 Crossfade 优化①·双层预扫描】
    // A 轨尾部低能量段起点(ms)（绝对时间戳）：播放开始时预扫，决定触发时机
    private var xfadeAQuietStartMs = -1L
    private var xfadeAPrescanDone = false
    // 【V8.15 A 轨副歌出】A 轨最后一个副歌结束位置(ms)：预扫 mode=3 得到，副歌唱完即触发 crossfade
    @Volatile private var xfadeAChorusEndMs = -1L
    // 【V8.16】本次 crossfade B 轨起点(ms)：B 上位后重扫副歌结束只扫该点之后，防止"播几十秒又切"
    @Volatile private var xfadeLastBStartMs = -1L
    // 【V8.17】BPM/副歌模式开关：settings shuffle_mode != "random" 时启用副歌逻辑（B从副歌进+A副歌完切）；
    // 纯随机("random")时禁用副歌，回归 5/10/15s 定时 + 能量低谷原始逻辑
    @Volatile private var bpmChorusMode = true
    // B 轨开头前奏静音段长度(ms)：openIncoming 后预扫，决定交叉窗口对齐；-1=无静音前奏
    private var xfadeBIntroMs = -1L
    private var xfadeBPrescanDone = false
// 【V8.15】B 轨副歌起点(ms)：预扫 mode=2 得到，crossfade 时 B 从副歌进；-1=未找到
private var xfadeBChorusMs = -1L
    // 【V8.15】两阶段 crossfade：preloading 提前（候选窗口），预扫完成后才触发
    @Volatile private var xfadePreloading = false
    @Volatile private var xfadePreloadReady = false  // 【2026-09-02】B 轨 openIncoming 后台完成标志
    // 【V8.24 A 轨拍点触发】拍点延迟已安排标志（防 monitor 每 tick 重复 postDelayed）
    @Volatile private var xfadeBeatPending = false          // 正在预加载 B（尚未触发）
    @Volatile private var xfadePreloadPath: String? = null // 预加载中的 B 路径
    @Volatile private var xfadePreloadIndex = -1           // 预加载中的 B 索引
    @Volatile private var xfadePreloadSong: Song? = null   // 预加载中的 B 歌曲

    // 【V8.4】Crossfade UI 状态推送：供播放界面做氛围色交接 + 封面交叉淡化动画
    // 状态机：IDLE（无）→ PRELOADING（预加载 B，UI 预读封面/主色）→ ACTIVE（交叉淡化中）
    //        → DONE（音频已交接，UI 保留叠加层直到封面 URI 切换，防“变两次”跳变）→ 下次播放清回 IDLE
    enum class XfadeUiState { IDLE, PRELOADING, ACTIVE, DONE }
    data class CrossfadeUiInfo(
        val state: XfadeUiState = XfadeUiState.IDLE,  // 当前阶段
        val durationMs: Int = 5000,         // crossfade 总时长
        val nextTitle: String = "",         // 下一首歌名（B 轨）
        val nextArtist: String = "",
        val nextAlbumArt: String? = null,   // B 轨封面 URI
        val nextAccentColor: Long = 0L      // B 轨主色（0 = 未提取）
    )
    @Volatile var crossfadeUiInfo = CrossfadeUiInfo()
        private set

    private val crossfadeMonitor = object : Runnable {
        override fun run() {
            // 无条件心跳：条件不满足也要重新调度自己，避免 monitor 永久停死
            if (isDestroyed) return
            // 每次 tick 动态读 prefs，不依赖字段时序（service 进程可能晚于开关设置才重启）
            val cf = getSharedPreferences("settings", MODE_PRIVATE)
            val enabled = cf.getBoolean("crossfade_enabled", false)
            val dur = cf.getInt("crossfade_duration_ms", 5000)
            val smartAlign = cf.getBoolean("crossfade_smart_align", true)
            // 【V8.17】纯随机模式（shuffle_mode=random）禁用副歌逻辑，回归定时+能量低谷
            bpmChorusMode = cf.getString("shuffle_mode", "random") == "bpm"
            crossfadeEnabled = enabled
            crossfadeDurationMs = dur
            // 智能对齐关闭时禁用预扫描分支（回退实时 RMS 检测）
            if (!smartAlign) { xfadeAPrescanDone = false; xfadeBPrescanDone = false }
            // 纯随机模式：禁用副歌相关状态（A 副歌触发、B 副歌起点）
            if (!bpmChorusMode) { xfadeAChorusEndMs = -1L; xfadeBChorusMs = -1L }
            var triggered = false
            if (enabled && !crossfadeBusy) {
                val p = oboeDirectPlayer
                if (p != null && p.isPrepared) {
                    val totalDur = p.getDurationMs()
                    val pos = p.getCurrentPositionMs()
                    if (totalDur > 0) {
                        val remain = totalDur - pos
                        // 【能量切点】进入候选区(remain<=dur)后，等能量低谷再切；
                        // 找不到低谷则 remain<=1500ms 强制触发（兑底，保证不漏切）
                        val FLOOR_MS = 1500
                        var shouldTrigger = false
                        // 【V8.15 A 轨副歌出】副歌唱完即触发：pos 越过最后副歌结束位置（留 8s 尾奏过渡）
                        // 【V8.17】仅 BPM 匹配模式启用；纯随机模式跳过（回归定时+能量低谷）
                        if (bpmChorusMode && xfadeAChorusEndMs > 0) {
                            // 【V8.16】剩余可播时长 < 30s 时不用副歌触发（B 从后半进场会很快又切）
                            val remainAfterChorus = xfadeAChorusEndMs - pos
                            if (remainAfterChorus >= 30000L) {
                                val chorusLead = 8000L  // 副歌结束前 8s 进入预加载窗口
                                if (pos >= xfadeAChorusEndMs - chorusLead && remain > dur) {
                                    shouldTrigger = true
                                    Log.i(TAG, "Crossfade: A-chorus-end trigger pos=$pos chorusEnd=${xfadeAChorusEndMs} remain=$remain dur=$dur")
                                }
                            }
                        }
                        // 【V8.9 优化①·双层预扫描】
                        // A 轨：播放开始时预扫尾部，拿到「A 轨结尾前低能量段起点」= 最佳交叉点
                        //   触发条件：remain<=dur 且 pos 已越过 A 静音段起点（交叉落在静音窗口内）
                        // B 轨：开头前奏静音段长度，用于对齐参考（B 前奏安静时可从容 fade in）
                        if (xfadeAPrescanDone && xfadeAQuietStartMs > 0) {
                            if (remain <= dur && pos >= xfadeAQuietStartMs - 300) {
                                shouldTrigger = true
                                Log.i(TAG, "Crossfade: prescan-A trigger pos=$pos aQuietStart=${xfadeAQuietStartMs} remain=$remain dur=$dur")
                            }
                            // 兜底：A 预扫对齐失败（A 无静音段/已错过）仍用 FLOOR_MS 保证不漏切
                            if (!shouldTrigger && remain <= FLOOR_MS) shouldTrigger = true
                        } else {
                            if (remain <= dur) {
                                val rms = p.getRmsLevel()
                                xfadePeakRms = maxOf(xfadePeakRms * 0.995f, rms)
                                if (rms < 0.15f && rms < xfadePeakRms * 0.4f) {
                                    xfadeDipCount++
                                } else {
                                    xfadeDipCount = 0
                                }
                                if (remain <= FLOOR_MS || xfadeDipCount >= 2) {
                                    shouldTrigger = true
                                    xfadeDipCount = 0
                                    xfadePeakRms = 0f
                                }
                            }
                        }
                        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
                        val realIdx = currentIndex
                        if (songs.isNotEmpty() && repeatMode != Player.REPEAT_MODE_ONE) {
                            // 【v8.15】复用 pickNextIndex：crossfade 自动切歌也走 BPM 匹配（随机开启时）
                            // 【V8.24】预加载已在进行时不再每 tick 重新选歌（此前每 200ms 随机重选+刷日志）
                            val nextIndex = if (!xfadePreloading) {
                                pickNextIndex(songs, realIdx).takeIf { it != realIdx } ?: -1
                            } else xfadePreloadIndex
                            if (nextIndex >= 0 && nextIndex != realIdx) {
                                // ===== 阶段1：候选窗口（A 副歌结束前 8s 或 remain<=dur+8s，取先到）→ 启动预加载+副歌预扫（不触发） =====
                                val preloadByChorus = xfadeAChorusEndMs > 0 && pos >= xfadeAChorusEndMs - 8000
                                val preloadByRemain = remain <= dur + 8000
                                if (!xfadePreloading && (preloadByChorus || preloadByRemain)) {
                                    xfadePreloading = true
                                    xfadePreloadIndex = nextIndex
                                    xfadePreloadSong = songs[nextIndex]
                                    val preFp = songs[nextIndex].filePath.ifEmpty { songs[nextIndex].path }
                                    xfadePreloadPath = if (preFp.startsWith("content://")) resolveContentUriToPath(preFp) else preFp
                                    val prePath = xfadePreloadPath
                                    if (prePath != null) {
                                        Log.i(TAG, "Crossfade: PRELOADING ${songs[nextIndex].title} (remain=$remain, window=${dur + 8000})")
                                        crossfadeUiInfo = CrossfadeUiInfo(
                                            state = XfadeUiState.PRELOADING,
                                            durationMs = dur,
                                            nextTitle = songs[nextIndex].title,
                                            nextArtist = songs[nextIndex].artist,
                                            nextAlbumArt = songs[nextIndex].albumArtUri?.takeIf { it.isNotEmpty() },
                                            nextAccentColor = 0L
                                        )
                                        // 【2026-09-02 fix】openIncoming 移后台线程——原在主线程同步执行，
                                        // 大 m4a/MediaCodec configure 数百 ms~秒级 → 主线程阻塞 → UI 卡死
                                        xfadePreloadReady = false
                                        Thread {
                                            try {
                                                val ok = p.openIncoming(prePath)
                                                xfadePreloadReady = ok
                                                if (!ok) {
                                                    Log.w(TAG, "Crossfade: openIncoming failed (preload, bg)")
                                                    xfadePreloading = false
                                                    xfadePreloadPath = null
                                                    xfadePreloadSong = null
                                                    xfadePreloadIndex = -1
                                                }
                                            } catch (e: Throwable) {
                                                Log.w(TAG, "Crossfade: openIncoming exception ${e.message}")
                                                xfadePreloadReady = false
                                            }
                                        }.apply { isDaemon = true }.start()
                                        // 副歌预扫（后台线程，~3-5s）—— 仅 BPM 匹配模式；纯随机跳过（B 从头播）
                                        // 预扫与 openIncoming 各自独立 extractor 并发跑, 无冲突
                                        xfadeBChorusMs = -1L
                                        if (bpmChorusMode) {
                                            val scanPath = prePath
                                            Thread {
                                                try {
                                                    val chorus = p.preScanPath(scanPath, 100, mode = 2)
                                                    xfadeBChorusMs = chorus.toLong()
                                                    if (chorus > 0) Log.i(TAG, "Crossfade: prescan-B chorus=$chorus ms")
                                                    else Log.i(TAG, "Crossfade: prescan-B no chorus (start from 0)")
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Crossfade: prescan-B chorus failed ${e.message}")
                                                    xfadeBChorusMs = -1L
                                                }
                                            }.apply { isDaemon = true }.start()
                                        }
                                    } else {
                                        xfadePreloading = false
                                        xfadePreloadPath = null
                                        xfadePreloadSong = null
                                    }
                                }
                                // ===== 阶段2：预加载已就绪 + 触发条件满足 → 真正 crossfade =====
                                // 【V8.24 A 轨拍点触发】shouldTrigger 满足时，若 A 轨 BPM 已知且 BPM 匹配模式，
                                // 延迟到 A 轨下一个强拍再触发（delay = beatMs - pos%beatMs），
                                // 与 B 轨已对齐的副歌拍点同步交接 → 拍上无缝
                                if (xfadePreloading && xfadePreloadReady && shouldTrigger) {
                                    val aBpmForBeat = if (bpmChorusMode) {
                                        com.sdw.music.player.BpmKeyCache.init(this@MusicService)
                                        val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                                        if (aPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(aPath)?.first ?: 0 else 0
                                    } else 0
                                    var beatDelay = 0L
                                    if (aBpmForBeat in 40..220) {
                                        val beatMs = 60000L / aBpmForBeat
                                        val posInBeat = pos % beatMs
                                        beatDelay = beatMs - posInBeat
                                        // 已贴拍（≤250ms）直接触发；否则延迟到拍点
                                        if (beatDelay > 250L) {
                                            if (!xfadeBeatPending) {
                                                xfadeBeatPending = true
                                                val prePath = xfadePreloadPath
                                                val preSong = xfadePreloadSong
                                                val preIndex = xfadePreloadIndex
                                                Log.i(TAG, "Crossfade: beat-align trigger in ${beatDelay}ms (bpm=$aBpmForBeat pos=$pos beatMs=$beatMs)")
                                                handler.postDelayed({
                                                    xfadeBeatPending = false
                                                    // 拍点到达：清预加载态，执行真实触发
                                                    if (prePath != null && preSong != null) {
                                                        xfadePreloading = false
                                                        xfadePreloadPath = null
                                                        xfadePreloadSong = null
                                                        doCrossfadeTrigger(p, preSong, preIndex, dur)
                                                    } else {
                                                        crossfadeBusy = false
                                                    }
                                                    // 触发后由 scheduleCrossfadeCompletionPoll 恢复 monitor 调度
                                                }, beatDelay)
                                            }
                                            // 已安排拍点，本 tick 不再走立即触发
                                            triggered = false
                                        } else {
                                            xfadeBeatPending = false
                                            val prePath = xfadePreloadPath
                                            val preSong = xfadePreloadSong
                                            val preIndex = xfadePreloadIndex
                                            xfadePreloading = false
                                            xfadePreloadPath = null
                                            xfadePreloadSong = null
                                            if (prePath != null && preSong != null) {
                                                doCrossfadeTrigger(p, preSong, preIndex, dur)
                                                triggered = true
                                            } else {
                                                crossfadeBusy = false
                                            }
                                        }
                                    } else {
                                        xfadeBeatPending = false
                                        val prePath = xfadePreloadPath
                                        val preSong = xfadePreloadSong
                                        val preIndex = xfadePreloadIndex
                                        xfadePreloading = false
                                        xfadePreloadPath = null
                                        xfadePreloadSong = null
                                        if (prePath != null && preSong != null) {
                                            doCrossfadeTrigger(p, preSong, preIndex, dur)
                                            triggered = true
                                        } else {
                                            crossfadeBusy = false
                                        }
                                    }
                                }
                                // 【V8.24 修复】A 轨播完(EOS)但预加载已就绪时强制接管：
                                // remain<=FLOOR_MS 正常兜底；A 轨已 EOS（isPlaying=false 或 pos 逼近/越过结尾）也强制
                                val aEosNoTakeover = p.isPlaying != true && xfadePreloading
                                if (!triggered && xfadePreloading && xfadePreloadReady && (remain <= 1500 || aEosNoTakeover)) {
                                    // 预加载就绪但触发条件未满足且已到 FLOOR_MS → 强制触发（兜底）
                                    val prePath = xfadePreloadPath
                                    val preSong = xfadePreloadSong
                                    val preIndex = xfadePreloadIndex
                                    xfadePreloading = false
                                    xfadePreloadPath = null
                                    xfadePreloadSong = null
                                    if (prePath != null && preSong != null) {
                                        crossfadeBusy = true
                                        crossfadeNextIndex = preIndex
                                        Log.i(TAG, "Crossfade: FLOOR trigger ${preSong.title} (remain=$remain)")
                                        var chorusMs = xfadeBChorusMs
                                        val deadline = System.currentTimeMillis() + 3000
                                        while (chorusMs < 0 && System.currentTimeMillis() < deadline) {
                                            try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                            chorusMs = xfadeBChorusMs
                                        }
                                        if (chorusMs > 0 && bpmChorusMode) {  // 【V8.17】纯随机不 seek 副歌
                                            xfadeLastBStartMs = chorusMs.toLong()  // 【V8.16】记录 B 起点
                                            val seeked = p.seekIncoming(chorusMs.toInt())
                                            if (seeked) Log.i(TAG, "Crossfade: B starts at chorus ${chorusMs}ms (floor)")
                                        } else {
                                            xfadeLastBStartMs = -1L
                                        }
                                        val cfDur = adaptiveXfadeDur(preSong)
                                        val ok = p.startCrossfade(cfDur, if (chorusMs > 0 && bpmChorusMode) chorusMs.toInt() else 0)
                                        if (ok) {
                                            triggered = true
                                            Log.i(TAG, "Crossfade: triggered ${cfDur}ms → ${preSong.title} (floor, dur=$dur)")
                                            crossfadeUiInfo = crossfadeUiInfo.copy(state = XfadeUiState.ACTIVE)
                                            scheduleCrossfadeCompletionPoll(p, preSong, preIndex)
                                        } else {
                                            Log.w(TAG, "Crossfade: startCrossfade failed (floor)")
                                            crossfadeUiInfo = CrossfadeUiInfo()
                                            p.stopIncoming()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    // 【V8.24 A 轨拍点触发】阶段2 真实触发体（立即触发与拍点延迟共用）
    private fun doCrossfadeTrigger(p: OboeDirectPlayer, preSong: Song, preIndex: Int, dur: Int) {
        // 【2026-09-02 fix】整体后台执行——原在主线程等 chorus 预扫最多 3s（Thread.sleep 阻塞主线程
        // → crossfade 触发瞬间 UI 卡顿）。UI 更新用 handler.post 切回主线程。
        Thread {
            try {
                doCrossfadeTriggerBg(p, preSong, preIndex, dur)
            } catch (e: Throwable) {
                Log.w(TAG, "Crossfade: trigger bg exception ${e.message}")
                crossfadeBusy = false
                handler.post { crossfadeUiInfo = CrossfadeUiInfo() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun doCrossfadeTriggerBg(p: OboeDirectPlayer, preSong: Song, preIndex: Int, dur: Int) {
        crossfadeBusy = true
        crossfadeNextIndex = preIndex
        Log.i(TAG, "Crossfade: preloading next: ${preSong.title}")
        // 等 chorus 预扫完成（最多 3s；超时从 0 播）
        var chorusMs = xfadeBChorusMs
        val deadline = System.currentTimeMillis() + 3000
        while (chorusMs < 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
            chorusMs = xfadeBChorusMs
        }
        if (chorusMs > 0 && bpmChorusMode) {  // 【V8.17】纯随机不 seek 副歌，B 从头播
            xfadeLastBStartMs = chorusMs.toLong()  // 【V8.16】记录 B 起点
            val seeked = p.seekIncoming(chorusMs.toInt())
            if (seeked) Log.i(TAG, "Crossfade: B starts at chorus ${chorusMs}ms")
        } else {
            xfadeLastBStartMs = -1L  // 【V8.16】无副歌起点
        }
        // 【V8.23 BPM 自适应时长】A/B 两轨 BPM 差 ≤2 → 短混音（2s，DJ 式拍上交接）；差 >2 → 用户设定时长
        val cfDur = adaptiveXfadeDur(preSong)
        val ok = p.startCrossfade(cfDur, if (chorusMs > 0 && bpmChorusMode) chorusMs.toInt() else 0)
        if (ok) {
            Log.i(TAG, "Crossfade: triggered ${cfDur}ms → ${preSong.title} (dur=$dur)")
            handler.post { crossfadeUiInfo = crossfadeUiInfo.copy(state = XfadeUiState.ACTIVE) }
            scheduleCrossfadeCompletionPoll(p, preSong, preIndex)
        } else {
            Log.w(TAG, "Crossfade: startCrossfade failed")
            handler.post { crossfadeUiInfo = CrossfadeUiInfo() }
            p.stopIncoming()
        }
    }

    private fun scheduleCrossfadeCompletionPoll(p: OboeDirectPlayer, nextSong: Song, nextIndex: Int) {
        // 轮询直到 active 轨翻转（crossfade 完成），然后切元数据 + 继续下一轮监控
        val startActiveB = p.isActiveB()
        val pollStart = System.currentTimeMillis()
        val poller = object : Runnable {
            override fun run() {
                if (isDestroyed) { crossfadeBusy = false; return }
                val nowB = p.isActiveB()
                // crossfade 完成 = active 轨状态与触发前相反
                if (nowB != startActiveB) {
                    // 【V8.4】crossfade 完成 → DONE：UI 保留叠加层直到封面 URI 切换（防变两次跳变）
                    crossfadeUiInfo = crossfadeUiInfo.copy(state = XfadeUiState.DONE)
                    handler.post {
                        currentSong = nextSong
                        currentIndex = nextIndex
                        notifySongChanged(nextSong)
                        updateNotification()
                    }
                    crossfadeBusy = false
                    crossfadeNextIndex = -1
                    // 【V8.10】B 上位成为新 A 轨：重新预扫其尾部静音段 + 副歌结束，供下一轮 crossfade 触发使用
                    xfadeAPrescanDone = false
                    xfadeAQuietStartMs = -1L
                    xfadeAChorusEndMs = -1L
                    // 【V8.16】B 上位重扫副歌结束只扫实际起点之后（防止把 B 当新歌从头扫，定位到后段副歌 → 播几十秒又切）
                    val bStartForScan = if (xfadeLastBStartMs > 0) xfadeLastBStartMs else 0L
                    val newAPath = nextSong.filePath.ifEmpty { nextSong.path }
                    val newAActual = if (newAPath.startsWith("content://")) {
                        resolveContentUriToPath(newAPath)
                    } else {
                        newAPath
                    }
                    if (newAActual != null) {
                        Thread {
                            try {
                                // 【V8.15 A 轨副歌出】重新预扫新 A 的最后副歌结束位置（V8.16：从 B 实际起点之后扫）
                                // 【V8.17】仅 BPM 匹配模式；纯随机跳过副歌预扫
                                if (bpmChorusMode) {
                                    val aChorusEnd = p.preScanPath(newAActual, 100, mode = 3, seekFromMs = bStartForScan)
                                    if (aChorusEnd > 0) {
                                        xfadeAChorusEndMs = aChorusEnd.toLong()
                                        Log.i(TAG, "Crossfade: prescan-A(next) chorusEnd=$aChorusEnd ms (from $bStartForScan)")
                                    }
                                }
                                val quiet = p.preScanPath(newAActual, 100, mode = 0, seekFromMs = 0L)
                                xfadeAQuietStartMs = quiet.toLong()
                                xfadeAPrescanDone = true
                                if (quiet > 0) Log.i(TAG, "Crossfade: prescan-A(next) quietStart=$quiet ms")
                                else Log.i(TAG, "Crossfade: prescan-A(next) no quiet segment (fallback RMS)")
                            } catch (e: Exception) {
                                Log.w(TAG, "Crossfade: prescan-A(next) failed ${e.message}")
                                xfadeAQuietStartMs = -1L
                                xfadeAPrescanDone = true
                            }
                        }.apply { isDaemon = true }.start()
                    } else {
                        xfadeAPrescanDone = true
                    }
                    // 继续监控下一轮
                    handler.postDelayed(crossfadeMonitor, 200)
                    return
                }
                if (System.currentTimeMillis() - pollStart > crossfadeDurationMs + 3000L) {
                    // 超时：crossfade 未完成（可能异常），恢复硬切兜底
                    // 【V8.4】超时也清 UI 状态
                    crossfadeUiInfo = CrossfadeUiInfo()
                    crossfadeBusy = false
                    crossfadeNextIndex = -1
                    handler.postDelayed(crossfadeMonitor, 200)
                    return
                }
                handler.postDelayed(this, 100)
            }
        }
        handler.postDelayed(poller, 100)
    }

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
            // 【2026-09-07】蓝牙 A2DP 连接/编码切换 → 刷新预补偿
            val anyBt = addedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (anyBt) refreshBtPreEmphasis()
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

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // 【2026-09-07】蓝牙 A2DP 断开/切换 → 刷新预补偿
            val anyBt = removedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (anyBt) refreshBtPreEmphasis()
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

            // [fix] 无真实播放歌曲时报告 INDEX_UNSET，避免 connect() 误把 index=0（歌单第一首）
            // 当成「当前歌曲」写回 _currentSong，导致迷你条每次打开都显示同一首歌。
            val safeIndex = if (song == null) {
                C.INDEX_UNSET
            } else {
                MusicService.currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
            }
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
            // [fix] 系统媒体卡片「下一曲/上一曲」按钮走 media3 默认 seek，其 getNextMediaItemIndex()
            // 是纯顺序 +1、忽略 shuffleModeEnabled，若直接 playSong(mediaItemIndex) 会绕过
            // playNext() 的 shuffle 随机逻辑 → 出现「shuffle 图标显示随机、切歌却按顺序」。
            // 这里按 seekCommand 分流到权威 playNext()/playPrevious()；点歌单某首（SEEK_TO_MEDIA_ITEM）
            // 仍走精确 index。
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> this@MusicService.playNext()
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> this@MusicService.playPrevious()
                else -> {
                    if (mediaItemIndex != MusicService.currentIndex && mediaItemIndex in songs.indices) {
                        this@MusicService.playSong(mediaItemIndex)
                    } else {
                        this@MusicService.seekTo(positionMs)
                    }
                }
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
            // [fix] 让设置的 idle_level 真正说了算：手动暂停与自动播完统一按设置档位
            val idleMs = when (inst.getSharedPreferences("sdw_music_prefs", MODE_PRIVATE).getString("idle_level", "频繁")) {
                "常用" -> 1_800_000L  // 30 min
                "频繁" -> 300_000L       // 5 min
                "偶尔" -> 3_000L             // 3 sec
                "受限" -> 0L            // immediate
                else -> 300_000L
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

        // 【Crossfade】读取顺序自动交叉淡化开关
        val cfPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        crossfadeEnabled = cfPrefs.getBoolean("crossfade_enabled", false)
        crossfadeDurationMs = cfPrefs.getInt("crossfade_duration_ms", 5000)
        Log.d(TAG, "Restored crossfade: enabled=$crossfadeEnabled, dur=$crossfadeDurationMs")

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
            // 【方向1】claim 是 Manager 全局状态，以 isClaimed() 为准；controller 每首歌新建
            // （单曲会话状态机，见 UsbDacPlaybackController.DacState），dacRunning 仅诊断参考。
            // 旧代码用局部 var dacStreaming 手工推断「play 是否需要 startStreaming」，
            // 8.21 曾漏置导致 keep-claim 噪音/卡死。现在所有分支都保证走到 play() 时
            // native 流未启，streamAlreadyRunning 恒 false，推断逻辑整体删除。
            val dacClaimed = UsbDacManager.isClaimed()
            val dacRunning = UsbDacManager.isStreaming()
            val dacSession = usbDacController?.sessionState?.name
            DebugLog.add(TAG, "playSong[$index]: ${dacProfile.name} Bit-Perfect, claimed=$dacClaimed running=$dacRunning session=$dacSession")

            // First play (or after full teardown): claim DAC
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
            } else if (dacRunning) {
                // claim 保留且流在跑（手动切歌）：查采样率是否变化
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
                    val device2 = UsbDacManager.getDacDevice()
                    val bits = DacProfile.wireBitsFor(device2?.vendorId ?: 0, device2?.productId ?: 0)
                    if (device2 != null && UsbDacManager.claimAndStart(device2, newRate, 2, bits)) {
                        DebugLog.add(TAG, "playSong[$index]: cross-rate reclaim OK")
                    } else {
                        DebugLog.add(TAG, "playSong[$index]: cross-rate reclaim FAIL, fallback Oboe system-route (Salt Player style)")
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                        refreshOboeModeCache()
                        playSongOboeDirect(index, songs)
                        notifySongChanged(songs[index])
                        return
                    }
                } else {
                    // 【V8.21 fix】手动切歌 same-rate keep-claim：stopMonitor() 内部已 pauseStream
                    // + resetRingBuffer + releaseResources（controller → EOS_HOLD 语义）。
                    // 随后新建 controller open()+play(streamAlreadyRunning=false) 走完整
                    // startStreaming（native keep-stream 幂等重启）。
                    usbDacController?.stopMonitor()
                    usbDacController = null
                    DebugLog.v(TAG, "playSong[$index]: DAC keep-claim, monitor killed, play() will restart stream")
                }
            } else {
                // claim 保留但流已停（EOS 自动续播 / 暂停恢复 / 上轮 keep-claim 后）：
                // 直接 restart stream，不 re-claim（re-claim 会 EBUSY）。
                DebugLog.add(TAG, "playSong[$index]: claim held, stream stopped — restart stream, no reclaim")
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
                        // 【修复】解码异常不自动切歌、不 fallback ExoPlayer（用户明确不要）。
                        // 解码线程 finally 已 releaseResources + isPlaying=false，流自然停住。
                        // 保持静默，交给用户手动操作，避免「报错→切下一首」级联。
                        handler.post { notifyPlayStateChanged(false) }
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
                    // 【方向1】所有分支已保证 native 流未启（claim 后/keep-claim stopMonitor 后/EOS 后均如此），
                    // streamAlreadyRunning 恒 false：play() 必走完整 startStreaming（幂等安全）
                    controller.play(streamAlreadyRunning = false)
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
                            UsbDacManager.setMseb10Band(
                                MsebCalculator.calculateGains(msebParams),
                                MsebCalculator.BAND_FREQS,
                                MsebCalculator.BAND_QS
                            )
                            dspEqEnabled = true
                        }
                    }
                    // 【V8.19】DTS 环绕独立开关恢复（native 声场状态在重新打开 DAC 后会丢）
                    if (MsebCalculator.isDtsEnabled(this@MusicService)) {
                        val (ss, img) = MsebCalculator.dtsStageParams(this@MusicService)
                        UsbDacManager.setMsStage(ss, img)
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
                refreshBtPreEmphasis()  // 【2026-09-07】播放器就绪后应用蓝牙预补偿设置

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
                    handler.post {
                        crossfadeBusy = false
                        crossfadeNextIndex = -1
                        crossfadeUiInfo = CrossfadeUiInfo()  // 【V8.4】手动/自然完成清 UI 状态
                        playNext()
                    }
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

                // 【V8.9 优化①·双层】A 轨预扫：当前歌尾部低能量段起点（决定 crossfade 触发时机）
                // 后台线程不阻塞播放；失败回退实时 RMS 检测
                xfadeAPrescanDone = false
                xfadeAQuietStartMs = -1L
                xfadeAChorusEndMs = -1L
                val aScanPath = actualPath
                Thread {
                    try {
                        // 【V8.15 A 轨副歌出】预扫最后一个副歌结束位置（mode=3），副歌唱完提前触发
                        // 【V8.17】仅 BPM 匹配模式；纯随机跳过（回归定时+能量低谷）
                        if (bpmChorusMode) {
                            val aChorusEnd = newPlayer.preScanPath(aScanPath, 100, mode = 3, seekFromMs = 0L)
                            if (aChorusEnd > 0) {
                                xfadeAChorusEndMs = aChorusEnd.toLong()
                                Log.i(TAG, "Crossfade: prescan-A chorusEnd=${aChorusEnd} ms")
                            } else {
                                Log.i(TAG, "Crossfade: prescan-A no chorusEnd (fallback)")
                            }
                        }
                        val quiet = newPlayer.preScanPath(aScanPath, 100, mode = 0, seekFromMs = 0L)
                        xfadeAQuietStartMs = quiet.toLong()
                        xfadeAPrescanDone = true
                        if (quiet > 0) Log.i(TAG, "Crossfade: prescan-A quietStart=$quiet ms")
                        else Log.i(TAG, "Crossfade: prescan-A no quiet segment (fallback RMS)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Crossfade: prescan-A failed ${e.message}")
                        xfadeAQuietStartMs = -1L
                        xfadeAPrescanDone = true
                    }
                }.apply { isDaemon = true }.start()

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
                oboeFlowTrace = "2705 Oboe OK (exclusive=${newPlayer.isExclusiveMode()})"

                handler.post {
                    currentSong = song
                    currentIndex = index
                    volumeGuard.resetMuteState()
                    oboeFailureCount = 0
                    // 【能量切点】切歌后复位低谷检测状态，避免跨歌曲残留
                    xfadeDipCount = 0
                    xfadePeakRms = 0f

                    EqualizerManager.restoreSettings(this@MusicService)

                    // Restore MSEB if active (new OboeDirectPlayer resets native Biquad to zero)
                    if (MsebCalculator.isEnabled(this@MusicService)) {
                        val msebParams = MsebCalculator.load(this@MusicService)
                        if (!msebParams.isFlat) {
                            oboeDirectPlayer?.setDspEnabled(true)
                            oboeDirectPlayer?.setMseb10Band(
                                MsebCalculator.calculateGains(msebParams),
                                MsebCalculator.BAND_FREQS,
                                MsebCalculator.BAND_QS
                            )
                            dspEqEnabled = true
                        }
                    }
                    // 【V8.19】DTS 环绕独立开关恢复（新建 OboeDirectPlayer 后声场系数需重设）
                    if (MsebCalculator.isDtsEnabled(this@MusicService)) {
                        val (ss, img) = MsebCalculator.dtsStageParams(this@MusicService)
                        oboeDirectPlayer?.setMsStage(ss, img)
                    }

                    // [V8.1] Always sync ExoPlayer playlist so ForwardingPlayer.getCurrentMediaItem()
                    // returns correct metadata (system notification / lock screen / car / Wear OS).
                    // In Oboe mode: update MediaItem without prepare() �?avoids CPU waste on
                    // parallel MediaCodec decoding since audio is driven by OboeDirectPlayer.


                    notifyPlayStateChanged(true)
                    notifySongChanged(song)
                    updateNotification()

                    // 【Crossfade】启动顺序自动交叉淡化监控（无条件启动，monitor 自行读 prefs 判断）
                    handler.removeCallbacks(crossfadeMonitor)
                    crossfadeBusy = false
                    crossfadeNextIndex = -1
                    crossfadeUiInfo = CrossfadeUiInfo()  // 【V8.4】新歌启动清 UI 状态
                    handler.postDelayed(crossfadeMonitor, 500)

                    handler.postDelayed({
                        if (fftCallback != null && !visualizerManager.isReady()) { visualizerManager.setup() }
                    }, 500)

                    val sampleRate = newPlayer.getSampleRate() ?: 0
                    val nativeRate = newPlayer.getSampleRateNative() ?: 0
                    val bitPerfect = sampleRate == nativeRate
                    val clipInfo = newPlayer.getClipDebugInfo() ?: ""
                    Log.i(TAG, "OboeDirect playing: ${song.title}, rate=${sampleRate}Hz, native=${nativeRate}Hz, bitPerfect=$bitPerfect, exclusive=${newPlayer.isExclusiveMode()}, $clipInfo")
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

    // 【2026-09-07】A2DP 编码前预补偿（蓝牙 SBC/AAC 高频瞬态补偿）
    // DAC 独占时跳过：USB 有线无蓝牙编码；Oboe/蓝牙路径下发到 native。
    // enabled = 设置页总开关；codecDb 由 BtCodecTracker 检测当前蓝牙编码决定
    // （SBC 2.0dB / AAC 0.8dB / LHDC·LDAC·有线 0 = 旁路）。
    fun applyBtPreEmphasis(enabled: Boolean) {
        if (isDacActive()) {
            oboeDirectPlayer?.setBtPreEmphasis(false, 0f)
            return
        }
        val db = BtCodecTracker.refresh(this)
        oboeDirectPlayer?.setBtPreEmphasis(enabled, if (enabled) db else 0f)
        Log.i(TAG, "BtPreEmphasis ${if (enabled) "ON" else "OFF"} codec=${BtCodecTracker.currentCodecName} db=$db")
    }

    // 路由变化/播放器重建后重发当前设置（保持开关状态与 codec 检测同步）
    fun refreshBtPreEmphasis() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean("bt_pre_emphasis", true)
        applyBtPreEmphasis(enabled)
    }

    /** 应用 MSEB 10 段 EQ — 根据当前播放模式路由到 Oboe 或 USB DAC 链路（共用同一套 Biquad） */
    fun applyMsebEq(gainsDb: FloatArray, freqsHz: FloatArray?, qValues: FloatArray?) {
        dspEqEnabled = true
        val f = freqsHz ?: FloatArray(gainsDb.size)
        val q = qValues ?: FloatArray(gainsDb.size) { 1.0f }
        if (isDacActive()) {
            UsbDacManager.setDspEnabled(true)
            UsbDacManager.setMseb10Band(gainsDb, f, q)
        } else {
            oboeDirectPlayer?.setDspEnabled(true)
            oboeDirectPlayer?.setMseb10Band(gainsDb, f, q)
        }
    }

    /** 关闭 MSEB / 重置为平坦 — 路由到当前播放模式对应的链路 */
    fun resetMsebEq() {
        dspEqEnabled = false
        if (isDacActive()) {
            UsbDacManager.resetMseb10Band()
            UsbDacManager.setDspEnabled(false)
        } else {
            oboeDirectPlayer?.resetMseb10Band()
            oboeDirectPlayer?.setDspEnabled(false)
        }
    }

    /** 【V8.3】应用 AutoEQ 10 段耳机修正 — 路由到 Oboe 或 USB DAC（与 MSEB 并存叠加，打底）。*/
    fun applyAutoEq(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float) {
        if (isDacActive()) {
            UsbDacManager.setAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preampDb)
        } else {
            oboeDirectPlayer?.setAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preampDb)
        }
    }

    /** 清除 AutoEQ 耳机修正 — 路由到当前播放模式对应的链路 */
    fun resetAutoEq() {
        if (isDacActive()) {
            UsbDacManager.resetAutoEq()
        } else {
            oboeDirectPlayer?.resetAutoEq()
        }
    }

    /** 【V8.3】应用 M/S 声场（跨声道矩阵）— 独立于 EQ，单独开关 */
    fun applyMsStage(soundstage: Float, imaging: Float) {
        if (isDacActive()) {
            UsbDacManager.setMsStage(soundstage, imaging)
        } else {
            oboeDirectPlayer?.setMsStage(soundstage, imaging)
        }
    }

    fun resetMsStage() {
        if (isDacActive()) {
            UsbDacManager.resetMsStage()
        } else {
            oboeDirectPlayer?.resetMsStage()
        }
    }

    /** 【V8.3】应用瞬态整形（时域，impulseResponse 维度映射，-1..+1）*/
    fun applyTransient(amount: Float) {
        if (isDacActive()) {
            UsbDacManager.setTransient(amount)
        } else {
            oboeDirectPlayer?.setTransient(amount)
        }
    }

    fun resetTransient() {
        if (isDacActive()) {
            UsbDacManager.resetTransient()
        } else {
            oboeDirectPlayer?.resetTransient()
        }
    }

    /** 【V8.3】动态压缩（Master Bus Compressor，独立全局模块）*/
    fun applyCompressor(enabled: Boolean, thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) {
        if (isDacActive()) {
            UsbDacManager.setCompressorEnabled(enabled)
            if (enabled) UsbDacManager.setCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
        } else {
            oboeDirectPlayer?.setCompressorEnabled(enabled)
            if (enabled) oboeDirectPlayer?.setCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
        }
    }

    fun resetCompressor() {
        if (isDacActive()) {
            UsbDacManager.setCompressorEnabled(false)
        } else {
            oboeDirectPlayer?.setCompressorEnabled(false)
        }
    }

    fun applyLoudness(enabled: Boolean, intensity: Float) {
        if (isDacActive()) {
            UsbDacManager.setLoudnessEnabled(enabled)
            if (enabled) UsbDacManager.setLoudnessIntensity(intensity)
        } else {
            oboeDirectPlayer?.setLoudnessEnabled(enabled)
            if (enabled) oboeDirectPlayer?.setLoudnessIntensity(intensity)
        }
    }

    fun resetLoudness() {
        if (isDacActive()) {
            UsbDacManager.setLoudnessEnabled(false)
        } else {
            oboeDirectPlayer?.setLoudnessEnabled(false)
        }
    }

    fun applyCrossfeed(amount: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setCrossfeed(amount)
    }

    fun resetCrossfeed() {
        if (isDacActive()) return
        oboeDirectPlayer?.resetCrossfeed()
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
        // 【2026-09-07 fix】用户主动 seek = 明确想听当前位置，不是自然播到尾部：
        // 中止尚未开始的 crossfade 预加载，否则拖到歌尾附近 remain<=dur+8000 会触发
        // 自动预加载下一首 + FLOOR 强制切歌 → 表现为"进度条拖不动/自己停住/跳歌"。
        // 已在进行中的 crossfade（crossfadeBusy=true，B 已接管混合）不打断。
        abortCrossfadePreload()
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

    /**
     * 【2026-09-07】中止 crossfade 预加载（用户主动 seek / 手动切歌时调用）。
     * 清空 B 轨预加载状态 + 能量低谷计数；若 B 轨已在后台 openIncoming 则释放其 fd。
     * 仅中止「未开始」的预加载；crossfadeBusy=true（B 已接管混合中）不动。
     */
    private fun abortCrossfadePreload() {
        // 正在进行的 crossfade 不打断
        if (crossfadeBusy) return
        // 【2026-09-07 hotfix】B 轨已上位成为 active（crossfade 已完成/进行中）时，
        // 残余的 xfadePreloadReady/xfadePreloading 只是陈旧标志——此时绝不能
        // stopIncoming()（会误杀当前播放轨，导致完全无法 seek）。只清标志。
        val bActive = try { oboeDirectPlayer?.isActiveB() == true } catch (_: Throwable) { false }
        if (bActive) {
            val stale = xfadePreloadReady || xfadePreloading || xfadePreloadPath != null
            xfadePreloading = false
            xfadePreloadReady = false
            xfadePreloadPath = null
            xfadePreloadSong = null
            xfadePreloadIndex = -1
            xfadeBeatPending = false
            xfadeDipCount = 0
            xfadePeakRms = 0f
            if (stale) Log.i(TAG, "Crossfade: stale preload flags cleared (B active, no stopIncoming)")
            return
        }
        val hadPreload = xfadePreloading || xfadePreloadPath != null || xfadePreloadIndex >= 0 || xfadePreloadReady
        if (!hadPreload && !xfadeBeatPending && xfadeDipCount == 0) return
        // A 仍为 active（crossfade 未真正开始）→ 安全停掉 B 轨已打开的预加载流
        if (xfadePreloadReady || xfadePreloading) {
            try { oboeDirectPlayer?.stopIncoming() } catch (_: Throwable) {}
        }
        xfadePreloading = false
        xfadePreloadReady = false
        xfadePreloadPath = null
        xfadePreloadSong = null
        xfadePreloadIndex = -1
        xfadeBeatPending = false
        xfadeDipCount = 0
        xfadePeakRms = 0f
        if (hadPreload) Log.i(TAG, "Crossfade: preload aborted by seek/switch")
    }


    /** 【v8.15】按当前模式选下一首 index：shuffle+bpm 匹配 / shuffle 纯随机 / 顺序。
     *  BPM 匹配只在随机播放开启且 settings shuffle_mode=bpm 时生效。 */
    private fun pickNextIndex(songs: List<Song>, realIdx: Int): Int {
        com.sdw.music.player.BpmKeyCache.init(this)  // 【V8.15】crossfade 也走 BPM 缓存（幂等，prefs==null 才初始化）
        if (songs.isEmpty()) return -1
        if (songs.size <= 1) return realIdx
        if (!isShuffleMode) {
            val ni = (realIdx + 1) % songs.size
            Log.i(TAG, "Crossfade: pickNext seq=$ni (shuffle off)")
            return ni
        }
        val pool = (0 until songs.size).filter { it != realIdx }
        val bpmMatch = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("shuffle_mode", "random") == "bpm"
        if (!bpmMatch) {
            val ni = pool.random()
            Log.i(TAG, "Crossfade: pickNext random=$ni (shuffle on, bpm off)")
            return ni
        }
        val cachePath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val cacheHit = if (cachePath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(cachePath) else null
        val curBpm = cacheHit?.first ?: 0
        if (curBpm !in 40..220) {
            val ni = pool.random()
            Log.i(TAG, "Crossfade: pickNext random=$ni (curBpm=$curBpm unknown, cachePath='$cachePath' hit=${cacheHit != null}, cacheSize=${com.sdw.music.player.BpmKeyCache.size()})")
            return ni
        }
        // 【V8.16】逐级放宽匹配窗口：±5 → ±10 → ±15 → ±20，最后才全池随机
        // 避免"快歌后慢歌"——匹配池空时仍尽量选节奏相近的歌
        val bpmOf: (Int) -> Int = { idx ->
            val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
            if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.first ?: songs[idx].bpm
            else songs[idx].bpm
        }
        // 【V8.25 防漂移】"第三首变慢歌"根因：容差内 random() 会随机到容差边缘的歌
        // （134±5 池里随机到 129 → 129 再匹配又放宽 → 三级后漂到慢歌）。
        // 【V8.26 防锁死】minByOrNull 永远返回差最小的第一首(index 最小) → 本轮排除它后
        // 又选第二小 → 恰好两首同 BPM 的歌来回切（用户报"只能两首歌切来切去"）。
        // 修复：容差内先找最小差 minDiff，再在 [minDiff, minDiff+2] 窄带内随机——
        // 节奏锚定不漂移，同时不锁死单曲。
        for (tolerance in intArrayOf(5, 10, 15, 20)) {
            val matched = pool.map { it to bpmOf(it) }
                .filter { (_, bpm) -> bpm in 40..220 && bpm in (curBpm - tolerance)..(curBpm + tolerance) }
            if (matched.isNotEmpty()) {
                val minDiff = matched.minOf { (_, bpm) -> kotlin.math.abs(bpm - curBpm) }
                val band = matched.filter { (_, bpm) -> kotlin.math.abs(bpm - curBpm) <= minDiff + 2 }
                val (ni, nb) = band.random()
                Log.i(TAG, "Crossfade: pickNext bpm=$ni (curBpm=$curBpm tol=$tolerance picked=$nb band=$minDiff..${minDiff + 2} bandSize=${band.size}/${matched.size})")
                return ni
            }
        }
        val ni = pool.random()
        Log.i(TAG, "Crossfade: pickNext random=$ni (no bpm within +-20, curBpm=$curBpm)")
        return ni
    }

    // 【V8.23】BPM 自适应 crossfade 时长：A/B 两轨 BPM 差 ≤2 → 2s 短混音（拍上交接）
    // 差 >2 → 返回用户设定时长（默认 5s，能量交叉长过渡）
    private fun adaptiveXfadeDur(preSong: Song?): Int {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        val userDur = cfg.getInt("crossfade_duration_ms", 5000)
        if (!isShuffleMode) return userDur
        if (cfg.getString("shuffle_mode", "random") != "bpm") return userDur  // 纯随机不搞 BPM 短混
        com.sdw.music.player.BpmKeyCache.init(this)
        val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val aBpm = if (aPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(aPath)?.first ?: 0 else 0
        val bBpm = if (bPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(bPath)?.first ?: 0 else 0
        if (aBpm in 40..220 && bBpm in 40..220) {
            val diff = kotlin.math.abs(aBpm - bBpm)
            // 【V8.24 过渡时长多档】BPM 越接近混音越短（DJ 式拍上交接）；差大保持长过渡
            val shortDur = when (diff) {
                0 -> 2000   // 完全同速：最短混音，拍上无缝
                1 -> 3000   // 微差：稍长但仍紧凑
                2 -> 4000   // 小差：中短混音
                else -> -1  // 差>2：走长过渡
            }
            if (shortDur > 0) {
                Log.i(TAG, "Crossfade: adaptive mix ${userDur}ms->${shortDur}ms (A=$aBpm B=$bBpm diff=$diff)")
                return shortDur
            }
            Log.i(TAG, "Crossfade: adaptive keep ${userDur}ms (A=$aBpm B=$bBpm diff=$diff)")
        }
        return userDur
    }

    fun playNext() {
        com.sdw.music.player.BpmKeyCache.init(this)
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
        val nextIndex = pickNextIndex(songs, realIdx)
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

    /** 【Crossfade】设置顺序自动交叉淡化开关（只 Oboe 路径）。 */
    fun setCrossfadeEnabled(enabled: Boolean) {
        crossfadeEnabled = enabled
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("crossfade_enabled", enabled).apply()
        if (enabled) {
            handler.removeCallbacks(crossfadeMonitor)
            if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                handler.postDelayed(crossfadeMonitor, 300)
            }
        } else {
            handler.removeCallbacks(crossfadeMonitor)
            crossfadeBusy = false
            crossfadeNextIndex = -1
            crossfadeUiInfo = CrossfadeUiInfo()  // 【V8.4】关闭时清 UI 状态
        }
        Log.i(TAG, "Crossfade: enabled=$enabled")
    }

    fun isCrossfadeEnabled(): Boolean = crossfadeEnabled

    fun setCrossfadeDurationMs(ms: Int) {
        crossfadeDurationMs = ms.coerceIn(1000, 15000)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("crossfade_duration_ms", crossfadeDurationMs).apply()
        Log.i(TAG, "Crossfade: duration=${crossfadeDurationMs}ms")
    }

    fun getCrossfadeDurationMs(): Int = crossfadeDurationMs

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






