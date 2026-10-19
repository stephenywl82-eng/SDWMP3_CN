package com.sdw.music.player.core.audio.dsp
import com.sdw.music.player.core.audio.dsp.AutoEqPresetManager

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义参数 EQ 预设的本地持久化（命名保存 / 载入 / 删除）。
 *
 * 存储为 app 私有文件 peq_presets.json（JSON 数组），不引入外部 JSON 库（用 Android 内置 org.json）。
 * 与 AutoEqPresetManager 的内置耳机库解耦：这里只管「用户自己调出来的命名预设」。
 */
object CustomPeqStore {
    private const val TAG = "CustomPeqStore"
    private const val FILE = "peq_presets.json"

    data class Entry(
        val name: String,
        val preamp: Float,
        val bands: List<ParametricEqManager.Band>
    ) {
        fun toPreset(): ParametricEqManager.Preset =
            ParametricEqManager.Preset(name = name, preamp = preamp, bands = bands)
    }

    private fun file(context: Context): File = File(context.filesDir, FILE)

    private fun readAll(context: Context): MutableList<Entry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val bandsArr = o.getJSONArray("bands")
                val bands = mutableListOf<ParametricEqManager.Band>()
                for (j in 0 until bandsArr.length()) {
                    val b = bandsArr.getJSONObject(j)
                    bands.add(
                        ParametricEqManager.Band(
                            type = b.optInt("t", 0).coerceIn(0, 2),
                            freq = b.optDouble("f", 1000.0).toFloat().coerceIn(20f, 20000f),
                            gain = b.optDouble("g", 0.0).toFloat().coerceIn(-15f, 15f),
                            q = b.optDouble("q", 1.0).toFloat().coerceIn(0.1f, 18f),
                            enabled = b.optBoolean("e", true)
                        )
                    )
                }
                list.add(Entry(o.optString("name", "Preset"), o.optDouble("preamp", 0.0).toFloat(), bands))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "readAll failed", e)
            mutableListOf()
        }
    }

    private fun writeAll(context: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                val o = JSONObject()
                o.put("name", e.name)
                o.put("preamp", e.preamp)
                val bands = JSONArray()
                e.bands.forEach { b ->
                    val bo = JSONObject()
                    bo.put("t", b.type); bo.put("f", b.freq); bo.put("g", b.gain)
                    bo.put("q", b.q); bo.put("e", b.enabled)
                    bands.put(bo)
                }
                o.put("bands", bands)
                arr.put(o)
            }
            file(context).writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "writeAll failed", e)
        }
    }

    fun list(context: Context): List<Entry> = readAll(context)

    /** 按名字保存（重名覆盖）。名字为空/纯空白返回 false。 */
    fun save(context: Context, name: String, preset: ParametricEqManager.Preset): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val list = readAll(context)
        val entry = Entry(n, preset.preamp, preset.bands)
        val idx = list.indexOfFirst { it.name == n }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        writeAll(context, list)
        return true
    }

    fun delete(context: Context, name: String): Boolean {
        val list = readAll(context)
        val removed = list.removeIf { it.name == name }
        if (removed) writeAll(context, list)
        return removed
    }
}
