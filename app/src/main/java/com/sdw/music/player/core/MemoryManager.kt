package com.sdw.music.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 【v3.2 性能优化】内存与缓存管理器
 *
 * 核心功能:
 * 1. LRU Cover颜色缓存(自动淘汰最少使用)
 * 2. 低内存时自动清理
 * 3. 智能预加载(可见项优先)
 * 4. 内存使用监控
 */
object MemoryManager {
    private const val TAG = "MemoryManager"

    // 【LRU 缓存】最多缓存 500 首歌曲的Cover颜色
    private const val PALETTE_CACHE_SIZE = 500

    private val paletteCache = object : LruCache<Long, Int>(PALETTE_CACHE_SIZE) {
        override fun sizeOf(key: Long, value: Int): Int = 1
    }

    // 【多色缓存】每首歌缓存5色 (vibrant, lightVibrant, darkVibrant, muted, lightMuted)
    private const val MULTI_CACHE_SIZE = 300
    private val multiPaletteCache = object : LruCache<Long, IntArray>(MULTI_CACHE_SIZE) {
        override fun sizeOf(key: Long, value: IntArray): Int = 1
    }

    // 【预加载状态】正在预加载的歌曲 ID
    private val preloadingIds = ConcurrentHashMap<Long, Boolean>()

    // 【磁盘持久化】Cover颜色缓存 SharedPreferences
    private const val PREFS_PALETTE = "palette_cache"
    private const val KEY_PALETTE_JSON = "palette_json"
    @Volatile
    private var paletteDirty = false

    // 【内存压力等级】
    enum class MemoryPressure {
        NORMAL,      // 内存充足
        MODERATE,    // 内存紧张
        CRITICAL     // 内存严重不足
    }

    /**
     * 获取当前内存压力等级
     */
    fun getMemoryPressure(context: Context): MemoryPressure {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val availablePercent = (memoryInfo.availMem.toDouble() / memoryInfo.totalMem) * 100

        return when {
            availablePercent < 10 -> MemoryPressure.CRITICAL
            availablePercent < 25 -> MemoryPressure.MODERATE
            else -> MemoryPressure.NORMAL
        }
    }

    /**
     * 缓存Cover颜色
     */
    fun putPaletteColor(songId: Long, color: Int) {
        paletteCache.put(songId, color)
        paletteDirty = true
    }

    /**
     * 获取缓存的Cover颜色
     * @return 颜色值,如果不存在Back 0
     */
    fun getPaletteColor(songId: Long): Int {
        return paletteCache.get(songId) ?: 0
    }

    /**
     * 从Cover提取强调色,过滤黑白,智能提亮深色
     */
    suspend fun extractAccentColor(context: Context, uriString: String, songId: Long): Int? {
        // 优先查内存缓存
        val memCached = getPaletteColor(songId)
        if (memCached != 0) return memCached
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = if (uri.scheme == null || uri.scheme == "file") {
                    android.graphics.BitmapFactory.decodeFile(uri.path ?: uriString)
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                if (bitmap.width <= 0) {
                    bitmap.recycle()
                    return@withContext null
                }
                val palette = Palette.from(bitmap).generate()
                bitmap.recycle()

                // 优先取亮色/鲜艳色,保证在深色背景上可见
                val swatch = palette.lightVibrantSwatch
                    ?: palette.vibrantSwatch
                    ?: palette.lightMutedSwatch
                    ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                    ?: return@withContext null

                val rgb = swatch.rgb
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(rgb, hsv)

                val hue = hsv[0]
                val sat = hsv[1]
                val bri = hsv[2]

                // 过滤纯黑纯白
                if (bri < 0.04f || bri > 0.97f) return@withContext null

                // 智能提亮:深色Cover取出的颜色太暗,文字/按钮看不清
                val adjustedSat: Float
                val adjustedBri: Float
                when {
                    bri < 0.25f -> {
                        adjustedSat = (sat * 1.5f).coerceAtMost(1f)
                        adjustedBri = 0.55f  // 深色→强制提亮
                    }
                    bri < 0.40f -> {
                        adjustedSat = (sat * 1.25f).coerceAtMost(1f)
                        adjustedBri = 0.50f
                    }
                    else -> {
                        adjustedSat = if (sat < 0.15f) (sat * 1.6f).coerceAtMost(1f) else sat
                        adjustedBri = bri
                    }
                }

                val adjustedRgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, adjustedSat, adjustedBri))
                val color = (0xFF000000.toInt() or adjustedRgb)
                putPaletteColor(songId, adjustedRgb)
                flushPaletteToDisk(context)
                color
            } catch (e: Exception) {
                Log.w(TAG, "extractAccentColor failed: ${e.message}")
                null
            }
        }
    }

    /**
     * 从封面提取5色——用于Edge灯/Mixer等需要多色变化的地方
     * returns IntArray(5): [vibrant, lightVibrant, darkVibrant, muted, lightMuted]
     */
    suspend fun extractCoverColors(context: Context, uriString: String, songId: Long): IntArray? {
        val cached = multiPaletteCache.get(songId)
        if (cached != null && cached.size == 5) return cached
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bitmap = if (uri.scheme == null || uri.scheme == "file") {
                    android.graphics.BitmapFactory.decodeFile(uri.path ?: uriString)
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                if (bitmap.width <= 0) { bitmap.recycle(); return@withContext null }
                val p = Palette.from(bitmap).generate()
                bitmap.recycle()

                fun adjust(swatch: Palette.Swatch?): Int {
                    if (swatch == null) return 0
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
                    val bri = hsv[2]
                    if (bri < 0.04f || bri > 0.97f) return 0
                    val sat = when {
                        bri < 0.25f -> (hsv[1] * 1.5f).coerceAtMost(1f)
                        bri < 0.40f -> (hsv[1] * 1.25f).coerceAtMost(1f)
                        else -> hsv[1]
                    }
                    val b = when {
                        bri < 0.25f -> 0.55f
                        bri < 0.40f -> 0.50f
                        else -> bri
                    }
                    return android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], sat, b))
                }

                val colors = IntArray(5)
                colors[0] = adjust(p.vibrantSwatch)
                colors[1] = adjust(p.lightVibrantSwatch)
                colors[2] = adjust(p.darkVibrantSwatch)
                colors[3] = adjust(p.mutedSwatch)
                colors[4] = adjust(p.lightMutedSwatch)

                multiPaletteCache.put(songId, colors)
                colors
            } catch (e: Exception) {
                Log.w(TAG, "extractCoverColors failed: ${e.message}")
                null
            }
        }
    }

    /**
     * 从磁盘加载Cover颜色缓存
     */
    fun loadPaletteFromDisk(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PALETTE, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PALETTE_JSON, "") ?: ""
            if (json.isBlank()) return
            val root = org.json.JSONObject(json)
            val keys = root.keys()
            var loaded = 0
            while (keys.hasNext()) {
                val songIdStr = keys.next()
                val songId = songIdStr.toLongOrNull() ?: continue
                val color = root.getInt(songIdStr)
                paletteCache.put(songId, color)
                loaded++
            }
            paletteDirty = false
            Log.d(TAG, "loadPaletteFromDisk: loaded $loaded entries")
        } catch (e: Exception) {
            Log.w(TAG, "loadPaletteFromDisk failed: ${e.message}")
        }
    }

    /**
     * 将Cover颜色缓存写入磁盘
     */
    fun flushPaletteToDisk(context: Context) {
        if (!paletteDirty) return
        try {
            val root = org.json.JSONObject()
            val snapshot = paletteCache.snapshot()
            for ((songId, color) in snapshot) {
                root.put(songId.toString(), color)
            }
            context.applicationContext.getSharedPreferences(PREFS_PALETTE, Context.MODE_PRIVATE)
                .edit().putString(KEY_PALETTE_JSON, root.toString()).apply()
            paletteDirty = false
            Log.d(TAG, "flushPaletteToDisk: saved ${snapshot.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "flushPaletteToDisk failed: ${e.message}")
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCaches() {
        paletteCache.evictAll()
        preloadingIds.clear()
        Log.d(TAG, "All caches cleared")
    }

    /**
     * 低内存时清理部分缓存
     */
    fun onLowMemory() {
        // 清除一半的缓存
        val halfSize = paletteCache.size() / 2
        if (halfSize > 0) {
            // LruCache 会自动淘汰最久未使用的
            val entriesToRemove = paletteCache.snapshot().keys.take(halfSize)
            entriesToRemove.forEach { paletteCache.remove(it) }
            Log.d(TAG, "Low memory: cleared $halfSize palette entries")
        }
        preloadingIds.clear()
    }

    /**
     * 标记预加载On始
     * @return true 如果可以On始预加载,false 如果已经在预加载
     */
    fun startPreload(songId: Long): Boolean {
        return preloadingIds.putIfAbsent(songId, true) == null
    }

    /**
     * 标记预加载结束
     */
    fun endPreload(songId: Long) {
        preloadingIds.remove(songId)
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): String {
        return "Palette cache: ${paletteCache.size()}/$PALETTE_CACHE_SIZE"
    }
}


