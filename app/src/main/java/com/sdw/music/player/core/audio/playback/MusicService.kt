package com.sdw.music.player.core.audio.playback
import com.sdw.music.player.core.audio.dsp.DspSnapshot
import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.FileLog
import com.sdw.music.player.MainActivity
import com.sdw.music.player.core.audio.dsp.ParametricEqManager
import com.sdw.music.player.PlaylistManager
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import com.sdw.music.player.core.audio.analysis.AudioAnalyzer
import com.sdw.music.player.core.audio.analysis.BpmScanner
import com.sdw.music.player.core.audio.engine.BtCodecTracker
import com.sdw.music.player.core.audio.engine.DacProfile
import com.sdw.music.player.core.audio.dsp.EqualizerManager
import com.sdw.music.player.core.audio.dsp.LoudnessCache
import com.sdw.music.player.core.audio.dsp.LoudnessNormalizer
import com.sdw.music.player.core.audio.dsp.LoudnessTags
import com.sdw.music.player.core.audio.dsp.MasterSyncShadow
import com.sdw.music.player.core.audio.dsp.MsebCalculator
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer
import com.sdw.music.player.core.audio.playback.PlayerConnection
import com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
import com.sdw.music.player.core.audio.metadata.TagGainCache
import com.sdw.music.player.core.audio.dsp.TemplatePicker
import com.sdw.music.player.core.audio.engine.UsbDacManager
import com.sdw.music.player.core.audio.engine.UsbDacPlaybackController
import com.sdw.music.player.core.audio.playback.WaveformCache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
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
import com.sdw.music.player.core.util.floatValue
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sdw.music.player.core.audio.helpers.VolumeGuard
import com.sdw.music.player.core.audio.helpers.VisualizerManager
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

/** 【V8.274】服务未绑定时的哨兵原因（非空=失败）。仅用于判定成败，不直接展示给用户。 */
const val NO_SERVICE_REASON = "service_unbound"

class MusicService : MediaLibraryService() {
    /**
     * 【V8.364】普通模式音频链路· 运行期断言。
     *
     * 构建期闸门（tools/guard_plain_mode.py）能拦源码层面的连坐，但拦不住：
     *   · 运行时状态与预期不符（prefs 被外部改坏、快照缓存过期）
     *   · 将来新增的代码路径绕过静态检查
     * 所以在**每次真正下发音频参数之前**断言一次：DJ 关闭时，所有本该为 0/关闭的值
     * 必须真的是 0/关闭。不符就打 WARN 并把实际值带出来 —— 让异常在真机上可见，
     * 而不是变成「听感上好像有点怪」然后被放过。
     *
     * 这不是 assert（会在 release 里被消掉），是纯诊断：只打日志，不改行为、不抛异常。
     * **绝不在这里写任何修复逻辑** —— 一旦有了「自动纠正」，普通模式的行为就不再
     * 由代码唯一决定，那才是真正的不可控。
     */
    private fun assertPlainModeAudioInvariants(stage: String) {
        if (xfadeCfg().djMode) return          // DJ 模式不做任何断言
        val problems = ArrayList<String>(4)

        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val rubberbandOn = sp.getBoolean("crossfade_rubberband", true)
        if (rubberbandOn) {
            problems.add("rubberband=$rubberbandOn (普通模式应为引擎关闭)")
        }
        val tpl = runCatching { resolveXfadeTemplate() }.getOrDefault(-1)
        if (tpl != 0) {
            problems.add("tpl=$tpl (普通模式应为0 = 纯等功率)")
        }
        val fx = runCatching { resolveDjEffect(null) }.getOrNull()
        if (fx != null && fx.first != 0) {
            problems.add("djEffect=${fx.first} (普通模式应为 0)")
        }
        // NOTE: stemModeOn is deliberately NOT checked. Its KDoc says it mirrors
        // native's PREVIOUS round and is for logging only, so using it here would
        // produce false alarms. The real stem bypass is reported by
        // logTemplateBypassByStem() after the fact.
        if (problems.isNotEmpty()) {
            Log.w(TAG, "PLAIN-MODE VIOLATION at " + stage + ": "
                    + problems.joinToString(" | ")
                    + " -- plain-mode audio chain polluted by DJ capability (V8.363 constraint)")
        }
    }

    // ==========================================================================================
    // 【V8.363 最高优先级·不可回退】普通模式的音频链路已经稳定，任何改动不得影响它。
    //
    // 今天（8.300~8.362）二十余个版本、十几个 bug 换来的警戒线：
    //
    //  1) **DJ 关闭（crossfade_dj_enabled == false）时，音频链路是只读的。**
    //     普通模式 = 硬切 + 纯等功率交叉淡化，零 DJ 介入。8.362 真机实测已确认稳定：
    //       Beatmatch set: enabled=0   LiveFx enabled=0 type=0   Crossfade tpl=0
    //       XFADE-DONE shortA=0 shortB=0 underrun 不增长        hard cut 全程 0~3ms
    //
    //  2) **任何新功能先问一句：它在 djMode == false 时会改变音频吗？**
    //     会 -> 必须在函数内用 cfg.djMode 门控，不能只靠设置页把 UI 藏起来。
    //     今天的教训：crossfade_rubberband 的 UI 藏在折叠里、服务侧没检查 DJ 开关，
    //     结果普通模式仍然变速（8.360 修）。**UI 隐藏 != 服务生效。**
    //
    //  3) **不要在 crossfadeMonitor 守卫（enabled / isDacActive）上加新条件。**
    //     那是过渡引擎的入口，在那里加条件会连坐整个自动交叉淡化。
    //     8.357 在那里加了 !cfg.djMode，普通交叉淡化直接消失，
    //     用户报「开了交叉淡化开关，普通交叉淡化功能也没有了」（8.359 修）。
    //
    //  4) **普通模式不新增任何 DJ 相关开关。** 新能力一律先进 DJ 折叠 + 服务侧双门控。
    //
    //  5) 【V8.427 豁免·已登记】`bpmTierOf()` 读取 `crossfade_rubberband_max`
    //     只是借它的**档位口径给候选曲分档**（决定下一个候选落进哪一档），
    //     既不进 beatmatchParams()、也不进 native 变速链路 → **不改变任何速度/音高**。
    //     判据（第 2 条）问的是"会不会改变音频"，这里答案是"不会"，
    //     故不受 DJ 门控约束。源码处标了 GATE-EXEMPT，闸门脚本已登记该豁免。
    //
    // 判据一句话：**djMode == false 时的行为，必须与 8.362 之前的老播放器完全一致。**
    // ==========================================================================================
    private var mediaSession: MediaLibrarySession? = null
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

    // =====================================================================
    // 【v8.165 省电】crossfade 监控专用后台线程
    // ---------------------------------------------------------------------
    // 背景：crossfadeMonitor 原先是主线程 Handler 上每 200ms 无条件自调度的心跳
    // （"条件不满足也要重新调度自己"），每秒 5 次醒来读 5 次 SharedPreferences
    // 并做 BPM 查表 + JNI 查询。adb 实测：主线程 21.4% 单核、占进程 CPU 40.8%，
    // 与 AudioTrack 线程合计占进程 CPU 的 72%，而 batterystats 里 audio=0、
    // 耗电 94% 记在 CPU 头上。
    //
    // 三点改动：
    //   1) 整体挪到单条后台 HandlerThread（串行，不会并发 tick）
    //   2) 条件调度——没活干时降到 5s / 15s 心跳，不再空转
    //   3) settings 快照缓存，只有 crossfade_* / shuffle_mode 变化时才重读
    // =====================================================================
    private val xfadeThread = HandlerThread("xfade-mon", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val xfadeHandler by lazy { Handler(xfadeThread.looper) }

    // 【V8.179】seek 专用单线程：native seek（拿 g_decoderLifecycleMutex + flush 解码器）
    // 不占主线程/binder 线程，避免交叉切歌瞬间把 UI 一起堵住。串行执行天然合并连续拖动。
    private val seekExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sdw-seek").apply { isDaemon = true }
    }

    /**
     * 【V8.393】预扫描**并发恒为 1** 的单线程池。
     *
     * 为什么必须串行：native 侧每个 `preScanPath(mode 0/1/2/3/4)` 都会
     * `AMediaCodec_createDecoderByType` 新建一个一次性 codec、扫完即弃
     * （oboe_bridge.cpp:29675/30239/30751/31659/32227）。
     * 连点切歌时每首歌开播都要跑一次预扫描 ⇒ 20 刀就多出 20 个一次性 codec，
     * 直接把系统多媒体句柄榨干 —— 这是「连切几首后彻底切不动」的主要来源。
     *
     * 没有改成"预扫描共享一个常驻 codec"是因为那几处预扫描本来就在**并发线程**里跑，
     * 共享单实例会引入竞态；串行化既安全又同样把同时存活的 codec 数压到 1。
     */
    private val prescanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sdw-prescan").apply { isDaemon = true }
    }

    /** 监控心跳间隔：活跃(播放中且开关开) / 空闲(暂停中) / 关闭(开关关) */
    private val XFADE_TICK_ACTIVE_MS = 500L
    private val XFADE_TICK_IDLE_MS = 5_000L
    private val XFADE_TICK_OFF_MS = 15_000L

    /** 【V8.429】自动过渡的触发提前量（ms）。
     *  触发条件从 `remain <= dur` 放宽到 `remain <= dur + 本值`。
     *  取 5000 = 覆盖「500ms monitor 轮询」+「B 轨预加载握手 ~0.5s」+「拍点对齐最多等一整小节
     *  (100BPM≈2.4s，慢歌 60BPM 可达 4s 由下方 maxDelay 兜底不强行对齐)」+ 余量，
     *  保证真正进 doCrossfadeTrigger 时 remain 仍 ≥ dur + 安全余量，那圈 clamp 不削短、也不 overrun。
     *  必须小于预加载窗口的 12000ms 余量，否则预加载还没就绪。 */
    private val XFADE_TRIGGER_LEAD_MS = 5_000L
    /** 【V8.498】过渡时长硬上限安全余量（ms）。startCrossfade 被调用后，native 还要等
     *  XF_PREFILL_MAX_WAIT_MS(800ms) 攒齐 B 轨整段 PCM 才真正开始增益斜坡，末端还有 ~43ms
     *  de-click 拖尾。因此「过渡时长」必须比 A 轨剩余可播时长至少小这么多，否则 A 在过渡结束前
     *  EOS → 中段增益仍很大却突然静音 → "哒"/白噪音；且 B 来不及接管 → 下一首卡住。
     *  旧值 300ms 远不够覆盖 800ms 预填充，正是 16 拍长过渡"播完才切/白噪音"的根因。 */
    private val XFADE_ARM_SAFETY_MS = 1_500L
    /** 【V9.0】触发提前量随过渡时长放大：短过渡保底 5s，长过渡（16 拍/16 小节可达数十秒）
     *  给到 dur/2（封顶 12s），确保即便经过 500ms 轮询 + B 预加载握手 + 最多一个小节拍点对齐延迟，
     *  真正进 doCrossfadeTrigger 时 remain 仍 ≥ dur + 安全余量，过渡不被削短也不 overrun。 */
    private fun xfadeTriggerLead(durMs: Int): Long =
        maxOf(XFADE_TRIGGER_LEAD_MS, minOf(12_000L, (durMs * 0.5).toLong()))

    /** 【V8.312·A】自动切歌最小间隔（ms）。上一次自动过渡落点后，这段时间内不再发起
     *  下一轮自动过渡。0 = 不限（想回到旧的紧追行为时可设 0）。
     *  注意读的是 settings prefs（与设置页同一份），不是常量 —— 用户要能调。 */
    private fun autoXfadeMinGapMs(): Long {
        val raw = getSharedPreferences("settings", MODE_PRIVATE).getInt("crossfade_min_gap_sec", 25)
        return raw.coerceIn(0, 120) * 1000L
    }

    /** settings 快照：避免每 tick 读 5 次 SharedPreferences */
    private data class XfadeCfg(
        val enabled: Boolean,
        val durMs: Int,
        val bars: Int,
        val smartAlign: Boolean,
        val alignPreferBeat: Boolean,
        val bpmChorus: Boolean,
        // 【V8.357】Master switch for the DJ surface. When false the player is the
        // plain one: no ⚡/⏭ buttons, no automatic transition, no beat jump --
        // hard cuts only, i.e. the pre-DJ behaviour, which is the stable one.
        val djMode: Boolean
    )

    @Volatile private var xfadeCfgCache: XfadeCfg? = null

    // 【V8.194】shuffle_mode 三态（唯一出处：settings prefs 的 string）
    //   "off"     = 顺序播放
    //   "random"  = 纯随机
    //   "bpm"     = 随机 + BPM 匹配（旧的 putString("true") 就是它）
    // 注意：不能写成 const val —— 这个类只允许有一个 companion object，常量并进去即可。
    private val SHUFFLE_MODE_OFF = "off"
    private val SHUFFLE_MODE_BPM = "bpm"

    /** 【V8.194】把遗留/异常值统一成三态之一，任何读侧都必须过这一道。 */
    private fun normalizeShuffleMode(raw: String?): String = when (raw) {
        SHUFFLE_MODE_BPM, "true" -> SHUFFLE_MODE_BPM
        SHUFFLE_MODE_OFF, "false" -> SHUFFLE_MODE_OFF
        "random", "" -> "random"
        else -> "random"   // null / 未设置 / 新值 → 沿用 UI 默认（纯随机）
    }

    private val xfadePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // key == null 表示整表变化（clear），一并失效
        if (key == null || key.startsWith("crossfade_") || key == "shuffle_mode") {
            xfadeCfgCache = null
            // 开关被打开时立刻唤醒一次，否则要等最长 15s 的心跳才生效
            if (key == "crossfade_enabled") scheduleCrossfadeMonitor(0L)
        }
    }

    /**
     * 【V8.248】落点模式（合并原 smart_align / align_prefer / automix 三个键）：
     * standard = 标准（小节线进场，无智能落点）
     * beat     = 拍点（智能对齐：预扫静音段/副歌选更优落点，落点偏好由 crossfade_align_prefer 细分）
     * phrase   = 乐句（djay Automix 式：B 从自己的 8 小节乐句起点进 + 智能对齐）
     * 首次读取时从老键一次性迁移（automix=true→phrase；smart_align=true→beat；否则 standard）。
     */
    private fun landingModeOf(cf: android.content.SharedPreferences): String {
        val saved = cf.getString("crossfade_landing_mode", null)
        if (saved != null) return saved
        val migrated = when {
            cf.getBoolean("crossfade_automix", true) -> "phrase"
            cf.getBoolean("crossfade_smart_align", true) -> "beat"
            else -> "standard"
        }
        cf.edit().putString("crossfade_landing_mode", migrated).apply()
        return migrated
    }

    private fun xfadeCfg(): XfadeCfg {
        xfadeCfgCache?.let { return it }
        val cf = getSharedPreferences("settings", MODE_PRIVATE)
        val landing = landingModeOf(cf)
        val cfg = XfadeCfg(
            enabled = cf.getBoolean("crossfade_enabled", true),
            durMs = cf.getInt("crossfade_duration_ms", 5000),
            bars = cf.getInt("crossfade_bars", 4),
            // 【V8.248】由落点模式派生：beat/phrase 开智能对齐；拍点优先仅 beat 模式且细分选了 beat
            smartAlign = landing != "standard",
            alignPreferBeat = landing == "beat" &&
                cf.getString("crossfade_align_prefer", "quiet") == "beat",
            // 【V8.194】遗留的 boolean 迁移值 "true"=bpm / "false"=off 一并归一
            bpmChorus = normalizeShuffleMode(cf.getString("shuffle_mode", null)) == SHUFFLE_MODE_BPM,
            djMode = cf.getBoolean("crossfade_dj_enabled", true)
        )
        xfadeCfgCache = cfg
        return cfg
    }

    /** 统一调度入口：永远只保留一个待执行的 monitor，杜绝重复心跳 */
    private fun scheduleCrossfadeMonitor(delayMs: Long) {
        xfadeHandler.removeCallbacks(crossfadeMonitor)
        xfadeHandler.postDelayed(crossfadeMonitor, delayMs.coerceAtLeast(0L))
    }

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

    // [P2] Silence watchdog for auto-probe (unknown) DACs. After a Host Exclusive
    // attempt on an unknown DAC, if the native stream isn't actually draining data
    // (no URB completions / health=wedge) we treat the path as silent/dead and fall
    // back to the safe Oboe system-route — remembered for the rest of the session.
    private const val DAC_SILENCE_WINDOW_MS = 3500L
    private val dacSilenceRunnable = object : Runnable {
        private var armedGen = -1
        private var armedVid = 0
        private var armedPid = 0
        private var armedIndex = -1
        private var armedSongs: List<Song>? = null
        private var baselineCompleted: Long = 0

        fun arm(gen: Int, vid: Int, pid: Int, index: Int, songs: List<Song>, baseline: Long) {
            armedGen = gen; armedVid = vid; armedPid = pid
            armedIndex = index; armedSongs = songs; baselineCompleted = baseline
        }
        fun reset() {
            armedGen = -1; armedVid = 0; armedPid = 0; armedIndex = -1
            armedSongs = null; baselineCompleted = 0
        }

        override fun run() {
            val gen = armedGen
            if (gen < 0) return
            // Staleness guards: track changed, already forced Oboe, or stream stopped.
            if (gen != dacPlayGeneration) { reset(); return }
            if (UsbDacManager.isForcedOboe(armedVid, armedPid)) { reset(); return }
            if (!UsbDacManager.isStreaming()) { reset(); return }
            val liv = UsbDacManager.getStreamLiveness() ?: run {
                handler.postDelayed(this, 400); return   // native not ready, re-poll shortly
            }
            val delta = liv.urbCompleted - baselineCompleted
            // [P2] dead/silent path: no URB completed in the whole window, or native flagged wedge.
            if (delta <= 0L || liv.health == "wedge") {
                Log.w(TAG, "DAC auto-probe silence: vid=${armedVid.toString(16)} pid=${armedPid.toString(16)} " +
                    "delta=$delta health=${liv.health} → fallback Oboe")
                DebugLog.add(TAG, "DAC 自动探测静音/卡死 → 回退 Oboe 系统路由（本会话该 DAC 不再试 Host 独占）")
                fallbackToOboeSystemRoute(armedIndex, armedSongs ?: servicePlaylist, "auto-probe 静音")
                reset()
            }
        }
    }

    /**
     * [P2] Fall back from a (silently dead) Host Exclusive attempt to the safe Oboe
     * system-route. Marks the DAC session-Oboe so subsequent tracks skip the probe.
     */
    private fun fallbackToOboeSystemRoute(index: Int, songs: List<Song>, reason: String) {
        val device = UsbDacManager.getDacDevice()
        if (device != null) UsbDacManager.forceOboeFor(device.vendorId, device.productId)
        handler.removeCallbacks(dacHealthRunnable)
        handler.removeCallbacks(dacSilenceRunnable)
        releaseUsbDacController()
        UsbDacManager.stopAndRelease()
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("audio_output", "AAudio (Direct)").apply()
        refreshOboeModeCache()
        playSongOboeDirect(index, songs)
        notifySongChanged(songs[index])
        handler.post {
            try {
                android.widget.Toast.makeText(
                    this@MusicService,
                    "DAC 自动回退到 Oboe 系统路由（$reason）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) { /* ignore */ }
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
            .setSmallIcon(R.drawable.ic_notif_brand)
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
            val claimed = com.sdw.music.player.core.audio.engine.UsbDacManager.isClaimed()
            DebugLog.add(TAG, "VolumeGuard callback: pct=$pct claimed=$claimed")
            if (claimed) {
                UsbDacManager.setVolume(pct)
            }
        }
    }

    // 
    // 【V8.180】后台线程（playSongOboeDirect）整体替换这个引用，主线程（binder/UI/通知）
    // 会读它做 pause/resume/seek/进度/下发 DSP —— 不加 @Volatile 就是跨线程无同步发布，
    // 主线程可能长时间读到旧对象或已被 release 的实例（暂停无效、进度回零、设置下发丢）。
    @Volatile
    var oboeDirectPlayer: OboeDirectPlayer? = null
    private val oboeSwitchLock = Any()  // 串行化切歌线程，防 native 全局状态并发损坏
    private var useOboeDirect: Boolean = false

    // =====================================================================
    // 【CROSSFADE ENGINE 区】顺序自动交叉淡化（只 Oboe 路径）
    // 状态字段 181-211 · monitor 228-487 · 触发 489-534 · 完成轮询 536-610
    // 宿主依赖：oboeDirectPlayer/handler/currentSong/servicePlaylist/pickNextIndex/
    //           updateNotification/isDacActive/resolveContentUriToPath 等
    // 设计：两阶段 preloading + 拍点对齐 + 副歌预扫 + 能量低谷检测 + FLOOR 兜底
    // =====================================================================

    // 【Crossfade】顺序自动交叉淡化（只 Oboe 路径，方案 B：先 mix 再 DSP）
    private var crossfadeEnabled = false
    private var crossfadeDurationMs = 5000
    private var crossfadeBars = 4   // 【V8.152/V8.415】过渡长度按小节：2/4/8/16
    /** 【V8.415】小节折算的上限。原先是 20000ms，导致新增的「16 小节」在 120BPM 下
     *  （自然应 ~32s）被截成 20s，名不副实（实际只有 ~10 小节）。提到 32s 让 16 小节成立。 */
    private val kMaxXfadeMs = 32000
    @Volatile private var crossfadeBusy = false  // 一次 crossfade 进行中（防重复触发）
    /**
     * 【V8.428】busy 脏值判定上限：单次过渡最长 15s（durationMs.coerceIn(1000, 15000)），
     * 超过这个时间还处于 busy 就认为是上一轮残留（DJ 被关 / 轮询被掐 / 窗口没再触发），
     * 在 onCompletion 里强制复位并正常切歌，避免「播完不接下一首」。
     */
    private val MAX_STALE_XFADE_BUSY_MS = 20_000L
    private var crossfadeBusySinceMs = 0L        // 【V8.271】置位时刻，用于卡死自愈
    /** 【V8.381】本轮 crossfade 是否已真正进入 native 过渡(startCrossfade 成功)。
     *  看门狗据此区分"触发线程卡死(从未 startCrossfade)"与"native 过渡正常进行中"，
     *  避免把正常的长过渡(如 bars=32)误杀导致切歌中途被打断。 */
    @Volatile private var crossfadeNativeStarted = false
    /** 【V8.383】手动切歌那个开轨线程是否已跑完（含失败返回）。
     *  用来识别「线程卡在 native openIncoming 里永远回不来」这种僵局。 */
    @Volatile private var manualXfadeOpenDone = true
    /** 【V8.384】上一刀开轨线程的发起时刻。用来判断「它还在飞」还是「已经卡死超时」。 */
    @Volatile private var manualXfadeOpenSinceMs = 0L
    /** 【V8.383】每次手动开轨领一个世代号，让「上一刀的兜底定时器」不会误判下一刀的状态。 */
    @Volatile private var manualXfadeGen = 0
    @Volatile private var pendingManualTrigger = false  // 【V8.271】过渡中被手动点了一下 → 结束后自动补切
    @Volatile private var pendingStereoBalance: Float? = null   // V8.348: balance requested before the player existed
    private var crossfadeNextIndex = -1  // 已预加载的下一首索引（crossfade 完成后切元数据）

    // 【V8.312·A】自动切歌最小间隔闸门。上一次**自动**过渡的落点时刻（elapsedRealtime）。
    // 症状：入场点选得太靠后（B 一进来就接近 EOS）→ remain<=dur 立刻成立 → 落点后
    // 200ms 重挂 monitor 又触发 → 无限连锁切歌。闸门从根上否掉「几秒内连切两首」，
    // 这在 djay 里也是明确避免的行为（自动化也需要呼吸空间）。
    // 只拦自动触发；手动点「立即切歌」是用户明确意图，不受限流。
    private var lastAutoXfadeDoneAtMs = 0L
    private fun autoXfadeCooldownRemainingMs(): Long {
        if (lastAutoXfadeDoneAtMs == 0L) return 0L
        val elapsed = SystemClock.elapsedRealtime() - lastAutoXfadeDoneAtMs
        val cool = autoXfadeMinGapMs() - elapsed
        return if (cool > 0L) cool else 0L
    }
    // 【能量切点】crossfade 触发窗口内的能量低谷检测（避开鼓点/高潮硬叠打架）
    private var xfadeDipCount = 0        // 连续低谷采样计数（200ms/采样）
    private var xfadePeakRms = 0f        // 窗口内能量峰值（带衰减）
    /** 【V8.390 诊断】monitor tick 计数器，用于把 beat-quantized 分支的守卫值节流打点。 */
    private var xfadeMonitorTickCount = 0
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
    private var bEntryMode = "intro"   // 【V8.422】B 入场点模式：intro(djay 式, 默认) / chorus(旧行为)

    /**
     * 【V8.446】手动切歌的起播偏移（ms）。-1 = 不偏移（从0:00 播）。
     *
     * 用户诉求："手动切歌也是要和自动切歌逻辑一样，从进歌点进。
     * 4 拍 8 拍只是过渡时长，不是进入时间点。"
     *
     * 原来手动 ⏭ 走 `playNext -> playSong`，从头播；而自动交叉淡化走
     * `doCrossfadeTrigger -> resolveBEntryMs`，从 Drop/副歌/首拍/进歌点进。
     * 两条路径入场点不一致 —— 这正是用户说的"逻辑搞错了"。
     *
     * 口径统一：**入场点只由 resolveBEntryMs 决定；crossfadeBars（小节数）
     * 只参与过渡时长计算，绝不参与入场点选择。**
     */
    @Volatile private var nextSongEntryMs = -1L
    // 【V8.170】响度统一管理：当前生效的归一化增益（dB），仅作记录/诊断
    @Volatile private var currentNormDb = 0f
    // 【V8.142】B 轨开头前奏静音段长度(ms)：openIncoming 后预扫 mode=1；-1=无静音前奏
    @Volatile private var xfadeBIntroMs = -1L
    // 【V8.431】B 轨「能量点」进场落点（Drop）。warmDjStructure 早就算出来了，
    // 但之前只用于画UI 标记，从没参与入场点选择 —— 于是 B 永远从 firstBeat 进。
    @Volatile private var xfadeBDropMs = -1L
    @Volatile private var xfadeBPrescanDone = false
    // 【V8.142 智能对齐】A 轨首个强拍位置(ms)：触发延迟用它对齐真实拍点（原 pos%beatMs 假设重拍在 t=0）
    @Volatile private var xfadeABeatPhaseMs = -1L
    // 【V8.142 智能对齐】B 轨首个强拍位置(ms)：B seek 点吸附到自身拍栅格，实现 A/B 拍上交接
    @Volatile private var xfadeBFirstBeatMs = -1L
// 【V8.15】B 轨副歌起点(ms)：预扫 mode=2 得到，crossfade 时 B 从副歌进；-1=未找到
private var xfadeBChorusMs = -1L
    // 【V8.15】两阶段 crossfade：preloading 提前（候选窗口），预扫完成后才触发
    @Volatile private var xfadePreloading = false
    @Volatile private var xfadePreloadReady = false  // 【2026-09-02】B 轨 openIncoming 后台完成标志
    // 【V8.24 A 轨拍点触发】拍点延迟已安排标志（防 monitor 每 tick 重复 postDelayed）
    @Volatile private var xfadeBeatPending = false          // 正在预加载 B（尚未触发）
    // 【V8.183 拍点触发可取消】在飞的拍点延迟 Runnable + 递增令牌。
    // 原先只有 xfadeBeatPending 一个布尔位：手动切歌时清的是 xfadeHandler 队列，
    // 而这枚回调是走主线程 handler 的，清不掉 —— 于是用户点「下一首」之后，
    // 那枚旧回调照常在 beatDelay 到点时拿着【已经丢掉的 B 轨】发起 crossfade，
    // 还把 crossfadeBusy 重新置 true，把新歌锁进上一次过渡的状态里。
    private var xfadeBeatRunnable: Runnable? = null
    @Volatile private var xfadeBeatToken = 0
    @Volatile private var xfadePreloadPath: String? = null // 预加载中的 B 路径
    @Volatile private var xfadePreloadIndex = -1           // 预加载中的 B 索引
    @Volatile private var xfadePreloadSong: Song? = null   // 预加载中的 B 歌曲
    // [2026-09-10 fix] Pending B-track metadata for the crossfade handover. Native
    // crossfade duration is deterministic: once startCrossfade() returns true the audio
    // WILL be on B after durationMs. So on poll timeout we must still advance metadata,
    // otherwise currentSong stays on A while B is audibly playing (cover/title mismatch).
    @Volatile private var xfadePendingSong: Song? = null
    @Volatile private var xfadePendingIndex: Int = -1

    // 【V8.182 过渡可视化】过渡期间 60ms 采样一次 native 的 crossfade 进度和 A/B 两轨电平，
    // 写进 crossfadeUiInfo 供播放页画「过渡进度条 + 两轨电平」。只在 ACTIVE/DONE 窗口跑，
    // 窗口外自动停并将数值归零 —— 平时零开销。
    private var xfadeLevelRunnable: Runnable? = null
    private fun startXfadeLevelSampler() {
        stopXfadeLevelSampler()
        val r = object : Runnable {
            override fun run() {
                val st = crossfadeUiInfo.state
                if (st != XfadeUiState.ACTIVE) {
                    // DONE 阶段保留最后一帧（封面要接着走淡出），但不再刷新数值
                    stopXfadeLevelSampler()
                    return
                }
                val lv = try { oboeDirectPlayer?.getXfadeLevel() } catch (_: Throwable) { null }
                if (lv != null && lv.size >= 3) {
                    val cur = crossfadeUiInfo
                    if (cur.state == XfadeUiState.ACTIVE) {
                        crossfadeUiInfo = cur.copy(
                            xfProgress = lv[0].coerceIn(0f, 1f),
                            rmsA = lv[1].coerceIn(0f, 1f),
                            rmsB = lv[2].coerceIn(0f, 1f)
                        )
                    }
                }
                handler.postDelayed(this, 60)
            }
        }
        xfadeLevelRunnable = r
        handler.post(r)
    }
    private fun stopXfadeLevelSampler() {
        xfadeLevelRunnable?.let { handler.removeCallbacks(it) }
        xfadeLevelRunnable = null
        val cur = crossfadeUiInfo
        if (cur.xfProgress != 0f || cur.rmsA != 0f || cur.rmsB != 0f) {
            crossfadeUiInfo = cur.copy(xfProgress = 0f, rmsA = 0f, rmsB = 0f)
        }
    }

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
        val nextAccentColor: Long = 0L,     // B 轨主色（0 = 未提取）
        // 【V8.139】native crossfade 真正启动时刻（SystemClock.elapsedRealtime）。
        // UI 用它做相位对齐：画面过渡从「已经过去多少 ms」续接，而不是从收到状态那刻从 0 重跑，
        // 从而消除 200ms 轮询量化造成的「画面比音频慢半拍」。0 = 尚未启动。
        val startedAtMs: Long = 0L,
        // 【V8.182 过渡可视化】ACTIVE 阶段由 native 侧实时回填（60ms 一次）：
        // xfProgress = 过渡进度 0..1；rmsA / rmsB = A/B 两轨慢速电平（0..1，给两轨电平条用）。
        // 非过渡期恒 0，UI 据此收起可视化，零开销。
        val xfProgress: Float = 0f,
        val rmsA: Float = 0f,
        val rmsB: Float = 0f,
        // V8.367 decision output -- lets the DJ surface show what the engine is thinking.
        // beatmatchParams() already computed these; it just handed them to native and
        // dropped them. djay Automix shows exactly this ("87 -> 111 BPM", "-7.5%") and it
        // is the single biggest reason its UI reads as intelligent: you see WHY this track
        // was chosen and HOW the two will be aligned.
        //   aBpm / bBpm = each track's own tempo (0 = not analysed)
        //   targetBpm   = the tempo both converge to after stretching
        //   ratioPct    = B relative to A, in percent (djay shows -7.5%)
        //   pickReason  = one-line justification for the choice
        val aBpm: Float = 0f,
        val bBpm: Float = 0f,
        val targetBpm: Float = 0f,
        val ratioPct: Float = 0f,
        val pickReason: String = ""
    )
    @Volatile var crossfadeUiInfo = CrossfadeUiInfo()
        private set

    private val crossfadeMonitor = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            // 【v8.165 省电】settings 走快照缓存，不再每 tick 读 5 次 SharedPreferences
            // （prefs 变化由 xfadePrefsListener 失效缓存并按需唤醒）
            val cfg = xfadeCfg()
            val enabled = cfg.enabled
            val durRaw = cfg.durMs
            val barsCfg = cfg.bars   // 【V8.152】按小节过渡
            // 【V8.142】落点偏好：quiet=静音段优先(默认，保留原行为) / beat=拍点优先
            val alignPreferBeat = cfg.alignPreferBeat
            // 【V8.17】纯随机模式（shuffle_mode=random）禁用副歌逻辑，回归定时+能量低谷
            bpmChorusMode = cfg.bpmChorus
            crossfadeEnabled = enabled
            crossfadeBars = barsCfg
            bEntryMode = getSharedPreferences("settings", MODE_PRIVATE).getString("crossfade_b_entry", "auto") ?: "auto"

            // 【v8.165 省电·条件调度】
            // 原实现注释写着「无条件心跳：条件不满足也要重新调度自己」，即每秒 5 次
            // 无条件醒来。改为：没活干时降到低频心跳——
            //   开关关 / DAC 独占路径（不走 Oboe crossfade）→ 15s，仅保持 monitor 存活；
            //   暂停中 → 5s。
            // 「monitor 永久停死」的顾虑由两处兜底：settings 监听器（开关被打开时
            // 立即 schedule(0)）以及每次 playSong() 都会重新 schedule(500ms)。
            // 【V8.359 修正 V8.357 的误伤】这里**不能**加 !cfg.djMode。
            //
            // V8.357 把「普通/ DJ 两套界面」实现成「DJ 关闭 = monitor 整个静默」，
            // 结果把普通交叉淡化也一起关掉了 —— 用户反馈「开了交叉淡化开关，
            // 普通交叉淡化功能也没有了」。这是把『界面/ 效果』的开关当成了
            //『过渡引擎』的总闸。
            //
            // 正确分层：
            //   · crossfade_enabled（总开关）→ 过渡引擎跑不跑            ← 归本行管
            //   · crossfade_dj_enabled（DJ 开关）→ 界面长什么样 +挂不挂效果 ← 归各自函数管
            // DJ 关闭时效果层已由三处独立门控为纯等功率，无需在此处停引擎：
            //   xfadeTemplateActive() 5438 → tpl=0（无模板）
            //   resolveDjEffect()     5665 → 0 to 0f（无效果）
            //   applyLiveFx / setLiveFxBus / bpmFallActive 5693/5744 → 总线旁路
            if (!enabled || isDacActive()) {
                scheduleCrossfadeMonitor(XFADE_TICK_OFF_MS)
                return
            }
            if (!isPlaying()) {
                scheduleCrossfadeMonitor(XFADE_TICK_IDLE_MS)
                return
            }
            // 【V8.155】触发阈值/预加载窗口必须用「真实生效时长」：
            // 按小节时 = bars*4*(60000/A BPM)，否则用秒数档。
            // 旧版这里恒用秒数(dur)，8 小节实混 ~15s 却仍等 remain<=5s 才触发 →
            // B 进场太晚甚至 A 播完才进（用户报「播放结束了才进场」）。
            // 【V8.414】durRaw 在「小节模式」下是 0（V8.413 起的哨兵值）。若 barsToMsOrNull
            // 又因为「A 轨没测出 BPM」返回 null，就会得到 dur=0 → 触发阈值退化成 remain<=0
            // → 过渡永远迟到、A 播完才切 → 再被 clamp 成剩余时长（用户听到「2 秒就切」）。
            // 兜底口径必须与 adaptiveXfadeDur 的兜底一致（同样按小节 + 假定 120BPM 折算），
            // 否则监控的触发提前量与实际过渡时长对不上，落点仍会被 clamp 削短。
            val dur = (barsToMsOrNull() ?: durRaw).let { d ->
                if (d > 0) d
                else {
                    val b = getSharedPreferences("settings", MODE_PRIVATE)
                        .getInt("crossfade_bars", 4)
                    if (b in 1..32) (b * 4 * (60000f / 120f)).toInt().coerceIn(1500, kMaxXfadeMs) else 8_000
                }
            }
            val smartAlign = cfg.smartAlign
            crossfadeDurationMs = dur
            // 智能对齐关闭时禁用预扫描分支（回退实时 RMS 检测）
            if (!smartAlign) {
                xfadeAPrescanDone = false; xfadeBPrescanDone = false
                xfadeABeatPhaseMs = -1L; xfadeBFirstBeatMs = -1L
            }
            // 纯随机模式：禁用副歌相关状态（A 副歌触发、B 副歌起点）
            if (!bpmChorusMode) { xfadeAChorusEndMs = -1L; xfadeBChorusMs = -1L }
            var triggered = false
            if (enabled && !crossfadeBusy) {
                val p = oboeDirectPlayer
                if (p != null && p.isPrepared) {
                    val totalDur = p.getDurationMs()
                    val pos = p.getCurrentPositionMs()
                    // V8.350 DIAG: this path fails SILENTLY when the player is not
                    // usable (p == null / !isPrepared / duration <= 0) -- we fall
                    // through and reschedule with nothing in logcat, so "it never
                    // auto-crossfades" leaves no trail. Log the reason every tick.
                    if (p == null) Log.i(TAG, "XF-MON: player null")
                    else if (!p.isPrepared) Log.i(TAG, "XF-MON: not prepared")
                    else if (p.getDurationMs() <= 0L) Log.i(TAG, "XF-MON: duration<=0")
                    else {
                        val _remain = p.getDurationMs() - p.getCurrentPositionMs()
                        val _dur = barsToMsOrNull() ?: durRaw
                        Log.i(TAG, "XF-MON tick remain=${_remain}ms dur=${_dur}ms busy=$crossfadeBusy")
                    }
                    if (totalDur > 0) {
                        val remain = totalDur - pos
                        // 【V8.498】兜底强制触发点。上调到 2500ms：配合 XFADE_ARM_SAFETY_MS(1500)，
                        // 即便落到 FLOOR 兑底也还能留出 ~1s 真实过渡，而非退化为硬切；
                        // 正常路径由 remain<=dur+lead 更早接管，FLOOR 只在预加载迟滞时兜底。
                        val FLOOR_MS = 2500
            val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            var shouldTrigger = false
            // 【V8.216】确定性触发：按小节/时长设了过渡长度，进入窗口(remain<=dur)就切，
            // 不再等能量低谷/静音段（高潮曲永远等不到低谷 → 拖到结尾才切，用户报"播完了才自动切"）。
            // 下方 smartAlign/副歌/静音段逻辑作为更优落点的提前触发，但绝不能把触发拖过 remain<=dur。
            if (remain <= dur) {
                shouldTrigger = true
                Log.i(TAG, "Crossfade: window trigger pos=$pos remain=$remain dur=$dur")
            }
            // 【V8.429 修"过渡太晚/听着像结束才切"】给触发窗口加一段提前量。
            // 旧逻辑是 remain<=dur 才触发 = 过渡**刚好占满**剩余时间，零余量：
            //   · monitor 500ms 一跳（XFADE_TICK_ACTIVE_MS），最坏 500ms 才发现；
            //   · 之后还要过"预加载已就绪"与拍点对齐（最多等一整小节 ~2.4s）；
            //   · 再叠加 doCrossfadeTriggerBg 里那圈 clamp（remainNow-300）必然削短时长。
            // 三项叠加就表现为"过渡刚开一半就到歌尾" → 听着像没接上。
            //
            // 提前量随过渡时长放大（xfadeTriggerLead）：短过渡 2.5s，16 拍/16 小节长过渡给到 dur/2，
            // 覆盖 500ms 轮询 + 最多一个小节拍点对齐延迟，使真正进 doCrossfadeTrigger 时
            // remain 仍 ≥ dur + XFADE_ARM_SAFETY_MS，过渡既不被削短也不 overrun。
            else if (remain <= dur + xfadeTriggerLead(dur)) {
                shouldTrigger = true
                Log.i(TAG, "Crossfade: EARLY window trigger pos=$pos remain=$remain dur=$dur lead=${XFADE_TRIGGER_LEAD_MS}ms")
            }
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
                        // 【V8.390 诊断】beat-quantized 分支的 4 个守卫，逐个打出来。
                        // 为什么需要：V8.390 两次真机验证都**没看到量化行**，说明这个 if 根本没进，
                        // 但不知道是哪个守卫不满足。继续"等久一点"不会产生新信息，
                        // 必须先把守卫值摊开。节流：每 20 次 tick 打一次。
                        if ((xfadeMonitorTickCount++ % 20) == 0) {
                            Log.i(TAG, "XFDG: guard smartAlign=$smartAlign preferBeat=$alignPreferBeat " +
                                "prescanDone=$xfadeAPrescanDone beatPhase=${xfadeABeatPhaseMs} " +
                                "quietStart=$xfadeAQuietStartMs pos=$pos remain=$remain dur=$dur")
                        }
                        // 【V8.142 拍点优先】不等静音段，进 window 即可触发，由阶段2拍相位延迟精确定位
                        if (smartAlign && alignPreferBeat && xfadeAPrescanDone && xfadeABeatPhaseMs > 0) {
                            // 【V8.390 乐句量化触发】原来是纯绝对时间 `remain <= dur` 就切 ——
                            // dur 到了就动手，完全不管那一刻卡在鼓点正中还是弱拍上，
                            // 两首歌节奏就此错位（"像两只乐队在各自打架"）。
                            // 现在把启动时刻**向上量化到最近的小节线**（4/4 强拍）。
                            // 安全性：量化只会把启动**往后**推（不会提前），所以
                            //   ① 绝不会导致过渡跑不完；② 仍保留下面的 FLOOR 兜底。
                            val ap0 = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                            val aBpmF0 = if (ap0.isNotBlank()) {
                                com.sdw.music.player.BpmKeyCache.getEntry(ap0)?.bpmFloat ?: 0f
                            } else 0f
                            val idealStart = pos + remain - dur      // 「如果按 dur 走，该在哪一刻起切」
                            // 【P1】改走动态 beatgrid：用 idealStart 所在段的 BPM/首拍做量化；
                            // 无 grid 或置信度不足时内部自动回退到原恒定 BPM 路径。
                            val qStart = quantizeTriggerWithGrid(ap0, idealStart, xfadeABeatPhaseMs, aBpmF0)
                            // 量化只会把起切点提前 snapLead 毫秒（绝不推迟，否则过渡会超出歌尾）。
                            // 于是闸门要相应放宽：允许在 remain 尚大于 dur 时就起切，
                            // 否则「remain <= dur」会把提前量吃掉，量化等于没生效。
                            val snapLead = (idealStart - qStart).coerceAtLeast(0L)
                            if (snapLead > 0) {
                                Log.i(TAG, "Crossfade: quantized trigger ideal=$idealStart -> bar=$qStart " +
                                    "(lead=${snapLead}ms bpm=%.1f beatPhase=$xfadeABeatPhaseMs)".format(aBpmF0))
                            }
                            if (remain <= dur + snapLead && pos >= qStart) {
                                shouldTrigger = true
                                Log.i(TAG, "Crossfade: beat-quantized trigger pos=$pos qStart=$qStart " +
                                    "aBeatPhase=${xfadeABeatPhaseMs} bpm=$aBpmF0 remain=$remain dur=$dur lead=$snapLead")
                            }
                        }
                        // 【V8.433】原来这层门是 `prescanDone && quietStart > 0`：
                        // 尾奏检不出时整块被跳过 → 落回 remain<=dur → 过渡压在歌尾。
                        // 现在**无条件**进来，quietStart=-1 时用虚拟出拍点兜底。
                        // （条件恒真写成 if 而不是裸 block，避免 Kotlin 的 if/else 悬空语法问题）
                        if (pos + remain > 0) {
                            // 【V8.431 djay 式出点】原来是 `remain <= dur && pos >= quietStart-300`：
                            // 过渡永远占满**最后 dur 秒**，也就是一首歌的结尾被过渡吃掉，
                            // 听感是「拖到最后一刻才切」。djay 相反 —— 它在**尾奏起点就收掉**，
                            // 让过渡完整落在「最后一个高能量段」里，之后那段安静尾巴直接结束歌曲。
                            //
                            // 以「出拍点」为终点反推起点：pos >= 出拍点 - dur 即触发。
                            // 出拍点 = 检出的尾奏起点，未检出则用虚拟出拍点（曲长 94%）。
                            val quietStart = xfadeAQuietStartMs
                            // 【V8.433 实测修正】现代母带（EDM / DJ 混音 / Loudness War）常常**根本没有尾奏** ——
                            // 实测两首真曲：I Am Optimus Prime 的包络 p05=0.496 p50=0.512 p98=0.531，
                            //   从头到尾一条直线（dyn=0.015），物理上不存在段落结构，任何算法都检不出。
                            // 这种情况旧逻辑回退到 `remain <= dur` → 过渡正好压在歌尾 → 用户听感就是"结尾才切"。
                            //
                            // 现在给一条**不依赖尾奏检出**的兜底：把「曲长 94%」当虚拟出拍点，
                            // 让过渡完整落在最后 6% 之前 —— 也就是"结尾前靠前出"，与 djay 的做法一致。
                            // （6% @ 4分钟曲 = 14秒，足够容纳 8~15 秒的过渡 + 余量。）
                            val virtualOutro = (pos + remain) * 94 / 100
                            val realQuiet = if (xfadeAPrescanDone && quietStart > 0) quietStart else virtualOutro
                            // 【V8.432 实测】下限：落点必须装得下整个过渡 + 2s，否则等于还是在歌尾切。
                            val headroomNeeded = (dur + 2000L)
                            val canLandOnQuiet = realQuiet > 0 &&
                                    (pos + remain - realQuiet) >= headroomNeeded
                            if (canLandOnQuiet && pos >= realQuiet - dur) {
                                shouldTrigger = true
                                Log.i(TAG, "Crossfade: OUTRO-landing trigger pos=$pos land=$realQuiet " +
                                    "remain=$remain dur=$dur (quiet=$quietStart virtual=$virtualOutro)")
                            } else if (remain <= dur && pos >= quietStart - 300) {
                            shouldTrigger = true
                            Log.i(TAG, "Crossfade: prescan-A trigger pos=$pos aQuietStart=${xfadeAQuietStartMs} remain=$remain dur=$dur")
                            }
                            // 【#2】检测到的段落边界出点：djay 在"换段处"收掉 A。
                            // 取 A 最靠尾、且留出足够余量（dur+2s）的**内部**换段点作 mix-out 目标；
                            // 距歌尾 ≤30s（否则是中段，过早切掉 A 大半首歌）且非 OUTRO 段。
                            // 无检测段落时整块跳过 → 退回当前行为（无退化）。
                            if (aPath.isNotBlank()) {
                                val secs = com.sdw.music.player.BpmKeyCache.getStructure(aPath).sections
                                if (secs.isNotEmpty()) {
                                    val songEnd = pos + remain
                                    var mixOut = -1L
                                    for (s in secs) {
                                        val b = s.startMs.toLong()
                                        if (b <= 0L || b >= songEnd) continue
                                        if (s.kind == com.sdw.music.player.BpmKeyCache.SectionBoundary.OUTRO) continue
                                        val tailRoom = songEnd - b
                                        if (tailRoom < dur + 2000L) continue       // 余量不足，跨不过去
                                        if (tailRoom > 30_000L) continue            // 太靠中前，切太多 → 跳过
                                        if (b > mixOut) mixOut = b                  // 取最靠尾的候选
                                    }
                                    if (mixOut > 0L && pos >= mixOut - dur) {
                                        shouldTrigger = true
                                        Log.i(TAG, "Crossfade: SECTION-out trigger pos=$pos mixOut=$mixOut remain=$remain dur=$dur (sections=${secs.size})")
                                    }
                                }
                            }

                            // 兜底：A 预扫对齐失败（A 无静音段/已错过）仍用 FLOOR_MS 保证不漏切。
                            // 【V8.182 A 轨尾部留白】FLOOR 兜底不能再是无脑 1500ms：过渡时长可能
                            // 有 5~15s，1.5s 就触发等于 A 在过渡中途就播完、B 从零开始。
                            // 收敛到「剩余时间还够跑半个过渡」再兜底。
                            if (!shouldTrigger && remain <= FLOOR_MS.toLong() && remain <= dur / 2L) shouldTrigger = true
                        } else {
                            if (remain <= dur) {
                                val rms = p.getRmsLevel()
                                xfadePeakRms = maxOf(xfadePeakRms * 0.995f, rms)
                                if (rms < 0.15f && rms < xfadePeakRms * 0.4f) {
                                    xfadeDipCount++
                                } else {
                                    xfadeDipCount = 0
                                }
                                // 【V8.182】同上：兜底阈值随过渡时长收敛，避免 A 在中途播完
                                if (xfadeDipCount >= 2 || (remain <= FLOOR_MS.toLong() && remain <= dur / 2L)) {
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
                            // [V8.74] pickNextIndex() used to run on EVERY 200ms tick even
                            // outside the preload window. In BPM mode that re-ran the whole
                            // BPM match and logged a "pickNext" line ~10x/second for the
                            // entire song (1412 Crossfade log lines, only 4 meaningful).
                            val preloadByChorus = xfadeAChorusEndMs > 0 && pos >= xfadeAChorusEndMs - 8000
                            val preloadByRemain = remain <= dur + 8000
                            val nextIndex = if (!xfadePreloading && (preloadByChorus || preloadByRemain)) {
                                pickNextIndex(songs, realIdx).takeIf { it != realIdx } ?: -1
                            } else xfadePreloadIndex
                            if (nextIndex >= 0 && nextIndex != realIdx) {
                                // ===== 阶段1：候选窗口（A 副歌结束前 8s 或 remain<=dur+8s，取先到）→ 启动预加载+副歌预扫（不触发） =====
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
                                            // [V8.94] 判据用 hasRealCover，否则死链（albumart 虚拟表）会进 B 轨
                                            // 封面叠加层与模糊背景，解码失败就是一片空白。
                                            nextAlbumArt = songs[nextIndex].albumArtUri.takeIf { SongRepository.hasRealCover(it) },
                                            nextAccentColor = 0L,
                                            startedAtMs = 0L
                                        )
                                        // 【2026-09-02 fix】openIncoming 移后台线程——原在主线程同步执行，
                                        // 大 m4a/MediaCodec configure 数百 ms~秒级 → 主线程阻塞 → UI 卡死
                                        xfadePreloadReady = false
                                        // 【V8.314】候选曲开始预加载时同步 probe 分轨预计算 ——
                                        // 分离要~60s，与 configure MediaCodec 并行跑，
                                        // 等真触发时窗口大概率已就绪。
                                        // 【V8.325 修正】原代码传 p.getDurationMs() 给 probePrecomputed，
                                        // 那是**当前 A 轨**的时长，与候选曲 B 无关；插件侧只把它当 hint 用，
                                        // 但日志里会打出错误时长、且宿主自己 windowed 请求的长度也按它算。
                                        // 这里改用歌曲自身的时长（BpmScanner 缓存或durationMs 字段），
                                        // 并且**把真正要用的窗口长度一并交给插件**，
                                        // 而不是等setupStemBusForXfade 才发（那时只剩几十秒，来不及）。
                                        run {
                                            try {
                                                com.sdw.music.player.core.stems.StemBusManager
                                                    .probePrecomputed(
                                                        this@MusicService, prePath,
                                                        p.getSampleRate(), songDurationMsOf(songs[nextIndex])
                                                    )
                                                // 自动路径入场点通常是副歌，用窗口式请求覆盖入场点附近。
                                                val bCh = com.sdw.music.player.BpmKeyCache
                                                    .getStructure(prePath).chorusMs.toLong().coerceAtLeast(0L)
                                                // 窗口长度 = 过渡时长 + 余量（下限 20s 覆盖插件最小窗口）
                                                val need = (barsToMsOrNull()?.toLong()
                                                    ?: adaptiveXfadeDur(songs[nextIndex]).toLong())
                                                    .coerceIn(20_000L, 60_000L)
                                                if (bCh > 0) {
                                                    com.sdw.music.player.core.stems.StemBusManager
                                                        .requestWindowAt(this@MusicService, prePath,
                                                            (bCh - 3_000L).coerceAtLeast(0L), need)
                                                } else {
                                                    com.sdw.music.player.core.stems.StemBusManager
                                                        .requestWindow(this@MusicService, prePath, need)
                                                }
                                            } catch (_: Throwable) {}
                                        }
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
                                        // 副歌/首拍 —— 【v8.166】优先查缓存（扫描阶段已入库）。
                                        // 旧逻辑每次切歌都实时解码预扫，实测耗时 15~16s，触发线程要等它最多 3s
                                        // → 算好的拍点被这段等待吃掉（18:54 那次实测晚了 2.1s）。
                                        xfadeBChorusMs = -1L
                                        xfadeBIntroMs = -1L
                                        xfadeBDropMs = -1L   // 【V8.431】换歌时一并重置
                                        xfadeBPrescanDone = false
                                        xfadeBFirstBeatMs = -1L
                                        val preSongForScan = songs[nextIndex]
                                        val bBpmForScan = preSongForScan.let {
                                            val sp = it.filePath.ifEmpty { it.path }
                                            if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.first ?: 0 else 0
                                        }
                                        val scanPath = prePath
                                        // 【v8.166】B 轨也顺带补扫：这次可能赶不上，但下次（它当 B 时）就能查表了
                                        if (scanPath != null) ensureSongAnalyzed(scanPath)
                                        val bStruct = com.sdw.music.player.BpmKeyCache.getStructure(scanPath)
                                        val bCachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(scanPath)
                                        if (bCachedBeat > 0) xfadeBFirstBeatMs = bCachedBeat.toLong()
                                        if (bStruct.introQuietMs > 0) xfadeBIntroMs = bStruct.introQuietMs.toLong()
                                        // 【V8.431】B 轨「能量点」落点（djay 式进场）。
                                        // warmDjStructure 开播时就用包络法算好了 dropMs，这里直接取。
                                        if (bStruct.dropMs > 0) {
                                            xfadeBDropMs = bStruct.dropMs.toLong()
                                            Log.i(TAG, "Crossfade: B entry drop=${bStruct.dropMs} ms (djay energy point)")
                                        }
                                        if (bStruct.chorusMs > 0) {
                                            // 缓存命中：直接可用，触发时无需等待 → 拍点对齐得以保住
                                            xfadeBChorusMs = bStruct.chorusMs.toLong()
                                            xfadeBPrescanDone = true
                                            Log.i(TAG, "Crossfade: prescan-B chorus=${bStruct.chorusMs} ms (cached)")
                                        }
                                        // 缺什么补什么（后台，不阻塞触发；结果回填缓存供下次直接用）
                                        if (bCachedBeat <= 0 || bStruct.introQuietMs < 0) {
                                            Thread {
                                                try {
                                                    val bBeat = if (bCachedBeat > 0) bCachedBeat else p.preScanPath(scanPath, 100, mode = 4, seekFromMs = 0L)
                                                    xfadeBFirstBeatMs = if (bBeat > 0) bBeat.toLong() else -1L
                                                    if (bBeat > 0) Log.i(TAG, "Crossfade: prescan-B firstBeat=$bBeat ms")
                                                    // 【V8.430 进拍】不再调 native preScan(mode=1)：那条路
                                                    // 每次新建一个 MediaCodec 解码头部，是 V8.386 要消除的解码器
                                                    // 消耗来源。改为 warmDjStructure 的包络法（introQuietMs）。
                                                    val bIntro = com.sdw.music.player.BpmKeyCache.getStructure(scanPath).introQuietMs
                                                    if (bIntro > 0) xfadeBIntroMs = bIntro.toLong()
                                                    xfadeBPrescanDone = true
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Crossfade: prescan-B beat failed ${e.message}")
                                                    xfadeBPrescanDone = true
                                                }
                                            }.apply { isDaemon = true }.start()
                                        } else {
                                            xfadeBPrescanDone = true
                                        }
                                        if (bpmChorusMode && bStruct.chorusMs <= 0) {
                                            Thread {
                                                try {
                                                    // 【V8.142】传入真实 B BPM，拍点吸附不再写死 120
                                                    val chorus = p.preScanPath(scanPath, 100, mode = 2, bpm = bBpmForScan)
                                                    xfadeBChorusMs = chorus.toLong()
                                                    if (chorus > 0) {
                                                        Log.i(TAG, "Crossfade: prescan-B chorus=$chorus ms (bpm=$bBpmForScan)")
                                                        com.sdw.music.player.BpmKeyCache.mergeStructure(scanPath, chorusMs = chorus)
                                                        com.sdw.music.player.BpmKeyCache.scheduleFlush()
                                                    } else {
                                                        Log.i(TAG, "Crossfade: prescan-B no chorus (start from 0)")
                                                    }
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
                                    val aPathForBeat = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                                    // 【V8.226 逻辑审查】A 侧拍/小节对齐不再只限 BPM 匹配随机模式：
                                    // 配了小节数（crossfade_bars>0）本意就是 DJ 式小节线交接，顺序播放
                                    // 同样应吸附（原 bpmChorusMode 门控与下方“配了小节数时 A 也吸附
                                    // 到整小节线”的注释自相矛盾：顺序播放下 A 在任意毫秒触发，
                                    // automix/对齐形同虚设）。B 轨副歌 seek 仍按 bpmChorusMode 门控不变。
                                    val aBpmForBeat = run {
                                        com.sdw.music.player.BpmKeyCache.init(this@MusicService)
                                        if (aPathForBeat.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(aPathForBeat)?.first ?: 0 else 0
                                    }
                                    var beatDelay = 0L
                                    if (aBpmForBeat in 40..220) {
                                        // 【P2-2】动态 beatgrid：pos 所在段 → 该段 BPM + 该段绝对相位
                                        val (gBeat, gBpm) = if (aPathForBeat.isNotBlank()) gridBeatAt(aPathForBeat, pos) else Pair(-1L, 0)
                                        val useBpm = if (gBpm in 40..220) gBpm else aBpmForBeat
                                        val beatMs = 60000L / useBpm
                                        // 【V8.182 小节对齐】B 的入场点已在 doCrossfadeTrigger 里被
                                        // snapBToBarGrid 吸附到小节线（downbeat 对 downbeat），
                                        // 但 A 侧仍只按「拍」延迟 → A 落在小节内任意拍、B 落在小节线，
                                        // 两边差 0~3/4 小节，所谓"拍上交接"实际对不齐。
                                        // 配了小节数（>0）时，A 也吸附到整小节线。
                                        val barAligned = crossfadeBars > 0
                                        // 【P2】一小节按该段自己的拍号算（旧数据缺省 4 → 与原来 beatMs*4 一致）
                                        val bpbA = beatsPerBarOf(
                                            if (aPathForBeat.isNotBlank()) gridSegmentAt(aPathForBeat, pos) else null
                                        )
                                        val gridMs = if (barAligned) beatMs * bpbA else beatMs
                                        // 【V8.142】用 A 轨首拍相位校正（原 pos % beatMs 误把重拍当成落在 t=0）
                                        val anchor = if (gBeat > 0) gBeat else if (xfadeABeatPhaseMs > 0) xfadeABeatPhaseMs else 0L
                                        val phase = anchor % gridMs
                                        var posInGrid = (pos - phase) % gridMs
                                        if (posInGrid < 0) posInGrid += gridMs
                                        beatDelay = gridMs - posInGrid
                                        // 【V8.182 安全网】等一整个小节最坏要 ~2.4s（100BPM），
                                        // 若 A 的剩余时长已经兜不住这次延迟，就地触发（宁可不对齐，不能漏切）。
                                        // 【V8.498】上限必须扣除 dur + 安全余量：否则节拍对齐把起切点推后，
                                        // 等效剩余被吃掉 → 过渡仍可能 overrun（A 在过渡结束前 EOS）。
                                        val maxDelay = remain - dur - XFADE_ARM_SAFETY_MS
                                        if (beatDelay > maxDelay) beatDelay = 0L
                                        // 已贴拍（≤250ms）直接触发；否则延迟到网格点
                                        if (beatDelay > 250L) {
                                            if (!xfadeBeatPending) {
                                                xfadeBeatPending = true
                                                val prePath = xfadePreloadPath
                                                val preSong = xfadePreloadSong
                                                val preIndex = xfadePreloadIndex
                                                Log.i(TAG, "Crossfade: beat-align trigger in ${beatDelay}ms (bpm=$aBpmForBeat pos=$pos beatMs=$beatMs)")
                                                // 【V8.183】存下这枚 Runnable 与令牌：手动切歌/关闭
                                                // crossfade 时要用它们把自己从队列里摘掉，避免对已经
                                                // 换掉的新歌强推一次上一轮的 B 轨。
                                                val myToken = ++xfadeBeatToken
                                                val beatRunnable = Runnable {
                                                    xfadeBeatPending = false
                                                    if (xfadeBeatToken != myToken) return@Runnable   // 已被 cancelPendingBeatTrigger 作废
                                                    // 【V8.226】防御：若此刻已有 crossfade 在飞（理论上 token 已作废，
                                                    // 兜底防双重触发），放弃本次拍点触发。
                                                    if (crossfadeBusy) { Log.w(TAG, "Crossfade: beat trigger skipped (busy)"); return@Runnable }
                                                    xfadeBeatRunnable = null
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
                                                }
                                                xfadeBeatRunnable = beatRunnable
                                                handler.postDelayed(beatRunnable, beatDelay)
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
                                // 【V8.226 逻辑审查】拍点触发在飞（xfadeBeatPending）时禁走 FLOOR：
                                // 拍点 Runnable 已排定且 beatDelay <= remain-500（必在 EOS 前 500ms 内触发），
                                // 此处再 startCrossfade 会与它双重触发——同一个 B 轨连切两次。
                                // 仅 A 已提前 EOS（元数据长度虚高/流提前耗尽）时才作废拍点、强制接管，
                                // 避免在轨道结束后干等拍点出声。
                                val beatInFlight = xfadeBeatPending
                                val allowFloor = !beatInFlight || aEosNoTakeover
                                if (allowFloor && beatInFlight) cancelPendingBeatTrigger()
// 【V8.312·A】FLOOR 路径也要受限流，但它和常规路径语义不同：
//   · aEosNoTakeover = A 轨**真的停了**（isPlaying!=true，元数据长度虚高/流耗尽）
//     → 必须立刻切，绝对不能等闸门，否则就是干等着放静音；
//   · remain <= 1500 = A 还在播、只是快到 EOS
//     → 这正是「连锁切歌」的落点（B 入场太靠后 → 立刻又满足），
//       必须受限流；宁可让 A 多唱一会儿也不要几秒连切两首。
// 被限流时**不动** crossfadeBusy（此路径尚未置位），只留预加载的 B，
                                    // monitor 下一次 tick 会重新判定；冷却到期自然放行。
                                    val coolRemainFloor = autoXfadeCooldownRemainingMs()
                                    val floorBlockedByCooldown =
                (coolRemainFloor > 0L && !aEosNoTakeover)
                                    if (floorBlockedByCooldown) {
                                        Log.i(TAG, "Crossfade: FLOOR suppressed by cooldown ${coolRemainFloor}ms (A still playing, remain=$remain)")
                                    }
                                    if (!triggered && !floorBlockedByCooldown && allowFloor && xfadePreloading && xfadePreloadReady && (remain <= 1500 || aEosNoTakeover)) {
                                    // 预加载就绪但触发条件未满足且已到 FLOOR_MS → 强制触发（兜底）
                                    val prePath = xfadePreloadPath
                                    val preSong = xfadePreloadSong
                                    val preIndex = xfadePreloadIndex
                                    xfadePreloading = false
                                    xfadePreloadPath = null
                                    xfadePreloadSong = null
                                    if (prePath != null && preSong != null) {
                                        crossfadeBusy = true
                                        crossfadeBusySinceMs = SystemClock.elapsedRealtime()
                                        crossfadeNextIndex = preIndex
                                        armCrossfadeBusyWatchdog()
                                        Log.i(TAG, "Crossfade: FLOOR trigger ${preSong.title} (remain=$remain)")
                                        val bPathForChorus2 = preSong.let { it.filePath.ifEmpty { it.path } }
                                        val entryRaw = resolveBEntryMs(preSong)
                                        var chorusMs = entryRaw
                                        val deadline = System.currentTimeMillis() + 3000
                                        while (chorusMs < 0 && System.currentTimeMillis() < deadline) {
                                            try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                            chorusMs = xfadeBChorusMs
                                            if (chorusMs < 0 && bPathForChorus2.isNotBlank()) {
                                                val cached = com.sdw.music.player.BpmKeyCache.getStructure(bPathForChorus2).chorusMs
                                                if (cached > 0) { chorusMs = cached.toLong(); xfadeBChorusMs = chorusMs }
                                            }
                                        }
                                        // 【V8.268】B 轨入场防「文件头空洞」：
                                        // ① 无副歌数据（预扫超时/未检出）→ 不再从 0 起播，退到首个音乐 downbeat；
                                        // ② 副歌点落在静音前奏内（能量峰值法在前奏强的歌上误判）→ 同样退到首拍。
                                        // B 从前奏 groove 进是 DJ 正确手法（避免人声冲突），但起播点必须是音乐开始处。
                                        if (chorusMs <= 0L && xfadeBFirstBeatMs > 0) {
                                            chorusMs = xfadeBFirstBeatMs
                                            Log.i(TAG, "Crossfade: B entry fallback firstBeat=${xfadeBFirstBeatMs}ms (no chorus, floor)")
                                        }
                                        val introQ1 = xfadeBIntroMs
                                        if (chorusMs > 0 && introQ1 > 0 && chorusMs < introQ1 && xfadeBFirstBeatMs > chorusMs) {
                                            chorusMs = xfadeBFirstBeatMs
                                            Log.i(TAG, "Crossfade: B entry chorus<intro($introQ1) -> firstBeat=${xfadeBFirstBeatMs}ms (floor)")
                                        }
                                        if (chorusMs > 0) {  // 【V8.422】任意模式都 seek 到入场点（intro=前奏首拍 / chorus=副歌）
                                            // 【V8.320 补漏】FLOOR 路径「太靠后」守卫 + snap 后剩余量复查（与常规路径一致）。
                                            // FLOOR 在 A 剩 <=1.5s 时触发，正是最容易让 B 一进场就接近 EOS
                                            // 的那条路径 → 连锁切歌。统一走 guardBEntryPoint。
                                            chorusMs = guardBEntryPoint(preSong, chorusMs)
                                            chorusMs = snapBToBarGrid(preSong, chorusMs, currentBeatInBar(p))
                                            chorusMs = guardBEntryPoint(preSong, chorusMs)   // snap 后复查
                                            xfadeLastBStartMs = chorusMs.toLong()  // 【V8.16】记录 B 起点
                                            val seeked = p.seekIncoming(chorusMs.toInt())
                                            if (seeked) Log.i(TAG, "Crossfade: B starts at ${bEntryMode} ${chorusMs}ms (floor, grid-snapped)")
                                        } else {
                                            xfadeLastBStartMs = -1L
                                        }
                                        val cfDurBase0 = adaptiveXfadeDur(preSong)
                                        // 【P3】真实 Master Sync：微调过渡时长使落点落在 master 小节线（未开启时返回 0）
                                        val cfDur0 = cfDurBase0 + masterSyncDurAdjustMs(preSong, cfDurBase0)
                                        // [V8.343] shadow: master phase solve (observation only)
                                        if (bpmChorusMode) shadowSolvePhase(preSong, chorusMs, cfDur0)
                                        // 【V8.231→V8.498】同 doCrossfadeTriggerBg：FLOOR 触发时 remain 更小，
                                        // 不 clamp 的话 A 几乎必 EOS（这正是 FLOOR 兑底路径的老毛病）。
                                        // 改用 remainNow - XFADE_ARM_SAFETY_MS 硬上限，remainNow 偏小时也照样夹。
                                        val cfDur = run {
                                            val total = p.getDurationMs()
                                            val posNow = p.getCurrentPositionMs()
                                            val remainNow = if (total > 0) total - posNow else -1L
                                            if (remainNow > 0) {
                                                val safe = (remainNow - XFADE_ARM_SAFETY_MS).coerceAtLeast(0L).toInt()
                                                if (safe < cfDur0) {
                                                    Log.i(TAG, "Crossfade: dur clamp(floor) $cfDur0 -> $safe (remainNow=$remainNow)")
                                                    safe
                                                } else cfDur0
                                            } else cfDur0
                                        }
                                        // 【V8.145】DJ 切歌效果下发（dissolve=4 与旧行为一致）
                                        // 【V8.246】带 BPM 差降级：超 ±8% 变速窗 → echo-out + 过渡压短到 1.5s
                                        // 【V8.322】把 B 入场点的真实拍相位给 PLL（须在 setBeatmatch 之前）
                                        pushBEntryBeatPhase(p, preSong, chorusMs)
                                        pushAEntryBeatPhase(p)        // 【V8.503·③】A 轨真实拍相位给 PLL（须 setBeatmatch 之前）
                                        applyXfadeTemplate(preSong)   // 【V8.281】djay 模板
                                        val (fxType, fxBeat, bpmFall) = resolveDjEffectAuto(preSong)
                                        p.setCrossfadeEffect(fxType, fxBeat)
                                        if (liveFxTransitionOnly()) setLiveFxBus(true)   // 【V8.271】过渡窗口挂上
                                        // 【V8.477 修"A 轨退出前又响一秒/没有渐弱"】
                                        //
                                        // 原逻辑：BPM 差超 ±8%（bpmFall）就把过渡**硬压到 1500ms**。
                                        // 用户设的是 4 拍 = 11023ms，实际只给 1.5 秒 ——
                                        // A 轨在 1.5 秒里被快速处理掉，听感就是
                                        // 「不是渐弱，是突然响一下就没了」。
                                        // 真机日志：
                                        //   Crossfade: BPM diff beyond ±8% window (a=87 b=94) -> echo-out fallback
                                        //   Crossfade: dur cap(echo-out) 11023 -> 1500
                                        //
                                        // 1.5s 这个数当年是为了"不对拍就别拖"，但代价是 A 轨没有
                                        // 渐弱空间。改为：**至少 2.5s，且不少于用户设定值的一半**。
                                        val cfDurEff = if (bpmFall)
                                            maxOf(2500, cfDur / 2).coerceAtMost(cfDur)
                                        else cfDur
                                        if (bpmFall && cfDur != cfDurEff) {
                                            Log.i(TAG, "Crossfade: dur cap(echo-out) $cfDur -> $cfDurEff")
                                        }
                                        // 【M2】beatmatch：incoming 变速到 A 轨速度（必须在 startCrossfade 之前）
                                        // 【V8.424】过渡采样音效已删除：sfxMode=2 完全静默（aBpm 仅保留 native 签名兼容）
                                        val bmA = beatmatchParams(preSong)
                                        applyTempoRamp(p)   // 【V8.427】渐变拉速须先于 setBeatmatch/arming 下发
                                        p.setBeatmatch(bmA.ratio, bmA.aBpm, bmA.bBpm, bmA.phaseLock, bmA.engage)
                                        // 【V8.314】FLOOR 兜底路径同样接分轨（以前只有常规自动路径有）
                                        val stemActive = setupStemBusForXfade(p, preSong, xfadeLastBStartMs.coerceAtLeast(0L), cfDurEff)
                                        logTemplateBypassByStem(p, stemActive, preSong)
                                        val ok = p.startCrossfade(cfDurEff, if (chorusMs > 0) chorusMs.toInt() else 0,
                                            songLufs(currentSong), songLufs(preSong), 2, bmA.aBpm)
                                        if (ok) {
                                            Log.i(TAG, "Crossfade: triggered ${cfDurEff}ms → ${preSong.title} (floor, dur=$dur)")
                                            // 【V8.419】三条触发路径（自动/FLOOR/手动）统一上报入口对齐残差
                                            logEntryAlignment(p, preSong, chorusMs.toInt(), bmA)
                                            // 【V8.143】把真实生效时长(cfDur=自适应)写进 UI 信息：原 UI 用用户设定值(dur)
                                            // 当分母推算进度，音频只混 2~4s（自适应）时封面通道才走到约 40%，
                                            // DONE 一瞬瞬时切封面就露出未过渡的部分 → 那下「很快的闪」。
                                            // 【V8.183】这里原先把 B 轨的封面/标题整个漏掉了：copy 只写了
                                            // state/durationMs/startedAtMs，PRELOADING 阶段设好的
                                            // nextTitle/nextAlbumArt 被冲掉又不重设，封面不做交叉淡化；
                                            // 也漏了 startXfadeLevelSampler()，走这条兜底路径时
                                            // 过渡可视化条整段不出现。补上与 doCrossfadeTriggerBg 一致。
                                            crossfadeUiInfo = crossfadeUiInfo.copy(
                                                state = XfadeUiState.ACTIVE,
                                                startedAtMs = SystemClock.elapsedRealtime(),
                                                durationMs = cfDur,
                                                nextTitle = preSong.title,
                                                nextArtist = preSong.artist,
                                                nextAlbumArt = preSong.albumArtUri.takeIf { SongRepository.hasRealCover(it) },
                                                nextAccentColor = 0L
                                            )
                                            startXfadeLevelSampler()
                                            crossfadeNativeStarted = true
                                            scheduleCrossfadeCompletionPoll(p, preSong, preIndex, cfDurMs = cfDurEff.toLong())
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
            // 【v8.165 省电】200ms → 500ms，且改投后台 xfadeHandler
            scheduleCrossfadeMonitor(XFADE_TICK_ACTIVE_MS)
        }
    }

    // 【V8.24 A 轨拍点触发】阶段2 真实触发体（立即触发与拍点延迟共用）
    private fun doCrossfadeTrigger(p: OboeDirectPlayer, preSong: Song, preIndex: Int, dur: Int) {
        // 【V8.364】普通模式只读断言：过渡参数下发前确认
        assertPlainModeAudioInvariants("auto-xfade-trigger")
        // V8.367: publish the decision so the DJ surface can render it.
        // djay Automix shows exactly this ("87 -> 111 BPM", "-7.5%") and it is the main
        // reason its UI reads as intelligent: you see WHY this track was chosen and HOW the
        // two will be aligned. Ours computed the same numbers all along in
        // beatmatchParams(), then handed them to native and dropped them.
        run {
            val bm = beatmatchParams(preSong)
            val target = if (bm.ratio > 0f) bm.bBpm / bm.ratio else 0f
            val reason = if (!xfadeCfg().djMode) "plain crossfade"
            else "BPM " + bm.bBpm.toInt() + " to " + target.toInt()
            handler.post {
                crossfadeUiInfo = crossfadeUiInfo.copy(
                    aBpm = bm.aBpm,
                    bBpm = bm.bBpm,
                    targetBpm = target,
                    ratioPct = (bm.ratio - 1f) * 100f,
                    pickReason = reason
                )
            }
        }
        // 【2026-09-02 fix】整体后台执行——原在主线程等 chorus 预扫最多 3s（Thread.sleep 阻塞主线程
        // → crossfade 触发瞬间 UI 卡顿）。UI 更新用 handler.post 切回主线程。
        // [V8.74] Claim the slot BEFORE spawning. This used to be the first statement of
        // the background thread, which left a window where a second monitor loop still saw
        // crossfadeBusy == false and ran its own preload + startCrossfade on the same
        // native player while the first one was already in flight.
        crossfadeBusy = true; crossfadeBusySinceMs = SystemClock.elapsedRealtime()
        armCrossfadeBusyWatchdog()
        Thread {
            try {
                doCrossfadeTriggerBg(p, preSong, preIndex, dur, claimedByCaller = true)
            } catch (e: Throwable) {
                // 【V8.337 补齐】原来只清 crossfadeBusy，漏了 crossfadeNextIndex：
                // 残留的 nextIndex 会让 UI/后续逻辑以为"已预加载好下一首"，
                // 与实际（没有 B 轨）不符。与 busy 一起清，保持状态一致。
                Log.w(TAG, "Crossfade: trigger bg exception ${e.message}", e)
                crossfadeBusy = false
                crossfadeNextIndex = -1
                crossfadeBusySinceMs = 0L
                handler.post { crossfadeUiInfo = CrossfadeUiInfo() }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 【V8.325】候选曲（B 轨）的曲长，供分轨预计算用。
     *
     * 之前这里传的是 `oboeDirectPlayer.getDurationMs()` —— 那是**当前 A 轨**的时长，
     * 与候选曲无关。插件侧只当hint，但它会让日志里的时长误导排查。
     * 优先用扫描缓存（预扫描阶段已写入），拿不到就返回 0（插件会自行从文件读）。
     */
    private fun songDurationMsOf(song: Song?): Long {
        // Structure 里没有 durationMs（只有 chorus/chorusEnd/quiet），曲长只能取 Song.duration。
        // 拿不到返回 0 —— 插件侧会自行从文件读，不是硬依赖。
        return try { song?.duration ?: 0L } catch (_: Throwable) { 0L }
    }

    /**
     * 【V8.314】分轨（NeuralMix stem）总线接线 —— **自动 / 手动两条过渡路径共用**。
     *
     * 背景：这段逻辑原先只写在 doCrossfadeTriggerBg（自动路径）里，手动「立即切歌」
     * 完全没有分轨代码。用户的真实用法就是手动切歌，于是「分轨效果听不出来」——
     * 不是参数太弱，而是**功能从未执行**。真机日志佐证：`STEM BUS active` 0 条。
     *
     * 窗口就绪 → 四轨错峰交接（bass 提前 / vocals 推迟）；
     * 未就绪 → 现场请求窗口（下次该曲就有），本次退回普通混音。
     *
     * @param entryMs B 轨入场点（ms），窗口起点据此计算
     * @param cfDur 本次过渡时长（ms），窗口要覆盖它
     */
    private fun setupStemBusForXfade(
        p: OboeDirectPlayer,
        preSong: Song?,
        entryMs: Long,
        cfDur: Int
    ): Boolean {
        try {
            val bPathS = preSong?.filePath?.ifEmpty { preSong.path } ?: ""
            if (bPathS.isBlank() || bPathS.startsWith("content://")) return false
            val mgr = com.sdw.music.player.core.stems.StemBusManager
            if (mgr.isWindowReady(this, bPathS, entryMs, cfDur + 2000L)) {
                val fds = mgr.openStems(this, bPathS)
                // 【V8.314 修】原代码这里有**两处** beginStemMode() 调用：
                //   else if (!p.beginStemMode()) { ...fds.close() }
                //   if (fds != null && p.beginStemMode()) { ... }
                // 第二次调用会重跑 nativeStemReset() **清空四轨环**，把刚开好的总线抹掉，
                // 而且"两次都成功才继续"的逻辑纯属冗余。改为**只调一次**。
                if (fds == null) {
                    Log.w(TAG, "StemBus: window ready but openStems() returned null ($bPathS)")
                    return false
                }
                if (!p.beginStemMode()) {
                    Log.w(TAG, "StemBus: beginStemMode() failed (stream rate mismatch?)")
                    fds.forEach { runCatching { it.close() } }
                    return false
                }
                // 【V8.278】窗口可能不是从 0 开始，文件内偏移要减掉窗口起点，否则跳错位置。
                val wStart = stemWindowStartMs[bPathS] ?: 0L
                val skipMs = (entryMs - wStart).coerceAtLeast(0L)
                // 【V8.282】i16le 立体声 = 4 字节/帧。偏移按分轨文件率算（插件恒 44.1k）。
                p.startStemFeeder(
                    fds,
                    skipMs * OboeDirectPlayer.STEM_FILE_RATE.toLong() * 4L / 1000L
                )
                Log.i(TAG, "StemBus: STEM BUS active (entry=${entryMs}ms, wStart=${wStart}ms, cf=${cfDur}ms)")
                return true
            } else {
                // 【V8.278】精确窗口：以入场点为起点存 45s（固定 [0,90s] 时，
                // 入场点在 90s 之后的长曲永远命中不了）。
                val wStart = (entryMs - 3_000L).coerceAtLeast(0L)
                val wLen = 45_000L
                stemWindowStartMs[bPathS] = wStart
                mgr.requestWindowAt(this, bPathS, wStart, wLen)
                Log.i(TAG, "StemBus: not ready -> requested ${wStart}..${wStart + wLen}ms, plain mix this round")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "StemBus: ${t.message}")
            try { p.endStemMode() } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * 【V8.319顺序修正】分轨总线就绪后补记"模板被旁路"。
     *
     * 原实现在 `applyXfadeTemplate()` 里用 `p.stemModeOn` 判断，但那个函数调用在
     * `setupStemBusForXfade()` **之前** —— 此时读到的是**上一轮**的分轨状态。
     *后果：首次开启分轨的那一轮判断不到，模板照常"生效"（实际被 native 静默跳过），
     * 用户和日志都以为模板在跑。改为在 setup 返回后按**本轮真实结果**判断。
     */
    private fun logTemplateBypassByStem(p: OboeDirectPlayer, stemActive: Boolean, preSong: Song? = null) {
        if (!stemActive) return
        // V8.346: pass preSong -- with the "random" option the picker re-decides on every call,
        // so calling it without the candidate could name a DIFFERENT template than the one
        // actually bypassed this round.
        val tpl = resolveXfadeTemplate(preSong)
        if (tpl > 0) {
            // V8.349: the track-level template is still bypassed, but the effect is no
            // longer absent -- stemFxTarget(tpl) now derives a stem route (drums/vocal)
            // so the round still does something. Say so, otherwise the log (and the
            // settings chip) claims "no effect" while audio clearly has one.
            val fxTarget = stemFxTarget(tpl)
            val fxMode = stemFxMode()
            if (fxTarget > 0) {
                Log.i(TAG, "Crossfade: template tpl=$tpl track-level bypassed (stem mode) -> routed to stem target=$fxTarget mode=$fxMode")
            } else {
                Log.w(TAG, "Crossfade: template tpl=$tpl bypassed AND no stem fx target (stem mode) -> plain mix")
            }
        }
    }

    private fun doCrossfadeTriggerBg(p: OboeDirectPlayer, preSong: Song, preIndex: Int, dur: Int, claimedByCaller: Boolean = false) {
        // 【V8.312·A】自动切歌最小间隔闸门。这是**所有自动过渡的唯一入口**（monitor 的拍点 /
        // 副歌 / 静音段 / FLOOR 四条判定路径最后都汇到这里），所以闸门放这一处即可全覆盖。
        // 症状回顾：入场点选得太靠后 → B 一进场就接近 EOS → remain<=dur 立刻成立 →
        // 落点后 200ms 重挂 monitor 又触发 → 无限连锁。这里从根上否掉「几秒内连切两首」。
        // 注意：**手动**立即切歌走triggerCrossfadeNowDetailed，不经过本函数，不受限流。
        val coolRemain = autoXfadeCooldownRemainingMs()
        if (coolRemain > 0L) {
            Log.i(TAG, "Crossfade: auto cooldown ${coolRemain}ms remaining (gap=${autoXfadeMinGapMs()}ms) -> skip this round")
            // ⚠️ 调用方（triggerXfadeFromMonitor 那条 Thread 启动处）已经先置了
            // crossfadeBusy=true，这里必须复位，否则冷却期内 busy 永久卡住
            //（手动路径的 30s 卡死自愈是唯一救命稻草，等于自动切歌整个瘫掉）。
            crossfadeBusy = false
            crossfadeNextIndex = -1
            crossfadeUiInfo = CrossfadeUiInfo()
            // 预加载的 B 轨留着：冷却到期后它多半还有效（45s 窗口内），
            // 强行 stopIncoming 反而要重开一遍 codec。
            // monitor 会按常规 tick 再来一次，到期后自然重新满足触发条件。
            return
        }
        // 【V8.337·自动路径补卡死自愈】手动路径早有 30s 自愈（V8.271），**自动路径一直缺**。
        // 而 busy 唯一的正常清理由 scheduleCrossfadeCompletionPoll 负责；只要那条链路
        // 漏掉一次（poll 没 post 上、线程异常、startCrossfade 返回 false 等），
        // busy 就永久 true → monitor 四条判定全被挡、手动一直撞「正在过渡中」，
        // 用户观感=「切了几首后再也切不动，歌单也点不动」。
        // 这里补最后一道保险：单次过渡最长 ~60s(bars=32 时可更长)，置位超过 90s 必然是漏了。
        // V8.352: only treat a PRE-EXISTING busy as a conflict. When we come from doCrossfadeTrigger() the caller already set crossfadeBusy=true (V8.74, to close the double-trigger window) -- so this check used to reject our OWN trigger:   doCrossfadeTrigger sets busy -> bg thread -> `if (crossfadeBusy)` -> held=1ms   -> "AUTO trigger skipped (busy 1ms)" -> the automatic crossfade could never   fire. Device log proved it: window trigger + beat-align fired, then   "AUTO trigger skipped (busy 1ms)" every single time.
        if (crossfadeBusy && !claimedByCaller) {
            val heldBusy = SystemClock.elapsedRealtime() - crossfadeBusySinceMs
            if (heldBusy > 90_000L) {
                Log.w(TAG, "Crossfade: busy stuck ${heldBusy}ms on AUTO path -> force reset")
                try { p.stopIncoming() } catch (_: Throwable) {}
                crossfadeBusy = false
                crossfadeNextIndex = -1
                crossfadeBusySinceMs = 0L
                crossfadeUiInfo = CrossfadeUiInfo()
            } else {
                Log.w(TAG, "Crossfade: AUTO trigger skipped (busy ${heldBusy}ms)")
                return
            }
        }
        crossfadeBusy = true
        crossfadeBusySinceMs = SystemClock.elapsedRealtime()
        armCrossfadeBusyWatchdog()
        // 【V8.250】硬切模式（djay CUT）：不做淡化，在当前对齐点（拍线/乐句线/静音段）
        // 直接切歌。触发时机仍由 monitor 的对齐逻辑决定——与淡化共用同一个落点。
        if (getSharedPreferences("settings", MODE_PRIVATE).getString("crossfade_style", "fade") == "cut") {
            Log.i(TAG, "Crossfade: style=cut -> immediate switch to ${preSong.title}")
            handler.post {
                crossfadeUiInfo = CrossfadeUiInfo()
                playSong(preIndex)
                crossfadeBusy = false
                crossfadeNextIndex = -1
            }
            return
        }
        crossfadeNextIndex = preIndex
        Log.i(TAG, "Crossfade: preloading next: ${preSong.title}")
        // 等 chorus 预扫完成（最多 3s；超时从 0 播）
        // 【v8.166】等待期间顺带复查缓存——后台补扫可能刚写完，就不用等预扫了
        val bPathForChorus = preSong.let { it.filePath.ifEmpty { it.path } }
        var chorusMs = xfadeBChorusMs
        val deadline = System.currentTimeMillis() + 3000
        while (chorusMs < 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
            chorusMs = xfadeBChorusMs
            if (chorusMs < 0 && bPathForChorus.isNotBlank()) {
                val cached = com.sdw.music.player.BpmKeyCache.getStructure(bPathForChorus).chorusMs
                if (cached > 0) { chorusMs = cached.toLong(); xfadeBChorusMs = chorusMs }
            }
        }
        // 【V8.268】B 轨入场防「文件头空洞」（同 floor 触发点）：无副歌数据或副歌误判进
        // 静音前奏时，退到首个音乐 downbeat（首拍），绝不从文件头静音起播。
        if (chorusMs <= 0L && xfadeBFirstBeatMs > 0) {
            chorusMs = xfadeBFirstBeatMs
            Log.i(TAG, "Crossfade: B entry fallback firstBeat=${xfadeBFirstBeatMs}ms (no chorus)")
        }
        val introQ0 = xfadeBIntroMs
        if (chorusMs > 0 && introQ0 > 0 && chorusMs < introQ0 && xfadeBFirstBeatMs > chorusMs) {
            chorusMs = xfadeBFirstBeatMs
            Log.i(TAG, "Crossfade: B entry chorus<intro($introQ0) -> firstBeat=${xfadeBFirstBeatMs}ms")
        }
                                        if (chorusMs > 0) {  // 【V8.422】任意模式都 seek 到入场点（intro=前奏首拍 / chorus=副歌）
                                            // 【V8.312·B】入场点合理性兜底（与 native 扫描窗自适应配套，双保险）。
            // 即使检测器给出 210s 这种点，只要它超过 B 曲长的 60%，就判定为误判
            //（实测那是曲子中后段的能量回升处，不是真副歌）→ 回退到首个 downbeat，
            //再不行用曲长 40% 处，保证 B 进场后还有足够内容可播。
            // 不做这一步的话「接歌接到快结束的地方」会稳定复现。
            // 【V8.320】第一道（60% 兜底）统一到 guardBEntryPoint；这里先过第一段
            chorusMs = guardBEntryPoint(preSong, chorusMs)
            // 【v8.166】B 入场点吸附到「与 A 当前小节内同一拍位」的拍 → downbeat 对 downbeat
            chorusMs = snapBToBarGrid(preSong, chorusMs, currentBeatInBar(p))
            // 【V8.316·最后一道防线】snapBToBarGrid 的搜索窗是 **±32 拍**（@120bpm ≈ ±16s），
            // 而且 anchor 可能落在曲尾 → 吸附本身**能把一个已经合理的入场点推到很后面**。
            // 上面的 60% 兜底是在 snap **之前**做的，管不到这一段。
            // 这里在最终值上再判一次：B 剩下的内容必须够播完整个过渡 + 一定余量，
            // 否则往前找一个更早的拍位。宁可入场点不够"对齐"，也不能让 B 一进来就该 EOS。
            // 【V8.320】第三道（snap 后剩余量）—— 同一函数再过一次
            chorusMs = guardBEntryPoint(preSong, chorusMs)
            // 【V8.221】Camelot 调性和谐检查（可观测化；选曲侧的和谐优先已在
            // shuffle bpm 模式的 keyScore 里）。和谐 = 同号 / ±1 同字母 / ±3 异字母。
            run {
                val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                val aKey = if (aPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(aPath)?.second else null
                val bKey = if (bPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(bPath)?.second else null
                if (!aKey.isNullOrBlank() && !bKey.isNullOrBlank()) {
                    Log.i(TAG, "Crossfade: Camelot A=$aKey B=$bKey compatible=${camelotCompatible(aKey, bKey)}")
                }
            }
            xfadeLastBStartMs = chorusMs.toLong()  // 【V8.16】记录 B 起点
            val seeked = p.seekIncoming(chorusMs.toInt())
            if (seeked) Log.i(TAG, "Crossfade: B starts at chorus ${chorusMs}ms (grid-snapped)")
        } else {
            xfadeLastBStartMs = -1L  // 【V8.16】无副歌起点
        }
        // 【V8.23 BPM 自适应时长】A/B 两轨 BPM 差 ≤2 → 短混音（2s，DJ 式拍上交接）；差 >2 → 用户设定时长
        var cfDur = adaptiveXfadeDur(preSong)
        // 【P3】真实 Master Sync：微调过渡时长使落点落在 master 小节线（未开启时返回 0）
        cfDur += masterSyncDurAdjustMs(preSong, cfDur)
        // [V8.343] shadow: master phase solve (observation only)
        if (bpmChorusMode) shadowSolvePhase(preSong, chorusMs, cfDur)
        // 【V8.231→V8.498】过渡时长不得大于 A 的剩余可播时长：拍点对齐/窗口触发都可能比
        // "remain<=dur" 更晚动手（日志实锓 remain=6652 < dur=7441），A 在过渡结束前 EOS →
        // 混音中段 A 突然静音（增益还很大）→ 「哒」一声/白噪音。
        // 旧逻辑用 remainNow-300 且仅在 (700..cfDur) 区间才夹：remainNow 偏小时反而不夹，
        // 长过渡必 overrun。改为：始终夹到 remainNow - XFADE_ARM_SAFETY_MS（覆盖 800ms 预填充
        // + de-click 拖尾 + 轮询/握手抖动），remainNow 极小时退化为硬切也不 overrun。
        run {
            val total = p.getDurationMs()
            val posNow = p.getCurrentPositionMs()
            val remainNow = if (total > 0) total - posNow else -1L
            if (remainNow > 0) {
                val safe = (remainNow - XFADE_ARM_SAFETY_MS).coerceAtLeast(0L).toInt()
                if (safe < cfDur) {
                    Log.i(TAG, "Crossfade: dur clamp $cfDur -> $safe (remainNow=$remainNow, armSafety=${XFADE_ARM_SAFETY_MS})")
                    cfDur = safe
                }
            }
        }
        // 【V8.145】DJ 切歌效果下发（dissolve=4 与旧行为一致）
        // 【V8.246】带 BPM 差降级：超 ±8% 变速窗 → echo-out + 过渡压短到 1.5s
        // 【V8.322】把 B 入场点的真实拍相位给 PLL（须在 setBeatmatch 之前）
        pushBEntryBeatPhase(p, preSong, chorusMs)
        pushAEntryBeatPhase(p)        // 【V8.503·③】A 轨真实拍相位给 PLL（须 setBeatmatch 之前）
        applyXfadeTemplate(preSong)   // 【V8.281】djay 模板
        val (fxType, fxBeat, bpmFall) = resolveDjEffectAuto(preSong)
        p.setCrossfadeEffect(fxType, fxBeat)
        if (liveFxTransitionOnly()) setLiveFxBus(true)   // 【V8.271】过渡窗口挂上
        // 【V8.477】同上：BPM 差大时不再硬压到 1500ms，
        // 至少 2.5s 且不少于用户设定值的一半，给 A 轨留出渐弱空间。
        if (bpmFall) {
            val capped = maxOf(2500, cfDur / 2).coerceAtMost(cfDur)
            if (capped != cfDur) {
                Log.i(TAG, "Crossfade: dur cap(echo-out) $cfDur -> $capped")
                cfDur = capped
            }
        }
        // 【M2】beatmatch：incoming 变速到 A 轨速度（必须在 startCrossfade 之前）
        // 【V8.424】过渡采样音效已删除：sfxMode=2 完全静默（aBpm 仅保留 native 签名兼容）
        val bmA = beatmatchParams(preSong)
        applyTempoRamp(p)   // 【V8.427】渐变拉速须先于 setBeatmatch/arming 下发
        p.setBeatmatch(bmA.ratio, bmA.aBpm, bmA.bBpm, bmA.phaseLock, bmA.engage)
        // 【V8.314】分轨总线接到手动路径：以前这段只在 doCrossfadeTriggerBg（自动）里，
        // 手动「立即切歌」一行分轨代码都没有 → 用户手动测永远听不到分轨效果。
        val stemActive = setupStemBusForXfade(p, preSong, xfadeLastBStartMs.coerceAtLeast(0L), cfDur)
        logTemplateBypassByStem(p, stemActive, preSong)
        val ok = p.startCrossfade(cfDur, if (chorusMs > 0) chorusMs.toInt() else 0,
            songLufs(currentSong), songLufs(preSong), 2, bmA.aBpm)
        if (ok) {
            Log.i(TAG, "Crossfade: triggered ${cfDur}ms → ${preSong.title} (dur=$dur)")
            // 【V8.419·可量化验收】用户诉求：「B 第一拍要接 A 第 9 拍」。这条日志把
            // "接得齐不齐"变成可测数字：A/B 各自入口的拍号 + 小节内拍位 + 拍长 + 残差(ms)。
            // 判据：phaseResidualMs 越接近 0 越齐；beatInBar 相同即 downbeat 对 downbeat。
            logEntryAlignment(p, preSong, chorusMs.toInt(), bmA)
            // 【V8.139】绑定 native 启动时刻，供 UI 做相位对齐（避免画面滞后一拍）
            val startedAt = SystemClock.elapsedRealtime()
            // 【V8.143】同 floor：UI 进度分母必须用真实时长 cfDur，封面/背景才能与音频同相走完
            handler.post {
                crossfadeUiInfo = crossfadeUiInfo.copy(state = XfadeUiState.ACTIVE, startedAtMs = startedAt, durationMs = cfDur)
                startXfadeLevelSampler()   // 【V8.182 过渡可视化】过渡期间 60ms 采样两轨电平
            }
            crossfadeNativeStarted = true
            scheduleCrossfadeCompletionPoll(p, preSong, preIndex, cfDurMs = cfDur.toLong())
        } else {
            // 【V8.337关键修复·用户报"切了三首又切不过去了"】
            // 原实现在 startCrossfade 返回 false 时**只**清了 UI 和 incoming 轨道，
            // 却没复位 1118 行置位的 crossfadeBusy → busy 永久卡 true。
            // 后果：之后 monitor 的四条判定全被 `!crossfadeBusy` 挡住（永不触发），
            // 手动路径也会一直撞「正在过渡中」——用户观感就是「切了几首后再也切不动了」。
            // 歌单点别的歌则因为 playSong 同样要过 busy 检查而被一起挡住，
            // 所以「无法点歌单播放」是**同一个根因**，不是两个 bug。
            Log.w(TAG, "Crossfade: startCrossfade failed -> reset busy (was stuck forever)")
            crossfadeBusy = false
            crossfadeNextIndex = -1
            crossfadeBusySinceMs = 0L
            handler.post { crossfadeUiInfo = CrossfadeUiInfo() }
            p.stopIncoming()
        }
    }

    /** 【V8.182】手动立即触发一次 DJ 交叉淡化（按钮用）。
     *  不等候选窗口、不等拍点对齐、不等 chorus 预扫 —— 点了当下就切。
     *  openIncoming 会 configure MediaCodec（数百 ms~秒级），必须后台，否则主线程卡死。 */
    /** 【V8.271】上次手动触发被拒的具体原因（UI 直接显示，别再只说「检查总开关」）。 */
    /** 【V8.278】记住每首歌 stem 窗口的起点（ms）。窗口不再固定从 0 开始，
     *  打开 stem 文件时要用它换算文件内偏移。 */
    private val stemWindowStartMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile var lastManualTriggerReason: String = ""
    /** 【V8.304】上次手动触发是否只是「已排队」（过渡中，过渡结束自动补切）。
     *  这不是拒绝 —— UI 据此换用「已排队」提示，而不是「未触发/检查…」的故障口吻。 */
    @Volatile var lastManualTriggerQueued: Boolean = false
        private set

    fun triggerCrossfadeNow(): Boolean = triggerCrossfadeNowDetailed().isEmpty()

    /** 【V8.307】手动抢占时把当前过渡压缩到这么长（ms）。太短会像瞬切爆音，250ms 是
     *  既明显"马上就切"又保住等功率曲线形状的折中；native 侧还有 150ms 硬下限。 */
    private val PREEMPT_CUT_MS = 250

    /** 【V8.271】手动触发，返回 ""=成功，否则是被拒原因。
     * 旧实现有多个「静默 return false」分支（播放器为空/未就绪/单曲循环/选曲失败/路径空），
     * 用户只能看到一句「未触发」，无从排查 —— 现在每条都给出具体原因。
     * 另外：手动触发不再被单曲循环挡死，选不出下一首时直接顺延到下一首。
     */
    fun triggerCrossfadeNowDetailed(): String {
        fun fail(msg: String): String {
            Log.w(TAG, "Crossfade manual: skip ($msg)")
            lastManualTriggerReason = msg
            return msg
        }
        // 【V8.274】原因文案走 stringResource，英文系统下不再漏中文
        fun failRes(id: Int): String = fail(getString(id))
        // 【V8.362 修复】手动 DJ 切歌**不**卡总开关：总开关 crossfade_enabled 只决定"自动过渡
        // monitor"是否运行；手动切歌直接走 openIncoming/startCrossfade，与总开关无关。
        // 应用自身文案 settings_dj_still_on 也写明"总开关没开：手动切歌键仍可用"。
        // 原先这里用 !crossfadeEnabled 拦截，导致单开 DJ 模式（总开关仍关）时，每次手动切歌
        // 都被挡下、UI 统一显示"未触发"。现只校验 DJ 模式(djMode)本身。
        if (!xfadeCfg().djMode) return failRes(R.string.dj_reason_crossfade_off)
        if (crossfadeBusy) {
            // 【V8.271】① 卡死自愈：置位超过 30s 说明完成轮询没兜住，强制复位；
            // ② 正常过渡中：不再干拒，记下来等本次过渡结束自动补切（实测用户频繁撞上这个挡）。
            val held = SystemClock.elapsedRealtime() - crossfadeBusySinceMs
            if (held > 30_000L) {
                // V8.347: the 30s self-heal used to only clear the flag, so the tap that
                // finally cleared it did NOT cut anything -- the user had to tap twice.
                // Worse, any queued intent set before the stall was already lost.
                // Now: clear the flag AND service this tap, so one press always cuts.
                Log.w(TAG, "Crossfade manual: busy stuck ${held}ms -> force reset + serve this tap")
                crossfadeBusy = false
                crossfadeBusySinceMs = 0L
                crossfadeNextIndex = -1
                crossfadeUiInfo = CrossfadeUiInfo()
                pendingManualTrigger = false
                try { oboeDirectPlayer?.stopIncoming() } catch (_: Throwable) {}
            } else {
                pendingManualTrigger = true
                // 【V8.304】标记「已排队」而非「被拒」：UI 用不同口吻提示，别让用户
                // 以为这次点击丢了（实际过渡结束会自动补切）。
                lastManualTriggerQueued = true
                // 【V8.307 手动抢占】手动「立即切歌」的语义是"我现在就要切"：若此刻正有
                // 过渡在跑（多半是 bars=8 的 ~15s 自动过渡），把**当前**这条压缩到
                // PREEMPT_CUT_MS 内结束，随后既有 poll 检测到 active 翻转会自动补切。
                // 只放大 native 步长、不改 pos、不碰 A/B 轨与引擎 → 落点仍走完整正常路径，
                // 不引入新的拼接问题。用户自己设的过渡时长**不受影响**（这只压缩别人）。
                val cut = oboeDirectPlayer?.cutCrossfadeShort(PREEMPT_CUT_MS) == true
                Log.i(TAG, "Crossfade manual: preempt cut=$cut (busy ${held}ms) -> will switch on landing")
                return failRes(R.string.dj_reason_in_transition)
            }
        }
        lastManualTriggerQueued = false
        if (isDacActive()) return failRes(R.string.dj_reason_dac_busy)
        val p = oboeDirectPlayer ?: return failRes(R.string.dj_reason_player_not_ready)
        if (!p.isPrepared) return failRes(R.string.dj_reason_no_track)
        // 【V8.183】暂停状态下点「立即触发」会在没出声的情况下切走元数据和播放位置：
        // 没有回调在跑，active 轨永远不翻转，只能等 poll 超时后强制推进。直接挡掉。
        if (!p.isPlaying) return failRes(R.string.dj_reason_not_playing)
        // 【V8.183】先作废在飞的拍点触发，否则它会和这次手动触发各来一次 crossfade。
        cancelPendingBeatTrigger()
        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        if (songs.isEmpty()) return failRes(R.string.dj_reason_queue_empty)
        val realIdx = currentIndex
        var nextIndex = pickNextIndex(songs, realIdx).takeIf { it != realIdx && it >= 0 } ?: -1
        // 【V8.271】手动触发要「此刻就切一首」：选曲失败/只剩自己时顺延下一首（单曲循环也放行）
        if (nextIndex < 0) nextIndex = (realIdx + 1) % songs.size
        if (nextIndex == realIdx) return failRes(R.string.dj_reason_single_song)
        val nextSong = songs[nextIndex]
        val fp = nextSong.filePath.ifEmpty { nextSong.path }
        val path = if (fp.startsWith("content://")) resolveContentUriToPath(fp) else fp
        if (path.isNullOrBlank()) return failRes(R.string.dj_reason_next_path_invalid)
        crossfadeBusy = true   // 立刻占位，防连点
        crossfadeBusySinceMs = SystemClock.elapsedRealtime()
        armCrossfadeBusyWatchdog()
        crossfadeNextIndex = nextIndex
        // 【V8.314】**提前**请求分轨窗口。分轨是预计算制（实测 90s 窗口要 ~30s），
        // 过渡瞬间才请求就来不及 —— 那样手动切歌永远第一次没效果、第二次才有。
        // 在 openIncoming（configure MediaCodec，本身就要几百 ms~秒）之前就发请求，
        // 让分离和轨道打开**并行**跑；切歌时 isWindowReady 大概率已就绪。
        // 从0 播（手动不 seek），所以窗口起点 0、长度取用户设定的过渡时长 + 余量。
        run {
            val mgr = com.sdw.music.player.core.stems.StemBusManager
            // 【V8.314】probePrecomputed 会顺带触发插件的全曲预计算（fire-and-forget），
            // 是窗口请求的**前置**：没预计算过的曲子，窗口请求拿不到东西。
            // 顺带说明：probePrecomputed 此前全项目**从未被调用** —— 这是「分轨一直没效果」
            // 的深层原因之一（除了手动路径没接，探测环节本身也没启动过）。
            mgr.probePrecomputed(this, path, p.getSampleRate(), p.getDurationMs().toLong())
            val gapMs = (adaptiveXfadeDur(nextSong).toLong() + 15_000L).coerceAtMost(120_000L)
            mgr.requestWindow(this, path, gapMs)
        }
        // 【V8.389】上一刀的开轨线程还在飞时，**必须区分「正常在飞」和「确实卡死」**。
        //
        // 【这是 V8.384 引入的回归】原来只要 `!manualXfadeOpenDone` 且距开轨不足
        // MANUAL_OPEN_POISONED_MS(10s) 就直接硬切 —— 于是**手速稍快的一串连点，
        // 每一刀都被降级成硬切**（实测 8 刀里 6 刀走 `hard cut directly`、
        // 交叉淡化 0 次命中），听感上"不像在切歌 / 没切对"。
        // 根因：把「手快（正常）」和「线程卡死（异常）」当成同一件事，
        // 用一个 10 秒长窗口一刀切。
        //
        // 现在按「距开轨时长」分流：
        //   >= MANUAL_OPEN_STALL_MS(1.5s) → 确实卡死 → 硬切（这一刀立刻换歌，保留兜底价值）
        //   <  MANUAL_OPEN_STALL_MS       → 正常在飞 → 排队等落点补切，**不降级交叉淡化**
        // 串行化仍然成立：排队期间不新起第二个开轨线程，由 pendingManualTrigger 单一消费。
        if (!manualXfadeOpenDone) {
            val openElapsed = SystemClock.elapsedRealtime() - manualXfadeOpenSinceMs
            if (openElapsed >= MANUAL_OPEN_STALL_MS) {
                Log.w(TAG, "Crossfade manual: prev open stuck ${openElapsed}ms -> hard cut directly")
                // 【V8.397】这一刀已判定为卡死并硬切，就不再等那个线程回来：
                // 置回 done，让**下一刀**能正常走 openIncoming，而不是被这里继续判成 stuck。
                manualXfadeOpenDone = true
                crossfadeBusy = false
                crossfadeBusySinceMs = 0L
                crossfadeNativeStarted = false
                crossfadeNextIndex = -1
                pendingManualTrigger = false
                cancelCrossfadeBusyWatchdog()
                handler.post { crossfadeUiInfo = CrossfadeUiInfo(); playNext() }
                return ""
            }
            // 正常在飞：这一刀排队，落点后自动补切 —— 不硬切、不新开线程。
            Log.i(TAG, "Crossfade manual: prev open in-flight ${openElapsed}ms -> queue this cut")
            pendingManualTrigger = true
            return ""
        }
        val openGen = ++manualXfadeGen
        manualXfadeOpenDone = false
        manualXfadeOpenSinceMs = SystemClock.elapsedRealtime()
        Thread {
            try {
                val opened = p.openIncoming(path)
                if (openGen != manualXfadeGen) {
                    // 【V8.384】本刀已被兜底硬切作废（开轨超时兜底推进过世代号）。
                    // 这个线程可能刚从 openIncoming 的永久阻塞里醒来，必须丢弃结果、
                    // 绝不能再起过渡，否则会和已经硬切出去的播放状态打架。
                    Log.w(TAG, "Crossfade manual: stale gen after openIncoming -> discard")
                    p.stopIncoming()
                } else if (!opened) {
                    Log.w(TAG, "Crossfade manual: openIncoming failed -> hard cut fallback")
                    p.stopIncoming()
                    // 【V8.385】native 侧 openIncoming 失败的原因改成「限时拿不到生命周期锁」
                    // （上一刀卡在 AMediaCodec_start 还占着锁）。这时交叉淡化不可能成功，
                    // 原实现只复位 busy —— 用户这一下点击完全白点。直接硬切换歌。
                    manualXfadeGen++
                    handler.post {
                        crossfadeUiInfo = CrossfadeUiInfo()
                        crossfadeBusy = false
                        crossfadeBusySinceMs = 0L
                        crossfadeNativeStarted = false
                        crossfadeNextIndex = -1
                        pendingManualTrigger = false
                        cancelCrossfadeBusyWatchdog()
                        playNext()
                    }
                } else {
                    // 【V8.306 撤销 8.302/8.303 的 3.5s 封顶】手动触发完全按用户在设置里选的
                    // 时长走（crossfade_bars → adaptiveXfadeDur），与自动过渡同一口径。
                    // 用户反馈 3.5s「太快了」——封顶是我加的，越过了用户的时长设置意图。
                    // 连点被挡的问题改由「已排队」提示（8.305）解释，不再靠偷偷缩短时长。
                    val cfDurBase = adaptiveXfadeDur(nextSong)
                    // 【P3】真实 Master Sync：微调过渡时长使落点落在 master 小节线（未开启时返回 0）
                    val cfDur = cfDurBase + masterSyncDurAdjustMs(nextSong, cfDurBase)
                    // [V8.343] shadow: master phase solve (observation only).
                    // Manual path starts B from 0 (no entry-point seek), so pass 0 and let
                    // the shadow solver skip -- there is no entry offset to align.
                    shadowSolvePhase(nextSong, 0L, cfDur)
                    applyXfadeTemplate(nextSong)   // 【V8.281】djay 模板
                    val djFx = resolveDjEffect(nextSong)
                    p.setCrossfadeEffect(djFx.first, djFx.second)
                    if (liveFxTransitionOnly()) setLiveFxBus(true)   // 【V8.271】过渡窗口挂上
                    // 【M2】beatmatch：手动触发同样走变速对齐
                    // 【V8.424】过渡采样音效已删除：手动切歌不再触发黑胶刹车，sfxMode=2 完全静默
                    val bm = beatmatchParams(nextSong)
                    applyTempoRamp(p)   // 【V8.427】渐变拉速须先于 setBeatmatch/arming 下发
                    p.setBeatmatch(bm.ratio, bm.aBpm, bm.bBpm, bm.phaseLock, bm.engage)
                    // 【V8.314】手动路径接分轨总线：手动从0 播（entryMs=0），与自动路径同一函数。
                    // 这一行是「手动听不到分轨效果」的直接修复 —— 以前手动路径完全没有分轨代码。
                    val stemActive = setupStemBusForXfade(p, nextSong, 0L, cfDur)
                    logTemplateBypassByStem(p, stemActive, nextSong)
                    val started = p.startCrossfade(cfDur, 0, songLufs(currentSong), songLufs(nextSong), 2, bm.aBpm)
                    if (started) {
                        // 【V8.419】入口对齐残差（观测）。这条路径 entryMs=0（B 从头起），
                        // 是**自然自动过渡**真正走的分支 —— 之前只挂在手动路径上，
                        // 结果自然过渡永远打不出对齐数字（V8.419 首轮验收的教训）。
                        logEntryAlignment(p, nextSong, 0, bm)
                    }
                    if (!started) {
                        // 【V8.384】startCrossfade 返回 false 常见两因：
                        //   ① native 重入守卫（日志 "StartCrossfade: already active, ignored"）
                        //      ——上一刀的 g_crossfadeActive / 预填充窗口还没退，稍等即恢复；
                        //   ② incoming 解码器被别的路径拆掉（"incoming(X) not opened"）。
                        // 原实现直接放弃这一刀：triggerCrossfadeNowDetailed 早已返回 ""，
                        // UI 认为成功，用户这一下点击**完全没反应且不报错**。
                        // 先给 250ms 让它收敛再原样重试一次；仍失败才退回硬切。
                        Log.w(TAG, "Crossfade manual: startCrossfade failed -> settle & retry once")
                        try { Thread.sleep(250) } catch (_: Throwable) {}
                        val retried = openGen == manualXfadeGen &&
                            p.startCrossfade(cfDur, 0, songLufs(currentSong), songLufs(nextSong), 2, bm.aBpm)
                        if (retried) {
                            Log.i(TAG, "Crossfade manual: retry ok")
                            handler.post {
                                crossfadeUiInfo = crossfadeUiInfo.copy(
                                    state = XfadeUiState.ACTIVE,
                                    durationMs = cfDur,
                                    startedAtMs = SystemClock.elapsedRealtime(),
                                    nextTitle = nextSong.title,
                                    nextArtist = nextSong.artist,
                                    nextAlbumArt = nextSong.albumArtUri.takeIf { SongRepository.hasRealCover(it) }
                                )
                                startXfadeLevelSampler()
                            }
                            crossfadeNativeStarted = true
                            scheduleCrossfadeCompletionPoll(p, nextSong, nextIndex, fromManual = true, cfDurMs = cfDur.toLong())
                        } else {
                            Log.w(TAG, "Crossfade manual: retry failed -> hard cut fallback")
                            p.stopIncoming()
                            manualXfadeGen++
                            handler.post {
                                crossfadeUiInfo = CrossfadeUiInfo()
                                crossfadeBusy = false
                                crossfadeBusySinceMs = 0L
                                crossfadeNativeStarted = false
                                crossfadeNextIndex = -1
                                pendingManualTrigger = false
                                cancelCrossfadeBusyWatchdog()
                                playNext()
                            }
                        }
                    } else {
                        handler.post {
                            crossfadeUiInfo = crossfadeUiInfo.copy(
                                state = XfadeUiState.ACTIVE,
                                durationMs = cfDur,
                                startedAtMs = SystemClock.elapsedRealtime(),
                                nextTitle = nextSong.title,
                                nextArtist = nextSong.artist,
                                nextAlbumArt = nextSong.albumArtUri.takeIf { SongRepository.hasRealCover(it) }
                            )
                            startXfadeLevelSampler()
                        }
                        // 【V8.312·A】手动切歌：fromManual=true，不计入自动闸门计时
                        crossfadeNativeStarted = true
                        scheduleCrossfadeCompletionPoll(p, nextSong, nextIndex, fromManual = true, cfDurMs = cfDur.toLong())
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Crossfade manual exception ${e.message}")
                handler.post { crossfadeUiInfo = CrossfadeUiInfo(); crossfadeBusy = false }
            } finally {
                // 【V8.397 修正】这里**必须**保留世代校验。
                // 试过无条件恢复（`manualXfadeOpenDone = true`）：结果 19 刀里 13 刀被拒，
                // 原因是「播放器未就绪（未连接）」—— 因为闸门失效后会同时起多个 open 线程，
                // 并发 openIncoming 互相 stop codec 把播放器搞成未就绪，换歌数反而从 21 掉到 7。
                // 正确的做法是：只在「确认这一刀已卡死并已硬切」的地方恢复该标志
                // （见闸门分支与 stalled 分支），而不是在线程收尾处无条件恢复。
                if (openGen == manualXfadeGen) manualXfadeOpenDone = true
            }
        }.apply { isDaemon = true }.start()
        // 【V8.383】开轨卡死兜底：MediaCodec 资源紧张时 openIncoming 会永久阻塞
        // （日志止于 "Copied max-input-size"，既不出 NDK Decoder 也不出 startCrossfade），
        // 那个线程再也回不来 → busy 永远不清 → 之后每一下点击都只是排队。
        // 给一个上界：超时就强制复位，让下一次点击仍是「新的一刀」。
        xfadeHandler.postDelayed({
            if (openGen == manualXfadeGen && crossfadeBusy && !crossfadeNativeStarted && !manualXfadeOpenDone) {
                Log.w(TAG, "Crossfade manual: openIncoming stalled -> force reset busy + hard cut")
                // 【V8.384】开轨卡死（MediaCodec 资源耗尽）时这一刀已不可能走交叉淡化。
                // 原实现只复位 busy，用户这一下点击就白白丢掉 —— 正是「连切几首后
                // 再也切不动」的观感来源。先推进世代号作废这一刀（卡住的那个线程
                // 醒来后会自行丢弃结果），再硬切，保证每一下点击都真的换歌。
                manualXfadeGen++
                // 【V8.397】++gen 会让卡住那个线程的 finally 世代校验失败、从而永不恢复
                // manualXfadeOpenDone —— 这就是「stuck 从 2.2s 一路涨到 22s」的 sticky 成因。
                // 既然已经作废这一刀并硬切，就在这里把标志置回，让下一刀重新有机会
                // 走真正的交叉淡化（而不是被闸门一律降级成硬切）。
                manualXfadeOpenDone = true
                forceResetCrossfadeBusy()
                handler.post { playNext() }
            }
        }, MANUAL_OPEN_STALL_MS)
        lastManualTriggerReason = ""
        return ""
    }

    /** 【V8.183】作废在飞的拍点延迟触发。
     *  这枚回调走的是主线程 handler（xfadeHandler.removeCallbacksAndMessages 清不到它），
     *  所以在「用户手动切歌」「关闭 crossfade」这两条路径上必须显式摘掉 + 换令牌，
     *  否则 beatDelay 到点时它会拿上一轮那个已经丢掉的 B 轨发起 crossfade。
     *  换令牌的副作用是：即便回调已经出现在队列里，执行时也会因令牌不符自行退出。 */
    private fun cancelPendingBeatTrigger() {
        xfadeBeatToken++
        xfadeBeatRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Throwable) {} }
        xfadeBeatRunnable = null
        xfadeBeatPending = false
    }

    // =====================================================================
    // 【V8.381】crossfadeBusy 看门狗：基于时间(而非"下次点击")强制复位。
    // 旧 30s 自愈只在"再次点击"时触发 —— 用户停手期间 busy 永久卡死(老 bug"只能切一次")。
    // 这里改为时间驱动，两层：
    //   · 置位 >15s 且从未进入 native 过渡 → 视为触发线程卡死(多半是插件/原生调用阻塞)，强制复位；
    //   · 置位 >30s(任何情况) → 兜底复位，覆盖完成轮询漏清 busy 的残留。
    // 正常 crossfade(默认 5s、最长 bars=32≈20s)都会在 30s 内由轮询正常清 busy，不会被误杀。
    // =====================================================================
    /** 【V8.383】手动切歌开轨线程的最大容忍时长，超过即认为卡死并强制复位。 */
    // 【V8.384】实测成功开轨只要 90~300ms；原 4000ms 让卡死那一刀要干等 4s 才硬切，
    // 用户观感是「点了没反应，隔很久突然跳一首」。收到 1500ms：既不会误杀正常的慢开轨，
    // 又能让卡死的那一下很快有结果。
    private val MANUAL_OPEN_STALL_MS = 1500L
    /** 【V8.384】开轨线程一旦被判定卡死，它**永远不会回来**（MediaCodec 资源已耗尽）。
     *  在这段窗口内不要再起第二个开轨线程——起了也一定再卡，只会继续泄漏解码器。
     *  这段时间内的点击一律直接硬切，立刻换歌。 */
    private val CROSSFADE_WATCHDOG_HANG_MS = 15_000L
    private val CROSSFADE_WATCHDOG_HARD_MS = 30_000L
    private val crossfadeWatchdog = object : Runnable {
        override fun run() {
            if (!crossfadeBusy) { cancelCrossfadeBusyWatchdog(); return }
            val held = SystemClock.elapsedRealtime() - crossfadeBusySinceMs
            if (held > CROSSFADE_WATCHDOG_HARD_MS ||
                (held > CROSSFADE_WATCHDOG_HANG_MS && !crossfadeNativeStarted)) {
                Log.w(TAG, "Crossfade: busy stuck ${held}ms (nativeStarted=$crossfadeNativeStarted) -> watchdog force reset")
                forceResetCrossfadeBusy()
            } else {
                // 仍在合理窗口内：继续盯
                xfadeHandler.postDelayed(this, 2000L)
            }
        }
    }
    private fun forceResetCrossfadeBusy() {
        // 【V8.383】必须先清标志位，原生收尾挪到最后且放后台线程。
        // 旧实现第一句就是 oboeDirectPlayer?.stopIncoming()，而触发线程此时可能正卡在
        // native openIncoming 里并持有同一把锁 —— stopIncoming() 会跟着一起卡死，
        // 表现是「日志明明打了 watchdog force reset，busy 却一路涨到 22s」：
        // 复位根本没执行到 crossfadeBusy=false。
        crossfadeBusy = false
        crossfadeBusySinceMs = 0L
        crossfadeNativeStarted = false
        crossfadeNextIndex = -1
        pendingManualTrigger = false
        crossfadeUiInfo = CrossfadeUiInfo()
        // 原生收尾放后台：卡住也不影响上面的复位。
        // 【V8.383 修正】开轨线程还没跑完时**不要** stopIncoming：它会在新解码器
        // 配置途中把刚建好的那个拆掉，日志表现就是 CCodecConfig "config failed => CORRUPTED"
        // 叠加 "Failed to query component interface" —— 实测会让后续每一刀都拿不到解码器
        // （8 次连点从 8/8 掉到 1/8）。卡死场景本来也拆不动，跳过它是安全的。
        if (manualXfadeOpenDone) {
            Thread {
                try { oboeDirectPlayer?.stopIncoming() } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
        }
        scheduleCrossfadeMonitor(200)
    }
    private fun armCrossfadeBusyWatchdog() {
        // 新一轮 crossfade 起算：清掉上一轮可能残留的 nativeStarted 标记
        crossfadeNativeStarted = false
        xfadeHandler.removeCallbacks(crossfadeWatchdog)
        xfadeHandler.postDelayed(crossfadeWatchdog, 2000L)
    }
    private fun cancelCrossfadeBusyWatchdog() {
        xfadeHandler.removeCallbacks(crossfadeWatchdog)
    }

    private fun scheduleCrossfadeCompletionPoll(p: OboeDirectPlayer, nextSong: Song, nextIndex: Int,
  /** 【V8.312·A】这次过渡是手动触发的吗？手动是用户明确意图，不该启动自动闸门计时。 */
        fromManual: Boolean = false,
        /** 【V8.380】本次切歌实际用的过渡时长（即 startCrossfade 的入参）。完成判定主信号用它，
         *  不再依赖被 monitor 每 tick 改写的全局 crossfadeDurationMs（DJ bars 模式下可达数十秒，
         *  导致 busy 卡死、只能切一次）。 */
        cfDurMs: Long = crossfadeDurationMs.toLong()) {
        // [2026-09-10 fix] Remember the pending B track so a timed-out poll can still
        // reconcile metadata with the deterministically completed native crossfade.
        xfadePendingSong = nextSong
        xfadePendingIndex = nextIndex
        // 轮询直到 active 轨翻转（crossfade 完成），然后切元数据 + 继续下一轮监控
        val startActiveB = p.isActiveB()
        var sawActive = false   // 【V8.369】兜底层：一旦观测到原生 crossfade 在跑，
        // 之后它变 false（g_crossfadeActive 落 false）即视为过渡结束，不依赖 isActiveB 翻转。
        // 这是「DJ 手动切歌只能切一次、之后点多少都没反应」的根因：仅看 isActiveB 翻转时，
        // 某些过渡落点没触发翻转(或翻转与 startActiveB 同值)，busy 只能等 8s 超时兜底，
        // 期间所有点击撞 busy 被排队、preempt 又压缩失败 → 表现为「切一次就卡死」。
        val pollStart = System.currentTimeMillis()
        // 【V8.380】完成判定的主信号：本次切歌实际过渡时长 cfDurMs + 缓冲。startCrossfade 用的就是它，
        // 所以我们确切知道过渡应在 pollStart+cfDurMs 结束。原生 isActiveB 翻转 / isCrossfading 结束
        // 在 DJ 长过渡里常前后不变（不可靠），旧逻辑只能等 crossfadeDurationMs+3000 超大超时 → busy 卡死。
        val cfEndMs = cfDurMs + 1500L
        val hardCapMs = cfDurMs + 12_000L
        val poller = object : Runnable {
            override fun run() {
                if (isDestroyed) { crossfadeBusy = false; return }
                val nowB = p.isActiveB()
                val xfActive = p.isCrossfading()
            if (xfActive) sawActive = true
            val elapsed = System.currentTimeMillis() - pollStart
            // crossfade 完成 = active 轨状态与触发前相反，
            // 或原生 crossfade 已结束（兜底层，防止翻转检测漏掉导致 busy 卡死），
            // 或已经过本次实际过渡时长+缓冲（【V8.380】主兜底：原生信号不可靠时长也能稳定清 busy）
            if (nowB != startActiveB || (sawActive && !xfActive) || elapsed >= cfEndMs) {
                    // 【V8.263】stem 总线收尾：四轨环/供数器退役，恢复常规单混音路径
                    try { p.endStemMode() } catch (_: Throwable) {}
                    // 【V8.312·A】记落点时刻：闸门的计时基准是「上一次自动过渡**结束**」，
                    // 而不是发起时刻 —— 否则一段 15s 的过渡会把闸门算成"已过 15s"而形同虚设。
                    // 手动切歌不计入：否则用户手动连切两首后，自动闸门会莫名其妙进入等待。
                    if (!fromManual) lastAutoXfadeDoneAtMs = SystemClock.elapsedRealtime()
                    // 【V8.331】B 轨上位即记入「最近听过」，否则它的 (1) 副本下一轮立刻被选中
                    markPlayedSong(nextSong)
                    // 【V8.4】crossfade 完成 → DONE：UI 保留叠加层直到封面 URI 切换（防变两次跳变）
                    crossfadeUiInfo = crossfadeUiInfo.copy(state = XfadeUiState.DONE)
                    handler.post {
                        currentSong = nextSong
                        currentIndex = nextIndex
                        notifySongChanged(nextSong)
                        updateNotification()
                    }
                    crossfadeBusy = false
                    crossfadeNativeStarted = false
                    crossfadeNextIndex = -1
                    // 【V8.271】过渡结束 → 实时效果旁路（仅过渡时生效）
                    if (liveFxTransitionOnly()) setLiveFxBus(false)
                    if (pendingManualTrigger) {
                        // [V8.343] Queued manual cut delay 500ms -> 1200ms.
                        // Right after landing this poll still starts a background prescan
                        // (preScanPath configures its own MediaCodec). Firing after 500ms made
                        // openIncoming race it; native tore down the freshly built incoming codec
                        // and StartCrossfade failed with "incoming(B) not opened" -> the cut was
                        // dropped and the picker kept proposing the same songs (the "repeats" bug).
                        pendingManualTrigger = false
                        // V8.347: retry, and never let a throw kill the poll chain.
                        // Symptom this fixes: user taps cut while a transition is running
                        // -> UI says queued, then NOTHING happens and every later tap is
                        // swallowed too (user reports "once it queues, nothing cuts").
                        // Cause: this block is the ONLY consumer of pendingManualTrigger.
                        // If the runnable throws, or triggerCrossfadeNowDetailed() returns
                        // a rejection, the intent is silently dropped -- the flag was already
                        // cleared, so nothing retries.
                        try {
                            val r = triggerCrossfadeNowDetailed()
                            if (r.isEmpty()) {
                                Log.i(TAG, "Crossfade manual: queued trigger -> ok")
                            } else {
                                // Rejected again: restore the flag so the next poll or user
                                // tap retries instead of losing the intent.
                                Log.w(TAG, "Crossfade manual: queued trigger REJECTED ($r) -> re-queue")
                                pendingManualTrigger = true
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Crossfade manual: queued trigger threw ${t.message} -> re-queue")
                            pendingManualTrigger = true
                        }
                    }
                    xfadePendingSong = null
                    xfadePendingIndex = -1
                    // 【V8.10】B 上位成为新 A 轨：重新预扫其尾部静音段 + 副歌结束，供下一轮 crossfade 触发使用
                    // 【v8.166】优先查缓存；只在缺项时才后台解码预扫（预扫 ~15s，纯属浪费）
                    xfadeAPrescanDone = false
                    xfadeAQuietStartMs = -1L
                    xfadeAChorusEndMs = -1L
                    xfadeABeatPhaseMs = -1L
                    xfadeBFirstBeatMs = -1L
                    // 【V8.16】B 上位重扫副歌结束只扫实际起点之后（防止把 B 当新歌从头扫，定位到后段副歌 → 播几十秒又切）
                    val bStartForScan = if (xfadeLastBStartMs > 0) xfadeLastBStartMs else 0L
                    val newAPath = nextSong.filePath.ifEmpty { nextSong.path }
                    val newAActual = if (newAPath.startsWith("content://")) {
                        resolveContentUriToPath(newAPath)
                    } else {
                        newAPath
                    }
                    if (newAActual != null) {
                        com.sdw.music.player.BpmKeyCache.init(this@MusicService)
                        val aStruct = com.sdw.music.player.BpmKeyCache.getStructure(newAActual)
                        val aCachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(newAActual)
                        // 【V8.343 Master Sync 影子模式】B 上位 = 新的 master 相位起点。
                        // 只累加拍数 + 统计，不改变任何播放行为（不改入场点/时长/native）。
                        // 落点那一拍的偏差由 onLanding 汇总，便于评估跨曲目相位是否真的连续。
                        try {
                            val nbF = com.sdw.music.player.BpmKeyCache.getBpmFloat(newAActual)
                                .takeIf { it >= 40f && it <= 220f } ?: 0f
                            MasterSyncShadow.onSongStart(
                                newAActual,
                                nbF,
                                aCachedBeat.toDouble()
                            )
                            MasterSyncShadow.onLanding(null)
                        } catch (_: Throwable) {}
                        if (aCachedBeat > 0) xfadeABeatPhaseMs = aCachedBeat.toLong()
                        if (bpmChorusMode && aStruct.chorusEndMs > 0) {
                            xfadeAChorusEndMs = aStruct.chorusEndMs.toLong()
                            Log.i(TAG, "Crossfade: prescan-A(next) chorusEnd=${aStruct.chorusEndMs} ms (cached)")
                        }
                        if (aStruct.tailQuietMs >= 0) {
                            xfadeAQuietStartMs = if (aStruct.tailQuietMs > 0) aStruct.tailQuietMs.toLong() else -1L
                            xfadeAPrescanDone = true
                        }
                        val needChorus = bpmChorusMode && aStruct.chorusEndMs <= 0
                        val needQuiet = aStruct.tailQuietMs < 0
                        if (needChorus || needQuiet || aCachedBeat <= 0) {
                            // 【V8.393】预扫描串行化（并发恒为 1），杜绝一次性 codec 风暴
                            prescanExecutor.execute {
                                try {
                                    // 【V8.263 NeuralMix】候选窗口早期请求：下一曲 stem 预计算。
                                    // 【V8.282】窗口 240s→45s：插件会把任何窗口钳到 90s 上限，
                                    // 240s 只是白花 63MB/首（i16 后），而入场点超过 90s 的曲子
                                    // 照样命中不了 —— 那部分已经由过渡时的 requestWindowAt
                                    // （按 entryMs-3s 起存 45s）精确兜住。这里只做最便宜的预热。
                                    // 【V8.474 修"B 轨过渡完成后几秒的噪音"· 延后分轨预热】
                                    //
                                    // 用户取证（14:52:34）：噪音出现在**B 轨成为 active 之后几秒**，
                                    // 且每次切歌通知栏都会弹
                                    //   "NeuralMix 分轨插件 / 正在为下一首预计算分轨(45s 窗口)"
                                    // 两者时间完全重合。
                                    //
                                    // 插件是**独立进程**：收到 requestPrecomputeWindow 后要自己
                                    // 打开文件、建 MediaCodec 解 45 秒、再跑神经网络分离 ——
                                    // CPU / 内存带宽 / IO 同时被打满。而这段请求原本是**切歌瞬间**
                                    // 发出的，正好撞在 B 轨刚上位、最需要稳定供给的那几秒上。
                                    // 于是 Oboe callback 抖动、解码线程被拖慢，听感就是几秒噪音。
                                    //
                                    // 这也是为什么我在音频链路里怎么查都查不干净 ——
                                    // 音频数据本身没问题，是**系统资源被抢**。
                                    //
                                    // 修法：把这个"最便宜的预热"延后 8 秒再发，避开 B 轨进场关键期。
                                    // 代价只是预热晚 8 秒（分离本身要几十秒，8 秒无感）。
                                    try {
                                        if (newAActual.isNotBlank() && !newAActual.startsWith("content://")) {
                                            val stemPath = newAActual
                                            android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed({
                                                    try {
                                                        com.sdw.music.player.core.stems.StemBusManager
                                                            .requestWindow(this@MusicService, stemPath, 45_000L)
                                                        Log.i(TAG, "StemBus: 延后 8s 的下一曲预热已发出 ($stemPath)")
                                                    } catch (_: Throwable) {}
                                                }, 8_000L)
                                            Log.i(TAG, "StemBus: 下一曲预热已排队(延后8s, 避开 B 轨进场峰值) $newAActual")
                                        }
                                    } catch (_: Throwable) {}
                                    if (needChorus) {
                                        val aChorusEnd = p.preScanPath(newAActual, 100, mode = 3, seekFromMs = bStartForScan)
                                        if (aChorusEnd > 0) {
                                            xfadeAChorusEndMs = aChorusEnd.toLong()
                                            Log.i(TAG, "Crossfade: prescan-A(next) chorusEnd=$aChorusEnd ms (from $bStartForScan)")
                                        }
                                        com.sdw.music.player.BpmKeyCache.mergeStructure(
                                            newAActual, chorusEndMs = if (aChorusEnd > 0) aChorusEnd else 0
                                        )
                                    }
                                    if (aCachedBeat <= 0) {
                                        val aBeat = p.preScanPath(newAActual, 100, mode = 4, seekFromMs = 0L)
                                        if (aBeat > 0) xfadeABeatPhaseMs = aBeat.toLong()
                                    }
                                    if (needQuiet) {
                                        // 【V8.386】优先用已缓存的能量包络算尾奏：**不建MediaCodec**。
                                        // 原实现每首歌都要新建一个解码器解尾部、扫完即弃，
                                        // 加上 mode=3/4 的两次预扫描，一首歌凭空多出 3 个一次性
                                        // codec —— 这是 20 刀连点后交叉淡化全退化成硬卡的根因之一
                                        // （V8.385 / task #85 #86）。包络没缓存时才退回原生预扫描。
                                        var quiet = -1
                                        val envHit = com.sdw.music.player.core.audio.playback.WaveformCache.cached(newAActual)
                                        if (envHit != null) {
                                            val durMs = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                                                .durationMsOf(newAActual)
                                            if (durMs > 0) {
                                                quiet = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                                                    .tailQuietMs(envHit, durMs)
                                                Log.i(TAG, "Crossfade: prescan-A(next) tailQuiet from envelope=$quiet ms (dur=$durMs, no codec)")
                                            }
                                        } else {
                                            Log.i(TAG, "Crossfade: envelope miss for ${newAActual.takeLast(40)}, use native prescan")
                                        }
                                        if (quiet < 0) {
                                            quiet = p.preScanPath(newAActual, 100, mode = 0, seekFromMs = 0L)
                                        }
                                        xfadeAQuietStartMs = quiet.toLong()
                                        if (quiet > 0) Log.i(TAG, "Crossfade: prescan-A(next) quietStart=$quiet ms")
                                        else Log.i(TAG, "Crossfade: prescan-A(next) no quiet segment (fallback RMS)")
                                        com.sdw.music.player.BpmKeyCache.mergeStructure(
                                            newAActual, tailQuietMs = if (quiet > 0) quiet else 0
                                        )
                                    }
                                    xfadeAPrescanDone = true
                                    com.sdw.music.player.BpmKeyCache.scheduleFlush()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Crossfade: prescan-A(next) failed ${e.message}")
                                    xfadeAQuietStartMs = -1L
                                    xfadeAPrescanDone = true
                                }
                            }
                        }
                    } else {
                        xfadeAPrescanDone = true
                    }
                    // 继续监控下一轮
                    // [V8.74] Re-arm exactly once. Without the removeCallbacks every
                    // completed crossfade left an EXTRA monitor loop running ~20ms behind
                    // the original; the duplicate could then start a SECOND crossfade on
                    // the same player, so audio moved to a track the UI was never told
                    // about (the "UI shows A, audio plays C" report).
                    scheduleCrossfadeMonitor(200)
                    return
                }
                if (elapsed > hardCapMs) {
                    // 【V8.319 补漏】分轨总线收尾：正常落点(1382) 有endStemMode()，但**超时
                    // 路径原来没有**。超时 = 过渡没按预期翻转，stem 供数器/四轨环仍挂着，
                    // 而 crossfadeBusy 已清 → 下一次切歌会**叠加**开stem 总线
                    //（beginStemMode 会 nativeStemReset 清环，叠加导致听到的是空stem/突变）。
                    try { p.endStemMode() } catch (_: Throwable) {}
                    // [2026-09-10 fix] Timeout. The native crossfade duration is deterministic, so a
                    // crossfade that started HAS completed and the audio is on B already. Advancing
                    // metadata here is mandatory: the old code only cleared the UI state and left
                    // currentSong on A, which caused a persistent cover/title vs playing-song mismatch.
                    val pendSong = xfadePendingSong
                    val pendIdx = xfadePendingIndex
                    crossfadeUiInfo = CrossfadeUiInfo()
                    crossfadeBusy = false
                    crossfadeNativeStarted = false
                    crossfadeNextIndex = -1
                    xfadePendingSong = null
                    xfadePendingIndex = -1
                    if (pendSong != null && pendIdx >= 0) {
                        Log.w(TAG, "Crossfade poll timeout: force-advance metadata to B index=$pendIdx")
                        handler.post {
                            currentSong = pendSong
                            currentIndex = pendIdx
                            notifySongChanged(pendSong)
                            updateNotification()
                        }
                    }
                    // [V8.74] Re-arm exactly once. Without the removeCallbacks every
                    // completed crossfade left an EXTRA monitor loop running ~20ms behind
                    // the original; the duplicate could then start a SECOND crossfade on
                    // the same player, so audio moved to a track the UI was never told
                    // about (the "UI shows A, audio plays C" report).
                    scheduleCrossfadeMonitor(200)
                    return
                }
                // 【v8.165 省电】crossfade 完成轮询挪到后台线程（仅 crossfade 进行中运行）
                xfadeHandler.postDelayed(this, 100)
            }
        }
        xfadeHandler.postDelayed(poller, 100)
    }

    // USB DAC Exclusive mode controller
    // =====================================================================
    // 【USB DAC 独占控制器区】controller 生命周期 / dacHealthRunnable 看门狗 / usbDacCallback 热插拔 / wake lock / claim
    // =====================================================================
    @Volatile private var usbDacController: UsbDacPlaybackController? = null
    private var dacPlayGeneration: Int = 0  // [V4.0.1] invalidate stale onCompletion
    // 【V9.02 续】DAC 初始化「在途合并」闸门：background 化后，快速连点 / 重复回调会在极短窗口内
    // 触发两次完整 open()+play()+DSP 重发（双 controller 会话），既重复耗时又造成 16↔24 协商抖动爆音。
    // dacInitInFlight 保证同一时刻只有一路 DAC 初始化在跑；dacInitPendingIndex 缓存最后连点的目标，
    // 当前路结束后补跑（last-wins），避免双播/卡顿。
    @Volatile private var dacInitInFlight: Boolean = false
    @Volatile private var dacInitPendingIndex: Int = -1
    private var dacWakeLock: PowerManager.WakeLock? = null  // [V4.0.2] prevent CPU deep-sleep in Doze mode

    // 
    private var dspEqEnabled: Boolean = false

    // [V8.x] AudioDeviceCallback: detect USB DAC hotplug -> restart Oboe Exclusive
    @Volatile  // [V4.1.x] Oboe pause/resume state �� avoids JNI isPlaying lag in getPlayWhenReady()
    private var oboePlayWhenReady: Boolean = true
    private var oboeUsbGuardMs: Long = 0L
    private var oboeSuppressUsbRestart: Boolean = false  // [V8.2] block USB restarts when Oboe is already playing
    // 【V8.295】蓝牙断开主动重建节流（AudioDeviceCallback 可能连发，避免重建风暴）
    @Volatile private var lastBtRouteReopenMs: Long = 0L
    private val usbDacCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (System.currentTimeMillis() < oboeUsbGuardMs) return  // startup race guard
            // 【2026-09-07】蓝牙 A2DP 连接/编码切换 → 刷新预补偿
            val anyBt = addedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (anyBt) refreshBtPreEmphasis()
            // 【V9.08】蓝牙接入：强制 Shared 模式，避免 TWS 单边掉电
            if (anyBt) oboeDirectPlayer?.applyBluetoothSafeSharingMode()
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
        if (!anyBt) return
        refreshBtPreEmphasis()
        // 【V8.295】蓝牙断开：默认路由回到扬声器。主动按默认路由重建输出流，
        // 抢在「系统 A2DP 拆链(~2s) + AAudio ErrorDisconnected + onErrorAfterClose 重建」之前，
        // 把「连/断蓝牙静音好几秒」压到 ~1s 以内。看门狗只在「回调停滞」时动作，
        // 这里补上「回调仍在跑、但写进已死 A2DP 端点」的情形。后台线程执行避免主线程卡顿。
        val now = System.currentTimeMillis()
        if (now - lastBtRouteReopenMs >= 400L && isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
            lastBtRouteReopenMs = now
            Log.i(TAG, "BT removed: proactively reopening Oboe output on default route")
            // 【V9.08】回到默认路由（有线/扬声器）→ 允许 Exclusive（Bit-Perfect）
            oboeDirectPlayer?.applyBluetoothSafeSharingMode()
            this@MusicService.xfadeHandler.post { oboeDirectPlayer?.reopenOutputOnRouteReady() }
        }
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
        handler.removeCallbacks(dacSilenceRunnable)  // [P2]
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
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> this@MusicService.playNext(fromUser = true)
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> this@MusicService.playPrevious(fromUser = true)
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
        // [V8.93] 死链接（albumart 虚拟表）不要塞进 MediaMetadata.artworkUri，否则通知/锁屏
        // 会去解一张永远取不到的图，封面位置反而留空。
        val artworkUri = if (SongRepository.hasRealCover(song.albumArtUri)) android.net.Uri.parse(song.albumArtUri) else null
        val displayArtist = if (song.artist.isBlank() || SongRepository.isPlaceholderArtist(song.artist)) getString(R.string.app_name_moto) else song.artist
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

    // ===== Android Auto / MediaLibrary browsing helpers =====
    private val AUTO_ROOT_ID = "__AUTO_ROOT__"
    private val AUTO_ALL_SONGS_ID = "auto_all_songs"
    private val AUTO_PLAYLISTS_ID = "auto_playlists"

    private fun autoCategoryItem(mediaId: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun autoSongItem(song: Song, parentId: String): MediaItem {
        val artworkUri = if (SongRepository.hasRealCover(song.albumArtUri)) android.net.Uri.parse(song.albumArtUri) else null
        val displayArtist = if (song.artist.isBlank() || SongRepository.isPlaceholderArtist(song.artist)) getString(R.string.app_name_moto) else song.artist
        return MediaItem.Builder()
            .setMediaId("auto_tr:${song.id}@$parentId")
            .setUri(song.path)
            .setMimeType(getMimeType(song.format))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(displayArtist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(artworkUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun handleAutoMediaId(mediaId: String) {
        try {
            when {
                mediaId.startsWith("auto_tr:") -> {
                    val body = mediaId.removePrefix("auto_tr:")
                    val sep = body.indexOf('@')
                    val idStr = if (sep >= 0) body.substring(0, sep) else body
                    val parent = if (sep >= 0) body.substring(sep + 1) else AUTO_ALL_SONGS_ID
                    val songId = idStr.toLongOrNull() ?: return
                    val list = if (parent.startsWith("auto_pl:")) {
                        val plId = parent.removePrefix("auto_pl:").toLongOrNull() ?: return
                        PlaylistManager.getSongsInPlaylist(plId)
                    } else {
                        SongRepository.getSongs()
                    }
                    if (list.isEmpty()) return
                    setSongs(list, source = "Auto")
                    val idx = list.indexOfFirst { it.id == songId }
                    if (idx >= 0) playSong(idx)
                }
                mediaId.startsWith("auto_pl:") -> {
                    val plId = mediaId.removePrefix("auto_pl:").toLongOrNull() ?: return
                    val list = PlaylistManager.getSongsInPlaylist(plId)
                    if (list.isEmpty()) return
                    setSongs(list, source = "Auto:Playlist")
                    playSong(if (isShuffleMode) list.indices.random() else 0)
                }
                mediaId == AUTO_ALL_SONGS_ID -> {
                    val list = SongRepository.getSongs()
                    if (list.isEmpty()) return
                    setSongs(list, source = "Auto:All")
                    playSong(if (isShuffleMode) list.indices.random() else 0)
                }
                else -> {
                    // 非浏览媒体项（如本 App 自身播放），交给现有引擎，不做拦截
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAutoMediaId failed for '$mediaId': ${e.message}")
        }
    }

    private fun songFromBrowseMediaId(mediaId: String): Song? {
        if (!mediaId.startsWith("auto_tr:")) return null
        val idStr = mediaId.removePrefix("auto_tr:").substringBefore("@")
        val songId = idStr.toLongOrNull() ?: return null
        return SongRepository.getSongById(songId)
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
        // [2026-09-10 fix] StateFlow -> SharedFlow(replay=1). StateFlow dedups by value and a
        // new subscriber only ever receives the single latest emission; SharedFlow(replay=1)
        // guarantees the latest authoritative currentSong reaches ANY collector created later
        // (e.g. PlayerConnection rebuilt after Activity/ViewModel was recreated in background),
        // fixing 'UI cover/title differs from the song actually playing'.
        private val _songChangedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Song?>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
        val songChangedFlow: kotlinx.coroutines.flow.SharedFlow<Song?> = _songChangedFlow

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
            _songChangedFlow.tryEmit(song)  // 
            // [Phase 1] 同步刷新 MediaSession 状态机（歌名/封面/时长/播放列表变化）
            try { instance?.musicPlayerState?.refresh() } catch (_: Exception) {}
            // Save SharedPreferences
            if (song != null) {
                savePlaybackState()
                // 【V8.231】响度归一化必须逐曲重算：此前只在「播放器重建」那一处下发过一次，
                // 于是整场播放共用同一枚增益（新歌沿用上一首的 ReplayGain 修正量）——
                // 这正是"切歌后音量不对/某首特别响"的根因。LoudnessTags.read 是文件 IO，
                // 走后台线程；applyNormalization 只是 native setter，安全。
                runCatching {
                    val svc = instance
                    if (svc != null) svc.analysisScope.launch {
                        runCatching { svc.refreshNormalization() }
                            .onFailure { android.util.Log.w("MusicService", "norm refresh: ${it.message}") }
                    }
                }
                // 【V8.386】DJ 模式预热能量包络 + 尾奏/Drop（纯 Kotlin，不建 MediaCodec）。
                // 不预热的话，切歌热路径永远拿不到包络，V8.386 的两条路全是死代码。
                runCatching {
                    val svc = instance
                    // 【V8.437】warmDjStructure 现在同时负责 kick 三段包络 ——
                    //那是**djay 式波形着色**的数据来源，属于纯视觉功能，
                    // 不该只在 DJ 模式下才跑（否则普通播放进 DJ 页永远只有单色波形）。
                    if (svc != null) svc.warmDjStructure(song)
                }
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
                // [V8.94] 同上：死链不要落盘，否则小部件/恢复播放会拿死链去解码。
                .putString(KEY_ALBUM_ART_URI, song.albumArtUri.takeIf { SongRepository.hasRealCover(it) } ?: "")
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
            // [V8.112b] 原先 Oboe 直连模式一律不申请焦点 —— 结果是蓝牙/AAudio 路径下我们**不在
            // 系统焦点栈**，Moto 多音频焦点（com.motorola.dynamicvolume / MultiVolume）就按 UID 把
            // 我们当背景应用压音量（实测 KVP: uid=11143;app_volume=0.37715185 = -8.5dB）。
            // 现在只对 **USB DAC 独占** 保留「不申请」（独占 AAudio 请求焦点会触发假性丢焦，
            // 那是对的）；非独占的 Oboe/蓝牙路径正常申请，争取进入焦点栈。
            // 开关 audio_focus_register（设置页可关，默认开），出问题一键回退旧行为。
            if (instance?.isDacActive() == true) return
            val focusRegisterOn = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("audio_focus_register", true)
            if (!focusRegisterOn) return
            if (hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audioFocusListener == null) {
                audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // 【V9.09】永久丢焦（被别的 app 抢占）：Oboe 直出不响应系统焦点栈，
                            // 若不手动停止会陷入「已释放焦点却仍在播放」的异常态。这里真正停掉 Oboe
                            // 直出，但不 abandon（保留焦点槽位），等系统回发 GAIN 时自动恢复。
                            hasAudioFocus = false
                            val svc = instance
                            if (svc?.isOboeDirectMode() == true) {
                                svc.oboeDirectPlayer?.let { p ->
                                    if (p.isPlaying) {
                                        p.pause()
                                        svc.oboePlayWhenReady = false
                                        // notifyPlayStateChanged 是 companion object 成员，
                                        // 须以 MusicService. 限定调用，不能以 svc.(MusicService 实例) 调用
                                        MusicService.notifyPlayStateChanged(false)
                                    }
                                }
                                svc.wasPlayingBeforeFocusLoss = true
                                android.util.Log.d("MusicService", "Audio focus lost (permanent), paused Oboe direct output")
                            } else {
                                svc?.pause()
                                android.util.Log.d("MusicService", "Audio focus lost (permanent), pausing")
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // 瞬时丢焦（通知/导航播报等）：Oboe 直出不暂停，避免每次提示都卡顿；
                            // 保持播放、不改动播放状态，等 GAIN 回发即可继续。
                            hasAudioFocus = false
                            val svc = instance
                            if (svc?.isOboeDirectMode() != true) {
                                svc?.pause()
                                android.util.Log.d("MusicService", "Audio focus lost (transient), pausing")
                            } else {
                                android.util.Log.d("MusicService", "Audio focus lost (transient), keeping Oboe playback")
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            val svc = instance
                            if (svc?.isPlaying() != true && svc?.wasPlayingBeforeFocusLoss == true) {
                                svc.resume()
                                svc.wasPlayingBeforeFocusLoss = false
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
            // [V8.112b] 与申请侧对齐：USB DAC 独占从不申请 → 也不放弃；其余路径正常放弃。
            if (instance?.isDacActive() == true) return
            if (!hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioFocusListener?.let { am.abandonAudioFocus(it) }
            hasAudioFocus = false
            android.util.Log.d("MusicService", "Audio focus abandoned")
        }

        // [V8.114] 供设置页开关即时生效：关 → 立刻放弃焦点（回到旧行为）；开 → 立刻补申请。
        fun setAudioFocusRegister(ctx: android.content.Context, enabled: Boolean) {
            if (!enabled) abandonAudioFocus(ctx) else requestAudioFocusIfNeeded(ctx)
            android.util.Log.d("MusicService", "audio_focus_register=$enabled applied")
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

    // =====================================================================
    // 【Service 生命周期区】onCreate/onStartCommand/onGetSession/通知 PendingIntent
    // =====================================================================
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Service starting")

        // 【v8.165 省电】settings 变化监听：crossfade 开关被打开时立刻唤醒后台 monitor，
        // 不必等 15s 低频心跳
        try {
            getSharedPreferences("settings", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(xfadePrefsListener)
        } catch (e: Exception) {
            Log.w(TAG, "register xfade prefs listener failed: ${e.message}")
        }

        // 【V8.424】过渡采样音效功能已整体删除：不再注册 SfxPackManager 监听。

        // 【V8.227】全库 BPM/调性补扫（节流后台循环）：引擎换代后尽快把整库换到当前
        // 引擎值，BPM 匹配随机切歌才能锚在准的 BPM 上。
        startBulkBpmBackfill()

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
        // 【V8.361】蓝牙耳机戴回自动恢复（与电话监听同一生命周期）
        registerBtRestoreReceiver()

        createNotificationChannel()

        // 【V8.194】shuffle_mode 只在 settings 里存一份（off / random / bpm）。
        // 旧代码还在 MusicPlayer prefs 里存了一份同名的 boolean，两处互不同步：
        // 设置页写 settings 的 string，于是这里 getBoolean 永远取到 false ——
        // 服务重启后 shuffle 图标灭、纯随机模式下 playNext 直接退化成顺序播放。
        // 修：删掉 MusicPlayer 那份，统一读 settings 的 string。
        val prefs = getSharedPreferences("MusicPlayer", MODE_PRIVATE)
        isShuffleMode = normalizeShuffleMode(
            getSharedPreferences("settings", MODE_PRIVATE).getString("shuffle_mode", null)
        ) != SHUFFLE_MODE_OFF
        repeatMode = prefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF)
        // 【2026-09-09 fix】恢复字段后必须同步权威 flow：PlayerConnection 的
        // repeatSyncJob/shuffleSyncJob 收集的是 _repeatModeFlow/_shuffleModeFlow，
        // 不同步则 UI 图标停在 OFF（行为正确但图标不亮，服务重启后复现）
        _shuffleModeFlow.value = isShuffleMode
        _repeatModeFlow.value = repeatMode
        Log.d(TAG, "Restored shuffle mode: $isShuffleMode, repeat=$repeatMode")

        // 【Crossfade】读取顺序自动交叉淡化开关
        val cfPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        crossfadeEnabled = cfPrefs.getBoolean("crossfade_enabled", true)
        crossfadeDurationMs = cfPrefs.getInt("crossfade_duration_ms", 5000)
        crossfadeBars = cfPrefs.getInt("crossfade_bars", 4)
        bEntryMode = cfPrefs.getString("crossfade_b_entry", "auto") ?: "auto"
        // 【P3】Master Sync 真实同步，默认关闭。打开后过渡时长会被微调（±1 拍以内），
        // 让落点尽量落在 master 小节线上；关闭时 MasterSyncShadow 仍是纯观测，不改播放行为。
        MasterSyncShadow.enabled = cfPrefs.getBoolean("crossfade_master_sync", true)
        Log.d(TAG, "Restored crossfade: enabled=$crossfadeEnabled, dur=$crossfadeDurationMs, masterSync=${MasterSyncShadow.enabled}")

        // :,
        // 
        // First MediaSession: Shuffle + Close custom actions only.
        // System handles Play/Pause/Prev/Next via ForwardingPlayer standard commands.
        mediaSession = MediaLibrarySession.Builder(this, musicPlayerState, object : MediaLibrarySession.Callback {
                // no custom play/pause/prev/next — let system default buttons work
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
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

                // ===== Android Auto / MediaLibrary browsing (车机浏览) =====
                override fun onGetLibraryRoot(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val root = MediaItem.Builder()
                        .setMediaId(AUTO_ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(getString(R.string.app_name_moto))
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(root, params))
                }

                override fun onGetChildren(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    parentId: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val all = when (parentId) {
                        AUTO_ROOT_ID -> listOf(
                            autoCategoryItem(AUTO_ALL_SONGS_ID, "全部歌曲"),
                            autoCategoryItem(AUTO_PLAYLISTS_ID, "我的歌单")
                        )
                        AUTO_ALL_SONGS_ID -> SongRepository.getSongs().map { autoSongItem(it, AUTO_ALL_SONGS_ID) }
                        AUTO_PLAYLISTS_ID -> PlaylistManager.getPlaylists().map { pl ->
                            autoCategoryItem("auto_pl:${pl.id}", pl.name)
                        }
                        else -> if (parentId.startsWith("auto_pl:")) {
                            val plId = parentId.removePrefix("auto_pl:").toLongOrNull()
                            if (plId != null) PlaylistManager.getSongsInPlaylist(plId).map { autoSongItem(it, parentId) }
                            else emptyList()
                        } else emptyList()
                    }
                    val list = if (pageSize > 0) all.drop(page * pageSize).take(pageSize) else all
                    return Futures.immediateFuture(LibraryResult.ofItemList(list, params))
                }

                override fun onSearch(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<Void>> {
                    val q = query.trim().lowercase()
                    val results = if (q.isEmpty()) emptyList() else SongRepository.getSongs().filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }.map { autoSongItem(it, AUTO_ALL_SONGS_ID) }
                    session.notifySearchResultChanged(browser, query, results.size, params)
                    return Futures.immediateFuture(LibraryResult.ofVoid())
                }

                override fun onGetSearchResult(
                    session: MediaLibraryService.MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    query: String,
                    page: Int,
                    pageSize: Int,
                    params: MediaLibraryService.LibraryParams?
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    val q = query.trim().lowercase()
                    val results = if (q.isEmpty()) emptyList() else SongRepository.getSongs().filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }.map { autoSongItem(it, AUTO_ALL_SONGS_ID) }
                    val paged = if (pageSize > 0) results.drop(page * pageSize).take(pageSize) else results
                    return Futures.immediateFuture(LibraryResult.ofItemList(paged, params))
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val first = mediaItems.firstOrNull()
                    if (first != null) handleAutoMediaId(first.mediaId)
                    val resolved = mediaItems.mapNotNull { item ->
                        songFromBrowseMediaId(item.mediaId)?.let { buildMediaItem(it) }
                    }
                    return Futures.immediateFuture(if (resolved.isNotEmpty()) resolved else mediaItems)
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
                .setSmallIcon(R.drawable.ic_notif_brand)
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
            "com.sdw.music.player.DJ_CUT" -> {
                // 【V8.369】程序化触发 DJ 手动切歌（adb 复现/验证用，等价 UI 切歌键）
                val r = triggerCrossfadeNowDetailed()
                Log.i(TAG, "DJ_CUT broadcast -> ${if (r.isEmpty()) "ok" else "rejected: $r"}")
            }
            "com.sdw.music.player.PREV" -> playPrevious(fromUser = true)
            "com.sdw.music.player.NEXT" -> playNext(fromUser = true)
            "com.sdw.music.player.PLAY" -> resume()
            "com.sdw.music.player.PAUSE" -> pause()
        }
        return START_STICKY
    }

    // 
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

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

    // [V8.90 / V8.91] 按 song.id **原地** 刷新封面 URI 与文本标签，不重建、不重排队列。
    //
    // WAV 的封面和标签都是启动后异步 heal 的（healWavMetadata），而 currentSong /
    // servicePlaylist 持的是 heal 之前的快照 —— 通知栏、MediaSession 投影
    // （buildMediaItem 的 artworkUri / title / artist）、桌面小部件读的正是这两个字段，
    // 于是会出现「列表有封面和正确歌手、通知栏还是文件名 + <unknown>」。
    //
    // 这里只替换元数据字段：列表元素个数与顺序完全不变，
    // 因此 shuffle / 专辑队列 / 当前播放位置都不受影响。
    fun refreshMetadata() {
        try {
            val latest = SongRepository.getSongs().associateBy { it.id }
            if (latest.isEmpty()) return

            var changed = false

            // 单个条目按 id 取最新元数据；只覆盖非空字段，空值视为「内嵌标签没这个字段」
            fun remap(one: Song): Song {
                val fresh = latest[one.id] ?: return one
                var out = one
                if (SongRepository.hasRealCover(fresh.albumArtUri) && fresh.albumArtUri != out.albumArtUri) {
                    out = out.copy(albumArtUri = fresh.albumArtUri)
                }
                if (fresh.title.isNotBlank() && fresh.title != out.title) {
                    out = out.copy(title = fresh.title)
                }
                if (fresh.artist.isNotBlank() && fresh.artist != out.artist) {
                    out = out.copy(artist = fresh.artist)
                }
                if (fresh.album.isNotBlank() && fresh.album != out.album) {
                    out = out.copy(album = fresh.album)
                }
                return out
            }

            // 1) 当前播放歌曲（通知栏 / 小部件 / MediaSession 都读它）
            currentSong?.let { cur ->
                val fresh = remap(cur)
                if (fresh != cur) {
                    currentSong = fresh
                    changed = true
                }
            }

            // 2) 队列：按 id 映射，保持原顺序
            if (servicePlaylist.isNotEmpty()) {
                val mapped = servicePlaylist.map { remap(it) }
                if (mapped != servicePlaylist) {
                    servicePlaylist = mapped
                    _originalPlaylist = _originalPlaylist.map { remap(it) }
                    changed = true
                }
            }

            if (!changed) return

            // 3) 让下游跟着刷新
            try { coverCache.evictAll() } catch (_: Exception) {}
            try { musicPlayerState.refresh() } catch (_: Exception) {}
            try { updateNotification() } catch (_: Exception) {}
            val inst = instance
            if (inst != null) {
                try { MusicWidgetProvider.updateAllWidgets(inst) } catch (_: Exception) {}
                try { MusicWidgetProvider3x2.updateAllWidgets(inst) } catch (_: Exception) {}
            }
            Log.i(TAG, "refreshMetadata: song metadata refreshed in place (queue order untouched)")
        } catch (e: Exception) {
            Log.e(TAG, "refreshMetadata failed: " + e.message)
        }
    }

    /** ͬ�������嵥�� servicePlaylist�������ڴ��ֶΣ��������� I/O����ȷ������ŵ� playSong ��ͬһ���б�
     *  ���� USB/Oboe ģʽ�� playSong(index) �ᰴ�� servicePlaylist ��λ�Ŵ�� */
    fun setServicePlaylist(songs: List<Song>, source: String = "All Songs") {
        _originalPlaylist = songs  // [V3.3.2]
        servicePlaylist = songs
        playlistSource = source
        // [V8.90] 队列可能是 heal 之前的快照 —— 延后一拍按 id 补封面 URI。
        // 若 heal 尚未完成则此处是空操作，稍后 heal 完成时 PlayerViewModel 会再补一次。
        handler.post { refreshMetadata() }
    }

    // =====================================================================
    // 【播放路由区】playSong 三分路(DAC独占/Oboe直连/ExoPlayer兜底) / playSongById / 队列管理
    // =====================================================================
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
            // [P2] 自动决策：被静音看门狗判死的 DAC 本会话直接走 Oboe；否则按 profile 决定路径
            val useSystemRoute = DacProfile.shouldUseSystemRoute(device.vendorId, device.productId)
            val dacAutoProbe = dacProfile.autoProbe &&
                !UsbDacManager.isForcedOboe(device.vendorId, device.productId)

            // Path 2: unknown/problem DAC → Oboe system-route (with BIT_PERFECT API on Android 14+)
            if (useSystemRoute) {
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
            // 【V9.02 修复】原 USB DAC 重初始化（claim / open / play）全在主线程同步执行，
            // 阻塞 UI → 反复 "Input dispatching timed out" ANR。照搬 Oboe 路径(V7.123)做法，
            // 把这一段重活整体挪到后台线程（串行锁复用 oboeSwitchLock），主线程只保留轻量通知。
            // 【V9.02 续】DAC 初始化「在途合并」闸门：同一时刻只允许一路 DAC 初始化在跑，
            // 连点/重复回调只保留最后一首（dacInitPendingIndex），当前路结束后补跑，杜绝双播/卡顿/爆音。
            if (dacInitInFlight) {
                dacInitPendingIndex = index
                DebugLog.add(TAG, "playSong[$index]: DAC init in-flight, coalesced as pending (last wins)")
                return
            }
            dacInitInFlight = true
            Thread {
                try {
                synchronized(oboeSwitchLock) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val dacClaimed = UsbDacManager.isClaimed()
            val dacRunning = UsbDacManager.isStreaming()
            // [2026-09-10 C] Deep prebuffer on cross-rate restart: a full release+reclaim
            // leaves a wider supply gap than a same-rate keep-claim switch, which showed
            // up as back-to-back underruns right after the clock re-lock. 500ms of ring
            // absorbs it. Same-rate / first-claim paths keep the fast 200ms target.
            var deepPrebuffer = false
            val dacSession = usbDacController?.sessionState?.name
            DebugLog.add(TAG, "playSong[$index]: ${dacProfile.name} Bit-Perfect, claimed=$dacClaimed running=$dacRunning session=$dacSession")

            // First play (or after full teardown): claim DAC
            if (!dacClaimed) {
                val song = songs[index]
                val srcRate = UsbDacManager.getSourceSampleRate(song)
                val is44kFamily = srcRate in setOf(44100, 88200, 176400, 352800)
                if (dacProfile.lacks44k1Clock && is44kFamily) {
                    DebugLog.add(TAG, "playSong[$index]: DAC ${dacProfile.name} lacks 44.1k clock, route to ExoPlayer")
                    handler.post { playSongFallbackExo(index, songs) }
                    return@Thread
                }
                DebugLog.add(TAG, "playSong[$index]: claiming DAC sr=$srcRate bits=${dacProfile.wireBits}")
                if (!UsbDacManager.claimAndStart(device, srcRate, 2, dacProfile.wireBits)) {
                    DebugLog.add(TAG, "playSong[$index]: claim FAIL, fallback Oboe system-route (Salt Player style)")
                    // Salt Player fallback: Oboe without setDeviceId, kernel USB driver auto-routes
                    UsbDacManager.forceOboeFor(device.vendorId, device.productId)  // [P2] 本会话不再试 Host 独占
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                    refreshOboeModeCache()
                    handler.post { playSongOboeDirect(index, songs); notifySongChanged(songs[index]) }
                    return@Thread
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
                    handler.post { playSongFallbackExo(index, songs) }
                    return@Thread
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
                        deepPrebuffer = true
                    } else {
                        DebugLog.add(TAG, "playSong[$index]: cross-rate reclaim FAIL, fallback Oboe system-route (Salt Player style)")
                        UsbDacManager.forceOboeFor(device2.vendorId, device2.productId)  // [P2] 本会话不再试 Host 独占
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("audio_output", "AAudio (Direct)").apply()
                        refreshOboeModeCache()
                        handler.post { playSongOboeDirect(index, songs); notifySongChanged(songs[index]) }
                        return@Thread
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
                    // [P2] 未知 DAC 自动探测：武装静音看门狗（baseline=当前 URB 完成数）
                    if (dacAutoProbe) {
                        handler.removeCallbacks(dacSilenceRunnable)
                        val base = UsbDacManager.getStreamLiveness()?.urbCompleted ?: 0L
                        dacSilenceRunnable.arm(dacPlayGeneration, device.vendorId, device.productId, index, songs, base)
                        handler.postDelayed(dacSilenceRunnable, DAC_SILENCE_WINDOW_MS)
                        DebugLog.add(TAG, "playSong[$index]: auto-probe armed (baseURB=$base, ${DAC_SILENCE_WINDOW_MS}ms)")
                    }
            val dacProfile = DacProfile.find(UsbDacManager.getDacDevice()?.vendorId ?: 0, UsbDacManager.getDacDevice()?.productId ?: 0)
            controller.playbackWireBits = dacProfile.wireBits
                    // 【方向1】所有分支已保证 native 流未启（claim 后/keep-claim stopMonitor 后/EOS 后均如此），
                    // streamAlreadyRunning 恒 false：play() 必走完整 startStreaming（幂等安全）
                    // [2026-09-10 C] cross-rate restart gets the deeper ring target
                    usbDacController?.let { ctl ->
                        ctl.prebufferTargetMs = if (deepPrebuffer)
                            UsbDacPlaybackController.CROSS_RATE_PREBUFFER_MS
                        else UsbDacPlaybackController.DEFAULT_PREBUFFER_MS
                    }
                    // [2026-09-10 B] soft-start fade (default OFF -> bit-perfect untouched).
                    // Must be applied BEFORE play(), because native reads the flag in start().
                    UsbDacManager.setSoftStartEnabled(
                        getSharedPreferences("settings", MODE_PRIVATE)
                            .getBoolean("dac_soft_start", false)
                    )
                    if (deepPrebuffer) DebugLog.add(TAG, "playSong[$index]: deep prebuffer ${UsbDacPlaybackController.CROSS_RATE_PREBUFFER_MS}ms (cross-rate)")
                    controller.play(streamAlreadyRunning = false)
                    // Apply system media volume to native DAC (USB bypasses Android mixer)
                    val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                    if (am != null) {
                        val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                  am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                        UsbDacManager.setVolume(pct)
                    }
                    // 【V9.02 续】切歌 / DAC 重开后把 native DSP 状态整条重发（集中到 restoreDacDspState，
                    // reclaim / 切歌后 native 系数会整条丢失，仅恢复子开关不恢复 EQ 预设 → 预设静默失效）。
                    restoreDacDspState()
                    currentSong.let { song -> MusicService.currentSong = song; currentIndex = index }
                    handler.post {
                        notifyPlayStateChanged(true)
                        updateNotification()
                        notifySongChanged(songs[index])
                    }
                    return@Thread
                } else { DebugLog.add(TAG, "USB DAC: controller.open FAIL, fallback Exo") }
            }
        }
        } finally {
            // 【V9.02 续】在途合并收口：本路 DAC 初始化无论成功/失败/fallback 都释放闸门，
            // 并补跑用户最新连点目标（last-wins），避免同窗口内多路重初始化造成的卡顿/爆音。
            dacInitInFlight = false
            val pend = dacInitPendingIndex
            dacInitPendingIndex = -1
            if (pend >= 0) {
                DebugLog.add(TAG, "playSong: DAC init finished, launching coalesced pending=$pend")
                playSong(pend)
            }
        }
            }.apply { name = "UsbDacInit" }.start()
            return
        }

        // Non-exclusive fallback: Oboe/AAudio Direct - no ExoPlayer fallback
        playSongOboeDirect(index, songs)
        notifySongChanged(songs[index])
    }

    /**
     * 【V9.02 续】DAC 独占切歌 / 重开后，把 native DSP 状态整条重发一遍。
     * 顺序与原成功路径一致：先 MSEB（10 段）→ DTS 声场 → 各 refresh* 子开关 →
     * EqualizerManager 预设 → AGC / 低频交接 / 声道平衡 / 三段交接 / 变速 / Live FX。
     * 集中到此处，避免 playSong 成功路径被一长串 refresh* 撑散、且保证 DAC 重开后不漏发。
     */
    private fun restoreDacDspState() {
        // Restore MSEB on DAC path (reclaim/切歌后 native EQ 会丢失，重设一次)
        if (MsebCalculator.isEnabled(this)) {
            val msebParams = MsebCalculator.load(this)
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
        if (MsebCalculator.isDtsEnabled(this)) {
            val (ss, img) = MsebCalculator.dtsStageParams(this)
            UsbDacManager.setMsStage(ss, img)
        }
        // [V8.119] DAC 重开后重发宽裕余量档与原声直通（native 状态会丢）
        refreshWideHeadroom()
        refreshDirectPassthrough()
        refreshEngineDspEnabled()    // 【V9.01】DAC 重开后重发引擎 DSP 主开关
        // 【V9.01】DAC 重开后重发 5 个子开关（Bass Swap 由 refreshXfadeBassSwap 覆盖）
        refreshMsebEnabled()
        refreshLimiterEnabled()
        refreshDtsEnabled()
        refreshAutoEqEnabled()
        refreshHrtfProbeEnabled()
        refreshParametricEqEnabled()
        // 【V9.01 修复】DAC 重开后重发 5 段 EQ 品牌预设：native DSP 系数在重开时会
        // 整条丢失，仅恢复子开关而不恢复 EQ 预设 → 此前选好的预设静默失效（"开了预设没感觉"）。
        com.sdw.music.player.core.audio.dsp.EqualizerManager.restoreSettings(this)
        com.sdw.music.player.core.audio.dsp.ParametricEqManager.restoreSettings(this)  // 【V9.0x】DAC 重开后重发手动 PEQ（native biquad 银行会丢）
        refreshAgc()                  // 【V8.177】DAC 重开后重发 AGC 档
        refreshXfadeBassSwap()        // 【V8.220】DAC 重开后重发低频交接档
        refreshStereoBalance()       // 【V8.313】重开后重发声道平衡（否则回到居中）
        refreshXfadeThreeBand()       // 【V8.246】DAC 重开后重发三段频交接档
        applyBeatmatchEngine()        // 【V8.222】DAC 重开后重发变速引擎档
        applyLiveFx()   // 【V8.150】DAC 重开后重发 Live FX 总线设置
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

    // =====================================================================
    // 【Oboe 直连路径区】playSongOboeDirect(单曲会话生命周期) / FallbackExo / DSP 恢复
    // =====================================================================
    private fun playSongOboeDirect(index: Int, songs: List<Song>) {
        val song = songs[index]
        currentIndex = index
        currentSong = song
        // [V8.343] shadow: keep the master clock advancing on plain playback too,
        // otherwise the phase only advances during DJ transitions.
        try {
            val sp = song.filePath.ifEmpty { song.path }
            if (sp.isNotBlank() && !sp.startsWith("content://")) {
                com.sdw.music.player.BpmKeyCache.init(this)
                val bf = com.sdw.music.player.BpmKeyCache.getBpmFloat(sp)
                    .takeIf { it >= 40f && it <= 220f } ?: 0f
                MasterSyncShadow.onPlainPlay(
                    sp, bf,
                    com.sdw.music.player.BpmKeyCache.getFirstBeatMs(sp).toDouble()
                )
            }
        } catch (_: Throwable) {}
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

                // Stop previous (background thread - nativeStopSoft() internally syncs via
                // join+clear ring，但保留输出流给下一首复用)
                // 【V8.202】这里绝不能用完整 stop()：它会把输出流 close+reset，紧接着的
                // nativeOpenFd 就只能重建流，真机实测每次切歌 125~238ms 静音空窗。
                // 【V8.355】Clear crossfade state BEFORE tearing the player down.
                // A hard cut right after an automatic transition used to wedge because
                // the previous round left these flags set:
                //   xfadePreloading / xfadePreloadReady = true and a live incoming slot
                //   -> the monitor re-armed the preload path, openIncoming raced the new
                //      open(), and the new decoder got torn down (the old "incoming(B)
                //      not opened" failure).
                //   crossfadeBusy = true -> the next manual cut answered "busy" for up
                //      to 30s (the stuck-stall self-heal).
                // stopIncoming() also releases the fd and the B decoder, so the slot
                // cannot be reused by the preload thread afterwards.
                xfadePreloading = false
                xfadePreloadReady = false
                crossfadeBusy = false
                crossfadeBusySinceMs = 0L
                crossfadeNextIndex = -1   // non-null Int, -1 = none
                crossfadeUiInfo = CrossfadeUiInfo()      // non-null; fresh instance = "no overlay"
                xfadeBeatPending = false
                xfadeBeatToken++   // invalidate any queued beat-align runnable
                pendingManualTrigger = false
                // NOT handler.removeCallbacksAndMessages(null): that would also kill
                // the notification updater, the progress ticker and the monitor
                // re-schedule. xfadeBeatToken++ above is the targeted way to
                // invalidate a queued beat-align runnable.
                oboeDirectPlayer?.stopIncoming()
                Log.i(TAG, "hard cut: xfade state cleared (preload/busy/beat/slot)")
                // 【V8.364】普通模式只读断言：硬切前确认没有被 DJ 能力污染
                assertPlainModeAudioInvariants("hard-cut")
                oboeDirectPlayer?.stop(keepStream = true)

                // Create new player
                val newPlayer = OboeDirectPlayer(this@MusicService)
                oboeDirectPlayer = newPlayer
                // 【V8.355】Per-step timing. The hard cut went silent for 8.2s once:
                // every refresh below runs BEFORE newPlayer.open(), so whatever is
                // slow here delays the first sample of the new song.
                val _tHardCut = android.os.SystemClock.elapsedRealtime()
                fun _mark(stage: String) {
                    val n = android.os.SystemClock.elapsedRealtime() - _tHardCut
                    Log.i(TAG, "hard cut [${n}ms] after $stage")
                }
                _mark("xfade state cleared + stopIncoming")
                refreshBtPreEmphasis()  // 【2026-09-07】播放器就绪后应用蓝牙预补偿设置
                _mark("refreshBtPreEmphasis")
                refreshWideHeadroom()        // [V8.119] 播放器重建后重发宽裕余量档
                _mark("refreshWideHeadroom")
                refreshDirectPassthrough()   // [V8.119] 播放器重建后重发原声直通档
                _mark("refreshDirectPassthrough")
                refreshEngineDspEnabled()     // 【V9.01】播放器重建后重发引擎 DSP 主开关
                _mark("refreshEngineDspEnabled")
                // 【V9.01】播放器重建后重发 5 个子开关（Bass Swap 由 refreshXfadeBassSwap 覆盖）
                refreshMsebEnabled()
                refreshLimiterEnabled()
                refreshDtsEnabled()
                refreshAutoEqEnabled()
                refreshHrtfProbeEnabled()
                refreshParametricEqEnabled()
                // 【V9.01 修复】播放器重建后重发 5 段 EQ 品牌预设：native DSP 系数在重建时会
                // 整条丢失，仅恢复子开关而不恢复 EQ 预设 → 此前选好的预设静默失效（"开了预设没感觉"）。
                com.sdw.music.player.core.audio.dsp.EqualizerManager.restoreSettings(this@MusicService)
                com.sdw.music.player.core.audio.dsp.ParametricEqManager.restoreSettings(this@MusicService)  // 【V9.0x】播放器重建后重发手动 PEQ（native biquad 银行会丢）
                refreshAgc()                 // 【V8.177】播放器重建后重发 AGC 档
                _mark("refreshAgc")
                refreshXfadeBassSwap()       // 【V8.220】播放器重建后重发低频交接档
                _mark("refreshXfadeBassSwap")
                refreshStereoBalance()        // 【V8.313】重建后重发声道平衡
                _mark("refreshStereoBalance")
                refreshXfadeThreeBand()      // 【V8.246】播放器重建后重发三段频交接档
                _mark("refreshXfadeThreeBand")
                applyBeatmatchEngine()       // 【V8.222】播放器重建后重发变速引擎档
                _mark("applyBeatmatchEngine")
                applyLiveFx()                // 【V8.150】播放器重建后重发 Live FX 总线设置
                _mark("applyLiveFx")
                // 【V8.424】过渡采样音效功能已整体删除，重建时不再重发音效包
                // 【V8.170】播放器重建后重发响度归一化（新 player 的 native 增益回到 1.0）
                val normDb = refreshNormalization()
                Log.i(TAG, "NormGain: ${currentSong?.title} -> ${"%.2f".format(normDb)} dB")
                _mark("refreshNormalization")

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
                    Log.i(TAG, "OboeDirect: song completed")
                    handler.post {
                        // 【V8.380】交叉淡化进行中：出轨（A）自然放完由完成轮询负责切歌，
                        // 这里不要再 playNext() —— 否则会 playSong→xfadeHandler.removeCallbacksAndMessages(null)
                        // 掐断在飞的完成轮询，busy 永久卡死（"只能切一次"）。等轮询清 busy 后，
                        // 下一轨自然结束才会走这里正常 advance。
                        //
                        // 【V8.428 修「播完不接下一首」】原来只要 crossfadeBusy 为真就整个跳过。
                        // 但 busy 会被多种路径留下脏值：DJ 关掉、过渡窗口没再触发、
                        // playSong 的 removeCallbacksAndMessages 掐掉了在飞的完成轮询……
                        // 一旦脏住，这里就**静默 return，播完永远不接下一首**，只能手动点下一首。
                        // 现在改成：只有「确实有一场过渡正在进行」（UI 状态 ACTIVE/PRELOADING
                        // 且未超时）才跳过；否则强制复位 busy 并正常切歌。
                        if (crossfadeBusy) {
                            val heldMs = SystemClock.elapsedRealtime() - crossfadeBusySinceMs
                            val st = crossfadeUiInfo.state
                            val xfadeRunning = (st == XfadeUiState.ACTIVE || st == XfadeUiState.PRELOADING) &&
                                    heldMs < MAX_STALE_XFADE_BUSY_MS
                            if (xfadeRunning) {
                                Log.i(TAG, "OboeDirect: onCompletion during crossfade -> skip playNext (poll handles switch)")
                                return@post
                            }
                            Log.w(TAG, "OboeDirect: crossfadeBusy stale (state=$st held=${heldMs}ms) -> force reset + advance")
                        }
                        crossfadeBusy = false
                        crossfadeBusySinceMs = 0L
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
                xfadeABeatPhaseMs = -1L
                val aScanPath = actualPath
                // 【v8.166】边听边补：该曲条目过期/缺调性-段落时后台补测一次（不阻塞播放）
                ensureSongAnalyzed(aScanPath)
                // 【v8.166】先查缓存：扫描阶段已把副歌/首拍入库的歌，这里一次解码都不用做
                run {
                    com.sdw.music.player.BpmKeyCache.init(this)
                    val st = com.sdw.music.player.BpmKeyCache.getStructure(aScanPath)
                    val cb = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(aScanPath)
                    if (cb > 0) {
                        xfadeABeatPhaseMs = cb.toLong()
                        Log.i(TAG, "Crossfade: prescan-A firstBeat=$cb ms (cached)")
                    }
                    if (st.chorusEndMs > 0) {
                        xfadeAChorusEndMs = st.chorusEndMs.toLong()
                        Log.i(TAG, "Crossfade: prescan-A chorusEnd=${st.chorusEndMs} ms (cached)")
                    }
                    if (st.tailQuietMs >= 0) {
                        xfadeAQuietStartMs = if (st.tailQuietMs > 0) st.tailQuietMs.toLong() else -1L
                        xfadeAPrescanDone = true
                    }
                }
                // 【V8.393】改走单线程池：预扫描并发恒为 1，杜绝一次性 codec 风暴
                prescanExecutor.execute {
                    try {
                        // 【V8.15 A 轨副歌出】预扫最后一个副歌结束位置（mode=3），副歌唱完提前触发
                        // 【V8.17】仅 BPM 匹配模式；纯随机跳过（回归定时+能量低谷）
                        if (bpmChorusMode && xfadeAChorusEndMs <= 0) {
                            val aChorusEnd = newPlayer.preScanPath(aScanPath, 100, mode = 3, seekFromMs = 0L)
                            if (aChorusEnd > 0) {
                                xfadeAChorusEndMs = aChorusEnd.toLong()
                                Log.i(TAG, "Crossfade: prescan-A chorusEnd=${aChorusEnd} ms")
                            } else {
                                Log.i(TAG, "Crossfade: prescan-A no chorusEnd (fallback)")
                            }
                            com.sdw.music.player.BpmKeyCache.mergeStructure(
                                aScanPath, chorusEndMs = if (aChorusEnd > 0) aChorusEnd else 0
                            )
                        }
                        // 【V8.142】A 轨首拍（拍相位基准）——触发延迟用它对齐真实拍点
                        // 【v8.158】优先用缓存的 kick 首拍（新引擎已写入），省一次 20s 预扫
                        val aCachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(aScanPath)
                        val aBeat = if (aCachedBeat > 0) aCachedBeat else newPlayer.preScanPath(aScanPath, 100, mode = 4, seekFromMs = 0L)
                        xfadeABeatPhaseMs = if (aBeat > 0) aBeat.toLong() else -1L
                        if (aBeat > 0 && aCachedBeat <= 0) Log.i(TAG, "Crossfade: prescan-A firstBeat=$aBeat ms")
                        if (!xfadeAPrescanDone) {
                            val quiet = newPlayer.preScanPath(aScanPath, 100, mode = 0, seekFromMs = 0L)
                            xfadeAQuietStartMs = quiet.toLong()
                            xfadeAPrescanDone = true
                            if (quiet > 0) Log.i(TAG, "Crossfade: prescan-A quietStart=$quiet ms")
                            else Log.i(TAG, "Crossfade: prescan-A no quiet segment (fallback RMS)")
                            com.sdw.music.player.BpmKeyCache.mergeStructure(
                                aScanPath, tailQuietMs = if (quiet > 0) quiet else 0
                            )
                        }
                        com.sdw.music.player.BpmKeyCache.scheduleFlush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Crossfade: prescan-A failed ${e.message}")
                        xfadeAQuietStartMs = -1L
                        xfadeAPrescanDone = true
                    }
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
                oboeFlowTrace = "2705 Oboe OK (exclusive=${newPlayer.isExclusiveMode()})"

                // 【V8.446 手动切歌入场点】播起来后立刻 seek 到进歌点。
                // 放在 play() 之后而不是之前：open() 还没完成时 native seek 拿不到解码器，
                // 会被排队甚至丢弃（见 seekTo 里 !isPrepared 的处理）。
                val entry0 = nextSongEntryMs
                nextSongEntryMs = -1L
                if (entry0 > 0L) {
                    try {
                        newPlayer.seekTo(entry0)
                        newPlayer.resetDspEq()
                        Log.i(TAG, "ManualEntry: seek to ${entry0}ms applied on ${song.title.take(24)}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "ManualEntry: seek to ${entry0}ms failed: ${t.message}")
                    }
                }

                handler.post {
                    currentSong = song
                    currentIndex = index
                    markPlayedPath(song.filePath.ifEmpty { song.path })  // 【V8.197】历史名单，供下一次选歌避开
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

                    // 【Crossfade】启动顺序自动交叉淡化监控（后台线程，monitor 自行按条件降频）
                    // 【V8.180】手动切歌前先把 xfadeHandler 上在飞的 crossfade 完成轮询连同
                    // pending 一起清掉：否则上一首那次 crossfade 的 poller 超时后会用旧目标歌
                    // 写回 currentSong/currentIndex（点「下一首」打断过渡时，歌名封面跳回上一轮）。
                    // 必须在下面 scheduleCrossfadeMonitor(500) 之前清，否则会连 monitor 一起清掉。
                    xfadeHandler.removeCallbacksAndMessages(null)
                    // 【V8.183】这枚拍点回调走主线程 handler，上面那句清不掉它：不清的话，
                    // 用户点切歌后它会照旧用上一轮的 B 轨发起 crossfade，并把 crossfadeBusy
                    // 重新锁上，新歌就此卡在「过渡中」且永远等不到 B 轨翻转。
                    cancelPendingBeatTrigger()
                    xfadePendingSong = null
                    xfadePendingIndex = -1
                    crossfadeBusy = false
                    crossfadeNextIndex = -1
                    // 【V8.331关键修复·用户报"点了歌单没反应/被自动切歌吃掉"】
                    // 上一轮自动切歌留下的预加载状态**整批**没清，于是新歌一开播
                    // monitor 就用「上一首的副歌结束点 / B 轨索引 / 预扫结果」判定：
                    //   · xfadeAChorusEndMs 仍是旧歌的值 → preloadByChorus 立刻为 true
                    //   · xfadePreloading 仍为 true → 走 else 分支沿用旧 xfadePreloadIndex
                    //   · xfadePreloadReady 仍为 true → 旧 B 轨「已就绪」，直接触发过渡
                    // 实测：用户 14:30:38 点 Try Everything（4分半），**7 秒后**就被
                    // 自动过渡抢到 House Tour；随后 43s、30s 又连发两次。
                    // 症状对得上用户描述的「无法点击歌单播放 / 像是卡住」。
                    xfadePreloading = false
                    xfadePreloadReady = false
                    xfadePreloadIndex = -1
                    xfadePreloadSong = null
                    xfadePreloadPath = null
                    // 预扫产物同样属于上一首，必须重置，否则用旧拍点/静音段触发
                    xfadeAPrescanDone = false
                    xfadeBPrescanDone = false
                    xfadeABeatPhaseMs = -1L
                    xfadeBFirstBeatMs = -1L
                    xfadeAChorusEndMs = -1L
                    xfadeBChorusMs = -1L
                    xfadeAQuietStartMs = -1L
                    xfadeDipCount = 0
                    //【V8.331】用户点歌=明确要听这首，上一轮自动过渡的冷却闸门不该再拦他
                    lastAutoXfadeDoneAtMs = 0L
                    // 【V8.331】用户手动选的也算「最近听过」，
                    // 否则自动选曲可能立刻把它的 (1) 副本接上来
                    markPlayedSong(song)
                    // 【V8.319 补漏】同上：用户在过渡中点歌/切歌会走到这里（打断过渡），
                    // 这里也必须退役 stem 总线，否则分轨供数器跨歌残留。
                    try { oboeDirectPlayer?.endStemMode() } catch (_: Throwable) {}
                    crossfadeUiInfo = CrossfadeUiInfo()  // 【V8.4】新歌启动清 UI 状态
                    scheduleCrossfadeMonitor(500)

                    handler.postDelayed({
                        if (fftCallback != null && !visualizerManager.isReady()) { visualizerManager.setup() }
                    }, 500)

                    val sampleRate = newPlayer.getSampleRate()
                    val nativeRate = newPlayer.getSampleRateNative()
                    val bitPerfect = sampleRate == nativeRate
                    val clipInfo = newPlayer.getClipDebugInfo()
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
    // =====================================================================
    // 【DSP 效果接口区】MSEB/AutoEQ/5段EQ/M-S声场/瞬态/压缩/响度/Crossfeed/蓝牙预补偿 全部 setter 群
    // =====================================================================

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
    // 【V8.216】并发代号：避免"旧检测晚到"覆盖掉新一次下发的档位。
    private val btPreEmphasisGen = java.util.concurrent.atomic.AtomicInteger(0)

    fun applyBtPreEmphasis(enabled: Boolean) {
        if (isDacActive()) {
            oboeDirectPlayer?.setBtPreEmphasis(false, 0f)
            return
        }
        // 【V8.216】先用**上次已知** dB 立刻下发（首次为 0 = 旁路，编码未知时
        // 不会污染听感），真正的 codec 检测丢到后台线程；出结果后只有档位真的
        // 变了才补发一次。
        //
        // 旧写法在这里同步调 BtCodecTracker.refresh() —— 它内部 latch.await(8s)
        // 等 A2DP proxy，把主线程钉死约 8 秒。触发点是
        // AudioDeviceCallback.onAudioDevicesAdded（主线程），所以"一连蓝牙就卡死"。
        val cached = BtCodecTracker.currentCodecDb
        oboeDirectPlayer?.setBtPreEmphasis(enabled, if (enabled) cached else 0f)
        val gen = btPreEmphasisGen.incrementAndGet()
        BtCodecTracker.refreshAsync(this) { db ->
            if (gen == btPreEmphasisGen.get() && db != cached) {
                handler.post {
                    if (gen == btPreEmphasisGen.get()) {
                        oboeDirectPlayer?.setBtPreEmphasis(enabled, if (enabled) db else 0f)
                        Log.i(TAG, "BtPreEmphasis refreshed codec=${BtCodecTracker.currentCodecName} db=$db")
                    }
                }
            }
        }
        Log.i(TAG, "BtPreEmphasis ${if (enabled) "ON" else "OFF"} codec=${BtCodecTracker.currentCodecName} db=$cached (codec detect in background)")
    }

    // [V8.119] 宽裕余量档（EDM/Hi-Res 宽容模式）：双路下发 —— Oboe/蓝牙链与 USB DAC 链。
    // 关 = 传统（limiter 0.85 / softClip knee 2/3，-3.5dBFS 起削）；开 = 宽裕（0.95 / 0.92）。
    fun applyWideHeadroom(enabled: Boolean) {
        oboeDirectPlayer?.setWideHeadroom(enabled)
        UsbDacManager.setWideHeadroom(enabled)
        Log.i(TAG, "WideHeadroom ${if (enabled) "ON" else "OFF"}")
    }

    /**
     * 【V8.177】自动增益（AGC）独立开关。
     * native 侧 g_agcEnabled 默认 false（一直就没开），但界面上从来没有开关，
     * 用户在诊断页看到 AGC 那一格也不知道怎么关 —— 现在与设置页 pref 绑定。
     */
    fun applyAgc(enabled: Boolean) {
        oboeDirectPlayer?.setAgcEnabled(enabled)
        Log.i(TAG, "AGC ${if (enabled) "ON" else "OFF"}")
    }

    /** 【V8.177】播放器重建 / DAC 重开后按 pref 重发 AGC 档（native 状态会丢）。 */
    fun refreshAgc() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        applyAgc(prefs.getBoolean("agc_enabled", false))
    }

    // [V8.119] 原声直通：整条 DSP 旁路（EQ/MSEB/DTS/压缩/等响/瞬态/crossfeed/AGC/限幅/softClip/dither）。
    // 仅保留解码后的 PCM 原样输出；DAC 侧同步。
    fun applyDirectPassthrough(enabled: Boolean) {
        oboeDirectPlayer?.setDirectPassthrough(enabled)
        UsbDacManager.setDirectPassthrough(enabled)
        Log.i(TAG, "DirectPassthrough ${if (enabled) "ON" else "OFF"}")
    }

    // 【V9.01】引擎 DSP 主开关（DAC 模块"启用引擎 DSP"）：双路下发 Oboe/蓝牙链 + USB DAC 链。
    // false = 创意 DSP（Bass Swap/MSEB/DTS/Limiter/AutoEQ 等）整链旁路，仅保留音量/DJ混音/硬钳。
    fun applyEngineDspEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setEngineDspEnabled(enabled)
        UsbDacManager.setEngineDspEnabled(enabled)
        Log.i(TAG, "EngineDspEnabled ${if (enabled) "ON" else "OFF (bit-perfect passthrough)"}")
    }

    /**
     * 【V8.313】左右声道平衡下发。bal: -1=全左 / 0=居中 / +1=全右。
     * native 侧走恒功率声像曲线。只作用于普通播放输出（含 stem 混音后的总线），
     * **不进过渡路径** —— 过渡里 A/B 两轨各自还要做变速对齐/效果，再叠声道偏移
     * 会和 LUFS 预匹配、三段频交接耦合出怪声，收益远小于风险。
     */
    fun applyStereoBalance(bal: Float) {
        val b = bal.coerceIn(-1f, 1f)
        // V8.348: the old code was `oboeDirectPlayer?.setStereoBalance(b)` followed by an
        // UNCONDITIONAL Log.i -- so when the player was not ready yet (settings opened
        // before the Oboe engine came up, or after a player rebuild) the value was dropped
        // on the floor while the log still claimed it was applied. Device log showed
        // exactly one 'StereoBalance 0.00' line and none of the user's slider moves.
        val p = oboeDirectPlayer
        if (p == null) {
            // Persist and retry once the player exists (see refreshStereoBalance).
            pendingStereoBalance = b
            Log.w(TAG, "StereoBalance ${"%.2f".format(b)} DEFERRED (player not ready)")
            return
        }
        pendingStereoBalance = null
        p.setStereoBalance(b)
        Log.i(TAG, "StereoBalance ${"%.2f".format(b)} -> applied")
    }

    /**
     * 【V8.318】stem 分轨模式当前是否开启。
     * 设置页用它把 DJ 模板 chip **置灰**：分轨模式下native 的轨级模板块被整体跳过
     * （`tpl > 0 && c < 8 && !stemMode`，避免和分轨滤波打架），但 UI 上仍可选 →
     * 用户以为设了没生效。现在直接告诉用户"分轨模式下模板不生效"。
     */
    fun isStemModeActive(): Boolean = oboeDirectPlayer?.stemModeOn == true

    /** 【V8.313】播放器重建 / 服务重连后按 pref 重发声道平衡。 */
    fun refreshStereoBalance() {
        val v = getSharedPreferences("settings", MODE_PRIVATE).getInt("stereo_balance", 0)
        applyStereoBalance(v / 100f)
    }

    // 【V8.220】低频交接（专业 DJ 式 bass swap）—— 插件式可选功能，默认关。
    // 开 = A 轨低频提前切出（35% 内切干净）、B 轨低频同步顶上，过渡低频无重叠更干净；
    // 关 = 原防浑浊曲线，音频链路零改动。
    fun applyXfadeBassSwap(enabled: Boolean) {
        oboeDirectPlayer?.setXfadeBassSwap(enabled)
        Log.i(TAG, "XfadeBassSwap ${if (enabled) "ON" else "OFF"}")
    }

    /** 【V8.220】播放器重建 / 服务重连后按 pref 重发低频交接档。 */
    fun refreshXfadeBassSwap() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        applyXfadeBassSwap(prefs.getBoolean("crossfade_bass_swap", true))
    }

    // 【V8.246】三段频交接（低/中/高频错峰）—— 插件式可选功能，默认关。
    // 开 = 过渡期间低/中/高频各自独立曲线交接（低 35% 内快交、中标准、高 65% 后进），
    //      等效"穷版 NeuralMix"：两轨全频带不再硬叠，浑浊感显著下降；
    // 关 = 原链路（防浑浊 HPF/低切或 bass swap），逐样本一致。
    fun applyXfadeThreeBand(enabled: Boolean) {
        oboeDirectPlayer?.setXfadeThreeBand(enabled)
        Log.i(TAG, "XfadeThreeBand ${if (enabled) "ON" else "OFF"}")
    }

    /** 【V8.381】单一出口：所有 DJ 专属音频效果（变速 rubberband / 相位锁 phaselock / 三段频 threeband）
     *  只允许从这里取值，且一律以 djMode 门控。djMode 关 -> 全零，等价于 8.362 之前的老播放器，
     *  普通模式零影响。任何新 DJ 音频效果都必须加进这里，禁止在别处直接读对应 pref。 */
    private data class DjAudioConfig(
        val djMode: Boolean,
        val rubberband: Boolean,
        val phaselock: Boolean,
        val threeband: Boolean,
        /** 【V8.427】渐变拉速（全模板共用的速度地基）：过渡期把旧曲速度平滑拉向新曲。 */
        val tempoRamp: Boolean = false
    ) {
        companion object {
            val NONE = DjAudioConfig(false, false, false, false, false)
        }
    }

    private fun activeDjAudioConfig(): DjAudioConfig {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        val djMode = cfg.getBoolean("crossfade_dj_enabled", true)
        if (!djMode) return DjAudioConfig.NONE
        return DjAudioConfig(
            djMode = true,
            rubberband = cfg.getBoolean("crossfade_rubberband", true),
            phaselock = cfg.getBoolean("crossfade_rubberband_phaselock", true),
            threeband = cfg.getBoolean("crossfade_threeband", false),
            // 【V8.427】默认开：DJ 模式下任何模板都先做速度渐变（djay 式"先对拍再叠效果"）。
            tempoRamp = cfg.getBoolean("crossfade_tempo_ramp", true)
        )
    }

    /** 【V8.246】播放器重建 / 服务重连后按 pref 重发三段频档。 */
    fun refreshXfadeThreeBand() {
        applyXfadeThreeBand(activeDjAudioConfig().threeband)
    }

    // 路由变化/播放器重建后重发当前设置（保持开关状态与 codec 检测同步）
    fun refreshBtPreEmphasis() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean("bt_pre_emphasis", false)
        applyBtPreEmphasis(enabled)
    }

    // [V8.119] 播放器重建 / 路由变化后重发宽裕余量档
    fun refreshWideHeadroom() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        applyWideHeadroom(prefs.getBoolean("wide_headroom", true))
    }

    // [V8.119] 播放器重建 / 路由变化后重发原声直通档
    fun refreshDirectPassthrough() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        applyDirectPassthrough(prefs.getBoolean("direct_passthrough", false))
    }

    // 【V9.01】播放器重建 / 服务重连后按 pref 重发引擎 DSP 主开关
    fun refreshEngineDspEnabled() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        applyEngineDspEnabled(prefs.getBoolean("engine_dsp_enabled", true))
    }

    // ───── 【V9.01】DAC 模块 5 个子开关：每个效果独立使能，主开关关闭时整链已旁路 ─────
    // 低频交接（Bass Swap）：仅 Oboe 链的 DJ 交接效果，USB DAC 链不涉及 → 只下发 Oboe。
    fun applyBassSwapEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setXfadeBassSwap(enabled)
        Log.i(TAG, "BassSwapEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // MSEB 主观维度 EQ：双路下发 Oboe + USB DAC。
    fun applyMsebEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setMsebEnabled(enabled)
        UsbDacManager.setMsebEnabled(enabled)
        Log.i(TAG, "MsebEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // 多段限幅（Limiter）：仅 Oboe 链有独立限幅器，USB DAC 链走自身收尾硬钳 → 只下发 Oboe。
    fun applyLimiterEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setMultiBandLimiterEnabled(enabled)
        Log.i(TAG, "LimiterEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // DTS 环绕（M/S 处理块）：双路下发 Oboe + USB DAC。
    fun applyDtsEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setDtsEnabled(enabled)
        UsbDacManager.setDtsEnabled(enabled)
        Log.i(TAG, "DtsEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // 【第2步·隔离探针】HRTF 双耳渲染探针：仅 Oboe 链，与 DTS/M-S 互斥，USB DAC 链不接。
    fun applyHrtfProbeEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setHrtfProbeEnabled(enabled)
        Log.i(TAG, "HrtfProbeEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // 探针方向：-1=自动轮播；0=前 1=后 2=左 3=右 4=上
    fun applyHrtfProbeDir(dir: Int) {
        oboeDirectPlayer?.setHrtfProbeDir(dir)
        Log.i(TAG, "HrtfProbeDir $dir")
    }
    // 【真·双耳环绕】HRIR 多虚拟音箱 upmix 总开关（替换 DTS），仅 Oboe 链。
    fun applyHrtfSpatialEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setHrtfSpatialEnabled(enabled)
        Log.i(TAG, "HrtfSpatialEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // 声场朝向：yaw=水平旋转(±180°)、pitch=俯仰(±90°)。
    fun applyHrtfFieldOrientation(yaw: Float, pitch: Float) {
        oboeDirectPlayer?.setHrtfFieldOrientation(yaw, pitch)
        Log.i(TAG, "HrtfFieldOrientation yaw=$yaw pitch=$pitch")
    }
    // 【真·双耳环绕】房间混响开关（在 HRIR 求和之后加空气感），仅 Oboe 链。
    fun applyHrtfReverbEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setHrtfReverbEnabled(enabled)
        Log.i(TAG, "HrtfReverbEnabled ${if (enabled) "ON" else "OFF"}")
    }
    // 【真·双耳环绕】声场距离（米）：近场增强 + 距离衰减，仅 Oboe 链。
    fun applyHrtfFieldDistance(meters: Float) {
        oboeDirectPlayer?.setHrtfFieldDistance(meters)
        Log.i(TAG, "HrtfFieldDistance ${meters}m")
    }
    // AutoEQ 耳机修正：双路下发 Oboe + USB DAC。
    fun applyAutoEqEnabled(enabled: Boolean) {
        oboeDirectPlayer?.setAutoEqEnabled(enabled)
        UsbDacManager.setAutoEqEnabled(enabled)
        Log.i(TAG, "AutoEqEnabled ${if (enabled) "ON" else "OFF"}")
    }

    // 【V9.01】播放器重建 / 服务重连后按 pref 重发 5 个子开关
    fun refreshBassSwapEnabled() = applyBassSwapEnabled(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("crossfade_bass_swap", true))
    fun refreshMsebEnabled() = applyMsebEnabled(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("mseb_enabled", true))
    fun refreshLimiterEnabled() = applyLimiterEnabled(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("limiter_enabled", true))
    // 【V8.690 修复】DTS 状态恢复：子开关 + M/S 曲线必须一起从 "mseb" pref 恢复。
    // 旧实现读 "settings" 文件的同名字段（默认 true），而 MsebScreen 写的是 "mseb" 文件——
    // 两个文件永远不通；且旧实现只恢复子开关位、不恢复 width/center/surround/height 曲线，
    // 播放器重建后 native 曲线回到初值（width=1.0 中性、surround=0.35 弱档），
    // 用户实测"冷启动后开 DTS 没效果"（与 V9.01 EQ 预设同类问题：只恢复开关不恢复参数）。
    fun refreshDtsEnabled() {
        val on = MsebCalculator.isDtsEnabled(this)
        applyDtsEnabled(on)
        if (on) {
            val p = MsebCalculator.load(this)
            val ex = MsebCalculator.getDtsStageEx(this)
            applyMsStage(p.soundstage, p.imaging, ex.surround, ex.height)
            Log.i(TAG, "refreshDtsEnabled: ON stage(ss=${p.soundstage},img=${p.imaging}) ex(su=${ex.surround},ht=${ex.height})")
        } else {
            resetMsStage()
            Log.i(TAG, "refreshDtsEnabled: OFF")
        }
    }
    fun refreshHrtfProbeEnabled() = applyHrtfProbeEnabled(MsebCalculator.isHrtfProbeEnabled(this)).also {
        applyHrtfProbeDir(MsebCalculator.getHrtfProbeDir(this))
    }
    fun refreshAutoEqEnabled() = applyAutoEqEnabled(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autoeq_enabled", true))
    // 手动参数 EQ（Parametric EQ）：受 DSP 引擎主开关旁路，双路下发 Oboe + USB DAC。
    fun applyParametricEqEnabled(enabled: Boolean) {
        com.sdw.music.player.core.audio.dsp.ParametricEqManager.setEnabled(this, enabled)
        Log.i(TAG, "ParametricEqEnabled ${if (enabled) "ON" else "OFF"}")
    }
    fun refreshParametricEqEnabled() = applyParametricEqEnabled(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("parametric_eq_enabled", true))

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

    /**
     * 【V8.3】应用 M/S 声场（跨声道矩阵）— 独立于 EQ，单独开关。
     *
     * 【V8.172】surround / height 是新增的两个 DTS 高级参数（蓝牙耳机上"听不出环绕"
     * 的主因）。DAC 路径的 UsbDacManager 有自己的一套 M/S 实现，保持 bit-perfect
     * 语义不变，这两个参数只对 Oboe（蓝牙/内置/3.5mm）路径生效。
     */
    fun applyMsStage(soundstage: Float, imaging: Float, surround: Float = 5f, height: Float = 5f) {
        if (isDacActive()) {
            UsbDacManager.setMsStage(soundstage, imaging)
        } else {
            oboeDirectPlayer?.setMsStage(soundstage, imaging)
            oboeDirectPlayer?.setDtsStage(surround, height)
        }
    }

    /** 【V8.172】把当前 A2DP codec 下发给 native，收敛环绕的高频反射量。传 null = 非蓝牙。 */
    fun applyDtsBtProfile(codec: String?) {
        if (isDacActive()) return          // DAC 路径不参与蓝牙自适应
        oboeDirectPlayer?.setDtsBtProfile(codec)
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

    // ── 【V8.170】响度统一管理 ──
    // 增益来自曲子自带的 ReplayGain / LUFS 元数据，两条链路都要下发：
    // DAC 独占走 UsbAudioDriver，其余走 Oboe callback。

    /** 把一枚归一化增益推给当前生效的链路。 */
    fun applyNormalization(db: Float) {
        currentNormDb = db
        if (isDacActive()) {
            UsbDacManager.setNormalizationGain(db)
        } else {
            oboeDirectPlayer?.setNormalizationGain(db)
        }
    }

    /**
     * 按当前曲目元数据重算并下发，返回实际使用的 dB（供日志/诊断）。
     * 播放器每次重建都会调一次：新 OboeDirectPlayer 的 native 增益是 1.0，必须补发。
     */
    fun refreshNormalization(): Float {
        val path = currentSong?.filePath?.takeIf { it.isNotBlank() }
            ?: currentSong?.path?.takeIf { it.startsWith("/") }
            ?: return 0f
        // 【V8.515-P0b】USB/DAC 独占（bit-perfect）路径下，增益由硬件 DSP 接管，
        // 软件归一化会破坏位完美，必须保持 0 dB 恒等增益。
        if (isDacActive()) {
            Log.i(TAG, "NormGain: USB/DAC bit-perfect path → forced 0.00 dB (gain handled by hardware)")
            applyNormalization(0f)
            return 0f
        }
        val cfg = LoudnessNormalizer.load(this)
        val db = LoudnessNormalizer.gainFor(path, cfg)
        Log.i(TAG, "NormGain: enabled=${cfg.enabled} mode=${cfg.mode.name} db=${"%.2f".format(db)} src=${LoudnessNormalizer.source(path)} ($path)")
        applyNormalization(db)
        return db
    }

    /** 【V8.210】诊断页读「上次实际下发下去的归一化增益」（dB）。 */
    fun currentNormalizationDb(): Float = currentNormDb

    fun applyCrossfeed(amount: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setCrossfeed(amount)
    }

    fun resetCrossfeed() {
        if (isDacActive()) return
        oboeDirectPlayer?.resetCrossfeed()
    }

    // ── 【V8.170】新 DSP 模块 ──
    // 这四个模块（空间混响 / 动态 EQ / 多频段限幅 / AutoEQ 强度）只存在于 Oboe native 链。
    // USB DAC 独占走独立音频线程，不经 Oboe callback，所以 DAC 独占时静默跳过
    // （与 crossfeed 同一处理惯例），避免误以为开关生效。

    fun setSpaceReverbEnabled(enabled: Boolean) {
        if (isDacActive()) return
        oboeDirectPlayer?.setSpaceReverbEnabled(enabled)
    }

    fun setSpaceReverbWet(wet: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setSpaceReverbWet(wet)
    }

    fun setSpaceReverbShape(roomSize: Float, damping: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setSpaceReverbShape(roomSize, damping)
    }

    fun setDynamicEqEnabled(enabled: Boolean) {
        if (isDacActive()) return
        oboeDirectPlayer?.setDynamicEqEnabled(enabled)
    }

    fun setDynamicEqDepth(depth: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setDynamicEqDepth(depth)
    }

    fun setDynamicEqThreshold(threshold: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setDynamicEqThreshold(threshold)
    }

    fun setMultiBandLimiterEnabled(enabled: Boolean) {
        if (isDacActive()) return
        oboeDirectPlayer?.setMultiBandLimiterEnabled(enabled)
    }

    fun setAutoEqMix(mix: Float) {
        if (isDacActive()) return
        oboeDirectPlayer?.setAutoEqMix(mix)
        // AutoEQ 混了强度后 preamp 要跟着回退，重发一次当前曲线
        Log.i(TAG, "AutoEQ mix=${mix}")
    }

    // ── 新模块当前值（供 DspSnapshot 抓取，避免 UI 侧再存一份平行状态）──
    fun isSpaceReverbEnabled(): Boolean =
        oboeDirectPlayer?.getSpaceReverbEnabled() ?: false
    fun getSpaceReverbWet(): Float = oboeDirectPlayer?.getSpaceReverbWet() ?: 0f
    fun getSpaceReverbRoom(): Float = oboeDirectPlayer?.getSpaceReverbRoom() ?: 0.5f
    fun getSpaceReverbDamping(): Float = oboeDirectPlayer?.getSpaceReverbDamping() ?: 0.5f
    fun isDynamicEqEnabled(): Boolean = oboeDirectPlayer?.getDynamicEqEnabled() ?: false
    fun getDynamicEqDepth(): Float = oboeDirectPlayer?.getDynamicEqDepth() ?: 0f
    fun getDynamicEqThreshold(): Float = oboeDirectPlayer?.getDynamicEqThreshold() ?: 0.25f
    fun isMultiBandLimiterEnabled(): Boolean = oboeDirectPlayer?.getMultiBandLimiterEnabled() ?: true
    fun getAutoEqMix(): Float = oboeDirectPlayer?.getAutoEqMix() ?: 1f

    private var mSpaceReverbEnabled = false
    private var mSpaceReverbWet = 0f
    private var mSpaceReverbRoom = 0.5f
    private var mSpaceReverbDamping = 0.5f
    private var mDynamicEqEnabled = false
    private var mDynamicEqDepth = 0f
    private var mDynamicEqThreshold = 0.25f
    private var mLimiterEnabled = true
    private var mAutoEqMix = 1f

    /** 页面进入时把 native 里仍然生效的模块状态读回 Kotlin，供快照/UI 显示。 */
    fun syncNativeDspState(context: Context) {
        mSpaceReverbEnabled = isSpaceReverbEnabled()
        mSpaceReverbWet = getSpaceReverbWet()
        mSpaceReverbRoom = getSpaceReverbRoom()
        mSpaceReverbDamping = getSpaceReverbDamping()
        mDynamicEqEnabled = isDynamicEqEnabled()
        mDynamicEqDepth = getDynamicEqDepth()
        mDynamicEqThreshold = getDynamicEqThreshold()
        mLimiterEnabled = isMultiBandLimiterEnabled()
        mAutoEqMix = getAutoEqMix()
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
        val hsFreq = sp.floatValue("hs_freq", 8000f)
        val hsDb = sp.floatValue("hs_db", 0f)
        val hsQ = sp.floatValue("hs_q", 0.707f)
        val pkFreq = sp.floatValue("pk_freq", 12000f)
        val pkDb = sp.floatValue("pk_db", 0f)
        val pkQ = sp.floatValue("pk_q", 2.0f)
        val preGainDb = sp.floatValue("pre_gain_db", 0f)
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
            putFloat("hs_freq", sp.floatValue("hs_freq", 8000f))
            putFloat("hs_db", sp.floatValue("hs_db", 0f))
            putFloat("pk_freq", sp.floatValue("pk_freq", 12000f))
            putFloat("pk_db", sp.floatValue("pk_db", 0f))
            putFloat("pre_gain_db", sp.floatValue("pre_gain_db", 0f))
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
                // 【V8.300】暂停→再播放卡死修复：nativePause 只把解码线程 park + requestPause 流，
                // 解码器与输出流都还活着。旧 resume 走整文件 open()（= nativeOpenFd：重建 decoder +
                // 重建/关闭 AAudio 流），在主线程持流/生命周期互斥量去 join 被 park 的解码线程 → 主线程
                // 死锁（实测主线程 S/sleeping，nativeOpenFd 打 "opening FD" 后无后续日志），UI 卡在暂停态。
                // 正确做法：直接 un-park 解码线程 + requestStart 流（nativeResume，已有的轻量恢复），
                // 无需重建。仅当播放器未 prepared（如硬 stop 释放过 decoder）才回退整文件 open()+seek+play。
                if (oboeDirectPlayer?.isPrepared == true && oboeDirectPlayer?.resume() == true) {
                    oboePlayWhenReady = true
                    onOboeResumeSuccess()
                    return
                }
                // 回退：整文件重新 open + 跳到暂停位置（decoder 已被释放等非 prepared 场景）
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
        } else {
            val p = oboeDirectPlayer
            if (p == null) {
                // 【V8.339】原来这里静默走完（既不进 DAC 分支也不进 Oboe 分支），无日志
                Log.w(TAG, "seekTo($position) ignored: oboeDirectPlayer == null")
            } else if (!p.isPrepared) {
                // 【V8.339·关键】原实现在 !isPrepared 时**什么都不做且不记日志**
                // （else if 不成立就掉到函数末尾）。而 !isPrepared 恰好覆盖
                // 「刚 playSong 还没开完」「crossfade 落点后正在重挂」这两个高频窗口，
                // 用户在这两秒内拖进度条就是"完全没反应"。
                // 现在：照样把 seek 排进 executor —— open() 完成后 native seek 依然有效
                //（nativeSeekIncomingMs/ native 侧会作用于当前解码器），
                // 至少让请求不丢；并打日志便于确认是否真的生效。
                Log.i(TAG, "seekTo($position) queued while not prepared (will apply on open)")
                seekExecutor.execute {
                    try {
                        p.seekTo(position)
                        p.resetDspEq()
                        p.resetClipStats()
                    } catch (t: Throwable) {
                        Log.w(TAG, "seekTo($position) on unprepared player failed: ${t.message}")
                    }
                }
            } else {
                // 【V8.179】native seek 要抢 g_decoderLifecycleMutex，而 crossfade 触发时后台线程
                // 正持这把锁 join 解码线程。同步执行时这次 crossfade 阻塞会顺着 binder 回传
                // 一直堵到 UI 主线程 → 「交叉切歌后拖动播放条就卡住」。挪到单线程后台执行，
                // UI 主线程立刻返回，实际 seek 紧随其后（几十 ms 级）。
                seekExecutor.execute {
                    try {
                        p.seekTo(position)
                        p.resetDspEq()  // Reset filter state on seek
                        p.resetClipStats()
                    } catch (t: Throwable) {
                        Log.w(TAG, "seekTo($position) failed: ${t.message}")
                    }
                }
            }
        }
        // [Phase C] 播放界面 seek 后同步 SimpleBasePlayer 投影，否则媒体卡片 position 不刷新（单向同步）
        try { musicPlayerState.refresh() } catch (_: Exception) {}
    }

    // =====================================================================
    // 【V8.197 防 A→B→A→B 振荡】
    //   根因：选歌时只看「当前这一首」，候选池 = 全集去掉它。
    //   BPM 模式下 A(134)→B(132) 之后，B 的匹配池里最接近的就是 A，窄带又只剩它，
    //   于是只能回 A —— 两首歌来回切（v8.26 的注释认过这个病，但只改成"窄带随机"，
    //   根因没动，所以一直还在）。纯随机分支同理：刚听过的歌很快又回来，
    //   听感上就是在原地打转。
    //   解法：维护一份"本周期内已播过"的历史（按路径 + 标题|艺术家去重，
    //   让 X.mp3 与 X(1).mp3 副本算同一首），选歌时优先从没听过的那部分里挑。
    // 【V8.420 升级为「整库轮播」】
    //   旧实现是固定 12 首的滑动窗口——窗口之外就允许重播，小曲库（BPM 相近的就几首）
    //   会很快把窗口填满、之后又开始重复。改为：历史名单逐首累积，覆盖整张歌单；
    //   只有当"候选池里每一首都已在本周期播过"（即整库轮完一遍）时，才清空历史重开一轮。
    //   配合 preferUnplayed 的重置逻辑，做到"每首至少播一遍才允许重复"，
    //   哪怕 BPM 相近的歌只有寥寥几首也不会原地打转。
    // =====================================================================
    private val recentPlayedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())

    // 【V8.520】Pure-random 模式：Fisher–Yates 整库洗牌队列。进入随机时把整张歌单（排除当前曲）
    // 原地打乱成一列，按序出队；播到队尾（整库轮完一遍）再整体重洗，保证 3000+ 首里每首恰好播一遍才重洗。
    private var shuffleQueue: IntArray? = null
    private var shuffleCursor = 0

    private fun markPlayedPath(path: String?) {
        if (path.isNullOrBlank()) return
        synchronized(recentPlayedPaths) {
            while (recentPlayedPaths.remove(path)) {}
            recentPlayedPaths.add(path)
        }
    }

    /** 【V8.331】同时按「标题+艺术家」记账，让 (1) 副本也能被识别为「刚听过」。 */
    private fun markPlayedSong(s: Song?) {
        if (s == null) return
        val k = songDupKey(s)
        if (k.isBlank() || k == "|") return
        recentDupKeys.add(k)
    }

    /** 候选池：优先挑「最近没听过」的；全听过了就退回全集。
     *  【V8.331】去重键用「标题+艺术家」归一化，而不只是文件路径 ——
     *  歌单里常有 `X.mp3` 与 `X(1).mp3` 这类重复文件，路径不同但同属一首歌，
     *  BPM/调性/LUFS 也完全相同，纯路径去重会让它们连续被选中
     *  （实测 14:31:28 与 14:31:58 连切 Cold Heart 与 Cold Heart(1)）。 */
    private fun songDupKey(s: Song): String {
        val t = s.title.trim().lowercase()
            .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")   // 去掉尾部 (1) (2) 副本编号
            .replace(Regex("[\\[\\(]\\s*(remix|explicit| remaster.*?)?\\s*[\\]\\)]"), "")
            .trim()
        val a = s.artist.trim().lowercase()
        return "$t|$a"
    }

    private fun preferUnplayed(pool: List<Int>, songs: List<Song>): List<Int> {
        val memo = synchronized(recentPlayedPaths) { recentPlayedPaths.toSet() }
        if (memo.isEmpty()) return pool
        val fresh = pool.filter { idx ->
            val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
            sp !in memo && songDupKey(songs[idx]) !in recentDupKeys
        }
        if (fresh.isNotEmpty()) return fresh
        // 【V8.420】整库轮播：候选池里每一首都已在本周期播过 → 一个完整周期结束。
        // 清空历史重开一轮，但把「当前正在播的这首」(含其副本去重键) 重新记回，
        // 保证它不会被本轮回放（否则会立刻重播刚播完的歌）。
        // 即便 BPM 相近的歌只有寥寥几首，也保证"每首至少播一遍才允许重复"——不再原地打转。
        val curKey = currentSong?.let { songDupKey(it) } ?: ""
        val curPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        synchronized(recentPlayedPaths) { recentPlayedPaths.clear() }
        recentDupKeys.clear()
        recentPicked.clear()   // 【V9.0·BPM 去重修复】整库轮播重置时同步清空"最近选中"窗口：
                              // 否则上一轮最后 8 首选曲残留会持续压制本轮回合的有效新歌，
                              // 在 BPM 集中的小候选集里直接 fallback 到同一首 → 重复播放。
        if (curPath.isNotBlank()) recentPlayedPaths.add(curPath)
        if (curKey.isNotBlank() && curKey != "|") recentDupKeys.add(curKey)
        // 用重置后的历史再过滤一遍：当前曲及其副本仍被排除，其余全部可放（pool 已不含当前曲 index）
        val newFresh = pool.filter { idx ->
            val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
            sp !in recentPlayedPaths && songDupKey(songs[idx]) !in recentDupKeys
        }
        return newFresh.takeIf { it.isNotEmpty() } ?: pool
    }

    /** 【V8.520】重建 Fisher–Yates 整库洗牌队列：把除 exclude 之外的所有曲目打乱成 IntArray。 */
    private fun rebuildShuffleQueue(songs: List<Song>, exclude: Int) {
        val n = songs.size
        if (n <= 1) { shuffleQueue = IntArray(0); shuffleCursor = 0; return }
        val arr = IntArray(n - 1)
        var w = 0
        for (i in 0 until n) if (i != exclude) arr[w++] = i
        // Fisher–Yates 原地洗牌：从后往前，每次与 [0..i] 均匀随机位置交换
        for (i in arr.size - 1 downTo 1) {
            val j = (0..i).random()
            val t = arr[i]; arr[i] = arr[j]; arr[j] = t
        }
        shuffleQueue = arr
        shuffleCursor = 0
    }

    /** 【V8.331】最近听过的「标题+艺术家」集合，供 preferUnplayed 做副本去重。 */
    private val recentDupKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 【v8.15】按当前模式选下一首 index：shuffle+bpm 匹配 / shuffle 纯随机 / 顺序。
     *  BPM 匹配只在随机播放开启且 settings shuffle_mode=bpm 时生效。 */
    private fun pickNextIndex(songs: List<Song>, realIdx: Int): Int {
        com.sdw.music.player.BpmKeyCache.init(this)  // 【V8.15】crossfade 也走 BPM 缓存（幂等，prefs==null 才初始化）
        com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this)  // 【V8.241】能量感知选曲需要 Integrated LUFS
        com.sdw.music.player.core.audio.metadata.TagGainCache.init(this)    // 【V8.515-P1】标签增益缓存初始化
        if (songs.isEmpty()) return -1
        if (songs.size <= 1) return realIdx
        if (!isShuffleMode) {
            val ni = (realIdx + 1) % songs.size
            Log.i(TAG, "Crossfade: pickNext seq=$ni (shuffle off)")
            return ni
        }
        val pool = (0 until songs.size).filter { it != realIdx }
        // 【V8.197】把「正在播的这一首」算进最近听过，否则下一轮它又回到候选池里，
        // 两首歌指定的 BPM 就会永远互相吸引。
        val curPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        markPlayedPath(curPath)
        // 【V8.331】同一首歌的 (1)(2) 副本也要算「刚听过」，否则会被连续选中
        markPlayedSong(currentSong)
        val freshPool = preferUnplayed(pool, songs)
        // 【V8.241】当前曲调性 + 能量，两种 shuffle 模式共用（和声/能量感知选曲）
        val cacheHit = if (curPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(curPath) else null
        val curKey = cacheHit?.second?.takeIf { it.isNotBlank() } ?: currentSong?.key.orEmpty()
        val curLufs = if (curPath.isNotBlank()) com.sdw.music.player.core.audio.dsp.LoudnessCache.get(curPath)?.lufs else null
        // 【V8.194】走归一，别直接比字符串（遗留值 "true"/"false" 会漏过来）
        val bpmMatch = normalizeShuffleMode(
            getSharedPreferences("settings", MODE_PRIVATE).getString("shuffle_mode", null)
        ) == "bpm"
        // 【V8.245】显式「智能选曲」开关（crossfade_smartpick，默认开=V8.241 起的行为）：
        // 开 = 随机播放按「和声分档 + 能量接近」挑下一首；关 = 普通随机纯随机、
        // BPM 匹配模式保留节奏窄带但窄带内纯随机（节奏锚定不丢，和声/能量加权撤掉）。
        val smartPick = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("crossfade_smartpick", true)
        // 【V8.246】能量曲线策略（djay Automix 式）：smooth=平滑(默认) / build=逐步爬升 / wind=收尾降温
        val energyStrategy = when (
            getSharedPreferences("settings", MODE_PRIVATE).getString("automix_energy_strategy", "smooth")
        ) { "build" -> 1; "wind" -> 2; else -> 0 }
        if (!bpmMatch) {
            // 【V8.520】"Pure random" 模式 = 真随机：用 Fisher–Yates 整库洗牌队列出队，
            // 保证 3000+ 首里每首恰好播一遍才重洗，绝不原地打转（彻底解决"开 pure random 仍循环 ~20 首"）。
            // 不再走 freshPool.random()：那种抽法在窄候选集里会坍缩成当前歌的小邻域。
            // 队空（整库轮完一遍）或歌单规模变化时整体重洗，并排除「刚播完的这首」避免立刻重播。
            var q = shuffleQueue
            if (q == null || shuffleCursor >= (q.size) || (q.size) != songs.size - 1) {
                rebuildShuffleQueue(songs, realIdx)
                q = shuffleQueue
            }
            val queue = q!!
            val ni = if (queue.isEmpty()) {
                realIdx   // 极端：库里只有当前这一首，原地续播
            } else {
                val idx = queue[shuffleCursor]
                shuffleCursor++
                idx
            }
            markPickedIdx(ni)
            val nb = beatTempoOf(songs[ni].let { it.filePath.ifEmpty { it.path } })
            val ca = beatTempoOf(curPath)
            Log.i(TAG, "Crossfade: pickNext pureRandom=$ni (shuffle on, bpm off, fyQueue cursor=$shuffleCursor/${queue.size} bpm=$ca->$nb)")
            return ni
        }
        val curBpm = cacheHit?.first ?: 0
        if (curBpm !in 40..220) {
            // 当前 BPM 未知：仍走和声/能量感知，让无 BPM 数据时也能接得和谐
            val ni = if (smartPick) pickHarmonic(freshPool, songs, curKey, curLufs, energyStrategy) else freshPool.random()
            markPickedIdx(ni)
            Log.i(TAG, "Crossfade: pickNext harmonic=$ni (curBpm=$curBpm unknown, smart=$smartPick, hit=${cacheHit != null}, cacheSize=${com.sdw.music.player.BpmKeyCache.size()}, curKey='$curKey' curLufs=$curLufs pool=${freshPool.size}/${pool.size})")
            return ni
        }
        // 【V8.16】逐级放宽匹配窗口：±5 → ±10 → ±15 → ±20，最后才全池随机
        // 避免"快歌后慢歌"——匹配池空时仍尽量选节奏相近的歌
        val bpmOf: (Int) -> Int = { idx ->
            val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
            val raw = if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.first ?: songs[idx].bpm
            else songs[idx].bpm
            correctedBpm(raw)   // 【V8.243】半/倍速歧义校正
        }
        // 【v8.166】调性（Camelot）：v8.166 扫描会写入 key；老缓存没有则全为 ""（不参与加权）
        val keyOf: (Int) -> String = { idx ->
            val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
            if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.second ?: songs[idx].key
            else songs[idx].key
        }
        // 【V8.25 防漂移】"第三首变慢歌"根因：容差内 random() 会随机到容差边缘的歌
        // （134±5 池里随机到 129 → 129 再匹配又放宽 → 三级后漂到慢歌）。
        // 【V8.26 防锁死】minByOrNull 永远返回差最小的第一首(index 最小) → 本轮排除它后
        // 又选第二小 → 恰好两首同 BPM 的歌来回切（用户报"只能两首歌切来切去"）。
        // 修复：容差内先找最小差 minDiff，再在 [minDiff, minDiff+2] 窄带内随机——
        // 节奏锚定不漂移，同时不锁死单曲。
        // 【V8.478 Automix BPM 能量曲线】
        //
        // 用户反馈两点：
        //   a) "听来听去几首重复的歌" —— 旧逻辑永远挑**和当前 BPM 最接近**的
        //      （minDiff .. minDiff+2 窄带，容差还从 ±5 起步），
        //      于是 BPM 原地不动，可选池每一轮都差不多，几首歌来回切。
        //   b) "应该按 bpm 速度可以渐弱或者渐强来建立歌单" ——
        //      旧逻辑在 BPM 轴上**根本没有方向**：energyStrategy(build/wind)
        //      只作用在响度 LUFS 上，BPM 永远是"找最近的"。
        //
        // 修法：给 BPM 轴加方向。先看当前 BPM 落在库里的哪个分位，
        // 再决定这一刀往上还是往下 —— 于是整轮播放形成
        // "爬升 → 到顶回落 → 到底再爬升" 的长周期波浪：
        //   · 听感是有起伏的 set，而不是一潭死水；
        //   · 一轮能覆盖远多于从前的歌，不再绕着几首转。
        val rng = automixBpmRange(songs, bpmOf)
        val span = (rng[1] - rng[0]).coerceAtLeast(1)
        val frac = ((curBpm - rng[0]).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val dir = when (energyStrategy) {
            1 -> if (frac < 0.72f) 1 else -1    // build：还没到顶就继续爬，到顶转降温
            2 -> if (frac > 0.28f) -1 else 1    // wind：还没到底就继续降，到底转回升
            else -> 0                            // smooth：贴着当前速度
        }
        data class BpmWin(val lo: Int, val hi: Int)
        // 【V8.479】步长收窄。V8.478 用 +9/-9，方向化的同时把相邻 BPM 差拉大，
        // 变速比超出 RubberBand 的 ±8% 钳位窗 → 落点残留速率差大 → 噪音。
        // 收到 ±5：仍有明确的爬升/回落方向（每首挪 3~5 BPM），
        // 但相邻差始终落在变速能拉动的范围内。
        val ladder = when {
            dir > 0 -> listOf(BpmWin(+1, +5), BpmWin(-2, +9), BpmWin(-6, +16))
            dir < 0 -> listOf(BpmWin(-5, -1), BpmWin(-9, +2), BpmWin(-16, +6))
            else    -> listOf(BpmWin(-3, +3), BpmWin(-7, +7), BpmWin(-14, +14))
        }
        // 目标点：方向明确时朝那个方向偏一点，这样"最接近目标"不等于"最接近当前"
        val target = curBpm + (if (dir > 0) 3 else if (dir < 0) -3 else 0)
        for (w in ladder) {
            val matched = freshPool.map { it to bpmOf(it) }
                .filter { (_, bpm) -> bpm in 40..220 && (bpm - curBpm) in w.lo..w.hi }
            if (matched.isEmpty()) continue
            // 【V8.478 多样性】不再锁死 minDiff..minDiff+2 的极窄带：
            // 按"离目标 BPM 最近"取前 10 首，再在这批里选 ——
            // 既保持方向性，又给随机留出空间，避免几首歌循环。
            val top = matched.sortedBy { (_, bpm) -> kotlin.math.abs(bpm - target) }
                .take(kotlin.math.min(10, matched.size)).map { it.first }
            val ni = if (smartPick) pickHarmonic(top, songs, curKey, curLufs, energyStrategy) else top.random()
            if (ni < 0) continue
            val nb = bpmOf(ni)
            markPickedIdx(ni)
            Log.i(TAG, "Crossfade: pickNext bpm=$ni (curBpm=$curBpm dir=$dir frac=%.2f win=${w.lo}..${w.hi} "
                .format(frac) + "target=$target picked=$nb key=$curKey->${keyOf(ni)} "
                + "score=${keyScore(curKey, keyOf(ni))} "
                + "lufs=$curLufs->${com.sdw.music.player.core.audio.dsp.LoudnessCache.get(songs[ni].let { s -> s.filePath.ifEmpty { s.path } })?.lufs} "
                + "cand=${top.size}/${matched.size})")
            return ni
        }
        // 【V8.241】连 ±20 都找不到节奏相近的歌时，也走和声/能量感知，而非纯随机
        val ni = if (smartPick) pickHarmonic(freshPool, songs, curKey, curLufs, energyStrategy) else freshPool.random()
        markPickedIdx(ni)
        Log.i(TAG, "Crossfade: pickNext harmonic=$ni (no bpm within +-20, curBpm=$curBpm, smart=$smartPick, curKey='$curKey' curLufs=$curLufs pool=${freshPool.size}/${pool.size})")
        return ni
    }

    /**
     * 【V8.478】库的 BPM 分位区间 [p10, p90]，供 Automix 的"渐强/渐弱"判定用。
     *
     * 只采样最多 400 首（3000 首的库也不卡），结果按歌单规模缓存；
     * 歌单长度变了就重算。取 p10/p90 而不是 min/max，是为了不受个别
     * 极端 BPM（误检的 60 或 180）影响。
     */
    private var automixBpmRangeCache: IntArray? = null
    private var automixBpmRangeCacheFor: Int = -1
    private fun automixBpmRange(songs: List<Song>, bpmOf: (Int) -> Int): IntArray {
        automixBpmRangeCache?.let { if (automixBpmRangeCacheFor == songs.size) return it }
        val step = if (songs.size > 400) songs.size / 400 else 1
        val vals = ArrayList<Int>(512)
        var i = 0
        while (i < songs.size) {
            val b = bpmOf(i)
            if (b in 40..220) vals.add(b)
            i += step
        }
        val out = if (vals.size < 8) {
            intArrayOf(80, 140)
        } else {
            vals.sort()
            intArrayOf(vals[(vals.size * 10) / 100], vals[(vals.size * 90) / 100])
        }
        automixBpmRangeCache = out
        automixBpmRangeCacheFor = songs.size
        Log.i(TAG, "Automix: bpm range p10=${out[0]} p90=${out[1]} (sampled ${vals.size}/${songs.size})")
        return out
    }

    /**
     * 【V8.250】Automix 预览（djay 式「接下来怎么接」）：
     * 以当前曲为起点模拟未来 count 次选曲，返回 (歌, 理由) 列表。
     * 纯只读：不写播放历史、不改任何状态——用与 pickNextIndex 相同的评分逻辑但零副作用。
     * 理由示例：「和声 11A→11A · 能量差 0.3dB」「BPM 128→126」「顺序播放」。
     */
    fun previewUpNext(count: Int): List<Pair<Song, String>> {
        val out = mutableListOf<Pair<Song, String>>()
        try {
            // 【V8.414】与全项目其它 12 处口径对齐：servicePlaylist 为空时回退到全库。
            // 原实现用裸 servicePlaylist，导致「VIEW intent 起播 / 单曲播放 / 刚换歌单」
            // 这类场景下 servicePlaylist 是空的 → songs.size<2 → 整块「接下来怎么接」不渲染，
            // 而 playNext 有兜底所以音乐照放，用户看到的就是「歌在放但接下来不见了」。
            val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
            if (songs.size < 2) return out
            var curIdx = currentSong?.let { cs -> songs.indexOfFirst { it.id == cs.id } } ?: -1
            // 【V8.414】当前歌不在列表里也别直接返回空：先按路径兜一次，
            // 仍找不到就把歌单前 N 首作为粗略预览——宁可给个大概的，也别整块消失。
            if (curIdx < 0) {
                val csPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                if (csPath.isNotBlank()) {
                    curIdx = songs.indexOfFirst { it.filePath.ifEmpty { it.path } == csPath }
                }
            }
            if (curIdx < 0) {
                Log.w(TAG, "previewUpNext: current song not in playlist (${songs.size} songs), fallback to head preview")
                return songs.take(count).map { it to "歌单预览" }
            }
            com.sdw.music.player.BpmKeyCache.init(this)
            com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this)
            com.sdw.music.player.core.audio.metadata.TagGainCache.init(this)    // 【V8.515-P1】标签增益缓存初始化
            val cfg = getSharedPreferences("settings", MODE_PRIVATE)
            val smartPick = cfg.getBoolean("crossfade_smartpick", true)
            val energyStrategy = when (cfg.getString("automix_energy_strategy", "smooth")) { "build" -> 1; "wind" -> 2; else -> 0 }
            val bpmMatch = normalizeShuffleMode(cfg.getString("shuffle_mode", null)) == SHUFFLE_MODE_BPM
            fun rawBpmOf(idx: Int): Int {
                val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
                return if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.first ?: songs[idx].bpm else songs[idx].bpm
            }
            fun keyOfIdx(idx: Int): String {
                val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
                return if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.second ?: songs[idx].key else songs[idx].key
            }
            fun lufsOfIdx(idx: Int): Float? {
                val sp = songs[idx].filePath.ifEmpty { songs[idx].path }
                return if (sp.isNotBlank()) com.sdw.music.player.core.audio.dsp.LoudnessCache.get(sp)?.lufs else null
            }
            var pool = preferUnplayed((0 until songs.size).filter { it != curIdx }, songs).toMutableList()
            if (pool.isEmpty()) pool = (0 until songs.size).filter { it != curIdx }.toMutableList()
            var curKey = keyOfIdx(curIdx)
            var curLufs = lufsOfIdx(curIdx)
            var curBpm = correctedBpm(rawBpmOf(curIdx))
            repeat(count) {
                if (pool.isEmpty()) return out
                var ni = -1
                var reason = ""
                when {
                    !isShuffleMode -> {
                        ni = (curIdx + 1) % songs.size
                        reason = "顺序播放"
                    }
                    !smartPick -> {
                        ni = pool.random()
                        reason = "纯随机"
                    }
                    !bpmMatch || curBpm !in 40..220 -> {
                        ni = pickHarmonic(pool, songs, curKey, curLufs, energyStrategy)
                        val d = lufsOfIdx(ni)?.let { l -> curLufs?.let { c -> l - c } }
                        reason = buildString {
                            append("和声 $curKey→${keyOfIdx(ni)}")
                            d?.let { append(" · 能量差%.1fdB".format(it)) }
                        }
                    }
                    else -> {
                        // BPM 匹配：±5→±20 逐级放宽的窄带模拟（与 pickNextIndex 同逻辑，只读）
                        var band: List<Int> = emptyList()
                        for (tolerance in intArrayOf(5, 10, 15, 20)) {
                            val matched = pool.map { it to correctedBpm(rawBpmOf(it)) }
                                .filter { (_, b) -> b in 40..220 && b in (curBpm - tolerance)..(curBpm + tolerance) }
                            if (matched.isNotEmpty()) {
                                val minDiff = matched.minOf { (_, b) -> kotlin.math.abs(b - curBpm) }
                                band = matched.filter { (_, b) -> kotlin.math.abs(b - curBpm) <= minDiff + 2 }.map { it.first }
                                break
                            }
                        }
                        ni = if (band.isNotEmpty()) {
                            pickHarmonic(band, songs, curKey, curLufs, energyStrategy)
                        } else {
                            pickHarmonic(pool, songs, curKey, curLufs, energyStrategy)
                        }
                        val nb = if (ni >= 0) correctedBpm(rawBpmOf(ni)) else 0
                        reason = "BPM $curBpm→$nb · 和声 $curKey→${if (ni >= 0) keyOfIdx(ni) else "?"}"
                    }
                }
                if (ni < 0 || ni !in pool) return out
                out.add(songs[ni] to reason)
                pool.remove(ni)
                curIdx = ni
                curKey = keyOfIdx(ni)
                curLufs = lufsOfIdx(ni)
                curBpm = correctedBpm(rawBpmOf(ni))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "previewUpNext: ${t.message}")
        }
        return out
    }

    // =====================================================================
    // 【v8.166】单曲后台补扫：引擎升到 5 之后，老缓存普遍缺「调性 + 段落」，
    // 与其要求用户手动全库重扫，不如边听边补——每次开播一首歌，若它的条目过期
    // （engine < ENGINE）或缺段落结构，就在后台补测一次并回写缓存。
    // 一次解码约 1~2s，IO 线程，不碰音频通路。
    // =====================================================================
    private val analysisScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val analysisInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // 【V8.430 修"标记画不出来"】结构分析结果（intro/outro/drop）的**独立**内存缓存。
    //
    // 为什么不能只靠 BpmKeyCache.mergeStructure：那个函数开头是
    //   val old = cacheMap[filePath] ?: return
    // 即"该曲没有基础条目（BPM 未扫）时不写入"。而 warmDjStructure 是**开播时**异步跑的，
    // 跟 BPM 扫描赛跑 —— BPM 先进缓存才写得进去，慢了或BPM 扫失败就整份结构被丢弃，
    //   UI 于是永远读到 -1，线永远不画。
    // 这里存一份与 BPM 无关的结果：分析完就能用；BpmKeyCache 那份仍照写（用于跨会话持久化）。
    @Volatile private var djStructPath: String = ""
    @Volatile private var djStructIntro: Int = -1
    @Volatile private var djStructOutro: Int = -1
    @Volatile private var djStructDrop: Int = -1

    /**
     * 【V8.386】DJ 模式：开播即预热能量包络，并用包络算出**尾奏 + Drop**，落进 [BpmKeyCache]。
     *
     * ## 为什么必须预热
     * 尾奏/Drop 的算法只需要整曲 RMS 能量包络，而这个包络 [WaveformCache] 已经能算
     * （纯 MediaExtractor，**不建 MediaCodec**）。但它原先**只有打开 DJ 波形界面才会被填充**，
     * 于是「切歌热路径要用包络」和「包络只在看界面时才有」互相打架 ——
     * 实际跑起来每次都是 envelope miss、退回原生预扫描，白写了。
     * 这里把预热提前到**歌曲开始播放时**（且仅 DJ 模式），让两条路真正生效。
     *
     * ## 开销
     * 一次整曲包络扫描是纯解码前的工作（MediaExtractor 读全文件算 1400 桶），
     * 在 IO 协程上做、不碰音频通路；LRU 6 首，足够覆盖 DJ 连切窗口。
     */
    private fun warmDjStructure(song: Song) {
        val fp = song.filePath.ifEmpty { song.path }
        // 【V8.437】这两条早退以前是静默的，导致"为什么没日志"很难排查（我这次又踩了）。
        if (fp.isBlank() || fp.startsWith("content://")) {
            Log.i(TAG, "DJ struct: skip (path='${fp.takeLast(40)}')")
            return
        }
        val durMs = song.duration
        if (durMs <= 0) {
            // 时长未知（media provider 有些行读不到）—— 用解码器报的总长兜底，
            // 否则这首歌永远拿不到三段包络，波形就一直单色。
            val probed = runCatching {
                oboeDirectPlayer?.getDurationMs() ?: 0L
            }.getOrDefault(0L)
            Log.i(TAG, "DJ struct: song.duration<=0 for ${fp.takeLast(28)}, probed=$probed")
            if (probed <= 0) return
            // fallthrough：后面用 probed
            analysisInFlight.add("struct:$fp")
            analysisScope.launch {
                try {
                    com.sdw.music.player.core.audio.playback.WaveformCache.envelopeKick(applicationContext, fp, null)
                } catch (_: Throwable) {}
                analysisInFlight.remove("struct:$fp")
            }
            return
        }
        if (!analysisInFlight.add("struct:$fp")) return      // 同一首歌不重复扫
        analysisScope.launch {
            try {
                // 【V8.436】用 kick 包络（200Hz 低通能量）做段落检测，宽带只作为兜底。
                // 实测根因：Loudness War 把整条频带推平（宽带 dyn=0.015，检不出段落），
                // 但鼓组是节奏骨架不会被压平 —— native 首拍检测用 200Hz 低通就能稳定检出。
                // envelopeKick 内部会顺带把宽带包络也扫出来（wantKick=true），不再单独调。
                val kick = com.sdw.music.player.core.audio.playback.WaveformCache
                    .envelopeKick(applicationContext, fp, null)
                // 【V8.436】优先用 kick；没有 kick（扫描失败）才退回宽带。
                val cached = kick ?: com.sdw.music.player.core.audio.playback.WaveformCache.cached(fp)
                if (cached == null) {
                    Log.i(TAG, "DJ struct: envelope unavailable for ${fp.takeLast(28)}")
                    return@launch
                }
                Log.i(TAG, "DJ struct: analyzing ${fp.takeLast(28)} with ${if (kick != null) "KICK" else "BROADBAND"} envelope")
                com.sdw.music.player.BpmKeyCache.init(this@MusicService)
                val bpmF = com.sdw.music.player.BpmKeyCache.getEntry(fp)?.bpmFloat ?: 0f
                // 【#3】降拍（beat-1）校正：用包络把首拍重锚到真音乐强拍。
                // 全链路落点都从 Entry.firstBeatMs + 动态 grid 段首拍派生，二者在此统一重锚，
                // 切歌即落在 beat-1 而非"native 检到的第一个拍"（MusicService 6503 已指出该隐患）。
                // 与 #1 同门控：低 conf 跳过；包络/首拍缺失则不动（零退化、幂等）。
                val cur = com.sdw.music.player.BpmKeyCache.getEntry(fp)
                if (cur != null && cur.firstBeatMs > 0 && gridTrusted(fp)) {
                    val delta = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                        .downbeatDeltaBeats(cached, durMs, cur.firstBeatMs, cur.bpmFloat)
                    if (delta > 0) {
                        val beatMs = 60000.0 / cur.bpmFloat
                        val newFirst = (cur.firstBeatMs + delta * beatMs).toInt()
                        val newGrid = cur.grid.map { seg ->
                            val barMs = (4.0 * 60000.0 / seg[0]).toLong()
                            val startL = seg[2].toLong()
                            var nf = (seg[1] + delta * beatMs).toLong()
                            while (nf > startL + barMs) nf -= barMs
                            while (nf < startL) nf += barMs
                            intArrayOf(seg[0], nf.toInt(), seg[2])
                        }
                        com.sdw.music.player.BpmKeyCache.mergeFirstBeat(fp, newFirst, newGrid)
                        Log.i(TAG, "DJ struct: downbeat re-anchor +$delta beats -> firstBeat=$newFirst ms (${fp.takeLast(20)})")
                    }
                }
                val tail = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer.tailQuietMs(cached, durMs)
                val drop = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer.dropMs(cached, durMs, bpmF)
                // 【V8.430 进拍】包络法求主旋律起点，与 tailQuietMs 镜像；
                // 不走 native preScan（那条路每次新建 MediaCodec，正是 V8.386 要消除的）。
                //
                // 【V8.446 用户反馈："很多歌进歌点不是人声进歌，都是前旋律进"】
                // introQuietMs 用的是 kick 包络，鼓组是节奏骨架，前奏第一个鼓点
                // 就会让能量跳起来 → 检出点落在 0:0x（第一个鼓），不是人声入场。
                //
                // 改为：**优先用 mid/high 占比抬升检出的人声点**，
                // 人声特征不明显（双水位拉不开、或纯器乐曲）才回落到 kick 能量台阶。
                // 回落时打警告，便于日后判断阈值要不要再调。
                val bands3 = com.sdw.music.player.core.audio.playback.WaveformCache.bands3(fp)
                // 【V8.472】谐波度通道：没有它，"人声"判据就只是"低频占比"，
                // 会把前奏的旋律乐器当成进歌点。
                val harm = com.sdw.music.player.core.audio.playback.WaveformCache.harmOf(fp)
                val vocalIntro = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                    .introVocalMs(bands3, durMs, harm)
                val kickIntro = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                    .introQuietMs(cached, durMs)
                val intro = if (vocalIntro > 0) {
                    Log.i(TAG, "DJ struct: ${fp.takeLast(28)} 进歌点取**人声** $vocalIntro ms（kick 能量点为 $kickIntro，未采用）")
                    vocalIntro
                } else {
                    if (kickIntro > 0) {
                        Log.w(TAG, "DJ struct: ${fp.takeLast(28)} 未检出人声段，回落到 kick 能量点 $kickIntro ms")
                    }
                    kickIntro
                }
                if (tail > 0) com.sdw.music.player.BpmKeyCache.mergeStructure(fp, tailQuietMs = tail)
                if (drop > 0) com.sdw.music.player.BpmKeyCache.mergeStructure(fp, dropMs = drop)
                if (intro > 0) com.sdw.music.player.BpmKeyCache.mergeStructure(fp, introQuietMs = intro)
                // 【#2】真实段落边界检测（纯包络法，零新 codec）：用整曲能量包络切出
                // verse→chorus→bridge 分段，落点选择据此切在"换段处"而非"算出来的小节线"。
                // 检不出（母带压平/水位拉不开）返回空 → 不写，调用方退回小节线吸附（无退化）。
                // 【#1】低置信度 BPM 不把段落边界吸附到（可能错的）小节线
                val gridOk = this@MusicService.gridTrusted(fp)
                val sections = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                    .sectionBounds(cached, durMs, bpmF, drop, gridOk)
                if (sections.isNotEmpty()) com.sdw.music.player.BpmKeyCache.mergeStructure(fp, sections = sections)
                Log.i(TAG, "DJ struct: ${fp.takeLast(28)} dur=$durMs bpm=$bpmF -> tailQuiet=$tail drop=$drop introQuiet=$intro sections=${sections.size} (no codec)")
                // 【V8.430】先写独立缓存（无条件），再尝试写 BpmKeyCache（可能因无条目被丢弃）。
                // 顺序很重要：UI 只等分析完成，不该被 BPM 扫描的进度绑架。
                djStructPath = fp
                djStructIntro = intro
                djStructOutro = tail
                djStructDrop = drop
                if (tail <= 0 && drop <= 0 && intro <= 0) {
                    Log.w(TAG, "DJ struct: ${fp.takeLast(28)} 三项全未检出（母带可能被压平或首尾无明显安静段）")
                }
                if (tail > 0 || drop > 0 || intro > 0) com.sdw.music.player.BpmKeyCache.scheduleFlush()
            } catch (t: Throwable) {
                Log.w(TAG, "DJ struct failed: ${t.message}")
            } finally {
                analysisInFlight.remove("struct:$fp")
            }
        }
    }

    private fun ensureSongAnalyzed(path: String) {
        if (path.isBlank() || path.startsWith("content://")) return
        com.sdw.music.player.BpmKeyCache.init(this)
        val e = com.sdw.music.player.BpmKeyCache.getEntry(path)
        val st = com.sdw.music.player.BpmKeyCache.getStructure(path)
        val needBpm = e == null || e.engine < com.sdw.music.player.BpmKeyCache.ENGINE
        val needStruct = st.chorusMs < 0 || st.chorusEndMs < 0 || st.tailQuietMs < 0
        // 【V8.233】响度也要补：BPM 已新但响度没扫过的歌，播到它时必须顺手测一次，
        // 否则「正在播的这首」永远拿不到响度参数（只能等全库补扫排到它）。
        com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this)
        val needLufs = com.sdw.music.player.core.audio.dsp.LoudnessCache.get(path) == null
        if (!needBpm && !needStruct && !needLufs) return
        if (!analysisInFlight.add(path)) return
        analysisScope.launch {
            // 【V8.229】失败也要落一个「墓碑」条目（engine=当前引擎），否则全库补扫会
            // 无限重扫同一首坏文件：文件不存在 / 解码返回 null / 抛异常 / BPM 无效。
            var settled = false
            try {
                val f = java.io.File(path)
                if (!f.exists()) return@launch
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val arr = (try {
                    com.sdw.music.player.core.audio.analysis.BpmScanner.nativeDetectBpmFromFdEx(path, pfd.fd, 0, pfd.statSize)
                } finally { pfd.close() }) ?: return@launch
                val bpm = arr.getOrNull(0) ?: -1
                if (bpm !in 40..220) {
                    // 【V8.229】解码成功但 BPM 无效：写「墓碑」条目（engine=当前引擎），
                    // 让全库补扫前进，不无限重扫同一首；匹配侧把 40..220 外的 BPM 当未知处理。
                    // 【V8.233】解码成功但 BPM 无效：写墓碑（见上）。响度若还在则照常入库。
                    com.sdw.music.player.BpmKeyCache.putEx(path, e?.bpm ?: -1, e?.key ?: "",
                        e?.firstBeatMs ?: -1, e?.conf ?: 0f,
                        com.sdw.music.player.BpmKeyCache.ENGINE, e?.grid ?: emptyList())
                    com.sdw.music.player.BpmKeyCache.scheduleFlush()
                    runCatching {
                        com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this@MusicService)
                        if (com.sdw.music.player.core.audio.dsp.LoudnessCache.get(path) == null) {
                            com.sdw.music.player.core.audio.dsp.LoudnessCache.tombstone(path)
                            com.sdw.music.player.core.audio.dsp.LoudnessCache.scheduleFlush()
                        }
                    }
                    Log.i(TAG, "Analyze(backfill): invalid bpm=$bpm tombstoned ${f.name}")
                    settled = true
                    return@launch
                }
                val firstBeat = arr.getOrNull(1) ?: -1
                val conf = (arr.getOrNull(2) ?: 0) / 1000f
                val nseg = arr.getOrNull(3) ?: 0
                val grid = ArrayList<IntArray>()
                if (nseg > 0) {
                    var w = 4
                    for (s in 0 until nseg) {
                        val gb = arr.getOrNull(w) ?: break
                        val gf = arr.getOrNull(w + 1) ?: break
                        val gs = arr.getOrNull(w + 2) ?: break
                        grid.add(intArrayOf(gb, gf, gs)); w += 3
                    }
                }
                val tailIdx = 4 + 3 * grid.size
                val chorusMs = arr.getOrNull(tailIdx) ?: -1
                val keyCode = arr.getOrNull(tailIdx + 1) ?: -1
                // 【V8.233】同一次解码顺带回来的响度（lufs*100 / peak*1000）
                val lufs100 = arr.getOrNull(tailIdx + 2) ?: 0
                val peak1000 = arr.getOrNull(tailIdx + 3) ?: 0
                val key = if (keyCode > 0) {
                    if (keyCode >= 100) "${keyCode - 100}B" else "${keyCode}A"
                } else e?.key ?: ""
                // 【V8.304】同样带上浮点 tempo（尾部 [x]=bpmMilli），否则单曲重扫会把
                // 已有的 bpmF 冲掉（putEx 在 bpmF 非法时保留旧值，这里传 0 即可回退保留）
                val bpmMilli = arr.getOrNull(tailIdx + 4) ?: 0
                val bpmF = if (bpmMilli > 0) bpmMilli / 1000f else 0f
                com.sdw.music.player.BpmKeyCache.putEx(path, bpm, key, firstBeat, conf, com.sdw.music.player.BpmKeyCache.ENGINE, grid, null, bpmF)
                if (chorusMs > 0) com.sdw.music.player.BpmKeyCache.mergeStructure(path, chorusMs = chorusMs)
                com.sdw.music.player.BpmKeyCache.scheduleFlush()
                // 【V8.233】响度入库：无标签曲子靠它做归一化
                com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this@MusicService)
                if (lufs100 != 0) {
                    com.sdw.music.player.core.audio.dsp.LoudnessCache.put(
                        path, lufs100 / 100f, (peak1000 / 1000f).coerceIn(0.01f, 1f))
                } else {
                    com.sdw.music.player.core.audio.dsp.LoudnessCache.tombstone(path)
                }
                com.sdw.music.player.core.audio.dsp.LoudnessCache.scheduleFlush()
                // 【V8.234】扫到「正在播的这首」→ 立刻重算并下发归一化增益。
                // 之前响度入库后没人通知链路：诊断页的「计算增益」跟着扫描结果变了，
                // 「已下发」却停在切歌时的旧值，且不会自愈（要等下次切歌）——三个数长期不一致。
                runCatching {
                    val curPath = currentSong?.let { it.filePath.ifEmpty { it.path } }
                    if (!curPath.isNullOrBlank() && curPath == path) {
                        val db = refreshNormalization()
                        val cfg = com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.load(this@MusicService)
                        Log.i(TAG, "NormGain(rescan): ${"%.2f".format(db)} dB enabled=${cfg.enabled} src=${com.sdw.music.player.core.audio.dsp.LoudnessNormalizer.source(path)} (${f.name})")
                    }
                }
                settled = true
                Log.i(TAG, "Analyze(backfill): $bpm BPM key=$key chorus=$chorusMs lufs=${lufs100 / 100f} peak=${peak1000 / 1000f} ${f.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "Analyze(backfill) failed: ${t.message}")
            } finally {
                if (!settled) {
                    runCatching {
                        val cur = com.sdw.music.player.BpmKeyCache.getEntry(path)
                        if (cur == null || cur.engine < com.sdw.music.player.BpmKeyCache.ENGINE) {
                            com.sdw.music.player.BpmKeyCache.putEx(path, cur?.bpm ?: -1, cur?.key ?: "",
                                cur?.firstBeatMs ?: -1, cur?.conf ?: 0f,
                                com.sdw.music.player.BpmKeyCache.ENGINE, cur?.grid ?: emptyList())
                            com.sdw.music.player.BpmKeyCache.scheduleFlush()
                        }
                        // 【V8.233】失败也占住响度位子，避免补扫无限重试坏文件
                        runCatching {
                            com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this@MusicService)
                            if (com.sdw.music.player.core.audio.dsp.LoudnessCache.get(path) == null) {
                                com.sdw.music.player.core.audio.dsp.LoudnessCache.tombstone(path)
                                com.sdw.music.player.core.audio.dsp.LoudnessCache.scheduleFlush()
                            }
                        }
                        Log.i(TAG, "Analyze(backfill): tombstoned (analysis failed) $path")
                    }
                }
                analysisInFlight.remove(path)
            }
        }
    }

    // 【V8.227】全库补扫循环是否已启动（onCreate 起一次，服务存活期间不再重启）
    private var bpmBackfillStarted = false
    @Volatile private var backfillForce = false   // 【V8.264】设置页手动全速开关

    /** 【V8.264】设置页「立即全库分析」开关：旁路熄屏门控全速补扫；播放中仍自动暂停。 */
    fun setBackfillForce(on: Boolean) {
        backfillForce = on
        com.sdw.music.player.core.AnalysisProgress.running.value = on
        Log.i(TAG, "BpmBackfill: force=$on")
    }

    /**
     * 【V8.227】引擎换代后的全库节流补扫 —— BPM 匹配准不准的根基。
     *
     * 背景：pickNextIndex 的锚点（当前歌 BPM/调性）与候选（池内每首 BPM/调性）全来自
     * BpmKeyCache。引擎 5→6→7 换代后，绝大多数条目仍是旧引擎值（用户报"流行歌不准"
     * 的那批），而旧机制"边听边补"每播放/预加载一首才补一首 —— 几百首的库要听上好几天
     * 才换完，期间 BPM 匹配长期锚在错值上，表现为"没按接近的 BPM 切歌"。
     *
     * 这里起一个低占用后台循环：逐首找 engine<ENGINE 或缺段落结构的条目，按 ~5s/首
     * 节奏补测（单首解码 1-2s，8k 单声道，IO 线程，不碰音频通路）。几百首的库约
     * 45 分钟内全部换到当前引擎；全部新鲜后循环自行退出。onDestroy 随 analysisScope 取消。
     */
    private fun startBulkBpmBackfill() {
        if (bpmBackfillStarted) return
        bpmBackfillStarted = true
        analysisScope.launch {
            com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this@MusicService)
            Log.i(TAG, "BpmBackfill: bulk scan started (engine=${com.sdw.music.player.BpmKeyCache.ENGINE}, loudness=${com.sdw.music.player.core.audio.dsp.LoudnessCache.size()} cached)")
            while (!isDestroyed) {
                val songs = SongRepository.getSongs()
                if (songs.isEmpty()) { delay(30000); continue }
                var staleCount = 0
                var stale: Song? = null
                // 【V8.233】优先扫「正在播的这首」和「已预加载的下一首」：
                // 全库按列表顺序扫的话，当前曲目可能排在几百首之后才轮到，
                // 用户当下看到的就一直是「无响度参数」。
                val prio = listOfNotNull(currentSong, xfadePreloadSong)
                for (p0 in prio) {
                    val pp = p0.filePath.ifEmpty { p0.path }
                    if (pp.isBlank() || pp.startsWith("content://")) continue
                    val pe = com.sdw.music.player.BpmKeyCache.getEntry(pp)
                    if ((pe == null || pe.engine < com.sdw.music.player.BpmKeyCache.ENGINE)
                        || com.sdw.music.player.core.audio.dsp.LoudnessCache.get(pp) == null
                    ) { stale = p0; break }
                }
                for (s in songs) {
                    val sp = s.filePath.ifEmpty { s.path }
                    if (sp.isBlank() || sp.startsWith("content://")) continue
                    val e = com.sdw.music.player.BpmKeyCache.getEntry(sp)
                    // 【V8.229 修复死循环】过期判定只看引擎版本：离线分析只产出 BPM/key/grid/chorusMs，
                    // chorusEnd/tailQuiet 只有播放时实时预扫才有 —— 旧条件把它们也算进去，
                    // 导致每首分析完仍被判过期，循环永远卡在第一首上全库永不前进。
                    val need = e == null || e.engine < com.sdw.music.player.BpmKeyCache.ENGINE
                    // 【V8.233】响度也要全库扫一遍（同一次解码，不额外开销）
                    val needLufs = com.sdw.music.player.core.audio.dsp.LoudnessCache.get(sp) == null
                    if (need || needLufs) { staleCount++; if (stale == null) stale = s }
                }
                // 【V8.264】手动全速开关：旁路熄屏门控（播放中仍暂停以保护听感）。
                // 分析线程本就在后台，配合分块间 delay 占空比受控，不再复现 ANR。
                val bulkAllowed = backfillForce ||
                    (isCharging() && !isPlaying() &&
                        !(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive)
                com.sdw.music.player.core.AnalysisProgress.stale.value = staleCount
                com.sdw.music.player.core.AnalysisProgress.total.value = songs.size
                val prioPaths: Set<String>? = if (bulkAllowed) null else prio.mapNotNull { p0 ->
                    p0.filePath.ifEmpty { p0.path }.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                }.toSet()
                val target: Song? = stale?.takeIf { s ->
                    val sp = s.filePath.ifEmpty { s.path }
                    prioPaths == null || (sp.isNotBlank() && sp in prioPaths)
                }
                if (target == null) {
                    if (staleCount == 0) {
                        Log.i(TAG, "BpmBackfill: all ${songs.size} entries fresh, done")
                        com.sdw.music.player.core.AnalysisProgress.stale.value = 0
                        com.sdw.music.player.core.AnalysisProgress.total.value = songs.size
                        if (backfillForce) setBackfillForce(false)
                        break
                    }
                    delay(if (bulkAllowed) 5000 else 20000)   // 门控关闭期：低频轮询等充电/熄屏
                    continue
                }
                // 【V8.264】全速模式下分析线程降为后台优先级：解码器子线程继承此优先级，
                // UI/音频永远优先——手动开关开着用手机也不再卡。
                val tid = android.os.Process.myTid()
                val oldPrio = android.os.Process.getThreadPriority(tid)
                android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    ensureSongAnalyzed(target.filePath.ifEmpty { target.path })
                } finally {
                    try { android.os.Process.setThreadPriority(tid, oldPrio) } catch (_: Throwable) {}
                }
                if (staleCount % 20 == 1 || staleCount < 5) {
                    Log.i(TAG, "BpmBackfill: ~$staleCount/${songs.size} stale (next: ${target.title})")
                }
                // 【V8.259】批量期也让出更多空档：分析 ~1-2s + delay 8s ≈ 占空比 ~20%，熄屏省电
                delay(if (backfillForce) 6000 else if (bulkAllowed) 8000 else 20000)
            }
        }
    }

    /** 【V8.250】是否正在充电（全库补扫的门控条件之一）。 */
    private fun isCharging(): Boolean {
        val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            ?: return false
        val st = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        return st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               st == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    // 【V8.142 智能对齐】把 B 入场点(ms)吸附到 B 自身拍栅格（首拍 + k*beat）
    // B BPM 未知或未扫到首拍 → 原值返回（安全回退）
    // 【P2-2】动态 beatgrid：返回 targetMs 所在段的首拍相位(绝对 ms) 与段 BPM；无 grid → (-1, 0)
    private fun gridBeatAt(path: String, targetMs: Long): Pair<Long, Int> {
        val g = com.sdw.music.player.BpmKeyCache.getGrid(path)
        if (g.size < 2) return Pair(-1L, 0)
        var chosen: IntArray? = null
        for (seg in g) {
            val st = seg.getOrElse(2) { 0 }
            if (st.toLong() <= targetMs) chosen = seg
        }
        val c = chosen ?: g[0]
        val beat = c.getOrElse(1) { -1 }
        val bpm = c.getOrElse(0) { 0 }
        if (beat < 0 || bpm !in 40..220) return Pair(-1L, 0)
        return Pair(beat.toLong(), bpm)
    }

    /**
     * 【P2】取 targetMs 所在的**整段** grid 记录（不再只拿 bpm/首拍）。
     * 段结构：{bpm, firstBeatMs, startMs, beatsPerBar, downbeatOffsetMs}
     * 旧缓存只有前 3 个 → [beatsPerBarOf] / [downbeatOffsetOf] 会给出 4 / 0 的默认值，不退化。
     *
     * 与 [gridBeatAt] 保持同一套挑选规则（取 startMs <= targetMs 的最后一段，无则首段）。
     */
    private fun gridSegmentAt(path: String, targetMs: Long): IntArray? {
        val g = com.sdw.music.player.BpmKeyCache.getGrid(path)
        if (g.isEmpty()) return null
        var chosen: IntArray? = null
        for (seg in g) {
            val st = seg.getOrElse(2) { 0 }
            if (st.toLong() <= targetMs) chosen = seg
        }
        return chosen ?: g[0]
    }

    /** 拍号（一小节几拍），缺省 4/4 */
    private fun beatsPerBarOf(seg: IntArray?): Int =
        if (seg != null && seg.size >= 4 && seg[3] in 1..16) seg[3] else 4

    /** 小节第一拍相对段内首拍的偏移(ms)，缺省 0（首拍即重拍） */
    private fun downbeatOffsetOf(seg: IntArray?): Int =
        if (seg != null && seg.size >= 5) seg[4] else 0

    // 【#1】置信度门控（与 V8.419 对拍门控共用 crossfade_bpm_min_conf 阈值）：
    // 低置信度(-1 未知 / 低于阈值)的 BPM 测值，beatgrid 不可信，吸附到（可能错的）小节线
    // 只会切在错误相位。返回 false 时调用方应跳过网格吸附，回退到基于剩余时间/检测段落的触发。
    private fun gridTrusted(path: String): Boolean {
        if (path.isBlank()) return false
        val minConf = getSharedPreferences("settings", MODE_PRIVATE).getInt("crossfade_bpm_min_conf", 30)
        if (minConf <= 0) return true
        val thr = minConf / 10f
        val c = com.sdw.music.player.BpmKeyCache.confOf(path)
        return c >= thr
    }

    /**
     * 【P1 · 动态 beatgrid 量化】把「A 轨触发时刻」向上量化到最近的小节线，
     * 但**用触发点所在段的 BPM/首拍**，而不是整轨的恒定 BPM。
     *
     * 背景：原先直接调 [SongStructureAnalyzer.quantizeTriggerUpToBar]，形参是
     * 单一的 firstBeatMs + 单一 bpmF（恒定速度模型）。变速曲、或整轨 BPM 有
     * 亚 BPM 误差时，过渡越长累积漂移越大（0.5% × 30s ≈ 150ms）。
     * B 轨入场点早已走 grid（见 [snapBToBarGrid]），这里把 A 侧补齐。
     *
     * 兼容性（重要）：
     * - grid 为空 / 段数 < 2 → 走原函数，行为与改造前**完全一致**；
     * - 置信度不足（[gridTrusted] 为 false）→ 同样走原函数；
     * - 恒定速度曲即便有 2 段以上，各段 bpm/首拍相同 → 结果与原来一致。
     * 也就是说：**只有真正变速的曲子才会走到新分支**，绝大多数曲子零回归。
     */
    private fun quantizeTriggerWithGrid(
        path: String,
        idealStartMs: Long,
        fallbackBeatMs: Long,
        fallbackBpmF: Float,
        maxSnapMs: Long = 2_000L
    ): Long {
        if (idealStartMs <= 0) return idealStartMs
        if (path.isNotBlank()) {
            com.sdw.music.player.BpmKeyCache.init(this)
            if (gridTrusted(path)) {
                val (gBeat, gBpm) = gridBeatAt(path, idealStartMs)
                if (gBeat > 0 && gBpm in 40..220) {
                    // 段内 BPM 是整数精度；只有当该段 BPM 与整轨 bpmF 基本吻合时，
                    // 才换用浮点 bpmF 保留亚 BPM 精度；段速确实不同时必须用段自己的 BPM。
                    val segBpmF = if (fallbackBpmF >= 40f && Math.abs(gBpm - Math.round(fallbackBpmF)) <= 1) {
                        fallbackBpmF
                    } else {
                        gBpm.toFloat()
                    }
                    // 【P2】拍号与重拍偏移：3/4 拍、或段内首拍不是重拍时，小节线才算得准
                    val seg = gridSegmentAt(path, idealStartMs)
                    val bpb = if (seg != null && seg.size >= 4 && seg[3] in 1..16) seg[3] else 4
                    val dbo = if (seg != null && seg.size >= 5) seg[4] else 0
                    val snapped = com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
                        .quantizeTriggerUpToBar(idealStartMs, gBeat, segBpmF, maxSnapMs, bpb, dbo)
                    // 注意：必须用 String.format 包住整条消息。写成
                    // "a" + "b".format(x) 时，方法调用优先级高于 +，format 只会作用在后半段，
                    // 前半段的 %.2f 会原样输出。
                    Log.i(TAG, String.format(
                        "Crossfade: grid-quantize seg(bpm=$gBpm beat=$gBeat useBpmF=%.2f bpb=$bpb dbo=$dbo) ideal=$idealStartMs -> bar=$snapped",
                        segBpmF
                    ))
                    return snapped
                }
                Log.i(TAG, "Crossfade: grid-quantize skipped (no segment at ideal=$idealStartMs)")
            }
        }
        return com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer
            .quantizeTriggerUpToBar(idealStartMs, fallbackBeatMs, fallbackBpmF, maxSnapMs)
    }

    private fun snapBToBeatGrid(preSong: Song?, msLong: Long): Long {
        if (msLong <= 0L) return msLong
        val path = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        if (path.isBlank()) return msLong
        if (!gridTrusted(path)) return msLong   // 【#1】低置信度：不吸附，回退非网格触发
        com.sdw.music.player.BpmKeyCache.init(this)
        val bpm = com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        if (bpm !in 40..220) return msLong
        // 【P2-2】动态 beatgrid：优先用 msLong 所在段的 BPM/相位
        val (gridBeat, gridBpm) = gridBeatAt(path, msLong)
        // 【v8.158】次选缓存首拍（新引擎按 kick 写入）；再次预扫值
        val cachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path)
        val beat = if (gridBeat > 0) gridBeat else if (cachedBeat > 0) cachedBeat.toLong() else xfadeBFirstBeatMs
        if (beat <= 0L) return msLong
        // 【V8.304】拍长用浮点 tempo 算：整数 BPM 会把拍长量化（120 附近步进 0.5ms/拍），
        // 8 小节外累积 ~4ms —— 足以让 downbeat 对齐听感变差。整段用 Double 不取整。
        val bpmF = if (gridBpm in 40..220) gridBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f } ?: bpm.toFloat()
        val beatMs = 60000.0 / bpmF
        val k = Math.round((msLong - beat).toDouble() / beatMs)
        val snapped = (beat + k * beatMs).toLong()
        if (snapped <= 0L) return msLong
        if (kotlin.math.abs(snapped - msLong) > (beatMs * 0.5).toLong() + 1L) return msLong
        if (snapped != msLong) Log.i(TAG, "Crossfade: B grid snap ${msLong}ms -> ${snapped}ms (beat=$beat bpm=%.2f cached=$cachedBeat segs=${com.sdw.music.player.BpmKeyCache.getGrid(path).size})".format(bpmF))
        return snapped
    }

    /** 【v8.166】A 轨当前处于小节内第几拍（0..3），失败 -1。
     *  以「首拍 = 小节线」为基准（4/4 拍的常规情形）。 */
    private fun currentBeatInBar(p: OboeDirectPlayer): Int {
        val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        if (aPath.isBlank()) return -1
        com.sdw.music.player.BpmKeyCache.init(this)
        val pos = p.getCurrentPositionMs()
        val (gBeat, gBpm) = gridBeatAt(aPath, pos)
        val bpm = if (gBpm in 40..220) gBpm else com.sdw.music.player.BpmKeyCache.get(aPath)?.first ?: 0
        if (bpm !in 40..220) return -1
        val cachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(aPath)
        val anchor = if (gBeat > 0) gBeat
            else if (xfadeABeatPhaseMs > 0) xfadeABeatPhaseMs
            else if (cachedBeat > 0) cachedBeat.toLong()
            else 0L
        // 【V8.304】浮点 tempo 算拍长/小节长（整数 BPM 会量化 beatInBar 的判据）
        val bpmF = if (gBpm in 40..220) gBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(aPath).takeIf { it > 0f } ?: bpm.toFloat()
        val beatMs = 60000.0 / bpmF
        val barMs = beatMs * 4.0
        var rel = (pos - anchor) % barMs
        if (rel < 0) rel += barMs
        return (rel / beatMs).toInt().coerceIn(0, 3)
    }

    /**
     * 【V8.310 Beat Jump】按小节跳转（djay Beat Jump）：bars>0 往后、<0 往前。
     * 落点必须吸附到**小节线**，否则跳过去正好在拍中间 = 听起来是"打滑"而不是"跳拍"。
     * 返回 true = 已跳转；false = 拿不到 BPM/栅格（无法保证落在小节线上，不做裸跳转）。
     */
    fun beatJump(bars: Int): Boolean {
        // 【V8.357】Beat jump belongs to the DJ surface only. This guard STAYS even
        // after the V8.359 monitor fix: the plain player has no ⏭ button at all, and this
        // is the service-side backstop. Unlike the monitor guard it does not disable any
        // non-DJ capability -- nothing outside the DJ surface calls beatJump.
        if (!xfadeCfg().djMode) {
            Log.w(TAG, "beatJump ignored: DJ surface is off")
            return false
        }
        if (bars == 0) return false
        val p = oboeDirectPlayer ?: return false
        if (!p.isPlaying || !p.isPrepared) return false
        val song = currentSong ?: return false
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank() || path.startsWith("content://")) return false
        com.sdw.music.player.BpmKeyCache.init(this)
        // 拍长：优先动态 beatgrid 段 → 浮点 bpmF → 整数 bpm（与 snapBToBarGrid 同一口径）
        val pos = p.getCurrentPositionMs()
        val (gBeat, gBpm) = gridBeatAt(path, pos)
        val bpm = if (gBpm in 40..220) gBpm else com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        if (bpm !in 40..220) return false
        val bpmF = if (gBpm in 40..220) gBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f } ?: bpm.toFloat()
        val anchor = when {
            gBeat > 0 -> gBeat
            xfadeABeatPhaseMs > 0 -> xfadeABeatPhaseMs
            else -> com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path).toLong()
        }
        if (anchor <= 0L) return false
        val beatMs = 60000.0 / bpmF
        val barMs = beatMs * 4.0
        // 当前所在小节号 → 目标小节号
        val curBar = Math.floor((pos - anchor).toDouble() / barMs).toInt()
        val tgtBar = curBar + bars
        if (tgtBar < 0) return false                     // 往前跳过头 = 回到开头，交给 seekBar 逻辑
        val targetD = anchor + tgtBar * barMs
        val durMs = p.getDurationMs()
        if (durMs > 0 && targetD >= durMs - 1000) return false   // 目标超出曲长，忽略（防止跳到空白）
        if (targetD <= 0.0) return false
        val target = targetD.toLong()
        p.seekTo(target)
        Log.i(TAG, "BeatJump: $bars bar(s) ${if (bars > 0) "fwd" else "back"}: ${pos}ms -> ${target}ms (bar $curBar -> $tgtBar, bpm=%.2f)".format(bpmF))
        return true
    }

    /**
     * 当前曲目，供 UI 层读取（波形包络要按path 去分析文件）。
     * 【V8.372】加这个是因为 currentSong 是 private set，外部拿不到。
     */
    fun uiCurrentSong(): Song? = currentSong

    /**
     * 【V8.371 DJ 切片栅格】8 个热点的元数据，供 DJ 界面画时间轴并支持点击跳转。
     *
     * 为什么不用整首歌的静态包络：一首歌切成 8 块之后，每块的能量和低频含量都不一样，
     * 这正是 DJ 挑落点（"从第 5 块进副歌"）的依据。只给时间位置而没有能量参考，
     * 切片就退化成 8 个盲目的数字。
     *
     * 能量取自 BpmKeyCache 之外的路径：这里只返回栅格几何（位置 ms + 拍号），
     * 真正的每块能量由 UI 侧从 EnergyRibbon 已有的实时数据读，不重复分析音频。
     *
     * 拿不到 BPM/栅格时返回空列表 —— UI 应当隐藏切片条，而不是画 8 个假点。
     */
    data class SlicePoint(
        val index: Int,        // 0..7
        val barIndex: Int,     // 绝对小节号（相对锚点）
        val positionMs: Long,
        val beatInBar: Int
    )

    /**
     * 【V8.430 新增】当前曲目的**进拍/ 出拍**位置（毫秒），供 DJ 界面在波形上画标记。
     *
     * 对齐 djay 的两条白线：intro 是主旋律真正开始的点，outro 是尾部能量塌下去的点。
     * 都来自 [com.sdw.music.player.core.audio.analysis.SongStructureAnalyzer] 的包络分析，
     * 未检出时对应值为 -1（调用方应跳过绘制，不要画 0 位置）。
     *
     * 注意：这里给的是**绝对毫秒**，而 [BpmKeyCache.Structure.introQuietMs] 在旧
     * mode=1 语义下曾存的是"长度"；V8.430 起统一为绝对位置（与 tailQuietMs 同口径）。
     */
    data class StructureMarks(val introMs: Int, val outroMs: Int, val dropMs: Int)

    fun djStructureMarks(): StructureMarks {
        val song = currentSong ?: return StructureMarks(-1, -1, -1)
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank() || path.startsWith("content://")) return StructureMarks(-1, -1, -1)
        // 【V8.430】优先用独立缓存（BpmKeyCache 未必有该曲条目）；命中不了再回落查表。
        if (path == djStructPath) {
            val i = djStructIntro
            val o = djStructOutro
            val dr = djStructDrop
            if (i > 0 || o > 0 || dr > 0) return StructureMarks(i, o, dr)
        }
        com.sdw.music.player.BpmKeyCache.init(this)
        val st = com.sdw.music.player.BpmKeyCache.getStructure(path)
        return StructureMarks(st.introQuietMs, st.tailQuietMs, st.dropMs)
    }

    /**
     * 取当前曲目的 8 切片栅格。（V8.371）
     * 与 beatJump 用同一套 anchor/bpmF 口径，保证画出来的点与实际跳转落点一致。
     */
    fun djSliceGrid(count: Int = 8): List<SlicePoint> {
        if (!xfadeCfg().djMode) return emptyList()
        val p = oboeDirectPlayer ?: return emptyList()
        val song = currentSong ?: return emptyList()
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank() || path.startsWith("content://")) return emptyList()
        com.sdw.music.player.BpmKeyCache.init(this)

        val pos = p.getCurrentPositionMs()
        val (gBeat, gBpm) = gridBeatAt(path, pos)
        val bpm = if (gBpm in 40..220) gBpm else com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        if (bpm !in 40..220) return emptyList()
        val bpmF = if (gBpm in 40..220) gBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f } ?: bpm.toFloat()
        val anchor = when {
            gBeat > 0 -> gBeat
            xfadeABeatPhaseMs > 0 -> xfadeABeatPhaseMs
            else -> com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path).toLong()
        }
        if (anchor <= 0L) return emptyList()

        val beatMs = 60000.0 / bpmF
        val barMs = beatMs * 4.0
        val curBar = Math.floor((pos - anchor).toDouble() / barMs).toInt()
        val durMs = p.getDurationMs()

        val out = ArrayList<SlicePoint>(count)
        // 从当前所在小节往前数 count-1 个，再往后铺满，保证播放头附近总有点。
        for (i in 0 until count) {
            val bar = curBar - (count / 2) + i
            if (bar < 0) continue
            val targetD = anchor + bar * barMs
            if (targetD <= 0.0) continue
            // 超出曲长的点没意义（跳过去是空白），不画
            if (durMs > 0 && targetD >= durMs - 1000) continue
            out.add(SlicePoint(i, bar, targetD.toLong(), 0))
        }
        return out
    }

    /**
     * 跳到指定绝对小节。（V8.371）
     * 与 beatJump 共用同一套栅格口径；落点吸附小节线，失败返回 false（不裸跳）。
     */
    fun djSeekToBar(barIndex: Int): Boolean {
        if (!xfadeCfg().djMode) return false
        val p = oboeDirectPlayer ?: return false
        if (!p.isPlaying || !p.isPrepared) return false
        val song = currentSong ?: return false
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank() || path.startsWith("content://")) return false
        if (barIndex < 0) return false
        com.sdw.music.player.BpmKeyCache.init(this)

        val pos = p.getCurrentPositionMs()
        val (gBeat, gBpm) = gridBeatAt(path, pos)
        val bpm = if (gBpm in 40..220) gBpm else com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        if (bpm !in 40..220) return false
        val bpmF = if (gBpm in 40..220) gBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f } ?: bpm.toFloat()
        val anchor = when {
            gBeat > 0 -> gBeat
            xfadeABeatPhaseMs > 0 -> xfadeABeatPhaseMs
            else -> com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path).toLong()
        }
        if (anchor <= 0L) return false
        val targetD = anchor + barIndex * (60000.0 / bpmF) * 4.0
        if (targetD <= 0.0) return false
        val durMs = p.getDurationMs()
        if (durMs > 0 && targetD >= durMs - 1000) return false
        val target = targetD.toLong()
        p.seekTo(target)
        Log.i(TAG, "DJSliceseek: bar $barIndex -> ${target}ms (bpm=%.2f)".format(bpmF))
        return true
    }

    /**
     * 【V8.419】入口对齐残差日志（纯观测，不改任何参数）。
     *
     * 用户诉求是「8 小节过渡时 B 轨第一拍要接 A 轨第 8 拍之后的第 9 拍」，
     * 这件事此前无法验收：日志只有 `B bar-snap`（吸附动作）和 `PL init phase`（相位种子），
     * 没有任何数字说明"接进去之后到底差多少"。
     *
     * 这里用与 snap 同一套栅格公式，算出：
     *   - A 入口（过渡触发时刻）的拍号 k、beatInBar、该拍在过渡起点后的偏移
     *   - B 入口（chorusMs）的拍号 k'、beatInBar
     *   - phaseResidualMs = B 入口相对「A 当前拍」的偏差（正=晚，负=早）
     * 验收判据：residual 绝对值 < 拍长的 ~5% 视为齐；beatInBar 一致即 downbeat 对 downbeat。
     */
    private fun logEntryAlignment(p: OboeDirectPlayer, preSong: Song?, bEntryMs: Int, bm: BeatmatchParams) {
        try {
            val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            if (aPath.isBlank() || bPath.isBlank()) return
            val aBpm = bm.aBpm.takeIf { it in 40f..220f } ?: beatTempoOf(aPath)
            val bBpm = bm.bBpm.takeIf { it in 40f..220f } ?: beatTempoOf(bPath)
            if (aBpm !in 40f..220f || bBpm !in 40f..220f) {
                Log.i(TAG, "ALIGN: unknown bpm (a=$aBpm b=$bBpm) -> cannot score entry")
                return
            }
            val aBeatMs = 60000.0 / aBpm.toDouble()
            val bBeatMs = 60000.0 / bBpm.toDouble()
            // A 侧：过渡触发时刻在 A 曲里的位置
            val aPos = p.getCurrentPositionMs().toLong()
            val (aGridBeat, _) = gridBeatAt(aPath, aPos)
            val aAnchor = when {
                aGridBeat > 0 -> aGridBeat
                com.sdw.music.player.BpmKeyCache.getFirstBeatMs(aPath) > 0 ->
                    com.sdw.music.player.BpmKeyCache.getFirstBeatMs(aPath).toLong()
                else -> 0L
            }
            // B 侧：入场点
            val bEntry = bEntryMs.toLong().coerceAtLeast(0L)
            val (bGridBeat, _) = gridBeatAt(bPath, bEntry)
            val bAnchor = when {
                bGridBeat > 0 -> bGridBeat
                com.sdw.music.player.BpmKeyCache.getFirstBeatMs(bPath) > 0 ->
                    com.sdw.music.player.BpmKeyCache.getFirstBeatMs(bPath).toLong()
                else -> 0L
            }
            var aBeatInBar = -1; var aK = 0L; var aOffsetMs = 0L; var aFrac = 0.0
            if (aAnchor > 0) {
                // 【V8.419 修】(aPos - aAnchor) 是 Long、aBeatMs 是 Double：整型运算优先会走
                // Long 除法把小数截断（Kotlin 报 "f != java.lang.Long"）。两侧都显式转 Double。
                val aPosD = aPos.toDouble()
                val aAnchorD = aAnchor.toDouble()
                aFrac = (aPosD - aAnchorD) / aBeatMs - Math.round((aPosD - aAnchorD) / aBeatMs)
                aK = Math.round((aPosD - aAnchorD) / aBeatMs)
                aBeatInBar = (((aK % 4) + 4) % 4).toInt()
                aOffsetMs = (aFrac * aBeatMs).toLong()
            }
            var bBeatInBar = -1; var bK = 0L; var bOffsetMs = 0L; var bFrac = 0.0
            if (bAnchor > 0) {
                val bEntryD = bEntry.toDouble()
                val bAnchorD = bAnchor.toDouble()
                bFrac = (bEntryD - bAnchorD) / bBeatMs - Math.round((bEntryD - bAnchorD) / bBeatMs)
                bK = Math.round((bEntryD - bAnchorD) / bBeatMs)
                bBeatInBar = (((bK % 4) + 4) % 4).toInt()
                bOffsetMs = (bFrac * bBeatMs).toLong()
            }
            // 相位残差：B 的 downbeat 相对 A 当前拍（换算到 A 的拍长）的偏移
            val residual = if (aBeatInBar >= 0 && bBeatInBar >= 0) {
                val bPhaseInBar = bBeatInBar + bFrac
                val aPhaseInBar = aBeatInBar + aFrac
                ((bPhaseInBar - aPhaseInBar + 8.0) % 4.0 - 2.0) * aBeatMs
            } else Double.NaN
            Log.i(
                TAG,
                "ALIGN: A[pos=%dms beat=%d inBar=%d off=%.0fms beatMs=%.1f bpm=%.2f] " +
                        "B[entry=%dms beat=%d inBar=%d off=%.0fms beatMs=%.1f bpm=%.2f] " +
                        "phaseResidual=%.1fms ratio=%.5f (%.1f%% of A beat) %s".format(
                    aPos, aK, aBeatInBar, aOffsetMs.toDouble(), aBeatMs, aBpm,
                    bEntry, bK, bBeatInBar, bOffsetMs.toDouble(), bBeatMs, bBpm,
                    residual, bm.ratio, kotlin.math.abs(residual) / aBeatMs * 100.0,
                    if (aBeatInBar == bBeatInBar) "downbeat-aligned" else "BEAT-MISALIGNED"
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "logEntryAlignment: ${t.message}")
        }
    }

    // =====================================================================
    // 【V8.710】DJ 谐波选曲委托层：实现已提取至 playback/DjHarmonicPicker.kt（无状态 object）。
    // 此处保留同签名 private fun 薄委托，全部调用点零改动；两个取数助手把 MusicService 的
    // 运行时状态（当前曲 BPM / 变速钳位窗）参数化注入 picker。
    // =====================================================================

    /** 当前曲 BPM（供 picker 分档；无当前曲/无数据返回 0f）。 */
    private fun curBpmForPick(): Float = currentSong?.let { cs ->
        val p = cs.filePath.ifEmpty { cs.path }
        if (p.isBlank()) 0f else beatTempoOf(p)
    } ?: 0f

    /** DJ 变速钳位窗百分比（与 native 同口径，选曲 BPM 分档用）。 */
    private fun rubberbandMaxPct(): Int = getSharedPreferences("settings", MODE_PRIVATE)
        .getInt("crossfade_rubberband_max", 8).coerceIn(1, 40)

    private fun keyScore(a: String, b: String): Int = DjHarmonicPicker.keyScore(a, b)
    private fun camelotCompatible(a: String, b: String): Boolean = DjHarmonicPicker.camelotCompatible(a, b)
    private fun bpmDeltaPct(curBpm: Float, idx: Int, songs: List<Song>): Float =
        DjHarmonicPicker.bpmDeltaPct(curBpm, idx, songs, ::beatTempoOf)
    private fun bpmTierOf(pool: List<Int>, songs: List<Song>): Triple<Int, List<Int>, List<List<Int>>> =
        DjHarmonicPicker.bpmTierOf(pool, songs, curBpmForPick(), ::beatTempoOf, rubberbandMaxPct())
    private fun allTiersInOrder(best: Int, buckets: List<List<Int>>): List<Int> =
        DjHarmonicPicker.allTiersInOrder(best, buckets)
    private fun closestEnergyBand(withE: List<Pair<Int, Float>>): List<Int> =
        DjHarmonicPicker.closestEnergyBand(withE)
    private fun energyDelta(idx: Int, songs: List<Song>, curLufs: Float?): Float? =
        DjHarmonicPicker.energyDelta(idx, songs, curLufs)
    private fun camelotKeyOf(idx: Int, songs: List<Song>): String =
        DjHarmonicPicker.camelotKeyOf(idx, songs)
    private fun weightByEnergy(pool: List<Int>, withE: List<Pair<Int, Float>>): Map<Int, Float> =
        DjHarmonicPicker.weightByEnergy(pool, withE)
    private fun weightedRandom(weights: Map<Int, Float>): Int =
        DjHarmonicPicker.weightedRandom(weights)
    private fun pickHarmonic(pool: List<Int>, songs: List<Song>, curKey: String, curLufs: Float?, energyStrategy: Int = 0): Int =
        DjHarmonicPicker.pickHarmonic(pool, songs, curKey, curLufs, energyStrategy,
            curBpmForPick(), ::beatTempoOf, rubberbandMaxPct(), recentPickedIdx(), recentPicked.firstOrNull())

    /** V8.343: indices handed out by pickNext/pickHarmonic recently (most recent first). */    @Synchronized
    private fun recentPickedIdx(): Set<Int> = recentPicked.toSet()

    /** V8.343: record a pick so the next round can avoid repeating it. */
    @Synchronized
    private fun markPickedIdx(idx: Int) {
        recentPicked.remove(idx)
        recentPicked.add(0, idx)
        while (recentPicked.size > RECENT_PICK_MAX) recentPicked.removeAt(recentPicked.size - 1)
    }

    /** How many recent picks to exclude (>= the realistic size of pickHarmonic's candidate). */
    private val RECENT_PICK_MAX = 8
    private val recentPicked = java.util.Collections.synchronizedList(mutableListOf<Int>())

    // 【V8.500 ④】自动 Camelot 调性匹配辅助。
    // 调性平移严格复用 AudioAnalyzer.toCamelot 的映射（半音0..11 → 编号），否则与检测出来的记号对不上：
    //   大调 B：半音 0..11 → 8,3,10,5,12,7,2,9,4,11,6,1
    //   小调 A：半音 0..11 → 5,12,7,2,9,4,11,6,1,8,3,10
    private val MAJOR_ROOT_TO_NUM = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val MINOR_ROOT_TO_NUM = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    // 编号(1..12) → 根音半音：上面两张表的逆，下标 0 弃用
    private val MAJOR_NUM_TO_ROOT = intArrayOf(-1, 11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4)
    private val MINOR_NUM_TO_ROOT = intArrayOf(-1, 8, 3, 10, 5, 0, 11, 2, 1, 4, 9, 6, 1)

    /** 把 Camelot 调性按 semi 个半音平移（同大/小调，只搬根音）。 */
    private fun transposeCamelot(key: String, semi: Int): String {
        val m = Regex("(\\d{1,2})\\s*([AB])").find(key.trim().uppercase()) ?: return key
        val num = m.groupValues[1].toIntOrNull() ?: return key
        val letter = m.groupValues[2]
        val numToRoot = if (letter == "A") MINOR_NUM_TO_ROOT else MAJOR_NUM_TO_ROOT
        val rootToNum = if (letter == "A") MINOR_ROOT_TO_NUM else MAJOR_ROOT_TO_NUM
        val root = numToRoot.getOrElse(num) { return key }
        val newRoot = ((root + semi) % 12 + 12) % 12
        return "${rootToNum[newRoot]}$letter"
    }

    /** 找最小半音移位，使 transpose(aKey,s) 与 bKey Camelot 兼容（score≤1）；找不到兼容落点返回 0。 */
    private fun camelotBestShift(aKey: String, bKey: String): Int {
        var bestSemi = 0
        var bestScore = 3
        for (s in -6..6) {
            if (s == 0) continue
            val sc = keyScore(transposeCamelot(aKey, s), bKey)
            if (sc < bestScore || (sc == bestScore && kotlin.math.abs(s) < kotlin.math.abs(bestSemi))) {
                bestScore = sc; bestSemi = s
            }
        }
        return if (bestScore <= 1) bestSemi else 0
    }

    /**
     * 【V8.500 ④】自动 Camelot 调性匹配：算出把「出轨 A」整体平移几个半音，
     * 才能让 A 的调性与「入轨 B」Camelot 兼容（同调 score0 / 相邻 score1）。
     *
     * 与 djay Key Match 对齐：本架构过渡期引擎只处理出轨 A（入轨 B 走 1:1 原生），
     * 所以调性匹配作用在 A 上；落点引擎退役后 B 仍是自身原生调性 → 无调性阶跃。
     * 仅当 keylock 开 + crossfade_match_key 开时生效；A/B 任一缺调性则不动。
     * 选曲侧已按 Camelot 分档优先挑同调/兼容曲，故多数情况已 score≤1 → 返回 0（不平移）；
     * 仅当被迫选了不兼容曲（如 BPM 优先导致）才做最小半音校正（通常 ±2/±3/±5）。
     */
    private fun computeKeyMatchSemi(preSong: Song?): Int {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        if (!cfg.getBoolean("crossfade_match_key", true)) return 0
        if (!djKeyLock()) return 0
        val aKey = camelotKeyOfSong(currentSong)
        val bKey = camelotKeyOfSong(preSong)
        if (aKey.isBlank() || bKey.isBlank()) return 0
        if (keyScore(aKey, bKey) <= 1) return 0   // 已兼容，无需平移
        val semi = camelotBestShift(aKey, bKey)
        if (semi != 0) Log.i(TAG, "KeyMatch: A=$aKey -> shift $semi semi to match B=$bKey")
        return semi
    }

    /** 取单首歌 Camelot 调性（优先 BpmKeyCache，回退 Song.key）。 */
    private fun camelotKeyOfSong(song: Song?): String {
        val sp = song?.let { it.filePath.ifEmpty { it.path } } ?: ""
        if (sp.isBlank()) return song?.key ?: ""
        return com.sdw.music.player.BpmKeyCache.get(sp)?.second?.takeIf { it.isNotBlank() }
            ?: song?.key ?: ""
    }

    /** 【v8.166】把 B 入场点吸附到「小节内与 A 当前同一拍位」的拍上。
     *  旧 snapBToBeatGrid 只吸附到 B 自己的最近拍 —— 两边拍是齐了，但小节相位（第几拍）
     *  是随机的：A 在第 3 拍时 B 可能从第 1 拍进，downbeat 对不上，听感就是"差半拍"。
     *  aBeatInBar<0（相位未知）时退回原来的最近拍吸附。 */
    /**
     * 【V8.316】入场点守卫用的"本次过渡时长"（ms）。
     * 单独抽出来是因为入场点校验在 `cfDur` 变量声明**之前**就要用到，
     * 而 adaptiveXfadeDur 可能被 BPM 差降级压短（echo-out 1.5s）——
     * 守卫用"未降级的用户设定时长"更保守（要求剩余内容更多），宁可保守不可乐观。
     */
    private fun cfDurForEntryGuard(preSong: Song?): Long =
        try { adaptiveXfadeDur(preSong).toLong() } catch (_: Throwable) { 15_000L }

    /**
     * 【V8.320】B 入场点「太靠后」双守卫 —— 常规路径与 FLOOR 路径**共用**。
     *
     * 背景：入场点落在曲尾 → B 一进场就接近 EOS → `remain <= dur` 立刻成立 →
     * 落点后 200ms 重挂 monitor 又触发 → 无限连锁（用户报「自动切歌后下一曲又变尾部」）。
     * 三道防线：
     *   ① native 扫描窗跟曲长自适应（oboe_bridge.cpp）
     *   ② 本函数第一段：入场点 > 曲长 60% → 判误判，回退首拍 / 曲长 40%
     *   ③ 本函数第二段：**snap 之后**再判剩余量 ≥ 过渡时长 + 8s，不够就往前拉
     *      —— 必要因为 snapBToBarGrid 搜索窗 ±32 拍（@120bpm ≈ ±16s），吸附本身能推后
     *
     * 【V8.320 修复】以前这段只写在常规路径（doCrossfadeTriggerBg）里，
     * **FLOOR 兜底路径完全没做**—— 而 FLOOR 恰恰是最容易接曲尾的那条
     *（它在 `remain <= 1500` 即 A 快到结尾时触发）。抽出成公共函数杜绝第三次漏改。
     *
     * @return 修正后的入场点（ms）
     */
    private fun guardBEntryPoint(preSong: Song?, entryMs: Long): Long {
        val bTotal = preSong?.duration?.takeIf { it > 0 } ?: return entryMs
        var v = entryMs
        // 【V8.321 新增绝对上限】比例阈值对长曲太宽松：6分钟的歌 3:00 = 50%，不触发 60% 阈值，
        // 但 3 分钟处多半是「间奏后的能量回升」而不是真副歌 —— 用户实测就卡在这。
        // 【V8.334 跟曲长自适应·与native 对齐】原口径是 min(45% 歌长, **90s**)，
        // 当时是为了「与 native 选位口径一致」——native 用的是同一个 90s 封顶。
        // 但 V8.334 已把 native 的扫描范围放宽到 min(75% 歌长, 300s)，
        // 这里若仍留 90s 硬上限，长曲真副歌（2~3 分钟处）会被 native 检出、又被这里打回，
        // 反而回到「B 从 3 分钟进」的老问题。**两边口径必须同步放宽**。
        // 放宽后仍有两道保险：
        //   ② 60% 比例限制 —— 任何情况下 B 不会从后半段进；
        //   ③ 剩余量守卫 —— B 剩余内容必须够播完整个过渡 + 8s 余量。
        // 且 DJ 语义上入场点本就在歌曲前段：放宽的是**检测范围**（让真副歌能被看见），
        // 不是**准入范围**。
        val hardCapMs = minOf(bTotal * 75 / 100, 300_000L)
        if (v > hardCapMs) {
            val fallback0 = if (xfadeBFirstBeatMs in 1..hardCapMs) xfadeBFirstBeatMs
                            else (bTotal * 2 / 5).coerceAtMost(hardCapMs)
            Log.w(TAG, "Crossfade: B entry ${v}ms exceeds hard cap ${hardCapMs}ms (song=${bTotal}ms) -> fallback ${fallback0}ms")
            v = fallback0
        }
        // ② 太靠后 → 判误判
        if (v > bTotal * 3 / 5) {
            val fallback = if (xfadeBFirstBeatMs > 0L) xfadeBFirstBeatMs else (bTotal * 2 / 5)
            Log.w(TAG, "Crossfade: B entry ${v}ms too late (>60% of ${bTotal}ms) -> fallback ${fallback}ms (firstBeat=$xfadeBFirstBeatMs)")
            v = fallback
        }
        // ③ snap 后剩余量守卫
        val need = cfDurForEntryGuard(preSong)
        val remainB = bTotal - v
        if (remainB < need + 8000L) {
            val earliest = (bTotal - (need + 30_000L)).coerceAtLeast(0L)
            val fixed = snapBToBeatGrid(preSong, earliest)
            Log.w(TAG, "B entry ${v}ms leaves only ${remainB}ms (< dur ${need}+8s) -> pull back to ${fixed}ms (song=${bTotal}ms)")
            v = fixed
        }
        return v
    }

    // 【V8.422】B 轨入场点选择。
    //   "intro"（默认，djay 式）：进到前奏首拍（beat grid 起点），整首歌完整播，前奏安静淡入；
    //   "chorus"（旧行为）：进到副歌/高潮，跳过前奏+主歌。
    // 两种模式都在「任意模式」生效（不再被 bpmChorusMode 门控）—— 纯随机模式 B 也会 seek 到首拍，
    // 不再从 0:00 静音头播。chorus 模式内部仍做 3s 轮询等实时副歌预扫（与旧行为一致）。
    /**
     * 【#2】B 轨入场点落到"检测到的换段处"。
     * 给定算出的入场点 [rawMs] 与 B 的段落边界，若附近（≤ 一个乐句 ~16s、且不晚于 raw+4s，
     * 避免跳过 B 的段落开头）存在检测换段点，则把入场点挪到那个换段起点；
     * 否则保持原值（退回小节线吸附行为，无退化）。换段起点在检测时已是小节对齐的。
     */
    private fun snapBEntryToSection(rawMs: Long, bPath: String): Long {
        if (rawMs <= 0L) return rawMs
        if (bPath.isBlank() || bPath.startsWith("content://")) return rawMs
        val secs = com.sdw.music.player.BpmKeyCache.getStructure(bPath).sections
        if (secs.isEmpty()) return rawMs
        var best = -1L
        var bestD = Long.MAX_VALUE
        for (s in secs) {
            val d = kotlin.math.abs(s.startMs - rawMs)
            if (d < bestD && s.startMs <= rawMs + 4000L) { bestD = d; best = s.startMs.toLong() }
        }
        if (best >= 0L && bestD <= 16_000L) {
            Log.i(TAG, "B entry: section-snap $rawMs -> $best ms (detected section start, ${secs.size} sections)")
            return best
        }
        return rawMs
    }

    private fun resolveBEntryMs(preSong: Song?): Long {
        val path = preSong?.filePath?.ifEmpty { preSong.path } ?: ""
        // 【V8.472】用户明确要求："我想进歌点是人声第一拍"。
        //
        // 之前的优先级链是 Drop → 副歌 → 首拍 → 前奏长度 → 0，**根本没有"人声"
        // 这一档** —— 人声点只被写进 introQuietMs 沉在链尾，而 drop/chorus 几乎
        // 总能算出值，于是实际进歌点恒为能量点/副歌点。真机日志佐证：
        //   Crossfade: B phrase-snap 28600ms -> 21100ms
        //   Crossfade: B starts at chorus 21100ms (grid-snapped)
        // 用户听到的"从前旋律进"就是它。
        //
        // 修法：把人声第一拍提到**最前**。xfadeBIntroMs 自 V8.446 起已经是
        // "优先人声、检不出才回落 kick 能量点"的值（V8.472 又加了谐波度判据）。
        // 检不出人的歌（纯器乐/检测失败）才往下走原链，行为不变。
        val vocal = xfadeBIntroMs
        if (vocal > 0L) {
            Log.i(TAG, "B entry: vocal=$vocal ms (人声第一拍, 优先级最高)")
            return vocal
        }
        // 【#6】cue 点感知入场：人声没检出来时，优先落到检测到的「首段起点」（intro/verse 头），
        // 即 djay 的自然 cue 点，比 drop/chorus 更接近"从前奏/乐句头进"。人声优先（V8.472）已在上一步返回。
        val cueOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("crossfade_cue_entry", true)
        if (cueOn) {
            val secs = com.sdw.music.player.BpmKeyCache.getStructure(path).sections
            val cue = (secs.firstOrNull()?.startMs ?: 0).toLong()
            if (cue > 0L) {
                Log.i(TAG, "B entry: cue=$cue ms (cue-aware, sections=${secs.size})")
                return guardBEntryPoint(preSong, cue)
            }
        }
        Log.i(TAG, "B entry: 未取到人声点(introQuiet=$vocal)，沿用 ${bEntryMode} 链")
        val base = when (bEntryMode) {
            "vocal" -> {
                // 显式"人声"模式：人声没检出来也不掉到 drop/chorus（那样又会进错），
                // 退到首拍——首拍是"音乐真正开始"的点，比能量点更接近用户预期。
                val fb = xfadeBFirstBeatMs
                if (fb > 0) fb else 0L
            }
            "drop", "auto" -> {
                // 【V8.431 djay 式】优先从**能量爆发点**（Drop）进场：
                // 从能量爬升段的终点进，过渡一上来就是满能量 —— 这才是 djay 的听感。
                //
                // 【V8.432 实测修正】为什么不是"永远第 10 秒"：
                // 旧默认 "intro" 用 firstBeat，而 firstBeat 是 preScanFirstBeatMs 里
                // "前 20 秒内第一个 >= 峰值 60% 的 100ms 桶"。对多数流行曲来说那个桶
                // 恰好落在第一个大能量点附近（8~12s）—— 那不是常数，是 60% 阈值的产物。
                //
                // 【V8.432】Drop 判据在压缩母带下常落空（实测《无人之岛》drop=-1），
                // 已把 DROP_MIN_MS 从 8s 放宽到 2.5s；这里再加兜底链，保证任何情况都能进：
                //   Drop → 副歌 → 首拍 → 前奏长度 → 0（从头）
                val dp = xfadeBDropMs
                if (dp > 0) {
                    Log.i(TAG, "B entry: drop=$dp ms (energy point)")
                    dp
                } else {
                    val ch = xfadeBChorusMs
                    if (ch > 0) {
                        Log.i(TAG, "B entry: chorus=$ch ms (drop not detected)")
                        ch
                    } else {
                        val fb = xfadeBFirstBeatMs
                        val pick = if (fb > 0) fb else if (xfadeBIntroMs > 0) xfadeBIntroMs else 0L
                        Log.i(TAG, "B entry: firstBeat/intro=$pick ms (drop+chorus not detected)")
                        pick
                    }
                }
            }
            "intro" -> {
                // 前奏首拍（音乐/beat grid 起点）；无首拍则退到前奏静音长度；再无则从头
                val fb = xfadeBFirstBeatMs
                if (fb > 0) fb else if (xfadeBIntroMs > 0) xfadeBIntroMs else 0L
            }
            else -> { // "chorus"（现状）
                var c = xfadeBChorusMs
                if (c < 0L) {
                    val deadline = System.currentTimeMillis() + 3000
                    while (c < 0 && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        c = xfadeBChorusMs
                        if (c < 0 && path.isNotBlank()) {
                            val cached = com.sdw.music.player.BpmKeyCache.getStructure(path).chorusMs
                            if (cached > 0) { c = cached.toLong(); xfadeBChorusMs = c }
                        }
                    }
                }
                if (c <= 0L && xfadeBFirstBeatMs > 0) c = xfadeBFirstBeatMs
                val introQ = xfadeBIntroMs
                if (c > 0 && introQ > 0 && c < introQ && xfadeBFirstBeatMs > c) c = xfadeBFirstBeatMs
                c
            }
        }
        return snapBEntryToSection(base, path)
    }

    /**
     * 【V8.446】手动切歌的入场点。
     *
     * 与 [resolveBEntryMs] **同一套优先级**，但数据源不同：
     * `resolveBEntryMs` 读的是预加载阶段写下的 `xfadeB*` 字段（那是被交叉淡化准备好的那首），
     * 手动切歌时目标歌还没进预加载，直接复用会拿到上一首的入场点。
     * 所以这里改为直接查 [BpmKeyCache] 的结构缓存。
     *
     * 优先级（与自动路径完全一致，V8.472 起人声第一拍最高）：
     *   人声第一拍 → Drop（能量爆发点）→ 副歌 → 首拍 → 0（从头）
     *
     * 注意：这里**不涉及 crossfadeBars**。小节数是过渡时长，与进入哪一拍无关。
     */
    private fun resolveManualEntryMs(song: Song): Long {
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank() || path.startsWith("content://")) return -1L
        val durMs = song.duration
        com.sdw.music.player.BpmKeyCache.init(this)
        val st = com.sdw.music.player.BpmKeyCache.getStructure(path)
        val firstBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path).toLong()

        val picked = when {
            // 【V8.472】人声第一拍提到最前（与自动路径同口径）。
            // st.introQuietMs 自 V8.446 起就是"优先人声、检不出才回落 kick 能量点"的值。
            st.introQuietMs > 0 -> { Log.i(TAG, "ManualEntry: vocal/intro=${st.introQuietMs} ms (人声第一拍, 优先级最高)"); st.introQuietMs.toLong() }
            st.dropMs > 0 -> { Log.i(TAG, "ManualEntry[$path]: drop=${st.dropMs} ms"); st.dropMs.toLong() }
            st.chorusMs > 0 -> { Log.i(TAG, "ManualEntry: chorus=${st.chorusMs} ms (drop none)"); st.chorusMs.toLong() }
            st.introQuietMs > 0 -> { Log.i(TAG, "ManualEntry: intro=${st.introQuietMs} ms (vocal/entry point)"); st.introQuietMs.toLong() }
            firstBeat > 0 -> { Log.i(TAG, "ManualEntry: firstBeat=${firstBeat} ms (structure none)"); firstBeat }
            else -> { Log.i(TAG, "ManualEntry: no structure cached -> play from 0"); return -1L }
        }
        if (picked <= 0L) return -1L
        // 与自动路径同口径：先守卫再吸附小节线
        var v = guardBEntryPoint(song, picked)
        v = snapBEntryToSection(v, path)   // 【#2】优先落到检测到的换段处
        v = snapBToBarGrid(song, v, 0)
        v = guardBEntryPoint(song, v)
        // 进歌点离歌尾太近就放弃（守卫已覆盖大半，这里再兜一层：offset 必须留出可播时长）
        if (durMs > 0 && durMs - v < 15_000L) {
            Log.w(TAG, "ManualEntry: $v ms too close to end (dur=${durMs}ms) -> play from 0")
            return -1L
        }
        Log.i(TAG, "ManualEntry: final start = $v ms (dur=${durMs}ms bars=$crossfadeBars NOT used here)")
        return v
    }

    private fun snapBToBarGrid(preSong: Song?, msLong: Long, aBeatInBar: Int): Long {
        if (msLong <= 0L) return msLong
        val path = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        if (path.isBlank()) return msLong
        if (!gridTrusted(path)) return msLong   // 【#1】低置信度：不吸附，回退非网格触发
        com.sdw.music.player.BpmKeyCache.init(this)
        // 【P2】拍号取该段自己的（旧数据缺省 4/4 → 与改造前完全一致）
        val segB = gridSegmentAt(path, msLong)
        val bpbB = beatsPerBarOf(segB)
        if (aBeatInBar !in 0 until bpbB) return snapBToBeatGrid(preSong, msLong)
        val (gBeat, gBpm) = gridBeatAt(path, msLong)
        val bpm = if (gBpm in 40..220) gBpm else com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        if (bpm !in 40..220) return msLong
        val cachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path)
        // 【P2】锚点 = 首拍 + 重拍偏移（旧数据偏移 0 → 与改造前一致）
        val dboB = downbeatOffsetOf(segB)
        val anchor = if (gBeat > 0) gBeat + dboB
            else if (cachedBeat > 0) cachedBeat.toLong() + dboB
            else if (xfadeBFirstBeatMs > 0) xfadeBFirstBeatMs + dboB
            else return msLong
        // 【V8.304】浮点 tempo 算拍长（整数 BPM 量化会让 ±32 拍乐句搜索的候选点整体偏移）
        val bpmF = if (gBpm in 40..220) gBpm.toFloat()
                   else com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f } ?: bpm.toFloat()
        val beatMs = 60000.0 / bpmF
        // 【V8.221 Automix 乐句切入】开 = 优先吸附到 B 自己的 8 小节乐句起点（32 拍，
        // djay Automix 的 16/32 拍段落点切入）：±8 小节内取最近的乐句线，找不到再退回
        // 常规小节相位吸附。A 侧触发本来就对齐到小节线（downbeat），B 又从乐句头进，
        // 两边 downbeat 相齐且 B 起步就是一个完整乐句 —— 调性匹配由 shuffle bpm 模式的
        // keyScore 负责，这里只管落点。
        val automix = landingModeOf(
            getSharedPreferences("settings", MODE_PRIVATE)
        ) == "phrase"   // 【V8.248】原 crossfade_automix 独立键已并入落点模式
        if (automix) {
            val k0p = Math.round((msLong - anchor) / beatMs)
            var bestKp: Long? = null
            var bestDeltaP = Double.MAX_VALUE
            for (dk in -32..32) {
                val cand = k0p + dk
                val m = ((cand % 32) + 32) % 32
                if (m != 0L) continue
                val delta = kotlin.math.abs(anchor + cand * beatMs - msLong)
                if (delta < bestDeltaP) { bestDeltaP = delta; bestKp = cand }
            }
            val kp0 = bestKp
            if (kp0 != null) {
                // 【V8.226 逻辑审查】就近的乐句线若落在入场点之后（>半拍），退到前一个乐句线：
                // 绝不跳过 B 的段落开头（否则 B 从乐句中段闯入，正是 automix 要消除的情形）；
                // 宁可早进听半句前奏。移位后仍在 ±8 小节信任界内（就近≤4 小节 + 退一位≤8 小节）。
                var kp = kp0
                if (anchor + kp * beatMs > msLong + 0.5 * beatMs) kp = kp0 - 32
                val snappedP = (anchor + kp * beatMs).toLong()
                // 最多允许挪 8 小节，超出说明栅格/乐句不可信 → 退回常规吸附
                if (snappedP > 0L && kotlin.math.abs(snappedP - msLong) <= beatMs * 32.0 + 1.0) {
                    if (snappedP != msLong) {
                        Log.i(TAG, "Crossfade: B phrase-snap ${msLong}ms -> ${snappedP}ms (8-bar phrase, anchor=$anchor bpm=$bpm)")
                    }
                    return snappedP
                }
            }
        }
        val k0 = Math.round((msLong - anchor) / beatMs)
        var bestK: Long? = null
        var bestDelta = Double.MAX_VALUE
        for (dk in -3..3) {
            val cand = k0 + dk
            // 【P2】按实际拍号取模（4/4 时与原来的 %4 完全等价）
            val m = ((cand % bpbB) + bpbB) % bpbB
            if (m.toInt() != aBeatInBar) continue
            val delta = kotlin.math.abs(anchor + cand * beatMs - msLong)
            if (delta < bestDelta) { bestDelta = delta; bestK = cand }
        }
        val k = bestK ?: return snapBToBeatGrid(preSong, msLong)
        val snapped = (anchor + k * beatMs).toLong()
        if (snapped <= 0L) return msLong
        // 最多允许挪 2 拍，超出说明栅格不可信
        if (kotlin.math.abs(snapped - msLong) > beatMs * 2.0 + 1.0) return msLong
        if (snapped != msLong) Log.i(TAG, "Crossfade: B bar-snap ${msLong}ms -> ${snapped}ms (barPhase=$aBeatInBar/$bpbB dbo=$dboB beat=$anchor bpm=$bpm)")
        return snapped
    }

    /**
     * 【V8.322】PLL 整定参数下发。三个值都从 settings 读，改设置即时生效。
     *
     * 默认值是"浮点 BPM 时代"的新值：
     * - deadzone 0.0002：旧值 0.001 = 0.1% 速度盲区，而人耳~0.5% 就敏感 → 旧值可能卡住残余误差
     * - gainLo 0.10：接近同步时的小增益，防振荡
     * - gainHi 0.30：误差大时的大增益，快速收敛（native 侧在中间线性插值）
     *
     * 存int（微单位/千分位）避免 SharedPreferences 没有原生 Float XML 类型。
     */
    private fun applyPlTuning(p: OboeDirectPlayer) {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        // deadzone 存为「百万分之一」为单位：默认 200 = 0.0002
        val dz = (cfg.getInt("pll_deadzone_micro", 200).coerceIn(1, 20000)) / 1_000_000f
        val gLo = (cfg.getInt("pll_gain_lo_pct", 10).coerceIn(0, 100)) / 100f
        val gHi = (cfg.getInt("pll_gain_hi_pct", 30).coerceIn(0, 200)) / 100f
        p.setPlTuning(dz, gLo, maxOf(gLo, gHi))
    }

    /**
     * 【V8.322】把「B 入场点在 4/4 小节内的拍位」算出来下发给 native。
     *
     * native 原来硬写 `g_bmPlMB = 0`（假定 B 从 downbeat 起播），但入场点是
     * `snapBToBarGrid` 吸到「与 A 当前拍位相同」的那一拍 —— **不保证是 downbeat**。
     * 于是初始相位与实际不符，PLL 要花几秒才追回来（听感就是开头鼓点对不齐）。
     *
     * 这里用与 snap 完全相同的栅格公式重算拍序号，取 `k mod 4`（0=downbeat），
     * 归一化成 0..1 下发。取不到栅格就下发 -1，native 退回旧行为（假定 downbeat）。
     *
     * 必须在 `setBeatmatch()` **之前**调用（后者武装 PLL 时才读这个值）。
     */
    private fun pushBEntryBeatPhase(p: OboeDirectPlayer, preSong: Song?, entryMs: Long) {
        try {
            val path = preSong?.filePath?.ifEmpty { preSong.path } ?: ""
            if (path.isBlank() || path.startsWith("content://") || entryMs <= 0L) {
                p.setPlInitPhase(-1f); return
            }
            com.sdw.music.player.BpmKeyCache.init(this)
            val bpmF = com.sdw.music.player.BpmKeyCache.getBpmFloat(path).takeIf { it > 0f }
            if (bpmF == null || bpmF < 40f || bpmF > 220f) { p.setPlInitPhase(-1f); return }
            val beatMs = 60000.0 / bpmF
            val (gBeat, _) = gridBeatAt(path, entryMs)
            val cachedBeat = com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path)
            val anchor = when {
                gBeat > 0 -> gBeat
                xfadeBFirstBeatMs > 0 -> xfadeBFirstBeatMs
                cachedBeat > 0 -> cachedBeat.toLong()
                else -> { p.setPlInitPhase(-1f); return }
            }
            val k = Math.round((entryMs - anchor) / beatMs)
            val beatInBar = (((k % 4) + 4) % 4).toInt()
            p.setPlInitPhase(beatInBar / 4.0f)

            // 【V8.343 Master Sync 影子模式】在拿到入场点与拍长后解算一次跨曲目相位。
            // 【纯观测】不改entryMs、不改过渡时长、不调 native —— 只打日志 + 累加统计。
            // 参数里的 xfadeMs 用 0 表示「本轮时长此刻未定」，此时 MasterSync 会返回 null，
            // 真正的时长在 adaptiveXfadeDur 之后才有；故这里传 0，等落点后再补统计。
        } catch (t: Throwable) {
            Log.w(TAG, "pushBEntryBeatPhase: ${t.message}")
        }
    }

    /**
     * 【V8.503·③】把 A 轨当前的真实拍相位给 PLL（与 pushBEntryBeatPhase 对称）。
     * A 的 downbeat anchor 是 xfadeABeatPhaseMs（warmDjStructure 已重锚到真音乐 downbeat），
     * currentBeatInBar(p) 据此算出 A 当前在第几拍（0=downbeat）。归一化 beatInBar/4 下发，
     * 口径与 B 完全一致 → err=(MB-MA) 不再含幽灵相位差。
     * 取不到栅格（currentBeatInBar<0）下发 -1，native 退回旧行为（假定 downbeat@t=0）。
     * 必须在 setBeatmatch() 之前调用（后者武装 PLL 时才读这个值）。
     */
    private fun pushAEntryBeatPhase(p: OboeDirectPlayer) {
        try {
            val bib = currentBeatInBar(p)
            if (bib < 0) { p.setPlAInitPhase(-1f); return }
            p.setPlAInitPhase(bib / 4.0f)
        } catch (t: Throwable) {
            Log.w(TAG, "pushAEntryBeatPhase: ${t.message}")
        }
    }

    /**
     * V8.343 Master Sync - step 1, SHADOW MODE:
     * Solve cross-track master phase after the crossfade duration is known.
     * PURE OBSERVATION: never alters entry point, duration, native params or audio.
     * All three trigger paths (auto / FLOOR / manual) funnel through here so the
     * statistics use one consistent baseline.
     */
    private fun shadowSolvePhase(preSong: Song?, entryMs: Long, xfDurMs: Int) {
        if (entryMs <= 0L || xfDurMs <= 0) return
        try {
            val path = preSong?.filePath?.ifEmpty { preSong.path } ?: ""
            if (path.isBlank() || path.startsWith("content://")) return
            com.sdw.music.player.BpmKeyCache.init(this)
            val bpmF = com.sdw.music.player.BpmKeyCache.getBpmFloat(path)
                .takeIf { it >= 40f && it <= 220f } ?: return
            val beatMs = 60_000.0 / bpmF
            val (gBeat, _) = gridBeatAt(path, entryMs)
            val anchor = when {
                gBeat > 0 -> gBeat
                xfadeBFirstBeatMs > 0 -> xfadeBFirstBeatMs
                else -> com.sdw.music.player.BpmKeyCache.getFirstBeatMs(path).toLong()
            }
            if (anchor < 0L) return
            MasterSyncShadow.solvePhase(entryMs, beatMs, anchor, xfDurMs)
        } catch (_: Throwable) {
            // shadow mode must never affect the main path
        }
    }

    /**
     * 【P3】Master Sync 真实同步：把过渡时长微调到能让落点落在 master 的小节线上。
     *
     * 只改过渡时长、不动入场点和 native 参数 —— 这样即使算错，最坏情况也只是过渡
     * 长了/短了不到一拍，不会破坏对拍本身。
     * 开关未开 / master 未就绪 / 取不到 BPM 时一律返回 0（等价于影子模式，行为不变）。
     */
    private fun masterSyncDurAdjustMs(preSong: Song?, xfadeMs: Int): Int {
        if (!MasterSyncShadow.enabled) return 0
        if (xfadeMs <= 0) return 0
        return try {
            val path = preSong?.filePath?.ifEmpty { preSong.path } ?: ""
            if (path.isBlank()) {
                Log.w(TAG, "P3 skip: blank path")
                return 0
            }
            com.sdw.music.player.BpmKeyCache.init(this)
            val cachedBpmF = com.sdw.music.player.BpmKeyCache.getBpmFloat(path)
                .takeIf { it >= 40f && it <= 220f }
            // 兜底：Song.filePath 有时是 content:// URI（SongRepository 有 fileUri.toString()
            // 的来源），而 BpmKeyCache 以真实文件路径为 key，直接查会 miss。
            // 此时退回 MasterSyncShadow 自己维护的当前曲目 BPM —— 它同样来自 BpmKeyCache，
            // 只是 key 的取法不同，且 MASTER SKIP 时也会更新。
            val bpmF = cachedBpmF ?: MasterSyncShadow.currentBpmF.takeIf { it >= 40f && it <= 220f }
            if (bpmF == null) {
                Log.w(TAG, "P3 skip: no bpm for ...${path.takeLast(40)} (cache=$cachedBpmF cur=${MasterSyncShadow.currentBpmF})")
                return 0
            }
            MasterSyncShadow.suggestDurationAdjustMs(xfadeMs, 60_000.0 / bpmF)
        } catch (t: Throwable) {
            Log.w(TAG, "P3 skip: exception ${t.javaClass.simpleName}: ${t.message}")
            0   // 同步计算失败绝不能影响主路径
        }
    }

    // 【V8.155】按小节的过渡长度（A 轨 BPM 折算）。bars=0 或无 BPM → null（回退秒数档）
    // 1 小节 = 4 拍；ms = bars * 4 * (60000/BPM)，clamp 1500..20000
    private fun barsToMsOrNull(): Int? {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        // 【V8.429 修"过渡太晚/被削短"】这个值决定**何时触发**，必须和实际混音时长
        // （adaptiveXfadeDurInner）用**同一个口径**，否则监控提前量与实际混音对不上：
        //   触发提前 5s、实际混音 8s → 前 5s 正常混，尾部被 clamp 削短 → 听起来"拖到结尾才切"；
        //   触发提前 2s、实际混音 8s → 过渡刚开一半就到歌尾 → 听起来"有时候都结束了"。
        //
        // 旧代码的坑：这里 `if (userDur > 0) return userDur` 让秒数档**无条件优先**，
        // 而 V8.415 之后 UI 只提供「小节」档、选中小节时会把 duration_ms 归零；
        // 设备上残留的旧 duration_ms（备份恢复/老版本遗留）就会一直盖掉小节设置。
        // adaptiveXfadeDurInner 明确忽略 duration_ms（V8.415 注释），两边口径相反。
        //
        // 现在统一：**bars 优先**（与 UI 契约、与实际混音一致），只有 bars 无效时才回退秒数档。
        val bars = cfg.getInt("crossfade_bars", 4)
        if (bars in 1..32) {
            com.sdw.music.player.BpmKeyCache.init(this)
            val ap = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            val rawBpm = if (ap.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(ap)?.first ?: 0 else 0
            // 用 correctedBpm 与 adaptiveXfadeDurInner 对齐（否则两处算出的拍长不同）
            val aBpm = correctedBpm(rawBpm)
            val bpmOk = aBpm in 40..220
            val beatMs = if (bpmOk) 60000f / aBpm.toFloat() else (60000f / 120f)
            return (bars * 4 * beatMs).toInt().coerceIn(1500, kMaxXfadeMs)
        }
        // bars 无效（<=0 或越界）才回退历史秒数档
        val userDur = cfg.getInt("crossfade_duration_ms", 5000)
        if (userDur > 0) return userDur
        return null
    }

    /**
     * 【V8.413】安全包装：保证交叉淡化时长永远 > 0。
     *
     * 背景：选「小节」模式时 UI 会把 crossfade_duration_ms 归零(V8.413 UI 修复)，
     * 而 adaptiveXfadeDurInner 里有多条 `return userDur` 分支，在「A 轨没测出 BPM」时
     * 会返回 0 → 过渡时长 0 = 瞬切/不淡入（实测 XFADE-POS dur=0ms、prefill cfDur=0）。
     * 这里兜一个非零下限：有小节设置就按假定 120BPM 折算，否则用 8s。
     */
    private fun adaptiveXfadeDur(preSong: Song?): Int {
        val v = runCatching { adaptiveXfadeDurInner(preSong) }.getOrDefault(0)
        if (v > 0) return v
        val bars = runCatching {
            getSharedPreferences("settings", MODE_PRIVATE).getInt("crossfade_bars", 4)
        }.getOrDefault(4)
        val fb = if (bars in 1..32) (bars * 4 * (60000f / 120f)).toInt().coerceIn(1500, kMaxXfadeMs) else 8000
        Log.w(TAG, "Crossfade: dur computed as 0 -> fallback ${fb}ms (bars=$bars, A BPM unknown)")
        return fb
    }

    /**
     * 【v8.166 重写】混音时长：以「A 轨的小节」为单位，并受拍点漂移上限约束。
     *
     * 旧实现的问题（实测日志：adaptive mix 15000ms->3000ms）：
     *   BPM 差 ≤2 就无条件把用户设的 15s 压成 2/3/4s，UI 上的时长形同虚设；
     *   且 3s @107BPM = 1.34 小节 —— 连小节线都没落在整数倍上，谈不上 DJ 式混音。
     *
     * 新规则：
     *   1) 用户按小节设了长度（bars>0）→ 直接用 bars×4 拍；
     *   2) 否则按秒数档，但向下取整到整小节（保证落点在小节线上）；
     *   3) 【M2 起已解决】过去没有变速同步（无 time-stretch/keylock），两轨 BPM 差 d 时
     *      整段会错开 T·d/60 拍，要求全程错开 ≤ 1/4 拍 → T ≤ 15/d 秒。该上限同样取整到小节。
     *      d=1 → 15s 内都安全（旧实现却只给 3s）；d=2 → 7.5s；d=5 → 3s。
     *      开了「节拍同步」后 WSOLA 会把 incoming 拉到 A 轨速度，这条时长上限不再是唯一手段。
     */
    private fun adaptiveXfadeDurInner(preSong: Song?): Int {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        val userDur = cfg.getInt("crossfade_duration_ms", 5000)
        // 【V8.152】按小节定义过渡长度（DJ 式 2/4/8 小节混音）
        val bars = cfg.getInt("crossfade_bars", 4)
        com.sdw.music.player.BpmKeyCache.init(this)
        val ap = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val aBpm = correctedBpm(if (ap.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(ap)?.first ?: 0 else 0)
        // 【V8.415】小节是唯一模式：秒数档 5/10/15s 已从设置 UI 删除。
        //   bars>0 时按小节折算，并**忽略**设备上可能残留的 duration_ms
        //   （老版本/备份恢复留下的值，否则又会盖掉小节 = V8.413 的老毛病）。
        //   A 轨无 BPM 时按假定 120BPM 折算，保证仍是合理的非零值。
        val bpmOk = aBpm in 40..220
        val beatMs = if (bpmOk) 60000f / aBpm.toFloat() else (60000f / 120f)
        val barMs = beatMs * 4f
        if (bars in 1..32) {
            val ms = (bars * 4 * beatMs).toInt().coerceIn(1500, kMaxXfadeMs)
            Log.i(TAG, "Crossfade: bars=$bars -> ${ms}ms (A BPM=${if (bpmOk) aBpm else "unknown, assume 120"})")
            return ms
        }
        // 仅兼容 bars<=0 的历史配置（老设备的秒数档）
        if (!bpmOk) return userDur
        if (userDur > 0) {
            Log.i(TAG, "Crossfade: dur=${userDur}ms (legacy seconds, bars<=0)")
            return userDur
        }
        if (!isShuffleMode) return userDur
        // 【V8.194】同样走归一；纯随机不搞 BPM 短混
        if (normalizeShuffleMode(cfg.getString("shuffle_mode", null)) != "bpm") return userDur
        val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val bBpm = correctedBpm(if (bPath.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(bPath)?.first ?: 0 else 0)
        if (bBpm !in 40..220) return userDur
        val diff = kotlin.math.abs(aBpm - bBpm)
        val driftCapMs = if (diff <= 0) userDur else (15000 / diff)
        val target = minOf(userDur, driftCapMs)
        val nBars = (target / barMs).toInt().coerceIn(1, 16)
        val ms = (nBars * barMs).toInt().coerceIn(1500, kMaxXfadeMs)
        Log.i(TAG, "Crossfade: bar-aligned ${ms}ms = $nBars bars @A=$aBpm (user=${userDur}, driftCap=${driftCapMs}, diff=$diff)")
        return ms
    }

    /**
     * 【V8.222】Rubber Band 高质量 keylock（唯一存活的变速路径）。
     * WSOLA 版"节拍同步"已按用户要求移除；只有 crossfade_rubberband 开着才计算
     * 拉伸比率，Rubber Band 引擎（±8% 内几乎无痕）负责把 B 轨 tempo 拉齐到 A 轨。
     * 两道保护沿用旧版：半速/倍速折到 [0.75,1.5)，幅度上限 ±maxPct%（默认 8）。
     */
    /** 【V8.243】beatmatch 下发四元组：拉伸比 + A/B 轨 BPM（连续相位锁定用） + 相位锁定开关。 */
    private data class BeatmatchParams(val ratio: Float, val aBpm: Float, val bBpm: Float, val phaseLock: Boolean,
                                    /** 【V8.423】双侧 BPM 置信 → 武装变速引擎+PLL（djay 式：过渡期始终跟速，落点前回原速）。
                                     *  true 时即便 ratio≈1.0 也跟速，纠正 BPM 检测残差造成的漂移（否则近拍 pair 不锁相→分裂感）。 */
                                    val engage: Boolean = false)

    // 【V8.243】BPM 半/倍速歧义校验：检测偶发的 70 vs 140、100 vs 200 误判。
    // 做法：以「曲库中位数 BPM」为锚，把每个 BPM 拉回与中位数同一倍频程
    // （落在 [m/1.5, m*1.5]），消除 2 倍误判。修正只在运行时生效，不污染缓存。
    @Volatile private var bpmMedianCache: Int = -1
    private fun getBpmMedian(): Int {
        if (bpmMedianCache > 0) return bpmMedianCache
        com.sdw.music.player.BpmKeyCache.init(this)
        val list = com.sdw.music.player.BpmKeyCache.allBpms()
        if (list.isNotEmpty()) {
            val sorted = list.sorted()
            bpmMedianCache = sorted[sorted.size / 2]
        }
        return bpmMedianCache
    }
    private fun correctedBpm(raw: Int): Int {
        if (raw !in 40..220) return raw
        val m = getBpmMedian()
        if (m <= 0) return raw
        var v = raw
        while (v > m * 1.5 && v > 1) v /= 2
        while (v < m / 1.5 && v < 500) v *= 2
        return v
    }

    /** 【V8.304】浮点版倍频/半频校正：判据与 correctedBpm 完全一致，只是全程不取整。
     *  整数版会把 120.4 折成 120、把 120.6 折成 121 —— 而对拍要的正是这个小数部分：
     *  1 BPM @120 = 0.83% 速度差，落在「鼓点对不齐」的量级。 */
    private fun correctedBpmF(raw: Float): Float {
        if (raw < 40f || raw > 220f) return raw
        val m = getBpmMedian()
        if (m <= 0) return raw
        var v = raw
        while (v > m * 1.5f && v > 1f) v /= 2f
        while (v < m / 1.5f && v < 500f) v *= 2f
        return v
    }

    /** 【V8.304】取某曲「对拍用」tempo：优先缓存浮点 bpmF（V8.304 引擎产出），
     *  旧条目无浮点值时回退整数 bpm（并做倍频校正），保证全库重扫前也不至于退化为 0。 */
    private fun beatTempoOf(path: String): Float {
        if (path.isBlank()) return 0f
        com.sdw.music.player.BpmKeyCache.init(this)
        val bpmF = com.sdw.music.player.BpmKeyCache.getBpmFloat(path)
        if (bpmF > 0f) return correctedBpmF(bpmF)
        val raw = com.sdw.music.player.BpmKeyCache.get(path)?.first ?: 0
        return correctedBpm(raw).toFloat()
    }

    /** 【V8.243】取某曲 Integrated LUFS 给 native 做两轨响度预匹配（无则 -100 表示无效）。 */
    private fun songLufs(song: Song?): Float {
        val p = song?.let { it.filePath.ifEmpty { it.path } } ?: return -100f
        if (p.isBlank()) return -100f
        val l = com.sdw.music.player.core.audio.dsp.LoudnessCache.get(p)?.lufs
        return if (l != null && l > -60f) l else -100f
    }

    private fun beatmatchParams(preSong: Song?): BeatmatchParams {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        // 【V8.360】DJ master switch gates the whole time-stretch path, not just the
        // engine. applyBeatmatchEngine() only decides WHICH engine; the actual ratio
        // handed to native comes from here, and this function used to read
        // crossfade_rubberband directly -- so with DJ off it still computed a
        // BPM-match ratio and pulled the incoming track's tempo. Ratio 1.0 = truly
        // no speed change, which is what a plain crossfade must be.
        val djCfg = activeDjAudioConfig()
        // 【V8.365】Phase lock is a THIRD time-stretch control point, independent of both
        // the engine and the ratio. V8.360 gated the first two and missed this one, so
        // with DJ off the log showed `Beatmatch set: enabled=0 (phaselock=1)` -- the phase
        // loop kept nudging the rate (PL tuning: gainLo=0.09 gainHi=0.30) and left ~0.69%
        // at landing. That residual is what the user hears as the "click" right after the
        // fade: the rate snaps from 1.0069 to 1.0 in one frame.
        // Same rule as everywhere else: with DJ off, no time-stretch control may be active.
        val phaseLock = djCfg.phaselock
        // 【V8.427】旧注释里的「选 Tempo Blend 就绕过总开关强制算比率」早已在 V8.360 重构时
        // 被架空（forcedByTemplate 声明后再无使用），V8.426/427 的解耦把它彻底取代：
        // 模板不再决定是否变速，变速地基由 crossfade_rubberband + DJ 总开关统一控制，
        // 是否「渐变着拉」由 crossfade_tempo_ramp 控制，两者都对全部模板生效。
        if (!djCfg.djMode) return BeatmatchParams(1.0f, 0f, 0f, phaseLock, false)   // 【V8.360】plain crossfade: no time stretch at all
        // 【Phase1】Tempo adjust 四档：off = 完全不对拍（退回普通交叉淡化）；其余档位走常规对拍地基。
        if (cfg.getString("crossfade_tempo_adjust", "tempoblend") == "off")
            return BeatmatchParams(1.0f, 0f, 0f, phaseLock, false)
        val maxPct = cfg.getInt("crossfade_rubberband_max", 14)
        if (maxPct <= 0) return BeatmatchParams(1.0f, 0f, 0f, phaseLock, false)
        com.sdw.music.player.BpmKeyCache.init(this)
        val ap = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        // 【V8.304】走 beatTempoOf：优先浮点 bpmF，全库重扫前自动回退整数 BPM。
        val aBpm = beatTempoOf(ap)
        val bp = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        val bBpm = beatTempoOf(bp)
        if (aBpm !in 40f..220f || bBpm !in 40f..220f) return BeatmatchParams(1.0f, aBpm, bBpm, phaseLock, false)
        // 【V8.419】置信度门控：BPM 测错时算出的 ratio 会把两轨拉到「共同错误」的速度，
        // 比不对齐更伤（用户听感是"越混越乱"）。任一侧置信度低于阈值 → 本轮不做对拍，
        // 退回普通交叉淡化。未知（-1）也按不信任处理：BPM 都没有，对拍无从谈起。
        val minConf = cfg.getInt("crossfade_bpm_min_conf", 30)   // 标度 0~10，×10 存
        if (minConf > 0) {
            val aConf = com.sdw.music.player.BpmKeyCache.confOf(ap)
            val bConf = com.sdw.music.player.BpmKeyCache.confOf(bp)
            val thr = minConf / 10f
            if (aConf < thr || bConf < thr) {
                Log.i(TAG, "beatmatch SKIP: low conf (A=$aConf B=$bConf < $thr) -> plain crossfade")
                return BeatmatchParams(1.0f, aBpm, bBpm, false, false)
            }
        }
        // 【V8.490】BPM 差超变速钳位窗：在「变速地基」这一层直接关掉拉伸，退回干净交叉淡化。
        // 根因：原 bpmFallActive() 的"超窗降级"只作用在旧 DJ 效果总线上，而模板接管时该总线被旁路
        // （见 V8.281 注释 "模板接管时效果号强制 0、总线旁路"），对模板 / Tempo Blend 路径完全无效。
        // 于是速度融合下大 BPM 差仍进引擎做钳位拉伸（beatmatchParams 这里 engage 恒 true），
        // 残留速度差 + 拉伸伪影表现为白噪音（用户实听"速度融合下又出现白噪音"）。
        // 这里在 beatmatch 下发处拦截：超窗就不 engage，引擎不拉伸 → 无白噪音。与 V8.246 既定
        // 方案一致（超窗不强行对拍），只是这次真正落到了变速路径上。
        // （bpmFallActive 已处理 rubberband 关 / dj 关 / effect=off 的情况 → 那时本就不会进这里。）
        if (bpmFallActive(preSong)) {
            Log.i(TAG, "beatmatch SKIP: BPM diff beyond ±${maxPct}% window -> plain crossfade (no keylock stretch)")
            return BeatmatchParams(1.0f, aBpm, bBpm, false, false)
        }
        var ratio = bBpm / aBpm
        while (ratio > 1.5f) ratio /= 2f
        while (ratio < 0.75f) ratio *= 2f
        val limit = 1.0f + maxPct / 100f
        val clamped = ratio.coerceIn(1f / limit, limit)
        if (clamped > 1.002f || clamped < 0.998f) {
            Log.i(TAG, "RubberBand keylock: A=%.2f B=%.2f -> ratio=%.5f (max=±$maxPct%%)".format(aBpm, bBpm, clamped))
        }
        return BeatmatchParams(clamped, aBpm, bBpm, phaseLock, true)
    }

    /** 【V8.222】变速引擎按 pref 下发（Rubber Band 高质量档默认关）。 */
    fun applyBeatmatchEngine() {
        // 【V8.360】crossfade_rubberband (and, since V8.427, crossfade_tempo_ramp) are DJ
        // capabilities: they must honour the DJ master switch. Both come from
        // activeDjAudioConfig(), which returns NONE (everything false) when djMode == false,
        // so with DJ off no time-stretch control is handed to native at all.
        // The settings UI already hides this row inside if (djEnabled), but the
        // service read the pref directly, so with DJ off the engine kept time-stretching
        // B to match A. That is the "plain crossfade still changes speed" report:
        // the fades were equal-power (tpl=0) yet the BEAT of the incoming track was
        // being pulled, which is audible as a speed drift, not as an effect.
        val rb = activeDjAudioConfig().rubberband
        oboeDirectPlayer?.setBeatmatchEngine(rb)
        Log.i(TAG, "BeatmatchEngine ${if (rb) "rubberband" else "off(legacy wsola dormant)"}")
        applyTempoRamp()   // 【V8.427】播放器重建 / 设置变更后重发渐变拉速档
    }

    /**
     * 【V8.427】渐变拉速下发（全模板共用的速度地基）。
     *
     * 以前 native 只有 Tempo Blend（tpl==5）会做「推子推进途中把 A 的速度拉向 B」的渐变，
     * 其余模板起步就锁死目标比率 → 过渡开头速度一次性跳变，听感是「一进来就被掰快/掰慢」。
     * 现在把它提成全局能力：任何模板接地基后才叠自己的音色层。
     *
     * 必须在 `setBeatmatch()` / `startCrossfade()` 之前下发（native 在 arming 的那一刻读）。
     * DJ 总开关关闭时恒为 false —— 与 V8.360 同一原则：普通交叉淡化不做任何变速控制。
     */
    private fun applyTempoRamp(player: OboeDirectPlayer? = oboeDirectPlayer) {
        val on = activeDjAudioConfig().tempoRamp
        player?.setTempoRamp(on)
        Log.i(TAG, "TempoRamp ${if (on) "gradual (all templates)" else "off (lock ratio at start)"}")
    }

    // 【V8.145】DJ 切歌效果：解析 settings → native 效果编号 + 节拍(ms)。
    // 独立开关 crossfade_dj_enabled；关闭 → 0（旧等功率行为）。random 每次切歌重随。
    // 效果号：0=off 1=filter 2=eq 3=echo 4=dissolve 5=tremolo 6=lunar_echo
    /** 【V8.246】DJ 效果的节拍同步参数（echo/lunar 延迟按 1/2 拍）：优先 A 轨 BPM，回退 B 轨；无则 0。 */
    private fun djBeatMsFor(preSong: Song?): Float {
        var beatMs = 0f
        try {
            com.sdw.music.player.BpmKeyCache.init(this)
            var bpm = 0
            val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            if (aPath.isNotBlank()) bpm = com.sdw.music.player.BpmKeyCache.get(aPath)?.first ?: 0
            if (bpm !in 40..220) {
                val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
                if (bPath.isNotBlank()) bpm = com.sdw.music.player.BpmKeyCache.get(bPath)?.first ?: 0
            }
            if (bpm in 40..220) beatMs = 60000f / bpm.toFloat()
        } catch (_: Throwable) {}
        return beatMs
    }

    /** 【V8.246】取歌的校正 BPM（BpmKeyCache 优先，回退 tag；再过半/倍速校正）。 */
    private fun correctedBpmOf(song: Song?): Int {
        song ?: return 0
        val path = song.filePath.ifEmpty { song.path }
        val raw = if (path.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(path)?.first ?: song.bpm else song.bpm
        return correctedBpm(raw)
    }

    /**
     * 【V8.246】BPM 差降级判定（djay Automix 思路）：两轨校正 BPM 比值超出 ±8% 变速窗时
     * 拍点无法对齐（变速音质也在窗缘劣化），硬 blend 等于拍点打架 → 应改用 echo-out 短过渡。
     * 仅在同时满足时接管：① rubberband keylock 开（变速窗才有意义）；
     * ② 用户未自选 DJ 效果（必须 dj 开且停在默认 dissolve）——用户关掉 DJ 效果 = 显式
     * 「不要任何额外效果」，切到其它效果 = 显式要那个效果，两种都尊重，不接管。
     */
    private fun bpmFallActive(preSong: Song?): Boolean {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        if (!cfg.getBoolean("crossfade_rubberband", true)) return false
        val djEnabled = cfg.getBoolean("crossfade_dj_enabled", true)
        val effectName = cfg.getString("crossfade_dj_effect", "random") ?: "random"
        if (!djEnabled) return false
        // 【V8.489】原只在默认 dissolve 下触发 BPM 降级；用户一旦选了 Tempo Blend / echo /
        // phrase 等模板，effectName != "dissolve" 直接 return false → 降级不触发 →
        // 超出 ±${maxPct}% 钳位窗时 RubberBand 拉不满、落点残留速率差 → 白噪音
        // （用户实测"速度融合下又出现白噪音"）。降级只决定"是否还做变速对拍"，与所选模板无关：
        // 只要 rubberband 开、两轨都有 BPM、超出钳位窗，就改 echo-out 短过渡避免噪音
        // （V8.246 既定方案）。仅当用户显式关掉 DJ 效果（off）时不强制。
        if (effectName == "off") return false
        val ba = correctedBpmOf(currentSong)
        val bb = correctedBpmOf(preSong)
        if (ba !in 40..220 || bb !in 40..220) return false
        val ratio = bb.toFloat() / ba.toFloat()
        // 【V8.419】降级窗必须跟着变速钳位走：钳位放宽到 ±14% 后还按 ±8% 判降级，
        // 会让 10% 差的曲子「按 14% 拉得动」却又被打回 echo-out —— 自相矛盾。
        // 【V8.489】本读取受 crossfade_dj_enabled 门控（函数入口 !djEnabled 早退），plain 模式不会到达此处，速度/音高零影响。
        val maxPct = cfg.getInt("crossfade_rubberband_max", 14).coerceIn(1, 40)
        val lim = 1.0f + maxPct / 100f
        val out = ratio < 1f / lim || ratio > lim
        if (out) Log.i(TAG, "Crossfade: BPM diff beyond ±$maxPct% window (a=$ba b=$bb) -> echo-out fallback")
        return out
    }

    /**
     * 【V8.246】效果解析（自动过渡专用，带 BPM 差降级）：
     * 返回 (效果类型, beatMs, 是否降级)。降级生效时效果改为 echo(3)。
     */
    private fun resolveDjEffectAuto(preSong: Song?): Triple<Int, Float, Boolean> {
        val base = resolveDjEffect(preSong)
        return if (bpmFallActive(preSong)) {
            Triple(3, base.second.takeIf { it > 0f } ?: djBeatMsFor(preSong), true)
        } else Triple(base.first, base.second, false)
    }

    // ============================================================================
    // 【V8.281】djay Crossfader FX 模板（轨级、按推子位置调制）
    // 与旧的「DJ 效果 + Live FX 总线」互斥：模板接管时效果号强制 0、总线旁路，
    // 否则同一段样本会被两套效果各处理一遍（滤波叠滤波、回声叠回声 → 糊成一团）。
    //   0 关   1 Filter Fade   2 Echo Fade   3 Brake Drop   4 Dissolve Wash   5 每次随机
    // ============================================================================
    private fun xfadeTemplateName(): String {
        val raw = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("crossfade_dj_template", "phrase") ?: "phrase"
        // 【V8.414】滤波交接/变调下滑/噪声升扫实测是白噪音，已从设置与自动候选池移除。
        // 这里再兜一层：万一设备上存着旧值（老版本/备份恢复），也强制回落到安全的 phrase，
        // 避免"选项看不见但还在生效"。用户当前值（phrase/echo/…）不受影响。
        val safe = if (raw == "filter" || raw == "pitch" || raw == "riser") "phrase" else raw
        // 【Phase1】Tempo adjust 四档之 "Sync+Tempo Blend"：强制走 Tempo Blend 过渡（前提：模板本身未关 off）。
        if (safe != "off" &&
            getSharedPreferences("settings", MODE_PRIVATE).getString("crossfade_tempo_adjust", "tempoblend") == "tempoblend")
            return "tempo"
        return safe
    }

    /** 模板是否接管过渡（决定旧 DJ 效果 / 总线要不要让位）。 */
    private fun xfadeTemplateActive(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("crossfade_dj_enabled", true) && xfadeTemplateName() != "off"

    /** 解析成 native 模板号；"random" 每次过渡重掷一次（与旧 random 效果同口径）。 */
    /**
     * @param preSong 候选曲（B）。仅 "random" 档用它做智能判断；
     *               其余档位与判定逻辑不依赖它，故为可选。
     */
    private fun resolveXfadeTemplate(preSong: Song? = null): Int {
        if (!xfadeTemplateActive()) return 0
        return when (xfadeTemplateName()) {
            "filter" -> 1
            "echo" -> 2
            "brake" -> 3
            "dissolve" -> 4
            "tempo" -> 5        // 【V8.284】djay Tempo Blend：边推边对拍
            "roll" -> 6         // 【V8.286】Beat Roll / Gate：1/2 或 1/4 拍切片循环
            "phrase" -> 8       // 【V8.287】Phrase 分段式 FX：按小节头离散分段（djay 8 小节方案）
            "pitch" -> 9        // 【V8.301】Pitch Drop：变调不变速（Rubber Band 音高下扫）
            "eq" -> 10          // 【V8.421】EQ 三段交叉：低频中点交换，中高频等功率（复用 threeBand 底层）
            "stem" -> 11        // 【Phase1】Neural Mix 声部交叉：显式分轨过渡（native 基底层 Dissolve + 分轨交叉融合 + 跨轨 ducking）
            "riser" -> 7        // 【V8.310】Riser **重做**：滤波扫频（不再纯白噪音，V8.297 删过）
            // 【V8.346】random no longer means "uniform over all templates" -- that produced
            // mismatches the user described as "effects paired randomly, some songs don't fit"
            // (e.g. Pitch Drop at a 20% BPM gap sounds like a broken tape; Beat Roll on a
            // 70 BPM track shreds the groove). Delegate to the picker, which hard-excludes
            // mismatches and scores the rest on BPM gap / tempo / loudness / key.
            "random" -> pickTemplateSmart(preSong)
            else -> 0
        }
    }

    // ---- 【V8.310】DJ 扩展配置（Keylock / Pitch 档位 / Phrase 小节 / Auto-solo）----

    /** Keylock：变速时锁住音高（变速度不变调）。需 Rubber Band 引擎（native 会自动强制）。 */
    private fun djKeyLock(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("crossfade_keylock", true)

    /** Pitch/Bend 量化档位（半音）。0 = 关闭量化走连续扫；-12..+12 为常用档。 */
    private fun djPitchSemi(): Int {
        val raw = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("crossfade_pitch_semi", 0)
        return raw.coerceIn(-24, 24)
    }

    /** Phrase 分段方案小节数：4/8/16/32。 */
    private fun djPhraseBars(): Int {
        val raw = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("crossfade_phrase_bars", 4)
        return if (raw == 4 || raw == 8 || raw == 16 || raw == 32) raw else 4
    }

    /** Auto-solo 频段：0=关 1=低 2=中 3=高。 */
    private fun djSoloBand(): Int {
        val raw = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("crossfade_solo_band", "off") ?: "off"
        return when (raw) { "low" -> 1; "mid" -> 2; "high" -> 3; else -> 0 }
    }

    /**
     * 【V8.281】过渡开始前下发模板。echoSamples 取 3/4 拍（djay Echo Fade 的抽头间距，
     * 落在拍点上才有律动、不会像随机延迟）；拿不到 BPM 或采样率时给 0，native 回落到默认 3/8 拍。
     */
    private fun applyXfadeTemplate(preSong: Song?) {
        val p = oboeDirectPlayer ?: return
        val tpl = resolveXfadeTemplate(preSong)
        // 【Phase1】显式 Neural Mix(stem=11)：native 无独立 11 号模板，基底层回落到 Dissolve(4)；
        // 真正的"声部交叉"由 native 分轨交叉融合 + 跨轨 ducking（g_stemMode 时）承担。
        val nativeTpl = if (tpl == 11) 4 else tpl
        // 【V8.310】DJ 扩展配置随模板一起下发（都在过渡开始前定好，实时线程只读）。
        p.setDjExtras(djKeyLock(), djPitchSemi(), djPhraseBars(), djSoloBand())
        // 【V8.500 ④】自动 Camelot 调性匹配：算出入轨 B 相对出轨 A 的最小半音平移并下发
        p.setKeyMatchSemi(computeKeyMatchSemi(preSong))
        // 【V8.322】PLL 整定参数：跟模板一起下发，每次过渡前刷新（可随设置改动即时生效）。
        applyPlTuning(p)
        // 【V8.416】排查开关：settings 里 crossfade_diag_verbose=true 时打开 native 的逐帧诊断
        // （XF-CLICK 落点波形跳变 / XFADE-POST 落点后缓冲水位）。默认关；只在抓"落点咔一下 /
        // 卡顿 / B 接上跳一下"这类听感问题时临时打开（adb 改设置即可，无需重编译）。
        p.setDiagVerbose(
            getSharedPreferences("settings", MODE_PRIVATE).getBoolean("crossfade_diag_verbose", false)
        )
        if (tpl <= 0) {
            p.setXfadeTemplate(0, 0)
            p.setStemFxTarget(0, 0)      // 模板关 → 分轨音效路由一并撤掉
            return
        }
        val beatMs = djBeatMsFor(preSong)
        val sr = p.getSampleRate()
        // 【V8.286】echo 长度按模板语义算：roll = 1/2 或 1/4 拍切片（crossfade_roll_rate），
        // riser 用不到（传 0），其余滤/回声类沿用 3/4 拍抽头间距。
        val echo = when (tpl) {
            6 -> {
                val rate = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("crossfade_roll_rate", "half") ?: "half"
                val beats = if (rate == "quarter") 0.25f else 0.5f
                if (beatMs > 0f && sr > 0) (beats * beatMs * 0.001f * sr).toInt() else 0
            }
            7 -> 0
            else -> if (beatMs > 0f && sr > 0) (0.75f * beatMs * 0.001f * sr).toInt() else 0
        }
        p.setXfadeTemplate(nativeTpl, echo)
        // 【V8.285】分轨音效路由：效果类型跟着模板走（滤波类模板 → HPF，回声类 → Echo）。
        // Brake / Tempo 不是滤波也不是回声，挂到分轨上没有意义 → 传 0。
        // V8.349: STEM ROUNDS GET AN EFFECT TOO.
        // native bypasses the whole track-level template when stemMode is on
        // (`if (tpl > 0 && c < 8 && !stemMode)`, cpp ~13505) so that the filter/echo is
        // not run over stems that are already processed individually.
        // Consequence before this change: whenever the stem bus was active the round had
        // NO effect unless the user had explicitly picked a stem-fx target in settings
        // (default "off") -- that is the "some rounds have no transition effect" report.
        //
        // Fix: when the user has not chosen a target, derive one from the template so the
        // round still does something musical. This mirrors djay, whose Neural Mix
        // transition FX are routed onto a stem (drums cut away, vocal echo, ...).
        // An explicit user choice still always wins.
        val fxTarget = stemFxTarget(tpl)
        val userMode = stemFxMode()   // 【V8.30x】-1 = 跟模板推导；否则用户直选的分轨效果类型
        // V8.349: map EVERY template family onto a stem effect, not just filter/echo.
        // native's applyStemFx implements mode1=HPF sweep, mode2=Echo, mode3=LPF,
        // mode4=boost. Previously brake/tempo/roll/phrase/pitch fell into `else -> 0`,
        // which meant: stem round + those templates = no effect at all. The closest
        // musical match is HPF on drums (cut the old kit out, let the new one in) --
        // which is also what those templates do conceptually.
        val fxMode = if (fxTarget == 0) 0 else (if (userMode >= 0) userMode else when (nativeTpl) {
            2 -> 2                // Echo Fade -> vocal echo
            else -> 1              // filter/dissolve/phrase/brake/tempo/roll/pitch -> HPF sweep
        })
        p.setStemFxTarget(fxTarget, fxMode)
        // 【V8.308→V8.319】"模板被分轨旁路"的警告**移到了 setupStemBusForXfade() 之后**
        // （logTemplateBypassByStem）。原来在这里用 p.stemModeOn 判断，读到的是**上一轮**的
        // 分轨状态 —— 首次开启分轨的那一轮判断不到，模板"看起来在生效"实际被 native 跳过。
        Log.i(TAG, "Crossfade: djay template tpl=$tpl echo=${echo}smp (beat=${beatMs}ms sr=$sr)" +
                (if (fxTarget > 0) " stemFx target=$fxTarget mode=$fxMode" else ""))
    }

    /**
     * V8.285 stem-fx route target: 0=off 1=vocal 2=drums 3=bass 4=other.
     *
     * V8.349: takes the chosen template so that stem rounds still get an effect.
     * native skips the track-level template when stemMode is on, so without a target
     * the round is silent of FX. An explicit user choice still wins; otherwise derive a
     * sensible target from the template family:
     *   filter / dissolve / phrase / brake -> drums (cut the old kit away, classic mashup)
     *   echo -> vocal (the djay "vocal echo out" trick)
     *   tempo / roll / pitch -> drums as well (rhythmic material responds to gating)
     */
    private fun stemFxTarget(tpl: Int = 0): Int {
        val raw = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("crossfade_stem_fx_target", "vocal") ?: "vocal"
        when (raw) {
            "vocal" -> return 1
            "drums" -> return 2
            // V8.314: bass / other were split (native changed too); the old "harmonic"
            // value handled two stems at once and mode4's 1.8 gain clipped -> "no effect".
            "bass" -> return 3
            "other" -> return 4
            // legacy value from older prefs
            "harmonic" -> return 3
            "off" -> return 0
        }
        // auto (default): derive from the template.
        if (tpl <= 0) return 0
        return when (tpl) {
            2 -> 1                // Echo Fade -> vocal echo
            // 【V8.490】速度融合在分轨模式下也要"听得出在过渡"：给鼓 HPF 扫频（旧鼓组淡出、
            // 新鼓组进来），与 djay Neural Mix 过渡同思路。原 5->0 是 V8.425 怕弄脏鼓组，
            // 但用户明确要"分轨下也有各种效果"，且 HPF 扫在交接那几秒本就是 djay 的惯用手法。
            // 用户在「分轨音效路由」显式选了目标仍照常优先（上面已 return）。
            5 -> 2
            11 -> 2               // 【Phase1】Neural Mix：鼓 HPF 扫频同 Tempo Blend 思路
            else -> 2              // filter/dissolve/phrase/brake/roll/pitch -> drums
        }
    }

    /** 【V8.30x】分轨效果类型：auto=跟模板推导；hpf/echo/lpf/gain=用户直选（-1 表 auto）。 */
    private fun stemFxMode(): Int = when (
        getSharedPreferences("settings", MODE_PRIVATE)
            .getString("crossfade_stem_fx_mode", "echo") ?: "echo"
    ) {
        "hpf" -> 1
        "echo" -> 2
        "lpf" -> 3
        "gain" -> 4
        else -> -1
    }

    /**
     * V8.346: smart template selection (replaces the uniform-random "random" option).
     * Gathers A/B context, delegates the decision to TemplatePicker, and always logs the
     * inputs plus the chosen template so mismatches can be diagnosed from logcat.
     */
    private fun pickTemplateSmart(preSong: Song?): Int {
        return try {
            com.sdw.music.player.BpmKeyCache.init(this)

            val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            val bPath = preSong?.let { it.filePath.ifEmpty { it.path } } ?: ""

            val aEntry = aPath.takeIf { it.isNotBlank() }?.let {
                com.sdw.music.player.BpmKeyCache.get(it)
            }
            val bEntry = bPath.takeIf { it.isNotBlank() }?.let {
                com.sdw.music.player.BpmKeyCache.get(it)
            }

            val aBpm = correctedBpm(aEntry?.first ?: currentSong?.bpm ?: 0)
            val bBpm = correctedBpm(bEntry?.first ?: preSong?.bpm ?: 0)
            val aKey = aEntry?.second?.takeIf { it.isNotBlank() } ?: currentSong?.key.orEmpty()
            val bKey = bEntry?.second?.takeIf { it.isNotBlank() } ?: preSong?.key.orEmpty()

            val aLufs = aPath.takeIf { it.isNotBlank() }
                ?.let { com.sdw.music.player.core.audio.dsp.LoudnessCache.get(it)?.lufs }
            val bLufs = bPath.takeIf { it.isNotBlank() }
                ?.let { com.sdw.music.player.core.audio.dsp.LoudnessCache.get(it)?.lufs }

            // Same key or adjacent Camelot wheel positions (keyScore 0/1) counts as "related".
            val sameKey = aKey.isNotBlank() && bKey.isNotBlank() &&
                (keyScore(aKey, bKey) <= 1)

            val ctx = TemplatePicker.Context(
                aBpm = aBpm,
                bBpm = bBpm,
                aLufs = aLufs,
                bLufs = bLufs,
                aDurMs = songDurationMsOf(currentSong),
                bDurMs = songDurationMsOf(preSong),
                xfadeMs = adaptiveXfadeDur(preSong),
                sameKey = sameKey,
                // 【Phase1】分轨可用时让自动路由把 Neural Mix 列为高优先级子模板：
                // 用上一轮真实分轨状态（isStemModeActive）作为本轮回选的代理信号——
                // Automix 连续过渡的曲库分轨可用性通常稳定，一轮滞后不影响决策质量，
                // 且即便本轮回选了 stem 而本回合分轨未就绪，也会优雅回落到基底层 Dissolve。
                stemActive = isStemModeActive()
            )
            TemplatePicker.pick(ctx)
        } catch (t: Throwable) {
            // 【V8.416】兜底从 T_FILTER(1) 改为 T_DISSOLVE(4)：滤波(Filter Fade)正是用户点名
            // 删除的"白噪音"类效果——异常兜底绝不能成为它最后一条复活通道。溶解最无痕。
            Log.w(TAG, "pickTemplateSmart failed (${t.message}) -> fallback to Dissolve")
            TemplatePicker.T_DISSOLVE
        }
    }

    private fun resolveDjEffect(preSong: Song?): Pair<Int, Float> {
        // 【V8.281】模板接管 → 旧效果让位（0 = 等功率混音 + 轨级模板）
        if (xfadeTemplateActive()) return 0 to 0f
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        if (!cfg.getBoolean("crossfade_dj_enabled", true)) return 0 to 0f
        val name = cfg.getString("crossfade_dj_effect", "random") ?: "random"
        val code = when (name) {
            "filter" -> 1
            "eq" -> 2
            "echo" -> 3
            "dissolve" -> 4
            "tremolo" -> 5
            "lunar_echo" -> 6
            // 【V8.156】riser(上升音)已删；存量选过 riser 的落 else → dissolve(4)
            "random" -> (1..6).random()   // 【V8.156】效果集 1..6（Hydrant/riser 均已删）
            else -> 4
        }
        // 节拍同步（echo/lunar 延迟按 1/2 拍）：优先 A 轨 BPM，回退 B 轨；无则 0（native 用默认）
        val beatMs = djBeatMsFor(preSong)
        return code to beatMs
    }

    /** 【V8.150 Live FX 总线】把效果设置下发到当前 Oboe 播放器：开关 + 类型 + 深度，可随时切换、即时生效。
     *  dissolve(4)/关闭 时 native 总线整段旁路，与旧行为逐样本一致（零回归）。 */
    fun applyLiveFx() {
        // 【V8.281】djay 模板生效时不挂旧效果/总线（互斥，见 resolveDjEffect 注释）
        if (xfadeTemplateActive()) {
            oboeDirectPlayer?.let { p -> p.setCrossfadeEffect(0, 0f); p.setLiveFx(false, 0, 0f) }
            Log.i(TAG, "LiveFx: bypassed (djay template '${xfadeTemplateName()}' active)")
            return
        }
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = cfg.getBoolean("crossfade_dj_enabled", true)
        val name = cfg.getString("crossfade_dj_effect", "random") ?: "random"
        val depth = cfg.floatValue("crossfade_dj_depth", 1.0f).coerceIn(0f, 1f)
        val type = when (name) {
            "filter" -> 1
            "eq" -> 2
            "echo" -> 3
            "dissolve" -> 4
            "tremolo" -> 5
            "lunar_echo" -> 6
            // 【V8.194 修复】random 每次调用都重掷 → 用户拖一次「深度」滑杆
            // （onValueChangeFinished 会调 applyLiveFx）就会随机换一个效果类型。
            // 改为：同一首歌内保持，切歌（曲目变了）时才重掷 —— 与 resolveDjEffect
            // 「每次切歌重随」的设计一致，但设置面板内调 applyLiveFx 不再改变效果。
            "random" -> nextRandomDjType()
            else -> 0
        }
        var beatMs = 0f
        try {
            com.sdw.music.player.BpmKeyCache.init(this)
            var bpm = 0
            val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
            if (aPath.isNotBlank()) bpm = com.sdw.music.player.BpmKeyCache.get(aPath)?.first ?: 0
            if (bpm in 40..220) beatMs = 60000f / bpm.toFloat()
        } catch (_: Throwable) {}
        if (oboeDirectPlayer == null) {
            // 【V8.194】之前 oboeDirectPlayer 为 null（未在播放 / 服务还没建起 player）时
            // 这里静默什么都不做：用户打开 DJ 开关或拖深度，界面显示已生效但音效没下发。
            Log.w(TAG, "LiveFx: NOT APPLIED — no OboeDirectPlayer yet (playing? ${isPlaying()})")
            DebugLog.add(TAG, "LiveFx 未下发（播放器未就绪）")
        }
        oboeDirectPlayer?.let { p ->
            p.setCrossfadeEffect(type, beatMs)               // 延迟线长度（echo/lunar 拍同步）
            // 【V8.271】「仅过渡时生效」：普通播放期间总线旁路，效果只在过渡窗口挂上
            val busOn = enabled && type != 0 && !liveFxTransitionOnly()
            p.setLiveFx(busOn, type, depth)   // 总线开关 / 类型 / 深度
        }
        Log.i(TAG, "LiveFx: enabled=$enabled type=$type depth=$depth beatMs=$beatMs transitionOnly=${liveFxTransitionOnly()}")
    }

    /** 【V8.271】实时效果「仅过渡时生效」（默认开）：普通播放不再被总线效果染色。 */
    private fun liveFxTransitionOnly(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("crossfade_dj_transition_only", true)

    /**
     * 【V8.271】总线效果开关：true=过渡期间挂上，false=旁路（干净音质）。
     * 与 setCrossfadeEffect 分工：后者决定「过渡用什么效果」，前者决定「平时是否一直挂着」。
     */
    private fun setLiveFxBus(on: Boolean) {
        val cfg = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = cfg.getBoolean("crossfade_dj_enabled", true)
        val p = oboeDirectPlayer
        if (xfadeTemplateActive()) { p?.setLiveFx(false, 0, 0f); return }   // 【V8.281】模板互斥
        if (p == null || !enabled) { p?.setLiveFx(false, 0, 0f); return }
        val name = cfg.getString("crossfade_dj_effect", "random") ?: "random"
        val depth = cfg.floatValue("crossfade_dj_depth", 1.0f).coerceIn(0f, 1f)
        val type = when (name) {
            "filter" -> 1; "eq" -> 2; "echo" -> 3; "dissolve" -> 4
            "tremolo" -> 5; "lunar_echo" -> 6; "random" -> nextRandomDjType(); else -> 0
        }
        if (type == 0) { p.setLiveFx(false, 0, depth); return }
        p.setLiveFx(on, type, depth)
        Log.i(TAG, "LiveFx bus: ${if (on) "ON" else "bypass"} (type=$type depth=$depth)")
    }

    // 【V8.424】过渡采样音效功能已整体删除：applySfxPack / setSfxEnabled /
    // sfxPackPrefsListener / triggerSfx 均已移除，切歌过渡完全静默。

    /**
     * 【V8.244】按能量/调性做「自动流程排序」（Rekordbox Auto-Flow 思路）：
     * 以当前曲为锚，把其后的队列重排成「能量平滑爬升 + 相邻调性调和」的播放链。
     * 正在播放的曲子保持原位，体验无缝；无当前曲时对整个队列排序。
     */
    fun reorderQueueByFlow() {
        try {
            val list = servicePlaylist
            if (list.size <= 2) return
            val cur = currentSong
            val curIdx = if (cur != null) list.indexOfFirst { it.id == cur.id } else -1
            val head = if (curIdx > 0) list.subList(0, curIdx + 1)
            else (if (cur != null) listOf(cur) else emptyList())
            val tail = if (curIdx >= 0) list.subList(curIdx + 1, list.size) else list
            val sortedTail = com.sdw.music.player.core.SongSorter.flowSort(tail)
            val next = head + sortedTail
            setServicePlaylist(next, playlistSource)
            Log.i(TAG, "FlowSort: reordered queue ${next.size} tracks (anchor idx=$curIdx)")
        } catch (e: Throwable) {
            Log.e(TAG, "reorderQueueByFlow: ${e.message}")
        }
    }

    /** 【V8.194】random 效果的随机结果：只在「曲目变了」时重掷，设置面板内反复调用不换效果。 */
    private var djRandomType = 0
    private var djRandomTypeForPath = ""
    private fun nextRandomDjType(): Int {
        val aPath = currentSong?.let { it.filePath.ifEmpty { it.path } } ?: ""
        if (djRandomType in 1..6 && djRandomTypeForPath == aPath) return djRandomType
        djRandomType = (1..6).random()
        djRandomTypeForPath = aPath
        return djRandomType
    }

    /** 【V8.424】用户主动切歌（切歌键 / 媒体键 / 小组件 / 通知）。
     *  fromUser=true 时若在过渡窗口（crossfadeBusy）内，直接忽略并提示，避免连点打断交叉淡化
     *  （djay 同款行为）。内部自动续播一律 fromUser=false，永不被本锁拦截。 */
    fun playNext(fromUser: Boolean = false) {
        if (fromUser && crossfadeBusy) {
            Log.i(TAG, "playNext: ignored (transition in progress, skip locked)")
            toastSkipLocked()
            return
        }
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
        // 【V8.446】手动切歌与自动切歌统一入场点。
        // 只算起播位置，不做过渡 —— 手动 ⏭ 仍然是硬切，只是起点落在进歌点。
        // 小节数（4/8 拍）不参与这里：它是过渡时长，不是进入时间点。
        nextSongEntryMs = if (fromUser) resolveManualEntryMs(songs[nextIndex]) else -1L
        playSong(nextIndex)
    }

    fun playPrevious(fromUser: Boolean = false) {
        if (fromUser && crossfadeBusy) {
            Log.i(TAG, "playPrevious: ignored (transition in progress, skip locked)")
            toastSkipLocked()
            return
        }
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
    /** 【V8.424】过渡期锁定切歌键时，主线程弹一个轻提示（仅反馈，不阻断）。 */
    private fun toastSkipLocked() {
        try {
            handler.post {
                android.widget.Toast.makeText(
                    this@MusicService, R.string.xfade_skip_locked, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Throwable) { /* ignore */ }
    }

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
            if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                scheduleCrossfadeMonitor(300)
            }
        } else {
            xfadeHandler.removeCallbacks(crossfadeMonitor)
            // 【V8.183】关掉开关同样要作废在飞的拍点触发，否则下次再打开时会立刻补放一次
            // 这次被取消的过渡。
            cancelPendingBeatTrigger()
            crossfadeBusy = false
            crossfadeNextIndex = -1
            crossfadeUiInfo = CrossfadeUiInfo()  // 【V8.4】关闭时清 UI 状态
            xfadePendingSong = null
            xfadePendingIndex = -1
            // 【V8.194】预加载态也要一并作废：旧版只清了 busy/nextIndex/pending，
            // 漏了 xfadePreloading/Ready/Path。过渡途中关掉开关、再打开时，monitor 会
            // 拿着上一轮残留的 B 轨（可能已被 stopIncoming 或属于已切走的歌）直接触发。
            xfadePreloading = false
            xfadePreloadReady = false
            xfadePreloadPath = null
            xfadePreloadSong = null
            xfadePreloadIndex = -1
            xfadeBeatPending = false
        }
        Log.i(TAG, "Crossfade: enabled=$enabled")
    }

    fun isCrossfadeEnabled(): Boolean = crossfadeEnabled

    fun setCrossfadeDurationMs(ms: Int) {
        // 【V8.414】0 是「小节模式」哨兵值 —— V8.413 的 UI 在选中 bars>0 时会传 0，
        // 表示"别用秒数档，去按小节折算"。原来的 coerceIn(1000, 15000) 会把 0 夹成 1000，
        // 于是秒数档又反过来盖掉 bars（adaptiveXfadeDur 是 userDur>0 优先），
        // 症状＝「设了 8 小节却 1~2 秒就切」。这里放开下界到 0，保留上界 15s。
        crossfadeDurationMs = ms.coerceIn(0, 15000)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("crossfade_duration_ms", crossfadeDurationMs).apply()
        Log.i(TAG, "Crossfade: duration=${crossfadeDurationMs}ms")
    }

    fun getCrossfadeDurationMs(): Int = crossfadeDurationMs

    /** 【V8.152】过渡长度按小节（2/4/8/16 小节，用 A 轨 BPM 折算）
     *  【V8.194】UI 的按钮组只认这几个值，而这里原来允许 0..32 —— 存了别的值
     *  （比如旧版本落的 6）界面上四个按钮全无选中态。写回前先吸附到最近的可选项。 */
    fun setCrossfadeBars(bars: Int) {
        crossfadeBars = intArrayOf(0, 2, 4, 8, 16).minBy { kotlin.math.abs(it - bars) }
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("crossfade_bars", crossfadeBars).apply()
        Log.i(TAG, "Crossfade: bars=${crossfadeBars}")
    }

    /** 【V8.194】shuffle_mode 三态写回 settings（唯一出处）。
     *  enabled=true 时保留用户当前选的 random/bpm，只是从 off 切回来；写 false 落 "off"，
     *  而不是覆盖成 random —— 否则下次从锁屏/固定卡片把 shuffle 打开会丢掉"匹配 BPM"的选择。 */
    fun setShuffleMode(enabled: Boolean) {
        val cf = getSharedPreferences("settings", MODE_PRIVATE)
        val cur = normalizeShuffleMode(cf.getString("shuffle_mode", null))
        val next = when {
            !enabled -> SHUFFLE_MODE_OFF
            cur != SHUFFLE_MODE_OFF -> cur
            else -> "random"
        }
        // 【V8.194】MusicPlayer 里那份同名 boolean 已无读侧，不再写（写了只会让两处继续打架）
        cf.edit().putString("shuffle_mode", next).apply()
        isShuffleMode = next != SHUFFLE_MODE_OFF
        _shuffleModeFlow.value = isShuffleMode
        android.util.Log.d(TAG, "setShuffleMode: enabled=$enabled -> pref='$next', isShuffleMode=$isShuffleMode")

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

        // [V8.x] Cached album art - no disk I/O on main thread (prev jank in DAC mode)
        val artBitmap = if (SongRepository.hasRealCover(song.albumArtUri)) {
            try { coverCache.get(song.albumArtUri) } catch (_: Exception) { null }
        } else null
        // Cover download is triggered by notifySongChanged on track switch only, not here

val displayArtist = if (song.artist.isBlank() || SongRepository.isPlaceholderArtist(song.artist)) {
            getString(R.string.app_name_moto)
        } else {
            song.artist
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(displayArtist)
            .setSmallIcon(R.drawable.ic_notif_brand)
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
            notificationBuilder.setLargeIcon(artBitmap)
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

        // 【v8.165 省电】清理后台监控线程与 prefs 监听
        try {
            getSharedPreferences("settings", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(xfadePrefsListener)
        } catch (_: Exception) {}
        xfadeHandler.removeCallbacksAndMessages(null)
        xfadeThread.quitSafely()
        // 【V8.227】停掉全库 BPM 补扫循环（单首补测协程一并取消）
        analysisScope.cancel()

        // [V3.3.7] ע����Ļ״̬������
        unregisterScreenOffReceiver()
        // 【V8.361】对称注销：蓝牙恢复监听与电话监听同生命周期
        unregisterBtRestoreReceiver()
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


    // [V3.3.7] ������ͣ FFT ���ӻ������� CPU ����
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 【V8.361】蓝牙耳机戴回去后自动恢复。
     *  摘下耳机时 pause 是 Android 自己做的（BECOMING_NOISY / A2DP 断连），
     *  但「戴回去继续播」没有任何人负责 —— 项目里搜不到 ACL_DISCONNECTED /
     *  A2DP 状态监听，所以表现是：摘下暂停、戴上一直停着。 */
    private var btRestoreReceiver: BroadcastReceiver? = null
    /** 摘下耳机前是否在播放 —— 只有「本来在放」才自动恢复，不擅自开始播放。 */
    @Volatile private var wasPlayingBeforeBtLoss = false
    /** 上一次蓝牙路由的设备名，用于确认恢复时 A2DP 真的回来了。 */
    @Volatile private var btDeviceNameBeforeLoss = ""
    private var isScreenOn = true

    // 电话状态监听：来电/通话中暂停播放，挂断后自动恢复（不依赖 AudioFocus，避免 Oboe 假性焦点丢失）
    private var phoneStateListener: PhoneStateListener? = null
    private var wasPlayingBeforeCall = false

    /**
     * 【V8.361】蓝牙耳机「戴回去自动恢复」。
     *
     * 现状：摘下耳机暂停是**系统做的**（BECOMING_NOISY / A2DP 断连，MediaSession 收到
     * ACTION_AUDIO_BECOMING_NOISY 后自动 pause），所以「摘下就暂停」本来就正常。
     * 但**恢复没有任何人负责** —— 全项目搜不到 ACL_DISCONNECTED / A2DP 状态监听，
     * 于是表现成「摘下暂停、戴上永远停着」。用户只能手动按播放。
     *
     * 做法照抄本文件既有的「来电挂断恢复」(phoneStateListener) 模式：
     *   断连时记下「本来在不在播」→ 连回时若本来在播、现在没播、且确实回到蓝牙，
     * 才 resume()。三条都满足才恢复，避免在用户主动暂停后擅自开播。
     *
     * 延迟恢复是必要的：A2DP ACL 刚连上时音频路由还没切完，立刻 play 会走扬声器
     * 出来一声，或拿到还没就绪的 sink。1.2s 是实测「路由稳定」的经验值。
     */
    private fun registerBtRestoreReceiver() {
        if (btRestoreReceiver != null) return
        try {
            val filter = IntentFilter().apply {
                addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
                addAction("android.bluetooth.device.action.ACL_CONNECTED")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    when (action) {
                        "android.bluetooth.device.action.ACL_DISCONNECTED" -> {
                            // 只在「当前真的走蓝牙」时才记状态；否则拔的有线耳机不该触发
                            val name = try { btRoutedDeviceName() } catch (_: Throwable) { "" }
                            if (name.isNotBlank()) {
                                wasPlayingBeforeBtLoss = isPlaying()
                                btDeviceNameBeforeLoss = name
                                Log.i(TAG, "BT ACL_DISCONNECTED (was '$name', playing=$wasPlayingBeforeBtLoss) -> will auto-resume on reconnect")
                            } else {
                                Log.d(TAG, "BT ACL_DISCONNECTED but route is not BT, ignore")
                            }
                        }
                        "android.bluetooth.device.action.ACL_CONNECTED" -> {
                            if (!wasPlayingBeforeBtLoss) return   // 本来就没在播，不擅自开播
                            handler.postDelayed({
                                if (!wasPlayingBeforeBtLoss) return@postDelayed
                                val back = try { btRoutedDeviceName() } catch (_: Throwable) { "" }
                                // 必须确认路由真的回到蓝牙了，否则会从扬声器出声
                                if (back.isBlank()) {
                                    Log.w(TAG, "BT ACL_CONNECTED but route is not BT yet, skip resume")
                                    return@postDelayed
                                }
                                if (isPlaying()) { wasPlayingBeforeBtLoss = false; return@postDelayed }
                                wasPlayingBeforeBtLoss = false
                                Log.i(TAG, "BT reconnected via '$back', resuming playback")
                                resume()
                            }, 1200L)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnsafeRegisteredReceiver")
                registerReceiver(receiver, filter)
            }
            btRestoreReceiver = receiver
            Log.d(TAG, "BT restore receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register BT restore receiver: ${e.message}")
        }
    }

    private fun unregisterBtRestoreReceiver() {
        try {
            btRestoreReceiver?.let {
                unregisterReceiver(it)
                btRestoreReceiver = null
                Log.d(TAG, "BT restore receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister BT restore receiver: ${e.message}")
        }
    }

    /** 当前音频输出是否走蓝牙，以及设备名；非蓝牙返回空串。 */
    private fun btRoutedDeviceName(): String {
        val af = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return ""
        val devs = af.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS) ?: return ""
        for (d in devs) {
            if (d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) return d.productName?.toString() ?: "bt"
        }
        return ""
    }

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
                        // 【2026-09-09】点亮强制重推 MediaSession 权威元数据：
                        // 灭屏期间自动切歌后 SystemUI 不重拉 metadata，锁屏卡片会停在上首歌名
                        // （声音已切新歌、通知栏已刷新，仅系统媒体卡片 stale）。
                        // refresh() -> invalidateState() 推送最新 playlist+index，点亮瞬间标题纠正。
                        try { musicPlayerState.refresh() } catch (_: Exception) {}
                        try { updateNotification() } catch (_: Exception) {}
                        // 【2026-09-09 第二层】延时二次推送：SCREEN_ON 广播时 SystemUI 锁屏媒体卡
                        // 可能尚未完成订阅，立即推送会被丢弃；补推覆盖订阅竞态窗口。
                        // 【第三层 2026-09-09】Doze 唤醒后 SystemUI 冷启动慢，300ms/800ms 双段补推，
                        // 覆盖锁屏媒体卡恢复订阅的完整窗口（刷新是幂等投影，重复无副作用）。
                        handler.postDelayed({
                            try { musicPlayerState.refresh() } catch (_: Exception) {}
                            try { updateNotification() } catch (_: Exception) {}
                            Log.d(TAG, "Screen ON: delayed metadata re-push (300ms)")
                        }, 300)
                        handler.postDelayed({
                            try { musicPlayerState.refresh() } catch (_: Exception) {}
                            try { updateNotification() } catch (_: Exception) {}
                            Log.d(TAG, "Screen ON: delayed metadata re-push (800ms)")
                        }, 800)
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

}

