package com.sdw.music.player

/**
 * LRCLIB API LyricsSearch结果数据模型
 * @param id          Lyrics唯一ID
 * @param name       歌曲名称
 * @param artistName 艺术家名称
 * @param albumName  专辑名称（可能为空）
 * @param duration   歌曲时长（秒）
 * @param instrumental 是否为器乐（无人声）
 * @param plainLyrics 纯文本Lyrics（无时间轴）
 * @param syncedLyrics 带时间轴的 LRC 格式Lyrics
 */
data class LyricSearchResult(
    val id: Int,
    val name: String,
    val artistName: String,
    val albumName: String?,
    val duration: Int,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
) {
    /** 是否有同步Lyrics（LRC 格式） */
    val hasSyncedLyrics: Boolean
        get() = !syncedLyrics.isNullOrBlank() && syncedLyrics.contains("[")

    /** 是否有纯文本Lyrics */
    val hasPlainLyrics: Boolean
        get() = !plainLyrics.isNullOrBlank()

    /** 获取可用的Lyrics文本（优先同步Lyrics） */
    val usableLyrics: String?
        get() = syncedLyrics ?: plainLyrics
}


