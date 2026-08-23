package com.sdw.music.player

import android.content.Context

/**
 * MSEB (MageSound 8-Ball) — 11-dimension psychoacoustic tone control.
 * 11 perceptual sliders → 10 native biquad EQ bands via matrix superposition.
 *
 * 主观听觉频点分布（低频+高频加密、中频稀疏，贴等响曲线）：
 *   [0]  32 Hz  — Sub-bass rumble
 *   [1]  60 Hz  — Sub-bass depth + bass texture LF
 *   [2] 120 Hz  — Bass texture body / punch
 *   [3] 250 Hz  — Temperature warm + thickness
 *   [4] 500 Hz  — Thickness / body
 *   [5]  1 kHz  — Vocal body
 *   [6] 2.5 kHz — Vocal presence + female overtones
 *   [7]  5 kHz  — Sibilance LF + impulse attack
 *   [8]  8 kHz  — Sibilance HF + female shimmer
 *   [9] 12 kHz  — Air + temperature cool
 */
data class MsebParams(
    // ── 基础 6 维度 ──
    val temperature: Float = 0f,       // -10 (cool/crisp) .. +10 (warm/analog)
    val sibilance: Float = 0f,         // -10 (smooth)      .. +10 (bright)
    val subBass: Float = 0f,           // -10 (lean)        .. +10 (deep/rumbly)
    val thickness: Float = 0f,         // -10 (thin)        .. +10 (thick/lush)
    val vocalForward: Float = 0f,      // -10 (distant)     .. +10 (intimate/forward)
    val air: Float = 0f,               // -10 (dark)        .. +10 (airy/open)
    // ── 高阶细分 4 维度 ──
    val bassTexture: Float = 0f,       // -10 (loose)       .. +10 (tight/punchy)
    val femaleOvertones: Float = 0f,   // -10 (dry)         .. +10 (sweet/shimmer)
    val sibilanceLf: Float = 0f,       // -10 (soft LF)     .. +10 (crisp LF)
    val sibilanceHf: Float = 0f,       // -10 (soft HF)     .. +10 (crisp HF)
    // ── 瞬态响应 ──
    val impulseResponse: Float = 0f,   // -10 (soft/slow)   .. +10 (fast/attack)
    // ── 空间类（跨声道 M/S 处理，非 EQ）──
    val soundstage: Float = 0f,        // -10 (narrow/mono) .. +10 (wide)
    val imaging: Float = 0f            // -10 (diffuse)     .. +10 (focused/center)
) {
    val isFlat: Boolean get() =
        temperature == 0f && sibilance == 0f && subBass == 0f &&
        thickness == 0f && vocalForward == 0f && air == 0f &&
        bassTexture == 0f && femaleOvertones == 0f &&
        sibilanceLf == 0f && sibilanceHf == 0f && impulseResponse == 0f &&
        soundstage == 0f && imaging == 0f
}

object MsebCalculator {

    /** Fixed center frequencies for the 10 bands (Hz). */
    val BAND_FREQS = floatArrayOf(32f, 60f, 120f, 250f, 500f, 1000f, 2500f, 5000f, 8000f, 12000f)

    /** Fixed Q per band — narrower in dense LF/HF regions, wider in sparse mid. */
    val BAND_QS = floatArrayOf(1.0f, 1.0f, 1.2f, 0.9f, 0.8f, 0.8f, 1.0f, 1.0f, 1.1f, 1.1f)

    /**
     * 11-dimension → 10-band superposition matrix (主观听觉频点分布).
     *
     * Band[0] 32 Hz  : subBass rumble
     * Band[1] 60 Hz  : subBass depth + bassTexture LF
     * Band[2] 120 Hz : bassTexture body + subBass bleed
     * Band[3] 250 Hz : temperature warm + thickness
     * Band[4] 500 Hz : thickness body + temperature
     * Band[5] 1 kHz  : vocal body
     * Band[6] 2.5kHz : vocalForward presence + femaleOvertones
     * Band[7] 5 kHz  : sibilance LF + impulse attack edge
     * Band[8] 8 kHz  : sibilance HF + female shimmer
     * Band[9] 12 kHz : air + temperature cool
     */
    fun calculateGains(params: MsebParams): FloatArray {
        val g = FloatArray(10)

        // Band 0 — Sub-bass rumble (32 Hz)
        g[0] = params.subBass * 0.55f

        // Band 1 — Sub-bass depth (60 Hz)
        g[1] = params.subBass * 0.30f + params.bassTexture * 0.25f

        // Band 2 — Bass texture body (120 Hz)
        g[2] = params.bassTexture * 0.45f + params.subBass * 0.15f

        // Band 3 — Warm / thickness low-mid (250 Hz)
        g[3] = params.temperature * 0.25f + params.thickness * 0.20f

        // Band 4 — Thickness body (500 Hz)
        g[4] = params.thickness * 0.40f + params.temperature * 0.10f

        // Band 5 — Vocal body (1 kHz)
        g[5] = params.vocalForward * 0.20f

        // Band 6 — Vocal presence + female overtones (2.5 kHz)
        g[6] = params.vocalForward * 0.35f + params.femaleOvertones * 0.30f

        // Band 7 — Sibilance LF（5 kHz；impulseResponse 已改为时域瞬态整形，不再叠加于此）
        g[7] = params.sibilance * 0.25f + params.sibilanceLf * 0.30f

        // Band 8 — Sibilance HF + female shimmer (8 kHz)
        g[8] = params.sibilanceHf * 0.30f + params.femaleOvertones * 0.10f

        // Band 9 — Air + temperature cool (12 kHz)
        g[9] = params.air * 0.35f - params.temperature * 0.15f

        for (i in g.indices) {
            g[i] = g[i].coerceIn(-6f, 6f)
        }

        return g
    }

    fun describe(params: MsebParams): String {
        if (params.isFlat) return "Flat / bypass"
        val parts = mutableListOf<String>()
        val t = 1f
        if (params.subBass > t) parts.add("deep bass")
        if (params.subBass < -t) parts.add("lean bass")
        if (params.bassTexture > t) parts.add("tight bass")
        if (params.bassTexture < -t) parts.add("loose bass")
        if (params.thickness > t) parts.add("thick")
        if (params.thickness < -t) parts.add("thin")
        if (params.temperature > t) parts.add("warm")
        if (params.temperature < -t) parts.add("crisp")
        if (params.vocalForward > t) parts.add("forward vocal")
        if (params.vocalForward < -t) parts.add("distant vocal")
        if (params.femaleOvertones > t) parts.add("sweet vocal")
        if (params.femaleOvertones < -t) parts.add("dry vocal")
        if (params.sibilance > t) parts.add("bright")
        if (params.sibilance < -t) parts.add("smooth")
        if (params.sibilanceLf > t) parts.add("crisp LF")
        if (params.sibilanceLf < -t) parts.add("soft LF")
        if (params.sibilanceHf > t) parts.add("crisp HF")
        if (params.sibilanceHf < -t) parts.add("soft HF")
        if (params.air > t) parts.add("airy")
        if (params.air < -t) parts.add("dark")
        if (params.impulseResponse > t) parts.add("fast attack")
        if (params.impulseResponse < -t) parts.add("soft transient")
        if (params.soundstage > t) parts.add("wide soundstage")
        if (params.soundstage < -t) parts.add("narrow soundstage")
        if (params.imaging > t) parts.add("focused imaging")
        if (params.imaging < -t) parts.add("diffuse imaging")
        return parts.joinToString(" · ").ifEmpty { "Light touch" }
    }

    // ── Persistence ──

    private const val PREFS_NAME = "mseb"
    private const val KEY_TEMP   = "temperature"
    private const val KEY_SIB    = "sibilance"
    private const val KEY_SUB    = "subBass"
    private const val KEY_THICK  = "thickness"
    private const val KEY_VOCAL  = "vocalForward"
    private const val KEY_AIR    = "air"
    private const val KEY_BTEX   = "bassTexture"
    private const val KEY_FEM    = "femaleOvertones"
    private const val KEY_SIBLF  = "sibilanceLf"
    private const val KEY_SIBHF  = "sibilanceHf"
    private const val KEY_IMP    = "impulseResponse"
    private const val KEY_STAGE  = "soundstage"
    private const val KEY_IMG    = "imaging"
    private const val KEY_ENABLED = "enabled"

    fun load(context: Context): MsebParams {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return MsebParams(
            temperature     = p.getFloat(KEY_TEMP,  0f),
            sibilance       = p.getFloat(KEY_SIB,   0f),
            subBass         = p.getFloat(KEY_SUB,   0f),
            thickness       = p.getFloat(KEY_THICK, 0f),
            vocalForward    = p.getFloat(KEY_VOCAL, 0f),
            air             = p.getFloat(KEY_AIR,   0f),
            bassTexture     = p.getFloat(KEY_BTEX,  0f),
            femaleOvertones = p.getFloat(KEY_FEM,   0f),
            sibilanceLf     = p.getFloat(KEY_SIBLF, 0f),
            sibilanceHf     = p.getFloat(KEY_SIBHF, 0f),
            impulseResponse = p.getFloat(KEY_IMP,   0f),
            soundstage      = p.getFloat(KEY_STAGE, 0f),
            imaging         = p.getFloat(KEY_IMG,   0f)
        )
    }

    fun save(context: Context, params: MsebParams) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEMP,  params.temperature)
            .putFloat(KEY_SIB,   params.sibilance)
            .putFloat(KEY_SUB,   params.subBass)
            .putFloat(KEY_THICK, params.thickness)
            .putFloat(KEY_VOCAL, params.vocalForward)
            .putFloat(KEY_AIR,   params.air)
            .putFloat(KEY_BTEX,  params.bassTexture)
            .putFloat(KEY_FEM,   params.femaleOvertones)
            .putFloat(KEY_SIBLF, params.sibilanceLf)
            .putFloat(KEY_SIBHF, params.sibilanceHf)
            .putFloat(KEY_IMP,   params.impulseResponse)
            .putFloat(KEY_STAGE, params.soundstage)
            .putFloat(KEY_IMG,   params.imaging)
            .apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
