package com.sdw.music.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MSEB Preset Manager — save/load/export 10-dimension presets.
 * Storage: SharedPreferences "mseb_presets" as JSON array.
 */
data class MsebPreset(
    val name: String,
    val params: MsebParams,
    val createdAt: Long = System.currentTimeMillis()
)

object MsebPresets {

    private const val PREFS_KEY = "mseb_presets"

    /** 用户自定义预设上限 */
    const val MAX_USER_PRESETS = 2

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
    fun save(context: Context, name: String, params: MsebParams): Boolean {
        val presets = loadUserPresets(context).toMutableList()
        val exists = presets.any { it.name == name }
        if (!exists && presets.size >= MAX_USER_PRESETS) {
            return false
        }
        // Replace if same name exists, otherwise append
        presets.removeAll { it.name == name }
        presets.add(MsebPreset(name, params))
        writePresets(context, presets)
        return true
    }

    fun delete(context: Context, name: String) {
        val presets = loadUserPresets(context).toMutableList()
        presets.removeAll { it.name == name }
        writePresets(context, presets)
    }

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
            impulseResponse = o.optDouble("impulseResponse", 0.0).toFloat()
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
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
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
            })
        }
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            .edit()
            .putString("items", arr.toString())
            .apply()
    }
}
