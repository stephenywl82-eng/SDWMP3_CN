package com.sdw.music.player.lyric

import android.util.Log
import java.io.File
import com.sdw.music.player.LrcParser

/**
 * 【v7.XX】Local LRC 文件Lyrics提供者
 * 
 * 替代原有的 Salt Lyrics占位符，实现LocalLyrics文件匹配：
 * 1. Search音频文件同级目录的 .lrc 文件
 * 2. Search常见Lyrics目录（Music/lrc、Lyrics 等）
 * 3. 使用文件名模糊匹配（支持含 Remix、Live 等后缀）
 */
class SaltLyricProvider : ILyricProvider {

    override val providerName: String = "LocalLRC"
    override val providerId: String = "local_lrc"

    companion object {
        private const val TAG = "LocalLrcProvider"
        
        // 常见Lyrics目录（相对于外部存储根目录）
        private val COMMON_LYRIC_DIRS = listOf(
            "Music/lrc",
            "Music/Lyrics", 
            "Lyrics",
            "Lyrics",
            "Download/lrc"
        )
    }

    override suspend fun search(query: String): List<LyricResult> {
        // LocalSearch需要文件路径，search 方法不支持
        // Back空列表，依赖 match() 方法
        return emptyList()
    }

    override suspend fun match(title: String, artist: String, duration: Long): LyricResult? {
        // Local匹配需要文件路径，此方法不支持
        // 将在 LyricRepository 中通过扩展接口支持
        return null
    }

    /**
     * 【核心】根据音频文件路径匹配Local LRC 文件
     * 
     * @param audioFilePath 音频文件绝对路径
     * @param title 歌曲标题（用于模糊匹配）
     * @param artist 歌手（用于模糊匹配）
     * @return 匹配的Lyrics结果
     */
    suspend fun matchLocal(audioFilePath: String, title: String, artist: String): LyricResult? {
        if (audioFilePath.isBlank()) return null
        
        try {
            val audioFile = File(audioFilePath)
            val audioDir = audioFile.parentFile ?: return null
            val audioNameNoExt = audioFile.nameWithoutExtension.lowercase()
            
            // 1. Search音频文件同级目录
            val sameDirLrc = searchLrcInDir(audioDir, audioNameNoExt, title, artist)
            if (sameDirLrc != null) {
                Log.d(TAG, "同级目录找到LRC: ${sameDirLrc.name}")
                return parseLrcFile(sameDirLrc)
            }
            
            // 2. Search常见Lyrics目录
            val externalStorage = android.os.Environment.getExternalStorageDirectory()
            for (dirPath in COMMON_LYRIC_DIRS) {
                val lrcDir = File(externalStorage, dirPath)
                if (lrcDir.exists() && lrcDir.isDirectory) {
                    val foundLrc = searchLrcInDir(lrcDir, audioNameNoExt, title, artist)
                    if (foundLrc != null) {
                        Log.d(TAG, "Lyrics目录找到LRC: ${foundLrc.name}")
                        return parseLrcFile(foundLrc)
                    }
                }
            }
            
            Log.d(TAG, "未找到LocalLRC文件: $title")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "匹配LocalLRC异常: ${e.message}")
            return null
        }
    }

    /**
     * 在指定目录中Search匹配的 LRC 文件
     */
    private fun searchLrcInDir(dir: File, audioNameNoExt: String, title: String, artist: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        
        val titleLower = title.lowercase()
        val artistLower = artist.lowercase()
        
        // 按优先级匹配
        val lrcFiles = dir.listFiles { file -> 
            file.extension.lowercase() == "lrc" 
        } ?: return null
        
        // 优先级1：精确匹配文件名（音频文件同名 .lrc）
        val exactMatch = lrcFiles.find { 
            it.nameWithoutExtension.lowercase() == audioNameNoExt 
        }
        if (exactMatch != null) return exactMatch
        
        // 优先级2：标题+歌手匹配（允许顺序颠倒）
        val titleArtistMatch = lrcFiles.find { f ->
            val nameLower = f.nameWithoutExtension.lowercase()
            nameLower.contains(titleLower) && nameLower.contains(artistLower)
        }
        if (titleArtistMatch != null) return titleArtistMatch
        
        // 优先级3：仅标题匹配（歌手为空或未知时）
        if (titleLower.isNotBlank() && titleLower != "unknown") {
            val titleOnlyMatch = lrcFiles.find { 
                it.nameWithoutExtension.lowercase().contains(titleLower) 
            }
            if (titleOnlyMatch != null) return titleOnlyMatch
        }
        
        // 优先级4：模糊匹配（标题关键词）
        val keywords = titleLower.split(" ", "-", "_", "(", "[").filter { it.isNotBlank() }
        if (keywords.isNotEmpty()) {
            val keywordMatch = lrcFiles.find { f ->
                val nameLower = f.nameWithoutExtension.lowercase()
                keywords.any { kw -> nameLower.contains(kw) && kw.length >= 3 }
            }
            if (keywordMatch != null) return keywordMatch
        }
        
        return null
    }

    /**
     * 解析 LRC 文件为 LyricResult
     */
    private fun parseLrcFile(file: File): LyricResult? {
        return try {
            val content = file.readText(charset = Charsets.UTF_8)
            val lines = LrcParser.parse(content)
            
            if (lines.isEmpty()) {
                Log.w(TAG, "LRC parse failed（无有效行）: ${file.name}")
                return null
            }
            
            LyricResult(
                title = file.nameWithoutExtension,
                artist = "",
                album = null,
                source = providerId,
                syncedLrc = content,
                plainLrc = null,
                songId = file.absolutePath.hashCode().toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read LRC file: ${file.name}, ${e.message}")
            null
        }
    }

    override suspend fun getLyrics(songId: String): LyricResult? {
        // Local模式不支持 songId
        return null
    }

    override suspend fun isAvailable(): Boolean {
        // Local匹配始终可用（不依赖网络）
        return true
    }
}



