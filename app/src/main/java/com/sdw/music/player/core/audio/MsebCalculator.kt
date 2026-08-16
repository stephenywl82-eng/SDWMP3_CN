package com.sdw.music.player

import android.content.Context

/**
 * MSEB (MageSound 8-Ball) — 11-dimension psychoacoustic tone control.
 * 11 perceptual sliders → 5 native biquad EQ bands via matrix superposition.
 *
 * Band allocation:
 *   [0] 60 Hz   — Sub-bass depth + bass texture LF bleed
 *   [1] 200 Hz  — Temperature warm LF + thickness + bass texture body
 *   [2] 2.5 kHz — Vocal forward + female overtones (dedicated presence band)
 *   [3] 5.8 kHz — Sibilance (wide) + sibilance LF + impulse response edge
 *   [4] 10 kHz  — Sibilance HF + air + temperature cool HF
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
    val impulseResponse: Float = 0f    // -10 (soft/slow)   .. +10 (fast/attack)
) {
    val isFlat: Boolean get() =
        temperature == 0f && sibilance == 0f && subBass == 0f &&
        thickness == 0f && vocalForward == 0f && air == 0f &&
        bassTexture == 0f && femaleOvertones == 0f &&
        sibilanceLf == 0f && sibilanceHf == 0f && impulseResponse == 0f
}

object MsebCalculator {

    /** Fixed center frequencies for the 5 bands (Hz). */
    val BAND_FREQS = floatArrayOf(60f, 200f, 2500f, 5800f, 10000f)

    /**
     * 11-dimension → 5-band superposition matrix.
     *
     * Band[0] 60 Hz  : subBass (primary) + bassTexture (gentle LF bleed)
     * Band[1] 200 Hz : temperature warm + thickness + bassTexture (body zone)
     * Band[2] 2.5kHz : vocalForward + femaleOvertones (presence/shimmer)
     * Band[3] 5.8kHz : sibilance wide + sibilanceLf narrow + impulseResponse attack edge
     * Band[4] 10kHz : sibilanceHf + air + temperature cool (treble composite)
     */
    fun calculateGains(params: MsebParams): FloatArray {
        val g = FloatArray(5)

        // Band 0 — Sub-bass (60 Hz)
        g[0] = params.subBass * 0.40f + params.bassTexture * 0.20f

        // Band 1 — Low-mid body (200 Hz)
        g[1] = params.temperature * 0.20f + params.thickness * 0.30f + params.bassTexture * 0.20f

        // Band 2 — Vocal presence (2.5 kHz)
        g[2] = params.vocalForward * 0.25f + params.femaleOvertones * 0.30f

        // Band 3 — Sibilance + impulse attack edge (5.8 kHz)
        val totalSibilance = params.sibilance + params.sibilanceLf
        g[3] = -totalSibilance * 0.30f + params.impulseResponse * 0.18f

        // Band 4 — Treble / air (10 kHz)
        g[4] = -params.temperature * 0.20f + params.air * 0.35f + -params.sibilanceHf * 0.30f

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
            impulseResponse = p.getFloat(KEY_IMP,   0f)
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
