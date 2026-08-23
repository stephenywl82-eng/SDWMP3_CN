package com.sdw.music.player.core.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.sdw.music.player.DacProfile
import com.sdw.music.player.Song
import java.util.concurrent.ConcurrentHashMap

data class UsbDacDevice(val name: String, val vid: Int, val pid: Int)
data class UsbEndpointInfo(val address: Int, val maxPacketSize: Int, val interval: Int, val isUac2: Boolean)

object UsbDacManager {
    private const val TAG = "UsbDacManager"
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIOCONTROL = 1
    private const val SUBCLASS_AUDIOSTREAMING = 2
    private const val PROTOCOL_UAC2 = 0x20
    private const val ACTION_USB_PERMISSION = "com.sdw.music.player.USB_PERMISSION"
    private const val FIND_DACS_COOLDOWN_MS = 5000L

    @Volatile private var isNativeLoaded = false
    @Volatile private var contextRef: Context? = null
    @Volatile private var permissionReceiver: BroadcastReceiver? = null
    @Volatile private var streaming = false
    @Volatile private var initAttempted = false
    @Volatile private var cachedUsbDevice: UsbDevice? = null
    private var lastFindDacsTime = 0L
    @Volatile private var dumpedDeviceId = -1

    private data class PendingClaim(
        val device: UsbDevice,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val ifaceNum: Int
    )
    @Volatile private var pendingClaim: PendingClaim? = null

    // ============================================================
    // Public API
    // ============================================================

    fun init(context: Context) {
        if (isNativeLoaded || initAttempted) return
        initAttempted = true
        val appCtx = context.applicationContext
        contextRef = appCtx

        DebugLog.v(TAG, "init: loading native lib oboe_bridge...")
        try {
            System.loadLibrary("oboe_bridge")
            isNativeLoaded = true
            DebugLog.add(TAG, "init: oboe_bridge loaded OK")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            DebugLog.add(TAG, "init: oboe_bridge FAIL ${e.message}")
        }

        registerPermissionReceiver(appCtx)
        DebugLog.v(TAG, "init done, nativeLoaded=$isNativeLoaded")
    }

    fun findDacs(): List<UsbDacDevice> {
        val now = System.currentTimeMillis()
        if (cachedUsbDevice != null && (now - lastFindDacsTime) < FIND_DACS_COOLDOWN_MS) {
            DebugLog.v(TAG, "findDacs: cached → ${cachedUsbDevice!!.productName}")
            return listOf(UsbDacDevice(
                name = cachedUsbDevice!!.productName ?: "USB DAC",
                vid = cachedUsbDevice!!.vendorId,
                pid = cachedUsbDevice!!.productId
            ))
        }

        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "findDacs: contextRef=null"); return emptyList() }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "findDacs: usbManager=null"); return emptyList() }

        val dacs = mutableListOf<UsbDacDevice>()
        DebugLog.v(TAG, "findDacs: scanning ${usbMgr.deviceList.size} USB devices...")
        for ((_, dev) in usbMgr.deviceList) {
            val audio = isAudioDevice(dev)
            DebugLog.v(TAG, "findDacs: vid=${dev.vendorId.toString(16)} pid=${dev.productId.toString(16)} name=${dev.productName} ifaceCount=${dev.interfaceCount} audio=$audio")
            if (audio) {
                cachedUsbDevice = dev
                maybeDumpStructure(dev)
                dacs.add(UsbDacDevice(
                    name = dev.productName ?: "USB DAC (${dev.vendorId}:${dev.productId})",
                    vid = dev.vendorId, pid = dev.productId
                ))
            }
        }
        lastFindDacsTime = now
        DebugLog.add(TAG, "findDacs: result=${dacs.size} DAC(s) found")
        return dacs
    }

    fun getDacDevice(): UsbDevice? = cachedUsbDevice

    fun requestPermission(device: UsbDevice, context: Context) {
        val ctx = context.applicationContext
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (usbMgr.hasPermission(device)) {
            DebugLog.v(TAG, "perm: already have for ${device.productName}")
            return
        }
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usbMgr.requestPermission(device, pi)
        DebugLog.add(TAG, "perm: requesting for ${device.productName}")
    }

    fun claimAndStart(
        device: UsbDevice, sampleRate: Int, channels: Int, bitsPerSample: Int, ifaceNum: Int = -1
    ): Boolean {
        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "claim: contextRef=null"); return false }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "claim: usbManager=null"); return false }

        if (!usbMgr.hasPermission(device)) {
            pendingClaim = PendingClaim(device, sampleRate, channels, bitsPerSample, ifaceNum)
            DebugLog.add(TAG, "claim: no permission, queued")
            requestPermission(device, ctx)
            return false
        }

        if (!isNativeLoaded) { DebugLog.add(TAG, "claim: native not loaded"); return false }

        val conn = usbMgr.openDevice(device)
        if (conn == null) { DebugLog.add(TAG, "claim: openDevice FAIL"); return false }
        maybeDumpRaw(conn, device.deviceId)  // raw config + tSamFreq (before any claim/SETINTERFACE)

        val fd = conn.fileDescriptor

        // Dump all interfaces for debug
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            DebugLog.v(TAG, "claim: iface[$i] id=${iface.id} class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
        }

        // Find and claim audio streaming interfaces
        var targetIfaceNum = ifaceNum   // loop INDEX, used by getEndpointInfo
        var targetIfaceId = -1          // bInterfaceNumber, used by native USBDEVFS_SETINTERFACE
        var claimed = false
        // 【V3.2.7】先强制 claim AudioControl 接口(subclass=1)--SET_CUR 时钟命令的目标是
        // 控制接口上的 clock entity,不踢掉内核驱动对控制接口的占用,SET_CUR 永远 EBUSY。
        // Salt Player (libsaltusbaudio.so) 同款做法:全部强制 claim,系统 audio patch 会切回 SPEAKER。
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL) {
                val ok = conn.claimInterface(iface, true)
                DebugLog.v(TAG, "claim: force-claimed AudioControl iface id=${iface.id} → $ok")
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                // Only claim the OUT (playback) streaming interface - skip alt=0 (idle) and IN (capture) ifaces.
                // Bug fix: previously ALL streaming ifaces were claimed and targetIfaceId was overwritten
                // each pass, ending on the IN iface (id=2) → native set alt on the wrong interface →
                // ISO OUT ep 0x01 never activated → submitUrbRaw ENOENT.
                val hasOutEp = (0 until iface.endpointCount).any {
                    iface.getEndpoint(it).direction == android.hardware.usb.UsbConstants.USB_DIR_OUT
                }
                DebugLog.v(TAG, "claim: iface[$i] id=${iface.id} epCount=${iface.endpointCount} hasOutEp=$hasOutEp")
                if (!hasOutEp) continue
                if (targetIfaceNum < 0) targetIfaceNum = i
                if (targetIfaceId < 0) {
                    targetIfaceId = iface.id   // first OUT iface's bInterfaceNumber - never overwrite
                    conn.claimInterface(iface, true)
                    DebugLog.add(TAG, "claim: claimed OUT iface id=${iface.id} (index=$i)")
                    claimed = true
                }
            }
        }

        if (!claimed) { DebugLog.add(TAG, "claim: no audio streaming iface"); conn.close(); return false }

        val epInfo = getEndpointInfo(device, targetIfaceNum)
        if (epInfo == null) { DebugLog.add(TAG, "claim: no ISO OUT endpoint"); conn.close(); return false }

        val result = nativeClaim(
            device.vendorId, device.productId, fd,
            epInfo.address, epInfo.maxPacketSize, epInfo.interval,
            epInfo.isUac2, targetIfaceId
        )
        DebugLog.add(TAG, "claim: nativeClaim vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} ep=0x${epInfo.address.toString(16)} mps=${epInfo.maxPacketSize} uac2=${epInfo.isUac2} iface=$targetIfaceNum → $result")
        if (result) {
            // 【FU 覆盖】Kotlin 层 DacProfile 是单一数据源；固件不实现 GET_MIN/GET_MAX 的怪 DAC
            // 在 native 通用探测失败后，由这里注入 Salt-verified 的 Feature Unit 参数。
            val profile = DacProfile.find(device.vendorId, device.productId)
            if (profile.featureUnitId > 0) {
                nativeSetFeatureUnitOverride(
                    profile.featureUnitId, profile.featureUnitChannel,
                    profile.featureUnitChannels,
                    profile.featureUnitMinDb, profile.featureUnitMaxDb, profile.featureUnitResDb
                )
            }
        }
        if (!result) conn.close()
        return result
    }

    // V3.3.4: actual open-stream params for player-screen DAC info bar
    @JvmStatic @Volatile var activeSampleRate: Int = 0
    @JvmStatic @Volatile var activeBits: Int = 0

    fun startStreaming(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (!isNativeLoaded) { DebugLog.add(TAG, "start: native not loaded"); return false }
        if (!nativeIsClaimed()) { DebugLog.add(TAG, "start: not claimed"); return false }
        val result = nativeUsbStart(sampleRate, channels, bitsPerSample)
        streaming = result
        if (result) { activeSampleRate = sampleRate; activeBits = bitsPerSample }
        // 【V3.2.7】重 claim 后 native 驱动 volume_ 重置为 1.0,每次开流重新应用当前音量
        if (result) nativeSetVolume(currentVolume)
        DebugLog.add(TAG, "start: sr=$sampleRate ch=$channels bits=$bitsPerSample → $result (vol=$currentVolume)")
        return result
    }

    fun pushPcm(data: FloatArray, frameCount: Int): Int {
        // 【V3.2.7】claim 后即可写入(预缓冲阶段 streaming 还是 false,之前直接 -1 导致预缓冲全被丢弃)
        if (!isNativeLoaded || !nativeIsClaimed()) return -1
        return nativePushPcm(data, frameCount)
    }

    /** 【V3.2.7】ring buffer 当前帧数(EOS 排空用) */
    fun getRingFill(): Int {
        if (!isNativeLoaded) return 0
        return try { nativeGetRingFill() } catch (_: Throwable) { 0 }
    }

    fun stopAndRelease() {
        streaming = false; pendingClaim = null
        activeSampleRate = 0; activeBits = 0
        if (isNativeLoaded) { nativeStop(); nativeRelease() }
        DebugLog.add(TAG, "stopAndRelease")
    }

    /** Pause DAC stream thread without releasing USB claim or ring buffer */
    fun pauseStream() {
        streaming = false
        if (isNativeLoaded) nativeStopThreadOnly()  // stop streamLoop, keep USB claim
        DebugLog.v(TAG, "pauseStream (threads stopped, USB claim kept)")
    }

    /** Reset ring buffer positions before resume streaming - avoids stale data corruption */
    fun resetRingBuffer() {
        if (isNativeLoaded) nativeResetRingBuffer()
        DebugLog.v(TAG, "resetRingBuffer")
    }

    // 【V3.3.0】libFLAC 硬解封装:解码线程在 native,直入 ring buffer
    // 【V3.3.5】NDK fopen 不支持 UTF-8 路径（中文名→false），复制到 ASCII 临时路径绕过
    private var flacTempPath: String? = null
    private fun getFlacTempDir(): java.io.File {
        val ctx = contextRef ?: return java.io.File("/data/local/tmp")
        return java.io.File(ctx.cacheDir, "flac_temp").also { it.mkdirs() }
    }
    
    fun flacOpen(path: String): Boolean {
        if (!isNativeLoaded) { DebugLog.add(TAG, "flacOpen: native not loaded"); return false }
        return try {
            // 【V3.3.5】纯 ASCII 路径直接用；含非 ASCII 字符则复制到 cache/flac_temp/
            val openPath = if (path.all { it.code <= 0x7F }) {
                path
            } else {
                val tmp = java.io.File(getFlacTempDir(), "f_${path.hashCode().toUInt().toString(16)}.flac")
                if (!tmp.exists() || tmp.length() != java.io.File(path).length()) {
                    DebugLog.add(TAG, "flacOpen: copying UTF-8 file to ASCII temp \"${tmp.name}\"")
                    java.io.File(path).copyTo(tmp, overwrite = true)
                }
                flacTempPath = tmp.absolutePath
                tmp.absolutePath
            }
            val ok = nativeFlacOpen(openPath)
            DebugLog.add(TAG, "flacOpen(\"${path.takeLast(80)}\") = $ok")
            ok
        } catch (e: Throwable) {
            DebugLog.add(TAG, "flacOpen EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
    /** [sampleRate, channels, bits, durationMs] */
    fun flacInfo(): IntArray {
        if (!isNativeLoaded) return intArrayOf(0,0,0,0)
        return try {
            val info = nativeFlacInfo()
            DebugLog.add(TAG, "flacInfo: ${info.contentToString()}")
            info
        } catch (e: Throwable) {
            DebugLog.add(TAG, "flacInfo EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            intArrayOf(0,0,0,0)
        }
    }
    fun flacStart(): Boolean =
        isNativeLoaded && try { nativeFlacStart() } catch (_: Throwable) { false }
    fun flacPause(paused: Boolean) { if (isNativeLoaded) try { nativeFlacPause(paused) } catch (_: Throwable) {} }
    fun flacSeek(ms: Long) { if (isNativeLoaded) try { nativeFlacSeek(ms) } catch (_: Throwable) {} }
    fun flacStop() {
        if (isNativeLoaded) try { nativeFlacStop() } catch (_: Throwable) {}
        // 【V3.3.5】清理 ASCII 临时文件
        flacTempPath?.let { try { java.io.File(it).delete() } catch (_: Throwable) {} }; flacTempPath = null
    }
    fun flacIsEos(): Boolean = isNativeLoaded && try { nativeFlacIsEos() } catch (_: Throwable) { true }
    fun flacPositionMs(): Long = if (isNativeLoaded) try { nativeFlacPositionMs() } catch (_: Throwable) { 0L } else 0L
    /** Gapless 切曲:优先调 native flacGaplessSeek(支持跨文件);fallback 同文件内用 native flacSeek
     *  原理:同 FLAC 相邻轨共用一个 decoder,decoder 已在 running 状态,直接 seekSamples 即可 */
    fun flacGaplessSeek(path: String, targetSample: Long): Boolean {
        if (!isNativeLoaded) return false
        // 换算 sample → ms(近似,decoder 会自行 seek 精确位置)
        val info = flacInfo()
        val sr = info[0]
        if (sr <= 0) { try { nativeFlacGaplessSeek(path, targetSample) } catch (_: Throwable) { /* ignore */ }; return false }
        val targetMs = (targetSample * 1000L) / sr
        return try {
            // 同文件直接 seek(decoder 已 open 不关闭,seekSamples 会被 decode 线程消费)
            nativeFlacGaplessSeek(path, targetSample); true
        } catch (_: Throwable) {
            // fallback:强制 seek ms(decoder 已在 running,pendingSeekMs 会被处理)
            try { nativeFlacSeek(targetMs); true } catch (_: Throwable) { false }
        }
    }
    /** FLAC 总采样时长(ms) */
    fun flacTotalSamples(): Long {
        val info = flacInfo(); return if (info[3] > 0) info[3].toLong() else 0L
    }

    /**
     * 读 FLAC 内嵌歌词（Vorbis comment LYRICS / UNSYNCEDLYRICS 字段）。
     * 返回 UTF-8 歌词文本；无内嵌歌词或非 FLAC 返回 null。
     * 独立 open/close，不触碰解码状态，线程安全。
     * 懒加载：非 DAC 模式下 oboe_bridge 可能未加载（init 只在 USB 独占时调用），
     * 这里通过 init(context) 确保 native 库加载 + contextRef 设置，但不启动 USB 驱动。
     */
    fun flacReadLyrics(context: Context, path: String): String? {
        if (!isNativeLoaded) {
            init(context)
            if (!isNativeLoaded) return null
        }
        return try {
            // 【V3.3.5】纯 ASCII 路径直接用；含非 ASCII 字符则复制到 cache/flac_temp/（NDK fopen 不支持 UTF-8）
            val openPath = if (path.all { it.code <= 0x7F }) {
                path
            } else {
                val tmp = java.io.File(getFlacTempDir(), "lyrics_${path.hashCode().toUInt().toString(16)}.flac")
                try {
                    java.io.File(path).copyTo(tmp, overwrite = true)
                    tmp.absolutePath
                } catch (_: Throwable) {
                    android.util.Log.w("FlacLyrics", "copyTo tmp failed")
                    null
                }
            }
            if (openPath == null) return null
            android.util.Log.d("FlacLyrics", "flacReadLyrics: openPath=$openPath")
            val result = try { nativeFlacReadLyrics(openPath) } catch (e: Throwable) {
                android.util.Log.w("FlacLyrics", "nativeFlacReadLyrics throw: ${e.message}")
                null
            }
            android.util.Log.d("FlacLyrics", "flacReadLyrics: result=${result?.length ?: -1} chars")
            // 清理临时文件
            if (openPath != path) { try { java.io.File(openPath).delete() } catch (_: Throwable) {} }
            result
        } catch (_: Throwable) {
            null
        }
    }

    fun isStreaming(): Boolean = streaming && isNativeLoaded && nativeIsClaimed()
    // V3.3.3: claim held (stream may be paused after EOS keep-claim)
    fun isClaimed(): Boolean = isNativeLoaded && nativeIsClaimed()
    fun getUnderrunCount(): Int = if (isNativeLoaded) nativeGetUnderrunCount() else 0
    fun getDacName(): String? = if (isNativeLoaded) nativeGetDacName() else null

    // [V3.3.4] robust display name: native dacName_ is often empty -> prefer UsbDevice.productName
    fun getDacDisplayName(): String {
        val prod = try { cachedUsbDevice?.productName } catch (_: Throwable) { null }
        if (!prod.isNullOrBlank()) return prod.trim()
        val n = try { getDacName() } catch (_: Throwable) { null }
        return if (n.isNullOrBlank() || n == "No DAC") "USB DAC" else n
    }

    // [V3.3.6] 直接从 native 读取,避免 activeSampleRate 缓存问题
    fun queryActiveSampleRate(): Int =
        if (isNativeLoaded) try { nativeGetCurrentSampleRate() } catch (_: Throwable) { 0 } else 0
    fun queryActiveBits(): Int =
        if (isNativeLoaded) try { nativeGetCurrentBits() } catch (_: Throwable) { 0 } else 0
    fun getDetailedDacInfo(): String? = if (isNativeLoaded) nativeGetDetailedInfo() else null
    fun getSupportedRates(): String = if (isNativeLoaded) (nativeGetSupportedRates() ?: "") else ""
    fun getNativeDebugLog(): String? = if (isNativeLoaded) nativeGetDebugLog() else null
    fun getStats(): String? = if (isNativeLoaded) nativeGetStats() else null

    fun setVolume(v: Float) {
        // 【V3.2.8】立方 audio taper：线性 1/15 档 = -23dB 对灵敏耳机仍很响；
        // v3 后最小档 ≈ -70dB，音量曲线接近人耳感知
        val pct = v.coerceIn(0f, 1f)
        currentVolume = pct * pct * pct
        DebugLog.add(TAG, "setVolume: input=$v cubic=$currentVolume claimed=${isClaimed()}")
        if (isNativeLoaded) nativeSetVolume(currentVolume)
    }
    fun getVolume(): Float = currentVolume
    fun setDitherEnabled(enabled: Boolean) { if (isNativeLoaded) nativeSetDitherEnabled(enabled) }

    // ── DAC 链路 MSEB / 图形 EQ（与 Oboe 共用同一套 Biquad）──
    fun setDspEnabled(enabled: Boolean) { if (isNativeLoaded) nativeSetDspEnabled(enabled) }
    fun setDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray?) {
        if (isNativeLoaded) try { nativeSetDspEq5Band(gainsDb, freqsHz) } catch (_: Throwable) {}
    }
    fun resetDspEq5Band() { if (isNativeLoaded) try { nativeResetDspEq5Band() } catch (_: Throwable) {} }

    // 【V8.2】MSEB 10-band subjective EQ
    fun setMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray) {
        if (isNativeLoaded) try { nativeSetMseb10Band(gainsDb, freqsHz, qValues) } catch (_: Throwable) {}
    }
    fun resetMseb10Band() { if (isNativeLoaded) try { nativeResetMseb10Band() } catch (_: Throwable) {} }

    // 【V8.3】AutoEQ 10-band 耳机修正（任意频点 + PK/HS/LS），与 MSEB 并存叠加。
    fun setAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float) {
        if (isNativeLoaded) try { nativeSetAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preampDb) } catch (_: Throwable) {}
    }
    fun resetAutoEq() { if (isNativeLoaded) try { nativeResetAutoEq() } catch (_: Throwable) {} }

    // 【V8.3】M/S 声场（跨声道矩阵）
    fun setMsStage(soundstage: Float, imaging: Float) {
        if (isNativeLoaded) try { nativeSetMsStage(soundstage, imaging) } catch (_: Throwable) {}
    }
    fun resetMsStage() { if (isNativeLoaded) try { nativeResetMsStage() } catch (_: Throwable) {} }

    fun setTransient(amount: Float) { if (isNativeLoaded) try { nativeSetTransient(amount) } catch (_: Throwable) {} }
    fun resetTransient() { if (isNativeLoaded) try { nativeResetTransient() } catch (_: Throwable) {} }

    // 【V8.3】动态压缩（Master Bus Compressor，独立全局模块）
    fun setCompressorEnabled(enabled: Boolean) { if (isNativeLoaded) try { nativeSetCompressorEnabled(enabled) } catch (_: Throwable) {} }
    fun setCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) {
        if (isNativeLoaded) try { nativeSetCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupDb) } catch (_: Throwable) {}
    }

    @Volatile private var currentVolume = 0.7f
    fun getSafeDacInfo(): String {
        if (!isNativeLoaded) return "Native driver not loaded"
        return nativeGetDetailedInfo()?.lines()?.filter {
            !it.contains("/dev/") && !it.contains("fd=")
        }?.joinToString("\n") ?: "No DAC connected"
    }

    fun getNativeLog(): String {
        return if (isNativeLoaded) nativeGetDebugLog() ?: "" else "Native not loaded"
    }

    /**
     * Read source file sample rate via MediaExtractor (no decode).
     */
    fun getSourceSampleRate(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank()) return 48000
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            48000
        } finally { ex.release() }
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun maybeDumpStructure(device: UsbDevice) {
        val id = device.deviceId
        if (id == dumpedDeviceId) return
        dumpedDeviceId = id
        dumpStructure(device)
    }

    private fun dumpStructure(device: UsbDevice) {
        DebugLog.v(TAG, "=== USB DESCRIPTOR STRUCTURE vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} name=${device.productName} ===")
        DebugLog.v(TAG, "bcdDevice=${device.version} devCls=${device.deviceClass} devSub=${device.deviceSubclass} devProto=${device.deviceProtocol} ifaceCount=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val sb = StringBuilder()
            sb.append("iface[$i] id=${iface.id} alt=${iface.alternateSetting} cls=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                sb.append(" | ep$j addr=0x${ep.address.toString(16)} type=${ep.type} dir=${ep.direction} mps=${ep.maxPacketSize} interval=${ep.interval}")
            }
            DebugLog.add(TAG, sb.toString())
        }
    }

    /** Full DAC diagnostic dump (DebugLog.add, visible in Settings panel) */
    fun dumpDacInfo(device: UsbDevice, audioManager: android.media.AudioManager? = null) {
        DebugLog.add(TAG, "======== DAC DIAGNOSTIC START ========")
        DebugLog.add(TAG, "USB: vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} name=${device.productName}")
        DebugLog.add(TAG, "USB: version=${device.version} devClass=${device.deviceClass} devSub=${device.deviceSubclass} devProto=${device.deviceProtocol} ifaceCount=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val sb = StringBuilder()
            sb.append("iface[$i] id=${iface.id} alt=${iface.alternateSetting} cls=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                sb.append(" | ep$j addr=0x${ep.address.toString(16)} type=${ep.type} dir=${ep.direction} mps=${ep.maxPacketSize} interval=${ep.interval}")
            }
            DebugLog.add(TAG, sb.toString())
        }
        // Android AudioDeviceInfo
        if (audioManager != null) {
            val outDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val usbOuts = outDevices.filter { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE }
            if (usbOuts.isNotEmpty()) {
                for (d in usbOuts) {
                    DebugLog.add(TAG, "AudioDevice: id=${d.id} type=${d.type} name=${d.productName} addr=${d.address}")
                    DebugLog.add(TAG, "AudioDevice: sampleRates=${d.sampleRates?.toList()} channels=${d.channelCounts?.toList()} encodings=${d.encodings?.toList()}")
                }
            } else {
                DebugLog.add(TAG, "AudioDevice: no USB output device found (HAL may not expose)")
            }
        }
        DebugLog.add(TAG, "======== DAC DIAGNOSTIC END ========")
    }

    @Volatile private var dumpedRawId = -1
    private fun maybeDumpRaw(conn: UsbDeviceConnection, id: Int) {
        // V3.3.4: descriptor dump disabled - GET_DESCRIPTOR config transfer hung 2s (read=-1)
        // right before a device shutdown (suspected USB stack kernel crash). Diagnostic only, not needed.
        return
    }

    private fun dumpConfigDescriptor(conn: UsbDeviceConnection) {
        try {
            // Standard USB GET_DESCRIPTOR control request (bmRequestType=0x80 IN)
            val devBuf = ByteArray(18)
            val devLen = conn.controlTransfer(
                0x80, 0x06, 0x0100, 0,
                devBuf, devBuf.size, 1000
            )
            DebugLog.v(TAG, "GET_DESCRIPTOR device read=$devLen")
            hexDump(devBuf, devLen)
            // Config descriptor header (9 bytes) -> wTotalLength at bytes [2..3]
            val hdr = ByteArray(9)
            val hdrLen = conn.controlTransfer(
                0x80, 0x06, 0x0200, 0,
                hdr, hdr.size, 1000
            )
            if (hdrLen >= 9) {
                val total = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                DebugLog.v(TAG, "config wTotalLength=$total")
                val cfgBuf = ByteArray(total.coerceAtMost(4096))
                val cfgLen = conn.controlTransfer(
                    0x80, 0x06, 0x0200, 0,
                    cfgBuf, cfgBuf.size, 2000
                )
                DebugLog.v(TAG, "GET_DESCRIPTOR config read=$cfgLen")
                hexDump(cfgBuf, cfgLen)
            } else {
                DebugLog.v(TAG, "config header read=$hdrLen (FAILED)")
            }
        } catch (e: Exception) {
            DebugLog.add(TAG, "dumpConfigDescriptor FAILED: ${e.message}")
        }
    }

    private fun hexDump(buf: ByteArray, len: Int) {
        val n = if (len < 0) buf.size else len.coerceAtMost(buf.size)
        val sb = StringBuilder()
        for (i in 0 until n) {
            sb.append(String.format("%02X ", buf[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) { DebugLog.v(TAG, "RAW: $sb"); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) DebugLog.v(TAG, "RAW: $sb")
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
            if (iface.interfaceClass == 0x10 &&
                (iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL ||
                 iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING)) return true
        }
        return false
    }

    private fun getEndpointInfo(device: UsbDevice, targetIfaceId: Int): UsbEndpointInfo? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (i != targetIfaceId) continue  // match by index, not UsbInterface.id (may be garbage)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                val uac2 = iface.interfaceProtocol == PROTOCOL_UAC2
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT) {
                        return UsbEndpointInfo(ep.address, ep.maxPacketSize, ep.interval, uac2 || ep.maxPacketSize > 1024 || ep.interval > 1)
                    }
                }
            }
        }
        return null
    }

    private fun registerPermissionReceiver(context: Context) {
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                DebugLog.add(TAG, "perm result: granted=$granted device=${device?.productName}")
                if (granted && device != null) {
                    val claim = pendingClaim
                    if (claim != null && claim.device.deviceId == device.deviceId) {
                        pendingClaim = null
                        claimAndStart(device, claim.sampleRate, claim.channels, claim.bitsPerSample, claim.ifaceNum)
                    }
                } else { pendingClaim = null }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnsafeRegisteredReceiver") context.registerReceiver(receiver, filter)
        permissionReceiver = receiver
    }

    // ============================================================
    // JNI externals
    // ============================================================

    private external fun nativeUsbAvailable(): Boolean
    private external fun nativeClaim(vid: Int, pid: Int, fd: Int, address: Int, maxPacketSize: Int, epInterval: Int, isUac2: Boolean, ifaceNum: Int): Boolean
    private external fun nativeUsbStart(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean
    private external fun nativePushPcm(data: FloatArray, frameCount: Int): Int
    private external fun nativeGetRingFill(): Int
    private external fun nativeStop()
    private external fun nativeResetRingBuffer()
    private external fun nativeStopThreadOnly()
    private external fun nativeRelease()
    private external fun nativeIsClaimed(): Boolean
    private external fun nativeGetUnderrunCount(): Int
    private external fun nativeGetDacName(): String?
    private external fun nativeGetCurrentSampleRate(): Int
    private external fun nativeGetCurrentBits(): Int
    private external fun nativeGetDetailedInfo(): String?
    private external fun nativeGetSupportedRates(): String?
    private external fun nativeGetStats(): String?
    private external fun nativeGetDebugLog(): String?
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeSetDitherEnabled(enabled: Boolean)
    private external fun nativeSetFeatureUnitOverride(unitId: Int, channel: Int, channels: Int, minDb: Float, maxDb: Float, resDb: Float)
    private external fun nativeSetDspEnabled(enabled: Boolean)
    private external fun nativeSetDspEq5Band(gainsDb: FloatArray, freqsHz: FloatArray?)
    private external fun nativeResetDspEq5Band()
    private external fun nativeSetMseb10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray)
    private external fun nativeResetMseb10Band()
    private external fun nativeSetAutoEq10Band(gainsDb: FloatArray, freqsHz: FloatArray, qValues: FloatArray, filterTypes: IntArray, preampDb: Float)
    private external fun nativeResetAutoEq()
    private external fun nativeSetMsStage(soundstage: Float, imaging: Float)
    private external fun nativeResetMsStage()
    private external fun nativeSetTransient(amount: Float)
    private external fun nativeResetTransient()
    private external fun nativeSetCompressorEnabled(enabled: Boolean)
    private external fun nativeSetCompressorParams(thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float)
    private external fun nativeForceReset()
    fun forceReset() { if (isNativeLoaded) nativeForceReset() }

    // 【V3.3.0】native libFLAC 硬解(绕开 Moto MediaCodec 24bit→16bit 降级)
    private external fun nativeFlacOpen(path: String): Boolean
    private external fun nativeFlacInfo(): IntArray
    private external fun nativeFlacStart(): Boolean
    private external fun nativeFlacPause(paused: Boolean)
    private external fun nativeFlacSeek(ms: Long)
    private external fun nativeFlacStop()
    private external fun nativeFlacIsEos(): Boolean
    private external fun nativeFlacPositionMs(): Long
    private external fun nativeFlacGaplessSeek(path: String, targetSample: Long): Boolean
    private external fun nativeFlacTotalSamples(): Long
private external fun nativeFlacReadLyrics(path: String): String?
}
