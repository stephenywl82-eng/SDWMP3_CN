package com.sdw.music.player.lyric
import com.sdw.music.player.core.audio.metadata.LyricsEmbedder
import com.sdw.music.player.core.audio.engine.UsbDacManager
import com.sdw.music.player.core.audio.metadata.WavCoverReader

import android.content.Context
import android.util.Log
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
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
            QqLyricProvider(),      // V8.127 逐字歌词唯一来源（QRC），中文歌覆盖最好
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
            "QQ" to "Int Music",
            "lrclib" to "LRCLIB",
            "local" to "Local",
            "embedded" to "Embedded"
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
            "embedded" -> return@withContext withTimeout(4000L) { loadEmbedded(song) }  // 防大文件 native 解析卡住无限转圈
            else -> {
                val provider = providers.find { it.providerId == providerId }
                provider?.match(song.title, song.artist, song.duration)
            }
        }
    }


    // [V8.117] 内嵌歌词「忽略」集合（B）：用户清除过后，即使内嵌标签还在（WAV 无写入器、
    // 或没有「所有文件访问」权限），也不再用它 —— 否则读取端内嵌最优先，错歌词永远赢。
    private val ignoredPrefs by lazy { context.getSharedPreferences("lyric_prefs", Context.MODE_PRIVATE) }

    private fun markEmbeddedIgnored(songId: Long) {
        val cur = ignoredPrefs.getStringSet("ignored_embedded", emptySet()) ?: emptySet()
        ignoredPrefs.edit().putStringSet("ignored_embedded", cur + songId.toString()).apply()
    }

    private fun isEmbeddedIgnored(songId: Long): Boolean =
        (ignoredPrefs.getStringSet("ignored_embedded", emptySet()) ?: emptySet()).contains(songId.toString())

    // [V8.134] 内嵌写入成功后解除「忽略内嵌」标记。此前只 mark 从不 clear：
    // 一次失败留下的标记会永久压制内嵌源，之后即使真的写成功，读取端仍按「已忽略」
    // 走 .lrc / 网络源 —— 用户观感还是「保存了却读不到新词」。
    private fun clearEmbeddedIgnored(songId: Long) {
        val cur = ignoredPrefs.getStringSet("ignored_embedded", emptySet()) ?: emptySet()
        if (!cur.contains(songId.toString())) return
        ignoredPrefs.edit().putStringSet("ignored_embedded", cur - songId.toString()).apply()
    }

    /**
     * 【V8.117】 清除歌词：①标记忽略内嵌（WAV / 无写权限也能生效，可逆）
     * ②移除音频内嵌 LYRICS/USLT（FLAC/MP3，需写权限）③删除同目录与兜底目录的 .lrc ④清内存缓存。
     * @return 是否成功（至少完成标记 + 缓存清理）
     */
    suspend fun deleteLyrics(song: Song): Boolean = withContext(Dispatchers.IO) {
        val actualPath = song.filePath.ifBlank { song.path }
        markEmbeddedIgnored(song.id)

        if (actualPath.isNotBlank() && !actualPath.startsWith("content://")) {
            val ext = actualPath.substringAfterLast('.', "").lowercase()
            // [V8.134] removeWavLyrics 在 V8.129 已写好，但门闸漏了 wav/wave -> 永远走不到
            if (ext == "flac" || ext == "mp3" || ext == "wav" || ext == "wave") {
                val removed = com.sdw.music.player.core.audio.metadata.LyricsEmbedder.removeLyrics(actualPath)
                Log.d(TAG, "🧹 移除内嵌歌词${if (removed) "成功" else "失败/无"}: $actualPath")
            }
            // 删除同目录 .lrc / .LRC
            val base = LrcPaths.deriveBase(actualPath)
            if (base != null) {
                File("$base.lrc").takeIf { it.exists() }?.delete()
                File("$base.LRC").takeIf { it.exists() }?.delete()
            }
        }
        // 删除兜底目录 .lrc
        val fallbackDir = File(context.getExternalFilesDir(null), "lyrics")
        val safeName = "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        File(fallbackDir, safeName).takeIf { it.exists() }?.delete()

        // 清缓存 / 索引
        val cacheKey = "${song.title}_${song.artist}_${song.duration}"
        synchronized(cacheLock) { lyricsCache.remove(cacheKey) }
        localFileIndex.remove(actualPath)
        LrcPaths.derive(actualPath)?.let { localFileIndex.remove(it) }
        Log.d(TAG, "🧹 歌词已清除: ${song.title}")
        true
    }

    /**
     * 【v8.0】直接读音频文件内嵌歌词（验证用）
     * FLAC：native dr_flac 读 Vorbis comment LYRICS / UNSYNCEDLYRICS；其他格式暂不支持
     */
    private suspend fun loadEmbedded(song: Song): LyricResult? = withContext(Dispatchers.IO) {
        val actualPath = song.filePath.ifBlank { song.path }
        Log.d(TAG, "loadEmbedded: path=$actualPath")
        if (actualPath.isBlank()) return@withContext null
        // [V8.117] 用户已清除过该曲内嵌歌词 -> 忽略，交给本地 .lrc / 网络源
        if (isEmbeddedIgnored(song.id)) { Log.d(TAG, "loadEmbedded: 已忽略内嵌歌词"); return@withContext null }
        val ext = actualPath.substringAfterLast('.', "").lowercase()
        val isFlac = ext == "flac"
        // [V8.91] WAV 走内嵌 ID3v2 的 USLT 帧。平台索引对 WAV 完全拿不到标签，
        // 所以这里没有平台兜底可用 —— 有 USLT 就用，没有就交给后面的本地 LRC / 网络源。
        val isWav = ext == "wav" || ext == "wave"
        // [V8.96] MP3：内嵌 USLT 帧此前只写不读（写入端早已支持），这里补齐读取。
        // 复用 SongRepository.readId3v2Lyrics（支持 ID3v2.3 / v2.4）。
        val isMp3 = ext == "mp3"
        if (!isFlac && !isWav && !isMp3) { Log.d(TAG, "loadEmbedded: 未支持的格式 $ext, skip"); return@withContext null }
        try {
            val lyrics = when {
                isWav -> com.sdw.music.player.core.audio.metadata.WavCoverReader.readTags(actualPath)?.lyrics
                isMp3 -> SongRepository.readId3v2Lyrics(actualPath)
                else -> com.sdw.music.player.core.audio.engine.UsbDacManager.flacReadLyrics(context, actualPath)
            }
            if (lyrics.isNullOrBlank()) { Log.d(TAG, "loadEmbedded: 内嵌歌词为空"); return@withContext null }
            Log.d(TAG, "loadEmbedded: 读到 ${lyrics.length} 字")
            val hasSync = lyrics.contains('[')
            LyricResult(
                title = song.title,
                artist = song.artist,
                album = song.album,
                syncedLrc = if (hasSync) lyrics else null,
                plainLrc = if (hasSync) null else lyrics,
                source = "embedded",
                songId = "embedded"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "读内嵌歌词失败: ${e.message}")
            null
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
        
        // 0. 【内嵌歌词优先】音频内嵌 LYRICS tag 最权威，绝不误配（FLAC 优先，其他格式自动跳过）
        loadEmbedded(song)?.let { embedded ->
            synchronized(cacheLock) {
                lyricsCache[cacheKey] = embedded
            }
            Log.d(TAG, "🎵 内嵌歌词命中: ${song.title}")
            return@withContext embedded
        }
        
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
        
        // 优先 QQ（唯一逐字源），失败再回退 LRCLIB（最稳定）
        try {
            withTimeout(QUICK_MATCH_TIMEOUT_MS) {
                val qq = providers.find { it.providerId == "QQ" }
                val lrclib = providers.find { it.providerId == "lrclib" }
                val result = qq?.match(song.title, song.artist, song.duration)
                    ?: lrclib?.match(song.title, song.artist, song.duration)
                
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
        val basePath = LrcPaths.deriveBase(actualPath)
        
        if (basePath != null) {
            for (ext in extensions) {
                val file = File("$basePath.$ext")
                if (file.exists()) {
                    localFileIndex[actualPath] = file
                    return readLyricsFile(file, song)
                }
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
            // [V8.134] 实际落盘的 .lrc 路径。同目录写成功 / 兜底目录写成功都记在这里，
            // 不再用提前 return 短路 —— 那会整个跳过下面的内嵌写入。
            var savedPath: String? = null
            val actualPath = song.filePath.ifBlank { song.path }

            // 目标文件：优先同目录；content URI / 空路径 → 走 fallback
            val targetFile: File
            if (actualPath.isNotBlank() && !actualPath.startsWith("content://")) {
                val lrcFile = LrcPaths.safeTarget(actualPath)
                val parent = lrcFile?.parentFile
                if (lrcFile != null && parent != null && parent.canWrite()) {
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
                // [V8.134] 关键修复：这里**不能提前 return**。旧代码从这条分支直接 return，
                // 下面的内嵌写入整段被跳过 —— 于是「.lrc 落到兜底目录（算保存成功）
                // + 内嵌歌词从未被尝试」。用户看到的就是「编辑后保存了，内嵌却没变」。
                // 现在只记录「.lrc 走了兜底」，继续往下无条件尝试一次内嵌写入。
                val fallbackDir = File(context.getExternalFilesDir(null), "lyrics").apply { mkdirs() }
                val safeName = "${song.title}_${song.artist}.lrc".replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                val fallbackFile = File(fallbackDir, safeName)
                fallbackFile.writeText(lyrics, Charsets.UTF_8)
                Log.w(TAG, "💾 Lyrics write to co-located path denied, saved to fallback: ${fallbackFile.name}")
                localFileIndex[fallbackFile.absolutePath] = fallbackFile
                val cacheKey0 = "${song.title}_${song.artist}_${song.duration}"
                synchronized(cacheLock) { lyricsCache.remove(cacheKey0) }
                savedPath = fallbackFile.absolutePath
            }
            if (savedPath == null) {
                localFileIndex[targetFile.absolutePath] = targetFile
                val cacheKey = "${song.title}_${song.artist}_${song.duration}"
                synchronized(cacheLock) { lyricsCache.remove(cacheKey) }
                Log.d(TAG, "💾 Lyrics已Save: ${targetFile.name}")
                savedPath = targetFile.absolutePath
            }

            // 内嵌进音频 tag（FLAC / MP3 / WAV），失败不影响 .lrc 保存结果
            var embeddedOk = false
            if (actualPath.isNotBlank() && !actualPath.startsWith("content://")) {
                val ext = actualPath.substringAfterLast('.', "").lowercase()
                // [V8.134] 补 wav/wave：V8.129 的 WAV 写入器此前被这道门闸挡死
                if (ext == "flac" || ext == "mp3" || ext == "wav" || ext == "wave") {
                    embeddedOk = com.sdw.music.player.core.audio.metadata.LyricsEmbedder.embedLyrics(actualPath, lyrics)
                    Log.d(TAG, "💾 内嵌歌词${if (embeddedOk) "成功" else "失败/不支持"}: $actualPath")
                }
            }
            // [V8.128] 内嵌源在 autoMatch 里是第 0 优先级：内嵌没更新就抑制它，
            // 否则旧的内嵌词会在下次加载时盖掉刚保存的 .lrc。
            // [V8.134] 写入成功则相反 —— 解除抑制，让新内嵌成为权威来源（对称收口）。
            if (embeddedOk) clearEmbeddedIgnored(song.id) else markEmbeddedIgnored(song.id)

            // [V8.138] 整文件重写（改内嵌歌词）后 size/mtime 全变，而工程从不通知媒体库 ->
            // albumart 虚拟表那一行失效 -> 各界面取不到图只露 logo，看起来就是「封面被删了」。
            // 写入成功时重新抽取容器内封面并通知媒体库，把封面 URI 救回来。
            if (embeddedOk) {
                try {
                    SongRepository.refreshArtAfterRewrite(context, actualPath)
                } catch (e: Exception) {
                    Log.w(TAG, "刷新封面失败: ${e.message}")
                }
            }

            savedPath
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
            
            val lrcFile = LrcPaths.safeTarget(actualPath) ?: return@withContext
            
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
                    val lrcPath = LrcPaths.derive(actualPath) ?: return@forEach
                    val file = File(lrcPath)
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




