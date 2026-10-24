package com.sdw.music.player.core.audio.engine

import com.sdw.music.player.core.audio.DebugLog
import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer

/**
 * NeriPlayer USB 独占输出原生桥（移植）。
 *
 * 原生源码来自 NeriPlayer（https://github.com/cwuom/NeriPlayer，作者 cwuom / ouom），
 * 按 NeriPlayer Native Attribution License 获得闭源使用授权；内嵌 libusb 为 LGPL-2.1。
 *
 * 对应原生库 libneri_usb.so 中的 JNI 符号
 * （Java_com_sdw_music_player_core_audio_engine_NeriUsbNativeBridge_*）。
 * 所有调用都有异常/LinkageError 兜底：桥失败时返回安全值，由上层降级处理，
 * 绝不让异常冒泡出原生边界导致 std::terminate 崩溃。
 */
internal object NeriUsbNativeBridge {

    private const val TAG = "NeriUsbNative"

    @Volatile
    private var libraryReady = false

    @Volatile
    private var loadAttempted = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryReady) return true
        if (loadAttempted) return false
        loadAttempted = true
        return try {
            System.loadLibrary("neri_usb")
            libraryReady = true
            DebugLog.add(TAG, "ensureLoaded: libneri_usb.so loaded")
            true
        } catch (e: Throwable) {
            DebugLog.add(TAG, "ensureLoaded FAILED: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    val isLoaded: Boolean get() = libraryReady

    // ── 会话生命周期 ──────────────────────────────────────────────────────────

    fun open(
        connection: UsbDeviceConnection,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        subslotBytes: Int
    ): Long {
        if (!ensureLoaded()) return 0L
        val fd = connection.fileDescriptor
        return try {
            nativeOpen(fd, sampleRate, channelCount, bitsPerSample, subslotBytes)
        } catch (e: Throwable) {
            DebugLog.add(TAG, "nativeOpen threw: ${e.message}")
            0L
        }
    }

    fun close(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        try { nativeClose(handle) } catch (_: Throwable) {}
    }

    fun lastOpenError(): String {
        if (!ensureLoaded()) return "native_unavailable"
        return try { nativeLastOpenError() } catch (_: Throwable) { "native_unavailable" }
    }

    // ── PCM 播放 ──────────────────────────────────────────────────────────────

    fun preparePlayerPcm(handle: Long, inputSampleRate: Int, inputChannelCount: Int, inputEncoding: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try {
            nativePreparePlayerPcm(handle, inputSampleRate, inputChannelCount, inputEncoding)
        } catch (e: Throwable) {
            DebugLog.add(TAG, "nativePreparePlayerPcm threw: ${e.message}")
            false
        }
    }

    fun reconfigurePlayerPcmOutput(handle: Long, sampleRate: Int, channelCount: Int, bitsPerSample: Int, subslotBytes: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try {
            nativeReconfigurePlayerPcmOutput(handle, sampleRate, channelCount, bitsPerSample, subslotBytes)
        } catch (e: Throwable) {
            DebugLog.add(TAG, "nativeReconfigurePlayerPcmOutput threw: ${e.message}")
            false
        }
    }

    /** @return 实际写入的字节数（短写=背压，调用方应重试剩余部分或等待） */
    fun writePlayerPcm(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, volume: Float): Int {
        if (handle == 0L || size <= 0 || !buffer.isDirect || !ensureLoaded()) return 0
        return try {
            nativeWritePlayerPcm(handle, buffer, offset, size, volume)
        } catch (e: Throwable) {
            DebugLog.add(TAG, "nativeWritePlayerPcm threw: ${e.message}")
            0
        }
    }

    fun drainPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeDrainPlayerPcm(handle) } catch (_: Throwable) { false }
    }

    fun startPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeStartPlayerPcm(handle) } catch (_: Throwable) { false }
    }

    fun playPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativePlayPlayerPcm(handle) } catch (_: Throwable) { false }
    }

    fun pausePlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativePausePlayerPcm(handle) } catch (_: Throwable) { false }
    }

    fun flushPlayerPcm(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeFlushPlayerPcm(handle) } catch (_: Throwable) { false }
    }

    // ── 音量 / 比特完美 / 缓冲 ────────────────────────────────────────────────

    fun setPlayerVolume(handle: Long, volume: Float): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeSetPlayerVolume(handle, volume.coerceIn(0f, 1f)) } catch (_: Throwable) { false }
    }

    fun setHardwareVolume(handle: Long, fraction: Float): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeSetHardwareVolume(handle, fraction.coerceIn(0f, 1f)) } catch (_: Throwable) { false }
    }

    fun hasHardwareVolume(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeHasHardwareVolume(handle) } catch (_: Throwable) { false }
    }

    fun setPlayerBitPerfect(handle: Long, enabled: Boolean): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeSetPlayerBitPerfect(handle, enabled) } catch (_: Throwable) { false }
    }

    fun setPlayerFocusMuted(handle: Long, muted: Boolean): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeSetPlayerFocusMuted(handle, muted) } catch (_: Throwable) { false }
    }

    fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeConfigurePlayerBufferDuration(handle, durationMs) } catch (_: Throwable) { false }
    }

    fun configurePlayerTransferWindow(handle: Long, durationMs: Int): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeConfigurePlayerTransferWindow(handle, durationMs) } catch (_: Throwable) { false }
    }

    // ── 状态查询 ──────────────────────────────────────────────────────────────

    fun completedAudioFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return try { nativeGetCompletedAudioFrames(handle).coerceAtLeast(0L) } catch (_: Throwable) { 0L }
    }

    fun queuedPlayerFrames(handle: Long): Long {
        if (handle == 0L || !ensureLoaded()) return 0L
        return try { nativeGetQueuedPlayerFrames(handle).coerceAtLeast(0L) } catch (_: Throwable) { 0L }
    }

    fun playerPcmFreeBytes(handle: Long): Long? {
        if (handle == 0L || !ensureLoaded()) return null
        return try { nativeGetPlayerPcmFreeBytes(handle).takeIf { it >= 0L } } catch (_: Throwable) { null }
    }

    fun runtimeReport(handle: Long): String {
        if (handle == 0L || !ensureLoaded()) return "native_unavailable"
        return try { nativeRuntimeReport(handle) } catch (e: Throwable) { "jni_bridge_failure_runtime_report" }
    }

    // ── 恢复动作确认（recovery latch） ────────────────────────────────────────

    fun acknowledgeRecoveryAction(handle: Long, actionGeneration: Long, actionId: Long): Int {
        if (handle == 0L || actionGeneration < 0L || actionId < 0L || !ensureLoaded()) return -1
        return try { nativeAcknowledgeRecoveryAction(handle, actionGeneration, actionId).toInt() } catch (_: Throwable) { -1 }
    }

    // ── 测试音 / 停止 / 设备分离 ──────────────────────────────────────────────

    fun startGeneratedTone(handle: Long): Boolean {
        if (handle == 0L || !ensureLoaded()) return false
        return try { nativeStartGeneratedTone(handle) } catch (_: Throwable) { false }
    }

    fun stop(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        try { nativeStop(handle) } catch (_: Throwable) {}
    }

    fun markDeviceDetached(handle: Long) {
        if (handle == 0L || !ensureLoaded()) return
        try { nativeMarkDeviceDetached(handle) } catch (_: Throwable) {}
    }

    // ── JNI 声明（符号名与 libneri_usb.so 一致） ──────────────────────────────

    @JvmStatic private external fun nativeOpen(
        fd: Int, sampleRate: Int, channelCount: Int, bitsPerSample: Int, subslotBytes: Int
    ): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeLastOpenError(): String

    @JvmStatic private external fun nativePreparePlayerPcm(
        handle: Long, inputSampleRate: Int, inputChannelCount: Int, inputEncoding: Int
    ): Boolean

    @JvmStatic private external fun nativeReconfigurePlayerPcmOutput(
        handle: Long, sampleRate: Int, channelCount: Int, bitsPerSample: Int, subslotBytes: Int
    ): Boolean

    @JvmStatic private external fun nativeWritePlayerPcm(
        handle: Long, buffer: ByteBuffer, offset: Int, size: Int, volume: Float
    ): Int

    @JvmStatic private external fun nativeDrainPlayerPcm(handle: Long): Boolean
    @JvmStatic private external fun nativeStartPlayerPcm(handle: Long): Boolean
    @JvmStatic private external fun nativePlayPlayerPcm(handle: Long): Boolean
    @JvmStatic private external fun nativePausePlayerPcm(handle: Long): Boolean
    @JvmStatic private external fun nativeFlushPlayerPcm(handle: Long): Boolean

    @JvmStatic private external fun nativeSetPlayerVolume(handle: Long, volume: Float): Boolean
    @JvmStatic private external fun nativeSetHardwareVolume(handle: Long, fraction: Float): Boolean
    @JvmStatic private external fun nativeHasHardwareVolume(handle: Long): Boolean
    @JvmStatic private external fun nativeSetPlayerBitPerfect(handle: Long, enabled: Boolean): Boolean
    @JvmStatic private external fun nativeSetPlayerFocusMuted(handle: Long, muted: Boolean): Boolean
    @JvmStatic private external fun nativeConfigurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean
    @JvmStatic private external fun nativeConfigurePlayerTransferWindow(handle: Long, durationMs: Int): Boolean

    @JvmStatic private external fun nativeGetCompletedAudioFrames(handle: Long): Long
    @JvmStatic private external fun nativeGetQueuedPlayerFrames(handle: Long): Long
    @JvmStatic private external fun nativeGetPlayerPcmFreeBytes(handle: Long): Long
    @JvmStatic private external fun nativeRuntimeReport(handle: Long): String

    @JvmStatic private external fun nativeAcknowledgeRecoveryAction(
        handle: Long, actionGeneration: Long, actionId: Long
    ): String

    @JvmStatic private external fun nativeStartGeneratedTone(handle: Long): Boolean
    @JvmStatic private external fun nativeStop(handle: Long)
    @JvmStatic private external fun nativeMarkDeviceDetached(handle: Long)
}
