package com.sdw.music.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * BPM/Key 持久化缓存
 * key = filePath, value = {"bpm":128,"key":"8A"}
 * 使用 SharedPreferences 存储 JSON，启动时直接读取，无需重新扫描
 */
object BpmKeyCache {
    private const val PREFS_NAME = "bpm_key_cache"
    private const val KEY_DATA = "cache_json"
    private var prefs: SharedPreferences? = null
    private var cacheMap: MutableMap<String, Pair<Int, String>> = mutableMapOf()
    private var loaded = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val json = prefs?.getString(KEY_DATA, "") ?: ""
            if (json.isNotBlank()) {
                val root = JSONObject(json)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val filePath = keys.next()
                    val obj = root.getJSONObject(filePath)
                    val bpm = obj.optInt("bpm", 0)
                    val key = obj.optString("key", "")
                    cacheMap[filePath] = Pair(bpm, key)
                }
            }
            android.util.Log.d("BpmKeyCache", "Loaded ${cacheMap.size} cached entries")
        } catch (e: Exception) {
            android.util.Log.w("BpmKeyCache", "Failed to load cache: ${e.message}")
            cacheMap.clear()
        }
    }

    /** 获取缓存的 BPM/Key，没有Back null */
    fun get(filePath: String): Pair<Int, String>? {
        ensureLoaded()
        return cacheMap[filePath]
    }

    /** Save单条 BPM/Key */
    fun put(filePath: String, bpm: Int, key: String) {
        ensureLoaded()
        cacheMap[filePath] = Pair(bpm, key)
    }

    /** 批量Save后调用，写入磁盘 */
    fun flush() {
        try {
            val root = JSONObject()
            for ((path, pair) in cacheMap) {
                val obj = JSONObject()
                obj.put("bpm", pair.first)
                obj.put("key", pair.second)
                root.put(path, obj)
            }
            prefs?.edit()?.putString(KEY_DATA, root.toString())?.apply()
            android.util.Log.d("BpmKeyCache", "Flushed ${cacheMap.size} entries to disk")
        } catch (e: Exception) {
            android.util.Log.w("BpmKeyCache", "Failed to flush cache: ${e.message}")
        }
    }

    /** 缓存条目数 */
    fun size(): Int {
        ensureLoaded()
        return cacheMap.size
    }

    /** 清空缓存 */
    fun clear() {
        cacheMap.clear()
        prefs?.edit()?.remove(KEY_DATA)?.apply()
        android.util.Log.d("BpmKeyCache", "Cache cleared")
    }
}

