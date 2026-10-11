package com.sdw.music.player.core.audio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 【V8.233】离线响度扫描缓存 —— 曲库里没有 ReplayGain / LUFS 标签时的兜底数据源。
 *
 * 与 BpmKeyCache 同源同构：key = 文件路径，值 = {lufs, peak, engine}，落在
 * SharedPreferences 的 JSON 里。扫描由 MusicService 的全库节流补扫顺带完成
 * （复用 BPM 那一次解码，不额外解码一遍）。
 *
 * lufs/peak 为 null = 这首歌扫过但没算出有效值（墓碑），避免补扫循环反复重试。
 */
object LoudnessCache {

    /** 扫描器版本：算法换代时提一档，旧值会被补扫重新测量。 */
    const val ENGINE = 1

    private const val PREFS_NAME = "loudness_cache"
    private const val KEY_DATA = "cache_json"

    data class Entry(val lufs: Float?, val peak: Float?, val engine: Int)

    private var prefs: SharedPreferences? = null
    private var cacheMap: MutableMap<String, Entry> = mutableMapOf()
    private var loaded = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        // 【V8.260】同 BpmKeyCache 修复：prefs 未 init 时不得空载置位 loaded，
        // 否则 init 后磁盘数据永远读不回、flush 用残缺表覆盖磁盘。
        if (prefs == null) return
        loaded = true
        try {
            val json = prefs?.getString(KEY_DATA, "") ?: ""
            if (json.isBlank()) return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val obj = root.getJSONObject(path)
                val hasLufs = obj.has("lufs")
                val hasPeak = obj.has("peak")
                cacheMap[path] = Entry(
                    lufs = if (hasLufs) obj.optDouble("lufs", 0.0).toFloat() else null,
                    peak = if (hasPeak) obj.optDouble("peak", 1.0).toFloat() else null,
                    engine = obj.optInt("engine", 0)
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("LoudnessCache", "Failed to load: ${e.message}")
            cacheMap.clear()
        }
    }

    /** 扫描结果（lufs 单位 LUFS，peak 线性 0..1）。 */
    fun put(filePath: String, lufs: Float?, peak: Float?, engine: Int = ENGINE) {
        ensureLoaded()
        cacheMap[filePath] = Entry(lufs, peak, engine)
    }

    /** 墓碑：扫过但无有效值 —— 占住位子，补扫不再重试。 */
    fun tombstone(filePath: String) {
        ensureLoaded()
        cacheMap[filePath] = Entry(null, null, ENGINE)
    }

    /** 返回 null = 这首歌还没扫过（需要补扫）。 */
    fun get(filePath: String): Entry? {
        ensureLoaded()
        return cacheMap[filePath]
    }

    fun size(): Int {
        ensureLoaded()
        return cacheMap.size
    }

    /**
     * 【V8.238】曲库平均响度（LUFS），供「自动档」把目标定在库自身的平均水平 ——
     * 这样响的歌变小、轻的歌变大，平均增益≈0，整体听感不变又消除了曲目间差异。
     * 样本不足（<12 首）返回 null，让调用方回落到 -14 LUFS。
     */
    fun averageLufs(minSamples: Int = 12): Float? {
        ensureLoaded()
        var sum = 0.0
        var n = 0
        for (e in cacheMap.values) {
            val l = e.lufs
            if (l != null && l > -60f && l < 5f) { sum += l.toDouble(); n++ }
        }
        return if (n >= minSamples) (sum / n).toFloat() else null
    }

    // 【V8.264】防抖异步落盘：同 BpmKeyCache（后台单飞 + 节流），主线程绝不当序列化工人
    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "loudness-flush").apply { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
    }
    @Volatile private var flushQueued = false
    private val lastFlushAt = java.util.concurrent.atomic.AtomicLong(0L)

    fun scheduleFlush() {
        val now = System.currentTimeMillis()
        val last = lastFlushAt.get()
        if (now - last < 30_000L) return
        if (!lastFlushAt.compareAndSet(last, now)) return
        if (flushQueued) return
        flushQueued = true
        flushExecutor.execute {
            flushQueued = false
            try { flush() } catch (t: Throwable) {
                android.util.Log.w("LoudnessCache", "scheduleFlush: ${t.message}")
            }
        }
    }

    fun flush() {
        ensureLoaded()   // 【V8.260】防残缺覆盖：同 BpmKeyCache
        try {
            val root = JSONObject()
            for ((path, e) in cacheMap) {
                val obj = JSONObject()
                if (e.lufs != null) obj.put("lufs", e.lufs.toDouble())
                if (e.peak != null) obj.put("peak", e.peak.toDouble())
                obj.put("engine", e.engine)
                root.put(path, obj)
            }
            prefs?.edit()?.putString(KEY_DATA, root.toString())?.apply()
        } catch (e: Exception) {
            android.util.Log.w("LoudnessCache", "Failed to flush: ${e.message}")
        }
    }
}
