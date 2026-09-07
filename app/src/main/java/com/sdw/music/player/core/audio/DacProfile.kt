package com.sdw.music.player

/**
 * Per-DAC quirk profile for USB exclusive mode.
 *
 * Every DAC has different firmware quirks. Instead of scattering PID checks across
 * MusicService and native code, define known devices here with their capabilities
 * and let the playback pipeline read from a single source of truth.
 *
 * Native code (usb_audio_driver.cpp) still has its own low-level checks for things
 * like SET_CUR skipping, but these profiles tell the Kotlin layer what to expect.
 *
 * Routing (3 paths, ordered by priority):
 *   Path 1 — USB Host Exclusive (Bit-Perfect): known-good DACs like TTGK 33C0
 *   Path 2 — Oboe System Route: plug in any USB DAC, Oboe opens without setDeviceId,
 *            Android auto-routes to USB_HEADSET via kernel USB driver (like Resonāda)
 *   Path 3 — ExoPlayer (SRC fallback): no DAC, Bluetooth, or broken 44.1k family
 */
data class DacProfile(
    val vid: Int,
    val pid: Int,
    val name: String,

    /** True = use Oboe system-route instead of hand-rolled USB Host Exclusive.
     *  This is the safe default for unknown DACs — let the kernel USB driver
     *  handle all the low-level quirks (SET_CUR, clock negotiation, format auto-detect).
     *  Set false only for DACs we've thoroughly verified with host-mode Bit-Perfect. */
    val useSystemRoute: Boolean = true,

    /** True if the DAC physically cannot generate 44.1 kHz family (44.1/88.2/176.4/352.8).
     *  Songs with these rates will be routed to ExoPlayer for SRC. */
    val lacks44k1Clock: Boolean = false,

    /** True if SET_CUR control transfers will break this DAC (e.g. EPIPE / dead endpoint).
     *  Native open()/start() already skips SET_CUR for devices where this matters;
     *  this flag is informational for the Kotlin layer. */
    val skipSetCur: Boolean = false,

    /** Wire format bits for USB ISO OUT. Use 32 for S32_LE sub-slot DACs
     *  (mps%8==0), 24 for S24_3LE (mps%6==0), default 16 otherwise.
     *  Only used when useSystemRoute=false. */
    val wireBits: Int = 16,

    /** True = try Android 14+ BIT_PERFECT API (setPreferredMixerAttributes) before
     *  falling back to Oboe system-route. Only meaningful when useSystemRoute=true.
     *  DACs with known firmware quirks (broken SET_CUR, clock issues) can benefit
     *  from the kernel USB driver's usb_quirks tolerance via this API. */
    val tryBitPerfectApi: Boolean = false,

    /** Feature Unit hardware volume override (Salt-verified quirk).
     *  Non-zero featureUnitId = DAC has a known volume FU whose GET_MIN/GET_MAX
     *  STALLs in firmware (UAC2 optional requests the chip simply rejects).
     *  Native layer runs its generic UAC2 auto-probe first; if that fails it falls
     *  back to these params — so standard DACs self-detect, only quirky ones use
     *  this table. */
    val featureUnitId: Int = 0,
    /** Volume FU master channel. 0 = master ch0 exists (single SET_CUR);
     *  1 = no master, send per-channel ch1..chN. */
    val featureUnitChannel: Int = 1,
    /** Number of per-channel volume controls (e.g. 2 for stereo L/R). */
    val featureUnitChannels: Int = 2,
    /** dB range of the volume FU (16.16, e.g. -74.0 = -74.00 dB). */
    val featureUnitMinDb: Float = -127.0f,
    val featureUnitMaxDb: Float = 0.0f,
    val featureUnitResDb: Float = 1.0f
) {
    companion object {
        // ── Known DACs ─────────────────────────────────────────

        /** TTGK Audio (pid=33C0) — our reference DAC. Full UAC2, all rates, clean alt switch.
         *  Only DAC we trust with hand-rolled USB Host Exclusive for Bit-Perfect. */
        val TTGK_REFERENCE = DacProfile(0x3302, 0x33C0, "TTGK Audio (reference)",
            useSystemRoute = false,
            featureUnitId = 2, featureUnitChannel = 1, featureUnitChannels = 2,
            featureUnitMinDb = -74.0f, featureUnitMaxDb = 0.0f, featureUnitResDb = 0.5f)

        /** TTGK CX31993 (pid=33D8) — 用户新 DAC。descriptor 与 33C0 同构：
         *  iface1 alt=1/2/3 OUT ep 0x01 mps=192/288/384, iface2 IN fb ep 0x81,
         *  iface3 HID 音量。支持 16k~384k。Oboe system-route 下 Stream OFF 无声，
         *  改走手写 USB Host Exclusive（descriptor 自适应）。 */
        val TTGK_33D8 = DacProfile(0x3302, 0x33D8, "CX31993 (3302:33d8)",
            useSystemRoute = false,
            featureUnitId = 2, featureUnitChannel = 1, featureUnitChannels = 2,
            featureUnitMinDb = -74.0f, featureUnitMaxDb = 0.0f, featureUnitResDb = 0.5f)

        /** TTGK Note (pid=201D) — UAC2 adaptive (attributes=0x09), multi-alt.
         *  Salt verified: endpoint=0x04, alt=1 mps=196 (16bit), alt=2 mps=294 (24bit),
         *  alt=3 mps=392 (32bit). clockSource=6, SET_CUR to clock=6 succeeds (ret=4).
         *  Adaptive endpoint syncs via SOF, no feedback endpoint.
         *  Hand-rolled USB Host Exclusive. */
        val TTGK_NOTE = DacProfile(0x3302, 0x201D, "TTGK Note",
            useSystemRoute = false, lacks44k1Clock = false, skipSetCur = false)

        /** vid=2972 pid=0047 (FiiO BTR5) — UAC2 async (attributes=0x05), multi-alt.
         *  Salt verified: interface=1, alt=1 mps=392 (32bit), alt=2 mps=196 (16bit),
         *  alt=3 mps=392. clockSource=40 (selector) → clock=41, SET_CUR ret=4.
         *  Async endpoint has explicit feedback 0x81. NOT broken Clock Entity.
         *  Hand-rolled USB Host Exclusive. */
        val VID2972_0047 = DacProfile(0x2972, 0x0047, "FiiO BTR5",
            useSystemRoute = false, lacks44k1Clock = false, skipSetCur = false)

        /** 2D13:A001 "USB HiFi Audio" — S32_LE wire, buggy SET_CUR.
         *  Hardware supports 44.1k but SET_CUR locks clock at 384k.
         *  Hand-rolled USB Host Exclusive with skipSetCur = alt-switch implicit lock. */
        val HIFI_A001 = DacProfile(0x2D13, 0xA001, "USB HiFi Audio (2D13:A001)",
            useSystemRoute = false, skipSetCur = true, wireBits = 32)

        /** Realtek USB2.0 Audio (0BDA:4BA6) — UAC2 adaptive, 3 alt settings (mps 248/372/496).
         *  44.1k AND 48k both supported (Salt verified 44.1k output, clockVerified=true).
         *  Adaptive endpoint auto-tracks host rate via feedback — no SET_CUR needed.
         *  Hand-rolled USB Host Exclusive. */
        val REALTEK_4BA6 = DacProfile(0x0BDA, 0x4BA6, "Realtek USB2.0 Audio (0BDA:4BA6)",
            useSystemRoute = false, lacks44k1Clock = false,
            featureUnitId = 16, featureUnitChannel = 1, featureUnitChannels = 2,
            featureUnitMinDb = -65.25f, featureUnitMaxDb = 0.0f, featureUnitResDb = 0.38f)

        /** Qudelix-5K (0A12:4007) — UAC1 synchronous DAC/AMP.
         *  Single alt=1 AudioStreaming iface, ISO OUT ep=0x03 mps=576 (full-speed 1ms).
         *  mps=576 == 96kHz × 24bit × 2ch → wire format is 24-bit S24_3LE.
         *  No explicit feedback endpoint (0x81/0x89 are HID interrupt) → synchronous,
         *  streamLoop's "no feedback = maintain timing" path fits.
         *  Sample rate switched via SET_CUR (single alt, not multi-alt).
         *  Supports 44.1/48/88.2/96 kHz. Hand-rolled USB Host Exclusive. */
        val QUDELIX_5K = DacProfile(0x0A12, 0x4007, "Qudelix-5K USB DAC",
            useSystemRoute = false, lacks44k1Clock = false, wireBits = 24)

        /** Moondrop Chu II DSP (31B2:0113) — UAC1 synchronous DSP IEM.
         *  Multi-alt AudioStreaming OUT iface (id=2):
         *    alt=1 → ep=0x04 OUT mps=388 (96kHz 16bit 2ch)
         *    alt=2 → ep=0x04 OUT mps=582 (96kHz 24bit 2ch)
         *  iface id=1 (ep=0x84 IN) is the mic/record interface, iface id=3 is HID
         *  (DSP control). Wire format 24-bit S24_3LE. Sample rate via SET_CUR.
         *  Hand-rolled USB Host Exclusive. */
        val CHU2_DSP = DacProfile(0x31B2, 0x0113, "Moondrop Chu II DSP",
            useSystemRoute = false, lacks44k1Clock = false, wireBits = 24)

        /** CX31993 (0BDA:0023) — 山寨小尾巴（抄 Realtek VID），结构同 TTGK Note：
         *  iface#1 单 AudioStreaming，alt=1 ep=0x04 mps=196 (16bit) / alt=2 mps=294 (24bit)
         *  / alt=3 mps=392 (32bit)，HID iface#2。实测在 Oboe system-route 下 Stream OFF
         *  （内核 ALSA 驱动兼容问题），改走手写 USB Host Exclusive——descriptor 驱动
         *  自适应可识别该 alt 表（同 201D 同构，201D 已跑通）。 */
        val CX31993_0BDA_0023 = DacProfile(0x0BDA, 0x0023, "CX31993 (0bda:0023)",
            useSystemRoute = false, lacks44k1Clock = false, skipSetCur = false)

        // ── Lookup ─────────────────────────────────────────────

        private val byKey: Map<Pair<Int, Int>, DacProfile> = listOf(
            TTGK_REFERENCE, TTGK_NOTE, TTGK_33D8, VID2972_0047, HIFI_A001, REALTEK_4BA6, QUDELIX_5K, CHU2_DSP, CX31993_0BDA_0023
        ).associateBy { it.vid to it.pid }

        /** Look up a known profile, or return a generic safe default (system-route). */
        fun find(vid: Int, pid: Int): DacProfile {
            return byKey[vid to pid] ?: DacProfile(vid, pid, "USB DAC (${vid.toString(16)}:${pid.toString(16)})")
        }

        /** True if this VID/PID is known to lack 44.1 kHz clock hardware. */
        fun lacks44k1(vid: Int, pid: Int): Boolean = find(vid, pid).lacks44k1Clock

        /** True if this VID/PID should skip SET_CUR in native layer. */
        fun shouldSkipSetCur(vid: Int, pid: Int): Boolean = find(vid, pid).skipSetCur

        /** Correct wire-format bits for this DAC. */
        fun wireBitsFor(vid: Int, pid: Int): Int = find(vid, pid).wireBits

        /** True if this DAC should use Oboe system-route instead of USB Host Exclusive.
         *  For known-good DACs (TTGK 33C0) we use hand-rolled Bit-Perfect path. */
        fun shouldUseSystemRoute(vid: Int, pid: Int): Boolean = find(vid, pid).useSystemRoute
    }
}
