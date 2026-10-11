package com.sdw.music.player.core.audio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 【V8.515-P1】ReplayGain / 容器 LUFS 标签增益的落盘缓存 —— 与 LoudnessCache 同源同构，
 * 但存的是「从文件标签里读出来的响度增益」（track/album gain、peak、容器 LUFS），
 * 而不是离线扫描得到的 LUFS。
 *
 * 为什么单列一份：LoudnessTags.read 自身已有 256 条内存 LRU（key=path|size|mtime），
 * 单次会话内重复播放不会反复 IO；但这个 LRU 进程死了就丢，冷启动后每首歌第一次播放
 * 仍要 RandomAccessFile 解析一次标签。这里把标签增益落盘，冷启动首播直接命中磁盘缓存，
 * 省掉一次文件解析；也方便设置/诊断页在不读文件的情况下判断「这首有 RG 标签」。
 *
 * 没有动 Song 模型与 SongCache 的版本化 JSON：那份是全库序列化、版本号耦合，
 * 为「标签增益持久化」去 bump 它的版本、并在全库扫描里逐首回写整个 song_cache.json，
 * 对稳定度是净风险、对音质零收益 —— 与「不为重构而重构」的北极星相悖。
 * 本缓存是独立 SharedPreferences JSON，结构与 LoudnessCache 一致，风险可隔离。
 *
 * key = 文件路径；值与 LoudnessTags.Tag 的可见字段一一对应。
 */
object TagGainCache {

    /** 缓存器版本：算法换代时提一档，旧值由补扫重新测量（当前仅作标记，未做失效剔除）。 */
    const val ENGINE = 1

    private const val PREFS_NAME = "tag_gain_cache"
    private const val KEY_DATA = "cache_json"

    private var prefs: SharedPreferences? = null
    private var cacheMap: MutableMap<String, LoudnessTags.Tag> = mutableMapOf()
    private var loaded = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        // prefs 未 init 时不得空载置位 loaded，否则 init 后磁盘数据永远读不回、flush 用残缺表覆盖磁盘。
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
                val tg = if (obj.has("tg")) obj.optDouble("tg").toFloat() else null
                val ag = if (obj.has("ag")) obj.optDouble("ag").toFloat() else null
                val tp = if (obj.has("tp")) obj.optDouble("tp").toFloat() else null
                val ap = if (obj.has("ap")) obj.optDouble("ap").toFloat() else null
                val lu = if (obj.has("lu")) obj.optDouble("lu").toFloat() else null
                cacheMap[path] = LoudnessTags.Tag(
                    trackGainDb = tg, albumGainDb = ag, trackPeak = tp, albumPeak = ap, lufs = lu
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("TagGainCache", "Failed to load: ${e.message}")
            cacheMap.clear()
        }
    }

    /** 写入一枚标签增益；全 null（无响度元数据）的标签不占空间。 */
    fun put(filePath: String, tag: LoudnessTags.Tag) {
        ensureLoaded()
        if (tag.trackGainDb == null && tag.albumGainDb == null &&
            tag.trackPeak == null && tag.albumPeak == null && tag.lufs == null) return
        cacheMap[filePath] = tag
    }

    /** 返回 null = 这首歌没缓存过（需现场 LoudnessTags.read）。 */
    fun get(filePath: String): LoudnessTags.Tag? {
        ensureLoaded()
        return cacheMap[filePath]
    }

    // 防抖异步落盘：后台单飞 + 节流，主线程绝不当序列化工人（同 LoudnessCache）。
    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "taggain-flush").apply {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
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
                android.util.Log.w("TagGainCache", "scheduleFlush: ${t.message}")
            }
        }
    }

    fun flush() {
        ensureLoaded()
        try {
            val root = JSONObject()
            for ((path, t) in cacheMap) {
                val obj = JSONObject()
                t.trackGainDb?.let { obj.put("tg", it.toDouble()) }
                t.albumGainDb?.let { obj.put("ag", it.toDouble()) }
                t.trackPeak?.let { obj.put("tp", it.toDouble()) }
                t.albumPeak?.let { obj.put("ap", it.toDouble()) }
                t.lufs?.let { obj.put("lu", it.toDouble()) }
                root.put(path, obj)
            }
            prefs?.edit()?.putString(KEY_DATA, root.toString())?.apply()
        } catch (e: Exception) {
            android.util.Log.w("TagGainCache", "Failed to flush: ${e.message}")
        }
    }
}
