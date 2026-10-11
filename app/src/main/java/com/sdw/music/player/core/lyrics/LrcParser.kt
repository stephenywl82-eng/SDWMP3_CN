package com.sdw.music.player

/**
 * LRC Lyrics解析器 - Steven 优化版
 * 支持标准 LRC 格式、增强时间标签、多行时间戳
 */
object LrcParser {

    // 【关键】Steven 建议的正则表达式，支持毫秒
    private val LRC_REGEX = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})\](.*)""")

    // 【V8.121】逐字时间戳：行内 <mm:ss.xx> 尖括号标记（增强型 LRC / KRC 导出）
    private val WORD_REGEX = Regex("""<(\d{2}):(\d{2})[.:](\d{2,3})>""")

    /**
     * 检查文本是否为 LRC 格式
     */
    fun isLrcFormat(text: String): Boolean {
        return LRC_REGEX.containsMatchIn(text)
    }

    /**
     * 解析 LRC 格式的Lyrics文本
     * @param lrcContent LRC 文件内容
     * @return 排序后的Lyrics行列表
     */
    fun parse(lrcContent: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        lrcContent.lines().forEach { line ->
            // 【Steven 建议】支持同一行多时间标签（如 [00:00.00][01:30.00]副歌）
            val matches = LRC_REGEX.findAll(line)
            val timeStamps = mutableListOf<Long>()
            var content = ""
            
            matches.forEach { match ->
                val (minutes, seconds, milliseconds, text) = match.destructured
                val timeMs = minutes.toLong() * 60_000 +
                             seconds.toLong() * 1000 +
                             if (milliseconds.length == 2) milliseconds.toLong() * 10
                             else milliseconds.toLong()
                timeStamps.add(timeMs)
                content = text.trim()
            }
            
            // 每个时间戳创建一行Lyrics
            timeStamps.forEach { timeMs ->
                // 【V8.121】解析行内逐字时间戳；有则拆词并去标记后作为纯文本显示
                val words = parseWords(content, timeMs)
                val displayText = if (words != null) words.joinToString("") { it.text } else content
                lines.add(LyricLine(timeMs, displayText, null, words))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    /**
     * 【V8.121】解析行内逐字时间戳 <mm:ss.xx>，返回词序列；无则返回 null
     *
     * 语义：<time> 标记其后文本段的开始时间；首个 <time> 之前的文本用行首时间。
     * 例：[00:12.00]今天<00:12.50>天气<00:13.00>不错
     *   -> [今天@12.00][天气@12.50][不错@13.00]
     */
    private fun parseWords(content: String, lineStartMs: Long): List<LyricWord>? {
        val matches = WORD_REGEX.findAll(content).toList()
        if (matches.isEmpty()) return null

        val words = mutableListOf<LyricWord>()
        var cursor = 0
        var prevTime = lineStartMs
        for (m in matches) {
            // 【V8.127】不做 trim：QRC/逐字歌词的词间空格需原样保留，
            // 否则 joinToString("") 无法还原显示文本；content 整体已在 parse() 里 trim 过。
            val seg = content.substring(cursor, m.range.first)
            if (seg.isNotEmpty()) words.add(LyricWord(prevTime, seg))
            cursor = m.range.last + 1
            val (mm, ss, ms) = m.destructured
            prevTime = mm.toLong() * 60_000 + ss.toLong() * 1000 +
                       (if (ms.length == 2) ms.toLong() * 10 else ms.toLong())
        }
        val tail = content.substring(cursor)
        if (tail.isNotEmpty()) words.add(LyricWord(prevTime, tail))
        return if (words.isEmpty()) null else words
    }

    /**
     * 根据当前播放位置查找对应的Lyrics行索引
     * 【Steven 建议】使用二分查找提高性能
     */
    fun findCurrentLineIndex(lyrics: List<LyricLine>, currentPosition: Long): Int {
        if (lyrics.isEmpty()) return -1

        // 二分查找最后一个时间 <= currentPosition 的行
        var left = 0
        var right = lyrics.size - 1
        var result = -1

        while (left <= right) {
            val mid = (left + right) / 2
            if (lyrics[mid].timeMs <= currentPosition) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    /**
     * 【v2.9.6 新增】合并原文Lyrics和翻译Lyrics
     * 
     * @param originalLrc 原文 LRC 内容
     * @param translatedLrc 翻译 LRC 内容
     * @return 合并后的Lyrics行列表，每行包含原文和翻译
     */
    fun mergeWithTranslation(originalLrc: String, translatedLrc: String): List<LyricLine> {
        // 解析原文和翻译
        val originalLines = parse(originalLrc)
        val translatedLines = parse(translatedLrc)
        
        // 构建翻译时间映射
        val translationMap = translatedLines.associateBy { it.timeMs }
        
        // 合并：原文为主，匹配时间戳添加翻译
        return originalLines.map { line ->
            val translation = translationMap[line.timeMs]?.text
            if (!translation.isNullOrBlank() && translation != line.text) {
                line.copy(translation = translation)
            } else {
                line
            }
        }
    }
}

/**
 * 单行Lyrics数据类
 */
data class LyricLine(
    val timeMs: Long,      // 时间戳（毫秒）
    val text: String,      // Lyrics文本
    val translation: String? = null,  // 【v2.9.6 新增】翻译文本
    val words: List<LyricWord>? = null  // 【V8.121】逐字时间戳词序列（增强型 LRC）
) {
    // 辅助方法：是否有翻译
    fun hasTranslation(): Boolean = !translation.isNullOrBlank()
    // 【V8.121】是否有逐字时间戳
    fun hasWords(): Boolean = !words.isNullOrEmpty()
}

/**
 * 【V8.121】逐字时间戳词条（增强型 LRC 行内 <mm:ss.xx> 标记）
 */
data class LyricWord(
    val timeMs: Long,      // 该词开始时间（毫秒）
    val text: String       // 词文本
)


