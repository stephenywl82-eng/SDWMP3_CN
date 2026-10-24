package com.sdw.music.player.core.audio.engine

/**
 * USB DAC capability profile for exclusive-mode playback.
 *
 * ## P1 adaptive-DAC architecture (2026-10)
 *
 * Every USB Audio Class DAC exposes its real capabilities in its descriptors —
 * wire format (subslot size), hardware-volume Feature Unit, supported sample-rate
 * family, UAC version, feedback endpoint. The native driver (`usb_audio_driver.cpp`)
 * already parses all of these in [UsbAudioDriver.open] and packs them into a single
 * string via `getDescriptorProfile()`. On the Kotlin side that becomes a
 * [DacDescriptor], which is the single source of truth for *standard* DACs.
 *
 * Only **genuinely non-standard firmware** needs a hand-written entry, kept in
 * [DacQuirk]. A new, spec-compliant DAC therefore needs **zero** code — it takes
 * the generic default, whose [DacQuirk.autoProbe] flag makes it *attempt* USB Host
 * Exclusive (bit-perfect) first, then auto-fall-back to Oboe system-route if the
 * path turns out silent/dead (recorded via [UsbDacManager.isForcedOboe] for the
 * rest of the session). Wire format / hardware volume / 44.1k support are all
 * auto-detected. That is the end-state:
 * "插上就用，只有真正奇葩的才需要往 quirk 库里加一条。"
 *
 * Routing (3 paths, ordered by priority):
 *   Path 1 — USB Host Exclusive (Bit-Perfect): DACs with [DacQuirk.useSystemRoute]=false
 *   Path 2 — Oboe System Route: safe fallback (default for unknown DACs that fail the
 *            Host-Exclusive auto-probe, and for known-good system-route DACs)
 *   Path 3 — ExoPlayer (SRC fallback): no DAC, Bluetooth, or broken 44.1k family
 *
 * The merged result is [DacProfile]; [DacProfile.find] combines a [DacQuirk] with
 * the live [DacDescriptor] (read from [UsbDacManager]) — quirk overrides descriptor.
 */

/**
 * Native descriptor-derived capabilities for a connected DAC.
 * Everything here is parsed by the C++ driver; nothing is hand-written.
 * Parsed from the `key=value;...` string returned by `getDescriptorProfile()`.
 */
data class DacDescriptor(
    /** Wire bits = max alt subslot * 8 (e.g. 4-byte subslot -> 32-bit S32_LE). */
    val wireBits: Int,
    val subslotMax: Int,
    val subslotMin: Int,
    /** 1 = UAC1, 2 = UAC2. */
    val uacVersion: Int,
    val highSpeed: Boolean,
    val hasFeedbackEp: Boolean,
    val featureUnitId: Int,
    val featureUnitChannel: Int,
    val featureUnitChannels: Int,
    val featureUnitMinDb: Float,
    val featureUnitMaxDb: Float,
    val featureUnitResDb: Float,
    /** True if the descriptor exposes any 44.1k-family (44.1/88.2/176.4/352.8k) clock. */
    val has44k1Clock: Boolean,
    /** True only when the clock-range probe actually returned data. When false the
     *  44.1k result is unknown (probe failed) and must NOT be treated as "lacks 44.1k". */
    val hasClockInfo: Boolean,
    val altCount: Int,
    /** [P3] Rates the DAC actually accepted via SET_CUR+GET_CUR probe (the real
     *  supported set, overriding any descriptor declaration). Empty = probe absent. */
    val verifiedSampleRates: List<Int> = emptyList()
) {
    companion object {
        /** Parse the packed string from native `getDescriptorProfile()`. */
        fun parse(raw: String?): DacDescriptor? {
            if (raw.isNullOrBlank()) return null
            val map = raw.split(';').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
            }.toMap()
            fun int(k: String, d: Int = 0) = map[k]?.toIntOrNull() ?: d
            fun bool(k: String) = int(k) != 0
            val wb = int("wb", 16)
            return DacDescriptor(
                wireBits = if (wb > 0) wb else 16,
                subslotMax = int("ssMax"),
                subslotMin = int("ssMin"),
                uacVersion = int("uac", 2),
                highSpeed = bool("hs"),
                hasFeedbackEp = bool("fb"),
                featureUnitId = int("fuId"),
                featureUnitChannel = int("fuCh"),
                featureUnitChannels = int("fuChs", 2),
                featureUnitMinDb = int("fuMin") / 1000f,
                featureUnitMaxDb = int("fuMax") / 1000f,
                featureUnitResDb = int("fuRes", 1000) / 1000f,
            has44k1Clock = bool("k44"),
            hasClockInfo = int("ci") > 0,
            altCount = int("alts"),
            verifiedSampleRates = map["vr"]?.split(' ')
                ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        )
        }
    }
}

/**
 * Hand-written per-DAC quirk entry. **Only DACs with genuinely non-standard
 * firmware behavior need an entry here** — standard UAC-compliant DACs need none.
 *
 * The booleans/flags are true quirk decisions (firmware lies, clock locks, etc.).
 * Wire format (wireBits) and the hardware-volume Feature Unit are NOT hand-written
 * here — they come from native descriptor auto-detection (P1/P3). [P4] narrowed this
 * quirk DB to firmware-bug fields only, so a new standard UAC DAC needs no entry.
 */
data class DacQuirk(
    val vid: Int,
    val pid: Int,
    val name: String,

    /** True = use Oboe system-route instead of hand-rolled USB Host Exclusive.
     *  Safe default for unknown DACs — let the kernel USB driver handle the
     *  low-level quirks. Set false only for DACs verified with host-mode Bit-Perfect. */
    val useSystemRoute: Boolean = true,

    /** True if the DAC physically cannot generate 44.1 kHz family (even if the
     *  descriptor claims support — some firmware lie). */
    val lacks44k1Clock: Boolean = false,

    /** True if SET_CUR control transfers break this DAC (EPIPE / dead endpoint).
     *  Informational for the Kotlin layer; native open()/start() also skip SET_CUR. */
    val skipSetCur: Boolean = false,

    /** True = try Android 14+ BIT_PERFECT API (setPreferredMixerAttributes) before
     *  falling back to Oboe system-route. Only meaningful when useSystemRoute=true. */
    val tryBitPerfectApi: Boolean = false,

    // ── snd-usb-audio flag equivalents (native Linux quirk table mirror) ──
    val urbPacketsOverride: Int = 0,
    val ctlMsgDelayUs: Int = 0,
    val ifaceDelayUs: Int = 0,
    val forceIfaceReset: Boolean = false,
    val forceUac1: Boolean = false,
    val skipSetCurQuirk: Boolean = false,

    /** [2026-10-22] True = 强制禁用硬件音量、走软件增益。用于已实证「FU 活着但从不
     *  衰减」的设备（31B2:1112：SET_CUR 全 ACK、滑块全程不变声）。claim 期的回环
     *  闸门抓不到「存值但不应用」型固件，这类设备只有软件增益能真正衰减。 */
    val forceSoftwareVolume: Boolean = false,

    /** [P2] True for *unknown* DACs (generic default). Attempt Host Exclusive
     *  bit-perfect first, but arm the silence watchdog so a dead/silent path auto
     *  falls back to Oboe system-route. Known verified DACs leave this false. */
    val autoProbe: Boolean = false
) {
    /** Merge hand quirks over live descriptor auto-detection → final [DacProfile]. */
    fun merge(desc: DacDescriptor?): DacProfile {
        // [P4] Native auto-detection is now AUTHORITATIVE for wire format and the
        // hardware-volume Feature Unit — the hand-written wireBitsOverride / featureUnit
        // fallbacks are removed, so the quirk DB holds only real firmware-bug fields.
        val wireBits = if (desc != null && desc.wireBits > 0) desc.wireBits else 16

        val fuId: Int
        val fuCh: Int
        val fuChs: Int
        val fuMin: Float
        val fuMax: Float
        val fuRes: Float
        if (desc != null && desc.featureUnitId > 0) {
            fuId = desc.featureUnitId; fuCh = desc.featureUnitChannel; fuChs = desc.featureUnitChannels
            fuMin = desc.featureUnitMinDb; fuMax = desc.featureUnitMaxDb; fuRes = desc.featureUnitResDb
        } else {
            // Descriptor exposed no Feature Unit — neutral default (no HW-volume assumption).
            // [2026-10-22] 曾改为「回退 quirk 手写 FU」给 TTGK 强推硬件音量，被实证推翻
            // （TTGK FU2 SET 后 GET 读回 0.0dB，值都存不进；且 DacQuirk 本无 FU 字段，
            // 那是 DacProfile 的）。恢复 P4 原始语义：desc 探不到 FU → 0 → native 自主
            // 决定（探测失败走软件增益，packer 已验证全位深相乘）。
            fuId = 0; fuCh = 1; fuChs = 2; fuMin = -127.0f; fuMax = 0.0f; fuRes = 1.0f
        }

        // [P3] When the SET_CUR/GET_CUR probe returned data, it is AUTHORITATIVE for
        // 44.1k support: lacking the family means NONE of 44.1/88.2/176.4/352.8k was
        // verified. This overrides the descriptor-derived guess (and the hand quirk),
        // so we stop trusting firmware that advertises rates the clock can't make.
        // When the probe is empty we fall back to the descriptor/quirk logic.
        val vr = desc?.verifiedSampleRates
        val lacks44k1 = if (!vr.isNullOrEmpty()) {
            vr.none { it in setOf(44100, 88200, 176400, 352800) }
        } else {
            lacks44k1Clock || (desc != null && desc.hasClockInfo && !desc.has44k1Clock)
        }

        return DacProfile(
            vid = vid, pid = pid, name = name,
            useSystemRoute = useSystemRoute,
            lacks44k1Clock = lacks44k1,
            skipSetCur = skipSetCur,
            wireBits = wireBits,
            tryBitPerfectApi = tryBitPerfectApi,
            featureUnitId = fuId,
            featureUnitChannel = fuCh,
            featureUnitChannels = fuChs,
            featureUnitMinDb = fuMin,
            featureUnitMaxDb = fuMax,
            featureUnitResDb = fuRes,
            urbPacketsOverride = urbPacketsOverride,
            ctlMsgDelayUs = ctlMsgDelayUs,
            ifaceDelayUs = ifaceDelayUs,
            forceIfaceReset = forceIfaceReset,
            forceUac1 = forceUac1,
            skipSetCurQuirk = skipSetCurQuirk,
            forceSoftwareVolume = forceSoftwareVolume,
            verifiedSampleRates = vr ?: emptyList(),
            autoProbe = autoProbe
        )
    }

    companion object {
        // ── Known DACs ─────────────────────────────────────────
        // [P4] Quirk DB narrowed to *firmware-bug* fields only. wireBits and the
        // hardware-volume Feature Unit are no longer hand-written — they now come
        // straight from native descriptor auto-detection (P1/P3). A new standard UAC
        // DAC needs NO entry here at all.

        /** TTGK Audio (pid=33C0) — our reference DAC. Full UAC2, all rates. */
        val TTGK_REFERENCE = DacQuirk(0x3302, 0x33C0, "TTGK Audio (reference)",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000
            // [2026-10-22] 曾试过强推硬件音量（FU id=2 quirk 注入），被实证推翻：
            // FU2 的 SET_CUR(-12dB) 后 GET_CUR 读回 0.0dB——值都存不进影子寄存器，
            // 比 Realtek（存值不作用）死得更透。强推会被运行期回读校验自动踢回软件
            // 增益，无意义。TTGK 稳定走软件增益（packer 已验证全位深相乘，最低 -70dB）。
        )

        /** TTGK CX31993 (pid=33D8) — descriptor isomorphic to 33C0. */
        val TTGK_33D8 = DacQuirk(0x3302, 0x33D8, "CX31993 (3302:33d8)",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** TTGK CX31993+97220 HiFi (pid=3382) — descriptor isomorphic to 33D8. */
        val TTGK_3382 = DacQuirk(0x3302, 0x3382, "CX31993+97220 HiFi (3302:3382)",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** TTGK Note (pid=201D) — UAC2 adaptive, multi-alt, SET_CUR clock works. */
        val TTGK_NOTE = DacQuirk(0x3302, 0x201D, "TTGK Note",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** FiiO BTR5 (2972:0047) — UAC2 async, multi-alt, explicit feedback. */
        val VID2972_0047 = DacQuirk(0x2972, 0x0047, "FiiO BTR5",
            useSystemRoute = false, ctlMsgDelayUs = 1000)

        /** 2D13:A001 "USB HiFi Audio" — S32_LE wire, buggy SET_CUR (locks clock). */
        val HIFI_A001 = DacQuirk(0x2D13, 0xA001, "USB HiFi Audio (2D13:A001)",
            useSystemRoute = false, skipSetCur = true,
            skipSetCurQuirk = true)

        /** Realtek USB2.0 Audio (0BDA:4BA6) — UAC2 adaptive, 44.1k AND 48k both OK.
         *  [2026-10-22] 硬件音量实测是"画的"：FU3/FU16 全部应答 SET/GET 回环（寄存器
         *  文件活着）但从不衰减音频（用户两次实锤滑块不变声）。10:41 的功能探测还因此
         *  把原本正常的软件增益弄成死音量——强制软件音量，恢复可控衰减。 */
        val REALTEK_4BA6 = DacQuirk(0x0BDA, 0x4BA6, "Realtek USB2.0 Audio (0BDA:4BA6)",
            useSystemRoute = false,
            ctlMsgDelayUs = 1000,
            forceSoftwareVolume = true)

        /** Qudelix-5K (0A12:4007) — UAC1 synchronous, full-speed 96k/24bit. */
        val QUDELIX_5K = DacQuirk(0x0A12, 0x4007, "Qudelix-5K USB DAC",
            useSystemRoute = false,
            urbPacketsOverride = 16, ctlMsgDelayUs = 2000)

        /** Moondrop Chu II DSP (31B2:0113) — UAC1 synchronous DSP IEM, 24-bit wire. */
        val CHU2_DSP = DacQuirk(0x31B2, 0x0113, "Moondrop Chu II DSP",
            useSystemRoute = false,
            ctlMsgDelayUs = 1000)

        /** CX31993 (0BDA:0023) — clone, isomorphic to TTGK Note; system-route silent. */
        val CX31993_0BDA_0023 = DacQuirk(0x0BDA, 0x0023, "CX31993 (0bda:0023)",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** CX31993 384Khz HIFI (31B2:0111) — isomorphic to Note/0BDA:0023. */
        val CX31993_31B2_0111 = DacQuirk(0x31B2, 0x0111, "CX31993 384K HIFI (31b2:0111)",
            useSystemRoute = false,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** USB Audio (31B2:1112) — UAC1 全速。FU 有应答（-50~0dB）且 SET_CUR 全 ACK，
         *  但固件从不真正衰减（用户实测：滑块拖到 10% 仍满刻度，peak 恒 1.00）。
         *  「存值但不应用」型固件回环探测抓不到 → 强制软件增益保底。 */
        val USB_AUDIO_31B2_1112 = DacQuirk(0x31B2, 0x1112, "USB Audio (31B2:1112)",
            useSystemRoute = false,
            forceSoftwareVolume = true)

        // ── Lookup ─────────────────────────────────────────────

        private val byKey: Map<Pair<Int, Int>, DacQuirk> = listOf(
            TTGK_REFERENCE, TTGK_33D8, TTGK_3382, TTGK_NOTE, VID2972_0047, HIFI_A001,
            REALTEK_4BA6, QUDELIX_5K, CHU2_DSP, CX31993_0BDA_0023, CX31993_31B2_0111,
            USB_AUDIO_31B2_1112
        ).associateBy { it.vid to it.pid }

        /** Look up a known quirk, or return a generic safe default (system-route). */
        fun findQuirk(vid: Int, pid: Int): DacQuirk =
            byKey[vid to pid] ?: DacQuirk(
                vid, pid, "USB DAC (${vid.toString(16)}:${pid.toString(16)})",
                // [P2] 未知 DAC：先试 Host 独占(bit-perfect)，配静音看门狗回退 Oboe 系统路由
                useSystemRoute = false, autoProbe = true
            )
    }
}

/**
 * Merged per-DAC profile consumed by the playback pipeline.
 * Constructed by [DacQuirk.merge] from hand quirks + live descriptor.
 * All fields are preserved from the previous hand-written [DacProfile] so existing
 * call sites in MusicService / UsbDacManager / MsebDeviceAdapt keep working.
 */
data class DacProfile(
    val vid: Int,
    val pid: Int,
    val name: String,

    val useSystemRoute: Boolean = true,
    val lacks44k1Clock: Boolean = false,
    val skipSetCur: Boolean = false,
    val wireBits: Int = 16,
    val tryBitPerfectApi: Boolean = false,

    val featureUnitId: Int = 0,
    val featureUnitChannel: Int = 1,
    val featureUnitChannels: Int = 2,
    val featureUnitMinDb: Float = -127.0f,
    val featureUnitMaxDb: Float = 0.0f,
    val featureUnitResDb: Float = 1.0f,

    val urbPacketsOverride: Int = 0,
    val ctlMsgDelayUs: Int = 0,
    val ifaceDelayUs: Int = 0,
    val forceIfaceReset: Boolean = false,
    val forceUac1: Boolean = false,
    val skipSetCurQuirk: Boolean = false,

    /** [2026-10-22] 强制软件音量（硬件 FU 已实证不衰减时）。Mirrors [DacQuirk.forceSoftwareVolume]. */
    val forceSoftwareVolume: Boolean = false,

    /** [P3] Rates the DAC actually accepted via SET_CUR+GET_CUR probe. Empty = no
     *  probe data; when non-empty it is authoritative for rate support. */
    val verifiedSampleRates: List<Int> = emptyList(),

    /** [P2] Mirrors [DacQuirk.autoProbe]; true when this profile came from the generic
     *  unknown-DAC default and a silence watchdog is therefore supervising the Host
     *  Exclusive attempt. */
    val autoProbe: Boolean = false
) {
    companion object {
        /** Look up / build the merged profile for this VID/PID.
         *  Quirk (hand) + live descriptor (native auto-detect) merged. */
        fun find(vid: Int, pid: Int): DacProfile {
            return DacQuirk.findQuirk(vid, pid).merge(UsbDacManager.activeDescriptor)
        }

        /** True if this VID/PID is known to lack 44.1 kHz clock hardware. */
        fun lacks44k1(vid: Int, pid: Int): Boolean = find(vid, pid).lacks44k1Clock

        /** True if this VID/PID should skip SET_CUR in native layer. */
        fun shouldSkipSetCur(vid: Int, pid: Int): Boolean = find(vid, pid).skipSetCur

        /** Correct wire-format bits for this DAC (descriptor-derived or quirk override). */
        fun wireBitsFor(vid: Int, pid: Int): Int = find(vid, pid).wireBits

        /** True if this DAC should use Oboe system-route instead of USB Host Exclusive.
         *  [P2] A DAC judged silent by the auto-probe watchdog (forced Oboe for this
         *  session) always routes to Oboe, even if its quirk says otherwise. */
        fun shouldUseSystemRoute(vid: Int, pid: Int): Boolean {
            if (UsbDacManager.isForcedOboe(vid, pid)) return true
            return find(vid, pid).useSystemRoute
        }
    }
}
