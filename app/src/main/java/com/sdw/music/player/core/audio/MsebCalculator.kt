package com.sdw.music.player
import com.sdw.music.player.core.util.floatValue

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
 *   [4] 425 Hz  — Thickness / body (ERB)
 *   [5] 992 Hz  — Vocal body (ERB)
 *   [6] 2.05 kHz — Vocal presence + female overtones (ERB)
 *   [7] 4.02 kHz — Sibilance LF + impulse attack (ERB)
 *   [8] 7.71 kHz — Sibilance HF + female shimmer (ERB)
 *   [9] 14.6 kHz — Air + temperature cool (ERB)
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
    // 【V8.3】混合标度：低频 <250Hz 保持手挑（32/60/120/250，近似 1~1.5 倍频程，控下潜命门）；
    // 中高频 ≥250Hz 对齐 ERB 心理声学标度（等效矩形带宽等间隔，贴人耳临界频带）。
    // 精确 ERB 值：425.3 / 991.9 / 2049.4 / 4022.8 / 7705.8 / 14578.9
    val BAND_FREQS = floatArrayOf(32f, 60f, 120f, 250f, 425f, 992f, 2049f, 4023f, 7706f, 14579f)

    /** Fixed Q per band — narrower in dense LF/HF regions, wider in sparse mid. */
    val BAND_QS = floatArrayOf(1.0f, 1.0f, 1.2f, 0.9f, 0.8f, 0.8f, 1.0f, 1.0f, 1.1f, 1.1f)

    /**
     * 11-dimension → 10-band superposition matrix (主观听觉频点分布).
     *
     * Band[0] 32 Hz  : subBass rumble
     * Band[1] 60 Hz  : subBass depth + bassTexture LF
     * Band[2] 120 Hz : bassTexture body + subBass bleed
     * Band[3] 250 Hz : temperature warm + thickness
     * Band[4] 425 Hz : thickness body + temperature
     * Band[5] 992 Hz : vocal body
     * Band[6] 2.05kHz: vocalForward presence + femaleOvertones
     * Band[7] 4.02kHz: sibilance LF + impulse attack edge
     * Band[8] 7.71kHz: sibilance HF + female shimmer
     * Band[9] 14.6kHz: air + temperature cool
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

        // Band 4 — Thickness body (425 Hz)
        g[4] = params.thickness * 0.40f + params.temperature * 0.10f

        // Band 5 — Vocal body (1 kHz)
        g[5] = params.vocalForward * 0.20f

        // Band 6 — Vocal presence + female overtones (2.05 kHz)
        g[6] = params.vocalForward * 0.35f + params.femaleOvertones * 0.30f

        // Band 7 — Sibilance LF（4.02 kHz；impulseResponse 已改为时域瞬态整形，不再叠加于此）
        g[7] = params.sibilance * 0.25f + params.sibilanceLf * 0.30f

        // Band 8 — Sibilance HF + female shimmer (7.71 kHz)
        g[8] = params.sibilanceHf * 0.30f + params.femaleOvertones * 0.10f

        // Band 9 — Air + temperature cool (14.6 kHz)
        g[9] = params.air * 0.35f - params.temperature * 0.15f

        for (i in g.indices) {
            g[i] = g[i].coerceIn(-6f, 6f)
        }

        return g
    }

    /**
     * 【V8.184】主观描述。两条硬约束：
     *  ① 全部用短词（deep / loose / thick / warm…），不用 "loose bass"、"forward vocal" 这种
     *     双词短语 —— 它要塞进 MSEB 卡片副标题和播放页那枚小胶囊，一行放不下。
     *  ② 最多只取 3 个（低频 → 中低 → 中高 → 高频 → 瞬态 → 空间），按听感权重排序后截断。
     * 原来会把二十多个维度一股脑罗列出来，卡片那行是 maxLines=1，实际只露出半截。
     */
    fun describe(params: MsebParams): String {
        if (params.isFlat) return "Flat / bypass"
        val t = 1f
        val parts = mutableListOf<String>()
        if (params.subBass > t) parts.add("deep") else if (params.subBass < -t) parts.add("lean")
        if (params.bassTexture > t) parts.add("tight") else if (params.bassTexture < -t) parts.add("loose")
        if (params.thickness > t) parts.add("thick") else if (params.thickness < -t) parts.add("thin")
        if (params.temperature > t) parts.add("warm") else if (params.temperature < -t) parts.add("crisp")
        if (params.vocalForward > t) parts.add("vocal+") else if (params.vocalForward < -t) parts.add("vocal-")
        if (params.femaleOvertones > t) parts.add("sweet") else if (params.femaleOvertones < -t) parts.add("dry")
        // 齿音与 its LF/HF 两段描述重复（都是 crisp/soft），只留总开关那一条，
        // 细分维度留到 |v| > 5 才提一句，避免和 "crisp"/"warm" 撞词。
        if (params.sibilance > t) parts.add("bright")
        else if (params.sibilance < -t) parts.add("smooth")
        // 齿音 LF/HF 细分只在总开关没表态时才提，免得和上面那条撞出两个「crisp」
        else if (params.sibilanceLf > 5f || params.sibilanceHf > 5f) parts.add("crisp HF")
        else if (params.sibilanceLf < -5f || params.sibilanceHf < -5f) parts.add("soft HF")
        if (params.air > t) parts.add("airy") else if (params.air < -t) parts.add("dark")
        if (params.impulseResponse > t) parts.add("snap") else if (params.impulseResponse < -t) parts.add("soft")
        if (params.soundstage > t) parts.add("wide") else if (params.soundstage < -t) parts.add("narrow")
        if (params.imaging > t) parts.add("focus") else if (params.imaging < -t) parts.add("diffuse")
        return parts.take(3).joinToString(" · ").ifEmpty { "Light touch" }
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
    private const val KEY_DTS_ENABLED = "dts_enabled"
    private const val KEY_DTS_SURROUND = "dts_surround"
    private const val KEY_DTS_HEIGHT = "dts_height"
    private const val KEY_PRESET_NAME = "preset_name"

    fun load(context: Context): MsebParams {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return MsebParams(
            temperature     = p.floatValue(KEY_TEMP,  0f),
            sibilance       = p.floatValue(KEY_SIB,   0f),
            subBass         = p.floatValue(KEY_SUB,   0f),
            thickness       = p.floatValue(KEY_THICK, 0f),
            vocalForward    = p.floatValue(KEY_VOCAL, 0f),
            air             = p.floatValue(KEY_AIR,   0f),
            bassTexture     = p.floatValue(KEY_BTEX,  0f),
            femaleOvertones = p.floatValue(KEY_FEM,   0f),
            sibilanceLf     = p.floatValue(KEY_SIBLF, 0f),
            sibilanceHf     = p.floatValue(KEY_SIBHF, 0f),
            impulseResponse = p.floatValue(KEY_IMP,   0f),
            soundstage      = p.floatValue(KEY_STAGE, 0f),
            imaging         = p.floatValue(KEY_IMG,   0f)
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

    /** 【V8.19】DTS 环绕（M/S 声场）独立快捷开关 — 不影响 MSEB 其他调音 */
    fun isDtsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DTS_ENABLED, false)

    fun setDtsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DTS_ENABLED, enabled)
            .apply()
    }

    /** 【V8.20】当前应用中的 MSEB 预设名（"" = 手动调音/未命名）。 */
    fun getPresetName(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRESET_NAME, "") ?: ""

    /** 【V8.20】记录当前应用的预设名；手动调滑块时应传 "" 清除。 */
    fun setPresetName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET_NAME, name)
            .apply()
    }

    /**
     * 【V8.19】DTS 开关开启时应生效的声场参数。
     * 用户没调过声场/结像滑块（都为 0）时返回内置默认环绕参数，
     * 保证"开 DTS 就有环绕效果"，不依赖 MSEB 滑块。
     * 【V8.162】恢复原设计：未调滑块时 width 兜底 5f（width=1.4）。
     * 用户调过滑块则跟随滑块（DTS 作为总开关控制是否应用）。
     */
    fun dtsStageParams(context: Context): Pair<Float, Float> {
        val params = load(context)
        val ss = params.soundstage
        val img = params.imaging
        val hasUserStage = kotlin.math.abs(ss) > 0.1f || kotlin.math.abs(img) > 0.1f
        return if (hasUserStage) ss to img else 5f to 0f
    }

    /**
     * 【V8.172】DTS 虚拟环绕的两个高级参数，与宽度/结像正交。
     *
     * surround 默认 5（≈0.4 线性）：旧版环绕量是 (width-1)*0.5 再乘 side 幅度，
     * 实际回灌只有 0.01~0.04，低于可辨阈值；独立成旋钮后默认就给到能听见的量级。
     * height 默认 5（0.5）：耳廓共振 + 左右耳权重差，决定"两侧变宽"还是"有前后"。
     */
    data class DtsStageEx(
        val surround: Float = 5f,
        val height: Float = 5f
    ) {
        val isDefault: Boolean get() = surround == 5f && height == 5f
    }

    fun getDtsStageEx(context: Context): DtsStageEx {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DtsStageEx(
            surround = p.floatValue(KEY_DTS_SURROUND, 5f),
            height = p.floatValue(KEY_DTS_HEIGHT, 5f)
        )
    }

    fun saveDtsStageEx(context: Context, ex: DtsStageEx) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_DTS_SURROUND, ex.surround)
            .putFloat(KEY_DTS_HEIGHT, ex.height)
            .apply()
    }
}
