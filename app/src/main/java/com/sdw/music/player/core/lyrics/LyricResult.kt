package com.sdw.music.player.lyric

/**
 * 统一的Lyrics结果格式
 * 
 * 【Steven 设计要点】
 * - 所有Lyrics源Back统一格式
 * - 支持同步Lyrics和纯文本Lyrics
 * - 记录来源用于 UI 展示
 */
data class LyricResult(
    val title: String,           // 歌曲名
    val artist: String,          // 歌手
    val album: String? = null,   // 专辑（可选）
    val syncedLrc: String?,      // 带时间轴的Lyrics内容
    val plainLrc: String?,       // 纯文本Lyrics
    val translatedLrc: String? = null,  // 【v2.9.6 新增】翻译Lyrics
    val source: String,          // 来源标识: "QQ", "NetEase", "LRCLIB"
    val songId: String           // 平台特有的歌曲 ID
) {
    /**
     * 获取最佳Lyrics内容
     * 优先Back同步Lyrics
     */
    fun getBestLyrics(): String? {
        return when {
            !syncedLrc.isNullOrBlank() && syncedLrc.contains("[") -> syncedLrc
            !plainLrc.isNullOrBlank() -> plainLrc
            else -> null
        }
    }
    
    /**
     * 是否有同步Lyrics
     */
    fun hasSyncedLyrics(): Boolean {
        return !syncedLrc.isNullOrBlank() && syncedLrc.contains("[")
    }
    
    /**
     * 是否有翻译Lyrics
     */
    fun hasTranslation(): Boolean {
        return !translatedLrc.isNullOrBlank()
    }
    
    /**
     * 来源标签颜色
     */
    fun getSourceColor(): String {
        return when (source) {
            "local" -> "#FFB300"   // Local金色
            "salt" -> "#31C27C"    // Salt Lyrics绿
            "QQ" -> "#31C27C"      // QQ 音乐绿
            "NetEase" -> "#E60026" // NetEase红
            "LRCLIB" -> "#2196F3"  // LRCLIB 蓝
            "kugou" -> "#2196F3"   // Kugou蓝
            "kuwo" -> "#FF9800"    // Kuwo橙
            else -> "#9E9E9E"      // 默认灰
        }
    }
    
    /**
     * 来源显示名称
     */
    fun getSourceDisplayName(): String {
        return when (source) {
            "local" -> "Local"
            "salt" -> "SaltLyrics"
            "QQ" -> "QQ Music"
            "NetEase" -> "NetEase"
            "LRCLIB" -> "LRCLIB"
            "kugou" -> "Kugou"
            "kuwo" -> "Kuwo"
            else -> source
        }
    }
}



