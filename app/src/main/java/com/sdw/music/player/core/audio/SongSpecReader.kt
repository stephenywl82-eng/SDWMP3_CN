package com.sdw.music.player.core.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import com.sdw.music.player.Song
import java.io.File
import java.io.FileInputStream
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

        // 【V8.429】整个 create→use→release 收敛到单线程闸门，消除与其它探测
        // 并发拆卸内部 codec 导致的 native abort（详见 MediaProbe 类注释）。
        MediaProbe.onProbeThread {
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
        }

        var bitDepth = decodeBitDepth(mime, pcmEncoding)

        // 【V8.122】文件头真实位深兜底：KEY_PCM_ENCODING 报的是平台解码器输出位深，
        // FLAC/ALAC 24bit 源会被平台解码器降成 16bit（本项目上 libFLAC 直解就是为绕开这个），
        // 故对无损格式直接读文件头拿源位深，覆盖解码器位深。
        if (format == "FLAC" || format == "ALAC" || format == "WAV") {
            val hdr = parseHeaderBitDepth(path, format)
            if (hdr > 0) bitDepth = hdr
        }

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
            // 【V8.429】同parse：收敛到单线程闸门。可重入（已在闸门线程时就地执行）。
            MediaProbe.onProbeThread {
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
            }
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
            mime == "audio/alac" -> 16  // 【V8.122】ALAC 真实位深走文件头 magic cookie 覆盖；读不到时退回 16（多数 ALAC 为 16bit）
            else -> 0
        }
    }

    /** 【V8.122】按格式解文件头拿真实位深（源位深，非平台解码器输出位深）。
     *  返回 0 = 解析失败/未知，调用方保留原 decodeBitDepth 结果。 */
    private fun parseHeaderBitDepth(path: String, format: String): Int {
        return try {
            when (format) {
                "FLAC" -> flacBitDepth(path)
                "WAV" -> wavBitDepth(path)
                "ALAC" -> alacBitDepth(path)
                else -> 0
            }
        } catch (_: Throwable) { 0 }
    }

    /** 读文件前 maxBytes 字节；content:// 无 Context 读不到返回 null。 */
    private fun readHead(path: String, maxBytes: Int): ByteArray? {
        return try {
            val file = when {
                path.startsWith("file://") -> File(android.net.Uri.parse(path).path ?: return null)
                path.startsWith("content://") -> return null
                else -> File(path)
            }
            if (!file.exists() || file.length() < 64) return null
            val len = minOf(file.length(), maxBytes.toLong()).toInt()
            val buf = ByteArray(len)
            FileInputStream(file).use { fis ->
                var off = 0
                while (off < len) {
                    val n = fis.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
            }
            buf
        } catch (_: Throwable) { null }
    }

    /** FLAC：读 "fLaC" + STREAMINFO（首个 block）拿 bits-per-sample。
     *  STREAMINFO 34 字节，bit 布局（MSB 在前）：min/max block(各16) min/max frame(各24)
     *  sampleRate(20) channels-1(3) bitsPerSample-1(5) totalSamples(36) MD5(128)。 */
    private fun flacBitDepth(path: String): Int {
        val buf = readHead(path, 64) ?: return 0
        if (buf.size < 43) return 0
        if (buf[0] != 'f'.code.toByte() || buf[1] != 'L'.code.toByte() ||
            buf[2] != 'a'.code.toByte() || buf[3] != 'C'.code.toByte()) return 0
        val blockType = buf[4].toInt() and 0x7F
        if (blockType != 0) return 0  // 首 block 必为 STREAMINFO(type=0)
        val data = 8  // 跳过 "fLaC"(4) + block header(4)
        // STREAMINFO 位布局（MSB 在前）：min/max block(16+16) min/max frame(24+24)
        // sampleRate(20) channels-1(3) bps-1(5) totalSamples(36) MD5(128)
        // sampleRate+channels+bps 跨 data[10..13]：
        //   data[12] bit0 = bps-1 bit4，data[13] bit7..4 = bps-1 bit3..0
        val b0 = buf[data + 12].toInt() and 0xFF
        val b1 = buf[data + 13].toInt() and 0xFF
        val bps1 = ((b0 and 0x01) shl 4) or ((b1 shr 4) and 0x0F)
        return bps1 + 1
    }

    /** WAV：遍历 RIFF chunk 找 "fmt "，读偏移 14 的 wBitsPerSample（u16 LE）。 */
    private fun wavBitDepth(path: String): Int {
        val buf = readHead(path, 1024) ?: return 0
        if (buf.size < 44) return 0
        if (buf[0] != 'R'.code.toByte() || buf[1] != 'I'.code.toByte() ||
            buf[2] != 'F'.code.toByte() || buf[3] != 'F'.code.toByte()) return 0
        if (buf[8] != 'W'.code.toByte() || buf[9] != 'A'.code.toByte() ||
            buf[10] != 'V'.code.toByte() || buf[11] != 'E'.code.toByte()) return 0
        var p = 12
        while (p + 8 <= buf.size) {
            val id = String(buf, p, 4, Charsets.ISO_8859_1)
            val size = (buf[p + 4].toInt() and 0xFF) or
                ((buf[p + 5].toInt() and 0xFF) shl 8) or
                ((buf[p + 6].toInt() and 0xFF) shl 16) or
                ((buf[p + 7].toInt() and 0xFF) shl 24)
            if (id == "fmt ") {
                if (p + 8 + 16 <= buf.size) {
                    val bits = (buf[p + 8 + 14].toInt() and 0xFF) or
                        ((buf[p + 8 + 15].toInt() and 0xFF) shl 8)
                    if (bits in 1..32) return bits
                }
                return 0
            }
            if (size <= 0) return 0
            p += 8 + size
        }
        return 0
    }

    /** ALAC：在文件前 256KB 找 "alac" box（stsd 里）。
     *  ISO BMFF：'alac' box type 之后是 28 字节 AudioSampleEntry 固定头，
     *  然后是 24 字节 ALACSpecificConfig，bitDepth 在 config 第 5 字节（frameLength 4 + version 1）。 */
    private fun alacBitDepth(path: String): Int {
        val buf = readHead(path, 262144) ?: return 0
        val s = String(buf, Charsets.ISO_8859_1)
        var i = s.indexOf("alac")
        while (i >= 0) {
            val bits = alacBitDepthAt(buf, i + 32)  // 4(type) + 28(AudioSampleEntry 固定头)
            if (bits in 1..32) return bits
            i = s.indexOf("alac", i + 1)
        }
        return 0
    }

    /** config = frameLength(4) + compatibleVersion(1) + bitDepth(1) + ...；
     *  frameLength 与 compatibleVersion(<=1) 双校验过滤假 "alac" 匹配。 */
    private fun alacBitDepthAt(buf: ByteArray, configStart: Int): Int {
        if (configStart + 6 > buf.size) return 0
        val frameLength = ((buf[configStart].toInt() and 0xFF) shl 24) or
            ((buf[configStart + 1].toInt() and 0xFF) shl 16) or
            ((buf[configStart + 2].toInt() and 0xFF) shl 8) or
            (buf[configStart + 3].toInt() and 0xFF)
        if (frameLength <= 0 || frameLength > (1 shl 20)) return 0
        val compatibleVersion = buf[configStart + 4].toInt() and 0xFF
        if (compatibleVersion > 1) return 0  // kALACVersion = 1
        val bitDepth = buf[configStart + 5].toInt() and 0xFF
        return if (bitDepth in 1..32) bitDepth else 0
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
            // 【V8.429】收敛到单线程闸门
            MediaProbe.onProbeThread {
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
            }
        } catch (_: Throwable) { 0L }
    }
}
