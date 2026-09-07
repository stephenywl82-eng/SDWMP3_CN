package com.sdw.music.player.core.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import com.sdw.music.player.Song
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 【V8.20】歌曲音频规格懒读取器（播放界面显示用）。
 * 免解码读文件头：采样率 / 码率 / 位深（尽力取）。
 * 结果缓存（path → spec），切歌重复播放不重新读盘。
 *
 * 显示文本形态（由 [Spec.displayText] 拼装）：
 *   "FLAC · 24bit · 96kHz · 4609kbps"   高解析（HiRes 高亮由 UI 决定）
 *   "MP3 · 16bit · 44.1kHz · 320kbps"
 * 码率算不出时省略该段；全部取不到时返回 null（不显示）。
 */
object SongSpecReader {

    data class Spec(
        val format: String,      // 大写：FLAC / ALAC / MP3 / M4A...
        val bitDepth: Int = 0,   // 0 = 未知
        val sampleRate: Int = 0, // Hz
        val bitrate: Int = 0     // kbps，0 = 未知
    ) {
        val isHiRes: Boolean get() = bitDepth >= 24 || sampleRate >= 88200

        /** 拼装显示文本；返回 null 表示无可显示信息 */
        fun displayText(): String? {
            if (sampleRate <= 0 && bitDepth <= 0 && bitrate <= 0) return null
            val sb = StringBuilder(format)
            if (bitDepth > 0) sb.append(" · ${bitDepth}bit")
            if (sampleRate > 0) sb.append(" · ${sampleRate / 1000.0}kHz")
            if (bitrate > 0) sb.append(" · ${bitrate}kbps")
            return sb.toString()
        }
    }

    private val cache = ConcurrentHashMap<String, Spec>()

    fun clearCache() = cache.clear()

    fun getCached(path: String): Spec? = cache[path]

    /** 懒读取入口：先查缓存，未命中则解析（IO 线程调用） */
    fun specFor(song: Song): Spec? {
        val path = song.filePath.ifBlank { song.path }
        if (path.isBlank()) return null
        cache[path]?.let { return it }
        val spec = parse(path, song.format)
        if (spec != null) cache[path] = spec
        return spec
    }

    private fun parse(path: String, extFormat: String): Spec? {
        val format = normalizeFormat(extFormat, path)
        var sampleRate = 0
        var bitrateKbps = 0
        var pcmEncoding = 0
        var mime: String? = null
        var durationUs = 0L

        val ex = MediaExtractor()
        try {
            ex.setDataSource(path) // 支持 content:// 与普通文件路径
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    mime = m
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrateKbps = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    }
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = f.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
        } catch (_: Throwable) {
            // 解析失败：走下方文件大小兜底
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }

        val bitDepth = decodeBitDepth(mime, pcmEncoding)

        // 码率兜底：用 文件大小×8/时长（无损与有损 CBR 都算得准）
        if (bitrateKbps <= 0) {
            val sizeBytes = fileSize(path)
            if (sizeBytes > 0) {
                val durMs = if (durationUs > 0) durationUs / 1000 else durationMsFromFile(path)
                if (durMs > 0) {
                    // bytes*8/ms/1000 → kbps：sizeBytes*8/(durMs) = bits/ms ≈ kbps
                    bitrateKbps = ((sizeBytes * 8L) / durMs).toInt()
                }
            }
        }

        if (sampleRate <= 0 && bitDepth <= 0 && bitrateKbps <= 0) return null
        return Spec(format = format, bitDepth = bitDepth, sampleRate = sampleRate, bitrate = bitrateKbps)
    }

    private fun normalizeFormat(extFormat: String, path: String): String {
        var e = extFormat.ifBlank { path.substringAfterLast('.').substringBefore('?') }.uppercase()
        if (e == "M4A") e = if (mimeOf(path)?.contains("alac") == true) "ALAC" else "M4A"
        return e.ifBlank { "AUDIO" }
    }

    private fun mimeOf(path: String): String? {
        return try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(path)
                var mime: String? = null
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("audio/")) { mime = m; break }
                }
                mime
            } finally { ex.release() }
        } catch (_: Throwable) { null }
    }

    /** MediaFormat 的 PCM encoding 常量 → 位深（尽力而为） */
    private fun decodeBitDepth(mime: String?, pcmEncoding: Int): Int {
        return when {
            pcmEncoding == 2 || pcmEncoding == android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
            pcmEncoding == 21 || pcmEncoding == android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            pcmEncoding == 22 || pcmEncoding == android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
            pcmEncoding == 4 || pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
            pcmEncoding == 3 || pcmEncoding == android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
            mime == "audio/alac" -> 16
            else -> 0
        }
    }

    private fun fileSize(path: String): Long {
        return try {
            when {
                path.startsWith("file://") -> File(android.net.Uri.parse(path).path ?: "").length()
                // content:// 无法直接拿 size 时跳过（duration 兜底码率对 content uri 不可用）
                path.startsWith("content://") -> 0L
                else -> File(path).length()
            }
        } catch (_: Throwable) { 0L }
    }

    private fun durationMsFromFile(path: String): Long {
        return try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(path)
                var dur = 0L
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION)
                        break
                    }
                }
                dur / 1000
            } finally { ex.release() }
        } catch (_: Throwable) { 0L }
    }
}
