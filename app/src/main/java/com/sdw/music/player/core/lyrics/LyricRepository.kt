package com.sdw.music.player.lyric

import android.content.Context
import android.util.Log
import com.sdw.music.player.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 【v4.03】Lyrics聚合仓库 - 高性能优化版
 * 
 * 核心优化：
 * 1. 并发Search：select 优先Back，无需等待All
 * 2. 流式Back：Channel 实时推送，零延迟
 * 3. 智能Save：异步写入 + 去重检测
 * 4. Local加载：多级缓存 + 内存索引
 */
class LyricRepository private constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "LyricRepository"
        private const val SEARCH_TIMEOUT_MS = 10000L   // Search超时
        private const val MATCH_TIMEOUT_MS = 6000L     // 匹配超时
        private const val QUICK_MATCH_TIMEOUT_MS = 3000L  // 快速匹配超时
        
        @Volatile
        private var instance: LyricRepository? = null
        
        fun getInstance(context: Context): LyricRepository {
            return instance ?: synchronized(this) {
                instance ?: LyricRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Lyrics提供者列表（按优先级排序）
    // 优先级：LRCLIB（Kuwo已废、QQ/网易已移除）
    private val providers: List<ILyricProvider> by lazy {
        listOf(
            LRCLIBProvider(context)  // 兜底，英文歌友好但较慢
        )
    }
    
    // 【v4.03】内存缓存 - 避免重复Search
    private val lyricsCache = mutableMapOf<String, LyricResult>()
    
    // 【v7.XX】Local LRC 文件匹配器（Search同级目录+常见Lyrics目录）
    private val localLrcProvider = SaltLyricProvider()
    private val cacheLock = Any()
    
    /**
     * 【V7.70】暴露单个 provider 供 UI 按需获取Lyrics
     */
    fun getProvider(source: String): ILyricProvider? {
        return providers.find { it.providerId == source }
    }

    /**
     * 【新增】获取可用Lyrics源列表
     */
    /**
     * 获取可用来源的 UI 展示列表 (id -> 显示名)
     */
    fun getAvailableProviderOptions(): List<Pair<String, String>> {
        return listOf(
            "auto" to "Auto",
            "lrclib" to "LRCLIB",
            "local" to "Local"
        )
    }

    /**
     * 【新增】从指定Lyrics源获取Lyrics
     */
    suspend fun matchFromSpecificProvider(
        providerId: String,
        song: Song
    ): LyricResult? = withContext(Dispatchers.IO) {
        when (providerId) {
            "auto" -> return@withContext autoMatch(song)
            "local" -> {
                loadLocalLyrics(song)?.let { return@withContext it }
                return@withContext localLrcProvider.matchLocal(song.filePath, song.title, song.artist)
            }
            else -> {
                val provider = providers.find { it.providerId == providerId }
                provider?.match(song.title, song.artist, song.duration)
            }
        }
    }

    // 【v4.03】Local文件索引 - 加速Local查找
    private val localFileIndex = mutableMapOf<String, File>()
    
    /**
     * 【v4.03】并发聚合Search - select 优先Back
     * 
     * 优化点：
     * - 使用 select 获取最快Back的结果
     * - 无需等待All完成
     * - 实时去重
     */
    suspend fun searchFromAllSources(
        query: String,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): List<LyricResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LyricResult>()
        val seenIds = mutableSetOf<String>()  // 去重
        val completedCount = AtomicInteger(0)
        
        try {
            withTimeout(timeoutMs) {
                // 为每个提供者创建独立的 Deferred
                val deferreds = providers.map { provider ->
                    async {
                        try {
                            val providerResults = provider.search(query)
                            completedCount.incrementAndGet()
                            providerResults
                        } catch (e: Exception) {
                            Log.w(TAG, "${provider.providerName} Search失败: ${e.message}")
                            completedCount.incrementAndGet()
                            emptyList()
                        }
                    }
                }
                
                // 【核心优化】使用 select 优先处理先完成的
                while (completedCount.get() < providers.size) {
                    select<Unit> {
                        deferreds.forEach { deferred ->
                            deferred.onAwait { list ->
                                list.forEach { result ->
                                    val uniqueId = "${result.title}_${result.artist}_${result.source}"
                                    if (uniqueId !in seenIds) {
                                        seenIds.add(uniqueId)
                                        results.add(result)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 等待剩余任务
                deferreds.forEach { deferred ->
                    if (deferred.isActive) {
                        deferred.await().forEach { result ->
                            val uniqueId = "${result.title}_${result.artist}_${result.source}"
                            if (uniqueId !in seenIds) {
                                seenIds.add(uniqueId)
                                results.add(result)
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Search超时，Back已收集的 ${results.size} results")
        }
        
        // 按质量和来源排序
        results.sortedWith(compareByDescending<LyricResult> { it.hasSyncedLyrics() }
            .thenBy { providers.indexOfFirst { p -> p.providerId == it.source } }
        )
    }
    
    /**
     * 【v4.03】流式聚合Search - Channel 实时推送
     * 
     * 优化点：
     * - 使用 Channel 实现真正的实时推送
     * - 每个源独立协程，互不阻塞
     * - 支持背压和Cancel
     */
    fun searchFromAllSourcesFlow(
        query: String,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): Flow<LyricResult> = channelFlow {
        val seenIds = mutableSetOf<String>()
        
        // 为每个提供者启动独立协程
        // channelFlow 允许从不同协程安全调用 send()
        providers.forEach { provider ->
            launch(Dispatchers.IO) {
                try {
                    withTimeout(timeoutMs / 2) {  // 单个源更短超时
                        val results = provider.search(query)
                        results.forEach { result ->
                            val uniqueId = "${result.title}_${result.artist}_${result.source}"
                            val shouldSend = synchronized(seenIds) {
                                if (uniqueId !in seenIds) {
                                    seenIds.add(uniqueId)
                                    true
                                } else false
                            }
                            if (shouldSend) {
                                send(result)  // channelFlow 的 send，线程安全
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "${provider.providerName} 流式Search失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 【v4.03】自动匹配 - 智能优先级 + 快速Back
     * 
     * 优化点：
     * - 内存缓存优先
     * - Local文件次之
     * - 并发网络请求，取第一个有效结果
     * - 自动Save到Local（异步）
     */
    suspend fun autoMatch(
        song: Song,
        timeoutMs: Long = MATCH_TIMEOUT_MS
    ): LyricResult? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.title}_${song.artist}_${song.duration}"
        
        // 1. 内存缓存优先
        synchronized(cacheLock) {
            lyricsCache[cacheKey]?.let {
                Log.d(TAG, "💾 内存缓存命中: ${song.title}")
                return@withContext it
            }
        }
        
        // 2. Local文件次之
        loadLocalLyrics(song)?.let { localResult ->
            synchronized(cacheLock) {
                lyricsCache[cacheKey] = localResult
            }
            Log.d(TAG, "📁 LocalLyrics命中: ${song.title}")
            return@withContext localResult
        }
        
        // 2.5 【v7.XX】Local LRC 文件模糊匹配（Search常见目录）
        localLrcProvider.matchLocal(song.filePath, song.title, song.artist)?.let { localLrc ->
            synchronized(cacheLock) {
                lyricsCache[cacheKey] = localLrc
            }
            Log.d(TAG, "📁 LRCLocal匹配命中: ${song.title}")
            return@withContext localLrc
        }
        
        // 3. 并发网络匹配
        try {
            withTimeout(timeoutMs) {
                val results = mutableListOf<Deferred<LyricResult?>>()
                
                providers.forEach { provider ->
                    results += async {
                        try {
                            provider.match(song.title, song.artist, song.duration)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                
                // 等待第一个有效结果
                for (deferred in results) {
                    val result = deferred.await()
                    if (result != null && result.getBestLyrics() != null) {
                        // Cancel其他请求
                        results.forEach { it.cancel() }
                        
                        // 异步Save到Local和缓存
                        launch {
                            saveLyricsToLocal(song, result.getBestLyrics()!!)
                            synchronized(cacheLock) {
                                lyricsCache[cacheKey] = result
                            }
                        }
                        
                        Log.d(TAG, "✅ 网络匹配成功 [${result.source}]: ${song.title}")
                        return@withTimeout result
                    }
                }
                
                Log.d(TAG, "❌ 未找到匹配Lyrics: ${song.title}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "自动Match error: ${e.message}")
            null
        }
    }
    
    /**
     * 【v4.03】快速匹配 - 极简流程
     * 
     * 用于切歌时后台静默匹配，只查最稳定的源
     */
    suspend fun quickMatch(song: Song): LyricResult? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.title}_${song.artist}"
        
        // 内存缓存
        synchronized(cacheLock) {
            lyricsCache[cacheKey]?.let { return@withContext it }
        }
        
        // Local文件
        loadLocalLyrics(song)?.let {
            synchronized(cacheLock) { lyricsCache[cacheKey] = it }
            return@withContext it
        }
        
        // 【v7.XX】Local LRC 模糊匹配
        localLrcProvider.matchLocal(song.filePath, song.title, song.artist)?.let { localLrc ->
            synchronized(cacheLock) { lyricsCache[cacheKey] = localLrc }
            return@withContext localLrc
        }
        
        // 只查 LRCLIB（最稳定）
        try {
            withTimeout(QUICK_MATCH_TIMEOUT_MS) {
                val lrclib = providers.find { it.providerId == "lrclib" }
                val result = lrclib?.match(song.title, song.artist, song.duration)
                
                if (result != null) {
                    launch {
                        saveLyricsToLocal(song, result.getBestLyrics()!!)
                        synchronized(cacheLock) { lyricsCache[cacheKey] = result }
                    }
                }
                result
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // ========== 【v4.03】Local缓存管理优化 ==========
    
    /**
     * 读取LocalLyrics - 多级查找
     */
    private fun loadLocalLyrics(song: Song): LyricResult? {
        val actualPath = song.filePath.ifBlank { song.path }
        if (actualPath.isBlank()) return null
        
        // 1. 索引查找（最快）
        val indexedFile = localFileIndex[actualPath]
        if (indexedFile?.exists() == true) {
            return readLyricsFile(indexedFile, song)
        }
        
        // 2. 同目录查找
        val extensions = listOf("lrc", "LRC", "txt", "TXT")
        val basePath = actualPath.replace(Regex("\\.(flac|mp3|m4a|wav|ogg|aac|wma)$", RegexOption.IGNORE_CASE), "")
        
        for (ext in extensions) {
            val file = File("$basePath.$ext")
            if (file.exists()) {
                localFileIndex[actualPath] = file
                return readLyricsFile(file, song)
            }
        }
        
        // 3. Fallback directory — saveLyricsDirectly writes here when song dir is read-only (Android 10+ scoped storage)
        val fallbackDir = File(context.getExternalFilesDir(null), "lyrics")
        if (fallbackDir.isDirectory) {
            val safeName = "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val fallbackFile = File(fallbackDir, safeName)
            if (fallbackFile.exists()) {
                localFileIndex[actualPath] = fallbackFile
                return readLyricsFile(fallbackFile, song)
            }
        }
        
        return null
    }
    
    /**
     * 读取Lyrics文件 - 智能编码检测
     */
    private fun readLyricsFile(file: File, song: Song): LyricResult? {
        return try {
            val content = readLyricsWithEncodingDetection(file) ?: return null
            
            LyricResult(
                title = song.title,
                artist = song.artist,
                album = song.album,
                syncedLrc = if (content.contains("[")) content else null,
                plainLrc = if (!content.contains("[")) content else null,
                source = "local",
                songId = song.id.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取Lyrics文件失败: ${e.message}")
            null
        }
    }
    
    /**
     * 编码自动检测
     */
    private fun readLyricsWithEncodingDetection(file: File): String? {
        // Lyrics文件不应超过 2MB，防止 OOM
        if (file.length() > 2 * 1024 * 1024) {
            Log.w(TAG, "Lyrics文件过大，跳过: ${file.absolutePath}")
            return null
        }
        val bytes = file.readBytes()
        
        // 1. UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        
        // 2. UTF-8
        try {
            val content = String(bytes, Charsets.UTF_8)
            if (!content.contains("�") && isValidLrcContent(content)) return content
        } catch (_: Exception) { }
        
        // 3. GBK
        try {
            val content = String(bytes, charset("GBK"))
            if (isValidLrcContent(content)) {
                Log.d(TAG, "🔤 检测到 GBK 编码")
                return content
            }
        } catch (_: Exception) { }
        
        // 4. UTF-16
        try {
            val content = String(bytes, Charsets.UTF_16)
            if (isValidLrcContent(content)) return content
        } catch (_: Exception) { }
        
        // 5. 兜底 UTF-8
        return String(bytes, Charsets.UTF_8)
    }
    
    /**
     * 内容有效性检测
     */
    private fun isValidLrcContent(content: String): Boolean {
        if (content.isBlank()) return false
        if (content.contains("�")) return false
        
        // 检测有效字符比例
        val validChars = content.count { it.isLetterOrDigit() || it in "[]:,.，。？！\n\r \t" }
        return validChars >= content.length * 0.6
    }
    
    /**
     * 【v8.0】公OnSaveLyrics - 供编辑功能调用
     * @return Save成功Back文件路径，失败Back null
     */
    suspend fun saveLyricsDirectly(song: Song, lyrics: String): String? = withContext(Dispatchers.IO) {
        try {
            val actualPath = song.filePath.ifBlank { song.path }

            // 目标文件：优先同目录；content URI / 空路径 → 走 fallback
            val targetFile: File
            if (actualPath.isNotBlank() && !actualPath.startsWith("content://")) {
                val lrcPath = actualPath.replace(
                    Regex("\\.(flac|mp3|m4a|wav|ogg|aac|wma)$", RegexOption.IGNORE_CASE),
                    ".lrc"
                )
                val lrcFile = File(lrcPath)
                val parent = lrcFile.parentFile
                if (parent != null && parent.canWrite()) {
                    targetFile = lrcFile
                } else {
                    val fallbackDir = File(context.getExternalFilesDir(null), "lyrics").apply { mkdirs() }
                    targetFile = File(fallbackDir, "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_"))
                }
            } else {
                // content URI 或空路径 → always fallback
                val fallbackDir = File(context.getExternalFilesDir(null), "lyrics").apply { mkdirs() }
                targetFile = File(fallbackDir, "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_"))
            }

            try {
                targetFile.writeText(lyrics, Charsets.UTF_8)
            } catch (perm: Exception) {
                // Android 10+ scoped storage: canWrite() may return true but writeText still gets EACCES
                val fallbackDir = File(context.getExternalFilesDir(null), "lyrics").apply { mkdirs() }
                val safeName = "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                val fallbackFile = File(fallbackDir, safeName)
                fallbackFile.writeText(lyrics, Charsets.UTF_8)
                Log.w(TAG, "💾 Lyrics write to co-located path denied, saved to fallback: ${fallbackFile.name}")
                localFileIndex[fallbackFile.absolutePath] = fallbackFile
                val cacheKey = "${song.title}_${song.artist}_${song.duration}"
                synchronized(cacheLock) { lyricsCache.remove(cacheKey) }
                return@withContext fallbackFile.absolutePath
            }
            localFileIndex[targetFile.absolutePath] = targetFile
            val cacheKey = "${song.title}_${song.artist}_${song.duration}"
            synchronized(cacheLock) { lyricsCache.remove(cacheKey) }
            Log.d(TAG, "💾 Lyrics已Save: ${targetFile.name}")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "手动SaveLyrics失败: ${e.message}")
            null
        }
    }

    /**
     * 【v4.03】异步SaveLyrics到Local
     */
    private suspend fun saveLyricsToLocal(song: Song, lyrics: String) = withContext(Dispatchers.IO) {
        try {
            val actualPath = song.filePath.ifBlank { song.path }
            if (actualPath.isBlank()) return@withContext
            
            val lrcPath = actualPath.replace(
                Regex("\\.(flac|mp3|m4a|wav|ogg|aac|wma)$", RegexOption.IGNORE_CASE), 
                ".lrc"
            )
            val lrcFile = File(lrcPath)
            
            // 检查目录可写性
            val parent = lrcFile.parentFile
            if (parent != null && !parent.canWrite()) {
                Log.w(TAG, "Directory not writable: ${parent.absolutePath}")
                return@withContext
            }
            
            // 异步写入
            lrcFile.writeText(lyrics, Charsets.UTF_8)
            localFileIndex[actualPath] = lrcFile  // 更新索引
            
            Log.d(TAG, "💾 Lyrics已Save: ${lrcFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "SaveLyrics失败: ${e.message}")
        }
    }
    
    /**
     * 清除内存缓存
     */
    fun clearCache() {
        synchronized(cacheLock) {
            lyricsCache.clear()
        }
        localFileIndex.clear()
        Log.d(TAG, "🧹 缓存已清除")
    }
    
    /**
     * 预加载LocalLyrics索引
     */
    fun preloadLocalIndex(musicFiles: List<Song>) {
        CoroutineScope(Dispatchers.IO).launch {
            musicFiles.forEach { song ->
                val actualPath = song.filePath.ifBlank { song.path }
                if (actualPath.isNotBlank()) {
                    val basePath = actualPath.replace(
                        Regex("\\.(flac|mp3|m4a|wav|ogg|aac|wma)$", RegexOption.IGNORE_CASE), 
                        ".lrc"
                    )
                    val file = File(basePath)
                    if (file.exists()) {
                        localFileIndex[actualPath] = file
                    }
                }
            }
            Log.d(TAG, "📚 Local索引已加载: ${localFileIndex.size} 首")
        }
    }
    
    /**
     * 获取可用提供者（Back provider 实例列表）
     */
    fun getAvailableProvidersList(): List<ILyricProvider> = providers
}




