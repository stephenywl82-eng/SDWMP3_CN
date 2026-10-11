package com.sdw.music.player.lyric

import android.util.Log
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LyricWord
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ 音乐 QRC 逐字歌词解析器（V8.127）
 *
 * QRC = QQ 专有逐字歌词格式。解密链：hex → 自定义 3DES(DECRYPT) → zlib → UTF-8。
 * 解密后正文形如：
 *   [ti:稻香]\n[0,7730]稻(0,552)香(552,552)...\n[7730,7740]词(7730,1548)...
 * 行内 `(起始ms,时长ms)` 即为该词的逐字时间轴。
 *
 * 本类只做「解析 + 转增强型 LRC」，不联网；取回由 QqLyricProvider 负责。
 * 转成增强型 LRC（`[mm:ss.xx]<mm:ss.xx>词...`）后可完全复用现有
 * LrcParser / LyricViewCompose / 缓存 / 编辑链路，无需改动渲染层。
 */
object QrcParser {

    private const val TAG = "QrcParser"

    /** QRC 行：`[行开始ms,行时长ms]正文` */
    private val LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)$""")

    /** QRC 词：`词(起始ms,时长ms)`（词文本可含中英文/空白） */
    private val WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")

    /** QRC 标签头（ti/ar/al/by/offset/style 等，非时间轴行） */
    private val TAG_REGEX = Regex("""^\[[a-zA-Z]+:.*$""")

    /**
     * 判断一段文本是否为 QRC 正文（解密后的形态）。
     * 特征：存在 `[数字,数字]` 行。
     */
    fun isQrc(text: String): Boolean {
        if (text.isBlank()) return false
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return LINE_REGEX.containsMatchIn(first.trim())
    }

    /**
     * hex 字符串 → 明文 QRC XML/正文。失败返回 null。
     */
    fun decryptHex(hex: String): String? {
        return try {
            val clean = hex.trim()
            if (clean.isEmpty() || clean.length % 16 != 0) return null
            val enc = ByteArray(clean.length / 2)
            var i = 0
            while (i < enc.size) {
                enc[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                i++
            }
            inflate(QrcTripleDes.decrypt(enc))?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "QRC 解密失败: ${e.message}")
            null
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "QRC zlib 解压失败: ${e.message}")
            null
        }
    }

    /**
     * 从 QRC XML 提取 `Lyric_1` 的 LyricContent；若已是纯正文则原样返回。
     */
    fun extractContent(raw: String): String {
        val m = Regex("""LyricContent="(.*?)"/>""", RegexOption.DOT_MATCHES_ALL).find(raw)
        return m?.groupValues?.get(1) ?: raw
    }

    /**
     * 解析 QRC 正文为逐字 [LyricLine] 列表。
     */
    fun parse(text: String): List<LyricLine> {
        val content = extractContent(text)
        val lines = mutableListOf<LyricLine>()
        for (raw in content.split('\n')) {
            val line = raw.trim().replace("\r", "")
            if (line.isEmpty()) continue
            if (TAG_REGEX.matches(line)) continue
            val lm = LINE_REGEX.find(line) ?: continue
            val lineStart = lm.groupValues[1].toLongOrNull() ?: continue
            val body = lm.groupValues[3]

            val words = mutableListOf<LyricWord>()
            val plainText = StringBuilder()
            for (wm in WORD_REGEX.findAll(body)) {
                val wText = wm.groupValues[1]
                val wStart = wm.groupValues[2].toLongOrNull() ?: continue
                // QRC 词时间戳基本是绝对毫秒；若明显小于行首则视为相对偏移
                val absStart = if (wStart >= lineStart - 2000) wStart else lineStart + wStart
                if (wText.isEmpty()) continue
                words.add(LyricWord(absStart, wText))
                plainText.append(wText)
            }
            if (words.isEmpty()) continue
            val text2 = plainText.toString().trim()
            if (text2.isEmpty()) continue
            lines.add(LyricLine(timeMs = lineStart, text = text2, words = words))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    /**
     * 把 QRC 解析结果转回增强型 LRC（`[mm:ss.xx]<mm:ss.xx>词...`），
     * 供现有 LrcParser / 缓存 / 编辑功能复用。
     */
    fun toEnhancedLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (l in lines) {
            sb.append(formatMs(l.timeMs))
            val words = l.words
            if (words.isNullOrEmpty()) {
                sb.append(l.text)
            } else {
                for (w in words) {
                    sb.append('<').append(formatWordMs(w.timeMs)).append('>').append(w.text)
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * hex → 增强型 LRC 文本。失败返回 null。
     */
    fun hexToEnhancedLrc(hex: String): String? {
        val plain = decryptHex(hex) ?: return null
        val lines = parse(plain)
        if (lines.isEmpty()) return null
        return toEnhancedLrc(lines)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        val cs = (ms % 1000) / 10
        return "[%02d:%02d.%02d]".format(mm, ss, cs)
    }

    /** 行内逐字词标记：`<mm:ss.xx>`（不带方括号，与 LrcParser.WORD_REGEX 对齐） */
    private fun formatWordMs(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(mm, ss, cs)
    }
}
