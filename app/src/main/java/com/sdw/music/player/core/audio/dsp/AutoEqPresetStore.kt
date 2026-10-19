package com.sdw.music.player.core.audio.dsp
import com.sdw.music.player.SDWApp
import com.sdw.music.player.core.audio.dsp.AutoEqPresetStore

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AutoEQ 全量耳机预设库（assets/autoeq_presets.bin）
 *
 * 数据来源：https://github.com/jaakkopasanen/AutoEq（HEAD 7ae0f56）
 * 规模：8850 条 ParametricEQ 测量 → 按型号去重后 6033 款 / 652 品牌，全部 10 段
 *
 * 为什么走 assets 二进制而不是 Kotlin 源码：
 * 6033 款若写成 AutoEqFilter 字面量约 6 万行 / 2.5MB+ 源码，编译与 R8 阶段会 OOM，
 * 且每次改预设都要重编十几分钟。二进制 592KB，源码零膨胀，改数据不用重编。
 *
 * 二进制布局（little-endian，magic "AEQ1"）：
 *   u32 magic
 *   u16 presetCount, u16 brandCount
 *   brandCount x [u8 len + utf8]
 *   u8 srcCount
 *   srcCount x [u8 len + utf8]
 *   presetCount x {
 *     u16 brandIdx
 *     u8 nameLen + utf8 name
 *     u8 typeIdx (0=in-ear, 1=over-ear, 2=earbud, 3=other)
 *     u8 srcIdx
 *     i16 preamp x100
 *     u8 filterCount
 *     filterCount x { u8 type(0=PK,1=HS,2=LS), u16 freqHz, u16 qx100, i16 gainx100 }
 *   }
 */
object AutoEqPresetStore {
    private const val TAG = "AutoEqPresetStore"
    private const val ASSET_NAME = "autoeq_presets.bin"
    private const val MAGIC = 0x31514541  // "AEQ1" little-endian

    private val TYPE_NAMES = arrayOf("in-ear", "over-ear", "earbud", "other")
    private val FILTER_NAMES = arrayOf("PK", "HS", "LS")

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded: List<AutoEqPreset>? = null
    @Volatile private var brands: List<String> = emptyList()
    @Volatile private var byName: Map<String, AutoEqPreset> = emptyMap()
    @Volatile private var loadFailed = false

    /** 在 Application.onCreate 里调用一次即可（存 applicationContext，不持有 Activity） */
    // [V8.214] 后台预载作用域：6033 款预设解析约 6 万对象，原在主线程同步跑 → 开屏卡顿「没有响应」。
    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
        // [V8.214] 改为后台线程预载，不再阻塞 SDWApp.onCreate 主线程；
        // UI 经 presets()/getBrands() 访问时若已解析完则秒回，否则阻塞一次（极少发生）。
        loaderScope.launch { ensureLoaded() }
    }

    fun isLoaded(): Boolean = loaded != null

    /** 全量预设；加载失败返回空列表（调用方自行回落内置 46 款） */
    fun presets(): List<AutoEqPreset> {
        ensureLoaded()
        return loaded ?: emptyList()
    }

    /** 按品牌分组后的品牌名（字母序） */
    fun getBrands(): List<String> {
        ensureLoaded()
        return brands
    }

    fun findByBrand(brand: String): List<AutoEqPreset> {
        ensureLoaded()
        val all = loaded ?: return emptyList()
        return all.filter { it.brand == brand }
    }

    fun findByName(name: String): AutoEqPreset? {
        ensureLoaded()
        byName[name]?.let { return it }
        // 兼容旧存档名（如 "AirPods Max" vs "Apple AirPods Max"）：忽略大小写、忽略空格与连字符
        val target = normalize(name)
        if (target.isEmpty()) return null
        val all = loaded ?: return null
        for (p in all) {
            if (normalize(p.name) == target) return p
        }
        return null
    }

    /** 型号/品牌模糊搜索，用于 EQ 界面搜索框 */
    fun search(query: String, limit: Int = 300): List<AutoEqPreset> {
        ensureLoaded()
        val all = loaded ?: return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<AutoEqPreset>(minOf(limit, 64))
        for (p in all) {
            if (p.name.lowercase().contains(q) || p.brand.lowercase().contains(q)) {
                out.add(p)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** 品牌列表里挑前 N 个热门品牌（按预设数量），用于默认展示 */
    fun topBrands(limit: Int): List<String> {
        ensureLoaded()
        val all = loaded ?: return emptyList()
        return all.groupingBy { it.brand }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(" ", "").replace("-", "").replace("_", "")

    @Synchronized
    private fun ensureLoaded() {
        if (loaded != null || loadFailed) return
        val ctx = appContext ?: return
        try {
            val bytes = ctx.assets.open(ASSET_NAME).use { it.readBytes() }
            val parsed = parse(bytes)
            if (parsed.isEmpty()) {
                Log.w(TAG, "asset parsed to 0 presets, falling back to builtin list")
                loadFailed = true
                return
            }
            loaded = parsed
            brands = parsed.map { it.brand }.distinct().sorted()
            byName = parsed.associateBy { it.name }
            Log.i(TAG, "loaded ${parsed.size} AutoEQ presets / ${brands.size} brands (${bytes.size} B)")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load $ASSET_NAME, falling back to builtin list", t)
            loadFailed = true
        }
    }

    private fun parse(raw: ByteArray): List<AutoEqPreset> {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 8) return emptyList()
        if (buf.int != MAGIC) {
            Log.e(TAG, "bad magic in $ASSET_NAME")
            return emptyList()
        }
        val presetCount = buf.short.toInt() and 0xFFFF
        val brandCount = buf.short.toInt() and 0xFFFF
        if (presetCount <= 0 || presetCount > 60000) return emptyList()

        val brandsArr = Array(brandCount) { readStr(buf) }
        val srcCount = buf.get().toInt() and 0xFF
        val srcsArr = Array(srcCount) { readStr(buf) }

        val out = ArrayList<AutoEqPreset>(presetCount)
        for (i in 0 until presetCount) {
            if (buf.remaining() < 6) break
            val brandIdx = buf.short.toInt() and 0xFFFF
            val name = readStr(buf)
            val typeIdx = buf.get().toInt() and 0xFF
            val srcIdx = buf.get().toInt() and 0xFF
            val preamp = buf.short.toInt() / 100f
            val filterCount = buf.get().toInt() and 0xFF

            val filters = ArrayList<AutoEqFilter>(filterCount)
            for (f in 0 until filterCount) {
                if (buf.remaining() < 7) break
                val ft = buf.get().toInt() and 0xFF
                val freq = (buf.short.toInt() and 0xFFFF).toFloat()
                val q = (buf.short.toInt() and 0xFFFF) / 100f
                val gain = buf.short.toInt() / 100f
                filters.add(
                    AutoEqFilter(
                        type = FILTER_NAMES.getOrElse(ft) { "PK" },
                        freq = freq,
                        gain = gain,
                        q = q
                    )
                )
            }
            if (filters.isEmpty()) continue
            out.add(
                AutoEqPreset(
                    name = name,
                    brand = brandsArr.getOrElse(brandIdx) { "Other" },
                    type = TYPE_NAMES.getOrElse(typeIdx) { "other" },
                    preamp = preamp,
                    filters = filters
                )
            )
        }
        if (srcsArr.isEmpty()) Log.d(TAG, "no source table")
        return out
    }

    private fun readStr(buf: ByteBuffer): String {
        if (buf.remaining() < 1) return ""
        val len = buf.get().toInt() and 0xFF
        if (len == 0 || buf.remaining() < len) return ""
        val b = ByteArray(len)
        buf.get(b)
        return String(b, StandardCharsets.UTF_8)
    }
}
