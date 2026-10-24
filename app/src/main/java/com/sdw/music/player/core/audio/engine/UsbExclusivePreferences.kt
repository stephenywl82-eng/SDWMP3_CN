package com.sdw.music.player.core.audio.engine

import android.content.Context
import kotlin.math.abs

/**
 * NeriPlayer USB 独占输出偏好模型（移植）。
 *
 * 源码来自 NeriPlayer（https://github.com/cwuom/NeriPlayer，作者 cwuom / ouom）：
 * modules/model/.../data/model/settings/usb/UsbExclusivePreferences.kt
 * 按 NeriPlayer Native Attribution License 获得闭源使用授权。
 *
 * 存储：本应用既有 SharedPreferences("settings")，键前缀 usb_ex_。
 * 默认值与 NeriPlayer 保持一致（follow_source / auto / 不比特完美 / stable /
 * closest_supported / 三项兼容全开 / 前台 250ms / 后台 1500ms / 风险阈值 -6dBFS）。
 */

const val USB_EX_DEFAULT_SAMPLE_RATE_MODE = "follow_source"
const val USB_EX_DEFAULT_BIT_DEPTH_MODE = "auto"
const val USB_EX_DEFAULT_BIT_PERFECT = false
const val USB_EX_DEFAULT_BUFFER_PROFILE = "stable"
const val USB_EX_DEFAULT_UNSUPPORTED_FORMAT_POLICY = "closest_supported"
const val USB_EX_DEFAULT_SAMPLE_RATE_COMPATIBILITY = true
const val USB_EX_DEFAULT_BIT_DEPTH_COMPATIBILITY = true
const val USB_EX_DEFAULT_CHANNEL_COMPATIBILITY = true
const val USB_EX_DEFAULT_FOREGROUND_BUFFER_MS = 250
const val USB_EX_DEFAULT_BACKGROUND_BUFFER_MS = 1500
const val USB_EX_MIN_FOREGROUND_BUFFER_MS = 100
const val USB_EX_MIN_BACKGROUND_BUFFER_MS = 200
const val USB_EX_MAX_FOREGROUND_BUFFER_MS = 1000
const val USB_EX_MAX_BACKGROUND_BUFFER_MS = 3000
const val USB_EX_BUFFER_STEP_MS = 50
const val USB_EX_DEFAULT_VOLUME_RISK_THRESHOLD_DBFS = -6
const val USB_EX_MIN_VOLUME_RISK_THRESHOLD_DBFS = -24
const val USB_EX_MAX_VOLUME_RISK_THRESHOLD_DBFS = -1
const val USB_EX_VOLUME_RISK_THRESHOLD_STEP_DB = 1
const val USB_EX_DEFAULT_RESPOND_TO_ATTACH = true
const val USB_EX_MAX_CHANNELS = 8

enum class UsbExclusiveSampleRateMode(val storageValue: String, val sampleRateHz: Int?) {
    FOLLOW_SOURCE("follow_source", null),
    RATE_44100("44100", 44_100),
    RATE_48000("48000", 48_000),
    RATE_88200("88200", 88_200),
    RATE_96000("96000", 96_000),
    RATE_176400("176400", 176_400),
    RATE_192000("192000", 192_000),
    RATE_352800("352800", 352_800),
    RATE_384000("384000", 384_000);

    fun requestedSampleRateHz(sourceSampleRateHz: Int): Int? =
        sampleRateHz ?: sourceSampleRateHz.takeIf { it > 0 }

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveSampleRateMode =
            entries.find { it.storageValue == value?.trim() } ?: FOLLOW_SOURCE
    }
}

enum class UsbExclusiveBitDepthMode(val storageValue: String, val bitDepth: Int?) {
    AUTO("auto", null),
    BIT_16("16", 16),
    BIT_24("24", 24),
    BIT_32("32", 32);

    fun requestedBitDepth(sourceBitDepth: Int): Int? =
        bitDepth ?: sourceBitDepth.takeIf { it > 0 }

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveBitDepthMode =
            entries.find { it.storageValue == value?.trim() } ?: AUTO
    }
}

enum class UsbExclusiveBufferProfile(val storageValue: String, val bufferDurationMs: Int) {
    LOW_LATENCY("low_latency", 3000),
    BALANCED("balanced", 5000),
    STABLE("stable", 12000);

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveBufferProfile =
            entries.find { it.storageValue == value?.trim() } ?: STABLE
    }
}

enum class UsbExclusiveUnsupportedFormatPolicy(val storageValue: String) {
    SYSTEM_FALLBACK("system_fallback"),
    CLOSEST_SUPPORTED("closest_supported");

    companion object {
        fun fromStorageValue(value: String?): UsbExclusiveUnsupportedFormatPolicy =
            entries.find { it.storageValue == value?.trim() } ?: CLOSEST_SUPPORTED
    }
}

data class UsbExclusivePreferences(
    val sampleRateMode: UsbExclusiveSampleRateMode = UsbExclusiveSampleRateMode.FOLLOW_SOURCE,
    val bitDepthMode: UsbExclusiveBitDepthMode = UsbExclusiveBitDepthMode.AUTO,
    val bitPerfect: Boolean = USB_EX_DEFAULT_BIT_PERFECT,
    val bufferProfile: UsbExclusiveBufferProfile = UsbExclusiveBufferProfile.STABLE,
    val unsupportedFormatPolicy: UsbExclusiveUnsupportedFormatPolicy =
        UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED,
    val sampleRateCompatibilityEnabled: Boolean = USB_EX_DEFAULT_SAMPLE_RATE_COMPATIBILITY,
    val bitDepthCompatibilityEnabled: Boolean = USB_EX_DEFAULT_BIT_DEPTH_COMPATIBILITY,
    val channelCompatibilityEnabled: Boolean = USB_EX_DEFAULT_CHANNEL_COMPATIBILITY,
    val foregroundBufferMs: Int = USB_EX_DEFAULT_FOREGROUND_BUFFER_MS,
    val backgroundBufferMs: Int = USB_EX_DEFAULT_BACKGROUND_BUFFER_MS,
    val volumeRiskThresholdDbfs: Int = USB_EX_DEFAULT_VOLUME_RISK_THRESHOLD_DBFS,
    val respondToAttach: Boolean = USB_EX_DEFAULT_RESPOND_TO_ATTACH
) {
    /**
     * 源格式 + DAC 支持集 → 输出采样率。
     * 兼容开：follow_source + closest 时优先同家族（44.1k/48k）最近值。
     * 兼容关：只接受精确匹配（不匹配=不支持，由调用方决定降级）。
     */
    fun resolveSampleRateHz(sourceSampleRateHz: Int, supportedSampleRatesHz: Collection<Int>): Int? {
        val requested = sampleRateMode.requestedSampleRateHz(sourceSampleRateHz) ?: return null
        val normalized = supportedSampleRatesHz.filter { it > 0 }.distinct()
        if (!sampleRateCompatibilityEnabled) return requested.takeIf { it in normalized }
        if (sampleRateMode == UsbExclusiveSampleRateMode.FOLLOW_SOURCE &&
            unsupportedFormatPolicy == UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED &&
            requested !in normalized) {
            val family = sampleRateFamily(requested)
            if (family != null) {
                normalized.filter { sampleRateFamily(it) == family }.nearestTo(requested)
                    ?.let { return it }
            }
        }
        return resolveSupportedValue(requested, normalized, unsupportedFormatPolicy)
    }

    fun resolveBitDepth(sourceBitDepth: Int, supportedBitDepths: Collection<Int>): Int? {
        val requested = bitDepthMode.requestedBitDepth(sourceBitDepth) ?: return null
        val normalized = supportedBitDepths.filter { it > 0 }.distinct()
        if (!bitDepthCompatibilityEnabled) return requested.takeIf { it in normalized }
        if (bitDepthMode == UsbExclusiveBitDepthMode.AUTO) {
            normalized.filter { it >= requested }.minOrNull()?.let { return it }
        }
        return resolveSupportedValue(requested, normalized, unsupportedFormatPolicy)
    }

    fun resolveChannelCount(sourceChannelCount: Int, supportedChannelCounts: Collection<Int>): Int? {
        val normalized = supportedChannelCounts.filter { it > 0 }.distinct()
        if (normalized.isEmpty()) return sourceChannelCount.takeIf { it in 1..2 } ?: 2
        if (!channelCompatibilityEnabled) {
            return sourceChannelCount.takeIf { it in normalized && it in 1..2 }
        }
        return when {
            2 in normalized -> 2
            sourceChannelCount in normalized && sourceChannelCount in 1..2 -> sourceChannelCount
            else -> normalized.filter { it in 3..USB_EX_MAX_CHANNELS }.minOrNull()
        }
    }

    fun bufferDurationMs(appInForeground: Boolean): Int =
        if (appInForeground) normalizeUsbExclusiveForegroundBufferMs(foregroundBufferMs)
        else normalizeUsbExclusiveBackgroundBufferMs(backgroundBufferMs)

    fun reservedBufferDurationMs(): Int =
        maxOf(normalizeUsbExclusiveForegroundBufferMs(foregroundBufferMs), normalizeUsbExclusiveBackgroundBufferMs(backgroundBufferMs))

    companion object {
        fun fromStorageValues(
            sampleRateMode: String?,
            bitDepthMode: String?,
            bufferProfile: String?,
            unsupportedFormatPolicy: String?,
            bitPerfect: Boolean? = null,
            sampleRateCompatibilityEnabled: Boolean? = null,
            bitDepthCompatibilityEnabled: Boolean? = null,
            channelCompatibilityEnabled: Boolean? = null,
            foregroundBufferMs: Int? = null,
            backgroundBufferMs: Int? = null,
            volumeRiskThresholdDbfs: Int? = null,
            respondToAttach: Boolean? = null
        ): UsbExclusivePreferences {
            val profile = UsbExclusiveBufferProfile.fromStorageValue(bufferProfile)
            val hasValidProfile = bufferProfile?.trim()?.let { v ->
                UsbExclusiveBufferProfile.entries.any {
                    it.storageValue.equals(v, true) || it.name.equals(v, true)
                }
            } == true
            return UsbExclusivePreferences(
                sampleRateMode = UsbExclusiveSampleRateMode.fromStorageValue(sampleRateMode),
                bitDepthMode = UsbExclusiveBitDepthMode.fromStorageValue(bitDepthMode),
                bitPerfect = bitPerfect ?: USB_EX_DEFAULT_BIT_PERFECT,
                bufferProfile = profile,
                unsupportedFormatPolicy = UsbExclusiveUnsupportedFormatPolicy.fromStorageValue(unsupportedFormatPolicy),
                sampleRateCompatibilityEnabled = sampleRateCompatibilityEnabled
                    ?: USB_EX_DEFAULT_SAMPLE_RATE_COMPATIBILITY,
                bitDepthCompatibilityEnabled = bitDepthCompatibilityEnabled
                    ?: USB_EX_DEFAULT_BIT_DEPTH_COMPATIBILITY,
                channelCompatibilityEnabled = channelCompatibilityEnabled
                    ?: USB_EX_DEFAULT_CHANNEL_COMPATIBILITY,
                foregroundBufferMs = normalizeUsbExclusiveForegroundBufferMs(
                    foregroundBufferMs ?: if (hasValidProfile) profile.bufferDurationMs
                    else USB_EX_DEFAULT_FOREGROUND_BUFFER_MS
                ),
                backgroundBufferMs = normalizeUsbExclusiveBackgroundBufferMs(
                    backgroundBufferMs ?: USB_EX_DEFAULT_BACKGROUND_BUFFER_MS
                ),
                volumeRiskThresholdDbfs = normalizeUsbExclusiveVolumeRiskThresholdDbfs(
                    volumeRiskThresholdDbfs ?: USB_EX_DEFAULT_VOLUME_RISK_THRESHOLD_DBFS
                ),
                respondToAttach = respondToAttach ?: USB_EX_DEFAULT_RESPOND_TO_ATTACH
            )
        }
    }
}

fun normalizeUsbExclusiveForegroundBufferMs(value: Int): Int =
    normalizeBufferMs(value, USB_EX_MIN_FOREGROUND_BUFFER_MS, USB_EX_MAX_FOREGROUND_BUFFER_MS)

fun normalizeUsbExclusiveBackgroundBufferMs(value: Int): Int =
    normalizeBufferMs(value, USB_EX_MIN_BACKGROUND_BUFFER_MS, USB_EX_MAX_BACKGROUND_BUFFER_MS)

fun normalizeUsbExclusiveVolumeRiskThresholdDbfs(value: Int): Int {
    val clamped = value.coerceIn(
        USB_EX_MIN_VOLUME_RISK_THRESHOLD_DBFS, USB_EX_MAX_VOLUME_RISK_THRESHOLD_DBFS)
    return (clamped / USB_EX_VOLUME_RISK_THRESHOLD_STEP_DB) * USB_EX_VOLUME_RISK_THRESHOLD_STEP_DB
}

private fun normalizeBufferMs(value: Int, minMs: Int, maxMs: Int): Int {
    val clamped = value.coerceIn(minMs, maxMs)
    val rounded = (clamped / USB_EX_BUFFER_STEP_MS) * USB_EX_BUFFER_STEP_MS
    return rounded.coerceIn(minMs, maxMs)
}

private fun resolveSupportedValue(
    requested: Int,
    supportedValues: Collection<Int>,
    policy: UsbExclusiveUnsupportedFormatPolicy
): Int? {
    val candidates = supportedValues.filter { it > 0 }.distinct()
    if (requested in candidates) return requested
    if (policy == UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK) return null
    return candidates.nearestTo(requested)
}

private fun Collection<Int>.nearestTo(requested: Int): Int? =
    minWithOrNull(compareBy<Int> { abs(it.toLong() - requested.toLong()) }.thenByDescending { it })

private fun sampleRateFamily(sampleRateHz: Int): Int? = when {
    sampleRateHz > 0 && sampleRateHz % 44_100 == 0 -> 44_100
    sampleRateHz > 0 && sampleRateHz % 48_000 == 0 -> 48_000
    else -> null
}

/** SharedPreferences 读写（键前缀 usb_ex_，避免与既有设置冲突） */
object UsbExclusivePrefs {
    private const val PREFS = "settings"
    const val KEY_SAMPLE_RATE_MODE = "usb_ex_sample_rate_mode"
    const val KEY_BIT_DEPTH_MODE = "usb_ex_bit_depth_mode"
    const val KEY_BIT_PERFECT = "usb_ex_bit_perfect"
    const val KEY_BUFFER_PROFILE = "usb_ex_buffer_profile"
    const val KEY_UNSUPPORTED_POLICY = "usb_ex_unsupported_policy"
    const val KEY_RATE_COMPAT = "usb_ex_rate_compat"
    const val KEY_BITS_COMPAT = "usb_ex_bits_compat"
    const val KEY_CH_COMPAT = "usb_ex_ch_compat"
    const val KEY_FG_BUFFER_MS = "usb_ex_fg_buffer_ms"
    const val KEY_BG_BUFFER_MS = "usb_ex_bg_buffer_ms"
    const val KEY_VOLUME_RISK_DBFS = "usb_ex_volume_risk_dbfs"
    const val KEY_RESPOND_ATTACH = "usb_ex_respond_attach"

    fun read(context: Context?): UsbExclusivePreferences {
        val ctx = context ?: return UsbExclusivePreferences()
        return try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            UsbExclusivePreferences.fromStorageValues(
                sampleRateMode = sp.getString(KEY_SAMPLE_RATE_MODE, null),
                bitDepthMode = sp.getString(KEY_BIT_DEPTH_MODE, null),
                bufferProfile = sp.getString(KEY_BUFFER_PROFILE, null),
                unsupportedFormatPolicy = sp.getString(KEY_UNSUPPORTED_POLICY, null),
                bitPerfect = if (sp.contains(KEY_BIT_PERFECT)) sp.getBoolean(KEY_BIT_PERFECT, USB_EX_DEFAULT_BIT_PERFECT) else null,
                sampleRateCompatibilityEnabled = if (sp.contains(KEY_RATE_COMPAT)) sp.getBoolean(KEY_RATE_COMPAT, USB_EX_DEFAULT_SAMPLE_RATE_COMPATIBILITY) else null,
                bitDepthCompatibilityEnabled = if (sp.contains(KEY_BITS_COMPAT)) sp.getBoolean(KEY_BITS_COMPAT, USB_EX_DEFAULT_BIT_DEPTH_COMPATIBILITY) else null,
                channelCompatibilityEnabled = if (sp.contains(KEY_CH_COMPAT)) sp.getBoolean(KEY_CH_COMPAT, USB_EX_DEFAULT_CHANNEL_COMPATIBILITY) else null,
                foregroundBufferMs = if (sp.contains(KEY_FG_BUFFER_MS)) sp.getInt(KEY_FG_BUFFER_MS, USB_EX_DEFAULT_FOREGROUND_BUFFER_MS) else null,
                backgroundBufferMs = if (sp.contains(KEY_BG_BUFFER_MS)) sp.getInt(KEY_BG_BUFFER_MS, USB_EX_DEFAULT_BACKGROUND_BUFFER_MS) else null,
                volumeRiskThresholdDbfs = if (sp.contains(KEY_VOLUME_RISK_DBFS)) sp.getInt(KEY_VOLUME_RISK_DBFS, USB_EX_DEFAULT_VOLUME_RISK_THRESHOLD_DBFS) else null,
                respondToAttach = if (sp.contains(KEY_RESPOND_ATTACH)) sp.getBoolean(KEY_RESPOND_ATTACH, USB_EX_DEFAULT_RESPOND_TO_ATTACH) else null
            )
        } catch (_: Exception) { UsbExclusivePreferences() }
    }

    fun write(context: Context?, prefs: UsbExclusivePreferences) {
        val ctx = context ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString(KEY_SAMPLE_RATE_MODE, prefs.sampleRateMode.storageValue)
                putString(KEY_BIT_DEPTH_MODE, prefs.bitDepthMode.storageValue)
                putBoolean(KEY_BIT_PERFECT, prefs.bitPerfect)
                putString(KEY_BUFFER_PROFILE, prefs.bufferProfile.storageValue)
                putString(KEY_UNSUPPORTED_POLICY, prefs.unsupportedFormatPolicy.storageValue)
                putBoolean(KEY_RATE_COMPAT, prefs.sampleRateCompatibilityEnabled)
                putBoolean(KEY_BITS_COMPAT, prefs.bitDepthCompatibilityEnabled)
                putBoolean(KEY_CH_COMPAT, prefs.channelCompatibilityEnabled)
                putInt(KEY_FG_BUFFER_MS, prefs.foregroundBufferMs)
                putInt(KEY_BG_BUFFER_MS, prefs.backgroundBufferMs)
                putInt(KEY_VOLUME_RISK_DBFS, prefs.volumeRiskThresholdDbfs)
                putBoolean(KEY_RESPOND_ATTACH, prefs.respondToAttach)
                apply()
            }
        } catch (_: Exception) {}
    }
}
