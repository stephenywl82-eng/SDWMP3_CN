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
    private external fun nativeGetPositionMs(): Long
    private external fun nativeGetDurationMs(): Long
    private external fun nativeIsEos(): Boolean
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetChannelCount(): Int
    private external fun nativeIsExclusive(): Boolean
    private external fun nativeIsSharedMode(): Boolean

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
    // 【V8.3】瞬态整形 JNI
    private external fun nativeSetTransient(amount: Float)
    private external fun nativeResetTransient()
    // 【V8.3】动态压缩 JNI（Master Bus Compressor）
    private external fun nativeSetCompressorEnabled(enabled: Boolean)
    private external fun nativeSetCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float)
    // 【V7.86】AutoEQ 10-band JNI
    private external fun nativeSetAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float)
    private external fun nativeResetAutoEq()
    private external fun nativeSetNightMode(enabled: Boolean)
    private external fun nativeIsNightMode(): Boolean
    private external fun nativeSetDitherEnabled(enabled: Boolean)
    private external fun nativeIsDitherEnabled(): Boolean
    private external fun nativeSetDcBlockEnabled(enabled: Boolean)
    private external fun nativeIsDcBlockEnabled(): Boolean

    // 【V7.30】Brand Presets移植

    fun open(filePath: String): Boolean {
        Log.i(TAG, "Opening: $filePath")
        
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
            } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Exception in resume: ${e.message}")
            return false
        }
    }

    /**
     * Seek to position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        if (!isPrepared) return
        try {
            nativeSeekTo(positionMs * 1000) // ms → us
        } catch (e: Exception) {
            Log.e(TAG, "Exception in seekTo: ${e.message}")
        }
    }

    /**
     * Stop playback and release all resources.
     */
    fun stop() {
        // 【V7.01】先中断 monitor 线程并等待其结束，防止竞态条件
        val wasPlaying = isPlaying
        isPlaying = false  // 先Settings标志，让 monitor 线程退出循环
        monitorThread?.interrupt()
        try {
            monitorThread?.join(500)  // 等待最多 500ms
        } catch (_: Exception) {}
        monitorThread = null
        
        try {
            nativeStop()
        } catch (e: Exception) {
            Log.e(TAG, "Exception in stop: ${e.message}")
        }
        isPrepared = false
        if (wasPlaying) {
            onPlayStateChanged?.invoke(false)
        }
        currentFilePath = null
        // 【V7.21】Close FD
        try { parcelFd?.close() } catch (_: Exception) {}
        parcelFd = null
    }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPositionMs(): Long {
        if (!isPrepared) return 0
        return try { nativeGetPositionMs() } catch (_: Exception) { 0 }
    }

    /**
     * Get duration in milliseconds.
     */
    fun getDurationMs(): Long {
        if (!isPrepared) return 0
        return try { nativeGetDurationMs() } catch (_: Exception) { 0 }
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
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    // ---- Diagnostics ----

    fun getSampleRate(): Int = if (isPrepared) try { nativeGetSampleRate() } catch (_: Exception) { 0 } else 0
    fun getChannelCount(): Int = if (isPrepared) try { nativeGetChannelCount() } catch (_: Exception) { 0 } else 0
    fun isExclusiveMode(): Boolean = if (isPrepared) try { nativeIsExclusive() } catch (_: Exception) { false } else false

    /** Get current sharing mode: true = Shared, false = Exclusive */
    fun isSharedMode(): Boolean = try { nativeIsSharedMode() } catch (_: Exception) { false }

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
        } catch (e: Exception) {
            Log.e(TAG, "DSP EQ error: ${e.message}")
        }
    }

    /** Reset DSP EQ filter state (call on seek/track change) */
    fun resetDspEq() {
        try {
            nativeResetDspEq()
        } catch (_: Exception) {}
    }

    // 【v6.29】独立 DSP On关
    fun setDspEnabled(enabled: Boolean) {
        try {
            nativeSetDspEnabled(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setDspEnabled error: ${e.message}")
        }
    }

    fun isDspEnabled(): Boolean {
        return try {
            nativeIsDspEnabled()
        } catch (_: Exception) {
            false
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "setSampleRateNative error: ${e.message}")
        }
    }

    /** 【V3.2.7】设置输出设备（USB DAC Port ID，0=系统默认） */
    fun setOutputDevice(deviceId: Int) {
        try {
            nativeSetOutputDeviceId(deviceId)
            Log.i(TAG, "Output device set: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "setOutputDevice error: ${e.message}")
        }
    }

    /** 获取已Settings的原生采样率 */
    fun getSampleRateNative(): Int {
        return try { nativeGetSampleRateNative() } catch (_: Exception) { 0 }
    }

    // ============================================================================
    // 【V7.0】峰值检测 Debug
    // ============================================================================

    /**
     * 获取软削波触发比例（0.0 ~ 1.0）
     * 解读：> 5% = 增益过高，0.1%~5% = 刚好，< 0.1% = 保护过度
     */
    fun getClipRatio(): Float {
        return try { nativeGetClipRatio() } catch (_: Exception) { 0f }
    }

    fun getClipCount(): Int {
        return try { nativeGetClipCount() } catch (_: Exception) { 0 }
    }

    fun getTotalSampleCount(): Float {
        return try { nativeGetTotalSamples() } catch (_: Exception) { 0f }
    }

    /** 实时 RMS 振幅（0~1），Oboe 回调计算，用于节拍可视化 */
    fun getRmsLevel(): Float {
        return try { nativeGetRmsLevel() } catch (_: Exception) { 0f }
    }

    /** 实时频谱分频能量（0~1），Oboe 回调双二阶滤波计算 */
    fun getBandSub(): Float = try { nativeGetBandSub() } catch (_: Exception) { 0f }
    fun getBandBass(): Float = try { nativeGetBandBass() } catch (_: Exception) { 0f }
    fun getBandMid(): Float = try { nativeGetBandMid() } catch (_: Exception) { 0f }
    fun getBandHigh(): Float = try { nativeGetBandHigh() } catch (_: Exception) { 0f }
    // 8-band raw FFT
    fun getBand0(): Float = try { nativeGetBand0() } catch (_: Exception) { 0f }
    fun getBand1(): Float = try { nativeGetBand1() } catch (_: Exception) { 0f }
    fun getBand2(): Float = try { nativeGetBand2() } catch (_: Exception) { 0f }
    fun getBand3(): Float = try { nativeGetBand3() } catch (_: Exception) { 0f }
    fun getBand4(): Float = try { nativeGetBand4() } catch (_: Exception) { 0f }
    fun getBand5(): Float = try { nativeGetBand5() } catch (_: Exception) { 0f }
    fun getBand6(): Float = try { nativeGetBand6() } catch (_: Exception) { 0f }
    fun getBand7(): Float = try { nativeGetBand7() } catch (_: Exception) { 0f }
    /** All 8 bands as FloatArray for FFT display */
    fun getBands8(): FloatArray = try {
        floatArrayOf(nativeGetBand0(), nativeGetBand1(), nativeGetBand2(), nativeGetBand3(),
                     nativeGetBand4(), nativeGetBand5(), nativeGetBand6(), nativeGetBand7())
    } catch (_: Exception) { FloatArray(8) }

    /** 重置峰值统计（切歌时调用） */
    fun resetClipStats() {
        try { nativeResetClipStats() } catch (_: Exception) {}
    }

    /** 【V7.08】静音测试：强制输出静音（验证音频路径） */
    fun setSilenceTest(enabled: Boolean) {
        try { nativeSetSilenceTest(enabled) } catch (_: Exception) {}
    }

    /** 【V7.09】正弦波自检：播放 440Hz 测试音验证 Oboe 是否工作 */
    fun setSineTest(enabled: Boolean) {
        try { nativeSetSineTest(enabled) } catch (_: Exception) {}
    }

    /** 【V7.24】正弦测试是否运行中 */
    fun isSineTestRunning(): Boolean {
        return try { nativeIsSineTestRunning() } catch (_: Exception) { false }
    }

    /** 【V7.08】DSP Close时累计的采样数（>0 = Oboe 在工作但 DSP Close） */
    fun getDspDisabledSampleCount(): Long {
        return try { nativeGetDspDisabledSamples() } catch (_: Exception) { 0L }
    }

    fun getClipDebugInfo(): String {
        val callbacks = try { nativeGetCallbackCount() } catch (_: Exception) { 0L }
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
        } catch (e: Exception) {
            Log.e(TAG, "setDspEq5Band error: ${e.message}")
        }
    }

    /** 清除 5 段 EQ 预设，恢复默认 DSP Mode */
    fun resetDspEq5Band() {
        try {
            nativeResetDspEq5Band()
            Log.i(TAG, "DSP 5-band EQ preset cleared")
        } catch (_: Exception) {}
    }

    // 【V7.200】MSEB activation guard — C++ will refuse to overwrite 5-band EQ when MSEB is active
    fun setMsebActive(active: Boolean) {
        try {
            nativeSetMsebActive(active)
        } catch (_: Exception) {}
    }

    // 【V8.2】MSEB 10-band subjective EQ（11 主观维度 → 10 biquads，crossfade 无感切换）
    fun setMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray) {
        try {
            nativeSetMseb10Band(gainsDb, freqsHz, qValues)
            Log.i(TAG, "MSEB 10-band applied")
        } catch (e: Exception) {
            Log.e(TAG, "setMseb10Band error: ${e.message}")
        }
    }

    fun resetMseb10Band() {
        try {
            nativeResetMseb10Band()
        } catch (_: Exception) {}
    }

    // 【V8.3】M/S 声场（跨声道矩阵）
    fun setMsStage(soundstage: Float, imaging: Float) {
        try {
            nativeSetMsStage(soundstage, imaging)
        } catch (_: Exception) {}
    }

    fun resetMsStage() {
        try {
            nativeResetMsStage()
        } catch (_: Exception) {}
    }

    // 【V8.3】瞬态整形（impulseResponse 维度映射，-1..+1）
    fun setTransient(amount: Float) {
        try {
            nativeSetTransient(amount)
        } catch (_: Exception) {}
    }

    fun resetTransient() {
        try {
            nativeResetTransient()
        } catch (_: Exception) {}
    }

    // 【V8.3】动态压缩（Master Bus Compressor，独立全局模块）
    fun setCompressorEnabled(enabled: Boolean) {
        try {
            nativeSetCompressorEnabled(enabled)
        } catch (_: Exception) {}
    }

    fun setCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) {
        try {
            nativeSetCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
        } catch (_: Exception) {}
    }

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
        } catch (e: Exception) {
            Log.e(TAG, "setAutoEq10Band error: ${e.message}")
        }
    }

    /** 清除 AutoEQ 预设，恢复默认 DSP Mode */
    fun resetAutoEq() {
        try {
            nativeResetAutoEq()
            Log.i(TAG, "AutoEQ cleared, default DSP restored")
        } catch (_: Exception) {}
    }

    // 【V7.05】夜间模式 — softClip 阈值降低，声音温润厚实
    fun setNightMode(enabled: Boolean) {
        try {
            nativeSetNightMode(enabled)
            Log.i(TAG, "Night mode: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "setNightMode error: ${e.message}")
        }
    }

    fun isNightMode(): Boolean {
        return try { nativeIsNightMode() } catch (_: Exception) { false }
    }

    fun toggleNightMode() {
        setNightMode(!isNightMode())
    }

    // 【V7.05】TPDF Dither On关
    fun setDitherEnabled(enabled: Boolean) {
        try {
            nativeSetDitherEnabled(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setDitherEnabled error: ${e.message}")
        }
    }

    fun isDitherEnabled(): Boolean {
        return try { nativeIsDitherEnabled() } catch (_: Exception) { true }
    }

    // 【V7.05】DC Blocker On关
    fun setDcBlockEnabled(enabled: Boolean) {
        try {
            nativeSetDcBlockEnabled(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "setDcBlockEnabled error: ${e.message}")
        }
    }

    fun isDcBlockEnabled(): Boolean {
        return try { nativeIsDcBlockEnabled() } catch (_: Exception) { true }
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
        return try { nativeGetCallbackCount() } catch (_: Exception) { 0L }
    }

    /** 重置回调计数 */
    fun resetCallbackCount() {
        try { nativeResetCallbackCount() } catch (_: Exception) {}
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
            try { nativeGetSpeedDiag()?.let { appendLine("  ⏱ 速率诊断: $it") } } catch (_: Exception) {}
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
        return try { nativeGetStreamSampleRate() } catch (_: Exception) { 0 }
    }

    /** 获取流错误码 */
    fun getStreamError(): Int {
        return try { nativeGetStreamError() } catch (_: Exception) { 0 }
    }

    /** 解码器线程是否运行 */
    fun isDecoderThreadRunning(): Boolean {
        return try { nativeIsDecoderThreadRunning() } catch (_: Exception) { false }
    }

    /** 解码器输出帧数 */
    fun getDecoderFramesOutput(): Int {
        return try { nativeGetDecoderFramesOutput() } catch (_: Exception) { 0 }
    }

    /** Ring buffer 填充量 */
    fun getRingBufferFill(): Int {
        return try { nativeGetRingBufferFill() } catch (_: Exception) { 0 }
    }

    /** 解码器是否输出 float */
    fun isDecoderFloat(): Boolean {
        return try { nativeIsDecoderFloat() } catch (_: Exception) { false }
    }

    /** nativeOpen 执行步骤 */
    fun getOpenStep(): Int {
        return try { nativeGetOpenStep() } catch (_: Exception) { 0 }
    }

    /** nativeOpen 错误码 */
    fun getOpenErrorCode(): Int {
        return try { nativeGetOpenErrorCode() } catch (_: Exception) { 0 }
    }

    /** 【V7.39】RingBuffer underrun 次数 */
    fun getUnderrunCount(): Long {
        return try { nativeGetUnderrunCount() } catch (_: Exception) { 0L }
    }

    fun resetUnderrunCount() {
        try { nativeResetUnderrunCount() } catch (_: Exception) {}
    }

    private external fun nativeGetUnderrunCount(): Long
    private external fun nativeResetUnderrunCount()

    private external fun nativeGetOpenStep(): Int
    private external fun nativeGetOpenErrorCode(): Int
}



