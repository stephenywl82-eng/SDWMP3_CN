package com.sdw.music.player.lyric

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sdw.music.player.LrcParser
import com.sdw.music.player.LyricLine
import com.sdw.music.player.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LRCLIB Lyrics提供者
 * 
 * 【Steven 设计要点】
 * - 免费、无需 API Key
 * - 支持精确匹配和模糊Search
 * - 自动Save到Local
 */
class LRCLIBProvider(
    private val context: Context
) : ILyricProvider {
    
    override val providerName: String = "LRCLIB"
    override val providerId: String = "lrclib"
    
    companion object {
        private const val TAG = "LRCLIBProvider"
        private const val LRCLIB_API = "https://lrclib.net/api"
        private const val TIMEOUT_MS = 15000L  // LRCLIB响应慢(8-10s)，需要更长超时
        
        // 【Steven】字符清洗正则
        private val CLEANUP_PATTERNS = listOf(
            Regex("""\s*\([^)]*\)\s*"""),      // (Official Video)
            Regex("""\s*\[[^\]]*\]\s*"""),     // [FLAC], [320kbps]
            Regex("""\s*(feat\.|ft\.).*"""),  // feat. XXX
            Regex("""\s+\d{3,4}kbps\s*"""),   // 320kbps
            Regex("""\s+(HD|HQ|Remastered)\s*""")
        )
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    
    override suspend fun search(query: String): List<LyricResult> {
        return try {
            val cleanQuery = cleanMetadata(query)
            val url = "$LRCLIB_API/search?q=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}"
            
            Log.d(TAG, "🔍 Search: $cleanQuery")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPro/1.3.0 (Android)")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Search失败: HTTP ${response.code}")
                return emptyList()
            }
            
            val body = response.body?.string() ?: return emptyList()
            parseSearchResults(JSONArray(body))
        } catch (e: Exception) {
            Log.e(TAG, "Search异常: ${e.message}")
            emptyList()
        }
    }
    
    override suspend fun match(title: String, artist: String, duration: Long): LyricResult? {
        // 【v7.XX】Local LRC 文件兜底（优先于 API 调用）
        val localResult = loadLocalLrcFile(title, artist, duration)
        if (localResult != null) {
            Log.d(TAG, "Local LRC 命中，跳过 API 调用: $title")
            return localResult
        }
        
        return try {
            val cleanTitle = cleanMetadata(title)
            val cleanArtist = cleanMetadata(artist)
            
            // 优先使用精确匹配接口
            val url = buildString {
                append("$LRCLIB_API/get?")
                append("artist_name=${java.net.URLEncoder.encode(cleanArtist, "UTF-8")}&")
                append("track_name=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}")
                if (duration > 0) {
                    append("&duration=${duration / 1000}")
                }
            }
            
            Log.d(TAG, "精确匹配: $cleanTitle - $cleanArtist")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPro/1.3.0 (Android)")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "精确匹配失败: HTTP ${response.code}")
                // 回退到Search
                return fallbackSearch(cleanTitle, cleanArtist)
            }
            
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            
            val result = parseSingleResult(json)
            if (result != null) {
                Log.d(TAG, "精确匹配成功")
                result
            } else {
                fallbackSearch(cleanTitle, cleanArtist)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Match error: ${e.message}")
            null
        }
    }
    override suspend fun getLyrics(songId: String): LyricResult? {
        return try {
            val url = "$LRCLIB_API/get/$songId"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPro/1.3.0 (Android)")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            parseSingleResult(json)
        } catch (e: Exception) {
            Log.e(TAG, "获取Lyrics异常: ${e.message}")
            null
        }
    }
    
    override suspend fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$LRCLIB_API/get?artist_name=test&track_name=test")
                .build()
            
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    // ========== 【v7.XX】Local LRC 文件匹配 ==========
    
    /**
     * SearchLocal LRC 文件作为兜底
     * Search路径：音频同级目录 → Music/lrc → Lyrics → Lyrics → Download/lrc
     * 匹配优先级：精确同名 → 标题+歌手模糊 → 仅标题
     */
    private fun loadLocalLrcFile(title: String, artist: String, duration: Long): LyricResult? {
        val searchDirs = mutableListOf<File>()
        
        val externalDir = context.getExternalFilesDir(null)
        val musicDir = File(externalDir?.parent?.substringBeforeLast("/") ?: "/storage/emulated/0", "Music")
        searchDirs.add(musicDir)
        searchDirs.add(File(musicDir, "lrc"))
        searchDirs.add(File(musicDir, "Lyrics"))
        searchDirs.add(File("/storage/emulated/0/Music/lrc"))
        searchDirs.add(File("/storage/emulated/0/Lyrics"))
        searchDirs.add(File("/storage/emulated/0/Download/lrc"))
        
                // Filename sanitization - use replace chain
        var cleanTitle = title
        cleanTitle = cleanTitle.replace("/", "")
        cleanTitle = cleanTitle.replace("\\", "")
        cleanTitle = cleanTitle.replace(":", "")
        cleanTitle = cleanTitle.replace("*", "")
        cleanTitle = cleanTitle.replace("\"", "")
        cleanTitle = cleanTitle.replace("<", "")
        cleanTitle = cleanTitle.replace(">", "")
        cleanTitle = cleanTitle.replace("|", "")
        cleanTitle = cleanTitle.trim()
        
        var cleanArtist = artist
        cleanArtist = cleanArtist.replace("/", "")
        cleanArtist = cleanArtist.replace("\\", "")
        cleanArtist = cleanArtist.replace(":", "")
        cleanArtist = cleanArtist.replace("*", "")
        cleanArtist = cleanArtist.replace("\"", "")
        cleanArtist = cleanArtist.replace("<", "")
        cleanArtist = cleanArtist.replace(">", "")
        cleanArtist = cleanArtist.replace("|", "")
        cleanArtist = cleanArtist.trim()
        
        val extensions = listOf("lrc", "LRC", "txt")
        

        val queryVariants = listOf(
            "$cleanTitle - $cleanArtist",
            "$cleanArtist - $cleanTitle",
            cleanTitle,
            cleanArtist
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            
            for (query in queryVariants) {
                for (ext in extensions) {
                    // Exact match
                    val exactFile = File(dir, "$query.$ext")
                    if (exactFile.exists()) {
                        return parseLocalLrcFile(exactFile, title, artist)
                    }
                    
                    // Fuzzy match (contains)
                    dir.listFiles()?.find { f ->
                        f.nameWithoutExtension.contains(query, ignoreCase = true) &&
                        extensions.any { f.name.endsWith(".$it", ignoreCase = true) }
                    }?.let { matchedFile ->
                        return parseLocalLrcFile(matchedFile, title, artist)
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Parse local LRC file
     */
    private fun parseLocalLrcFile(file: File, title: String, artist: String): LyricResult? {
        return try {
            val content = readLocalLrcWithEncoding(file)
            if (content.isNullOrBlank()) return null
            
            LyricResult(
                title = title,
                artist = artist,
                album = null,
                syncedLrc = if (content.contains("[")) content else null,
                plainLrc = if (!content.contains("[")) content else null,
                source = "lrclib_local",
                songId = file.absolutePath.hashCode().toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse local LRC failed: " + e.message)
            null
        }
    }
    
    /**
     * Local LRC encoding detection
     */
    private fun readLocalLrcWithEncoding(file: File): String? {
        if (file.length() > 2 * 1024 * 1024) return null  // skip >2MB
        val bytes = file.readBytes()
        
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-8
        try {
            val s = String(bytes, Charsets.UTF_8)
            if (!s.contains("�")) return s
        } catch (_: Exception) {}
        // GBK
        try {
            val s = String(bytes, charset("GBK"))
            if (s.contains("[") || s.contains(":")) return s
        } catch (_: Exception) {}
        // UTF-16
        try {
            return String(bytes, Charsets.UTF_16)
        } catch (_: Exception) {}
        return null
    }
    
    // ========== 私有方法 ==========
    
    private fun cleanMetadata(text: String): String {
        var result = text
        CLEANUP_PATTERNS.forEach { pattern ->
            result = result.replace(pattern, " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }
    
    private suspend fun fallbackSearch(title: String, artist: String): LyricResult? {
        val results = search("$title $artist")
        return results.firstOrNull()
    }
    
    private fun parseSearchResults(jsonArray: JSONArray): List<LyricResult> {
        val results = mutableListOf<LyricResult>()
        
        for (i in 0 until minOf(jsonArray.length(), 20)) {
            val item = jsonArray.getJSONObject(i)
            val result = parseSingleResult(item)
            if (result != null) {
                results.add(result)
            }
        }
        
        return results.sortedByDescending { it.hasSyncedLyrics() }
    }
    
    private fun parseSingleResult(json: JSONObject): LyricResult? {
        return try {
            val synced = json.optString("syncedLyrics")
            val plain = json.optString("plainLyrics")
            
            if (synced.isNullOrBlank() && plain.isNullOrBlank()) {
                return null
            }
            
            LyricResult(
                title = json.optString("trackName", "Unknown"),
                artist = json.optString("artistName", "Unknown"),
                album = json.optString("albumName"),
                syncedLrc = if (!synced.isNullOrBlank()) synced else null,
                plainLrc = if (!plain.isNullOrBlank()) plain else null,
                source = providerId,
                songId = json.optInt("id", 0).toString()
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 【Steven 关键】自动SaveLyrics到Local
     */
    fun saveLyricsToLocal(song: Song, lyrics: String) {
        try {
            // 【Steven 修复】使用实际文件路径，而非 content URI
            val actualPath = song.filePath.ifBlank { song.path }
            val lrcPath = actualPath.replace(Regex("\\.(flac|mp3|m4a|wav|ogg)$", RegexOption.IGNORE_CASE), ".lrc")
            val lrcFile = File(lrcPath)
            
            lrcFile.parentFile?.let { parent ->
                if (!parent.canWrite()) {
                    Log.w(TAG, "Directory not writable: $lrcPath")
                    return
                }
            }
            
            lrcFile.writeText(lyrics)
            Log.d(TAG, "💾 Lyrics已Save: $lrcPath")
        } catch (e: Exception) {
            Log.e(TAG, "Save失败: ${e.message}")
        }
    }
}




