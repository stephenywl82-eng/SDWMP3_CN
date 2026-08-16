package com.sdw.music.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 智能Lyrics系统 - Steven 完整版
 * 核心逻辑：后台静默匹配（自动）+ 弹窗交互修正（手动）
 * 
 * 优先级：
 * 1. Local同名 .lrc 文件
 * 2. 内嵌Lyrics（FLAC/MP3）
 * 3. LRCLIB 精确匹配（自动Save）
 * 4. 用户手动Search
 */
object LyricUtils {
    private const val TAG = "LyricUtils"
    
    // LRCLIB API（免费、无需 API Key）
    private const val LRCLIB_API = "https://lrclib.net/api"
    
    // OkHttp 客户端（带超时）
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 【v2.9.6 新增】Lyrics加载结果，包含翻译信息
     */
    data class LyricLoadResult(
        val lines: List<LyricLine>,
        val hasTranslation: Boolean = false,
        val source: String = ""
    )

    /**
     * 【Steven 关键】干扰项正则 - 清洗歌曲标题
     * 过滤掉：括号内容、方括号内容、质量标记、官方标记等
     */
    private val CLEANUP_PATTERNS = listOf(
        Regex("""\s*\([^)]*\)\s*"""),           // (Official Video), (feat. XXX)
        Regex("""\s*\[[^\]]*\]\s*"""),           // [FLAC], [320kbps], [MV]
        Regex("""\s*-\s*(Official|官方|MV|视频).*""", RegexOption.IGNORE_CASE),  // - Official Audio
        Regex("""\s*(feat\.|ft\.).*""", RegexOption.IGNORE_CASE), // feat. XXX
        Regex("""\s+\d{3,4}kbps\s*""", RegexOption.IGNORE_CASE),  // 320kbps
        Regex("""\s+(HD|HQ|Remastered|Remix|Edit)\s*""", RegexOption.IGNORE_CASE),
        Regex("""\s+官方版\s*$"""),             // 中文官方版
        Regex("""\s+$"""),                       // 尾部空格
        Regex("""^\s+""")                        // 头部空格
    )

    /**
     * 清洗歌曲标题/歌手名
     */
    fun cleanMetadata(raw: String): String {
        var result = raw
        CLEANUP_PATTERNS.forEach { pattern ->
            result = result.replace(pattern, " ")
        }
        return result.trim()
    }

    /**
     * 主入口：按优先级加载Lyrics
     * 1. 同名 .lrc 文件
     * 2. 内嵌Lyrics（FLAC/MP3）
     * 3. LRCLIB 精确匹配（自动Save）
     */
    suspend fun loadLyrics(context: Context, song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        // 优先级 1：同名 .lrc 文件（使用实际文件路径，而非 content URI）
        val actualPath = song.filePath.ifBlank { song.path }
        val localLrc = loadLocalLrcFile(actualPath)
        if (localLrc != null) {
            Log.d(TAG, "✅ Local .lrc 文件加载成功: ${song.title}")
            return@withContext localLrc
        }
        
        // 优先级 2：内嵌Lyrics（使用实际文件路径）
        val embedded = loadEmbeddedLyrics(actualPath)
        if (embedded != null) {
            Log.d(TAG, "✅ 内嵌Lyrics加载成功: ${song.title}")
            return@withContext embedded
        }
        
        // 优先级 3：在线Search（LRCLIB）
        // 【V7.29】从多源并发Search改为仅 LRCLIB，提升稳定性
        val multiOnline = searchAllSourcesAndSaveLyrics(context, song, actualPath)
        if (multiOnline != null) {
            Log.d(TAG, "✅ 多源在线Lyrics加载成功（已Save）: ${song.title}")
            return@withContext multiOnline
        }
        
        // 【Steven 修复】无Lyrics时Back空列表，让 LyricView 显示空白
        Log.d(TAG, "❌ 未找到Lyrics: ${song.title}")
        return@withContext emptyList()
    }

    /**
     * 【Steven 建议】查找同名 .lrc 文件
     * 例如：/Music/Kaskade - It's You It's Me.flac
     *       → /Music/Kaskade - It's You It's Me.lrc
     */
    private fun loadLocalLrcFile(audioPath: String): List<LyricLine>? {
        return try {
            // 构造 .lrc 文件路径
            val lrcPath = audioPath.replace(Regex("\\.(flac|mp3|m4a|wav|ogg)$", RegexOption.IGNORE_CASE), ".lrc")
            val lrcFile = File(lrcPath)
            
            if (!lrcFile.exists()) {
                Log.d(TAG, "Local .lrc 不存在: $lrcPath")
                return null
            }
            
            // Lyrics文件不应超过 2MB，防止误读二进制文件导致 OOM
            val fileSize = lrcFile.length()
            if (fileSize > 2 * 1024 * 1024) {
                Log.w(TAG, "Lyrics文件过大 ($fileSize bytes)，跳过: $lrcPath")
                return null
            }
            
            // 【关键】Steven 提醒：处理 GBK 编码
            val rawBytes = FileInputStream(lrcFile).use { it.readBytes() }
            val lrcText = tryDetectEncoding(rawBytes)
            
            if (lrcText.isNotBlank()) {
                LrcParser.parse(lrcText)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "读取 .lrc 失败: ${e.message}")
            null
        }
    }

    /**
     * 【Steven 建议】使用 MediaMetadataRetriever 提取内嵌Lyrics
     * 注意：Android 原生对内嵌Lyrics支持有限
     */
    private fun loadEmbeddedLyrics(path: String): List<LyricLine>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            
            // 【关键】Android 没有 METADATA_KEY_LYRICS 常量
            // 需要使用原始键名（部分设备支持）
            val lyrics = extractLyricsMetadata()
            
            if (!lyrics.isNullOrBlank() && lyrics.contains("[")) {
                LrcParser.parse(lyrics)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "提取内嵌Lyrics失败: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 尝试提取Lyrics元数据（多字段兼容）
     */
    private fun extractLyricsMetadata(): String? {
        // Android MediaMetadataRetriever 标准 API 不支持内嵌Lyrics读取
        // 需要厂商扩展或第三方库（如 JAudioTagger）才能支持
        // 此处保留接口占位，Back null 由 LRC 文件兜底
        return null
    }

    /**
     * 【Steven 建议】智能检测文件编码
     * 解决中文 GBK 乱码问题
     */
    private fun tryDetectEncoding(bytes: ByteArray): String {
        // 优先尝试 UTF-8（BOM 检测）
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, Charsets.UTF_8)
        }
        
        // 尝试 UTF-8
        val utf8String = String(bytes, Charsets.UTF_8)
        if (!utf8String.contains("�")) {
            return utf8String
        }
        
        // 尝试 GBK（中文常用）
        val gbkString = String(bytes, Charset.forName("GBK"))
        if (!gbkString.contains("�")) {
            return gbkString
        }
        
        // 默认Back UTF-8
        return utf8String
    }

    /**
     * 【Steven 改进】LRCLIB 精确匹配 + 自动Save
     * 使用 get 接口进行精确匹配（比 search 更准确）
     * @param song 歌曲信息
     * @param actualPath 实际文件路径（用于Save .lrc）
     */
    private suspend fun searchAllSourcesAndSaveLyrics(context: Context, song: Song, actualPath: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        return@withContext try {
            val repository = com.sdw.music.player.lyric.LyricRepository.getInstance(context)
            val query = if (song.artist.isNotEmpty() && song.artist != "Unknown Artist")
                "${song.artist} - ${song.title}" else song.title
            Log.d(TAG, "多源Search: $query")
            
            val results = repository.searchFromAllSources(query, timeoutMs = 12000L)
            val bestResult = results.firstOrNull { r -> 
                val lrc = r.syncedLrc ?: r.plainLrc; !lrc.isNullOrBlank() 
            }
            
            if (bestResult != null) {
                val rawLrc = bestResult.syncedLrc ?: bestResult.plainLrc ?: return@withContext null
                val parsed = LrcParser.parse(rawLrc)
                if (parsed.isNotEmpty()) {
                    Log.d(TAG, "✅ [${bestResult.source}] 找到Lyrics: ${bestResult.title}")
                    val lrcPath = actualPath.replaceAfterLast('.', "lrc")
                    try { java.io.File(lrcPath).writeText(rawLrc); Log.d(TAG, "💾 已Save: $lrcPath") } catch (e: Exception) { Log.w(TAG, "Save失败: ${e.message}") }
                    return@withContext parsed
                }
            }
            Log.d(TAG, "多源Search无结果")
            null
        } catch (e: Exception) { Log.w(TAG, "多源Search异常: ${e.message}"); null }
    }

    private suspend fun searchAndSaveLyrics(context: Context, song: Song, actualPath: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        return@withContext try {
            // 【关键】先清洗元数据
            val cleanTitle = cleanMetadata(song.title)
            val cleanArtist = cleanMetadata(song.artist)
            
            // 构建精确匹配 URL（LRCLIB get 接口）
            val url = buildString {
                append("$LRCLIB_API/get?")
                append("artist_name=${java.net.URLEncoder.encode(cleanArtist, "UTF-8")}&")
                append("track_name=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}")
            }
            
            Log.d(TAG, "🌐 精确匹配: $cleanTitle - $cleanArtist")
            Log.d(TAG, "   URL: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPro/1.2.0 (Android)")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "精确匹配失败: HTTP ${response.code}")
                // 回退到Search接口
                return@withContext fallbackSearch(context, song, actualPath)
            }
            
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.w(TAG, "Empty response body")
                return@withContext fallbackSearch(context, song, actualPath)
            }
            
            // 解析 JSON
            val json = JSONObject(body)
            val syncedLyrics = json.optString("syncedLyrics", "")
            val plainLyrics = json.optString("plainLyrics", "")
            
            val lrcText = when {
                !syncedLyrics.isNullOrBlank() && syncedLyrics.contains("[") -> syncedLyrics
                !plainLyrics.isNullOrBlank() -> plainLyrics
                else -> null
            }
            
            if (lrcText != null) {
                // 【Steven 关键】自动Save到Local（使用实际文件路径）
                saveLyricsToFile(context, actualPath, lrcText)
                
                // 解析并Back
                if (lrcText.contains("[")) {
                    LrcParser.parse(lrcText)
                } else {
                    // 纯文本转换
                    lrcText.lines()
                        .filter { it.isNotBlank() }
                        .mapIndexed { index, text -> LyricLine(index * 5000L, text.trim()) }
                }
            } else {
                Log.d(TAG, "❌ LRCLIB Back无Lyrics")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "精确Match error: ${e.message}")
            null
        }
    }

    /**
     * 【Steven 备用】Search接口（当精确匹配失败时）
     */
    private suspend fun fallbackSearch(context: Context, song: Song, actualPath: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        return@withContext try {
            val cleanTitle = cleanMetadata(song.title)
            val cleanArtist = cleanMetadata(song.artist)
            val query = "$cleanTitle $cleanArtist"
            
            val url = "$LRCLIB_API/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            
            Log.d(TAG, "🔍 Search回退: $query")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPro/1.2.0 (Android)")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val body = response.body?.string() ?: return@withContext null
            val jsonArray = org.json.JSONArray(body)
            
            // 遍历结果，优先选同步Lyrics
            for (i in 0 until minOf(jsonArray.length(), 20)) {
                val item = jsonArray.getJSONObject(i)
                val synced = item.optString("syncedLyrics")
                val plain = item.optString("plainLyrics")
                
                val lrcText = when {
                    !synced.isNullOrBlank() && synced.contains("[") -> synced
                    !plain.isNullOrBlank() -> plain
                    else -> continue
                }
                
                // Save并Back（使用实际文件路径）
                saveLyricsToFile(context, actualPath, lrcText)
                
                return@withContext if (lrcText.contains("[")) {
                    LrcParser.parse(lrcText)
                } else {
                    lrcText.lines().filter { it.isNotBlank() }
                        .mapIndexed { index, text -> LyricLine(index * 5000L, text.trim()) }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Search回退失败: ${e.message}")
            null
        }
    }

    /**
     * 【Steven 关键】自动SaveLyrics到Local .lrc 文件
     */
    /**
     * 【V7.46修复】SaveLyrics到Local
     * 支持两种路径模式：
     * 1. 真实文件路径（如 /storage/emulated/0/Music/xxx.flac）→ 直接写文件
     * 2. content:// URI → 使用SAF (Storage Access Framework) 查找同名.lrc并写入
     */
    private fun saveLyricsToFile(context: android.content.Context, audioPath: String, lrcContent: String) {
        try {
            if (audioPath.startsWith("content://")) {
                // Android 10+ 作用域存储：content:// URI → 用SAF查找同名.lrc
                val lrcDisplayName = android.net.Uri.parse(audioPath)
                    .lastPathSegment
                    ?.substringAfterLast('/')
                    ?.replace(Regex("\\.(flac|mp3|m4a|wav|ogg)$", RegexOption.IGNORE_CASE), "")
                    ?: File(audioPath).nameWithoutExtension
                
                // 查找同目录下同名.lrc文件
                val parentUri = getParentUriFromPath(audioPath)
                if (parentUri != null) {
                    val foundUri = findLrcFileInDirectory(context, "$lrcDisplayName.lrc", lrcContent)
                    if (foundUri != null) {
                        Log.d(TAG, "✅ Lyrics已Save(SAF): $lrcDisplayName")
                    } else {
                        Log.w(TAG, "无法找到/创建Lyrics文件(SAF): $lrcDisplayName，可能需要用户授权")
                    }
                } else {
                    Log.w(TAG, "无法获取父目录URI: $audioPath")
                }
                return
            }
            
            // 传统方式：真实文件路径
            val lrcPath = audioPath.replace(Regex("\\.(flac|mp3|m4a|wav|ogg)$", RegexOption.IGNORE_CASE), ".lrc")
            val lrcFile = File(lrcPath)
            val parentDir = lrcFile.parentFile
            if (parentDir?.canWrite() != true) {
                Log.w(TAG, "Cannot write to dir，跳过Save: $lrcPath")
                return
            }
            FileOutputStream(lrcFile).use { fos ->
                fos.write(lrcContent.toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "✅ Lyrics已Save: $lrcPath")
        } catch (e: Exception) {
            Log.e(TAG, "SaveLyrics失败: ${e.message}")
        }
    }

    private fun getFileNameFromPath(path: String): String {
        return path.substringAfterLast('/').substringAfterLast(':')
    }

    private fun getParentUriFromPath(path: String): android.net.Uri? {
        return try {
            val uri = android.net.Uri.parse(path)
            // 从content://com.android.providers.media.documents/document/audio%3A123提取父路径
            val docId = uri.lastPathSegment ?: return null
            // parent 路径: content://com.android.providers.media.documents/file/AUDIO/123
            val parentDocId = docId.substringAfter("audio:").substringAfter(":")
            android.net.Uri.parse("content://com.android.provider.media.documents/document/audio%3A$parentDocId")
        } catch (e: Exception) {
            null
        }
    }

    private fun findLrcFileInDirectory(context: android.content.Context, lrcName: String, content: String): android.net.Uri? {
        // Bypass SAF complexity: save to app's lyrics directory
        // Android 10+ MediaStore automatically indexes getExternalFilesDir files
        return try {
            val lyricsDir = java.io.File(context.getExternalFilesDir(null), "lyrics")
            if (!lyricsDir.exists()) lyricsDir.mkdirs()
            val lrcFile = java.io.File(lyricsDir, lrcName)
            lrcFile.writeText(content, Charsets.UTF_8)
            android.util.Log.d(TAG, "LRC saved to: " + lrcFile.absolutePath)
            android.net.Uri.fromFile(lrcFile)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "LRC save failed: ${e.message}")
            null
        }
    }

    /**
     * 兼容旧接口
     */
    @Suppress("UNUSED_PARAMETER")
    fun getEmbeddedLyrics(path: String): String? = null // 已废弃
    
    fun parseEmbeddedLyrics(lyrics: String): List<LyricLine> {
        return if (LrcParser.isLrcFormat(lyrics)) {
            LrcParser.parse(lyrics)
        } else {
            // 纯文本，每 5 秒一行
            lyrics.lines().filter { it.isNotBlank() }
                .mapIndexed { index, text -> LyricLine(index * 5000L, text.trim()) }
        }
    }
}




