package com.sdw.music.player.core.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import com.sdw.music.player.DacProfile
import com.sdw.music.player.DspSnapshot
import com.sdw.music.player.MusicService

/**
 * 【V8.170】设备自适应校准。
 *
 * 同一首歌接不同设备好不好听，差别大半不在曲子在，而在链路：
 *   - SBC 编码把 16kHz 以上砍了，AAC 稍好，LDAC/LHDC 接近无损；
 *   - USB DAC 独占走的是独立音频线程 + 硬件时钟，DSP 全在 native；
 *   - 有线直连时用户多半想要的是原味，任何"润色"都是减法。
 *
 * 所以这里不替用户调滑块（那会覆盖手动调好的 13 个维度），只给"模块开关"的建议：
 * 一条建议 = 检测到的设备 + 建议的 DspSnapshot + 一句人话理由。UI 上给用户一个按钮，
 * 同意才下发，不同意什么也不动。
 */
data class DeviceDspAdvice(
    /** 检测到的输出设备技术名，如 "SBC" / "CX31993+97220 HiFi"；纯中文描述走 [type] 由 UI 本地化 */
    val deviceName: String,
    /**
     * 设备类型，UI 据此选本地化文案：
     * dac / wired / sbc / aac / hires / unknown
     */
    val type: String,
    /** 建议的模块状态；null 表示"别动" */
    val suggestion: DspSnapshot,
    /** true = 这些模块在当前输出链路上不生效（DAC 独占走独立线程），UI 要提示 */
    val modulesInactive: Boolean = false
)

object MsebDeviceAdapt {

    /**
     * 检测当前输出设备并给出模块建议。
     * 只在 MsebScreen 进入时调一次 —— 接拔设备 / 切蓝牙后重新进页面即可刷新。
     */
    fun detect(context: Context): DeviceDspAdvice {
        val dacActive = MusicService.instance?.isDacActive() == true

        // ── USB DAC 独占 ──
        if (dacActive) {
            val device: UsbDevice? = UsbDacManager.getDacDevice()
            val profile = if (device != null) {
                DacProfile.find(device.vendorId, device.productId)
            } else {
                DacProfile.find(0, 0)
            }
            return DeviceDspAdvice(
                deviceName = profile.name,
                type = "dac",
                // 独占链不该有"房间尾音"和"逐频段压缩"——那是给无线/回放场景补的。
                // 多频段限幅保留（防 EQ 叠加冲顶），AutoEQ 强度保持完整修正。
                suggestion = DspSnapshot(
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = false,
                    multiBandLimiterEnabled = true,
                    crossfeedEnabled = false
                ),
                // 这些模块挂在 Oboe callback 上，独占链不经这里 → 提示无效
                modulesInactive = true
            )
        }

        // ── 蓝牙 / 有线 Oboe ──
        val codec = BtCodecTracker.currentCodecName
        return when {
            // BtCodecTracker 对 3.5mm 有线返回硬编码中文 "有线"，这里归一化，文案由 UI 本地化
            codec == "有线" || codec == "Wired" -> DeviceDspAdvice(
                deviceName = "Wired",
                type = "wired",
                suggestion = DspSnapshot(
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = false,
                    multiBandLimiterEnabled = true,
                    crossfeedEnabled = true
                ),
                modulesInactive = false
            )
            codec.startsWith("SBC") -> adviceBluetooth(
                codec, "sbc",
                suggestion = DspSnapshot(
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = true,
                    dynamicEqDepth = 0.5f,
                    multiBandLimiterEnabled = true,
                    autoEqMix = 0.8f,
                    loudnessEnabled = false
                )
            )
            codec.startsWith("AAC") -> adviceBluetooth(
                codec, "aac",
                suggestion = DspSnapshot(
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = true,
                    dynamicEqDepth = 0.35f,
                    multiBandLimiterEnabled = true,
                    autoEqMix = 0.9f
                )
            )
            // 【V8.174】LHDC / aptX 同属高码率链路，以前漏了这一支会掉进 "unknown"
            // 反而在 LHDC v5 上给出 SBC 档的建议。
            codec.startsWith("LDAC") || codec.startsWith("HiRes") ||
            codec.contains("LHDC", true) || codec.contains("LLAC", true) ||
            codec.contains("aptX", true) -> adviceBluetooth(
                codec, "hires",
                suggestion = DspSnapshot(
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = false,
                    multiBandLimiterEnabled = true,
                    autoEqMix = 1.0f
                )
            )
            else -> adviceBluetooth(
                codec, "unknown",
                suggestion = DspSnapshot(
                    multiBandLimiterEnabled = true,
                    spaceReverbEnabled = false,
                    dynamicEqEnabled = false
                )
            )
        }
    }

    private fun adviceBluetooth(
        codec: String,
        type: String,
        suggestion: DspSnapshot
    ): DeviceDspAdvice = DeviceDspAdvice(
        deviceName = codec,
        type = type,
        suggestion = suggestion,
        modulesInactive = false
    )

    /** 没有音乐在播 / 服务未起时的兜底。 */
    fun unavailable(): DeviceDspAdvice = DeviceDspAdvice(
        deviceName = "—",
        type = "unknown",
        suggestion = DspSnapshot(),
        modulesInactive = true
    )
}
