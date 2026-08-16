package com.sdw.music.player.lyric

/**
 * Lyrics提供者接口
 * 
 * 【Steven 设计要点】
 * - 所有Lyrics插件必须实现此接口
 * - 支持手动Search和自动匹配
 * - 协程友好，所有方法都是 suspend
 */
interface ILyricProvider {
    
    /**
     * 提供者名称（用于 UI 显示）
     */
    val providerName: String
    
    /**
     * 提供者标识（用于日志和排序）
     */
    val providerId: String
    
    /**
     * 手动Search：根据关键词Back结果列表
     * 
     * @param query Search关键词（歌名 + 歌手）
     * @return Search结果列表
     */
    suspend fun search(query: String): List<LyricResult>
    
    /**
     * 自动匹配：根据精确信息直接BackLyrics
     * 
     * @param title 歌曲名（已清洗）
     * @param artist 歌手（已清洗）
     * @param duration 歌曲时长（毫秒，用于精确匹配）
     * @return 匹配结果，未找到Back null
     */
    suspend fun match(title: String, artist: String, duration: Long = 0): LyricResult?
    
    /**
     * 获取Lyrics详情（通过 songId）
     * 
     * @param songId 平台歌曲 ID
     * @return Lyrics结果
     */
    suspend fun getLyrics(songId: String): LyricResult?
    
    /**
     * 是否可用（网络检查、API 限制等）
     */
    suspend fun isAvailable(): Boolean {
        return true
    }
}


