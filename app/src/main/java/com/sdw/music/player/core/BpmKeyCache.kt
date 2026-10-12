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

    /** 【v8.157】缓存条目：BPM/Key + 首拍(ms) + 置信度 + 引擎版本 + 【P2-2】动态 beatgrid 段 */
    data class Entry(val bpm: Int, val key: String, val firstBeatMs: Int, val conf: Float, val engine: Int,
                     val grid: List<IntArray> = emptyList(),
                     /** 【v8.166】段落结构（分析一次入库，切歌时查表，不再实时解码预扫） */
                     val structure: Structure = Structure(),
                     /** 【V8.304】亚 BPM 精度浮点 tempo（交叉淡化对拍用）。
                     *  ≤0 表示旧缓存没有该字段 → 读时回退到整数 bpm（见 bpmFloatOf）。 */
                     val bpmF: Float = 0f) {
        /** 对拍用浮点 tempo：优先 bpmF，缺失/非法则回退整数 bpm。 */
        val bpmFloat: Float get() = if (bpmF >= 40f && bpmF <= 220f) bpmF else bpm.toFloat()
    }

    /**
     * 【#2】检测到的真实段落边界（verse→chorus→bridge 等）。
     * 与 chorusMs/dropMs 这种"单点锚"不同，这里是**整曲的分段序列**，
     * 落点选择（A 出点 / B 入点）可据此切在"音乐换段处"而非"算出来的小节线"。
     *
     * 段与段之间以 [startMs] 为边界；[kind] 为 best-effort 标签（边界本身最关键的）。
     * 全部为绝对毫秒；列表为空=未检出（调用方退回当前行为，无退化）。
     */
    data class SectionBoundary(
        val startMs: Int = 0,        // 本段起点（也是上一段的结束边界）
        val endMs: Int = 0,          // 本段终点
        val kind: Int = 0            // 见下方 KIND_* 常量
    ) {
        companion object {
            const val INTRO = 0
            const val VERSE = 1
            const val CHORUS = 2
            const val BRIDGE = 3
            const val OUTRO = 4
            const val DROP = 5
        }
    }

    /** 【v8.166】段落结构：全部为绝对毫秒，-1 = 未检测出来 */
    data class Structure(
        val chorusMs: Int = -1,      // B 轨入场点：副歌起点
        val chorusEndMs: Int = -1,   // A 轨出场点：最后一个副歌结束位置
        val tailQuietMs: Int = -1,   // A 轨尾部低能量段起点（旧 mode=0）
        val introQuietMs: Int = -1,  // B 轨前奏静音段长度（旧 mode=1）
        val dropMs: Int = -1,        // 【V8.386】Drop 起点（能量爆发入场点），能量包络阈值法
        /** 【#2】检测到的真实段落边界序列（verse/chorus/bridge…）；空=未检出，调用方降级 */
        val sections: List<SectionBoundary> = emptyList()
    )

    /** 【v8.157】扫描引擎版本：值越大越新；旧引擎（<此值）的缓存视为过期，下次扫描重算
     *  【v8.166】升到 5：修 BPM 倍频误判 + 首拍改栅格相位拟合 + 新增调性/副歌 → 全库需重扫 */
    /** 【V8.224】检测引擎 v7：v6 谱通量/组合滤波之上再加 DP 整拍跟踪（Ellis/librosa 范式）。 */
    /** 【V8.304】升到 8：投票改在浮点 tempo 上做（refineOctaveF + 组内置信度加权平均），
     *  产出亚 BPM 精度的 bpmF。整数 bpm 在 120 附近量化步进 1 = ±0.83% 速度误差，
     *  正是「鼓点对不齐」的量级 → 必须重扫一次才有浮点值。 */
    const val ENGINE = 8

    private var cacheMap: java.util.concurrent.ConcurrentHashMap<String, Entry> =
        java.util.concurrent.ConcurrentHashMap()   // 【V8.261】多线程访问（主线程/分析线程/补扫线程）
    private var loaded = false

    // 【V8.261】flush 性能修复：flush 会把整张表（数千条带 beatgrid）序列化成巨型 JSON，
    // 在主线程调用时冻结 UI 数秒（症状：过渡完成后卡一下/ANR 切不了歌）。改为：
    // 防抖（≥10s 一次）+ 后台单飞 + ConcurrentHashMap。
    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bpm-cache-flush").apply { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
    }
    @Volatile private var flushQueued = false
    private val lastFlushAt = java.util.concurrent.atomic.AtomicLong(0L)
    private const val FLUSH_MIN_INTERVAL_MS = 30_000L

    /** 【V8.261】防抖异步落盘：任何线程都可调用，绝不阻塞调用方。 */
    fun scheduleFlush() {
        val now = System.currentTimeMillis()
        val last = lastFlushAt.get()
        if (now - last < FLUSH_MIN_INTERVAL_MS) return
        if (!lastFlushAt.compareAndSet(last, now)) return
        if (flushQueued) return
        flushQueued = true
        flushExecutor.execute {
            flushQueued = false
            try { flush() } catch (t: Throwable) {
                android.util.Log.w("BpmKeyCache", "scheduleFlush: ${t.message}")
            }
        }
    }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 【V8.260】init 前可能有 get/put 空载跑过（loaded 未置位），这里无需处理——
            // ensureLoaded 现在在 prefs==null 时直接 return，等本方法设好 prefs 后再真正读盘。
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        // 【V8.260 修复】prefs 未 init 时【不得】置位 loaded 也不得读空——旧逻辑在这里
        // 空载置位 loaded=true，导致 init 后磁盘数据永远读不回来，且 flush 会用残缺内存表
        // 覆盖磁盘 → 「扫描过的歌重启就丢」。现在等 init 后第一次访问再读盘（内存预写的
        // 条目会与磁盘条目按键合并，两边都不丢）。
        if (prefs == null) return
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
                    val firstBeat = obj.optInt("firstBeat", -1)
                    val conf = obj.optDouble("conf", 0.0).toFloat()
                    val engine = obj.optInt("engine", 0)
                    // 【#2】真实段落边界序列（JSON 压成 [[start,end,kind],...]），须在读 Structure 之前解析
                    val sbArr = obj.optJSONArray("sb")
                    val sections = if (sbArr != null) {
                        val list = ArrayList<SectionBoundary>()
                        for (i in 0 until sbArr.length()) {
                            val t = sbArr.optJSONArray(i) ?: continue
                            if (t.length() >= 3) {
                                list.add(SectionBoundary(t.optInt(0), t.optInt(1), t.optInt(2)))
                            }
                        }
                        list
                    } else emptyList()
                    // 【v8.166】段落结构
                    val structure = Structure(
                        chorusMs = obj.optInt("ch", -1),
                        chorusEndMs = obj.optInt("ce", -1),
                        tailQuietMs = obj.optInt("tq", -1),
                        introQuietMs = obj.optInt("iq", -1),
                        dropMs = obj.optInt("dp", -1),
                        sections = sections
                    )
                    val grid = ArrayList<IntArray>()
                    obj.optJSONArray("grid")?.let { ga ->
                        for (i in 0 until ga.length()) {
                            val g = ga.optJSONArray(i) ?: continue
                            // 【P2】段宽度 5：{bpm, firstBeat, startMs, beatsPerBar, downbeatOffsetMs}
                            // 旧缓存只有 3 个 → 拍号按 4/4、重拍偏移按 0 补齐（与旧行为一致）。
                            if (g.length() >= 5) {
                                grid.add(intArrayOf(g.optInt(0), g.optInt(1), g.optInt(2), g.optInt(3), g.optInt(4)))
                            } else if (g.length() >= 3) {
                                grid.add(intArrayOf(g.optInt(0), g.optInt(1), g.optInt(2), 4, 0))
                            }
                        }
                    }
                    // 【V8.304】浮点 tempo（milli，缺省 0 = 旧条目 → 读时回退整数 bpm）
                    val bpmMilli = obj.optInt("bpmM", 0)
                    val bpmF = if (bpmMilli > 0) bpmMilli / 1000f else 0f
                    cacheMap[filePath] = Entry(bpm, key, firstBeat, conf, engine, grid, structure, bpmF)
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
        val e = cacheMap[filePath] ?: return null
        return Pair(e.bpm, e.key)
    }

    /** 【v8.157】完整条目（含 firstBeat/conf） */
    fun getEntry(filePath: String): Entry? {
        ensureLoaded()
        return cacheMap[filePath]
    }

    /** 【V8.304】取缓存的浮点 BPM（对拍用）。旧条目/未命中回退 0f，由调用方决定是否用整数。 */
    fun getBpmFloat(filePath: String): Float {
        ensureLoaded()
        val e = cacheMap[filePath] ?: return 0f
        return if (e.bpmF >= 40f && e.bpmF <= 220f) e.bpmF else 0f
    }

    /** 【v8.157】读取首拍位置(ms)，无则 -1 */
    fun getFirstBeatMs(filePath: String): Int {
        ensureLoaded()
        return cacheMap[filePath]?.firstBeatMs ?: -1
    }

    /**
     * 【V8.419】读取 BPM 检测置信度（0..10 标度，日志实测常见 5.2~9.4）。
     * 无条目返回 -1f（=未知，调用方按"未知"处理而非"低置信"）。
     * 用途：对拍前门控 —— 测错的 BPM 拉出来的比率比不对更伤（会把两轨拉到错误速度）。
     */
    fun confOf(filePath: String): Float {
        ensureLoaded()
        return cacheMap[filePath]?.conf ?: -1f
    }

    /** Save单条 BPM/Key（保留已有 firstBeat/conf/engine/grid/bpmF） */
    fun put(filePath: String, bpm: Int, key: String) {
        ensureLoaded()
        val old = cacheMap[filePath]
        cacheMap[filePath] = Entry(bpm, key, old?.firstBeatMs ?: -1, old?.conf ?: 0f, old?.engine ?: 0,
            old?.grid ?: emptyList(), old?.structure ?: Structure(), old?.bpmF ?: 0f)
    }

    /** 【v8.157】Save 完整条目（engine 默认当前引擎）。bpmF<=0 时不更新已有浮点值。 */
    fun putEx(filePath: String, bpm: Int, key: String, firstBeatMs: Int, conf: Float, engine: Int = ENGINE,
              grid: List<IntArray> = emptyList(), structure: Structure? = null, bpmF: Float = 0f) {
        ensureLoaded()
        val old = cacheMap[filePath]
        val bpmF2 = if (bpmF >= 40f && bpmF <= 220f) bpmF else (old?.bpmF ?: 0f)
        cacheMap[filePath] = Entry(bpm, key, firstBeatMs, conf, engine, grid,
            structure ?: old?.structure ?: Structure(), bpmF2)
    }

    /** 【#3】降拍校正后写回：重锚首拍 + 动态 grid 段首拍到真 beat-1，其余字段保留。 */
    fun mergeFirstBeat(filePath: String, firstBeatMs: Int, grid: List<IntArray>) {
        ensureLoaded()
        val old = cacheMap[filePath] ?: return
        cacheMap[filePath] = old.copy(firstBeatMs = firstBeatMs, grid = grid)
    }

    /** 【v8.166】读段落结构（未缓存返回全 -1 的 Structure） */
    fun getStructure(filePath: String): Structure {
        ensureLoaded()
        return cacheMap[filePath]?.structure ?: Structure()
    }

    /**
     * 【v8.166】合并写入段落结构（只更新传进来的字段，其余保留）。
     * 用于：实时预扫（mode=0/1/2/3）完成后把结果回填，下次切歌直接查表。
     * 该曲没有基础条目（BPM 未扫）时不写入，避免产生空壳条目。
     */
    fun mergeStructure(
        filePath: String,
        chorusMs: Int? = null,
        chorusEndMs: Int? = null,
        tailQuietMs: Int? = null,
        introQuietMs: Int? = null,
        dropMs: Int? = null,
        /** 【#2】真实段落边界序列；传 null 保留旧值，传空列表表示"已检但无段落" */
        sections: List<SectionBoundary>? = null
    ) {
        ensureLoaded()
        val old = cacheMap[filePath] ?: return
        val s = old.structure
        cacheMap[filePath] = old.copy(
            structure = Structure(
                chorusMs = chorusMs ?: s.chorusMs,
                chorusEndMs = chorusEndMs ?: s.chorusEndMs,
                tailQuietMs = tailQuietMs ?: s.tailQuietMs,
                introQuietMs = introQuietMs ?: s.introQuietMs,
                dropMs = dropMs ?: s.dropMs,
                sections = sections ?: s.sections
            )
        )
    }

    /** 【P2-2】动态 beatgrid 段：每段 [bpm, firstBeatMs, startMs]，空=全曲匀速 */
    fun getGrid(filePath: String): List<IntArray> {
        ensureLoaded()
        return cacheMap[filePath]?.grid ?: emptyList()
    }

    /** 批量Save后调用，写入磁盘 */
    fun flush() {
        ensureLoaded()   // 【V8.260】防残缺覆盖：没读过盘就 flush 会用空表抹掉磁盘记录
        try {
            val root = JSONObject()
            for ((path, e) in cacheMap) {
                val obj = JSONObject()
                obj.put("bpm", e.bpm)
                obj.put("key", e.key)
                obj.put("firstBeat", e.firstBeatMs)
                obj.put("conf", e.conf.toDouble())
                obj.put("engine", e.engine)
                // 【V8.304】浮点 tempo（milli）。0 = 该条目无浮点值（读时回退整数 bpm）。
                val bf = e.bpmFloat
                if (e.bpmF >= 40f && e.bpmF <= 220f) obj.put("bpmM", (bf * 1000f).toInt())
                if (e.grid.isNotEmpty()) {
                    val ga = org.json.JSONArray()
                    // 【P2】写 5 个字段（不足则按 4/4、偏移 0 补齐）
                    for (g in e.grid) {
                        val t = org.json.JSONArray()
                        t.put(g[0]); t.put(g[1]); t.put(g[2])
                        t.put(if (g.size >= 4) g[3] else 4)
                        t.put(if (g.size >= 5) g[4] else 0)
                        ga.put(t)
                    }
                    obj.put("grid", ga)
                }
                // 【v8.166】段落结构（只写已检出的字段，保持 JSON 紧凑）
                if (e.structure.chorusMs >= 0) obj.put("ch", e.structure.chorusMs)
                if (e.structure.chorusEndMs >= 0) obj.put("ce", e.structure.chorusEndMs)
                if (e.structure.tailQuietMs >= 0) obj.put("tq", e.structure.tailQuietMs)
                if (e.structure.introQuietMs >= 0) obj.put("iq", e.structure.introQuietMs)
                if (e.structure.dropMs >= 0) obj.put("dp", e.structure.dropMs)
                // 【#2】真实段落边界序列（压成 [[start,end,kind],...]，空则不写）
                if (e.structure.sections.isNotEmpty()) {
                    val sbArr = org.json.JSONArray()
                    for (sec in e.structure.sections) {
                        val t = org.json.JSONArray()
                        t.put(sec.startMs); t.put(sec.endMs); t.put(sec.kind)
                        sbArr.put(t)
                    }
                    obj.put("sb", sbArr)
                }
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

    /** 【V8.243】全量 BPM 列表（仅取 40..220 的有效值），供中位数歧义校验用。 */
    fun allBpms(): List<Int> {
        ensureLoaded()
        return cacheMap.values.mapNotNull { e -> if (e.bpm in 40..220) e.bpm else null }
    }

    /** 清空缓存 */
    fun clear() {
        cacheMap.clear()
        prefs?.edit()?.remove(KEY_DATA)?.apply()
        android.util.Log.d("BpmKeyCache", "Cache cleared")
    }
}

