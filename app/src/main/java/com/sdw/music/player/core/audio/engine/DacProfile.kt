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
 * [DacQuirk]. A new, spec-compliant DAC therefore needs **zero** code — it falls
 * through to the generic default (Oboe system-route) and its wire format / hardware
 * volume / 44.1k support are all auto-detected. That is the end-state:
 * "插上就用，只有真正奇葩的才需要往 quirk 库里加一条。"
 *
 * Routing (3 paths, ordered by priority):
 *   Path 1 — USB Host Exclusive (Bit-Perfect): DACs with [DacQuirk.useSystemRoute]=false
 *   Path 2 — Oboe System Route: the safe default for unknown/standard DACs
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
    val altCount: Int
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
                altCount = int("alts")
            )
        }
    }
}

/**
 * Hand-written per-DAC quirk entry. **Only DACs with genuinely non-standard
 * firmware behavior need an entry here** — standard UAC-compliant DACs need none.
 *
 * The booleans/flags are true quirk decisions (firmware lies, clock locks, etc.).
 * `wireBitsOverride` and `featureUnit` are *fallbacks* for when the native generic
 * probe cannot read the descriptor (rare); leave them at default and the live
 * [DacDescriptor] auto-detects instead.
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

    // ── Native-probe fallback overrides (default = auto-detect from descriptor) ──
    /** 0 = derive from descriptor (max subslot * 8). Non-zero = hard override. */
    val wireBitsOverride: Int = 0,
    /** Non-null = Feature Unit override (native probe known to fail on this firmware). */
    val featureUnit: FeatureUnit? = null,

    // ── snd-usb-audio flag equivalents (native Linux quirk table mirror) ──
    val urbPacketsOverride: Int = 0,
    val ctlMsgDelayUs: Int = 0,
    val ifaceDelayUs: Int = 0,
    val forceIfaceReset: Boolean = false,
    val forceUac1: Boolean = false,
    val skipSetCurQuirk: Boolean = false
) {
    /** Verified hardware-volume Feature Unit params (UAC2 int16.16 dB). */
    data class FeatureUnit(
        val id: Int, val channel: Int, val channels: Int,
        val minDb: Float, val maxDb: Float, val resDb: Float
    )

    /** Merge hand quirks over live descriptor auto-detection → final [DacProfile]. */
    fun merge(desc: DacDescriptor?): DacProfile {
        val wireBits = when {
            wireBitsOverride > 0 -> wireBitsOverride
            desc != null && desc.wireBits > 0 -> desc.wireBits
            else -> 16
        }

        val fu = featureUnit
        val fuId: Int
        val fuCh: Int
        val fuChs: Int
        val fuMin: Float
        val fuMax: Float
        val fuRes: Float
        if (fu != null) {
            fuId = fu.id; fuCh = fu.channel; fuChs = fu.channels
            fuMin = fu.minDb; fuMax = fu.maxDb; fuRes = fu.resDb
        } else if (desc != null && desc.featureUnitId > 0) {
            fuId = desc.featureUnitId; fuCh = desc.featureUnitChannel; fuChs = desc.featureUnitChannels
            fuMin = desc.featureUnitMinDb; fuMax = desc.featureUnitMaxDb; fuRes = desc.featureUnitResDb
        } else {
            fuId = 0; fuCh = 1; fuChs = 2; fuMin = -127.0f; fuMax = 0.0f; fuRes = 1.0f
        }

        // A descriptor that *successfully probed* and exposes no 44.1k range is treated
        // as lacking it (safer than trusting a firmware that advertises rates the clock
        // can't make). If the probe failed (no clock info), we can't conclude anything —
        // keep the quirk default so we never regress a DAC whose clock ranges are unreadable.
        val lacks44k1 = lacks44k1Clock || (desc != null && desc.hasClockInfo && !desc.has44k1Clock)

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
            skipSetCurQuirk = skipSetCurQuirk
        )
    }

    companion object {
        // ── Known DACs ─────────────────────────────────────────
        // Only the genuinely non-standard bits are handwritten. wireBits / Feature
        // Unit are kept as explicit fallbacks for now (hardware-verified values);
        // they can be dropped once native auto-detection is confirmed per-DAC, after
        // which a new standard DAC needs no entry at all.

        /** TTGK Audio (pid=33C0) — our reference DAC. Full UAC2, all rates. */
        val TTGK_REFERENCE = DacQuirk(0x3302, 0x33C0, "TTGK Audio (reference)",
            useSystemRoute = false,
            wireBitsOverride = 16,
            featureUnit = DacQuirk.FeatureUnit(2, 1, 2, -74.0f, 0.0f, 0.5f),
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** TTGK CX31993 (pid=33D8) — descriptor isomorphic to 33C0. */
        val TTGK_33D8 = DacQuirk(0x3302, 0x33D8, "CX31993 (3302:33d8)",
            useSystemRoute = false,
            wireBitsOverride = 16,
            featureUnit = DacQuirk.FeatureUnit(2, 1, 2, -74.0f, 0.0f, 0.5f),
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** TTGK CX31993+97220 HiFi (pid=3382) — descriptor isomorphic to 33D8. */
        val TTGK_3382 = DacQuirk(0x3302, 0x3382, "CX31993+97220 HiFi (3302:3382)",
            useSystemRoute = false,
            wireBitsOverride = 16,
            featureUnit = DacQuirk.FeatureUnit(2, 1, 2, -74.0f, 0.0f, 0.5f),
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** TTGK Note (pid=201D) — UAC2 adaptive, multi-alt, SET_CUR clock works. */
        val TTGK_NOTE = DacQuirk(0x3302, 0x201D, "TTGK Note",
            useSystemRoute = false, wireBitsOverride = 16,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** FiiO BTR5 (2972:0047) — UAC2 async, multi-alt, explicit feedback. */
        val VID2972_0047 = DacQuirk(0x2972, 0x0047, "FiiO BTR5",
            useSystemRoute = false, wireBitsOverride = 16, ctlMsgDelayUs = 1000)

        /** 2D13:A001 "USB HiFi Audio" — S32_LE wire, buggy SET_CUR (locks clock). */
        val HIFI_A001 = DacQuirk(0x2D13, 0xA001, "USB HiFi Audio (2D13:A001)",
            useSystemRoute = false, skipSetCur = true, wireBitsOverride = 32,
            skipSetCurQuirk = true)

        /** Realtek USB2.0 Audio (0BDA:4BA6) — UAC2 adaptive, 44.1k AND 48k both OK. */
        val REALTEK_4BA6 = DacQuirk(0x0BDA, 0x4BA6, "Realtek USB2.0 Audio (0BDA:4BA6)",
            useSystemRoute = false, wireBitsOverride = 16,
            featureUnit = DacQuirk.FeatureUnit(16, 1, 2, -65.25f, 0.0f, 0.38f),
            ctlMsgDelayUs = 1000)

        /** Qudelix-5K (0A12:4007) — UAC1 synchronous, full-speed 96k/24bit. */
        val QUDELIX_5K = DacQuirk(0x0A12, 0x4007, "Qudelix-5K USB DAC",
            useSystemRoute = false, wireBitsOverride = 24,
            urbPacketsOverride = 16, ctlMsgDelayUs = 2000)

        /** Moondrop Chu II DSP (31B2:0113) — UAC1 synchronous DSP IEM, 24-bit wire. */
        val CHU2_DSP = DacQuirk(0x31B2, 0x0113, "Moondrop Chu II DSP",
            useSystemRoute = false, wireBitsOverride = 24, ctlMsgDelayUs = 1000)

        /** CX31993 (0BDA:0023) — clone, isomorphic to TTGK Note; system-route silent. */
        val CX31993_0BDA_0023 = DacQuirk(0x0BDA, 0x0023, "CX31993 (0bda:0023)",
            useSystemRoute = false, wireBitsOverride = 16,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        /** CX31993 384Khz HIFI (31B2:0111) — isomorphic to Note/0BDA:0023. */
        val CX31993_31B2_0111 = DacQuirk(0x31B2, 0x0111, "CX31993 384K HIFI (31b2:0111)",
            useSystemRoute = false, wireBitsOverride = 16,
            ctlMsgDelayUs = 2000, ifaceDelayUs = 5000)

        // ── Lookup ─────────────────────────────────────────────

        private val byKey: Map<Pair<Int, Int>, DacQuirk> = listOf(
            TTGK_REFERENCE, TTGK_33D8, TTGK_3382, TTGK_NOTE, VID2972_0047, HIFI_A001,
            REALTEK_4BA6, QUDELIX_5K, CHU2_DSP, CX31993_0BDA_0023, CX31993_31B2_0111
        ).associateBy { it.vid to it.pid }

        /** Look up a known quirk, or return a generic safe default (system-route). */
        fun findQuirk(vid: Int, pid: Int): DacQuirk =
            byKey[vid to pid] ?: DacQuirk(vid, pid, "USB DAC (${vid.toString(16)}:${pid.toString(16)})")
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
    val skipSetCurQuirk: Boolean = false
) {
    companion object {
        /** Look up / build the merged profile for this VID/PID.
         *  Quirk (hand) + live descriptor (native auto-detect) merged. */
        fun find(vid: Int, pid: Int): DacProfile {
            return DacQuirk.findQuirk(vid, pid).merge(UsbDacManager.getActiveDescriptor())
        }

        /** True if this VID/PID is known to lack 44.1 kHz clock hardware. */
        fun lacks44k1(vid: Int, pid: Int): Boolean = find(vid, pid).lacks44k1Clock

        /** True if this VID/PID should skip SET_CUR in native layer. */
        fun shouldSkipSetCur(vid: Int, pid: Int): Boolean = find(vid, pid).skipSetCur

        /** Correct wire-format bits for this DAC (descriptor-derived or quirk override). */
        fun wireBitsFor(vid: Int, pid: Int): Int = find(vid, pid).wireBits

        /** True if this DAC should use Oboe system-route instead of USB Host Exclusive. */
        fun shouldUseSystemRoute(vid: Int, pid: Int): Boolean = find(vid, pid).useSystemRoute
    }
}
