package com.sdw.music.player

import android.content.Context
import android.util.Log

/**
 * OboeDirectPlayer — Direct audio playback using NDK MediaCodec decoder + Oboe output.
 *
 * Bypasses ExoPlayer entirely. Decodes audio files directly via AMediaCodec in C++,
 * feeds PCM to ring buffer, Oboe callback reads and outputs to AAudio Exclusive mode.
 *
 * This eliminates the ExoPlayer AudioSink format negotiation issues and allows
 * source-rate output (e.g., 44100Hz for FLAC) to bypass Motorola HAL resampling.
 *
 * Architecture:
 *   File → AMediaExtractor (demux) → AMediaCodec (decode) → PCM float → ring buffer → Oboe → AAudio Exclusive
 */
class OboeDirectPlayer(private val context: Context) {

    companion object {
        private const val TAG = "OboeDirectPlayer"

        /** 【V8.283】分轨文件的采样率（插件恒输出 44.1k，与宿主流率无关）。 */
        const val STEM_FILE_RATE = 44100
        
        var nativeLibLoaded = false
            private set

        init {
            try {
                System.loadLibrary("oboe_bridge")
                nativeLibLoaded = true
                Log.i(TAG, "oboe_bridge native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load oboe_bridge: ${e.message}")
                nativeLibLoaded = false
            }
        }
    }

    // State (volatile for cross-thread visibility)
    @JvmField @Volatile var isPlaying = false
    @JvmField @Volatile var isPrepared = false
    private var currentFilePath: String? = null
    private var parcelFd: android.os.ParcelFileDescriptor? = null  // 【V7.21】持有FD防止GCClose
    private var incomingParcelFd: android.os.ParcelFileDescriptor? = null  // 【Crossfade】incoming 轨 FD，防止 GC Close

    // Listener for playback events
    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Callback when native stream state changes (play ↔ pause).
     *  Called from ANY thread; callers must post to main thread for UI/MediaSession. */
    var onPlayStateChanged: ((isPlaying: Boolean) -> Unit)? = null

    // ---- Native methods ----
    private external fun nativeOpen(filePath: String): Boolean
    private external fun nativeOpenFd(fd: Int, offset: Long, length: Long): Boolean  // 【V7.20】FD方式打On
    private external fun nativePlay(): Boolean
    private external fun nativePause()
    private external fun nativeResume(): Boolean
    private external fun nativeSeekTo(positionUs: Long)
    private external fun nativeStop()
    /** 【V8.202】切歌软停止：解码全停、ring 清空，但保留输出流给下一首复用 */
    private external fun nativeStopSoft()
    private external fun nativeGetPositionMs(): Long
    private external fun nativeGetDurationMs(): Long
    private external fun nativeIsEos(): Boolean
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetChannelCount(): Int
    private external fun nativeIsExclusive(): Boolean
    private external fun nativeIsSharedMode(): Boolean
    // ---- Crossfade 双轨 native 方法 ----
    private external fun nativeOpenIncomingFd(fd: Int, offset: Long, length: Long): Boolean
    private external fun nativeStartCrossfade(durationMs: Int, bStartMs: Int, aLufs: Float, bLufs: Float, sfxMode: Int, aBpm: Float): Boolean
    /**
     * 【V8.245】sfxMode：0=自动过渡（native 按两轨 LUFS 差决定 riser/sweep/静默）
     *            1=手动切歌（黑胶刹车） 2=完全静默；aBpm=A 轨 BPM（音效小节量化用，无效传 0）
     */
    fun startCrossfade(durationMs: Int, bStartMs: Int = 0, aLufs: Float = -100f, bLufs: Float = -100f,
                       sfxMode: Int = 0, aBpm: Float = 0f): Boolean =
        try { nativeStartCrossfade(durationMs, bStartMs, aLufs, bLufs, sfxMode, aBpm) } catch (e: Throwable) { Log.e(TAG, "startCrossfade: ${e.message}"); false }
    private external fun nativePreScanPath(fd: Int, length: Long, windowMs: Int, mode: Int, seekFromMs: Long, bpm: Int): Int  // 【V8.9】预扫描：0=尾部 1=前奏静音 2=副歌 3=尾副歌结束 4=首拍(V8.142)
    private external fun nativeSeekIncomingMs(ms: Int): Boolean
    private external fun nativeStopIncoming()
    // 【V8.145】DJ 切歌效果：effect 0=off 1=filter 2=eq 3=echo 4=dissolve 5=tremolo 6=lunar_echo
    private external fun nativeSetCrossfadeEffect(effect: Int, beatMs: Float)
    // 【V8.150 Live FX 总线】效果改为混音后总线处理：开关 + 类型 + 深度，可随时下发
    private external fun nativeSetLiveFx(enabled: Boolean, type: Int, depth: Float)
    /** 【V8.281】djay Crossfader FX 模板：tpl 0=关 1=Filter 2=EchoFade 3=Brake 4=Dissolve */
    private external fun nativeSetXfadeTemplate(tpl: Int, echoSamples: Int)
    // 【M3.2】DJ 音效包：paths/triggers/gains 三数组一一对应下发；空数组 = 卸载当前包
    // 【V8.424】过渡采样音效已删除：nativeSfxSetPack / nativeSfxTrigger / nativeSfxSetEnabled
    // 三个 external 声明及对应包装函数移除（native 侧符号保留但不再被调用，恒静默）。
    private external fun nativeIsActiveB(): Boolean
    private external fun nativeIsCrossfading(): Boolean

    /** 【V8.307】压缩进行中的 crossfade 到 targetMs 内结束；无过渡在跑时返回 false。 */
    private external fun nativeCutCrossfadeShort(targetMs: Int): Boolean

    /** 【V8.310】DJ 扩展配置一次性下发：keylock / pitch 档位 / phrase 小节数 / auto-solo 频段。 */
    private external fun nativeSetDjExtras(
        keylock: Boolean, pitchSemi: Int, phraseBars: Int, soloBand: Int
    )

    /**
     * 【V8.310】下发 DJ 扩展配置（过渡开始前调用）。
     * @param keylock 变速时锁音高（变速度不变调）
     * @param pitchSemi Pitch/Bend 量化档位（半音，0=连续扫，负=降调）
     * @param phraseBars Phrase 分段方案小节数（4/8/16/32）
     * @param soloBand Auto-solo 频段（0=关 1=低 2=中 3=高）
     */
    fun setDjExtras(keylock: Boolean, pitchSemi: Int, phraseBars: Int, soloBand: Int) {
        try { nativeSetDjExtras(keylock, pitchSemi, phraseBars, soloBand) } catch (_: Throwable) {}
    }

    /** 【V8.500 ④】自动 Camelot 调性匹配：下发「出轨」音高平移半音数（仅 keylock 生效）。 */
    private external fun nativeSetKeyMatchSemi(semi: Int)
    fun setKeyMatchSemi(semi: Int) {
        try { nativeSetKeyMatchSemi(semi) } catch (_: Throwable) {}
    }
    private external fun nativeGetXfadeLevel(out: FloatArray): Unit
    private external fun nativeReleaseInactive()

    // DSP EQ native methods
    private external fun nativeSetDspEq(enabled: Boolean,
                                        highShelfFreq: Float, highShelfDb: Float, highShelfQ: Float,
                                        peakingFreq: Float, peakingDb: Float, peakingQ: Float,
                                        preGainDb: Float)
    private external fun nativeResetDspEq()

    // 【v6.29】独立 DSP On关
    private external fun nativeSetDspEnabled(enabled: Boolean)
    private external fun nativeIsDspEnabled(): Boolean

    // 【V7.0】采样率自适应
    private external fun nativeSetSampleRateNative(nativeSampleRate: Int)
    private external fun nativeGetSampleRateNative(): Int

    // 【V3.2.7】输出设备路由（USB DAC Port ID，0=系统默认）
    private external fun nativeSetOutputDeviceId(deviceId: Int)

    // 【V7.0】峰值检测 Debug
    private external fun nativeGetClipRatio(): Float
    private external fun nativeGetClipCount(): Int
    private external fun nativeGetTotalSamples(): Float
    private external fun nativeResetClipStats()
    private external fun nativeGetRmsLevel(): Float  // 【V7.xx】实时RMS振幅 0~1，节拍可视化
    private external fun nativeGetBandSub(): Float     // 0-60Hz (compat)
    private external fun nativeGetBandBass(): Float    // 60-250Hz (compat)
    private external fun nativeGetBandMid(): Float     // 250-2000Hz (compat)
    private external fun nativeGetBandHigh(): Float    // 2000-20000Hz (compat)
    private external fun nativeGetBand0(): Float       // 0-60Hz
    private external fun nativeGetBand1(): Float       // 60-120Hz
    private external fun nativeGetBand2(): Float       // 120-250Hz
    private external fun nativeGetBand3(): Float       // 250-500Hz
    private external fun nativeGetBand4(): Float       // 500-2000Hz
    private external fun nativeGetBand5(): Float       // 2000-6000Hz
    private external fun nativeGetBand6(): Float       // 6000-12000Hz
    private external fun nativeGetBand7(): Float       // 12000-20000Hz
    private external fun nativeSetSilenceTest(enabled: Boolean)  // 【V7.08】静音测试
    private external fun nativeSetSineTest(enabled: Boolean)         // 【V7.09】正弦波自检
    private external fun nativeIsSineTestRunning(): Boolean          // 【V7.24】
    private external fun nativeGetDspDisabledSamples(): Long        // 【V7.08】DSPClose时的采样数

    // 【V7.80】5段图形Equalizer预设 JNI
    private external fun nativeSetDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray?)
    private external fun nativeResetDspEq5Band()
    private external fun nativeSetMsebActive(active: Boolean)  // 【V7.200】MSEB激活保护标志
    // 【V8.2】MSEB 10-band subjective EQ JNI
    private external fun nativeSetMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray)
    private external fun nativeResetMseb10Band()
    // 【V8.3】M/S 声场 JNI
    private external fun nativeSetMsStage(soundstage: Float, imaging: Float)
    private external fun nativeResetMsStage()
    // 【V8.172】DTS 高级参数：环绕包围强度 + 高度感
    private external fun nativeSetDtsStage(surround: Float, height: Float)
    // 【V8.172】蓝牙链路自适应：把 A2DP codec 名交给 native 收敛环绕高频量
    private external fun nativeSetDtsBtProfile(codec: String?)
    // 【V8.3】瞬态整形 JNI
    private external fun nativeSetTransient(amount: Float)

    // 【V8.170】新 DSP 模块 JNI（空间混响 / 动态 EQ / 多频段限幅 / AutoEQ 强度）
    private external fun nativeSetSpaceReverbEnabled(enabled: Boolean)
    private external fun nativeSetSpaceReverbWet(wet: Float)
    private external fun nativeSetSpaceReverbShape(roomSize: Float, damping: Float)
    private external fun nativeSetDynamicEqEnabled(enabled: Boolean)
    private external fun nativeSetDynamicEqDepth(depth: Float)
    private external fun nativeSetDynamicEqThreshold(threshold: Float)
    private external fun nativeSetMultiBandLimiterEnabled(enabled: Boolean)
    private external fun nativeSetAutoEqMix(mix: Float)
    private external fun nativeResetTransient()
    // 【V8.3】动态压缩 JNI（Master Bus Compressor）
    private external fun nativeSetCompressorEnabled(enabled: Boolean)
    private external fun nativeSetCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float)
    // 【V8.3】等响补偿 JNI（ISO 226）
    private external fun nativeSetLoudnessEnabled(enabled: Boolean)
    private external fun nativeSetLoudnessIntensity(intensity: Float)
    private external fun nativeSetNormalizationGain(db: Float)
    // 【V8.3】Crossfeed JNI（消除头中效应，仅 Oboe）
    private external fun nativeSetCrossfeed(amount: Float)
    private external fun nativeResetCrossfeed()
    // 【V7.86】AutoEQ 10-band JNI
    private external fun nativeSetAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float)
    private external fun nativeResetAutoEq()
    private external fun nativeSetNightMode(enabled: Boolean)
    private external fun nativeIsNightMode(): Boolean
    // [V8.119] 宽裕余量档：Oboe/蓝牙路径 softClip 拐点 + limiter 天花板
    private external fun nativeSetWideHeadroom(enabled: Boolean)

    /**
     * V8.344: 音频回调内**逐帧**诊断日志的运行期总闸。
     *
     * 这些日志（XF-CLICK / XFADE-POST / XF-DBG / SDW_AUDG / SDW_VU / AGC diag）
     * 跑在实时音频线程上、窗口内每帧各打一条，一次 16 秒过渡约 3750 次
     * `__android_log_print` + 浮点格式化 —— 这是 underrun 的潜在来源。
     *
     * 默认 **关**：平时零开销（native 侧连参数都不求值）。
     * 排查真机音频异常时临时打开，用完请关回。
     */
    fun setDiagVerbose(on: Boolean) {
        try { nativeSetDiagVerbose(on) } catch (_: Throwable) {}
    }

    private external fun nativeSetDiagVerbose(on: Boolean)
    // [V8.119] 原声直通：整条 DSP（EQ/限幅/softClip/dither...）全部旁路，原始 PCM 直出
    private external fun nativeSetDirectPassthrough(enabled: Boolean)
    // 【V8.313】左右声道平衡：-1=全左 / 0=居中 / +1=全右，native 侧走恒功率声像曲线
    private external fun nativeSetStereoBalance(bal: Float)
    // 【V8.177】自动增益（AGC）独立开关：native 里 g_agcEnabled 默认 false，
    // 之前只由诊断页显示、界面上根本没有开关 —— 用户问"我有自动增益？不能关？"
    private external fun nativeSetAgcEnabled(enabled: Boolean)
    private external fun nativeSetDitherEnabled(enabled: Boolean)
    private external fun nativeIsDitherEnabled(): Boolean
    private external fun nativeSetDcBlockEnabled(enabled: Boolean)
    private external fun nativeSetBtPreEmphasis(enabled: Boolean, codecDb: Float)
    // 【V8.295】蓝牙断开时由 MusicService 的 AudioDeviceCallback 主动触发：按默认路由重建输出流
    private external fun nativeReopenOutputOnRouteReady()
    private external fun nativeIsDcBlockEnabled(): Boolean
    private external fun nativeGetDspChainState(): IntArray  // [V8.124] DSP 链 enable bitmask

    // 【V7.30】Brand Presets移植

    fun open(filePath: String): Boolean {
        Log.i(TAG, "Opening: $filePath")
        // 【V8.183】切歌时把仍占着 incoming 槽的旧 B 轨一并收掉。
        // 上一轮 crossfade 被手动切歌打断时不一定走到 stopIncoming()，残留的解码线程
        // 会继续往 ringB 写 PCM 并占着 codec/extractor；下次 crossfade 一开混，
        // 那条错位轨就叠进正在播放的音频里（听感：切歌后拖动播放条有「啪啪啪」乱响）。
        stopIncoming()

        try {
            // 【V7.20】优先用 FD 方式打On（解决 AMEDIA_ERROR_IO -10002）
            var opened = false
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    // Close之前的 PFD
                    parcelFd?.close()
                    parcelFd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    val fd = parcelFd!!.fd
                    val length = file.length()
                    Log.i(TAG, "Trying FD open: fd=$fd, offset=0, length=$length")
                    opened = nativeOpenFd(fd, 0L, length)
                    if (!opened) {
                        parcelFd?.close()
                        parcelFd = null
                        Log.w(TAG, "FD open failed, falling back to path")
                    }
                } else {
                    Log.w(TAG, "File does not exist: $filePath")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "FD open exception: ${e.message}, trying path")
            }
            
            // Fallback to path-based open
            if (!opened) {
                opened = nativeOpen(filePath)
            }
            
            if (opened) {
                isPrepared = true
                currentFilePath = filePath
                Log.i(TAG, "Opened successfully: rate=${nativeGetSampleRate()}, ch=${nativeGetChannelCount()}, exclusive=${nativeIsExclusive()}")
            } else {
                Log.e(TAG, "Failed to open: $filePath")
                isPrepared = false
                onError?.invoke("Failed to open file")
            }
            return opened
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in open: ${e.message}")
            isPrepared = false
            onError?.invoke(e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Start or resume playback.
     */
    fun play(): Boolean {
        if (!isPrepared) {
            Log.e(TAG, "Not prepared — call open() first")
            return false
        }

        try {
            val result = nativePlay()
            if (result) {
                isPlaying = true
                onPlayStateChanged?.invoke(true)
                // Start monitoring thread for completion
                startCompletionMonitor()
            }
            return result
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in play: ${e.message}")
            onError?.invoke(e.message ?: "Play error")
            return false
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        if (!isPlaying) return
        try {
            nativePause()
            isPlaying = false
            onPlayStateChanged?.invoke(false)
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in pause: ${e.message}")
        }
    }

    /**
     * Resume after pause.
     */
    fun resume(): Boolean {
        if (isPlaying) return true
        try {
            val result = nativeResume()
            if (result) {
                isPlaying = true
                onPlayStateChanged?.invoke(true)
                startCompletionMonitor()
            }
            return result
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in resume: ${e.message}")
            return false
        }
    }

    /**
     * Seek to position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        // 【V8.339 关键·用户报"进度条也无法拖动"】
        // 原来这里 `if (!isPrepared) return` **静默返回**，没打任何日志。
        // 而 !isPrepared 精确覆盖了用户最容易去拖进度条的时间窗：
        //   · 刚点歌、open() 还在配置 MediaCodec（数百 ms~秒级）；
        //   · DJ 过渡落点后正在重挂 monitor / 换 active 轨；
        //   · 解码线程刚 join、新的还没起来。
        // 这些窗口里拖进度条 = 播放位置跳了但声音没跳（或反之），观感就是"拖不动"。
        // 现在放行（nativeSeekTo 自己有 g_decoderLifecycleMutex + active轨分流兜底），
        // 并记录日志，便于确认是否真的生效。
        if (!isPrepared) {
            Log.w(TAG, "seekTo($positionMs) on UNPREPARED player -> still applying (was silently dropped)")
        }
        try {
            nativeSeekTo(positionMs * 1000) // ms → us
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in seekTo(${positionMs}): ${e.message}")
        }
    }

    /**
     * Stop playback and release all resources.
     *
     * 【V8.202】keepStream=true 是切歌专用：旧实现每次切歌都走完整 stop，
     * native 侧会把输出流 close+reset 掉，于是紧接着的 nativeOpenFd 只能重建流
     * （真机实测每次 125~238ms 静音）。这个模式保留流对象，下一首采样率一致时直接复用。
     * 真正退出（performHardExit / onDestroy）仍走完整销毁，不要图快省这一下。
     */
    fun stop(keepStream: Boolean = false) {
        // 【V7.01】先中断 monitor 线程并等待其结束，防止竞态条件
        val wasPlaying = isPlaying
        isPlaying = false  // 先Settings标志，让 monitor 线程退出循环
        monitorThread?.interrupt()
        try {
            monitorThread?.join(500)  // 等待最多 500ms
        } catch (_: Throwable) {}
        monitorThread = null

        try {
            if (keepStream) nativeStopSoft() else nativeStop()
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in stop: ${e.message}")
        }
        isPrepared = false
        if (wasPlaying) {
            onPlayStateChanged?.invoke(false)
        }
        currentFilePath = null
        // 【V7.21】Close FD
        try { parcelFd?.close() } catch (_: Throwable) {}
        parcelFd = null
        // 【Crossfade】清理 incoming 轨 FD
        try { incomingParcelFd?.close() } catch (_: Throwable) {}
        incomingParcelFd = null
    }

    // ---- Crossfade 双轨 API ----

    /** 【V8.343】incoming 槽当前装的是哪条路径（null = 空槽）。
     *  manual DJ triggering opens incoming every round; native's openIncomingToB_*
     *  unconditionally tears down the previous codec first, so re-opening the SAME
     *  track destroys the freshly-built one and StartCrossfade then fails with
     *  "incoming(B) not opened". Remembering the path lets us skip the redundant
     *  re-open. Reset by stopIncoming / open(). */
    @Volatile private var incomingPathCur: String? = null

    /** incoming 槽是否已经装着这条路径（且 codec 仍有效）。 */
    fun isIncomingOpenFor(filePath: String): Boolean {
        val cur = incomingPathCur
        return !cur.isNullOrBlank() && cur == filePath && incomingParcelFd != null
    }

    /** 预加载下一首（进空闲槽，open 但不启解码线程）。返回是否成功。
     *  【V8.343】同一路径重复 open 视为成功（复用已建好的 codec），
     *  避免 native 无条件重建把刚开的槽干掉。 */
    fun openIncoming(filePath: String): Boolean {
        if (isIncomingOpenFor(filePath)) {
            Log.i(TAG, "openIncoming: reuse existing incoming for $filePath")
            return true
        }
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.w(TAG, "openIncoming: file not exist: $filePath")
                return false
            }
            try { incomingParcelFd?.close() } catch (_: Throwable) {}
            incomingParcelFd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val fd = incomingParcelFd!!.fd
            val length = file.length()
            val ok = nativeOpenIncomingFd(fd, 0L, length)
            if (!ok) {
                try { incomingParcelFd?.close() } catch (_: Throwable) {}
                incomingParcelFd = null
                incomingPathCur = null
            } else {
                incomingPathCur = filePath
            }
            Log.i(TAG, "openIncoming: $filePath -> $ok")
            return ok
        } catch (e: Throwable) {
            Log.e(TAG, "openIncoming exception: ${e.message}")
            incomingPathCur = null
            return false
        }
    }

    /** 【V8.9】预扫描：
     *  mode=0：扫 path 尾部（从 seekFromMs 起），返回最长低能量段起点(ms)（A 轨用）
     *  mode=1：扫 path 开头前奏，返回前奏静音段长度(ms)（B 轨用）
     *  mode=2：B 轨副歌起点(ms)；bpm 用于拍点吸附（0=未知回退120）
     *  mode=3：A 轨最后副歌结束(ms)
     *  mode=4：【V8.142】首个强拍(ms)，拍相位基准
     *  找不到返回 -1。 */
    fun preScanPath(path: String, windowMs: Int = 100, mode: Int = 0, seekFromMs: Long = 0L, bpm: Int = 0): Int {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) { Log.w(TAG, "preScanPath: file not exist: $path"); -1 }
            else {
                val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val len = file.length()
                    nativePreScanPath(pfd.fd, len, windowMs, mode, seekFromMs, bpm)
                } finally {
                    try { pfd.close() } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) { Log.e(TAG, "preScanPath: ${e.message}"); -1 }
    }

    /** 【V8.15】B 轨从副歌起点播：crossfade 前 seek 空闲槽到指定 ms。 */
    fun seekIncoming(ms: Int): Boolean = try { nativeSeekIncomingMs(ms) } catch (e: Throwable) { Log.e(TAG, "seekIncoming: ${e.message}"); false }

    /** 【V8.145】设置交叉过渡 DJ 效果（beatMs 用于 echo/lunar 拍同步，0=不同步）。 */
    fun setCrossfadeEffect(effect: Int, beatMs: Float) {
        try { nativeSetCrossfadeEffect(effect, beatMs) } catch (e: Throwable) { Log.e(TAG, "setCrossfadeEffect: ${e.message}") }
    }

    /** 【V8.150】Live FX 总线：开关 + 效果类型(1..7) + 深度(0..1)，随时可切换、即时生效。 */
    fun setLiveFx(enabled: Boolean, type: Int, depth: Float) {
        try { nativeSetLiveFx(enabled, type, depth) } catch (e: Throwable) { Log.e(TAG, "setLiveFx: ${e.message}") }
    }

    /** 【V8.281】下发 djay 过渡模板。echoSamples = 3/4 拍长度（样本），0 让 native 用默认。 */
    fun setXfadeTemplate(tpl: Int, echoSamples: Int = 0) {
        try { nativeSetXfadeTemplate(tpl, echoSamples) }
        catch (e: Throwable) { Log.e(TAG, "setXfadeTemplate: ${e.message}") }
    }

    /**
     * 【V8.285】djay Neural Mix 分轨音效路由：把过渡效果只挂到某一个分轨上。
     * target：0=关（挂整曲） 1=人声 2=鼓组 3=旋律/伴奏；mode：0=无 1=HPF 扫频 2=Echo。
     * 只在分轨总线真正点亮（该曲已有分轨缓存）时才听得到，否则静默无效果。
     */
    fun setStemFxTarget(target: Int, mode: Int) {
        try { nativeSetStemFxTarget(target, mode) }
        catch (e: Throwable) { Log.e(TAG, "setStemFxTarget: ${e.message}") }
    }

    /** 【M2】beatmatch：给 native 下发「拉伸比 + 总开关」，下次 crossfade 生效。 */
    private external fun nativeSetBeatmatch(enabled: Boolean, ratio: Float, aBpm: Float, bBpm: Float, phaseLock: Boolean)

    /** 【V8.322】PLL 整定参数：死区 / 小增益 / 大增益。 */
    private external fun nativeSetPlTuning(deadzone: Float, gainLo: Float, gainHi: Float)

    /** 【V8.322】B 轨入场点的真实拍相位（0..1）；<0 表示未知（native 退回"假定 downbeat"）。 */
    private external fun nativeSetPlInitPhase(phase01: Float)

    /** 【V8.503·③】A 轨当前真实拍相位（0..1，以 xfadeABeatPhaseMs 为 anchor）；<0 退回旧行为。 */
    private external fun nativeSetPlAInitPhase(phase01: Float)

    /** 【V8.222】变速引擎选择：true=Rubber Band 高质量 keylock，false=WSOLA（旧档，保留）。 */
    private external fun nativeSetBeatmatchEngine(rb: Boolean)

    /** 【V8.222】切换变速引擎（切换即刻退役当前拉伸，下次 crossfade 按新引擎武装）。 */
    fun setBeatmatchEngine(rb: Boolean) {
        try { nativeSetBeatmatchEngine(rb) } catch (e: Throwable) { Log.e(TAG, "setBeatmatchEngine: ${e.message}") }
    }

    /** 【V8.427】渐变拉速总开关（全模板共用的速度地基）。true=过渡期从旧曲原速平滑拉齐新曲速度。 */
    private external fun nativeSetTempoRamp(on: Boolean)

    /** 【V8.427】渐变拉速：下发总开关，下次 crossfade arming 生效（运行中的过渡不受影响）。 */
    fun setTempoRamp(on: Boolean) {
        try { nativeSetTempoRamp(on) } catch (e: Throwable) { Log.e(TAG, "setTempoRamp: ${e.message}") }
    }

    /** 【V8.220】低频交接插件开关（默认关；只改交叉淡化期间的交接曲线）。 */
    private external fun nativeSetXfadeBassSwap(enabled: Boolean)

    /** 【V8.220】低频交接（专业 DJ 式 bass swap）。关闭时 native 走原曲线，链路零改动。 */
    fun setXfadeBassSwap(enabled: Boolean) {
        try { nativeSetXfadeBassSwap(enabled) } catch (e: Throwable) { Log.e(TAG, "setXfadeBassSwap: ${e.message}") }
    }

    /** 【V8.246】三段频交接插件开关（默认关；低/中/高频各自独立曲线错峰交接）。 */
    private external fun nativeSetXfadeThreeBand(enabled: Boolean)

    /** 【V8.246】三段频交接。关闭时 native 走原链路，逐样本一致。 */
    fun setXfadeThreeBand(enabled: Boolean) {
        try { nativeSetXfadeThreeBand(enabled) } catch (e: Throwable) { Log.e(TAG, "setXfadeThreeBand: ${e.message}") }
    }

    /**
     * 【M2】设置下一首的变速比率。
     *
     * ratio = 输出长度 / 输入长度：>1 表示 incoming 要「放慢听感」（拖长），<1 表示加快。
     * 比率在 ±0.2% 内视为无需处理（省 CPU）；native 侧会在下一次 startCrossfade 时才
     * 真正武装 WSOLA —— 那时采样率/声道数才确定。
     */
    /** 【M2/V8.243】beatmatch 下发：ratio=拉伸比，aBpm/bBpm=A/B 轨 BPM（连续相位锁定用），
     *  phaseLock=是否启用持续相位锁定（默认开）。 */
    /**
     * 【V8.322】下发 PLL 整定参数。默认给的是"浮点 BPM 时代"的新值：
     * 死区 0.0002（旧 0.001 相当于 0.1% 速度盲区，太粗）、小增益 0.10、大增益 0.30。
     */
    fun setPlTuning(deadzone: Float = 0.0002f, gainLo: Float = 0.10f, gainHi: Float = 0.30f) {
        try { nativeSetPlTuning(deadzone, gainLo, gainHi) } catch (e: Throwable) {
            Log.e(TAG, "setPlTuning: ${e.message}")
        }
    }

    /** 【V8.322】告知 native B 入场点的真实拍相位（0..1 小节内），让 PLL 初始相位不是硬假定。 */
    fun setPlInitPhase(phase01: Float) {
        try { nativeSetPlInitPhase(phase01) } catch (e: Throwable) {
            Log.e(TAG, "setPlInitPhase: ${e.message}")
        }
    }

    /** 【V8.503·③】告知 native A 轨当前真实拍相位（0..1 小节内，以 xfadeABeatPhaseMs 为 anchor）。 */
    fun setPlAInitPhase(phase01: Float) {
        try { nativeSetPlAInitPhase(phase01) } catch (e: Throwable) {
            Log.e(TAG, "setPlAInitPhase: ${e.message}")
        }
    }

    fun setBeatmatch(ratio: Float, aBpm: Float = 0f, bBpm: Float = 0f, phaseLock: Boolean = true, engage: Boolean = false) {
        // 【V8.423】engage = 双侧 BPM 置信（来自 beatmatchParams）；为 true 时即便 ratio≈1.0 也武装
        // 变速引擎 + 持续相位锁定（djay 式：过渡期始终跟速，落点前回原速），纠正 BPM 检测残差漂移。
        // 旧逻辑用 |ratio|>0.2% 判定「是否需要」，会导致近拍 pair 不锁相 → 过渡期鼓点漂移（分裂感）。
        try { nativeSetBeatmatch(engage, ratio, aBpm, bBpm, phaseLock) } catch (e: Throwable) { Log.e(TAG, "setBeatmatch: ${e.message}") }
    }

    /** 停止并释放 incoming 轨。 */
    fun stopIncoming() { try { nativeStopIncoming() } catch (_: Throwable) {}; try { incomingParcelFd?.close() } catch (_: Throwable) {}; incomingParcelFd = null; incomingPathCur = null }

    /** 当前 active 轨是否为 B。 */
    fun isActiveB(): Boolean = try { nativeIsActiveB() } catch (_: Throwable) { false }

    /** 当前是否正处于 crossfade（native g_crossfadeActive）。用于完成轮询的兜底检测，
     *  防止仅依赖 isActiveB() 翻转时 busy 卡死（DJ 手动切歌「只能切一次」的根因）。 */
    fun isCrossfading(): Boolean = try { nativeIsCrossfading() } catch (_: Throwable) { false }

    /**
     * 【V8.307 手动抢占】把进行中的 crossfade 压缩到 targetMs 内结束。
     * 只放大 native 的进度步长，落点仍走正常路径（XFADE-DONE / 引擎退役 / de-click）。
     * @return true = 已加速；false = 当前没有过渡在跑，或过渡已接近落点。
     */
    fun cutCrossfadeShort(targetMs: Int): Boolean =
        try { nativeCutCrossfadeShort(targetMs) } catch (_: Throwable) { false }

    /** 【V8.182 过渡可视化】floatArrayOf(progress 0..1, rmsA, rmsB)；非 crossfade 期 progress=0。 */
    fun getXfadeLevel(): FloatArray = try {
        val a = FloatArray(3)
        nativeGetXfadeLevel(a)
        a
    } catch (_: Throwable) { floatArrayOf(0f, 0f, 0f) }

    /** 释放非活动轨（crossfade 完成后回收旧轨）。 */
    fun releaseInactive() { try { nativeReleaseInactive() } catch (_: Throwable) {}; try { incomingParcelFd?.close() } catch (_: Throwable) {}; incomingParcelFd = null }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPositionMs(): Long {
        if (!isPrepared) return 0
        return try { nativeGetPositionMs() } catch (_: Throwable) { 0 }
    }

    /**
     * Get duration in milliseconds.
     */
    fun getDurationMs(): Long {
        if (!isPrepared) return 0
        return try { nativeGetDurationMs() } catch (_: Throwable) { 0 }
    }

    // ---- Completion monitor ----

    private var monitorThread: Thread? = null

    private fun startCompletionMonitor() {
        monitorThread?.interrupt()
        monitorThread = Thread {
            try {
                while (isPlaying && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(200)
                    if (nativeIsEos()) {
                        Log.i(TAG, "Playback completed (EOS)")
                        isPlaying = false
                        onPlayStateChanged?.invoke(false)
                        onCompletion?.invoke()
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // 【V7.120】Interrupted by stop() — check if EOS happened during sleep
                // Without this check, auto-next-on-completion is lost when stop() interrupts
                // the monitor before the EOS flag is polled.
                try {
                    if (nativeIsEos()) {
                        Log.i(TAG, "Playback completed (EOS detected after interrupt)")
                        isPlaying = false
                        onPlayStateChanged?.invoke(false)
                        onCompletion?.invoke()
                    }
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; start() }
    }

    // ---- Diagnostics ----

    fun getSampleRate(): Int = if (isPrepared) try { nativeGetSampleRate() } catch (_: Throwable) { 0 } else 0
    fun getChannelCount(): Int = if (isPrepared) try { nativeGetChannelCount() } catch (_: Throwable) { 0 } else 0
    fun isExclusiveMode(): Boolean = if (isPrepared) try { nativeIsExclusive() } catch (_: Throwable) { false } else false

    /** Get current sharing mode: true = Shared, false = Exclusive */
    fun isSharedMode(): Boolean = try { nativeIsSharedMode() } catch (_: Throwable) { false }

    // ---- DSP EQ API ----

    /**
     * Enable/Disable DSP Biquad EQ in Oboe callback (zero latency!)
     *
     * Steven's Special preset defaults:
     *   Band 1: High-Shelf 8000Hz / -6.0dB / Q=0.707
     *   Band 2: Peaking 12000Hz / -4.0dB / Q=2.0
     *   Pre-gain: -3dB headroom
     */
    fun setDspEq(
        enabled: Boolean,
        highShelfFreq: Float = 8000f,
        highShelfDb: Float = -6.0f,
        highShelfQ: Float = 0.707f,
        peakingFreq: Float = 12000f,
        peakingDb: Float = -4.0f,
        peakingQ: Float = 2.0f,
        preGainDb: Float = -3.0f
    ) {
        try {
            nativeSetDspEq(enabled, highShelfFreq, highShelfDb, highShelfQ,
                            peakingFreq, peakingDb, peakingQ, preGainDb)
        } catch (e: Throwable) {
            Log.e(TAG, "DSP EQ error: ${e.message}")
        }
    }

    /** Reset DSP EQ filter state (call on seek/track change) */
    fun resetDspEq() {
        try {
            nativeResetDspEq()
        } catch (_: Throwable) {}
    }

    // 【v6.29】独立 DSP On关
    fun setDspEnabled(enabled: Boolean) {
        try {
            nativeSetDspEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setDspEnabled error: ${e.message}")
        }
    }

    fun isDspEnabled(): Boolean {
        return try {
            nativeIsDspEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    // [V8.124] Signal Path 诊断：DSP 链 16 级 enable 状态（0=off/旁路, 1=on）
    fun getDspChainState(): IntArray {
        return try { nativeGetDspChainState() } catch (_: Throwable) { IntArray(16) }
    }

    // ============================================================================
    // 【V7.0】采样率自适应 — Bit-Perfect Check
    // ============================================================================

    /**
     * Settings系统原生采样率（从 AudioManager 查询后传入）
     * Android 原生采样率通常为 48000，少数设备为 44100
     *
     * 逻辑：
     * - 文件采样率 = 系统采样率 → Bit-Perfect 播放
     * - 文件采样率 ≠ 系统采样率 → 切换到系统采样率 + DSP 高质量重采样
     */
    fun setSampleRateNative(nativeSampleRate: Int) {
        try {
            nativeSetSampleRateNative(nativeSampleRate)
            Log.i(TAG, "Native sample rate set: $nativeSampleRate Hz")
        } catch (e: Throwable) {
            Log.e(TAG, "setSampleRateNative error: ${e.message}")
        }
    }

    /** 【V3.2.7】设置输出设备（USB DAC Port ID，0=系统默认） */
    fun setOutputDevice(deviceId: Int) {
        try {
            nativeSetOutputDeviceId(deviceId)
            Log.i(TAG, "Output device set: $deviceId")
        } catch (e: Throwable) {
            Log.e(TAG, "setOutputDevice error: ${e.message}")
        }
    }

    /** 获取已Settings的原生采样率 */
    fun getSampleRateNative(): Int {
        return try { nativeGetSampleRateNative() } catch (_: Throwable) { 0 }
    }

    // ============================================================================
    // 【V7.0】峰值检测 Debug
    // ============================================================================

    /**
     * 获取软削波触发比例（0.0 ~ 1.0）
     * 解读：> 5% = 增益过高，0.1%~5% = 刚好，< 0.1% = 保护过度
     */
    fun getClipRatio(): Float {
        return try { nativeGetClipRatio() } catch (_: Throwable) { 0f }
    }

    fun getClipCount(): Int {
        return try { nativeGetClipCount() } catch (_: Throwable) { 0 }
    }

    fun getTotalSampleCount(): Float {
        return try { nativeGetTotalSamples() } catch (_: Throwable) { 0f }
    }

    /** 实时 RMS 振幅（0~1），Oboe 回调计算，用于节拍可视化 */
    fun getRmsLevel(): Float {
        return try { nativeGetRmsLevel() } catch (_: Throwable) { 0f }
    }

    /** 实时频谱分频能量（0~1），Oboe 回调双二阶滤波计算 */
    fun getBandSub(): Float = try { nativeGetBandSub() } catch (_: Throwable) { 0f }
    fun getBandBass(): Float = try { nativeGetBandBass() } catch (_: Throwable) { 0f }
    fun getBandMid(): Float = try { nativeGetBandMid() } catch (_: Throwable) { 0f }
    fun getBandHigh(): Float = try { nativeGetBandHigh() } catch (_: Throwable) { 0f }
    // 8-band raw FFT
    fun getBand0(): Float = try { nativeGetBand0() } catch (_: Throwable) { 0f }
    fun getBand1(): Float = try { nativeGetBand1() } catch (_: Throwable) { 0f }
    fun getBand2(): Float = try { nativeGetBand2() } catch (_: Throwable) { 0f }
    fun getBand3(): Float = try { nativeGetBand3() } catch (_: Throwable) { 0f }
    fun getBand4(): Float = try { nativeGetBand4() } catch (_: Throwable) { 0f }
    fun getBand5(): Float = try { nativeGetBand5() } catch (_: Throwable) { 0f }
    fun getBand6(): Float = try { nativeGetBand6() } catch (_: Throwable) { 0f }
    fun getBand7(): Float = try { nativeGetBand7() } catch (_: Throwable) { 0f }
    // 【V8.174】左右声道各自的 8 频段（Mixer VU 表画真 L/R 两列用）
    private external fun nativeGetSpectrumL(): FloatArray
    private external fun nativeGetSpectrumR(): FloatArray
    fun getSpectrumL(): FloatArray = try { nativeGetSpectrumL() } catch (_: Throwable) { FloatArray(8) }
    fun getSpectrumR(): FloatArray = try { nativeGetSpectrumR() } catch (_: Throwable) { FloatArray(8) }

    /**
     * 【V8.207】一次取全，供 VU 表逐帧读：`out` 由调用方持有（不每次分配）。
     *   [0..7] L 声道 8 段   [8..15] R 声道 8 段
     *   [16] sub [17] bass [18] mid [19] high [20] rms(mono)
     *   【V8.208】[21] rmsL [22] rmsR [23] peakL [24] peakR（真值，可直接换 dBFS）
     * 长度必须 ≥25。返回 false = 没取到，调用方继续保持旧值。
     */
    private external fun nativeReadBandLevels(out: FloatArray): Int
    fun readBandLevels(out: FloatArray): Boolean =
        try { nativeReadBandLevels(out) > 0 } catch (_: Throwable) { false }

    /** All 8 bands as FloatArray for FFT display */
    fun getBands8(): FloatArray = try {
        floatArrayOf(nativeGetBand0(), nativeGetBand1(), nativeGetBand2(), nativeGetBand3(),
                     nativeGetBand4(), nativeGetBand5(), nativeGetBand6(), nativeGetBand7())
    } catch (_: Throwable) { FloatArray(8) }

    /**
     * 【V8.370】B 轨（DJ 预载的下一首）8 段频谱，一次 JNI 取回。
     *
     * 与 getBands8() 的区别：getBands8() 读的是混音之后的输出（在音频回调里算，
     * 输入是 A+B 淡化后的和），交叉淡化期间它并不代表 B 轨。这份是 B 轨解码线程
     * 里单独算的，与 A 轨级联完全隔离。B 轨尚未开始解码时返回 null。
     */
    private external fun nativeGetBandsB(out: FloatArray): Int
    fun getBandsB(): FloatArray? = try {
        val tmp = FloatArray(8)
        if (nativeGetBandsB(tmp) == 8) tmp else null
    } catch (_: Throwable) { null }

    /** 重置峰值统计（切歌时调用） */
    fun resetClipStats() {
        try { nativeResetClipStats() } catch (_: Throwable) {}
    }

    /** 【V7.08】静音测试：强制输出静音（验证音频路径） */
    fun setSilenceTest(enabled: Boolean) {
        try { nativeSetSilenceTest(enabled) } catch (_: Throwable) {}
    }

    /** 【V7.09】正弦波自检：播放 440Hz 测试音验证 Oboe 是否工作 */
    fun setSineTest(enabled: Boolean) {
        try { nativeSetSineTest(enabled) } catch (_: Throwable) {}
    }

    /** 【V7.24】正弦测试是否运行中 */
    fun isSineTestRunning(): Boolean {
        return try { nativeIsSineTestRunning() } catch (_: Throwable) { false }
    }

    /** 【V7.08】DSP Close时累计的采样数（>0 = Oboe 在工作但 DSP Close） */
    fun getDspDisabledSampleCount(): Long {
        return try { nativeGetDspDisabledSamples() } catch (_: Throwable) { 0L }
    }

    fun getClipDebugInfo(): String {
        val callbacks = try { nativeGetCallbackCount() } catch (_: Throwable) { 0L }
        val ratio = getClipRatio()
        val count = getClipCount()
        val total = getTotalSampleCount().toLong()
        val dspDisabled = getDspDisabledSampleCount()
        val status = when {
            callbacks == 0L -> "\uD83D\uDD34 Oboe回调从未触发"
            total == 0L -> "\uD83D\uDD34 DSP采样=0（解码器未输出）"
            dspDisabled > 0 && total == dspDisabled -> "\u26A0\uFE0F DSPClose（Oboe正常）"
            dspDisabled > 0 -> "\u26A0\uFE0F DSP部分Close"
            ratio > 0.05f -> "\u26A0\uFE0F 增益过高"
            ratio > 0.001f -> "\u2705 正常"
            ratio > 0f -> "\uD83D\uDD12 保护过度"
            else -> "\u2014 未触发"
        }
        val sineStatus = if (total > 0L) "\uD83D\uDD0A 正弦波自检中" else ""
        val base = "Calls:$callbacks | Clip:$count/$total (${String.format("%.2f", ratio * 100)}%) $status"
        return if (sineStatus.isNotEmpty()) "$base\n$sineStatus" else base
    }

    // ============================================================================
    // 【V7.80】5段图形Equalizer预设
    // ============================================================================

    /**
     * 应用 5 段 EQ 预设到 DSP
     * @param gainsDb 5 个频段的增益值，单位 dB，对应频率 [60, 230, 910, 3600, 14000] Hz
     */
    fun setDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray? = null) {
        try {
            nativeSetDspEq5Band(gainsDb, freqsHz)
            Log.i(TAG, "DSP 5-band EQ preset applied")
        } catch (e: Throwable) {
            Log.e(TAG, "setDspEq5Band error: ${e.message}")
        }
    }

    /** 清除 5 段 EQ 预设，恢复默认 DSP Mode */
    fun resetDspEq5Band() {
        try {
            nativeResetDspEq5Band()
            Log.i(TAG, "DSP 5-band EQ preset cleared")
        } catch (_: Throwable) {}
    }

    // 【V7.200】MSEB activation guard — C++ will refuse to overwrite 5-band EQ when MSEB is active
    fun setMsebActive(active: Boolean) {
        try {
            nativeSetMsebActive(active)
        } catch (_: Throwable) {}
    }

    // 【V8.2】MSEB 10-band subjective EQ（11 主观维度 → 10 biquads，crossfade 无感切换）
    fun setMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray) {
        try {
            nativeSetMseb10Band(gainsDb, freqsHz, qValues)
            Log.i(TAG, "MSEB 10-band applied")
        } catch (e: Throwable) {
            Log.e(TAG, "setMseb10Band error: ${e.message}")
        }
    }

    fun resetMseb10Band() {
        try {
            nativeResetMseb10Band()
        } catch (_: Throwable) {}
    }

    // 【V8.3】M/S 声场（跨声道矩阵）
    fun setMsStage(soundstage: Float, imaging: Float) {
        try {
            nativeSetMsStage(soundstage, imaging)
        } catch (_: Throwable) {}
    }

    /**
     * 【V8.172】DTS 环绕包围强度与高度感。
     *
     * surround 0..10 → 0..0.8：环绕反射层的整体增益。旧版把环绕量绑死在"宽度"上，
     * 实际回灌能量只有 0.01~0.04（听不出来）；现在独立成旋钮，默认 5（≈0.4）。
     * height 0..10 → 0..1：耳廓共振深度 + 左右耳权重差，决定是"只在两侧变宽"
     * 还是"真的听得出前后"。默认 5。
     */
    fun setDtsStage(surround: Float, height: Float) {
        try {
            nativeSetDtsStage(surround, height)
        } catch (_: Throwable) {}
    }

    /** 【V8.172】把当前 A2DP codec 名（"SBC"/"AAC"/"LDAC"…）下发给 native，null = 非蓝牙。 */
    fun setDtsBtProfile(codec: String?) {
        try {
            nativeSetDtsBtProfile(codec)
        } catch (_: Throwable) {}
    }

    fun resetMsStage() {
        try {
            nativeResetMsStage()
        } catch (_: Throwable) {}
    }

    // 【V8.3】瞬态整形（impulseResponse 维度映射，-1..+1）
    fun setTransient(amount: Float) {
        try {
            nativeSetTransient(amount)
        } catch (_: Throwable) {}
    }

    fun resetTransient() {
        try {
            nativeResetTransient()
        } catch (_: Throwable) {}
    }

    // 【V8.3】动态压缩（Master Bus Compressor，独立全局模块）
    fun setCompressorEnabled(enabled: Boolean) {
        try {
            nativeSetCompressorEnabled(enabled)
        } catch (_: Throwable) {}
    }

    fun setCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) {
        try {
            nativeSetCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
        } catch (_: Throwable) {}
    }

    // 【V8.3】等响补偿（ISO 226）
    fun setLoudnessEnabled(enabled: Boolean) {
        try {
            nativeSetLoudnessEnabled(enabled)
        } catch (_: Throwable) {}
    }

    fun setLoudnessIntensity(intensity: Float) {
        try {
            nativeSetLoudnessIntensity(intensity)
        } catch (_: Throwable) {}
    }

    /** 【V8.170】响度统一管理：元数据折算的整枚增益（dB，±24 内）。 */
    fun setNormalizationGain(db: Float) {
        try {
            nativeSetNormalizationGain(db)
        } catch (_: Throwable) {}
    }

    /** 【V8.210】诊断用：回读 native 里真正生效的归一化增益（dB）。 */
    fun getNormalizationGainDb(): Float = try { nativeGetNormalizationGain() } catch (_: Throwable) { 0f }
    private external fun nativeGetNormalizationGain(): Float

    // 【V8.3】Crossfeed（消除头中效应，仅 Oboe，amount 0~1）
    fun setCrossfeed(amount: Float) {
        try {
            nativeSetCrossfeed(amount)
        } catch (_: Throwable) {}
    }

    fun resetCrossfeed() {
        try {
            nativeResetCrossfeed()
        } catch (_: Throwable) {}
    }

    // ── 【V8.170】新 DSP 模块（空间混响 / 动态 EQ / 多频段限幅 / AutoEQ 强度）──
    // 全部走 Oboe native 链；callbacks 里每个模块都有 enabled 短路，无需额外 reset。

    /** 程序化空间混响开关。wet=0 时 native 仍逐样本判断，建议同时给 wet。 */
    fun setSpaceReverbEnabled(enabled: Boolean) {
        mSrEnabled = enabled
        try { nativeSetSpaceReverbEnabled(enabled) } catch (_: Throwable) {}
    }

    fun setSpaceReverbWet(wet: Float) {
        mSrWet = wet
        try { nativeSetSpaceReverbWet(wet) } catch (_: Throwable) {}
    }

    /** roomSize 0(小房间)~1(教堂)，damping 0(亮)~1(暗) */
    fun setSpaceReverbShape(roomSize: Float, damping: Float) {
        mSrRoom = roomSize
        mSrDamp = damping
        try { nativeSetSpaceReverbShape(roomSize, damping) } catch (_: Throwable) {}
    }

    fun setDynamicEqEnabled(enabled: Boolean) {
        mDeEnabled = enabled
        try { nativeSetDynamicEqEnabled(enabled) } catch (_: Throwable) {}
    }

    /** depth 0=旁路(逐样本直通) ~ 1  */
    fun setDynamicEqDepth(depth: Float) {
        mDeDepth = depth
        try { nativeSetDynamicEqDepth(depth) } catch (_: Throwable) {}
    }

    /** threshold 0~1，压到该电平以下不再压 */
    fun setDynamicEqThreshold(threshold: Float) {
        mDeThresh = threshold
        try { nativeSetDynamicEqThreshold(threshold) } catch (_: Throwable) {}
    }

    fun setMultiBandLimiterEnabled(enabled: Boolean) {
        mMblEnabled = enabled
        try { nativeSetMultiBandLimiterEnabled(enabled) } catch (_: Throwable) {}
    }

    /** AutoEQ 修正强度混合比 0(原声)~1(完整修正) */
    fun setAutoEqMix(mix: Float) {
        mAutoEqMix = mix
        try { nativeSetAutoEqMix(mix) } catch (_: Throwable) {}
    }

    // ── 当前值回读（UI 快照用；native 用 atomic 存，直接读）──
    private var mSrEnabled = false
    private var mSrWet = 0f
    private var mSrRoom = 0.5f
    private var mSrDamp = 0.5f
    private var mDeEnabled = false
    private var mDeDepth = 0f
    private var mDeThresh = 0.25f
    private var mMblEnabled = true
    private var mAutoEqMix = 1f

    fun getSpaceReverbEnabled(): Boolean = mSrEnabled
    fun getSpaceReverbWet(): Float = mSrWet
    fun getSpaceReverbRoom(): Float = mSrRoom
    fun getSpaceReverbDamping(): Float = mSrDamp
    fun getDynamicEqEnabled(): Boolean = mDeEnabled
    fun getDynamicEqDepth(): Float = mDeDepth
    fun getDynamicEqThreshold(): Float = mDeThresh
    fun getMultiBandLimiterEnabled(): Boolean = mMblEnabled
    fun getAutoEqMix(): Float = mAutoEqMix

    // 【V7.86】AutoEQ 10-band — On源 AutoEQ 项目风格耳机修正
    /**
     * 应用 10-band AutoEQ 预设
     * @param gainsDb 10 段增益 (dB)
     * @param freqsHz 10 段中心频率 (Hz)
     * @param qValues 10 段 Q 值
     * @param filterTypes 0=Peaking, 1=HighShelf, 2=LowShelf
     * @param preampDb AutoEQ 推荐 preamp (通常负值)
     */
    fun setAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float) {
        try {
            nativeSetAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preampDb)
            Log.i(TAG, "AutoEQ 10-band applied: preamp=${preampDb}dB")
        } catch (e: Throwable) {
            Log.e(TAG, "setAutoEq10Band error: ${e.message}")
        }
    }

    /** 清除 AutoEQ 预设，恢复默认 DSP Mode */
    fun resetAutoEq() {
        try {
            nativeResetAutoEq()
            Log.i(TAG, "AutoEQ cleared, default DSP restored")
        } catch (_: Throwable) {}
    }

    // 【V7.05】夜间模式 — softClip 阈值降低，声音温润厚实
    fun setNightMode(enabled: Boolean) {
        try {
            nativeSetNightMode(enabled)
            Log.i(TAG, "Night mode: $enabled")
        } catch (e: Throwable) {
            Log.e(TAG, "setNightMode error: ${e.message}")
        }
    }

    fun isNightMode(): Boolean {
        return try { nativeIsNightMode() } catch (_: Throwable) { false }
    }

    // [V8.119] 宽裕余量档开关（EDM/Hi-Res 宽容模式）
    fun setWideHeadroom(enabled: Boolean) {
        try { nativeSetWideHeadroom(enabled) } catch (e: Throwable) {
            Log.e(TAG, "setWideHeadroom error: ${e.message}")
        }
    }

    // [V8.119] 原声直通开关
    fun setDirectPassthrough(enabled: Boolean) {
        try { nativeSetDirectPassthrough(enabled) } catch (e: Throwable) {
            Log.e(TAG, "setDirectPassthrough error: ${e.message}")
        }
    }

    /**
     * 【V8.313】左右声道平衡。bal: -1=全左 / 0=居中 / +1=全右。
     * native 侧走恒功率（等功率声像）曲线，往一边偏时总功率不变 → 响度不变。
     */
    fun setStereoBalance(bal: Float) {
        val b = bal.coerceIn(-1f, 1f)
        try { nativeSetStereoBalance(b) } catch (e: Throwable) {
            Log.e(TAG, "setStereoBalance error: ${e.message}")
        }
    }

    /** 【V8.177】自动增益（AGC）：把不同曲目/频段的电平拉到同一档。默认关。 */
    fun setAgcEnabled(enabled: Boolean) {
        try { nativeSetAgcEnabled(enabled) } catch (e: Throwable) {
            Log.e(TAG, "setAgcEnabled error: ${e.message}")
        }
    }

    fun toggleNightMode() {
        setNightMode(!isNightMode())
    }

    // 【V7.05】TPDF Dither On关
    fun setDitherEnabled(enabled: Boolean) {
        try {
            nativeSetDitherEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setDitherEnabled error: ${e.message}")
        }
    }

    fun isDitherEnabled(): Boolean {
        return try { nativeIsDitherEnabled() } catch (_: Throwable) { true }
    }

    // 【V7.05】DC Blocker On关
    fun setDcBlockEnabled(enabled: Boolean) {
        try {
            nativeSetDcBlockEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "setDcBlockEnabled error: ${e.message}")
        }
    }

    fun isDcBlockEnabled(): Boolean {
        return try { nativeIsDcBlockEnabled() } catch (_: Throwable) { true }
    }

    // 【2026-09-07】A2DP 编码前预补偿（蓝牙 SBC/AAC 高频瞬态补偿）
    fun setBtPreEmphasis(enabled: Boolean, codecDb: Float) {
        try {
            nativeSetBtPreEmphasis(enabled, codecDb)
        } catch (e: Throwable) {
            Log.e(TAG, "setBtPreEmphasis error: ${e.message}")
        }
    }

    // 【V8.295】主动按默认路由重建输出流（蓝牙断开热插拔）。后台线程调用，避免主线程卡顿。
    fun reopenOutputOnRouteReady() {
        try {
            nativeReopenOutputOnRouteReady()
        } catch (e: Throwable) {
            Log.e(TAG, "reopenOutputOnRouteReady error: ${e.message}")
        }
    }

    // ============================================================================
    // 【V7.30】Brand Presets移植 — 28个品牌调音预设（待实现）
    // ============================================================================
    // Brand Presets功能待后续版本实现

    // ============================================================================
    // 【V7.10】回调计数诊断 — Oboe 是否工作
    // ============================================================================

    /** 获取 Oboe 回调计数（>0 表示 Oboe 正在工作） */
    fun getCallbackCount(): Long {
        return try { nativeGetCallbackCount() } catch (_: Throwable) { 0L }
    }

    /** 重置回调计数 */
    fun resetCallbackCount() {
        try { nativeResetCallbackCount() } catch (_: Throwable) {}
    }

    /** 【V7.10】完整诊断信息 */
    fun getOboeDebugInfo(): String {
        val callbacks = getCallbackCount()
        val total = getTotalSampleCount().toLong()
        val clipRatio = getClipRatio()
        val dspDisabled = getDspDisabledSampleCount()
        val prepared = isPrepared
        val playing = isPlaying
        
        val status = when {
            callbacks == 0L -> "\uD83D\uDD34 Oboe回调从未触发（音频系统异常）"
            total == 0L -> "\uD83D\uDD34 DSP处理了0采样（解码器未输出）"
            dspDisabled > 0 && total == dspDisabled -> "\u26A0\uFE0F DSPClose（Oboe工作正常）"
            dspDisabled > 0 -> "\u26A0\uFE0F DSP部分Close"
            clipRatio > 0.05f -> "\u26A0\uFE0F 增益过高"
            clipRatio > 0.001f -> "\u2705 正常"
            else -> "\uD83D\uDD12 保护过度"
        }
        
        return buildString {
            appendLine("\uD83C\uDFA7 Oboe诊断:")
            appendLine("  回调次数: $callbacks")
            appendLine("  总采样: $total")
            appendLine("  DSPClose采样: $dspDisabled")
            appendLine("  削波比例: ${"%.2f".format(clipRatio * 100)}%")
            appendLine("  准备状态: $prepared, Playing: $playing")
            appendLine("  Native库加载: $nativeLibLoaded")
            appendLine("  流错误码: ${getStreamError()}")
            appendLine("")
            appendLine("\uD83D\uDC0D 解码器:")
            appendLine("  解码线程: ${if (isDecoderThreadRunning()) "\u2705运行" else "\u274C停止"}")
            appendLine("  输出帧数: ${getDecoderFramesOutput()}")
            appendLine("  输出格式: ${if (isDecoderFloat()) "Float" else "PCM16"}")
            appendLine("  RingBuffer: ${getRingBufferFill()}")
            appendLine("  Underrun: ${getUnderrunCount()}")  // 【V7.39】
            appendLine("  Open步骤: ${getOpenStep()}/8")
            appendLine("  错误码: ${getOpenErrorCode()}")
            appendLine("  状态: $status")
            appendLine("")
            appendLine("\uD83D\uDCCB 播放流程:")
            appendLine("  ${MusicService.oboeFlowTrace}")
            appendLine("")
            appendLine("\uD83C\uDFA4 采样率:")
            val fileRate = getSampleRate()
            val streamRate = getStreamSampleRate()
            appendLine("  文件: ${fileRate}Hz | 流: ${streamRate}Hz")
            appendLine("  声道: ${getChannelCount()}")
            // 【V7.27】速率诊断
            try { nativeGetSpeedDiag()?.let { appendLine("  ⏱ 速率诊断: $it") } } catch (_: Throwable) {}
            if (fileRate != streamRate) {
                appendLine("  ⚠️ 文件/流采样率不匹配！可能加速")
            }
        }
    }

    // 【V7.10】Native 方法声明
    private external fun nativeGetCallbackCount(): Long
    private external fun nativeResetCallbackCount()
    private external fun nativeGetStreamError(): Int
    private external fun nativeIsDecoderThreadRunning(): Boolean
    private external fun nativeGetDecoderFramesOutput(): Int
    private external fun nativeGetRingBufferFill(): Int
    private external fun nativeIsDecoderFloat(): Boolean
    private external fun nativeGetStreamSampleRate(): Int
    private external fun nativeGetSpeedDiag(): String?  // 【V7.27】速率诊断

    /** 获取流采样率 */
    fun getStreamSampleRate(): Int {
        return try { nativeGetStreamSampleRate() } catch (_: Throwable) { 0 }
    }

    /** 获取流错误码 */
    fun getStreamError(): Int {
        return try { nativeGetStreamError() } catch (_: Throwable) { 0 }
    }

    /** 解码器线程是否运行 */
    fun isDecoderThreadRunning(): Boolean {
        return try { nativeIsDecoderThreadRunning() } catch (_: Throwable) { false }
    }

    /** 解码器输出帧数 */
    fun getDecoderFramesOutput(): Int {
        return try { nativeGetDecoderFramesOutput() } catch (_: Throwable) { 0 }
    }

    /** Ring buffer 填充量 */
    fun getRingBufferFill(): Int {
        return try { nativeGetRingBufferFill() } catch (_: Throwable) { 0 }
    }

    /** 解码器是否输出 float */
    fun isDecoderFloat(): Boolean {
        return try { nativeIsDecoderFloat() } catch (_: Throwable) { false }
    }

    /** nativeOpen 执行步骤 */
    fun getOpenStep(): Int {
        return try { nativeGetOpenStep() } catch (_: Throwable) { 0 }
    }

    /** nativeOpen 错误码 */
    fun getOpenErrorCode(): Int {
        return try { nativeGetOpenErrorCode() } catch (_: Throwable) { 0 }
    }

    /** 【V7.39】RingBuffer underrun 次数 */
    fun getUnderrunCount(): Long {
        return try { nativeGetUnderrunCount() } catch (_: Throwable) { 0L }
    }

    fun resetUnderrunCount() {
        try { nativeResetUnderrunCount() } catch (_: Throwable) {}
    }

    private external fun nativeGetUnderrunCount(): Long
    private external fun nativeResetUnderrunCount()

    private external fun nativeGetOpenStep(): Int
    private external fun nativeGetOpenErrorCode(): Int

    // ================= 【V8.263】NeuralMix stem 总线 =================

    @Volatile private var stemFeeder: Thread? = null

    /**
     * 开启 stem 模式（清空四轨环）。
     * 【V8.283】不再要求流采样率 = 44.1k：分轨文件恒为 44.1k，与流率不一致时由
     * native 在 push 时线性重采样（旧实现在 48k 音源上直接跳过，等于分轨总线永久关闭）。
     */
    fun beginStemMode(): Boolean {
        val rate = try { nativeGetSampleRate() } catch (_: Throwable) { 0 }
        if (rate <= 0) { Log.i(TAG, "stem mode skipped: stream rate unknown ($rate)"); return false }
        return try {
            nativeStemSetSrcRate(STEM_FILE_RATE)
            nativeStemReset()
            nativeStemSetMode(true)
            stemModeOn = true
            true
        } catch (t: Throwable) { Log.e(TAG, "beginStemMode: ${t.message}"); false }
    }

    fun endStemMode() {
        stopStemFeeder()
        try { nativeStemSetMode(false) } catch (_: Throwable) {}
        stemModeOn = false
    }

    /** 【V8.308】分轨总线是否已接管混合。native 的轨级 FX 模板块被 `!stemMode` 跳过，
     *  调用方据此提示「模板被分轨接管」而不是让用户以为模板坏了。
     *  （Kotlin 侧镜像 native 的 g_stemMode，省一个 JNI 往返；仅用于日志/提示，不参与控制。） */
    @Volatile var stemModeOn: Boolean = false
        private set

    /**
     * 【V8.282】启动 stem 供数线程：从 4 条 PFD 流同步读取 **i16le** 立体声块推入 native 环。
     * skipBytes = B 轨入场偏移（entryMs/1000 × 44100 × 4），先 seek 再供数。
     *
     * 【V8.282 修复】旧实现把 4 条流轮流读进**同一个** buf，每读一条就把上一条覆盖掉，
     * 循环结束后 buf 里只剩第 4 条（other），却把它推给了全部四个 stem ——
     * 总线一旦点亮，四轨其实都是 other 叠四遍，人声/鼓/贝斯的分轨意义完全丢失。
     * 现在每条流一个独立缓冲，各推各的轨。
     */
    fun startStemFeeder(fds: List<android.os.ParcelFileDescriptor>, skipBytes: Long = 0L) {
        stopStemFeeder()
        val t = Thread {
            var streams: List<java.io.InputStream>? = null
            try {
                streams = fds.map { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
                if (skipBytes > 0) {
                    for (s in streams) {
                        var left = skipBytes
                        while (left > 0) {
                            val n = s.skip(left)
                            if (n <= 0) break
                            left -= n
                        }
                    }
                    Log.i(TAG, "stem feeder skipped ${skipBytes / 1024}KB x${streams.size} (entry offset)")
                }
                val bytesPerFrame = 4                       // 2ch × 2B（i16le）
                val blockFrames = 8192
                val bufs = streams.map { ByteArray(blockFrames * bytesPerFrame) }
                val bbs = bufs.map {
                    java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                }
                val floats = FloatArray(blockFrames * 2)
                var eof = false
                var pushedFrames = 0
                while (!eof && !Thread.currentThread().isInterrupted) {
                    var frames = Int.MAX_VALUE
                    for (i in streams.indices) {
                        val buf = bufs[i]
                        var off = 0
                        while (off < buf.size) {
                            val n = streams[i].read(buf, off, buf.size - off)
                            if (n < 0) { eof = true; break }
                            off += n
                        }
                        if (off < buf.size) eof = true
                        frames = minOf(frames, off / bytesPerFrame)
                    }
                    if (frames <= 0) break
                    val samples = frames * 2
                    for (i in streams.indices) {
                        val bb = bbs[i]
                        bb.rewind()
                        for (k in 0 until samples) floats[k] = bb.getShort().toFloat() * (1f / 32768f)
                        nativeStemPush(i, floats, samples)
                    }
                    pushedFrames += frames
                }
                Log.i(TAG, "stem feeder done (${pushedFrames} frames = ${pushedFrames * 1000L / 44100L}ms)")
            } catch (t: Throwable) {
                Log.w(TAG, "stem feeder: ${t.message}")
            } finally {
                streams?.forEach { try { it.close() } catch (_: Throwable) {} }
            }
        }.apply {
            name = "stem-feeder"
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
        stemFeeder = t
    }

    fun stopStemFeeder() {
        stemFeeder?.interrupt()
        stemFeeder = null
    }

    private external fun nativeStemSetMode(enabled: Boolean)
    private external fun nativeStemReset()
    private external fun nativeStemSetSrcRate(rate: Int)
    private external fun nativeSetStemFxTarget(target: Int, mode: Int)
    private external fun nativeStemPush(stemId: Int, data: FloatArray, samples: Int): Int
}



