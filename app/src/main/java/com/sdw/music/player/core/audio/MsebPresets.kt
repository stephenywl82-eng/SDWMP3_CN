package com.sdw.music.player
import com.sdw.music.player.core.util.floatValue

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * MSEB Preset Manager — save/load/export 13-dimension presets + DSP 模块快照。
 * Storage: SharedPreferences "mseb_presets" as JSON array.
 */
data class MsebPreset(
    val name: String,
    val params: MsebParams,
    val createdAt: Long = System.currentTimeMillis(),
    /** 【V8.170】DSP 快照：预设不再只是"一组滑块"，切换时模块开关一起跟着走。 */
    val snapshot: DspSnapshot = DspSnapshot()
)

/**
 * 【V8.170】DSP 模块快照 —— 预设携带的"上下文"。
 *
 * 过去一个预设 = 一组滑块。切到「Live Soundstage」后压缩器/等响/Crossfeed/DTS
 * 全是上一次的残留状态，用户还得手动再拨一遍三个开关。现在这些开关一起进预设。
 *
 * 兼容策略：读旧预设（JSON 里没有 dsp 段）时以"当前运行时状态"为底，
 * 只覆盖 JSON 里明确存在的字段 —— 老用户行为不变。
 */
data class DspSnapshot(
    val msebEnabled: Boolean? = null,
    val dtsEnabled: Boolean? = null,
    val compressorEnabled: Boolean? = null,
    val compressorThreshold: Float? = null,
    val compressorRatio: Float? = null,
    val compressorAttack: Float? = null,
    val compressorRelease: Float? = null,
    val compressorMakeup: Float? = null,
    val loudnessEnabled: Boolean? = null,
    val loudnessIntensity: Float? = null,
    val crossfeedEnabled: Boolean? = null,
    val crossfeedAmount: Float? = null,
    // 【V8.170】新模块（native 侧 8.170 新增），可空语义同上：不给就保持现状
    val spaceReverbEnabled: Boolean? = null,
    val spaceReverbWet: Float? = null,
    val spaceReverbRoom: Float? = null,
    val spaceReverbDamping: Float? = null,
    val dynamicEqEnabled: Boolean? = null,
    val dynamicEqDepth: Float? = null,
    val dynamicEqThreshold: Float? = null,
    val multiBandLimiterEnabled: Boolean? = null,
    val autoEqMix: Float? = null
) {
    companion object {
        /** 从运行时状态抓一张快照。 */
        fun capture(context: Context): DspSnapshot {
            val comp = context.getSharedPreferences("compressor", Context.MODE_PRIVATE)
            val loud = context.getSharedPreferences("loudness", Context.MODE_PRIVATE)
            val xf = context.getSharedPreferences("crossfeed", Context.MODE_PRIVATE)
            return DspSnapshot(
                msebEnabled = MsebCalculator.isEnabled(context),
                dtsEnabled = MsebCalculator.isDtsEnabled(context),
                compressorEnabled = comp.getBoolean("enabled", false),
                compressorThreshold = comp.floatValue("threshold", -18f),
                compressorRatio = comp.floatValue("ratio", 2f),
                compressorAttack = comp.floatValue("attack", 10f),
                compressorRelease = comp.floatValue("release", 120f),
                compressorMakeup = comp.floatValue("makeup", 0f),
                loudnessEnabled = loud.getBoolean("enabled", false),
                loudnessIntensity = loud.floatValue("intensity", 1f),
                crossfeedEnabled = xf.getBoolean("enabled", false),
                crossfeedAmount = xf.floatValue("amount", 0.6f)
            )
        }

        /** 新模块没有 SharedPreferences 落点（当前只活在 native），
         *  从 MusicService 的运行时值抓。UI 打开页面时调用一次。 */
        fun captureNative(context: Context): DspSnapshot {
            val svc = MusicService.instance
            if (svc == null) return DspSnapshot()
            return DspSnapshot(
                spaceReverbEnabled = svc.isSpaceReverbEnabled(),
                spaceReverbWet = svc.getSpaceReverbWet(),
                spaceReverbRoom = svc.getSpaceReverbRoom(),
                spaceReverbDamping = svc.getSpaceReverbDamping(),
                dynamicEqEnabled = svc.isDynamicEqEnabled(),
                dynamicEqDepth = svc.getDynamicEqDepth(),
                dynamicEqThreshold = svc.getDynamicEqThreshold(),
                multiBandLimiterEnabled = svc.isMultiBandLimiterEnabled(),
                autoEqMix = svc.getAutoEqMix()
            )
        }

        /** 把快照写回运行时状态。null 字段跳过（= 保持现状）。 */
        fun apply(context: Context, s: DspSnapshot) {
            if (s.msebEnabled != null) MsebCalculator.setEnabled(context, s.msebEnabled!!)
            if (s.dtsEnabled != null) MsebCalculator.setDtsEnabled(context, s.dtsEnabled!!)
            val comp = context.getSharedPreferences("compressor", Context.MODE_PRIVATE).edit()
            if (s.compressorEnabled != null) comp.putBoolean("enabled", s.compressorEnabled!!)
            if (s.compressorThreshold != null) comp.putFloat("threshold", s.compressorThreshold!!)
            if (s.compressorRatio != null) comp.putFloat("ratio", s.compressorRatio!!)
            if (s.compressorAttack != null) comp.putFloat("attack", s.compressorAttack!!)
            if (s.compressorRelease != null) comp.putFloat("release", s.compressorRelease!!)
            if (s.compressorMakeup != null) comp.putFloat("makeup", s.compressorMakeup!!)
            comp.apply()
            val loud = context.getSharedPreferences("loudness", Context.MODE_PRIVATE).edit()
            if (s.loudnessEnabled != null) loud.putBoolean("enabled", s.loudnessEnabled!!)
            if (s.loudnessIntensity != null) loud.putFloat("intensity", s.loudnessIntensity!!)
            loud.apply()
            val xf = context.getSharedPreferences("crossfeed", Context.MODE_PRIVATE).edit()
            if (s.crossfeedEnabled != null) xf.putBoolean("enabled", s.crossfeedEnabled!!)
            if (s.crossfeedAmount != null) xf.putFloat("amount", s.crossfeedAmount!!)
            xf.apply()

            // 新模块只活在运行时的 native 侧，直接下发
            val svc = MusicService.instance
            if (svc != null) {
                s.spaceReverbEnabled?.let { svc.setSpaceReverbEnabled(it) }
                s.spaceReverbWet?.let { svc.setSpaceReverbWet(it) }
                if (s.spaceReverbRoom != null || s.spaceReverbDamping != null) {
                    svc.setSpaceReverbShape(
                        s.spaceReverbRoom ?: svc.getSpaceReverbRoom(),
                        s.spaceReverbDamping ?: svc.getSpaceReverbDamping()
                    )
                }
                s.dynamicEqEnabled?.let { svc.setDynamicEqEnabled(it) }
                s.dynamicEqDepth?.let { svc.setDynamicEqDepth(it) }
                s.dynamicEqThreshold?.let { svc.setDynamicEqThreshold(it) }
                s.multiBandLimiterEnabled?.let { svc.setMultiBandLimiterEnabled(it) }
                s.autoEqMix?.let { svc.setAutoEqMix(it) }
            }
        }

        private fun ob(o: JSONObject, k: String) = if (o.has(k)) o.getDouble(k).toFloat() else null
        private fun ob(o: JSONObject, k: String, d: Boolean) =
            if (o.has(k)) o.getBoolean(k) else d

        fun fromJson(str: String): DspSnapshot {
            return try {
                val o = JSONObject(str)
                DspSnapshot(
                    msebEnabled = if (o.has("msebEnabled")) o.getBoolean("msebEnabled") else null,
                    dtsEnabled = if (o.has("dtsEnabled")) o.getBoolean("dtsEnabled") else null,
                    compressorEnabled = if (o.has("compEnabled")) o.getBoolean("compEnabled") else null,
                    compressorThreshold = ob(o, "compThreshold"),
                    compressorRatio = ob(o, "compRatio"),
                    compressorAttack = ob(o, "compAttack"),
                    compressorRelease = ob(o, "compRelease"),
                    compressorMakeup = ob(o, "compMakeup"),
                    loudnessEnabled = if (o.has("loudEnabled")) o.getBoolean("loudEnabled") else null,
                    loudnessIntensity = ob(o, "loudIntensity"),
                    crossfeedEnabled = if (o.has("xfEnabled")) o.getBoolean("xfEnabled") else null,
                    crossfeedAmount = ob(o, "xfAmount"),
                    spaceReverbEnabled = if (o.has("srEnabled")) o.getBoolean("srEnabled") else null,
                    spaceReverbWet = ob(o, "srWet"),
                    spaceReverbRoom = ob(o, "srRoom"),
                    spaceReverbDamping = ob(o, "srDamp"),
                    dynamicEqEnabled = if (o.has("deEnabled")) o.getBoolean("deEnabled") else null,
                    dynamicEqDepth = ob(o, "deDepth"),
                    dynamicEqThreshold = ob(o, "deThresh"),
                    multiBandLimiterEnabled = if (o.has("mblEnabled")) o.getBoolean("mblEnabled") else null,
                    autoEqMix = ob(o, "autoEqMix")
                )
            } catch (_: Exception) {
                DspSnapshot()
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        msebEnabled?.let { put("msebEnabled", it) }
        dtsEnabled?.let { put("dtsEnabled", it) }
        compressorEnabled?.let { put("compEnabled", it) }
        compressorThreshold?.let { put("compThreshold", it) }
        compressorRatio?.let { put("compRatio", it) }
        compressorAttack?.let { put("compAttack", it) }
        compressorRelease?.let { put("compRelease", it) }
        compressorMakeup?.let { put("compMakeup", it) }
        loudnessEnabled?.let { put("loudEnabled", it) }
        loudnessIntensity?.let { put("loudIntensity", it) }
        crossfeedEnabled?.let { put("xfEnabled", it) }
        crossfeedAmount?.let { put("xfAmount", it) }
        spaceReverbEnabled?.let { put("srEnabled", it) }
        spaceReverbWet?.let { put("srWet", it) }
        spaceReverbRoom?.let { put("srRoom", it) }
        spaceReverbDamping?.let { put("srDamp", it) }
        dynamicEqEnabled?.let { put("deEnabled", it) }
        dynamicEqDepth?.let { put("deDepth", it) }
        dynamicEqThreshold?.let { put("deThresh", it) }
        multiBandLimiterEnabled?.let { put("mblEnabled", it) }
        autoEqMix?.let { put("autoEqMix", it) }
    }.toString()
}

object MsebPresets {

    private const val PREFS_KEY = "mseb_presets"

    /** 【V8.170】用户自定义预设上限。原来卡在 2，实际调个预设要来回删，太紧。 */
    const val MAX_USER_PRESETS = 12

    /** Built-in classic presets */
    val BUILTIN: List<MsebPreset> = listOf(
        MsebPreset(
            name = "Vocal Sweet",
            params = MsebParams(
                vocalForward = 4f,
                femaleOvertones = 6f,
                sibilance = -6f,
                sibilanceLf = -4f,
                thickness = 3f,
                temperature = 3f
            )
        ),
        MsebPreset(
            name = "Pop Forward Vocal",
            params = MsebParams(
                thickness = 5f,
                vocalForward = 4f,
                temperature = 3f,
                bassTexture = 3f,
                sibilance = -2f
            )
        ),
        MsebPreset(
            name = "Orchestral Wide",
            params = MsebParams(
                air = 6f,
                subBass = 4f,
                bassTexture = 4f,
                temperature = -3f,
                thickness = 2f,
                impulseResponse = 5f
            )
        ),
        MsebPreset(
            name = "Bass Monster",
            params = MsebParams(
                subBass = 8f,
                bassTexture = 6f,
                thickness = 4f,
                temperature = 2f
            )
        ),
        MsebPreset(
            name = "Live Soundstage",
            params = MsebParams(
                air = 5f,
                vocalForward = 3f,
                temperature = -2f,
                bassTexture = -2f,
                thickness = 1f,
                impulseResponse = 4f
            )
        ),
        MsebPreset(
            name = "Treble Smooth",
            params = MsebParams(
                sibilance = -8f,
                sibilanceLf = -6f,
                sibilanceHf = -6f,
                temperature = 4f,
                air = -3f
            )
        )
    )

    fun getAll(context: Context): List<MsebPreset> {
        val saved = loadUserPresets(context)
        return BUILTIN + saved
    }

    fun getUserPresets(context: Context): List<MsebPreset> = loadUserPresets(context)

    /**
     * 保存预设（同名替换）。新名字且已满 [MAX_USER_PRESETS] 个则拒绝。
     * @return true=已保存；false=超上限未保存
     */
    fun save(context: Context, name: String, params: MsebParams, snapshot: DspSnapshot = DspSnapshot()): Boolean {
        val presets = loadUserPresets(context).toMutableList()
        val exists = presets.any { it.name == name }
        if (!exists && presets.size >= MAX_USER_PRESETS) {
            return false
        }
        // Replace if same name exists, otherwise append
        presets.removeAll { it.name == name }
        presets.add(MsebPreset(name, params, System.currentTimeMillis(), snapshot))
        writePresets(context, presets)
        return true
    }

    fun delete(context: Context, name: String) {
        val presets = loadUserPresets(context).toMutableList()
        presets.removeAll { it.name == name }
        writePresets(context, presets)
    }

    /**
     * 【V8.170-BUGFIX】原来这里只写了 11 个维度，漏掉 soundstage / imaging。
     * 写预设走 toJson、读预设走 fromJson，这两个维度回读时恒为 0 ——
     * 存了带声场的预设，退出重进声场就没了。内置 6 个预设都没设这两个维度，
     * 所以一直没暴露。现在把 13 个维度全部序列化。
     */
    fun toJson(params: MsebParams): String = JSONObject().apply {
        put("temperature", params.temperature)
        put("sibilance", params.sibilance)
        put("subBass", params.subBass)
        put("thickness", params.thickness)
        put("vocalForward", params.vocalForward)
        put("air", params.air)
        put("bassTexture", params.bassTexture)
        put("femaleOvertones", params.femaleOvertones)
        put("sibilanceLf", params.sibilanceLf)
        put("sibilanceHf", params.sibilanceHf)
        put("impulseResponse", params.impulseResponse)
        put("soundstage", params.soundstage)
        put("imaging", params.imaging)
    }.toString()

    fun fromJson(json: String): MsebParams {
        val o = JSONObject(json)
        return MsebParams(
            temperature     = o.optDouble("temperature", 0.0).toFloat(),
            sibilance       = o.optDouble("sibilance", 0.0).toFloat(),
            subBass         = o.optDouble("subBass", 0.0).toFloat(),
            thickness       = o.optDouble("thickness", 0.0).toFloat(),
            vocalForward    = o.optDouble("vocalForward", 0.0).toFloat(),
            air             = o.optDouble("air", 0.0).toFloat(),
            bassTexture     = o.optDouble("bassTexture", 0.0).toFloat(),
            femaleOvertones = o.optDouble("femaleOvertones", 0.0).toFloat(),
            sibilanceLf     = o.optDouble("sibilanceLf", 0.0).toFloat(),
            sibilanceHf     = o.optDouble("sibilanceHf", 0.0).toFloat(),
            impulseResponse = o.optDouble("impulseResponse", 0.0).toFloat(),
            soundstage      = o.optDouble("soundstage", 0.0).toFloat(),
            imaging         = o.optDouble("imaging", 0.0).toFloat()
        )
    }

    // ── Internal ──

    private fun loadUserPresets(context: Context): List<MsebPreset> {
        val raw = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            .getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MsebPreset(
                    name = obj.getString("name"),
                    params = fromJson(obj.getString("params")),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    // 【V8.170】老预设没有 dsp 段 → DspSnapshot 全 null → 应用时"保持现状"
                    snapshot = if (obj.has("dsp")) DspSnapshot.fromJson(obj.getString("dsp"))
                               else DspSnapshot()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePresets(context: Context, presets: List<MsebPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("params", toJson(p.params))
                put("createdAt", p.createdAt)
                // 【V8.170】DSP 快照随预设一起落盘，这样切预设能一键把模块开关状态也带过去
                if (p.snapshot != DspSnapshot()) {
                    put("dsp", p.snapshot.toJson())
                }
            })
        }
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            .edit()
            .putString("items", arr.toString())
            .apply()
    }
}
