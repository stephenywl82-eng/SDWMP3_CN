package com.sdw.music.player.core.audio.engine

import com.sdw.music.player.core.audio.DebugLog
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NeriPlayer USB 独占会话（移植）—— 一个实例 = 一个原生 session handle。
 *
 * 原生源码来自 NeriPlayer（https://github.com/cwuom/NeriPlayer，作者 cwuom / ouom），
 * 按 NeriPlayer Native Attribution License 获得闭源使用授权；内嵌 libusb 为 LGPL-2.1。
 *
 * 与旧引擎（usb_audio_driver.cpp）的差异：
 * - 设备打开后原生侧自行 claim 接口 / 协商 UAC 格式 / 选择 feedback 端点，
 *   Kotlin 不再做 claimInterface 舞蹈；
 * - PCM 经 PcmPipeline（环形缓冲 + 重采样 + 位深转换 + 软件增益）进入 ISO 传输，
 *   环形缓冲跨曲目保持，切歌只 flush 不拆流 —— 这正是旧引擎爆音的根除点；
 * - 显式 feedback 时钟闭环 + 恢复 latch，长播不掉流。
 */
class NeriUsbSession {

    @Volatile var handle: Long = 0L
        private set

    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    private var directBuf: ByteBuffer? = null

    /** 当前 prepare 的输入格式（PCM 写入按此解释） */
    private var inputChannels: Int = 2
    private var inputSampleRate: Int = 48000
    private var inputEncoding: Int = AudioFormat.ENCODING_PCM_FLOAT

    /** 打开时协商出的输出格式（来自 runtimeReport） */
    @Volatile var outputSampleRate: Int = 0
        private set
    @Volatile var outputChannels: Int = 0
        private set
    @Volatile var outputBits: Int = 0
        private set
    @Volatile var outputSubslotBytes: Int = 0
        private set
    @Volatile var uacVersion: String = ""
        private set

    val isOpen: Boolean get() = handle != 0L

    /**
     * 打开设备并协商输出格式。
     * @param sampleRate  期望输出采样率（0/负=按源）
     * @param bitsPerSample 期望输出位深（0=自动）
     */
    fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int = 0
    ): Boolean {
        close()
        if (!NeriUsbNativeBridge.ensureLoaded()) return false
        if (!usbManager.hasPermission(device)) {
            DebugLog.add(TAG, "open: no permission for ${device.productName}")
            return false
        }
        val conn = try {
            usbManager.openDevice(device)
        } catch (e: Throwable) {
            DebugLog.add(TAG, "open: openDevice threw ${e.message}")
            null
        } ?: run {
            DebugLog.add(TAG, "open: openDevice FAIL (null)")
            return false
        }
        // subslotBytes 未显式指定时按位深派生(24bit→3、32bit→4)。原生 nativeOpen 在 subslot=0 时
        // 会默认成 2(=16bit 线宽)，导致 24bit 请求因 bits(24) > subslot*8(16) 被拒 → 只能退回 16bit。
        // 这里按 UAC2 标准显式传对线宽，使 24bit/48k 能真正开到 DAC 的 24bit alt(对齐旧引擎)。
        val effBits = if (bitsPerSample > 0) bitsPerSample else 0
        val effSubslot = when {
            subslotBytes > 0 -> subslotBytes
            effBits > 0 -> (effBits + 7) / 8
            else -> 0
        }
        val h = NeriUsbNativeBridge.open(
            conn,
            if (sampleRate > 0) sampleRate else 0,
            if (channelCount > 0) channelCount else 2,
            effBits,
            effSubslot
        )
        if (h == 0L) {
            DebugLog.add(TAG, "open: nativeOpen FAIL → ${NeriUsbNativeBridge.lastOpenError()}")
            try { conn.close() } catch (_: Throwable) {}
            return false
        }
        handle = h
        connection = conn
        refreshOutputFormat()
        DebugLog.add(TAG, "open: ok handle=$h out=${outputSampleRate}/${outputChannels}ch/${outputBits}bit uac=$uacVersion")
        return true
    }

    /** 从 runtimeReport 取回协商好的输出格式 */
    fun refreshOutputFormat() {
        if (handle == 0L) return
        val report = NeriUsbReport.parse(NeriUsbNativeBridge.runtimeReport(handle))
        outputSampleRate = report.sampleRate
        outputChannels = report.channels
        outputBits = report.bits
        outputSubslotBytes = report.subslotBytes
        uacVersion = report.uacVersion
    }

    /** 声明输入 PCM 格式（切歌 / 格式变化时调用） */
    fun preparePlayerPcm(inputSampleRate: Int, inputChannelCount: Int, inputEncoding: Int = AudioFormat.ENCODING_PCM_FLOAT): Boolean {
        if (handle == 0L) return false
        val ok = NeriUsbNativeBridge.preparePlayerPcm(handle, inputSampleRate, inputChannelCount, inputEncoding)
        if (ok) {
            this.inputSampleRate = inputSampleRate
            this.inputChannels = inputChannelCount
            this.inputEncoding = inputEncoding
        }
        return ok
    }

    /** 输出格式变化（如 44.1k→48k）时重新协商 */
    fun reconfigureOutput(sampleRate: Int, channelCount: Int, bitsPerSample: Int, subslotBytes: Int = 0): Boolean {
        if (handle == 0L) return false
        val ok = NeriUsbNativeBridge.reconfigurePlayerPcmOutput(handle, sampleRate, channelCount, bitsPerSample, subslotBytes)
        if (ok) refreshOutputFormat()
        return ok
    }

    /**
     * 写入 float 交织 PCM。环形满时短暂等待后重试（与旧引擎 pushPcm 语义一致）。
     * @return 接受的帧数；负数 = 错误（-1 会话无效 / -2 阻塞超时）
     */
    fun writeFloats(data: FloatArray, frameCount: Int, volume: Float): Int {
        if (handle == 0L || frameCount <= 0) return -1
        var framesLeft = frameCount
        var floatOffset = 0
        val deadline = System.nanoTime() + STALL_TIMEOUT_NS
        // 背压指数退避：ring 满时从 2ms 起、翻倍到 20ms 封顶；一旦有写入立即复位。
        // 灭屏听歌时 USB 消费持续慢于生产(常态背压)，固定 2ms 轮询会把 CPU 拽在高频、
        // 实测 ~550mAh/h；退避后唤醒频率 500Hz→50Hz，CPU 可进 idle，预计功耗降 30-50%。
        var backoffNs = 2L * 1_000_000L
        while (framesLeft > 0) {
            val buf = ensureDirectBuffer(framesLeft * inputChannels * 4)
            buf.clear()
            val floatsToPut = framesLeft * inputChannels
            // FloatBuffer view 直接批量拷贝，避免逐样本
            buf.asFloatBuffer().put(data, floatOffset, floatsToPut)
            val bytes = floatsToPut * 4
            buf.limit(bytes)
            buf.position(0)
            val written = NeriUsbNativeBridge.writePlayerPcm(handle, buf, 0, bytes, volume)
            if (written <= 0) {
                if (System.nanoTime() > deadline) {
                    DebugLog.add(TAG, "writeFloats: STALL ${framesLeft}f left (ring full >${STALL_TIMEOUT_NS / 1_000_000}ms)")
                    return -2
                }
                try { Thread.sleep(backoffNs / 1_000_000L) } catch (_: InterruptedException) { return -1 }
                backoffNs = minOf(backoffNs * 2, 20L * 1_000_000L)
                continue
            }
            val frames = written / (4 * inputChannels)
            if (frames <= 0) {
                if (System.nanoTime() > deadline) return -2
                try { Thread.sleep(backoffNs / 1_000_000L) } catch (_: InterruptedException) { return -1 }
                backoffNs = minOf(backoffNs * 2, 20L * 1_000_000L)
                continue
            }
            backoffNs = 2L * 1_000_000L  // 有进展：复位退避，保持起播/补写灵敏
            floatOffset += frames * inputChannels
            framesLeft -= frames
        }
        return frameCount
    }

    private fun ensureDirectBuffer(bytes: Int): ByteBuffer {
        var buf = directBuf ?: ByteBuffer.allocateDirect(maxOf(bytes, 64 * 1024)).order(ByteOrder.nativeOrder())
        if (buf.capacity() < bytes) {
            buf = ByteBuffer.allocateDirect(maxOf(bytes, 64 * 1024)).order(ByteOrder.nativeOrder())
        }
        directBuf = buf
        return buf
    }

    fun startPlayerPcm(): Boolean = handle != 0L && NeriUsbNativeBridge.startPlayerPcm(handle)
    fun play(): Boolean = handle != 0L && NeriUsbNativeBridge.playPlayerPcm(handle)
    fun pause(): Boolean = handle != 0L && NeriUsbNativeBridge.pausePlayerPcm(handle)
    fun flush(): Boolean = handle != 0L && NeriUsbNativeBridge.flushPlayerPcm(handle)
    fun drain(): Boolean = handle != 0L && NeriUsbNativeBridge.drainPlayerPcm(handle)

    fun setVolume(volume: Float): Boolean = handle != 0L && NeriUsbNativeBridge.setPlayerVolume(handle, volume)
    fun setHardwareVolume(fraction: Float): Boolean = handle != 0L && NeriUsbNativeBridge.setHardwareVolume(handle, fraction)
    fun hasHardwareVolume(): Boolean = handle != 0L && NeriUsbNativeBridge.hasHardwareVolume(handle)
    fun setBitPerfect(enabled: Boolean): Boolean = handle != 0L && NeriUsbNativeBridge.setPlayerBitPerfect(handle, enabled)
    fun setFocusMuted(muted: Boolean): Boolean = handle != 0L && NeriUsbNativeBridge.setPlayerFocusMuted(handle, muted)

    fun configureBufferDuration(ms: Int): Boolean = handle != 0L && NeriUsbNativeBridge.configurePlayerBufferDuration(handle, ms)
    fun configureTransferWindow(ms: Int): Boolean = handle != 0L && NeriUsbNativeBridge.configurePlayerTransferWindow(handle, ms)

    fun completedFrames(): Long = if (handle == 0L) 0L else NeriUsbNativeBridge.completedAudioFrames(handle)
    fun queuedFrames(): Long = if (handle == 0L) 0L else NeriUsbNativeBridge.queuedPlayerFrames(handle)
    fun freeBytes(): Long? = if (handle == 0L) null else NeriUsbNativeBridge.playerPcmFreeBytes(handle)

    fun report(): NeriUsbReport {
        if (handle == 0L) return NeriUsbReport.invalid
        return NeriUsbReport.parse(NeriUsbNativeBridge.runtimeReport(handle))
    }

    /** 通知原生：USB 设备已拔出 */
    fun markDeviceDetached() {
        if (handle != 0L) NeriUsbNativeBridge.markDeviceDetached(handle)
    }

    /** 停止传输（保留 handle，可再 prepare+start） */
    fun stop() {
        if (handle != 0L) NeriUsbNativeBridge.stop(handle)
    }

    fun close() {
        val h = handle
        if (h != 0L) {
            NeriUsbNativeBridge.close(h)
            handle = 0L
        }
        try { connection?.close() } catch (_: Throwable) {}
        connection = null
        directBuf = null
    }

    companion object {
        private const val TAG = "NeriUsbSession"
        private const val STALL_TIMEOUT_NS = 2_000_000_000L
    }
}

/**
 * runtimeReport 的关键字段解析（key=value 空格分隔）。
 * 只取播放控制 / 诊断需要的部分。
 */
class NeriUsbReport(raw: String) {
    val fields: Map<String, String> = parseFields(raw)

    val sampleRate: Int get() = intOf("sampleRate")
    val negotiatedRate: Int get() = intOf("negotiatedRate")
    val channels: Int get() = intOf("channels")
    val bits: Int get() = intOf("bits")
    val subslotBytes: Int get() = intOf("subslotBytes")
    val uacVersion: String get() = strOf("uacVersion")

    val running: Boolean get() = boolOf("running")
    val paused: Boolean get() = boolOf("paused")
    val transportFailed: Boolean get() = boolOf("transportFailed")
    val terminalFailure: Boolean get() = boolOf("terminalFailure")
    val playbackReady: Boolean get() = boolOf("playbackReady")
    val deviceOnline: Boolean get() = boolOf("deviceOnline")
    val feedbackReady: Boolean get() = boolOf("feedbackReady")

    val feedbackMode: String get() = strOf("feedbackMode")
    val feedbackState: String get() = strOf("feedbackState")
    val feedbackRatePpm: Long get() = longOf("feedbackRatePpm")
    val feedbackRateHz: Double get() = doubleOf("feedbackRateHz")

    val pcmLevel: Int get() = intOf("pcmLevel")
    val fifoMs: Int get() = intOf("fifoMs")
    val bufferMs: Int get() = intOf("bufferMs")
    val queuedFrames: Long get() = longOf("queuedFrames")
    val completedAudioFrames: Long get() = longOf("completedAudioFrames")
    val underrunBytes: Long get() = longOf("playerUnderrunBytes")
    val droppedBytes: Long get() = longOf("playerDroppedBytes")
    val zeroFillBytes: Long get() = longOf("playerZeroFillBytes")
    val backpressureEvents: Long get() = longOf("pcmBackpressureEvents")
    val outputPeak: Float get() = floatOf("outputPeak")
    val lastOutputPeak: Float get() = floatOf("lastOutputPeak")

    val errorCode: String get() = strOf("errorCode")
    val lastError: String get() = strOf("lastError")
    val source: String get() = strOf("source")
    val candidateId: String get() = strOf("candidateId")

    private fun strOf(key: String): String = fields[key] ?: ""
    private fun intOf(key: String): Int = fields[key]?.toIntOrNull() ?: 0
    private fun longOf(key: String): Long = fields[key]?.toLongOrNull() ?: 0L
    private fun doubleOf(key: String): Double = fields[key]?.toDoubleOrNull() ?: 0.0
    private fun floatOf(key: String): Float = fields[key]?.toFloatOrNull() ?: 0f
    private fun boolOf(key: String): Boolean = fields[key] == "true"

    companion object {
        val invalid = NeriUsbReport("")

        private val KV = Regex("(?:^|\\s)([A-Za-z0-9_]+)=([^\\s]*)")

        fun parseFields(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val map = HashMap<String, String>()
            for (m in KV.findAll(raw)) {
                // 首次出现为准（v2 规定重复 key 视为失败，这里取首个）
                if (!map.containsKey(m.groupValues[1])) {
                    map[m.groupValues[1]] = m.groupValues[2]
                }
            }
            return map
        }

        fun parse(raw: String): NeriUsbReport = NeriUsbReport(raw)
    }
}
