package com.sdw.music.player.utils

import android.util.Log
import java.io.File
import kotlin.math.roundToLong

/**
 * CUE Sheet Parser
 * 支持标准 CUE 格式：
 *   FILE "audio.flac" WAVE
 *     TRACK 01 AUDIO
 *       TITLE "Track Title"
 *       PERFORMER "Artist"
 *       INDEX 01 00:00:00
 *     TRACK 02 AUDIO
 *       TITLE "Track 2"
 *       INDEX 00 05:23:45   ← PREGAP（隐藏音轨）
 *       INDEX 01 05:30:00
 *
 * 关键概念：
 * - CUE 以文件（FILE）为单位，一个 FILE 含多轨（TRACK）
 * - 每轨有 INDEX 00（PREGAP 可选）和 INDEX 01（正式起点）
 * - INDEX 的 mm:ss:ff 格式：mm=分钟，ss=秒，ff=帧（75帧/秒，CD标准）
 * - FLAC 的 STREAMINFO 中 sample_rate 决定帧→采样点换算
 */
object CueParser {
    private const val TAG = "CueParser"

    data class CueFile(
        val filePath: String,
        val tracks: List<CueTrack>
    )

    data class CueTrack(
        val number: Int,           // 1-based
        val title: String?,
        val performer: String?,
        val index01Samples: Long,  // INDEX 01 对应 FLAC 采样点（绝对位置）
        val index00Samples: Long?,  // INDEX 00（PREGAP），null 表示无
        val isPregap: Boolean = false
    ) {
        /** 该轨起点采样（INDEX 00 优先，否则 INDEX 01） */
        val startSamples: Long get() = index00Samples ?: index01Samples
        /** 该轨相对于 INDEX 00 的偏移（INDEX 00 → INDEX 01 的间隔帧） */
        val pregapFrames: Long get() = if (index00Samples != null) {
            (index01Samples - index00Samples) * 75 / sampleRate
        } else 0
        companion object {
            const val sampleRate = 44100 // CUE 帧时钟基准（CD 标准 75fps），换算用
        }
    }

    /**
     * 解析 CUE 文件，返回 CueFile 结构。
     * @param cuePath CUE 文件路径（.cue）
     * @return CueFile 或 null（解析失败）
     */
    fun parse(cuePath: String): CueFile? {
        val file = File(cuePath)
        if (!file.exists()) {
            Log.e(TAG, "CUE not found: $cuePath")
            return null
        }

        var currentFile: String? = null
        var currentTrackNum = 0
        var currentTitle: String? = null
        var currentPerformer: String? = null
        var currentIndex00: Long? = null
        var currentIndex01: Long? = null
        val tracks = mutableListOf<CueTrack>()

        try {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("REM")) return@forEachLine

                val parts = splitCommand(line)
                when (parts[0].uppercase()) {
                    "FILE" -> {
                        // 保存上一 FILE 的最后 track
                        flushTrack(tracks, currentTrackNum, currentTitle, currentPerformer, currentIndex00, currentIndex01)
                        currentTrackNum = 0
                        currentTitle = null
                        currentPerformer = null
                        currentIndex00 = null
                        currentIndex01 = null
                        // FILE "filename" WAVE|AIFF|FLAC|...
                        currentFile = extractQuoted(parts.drop(1).joinToString(" "))
                        if (currentFile != null) {
                            // 尝试还原完整路径（CUE 和音频文件通常同目录）
                            val cueDir = file.parentFile
                            val f = File(cueDir, currentFile!!)
                            if (f.exists()) currentFile = f.absolutePath
                        }
                    }
                    "TRACK" -> {
                        // 保存上一 track
                        flushTrack(tracks, currentTrackNum, currentTitle, currentPerformer, currentIndex00, currentIndex01)
                        currentTrackNum = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        currentTitle = null
                        currentPerformer = null
                        currentIndex00 = null
                        currentIndex01 = null
                    }
                    "TITLE" -> currentTitle = extractQuoted(parts.drop(1).joinToString(" "))
                    "PERFORMER" -> currentPerformer = extractQuoted(parts.drop(1).joinToString(" "))
                    "INDEX" -> {
                        val idxNum = parts.getOrNull(1)?.toIntOrNull()
                        val timeStr = parts.getOrNull(2)
                        if (idxNum != null && timeStr != null) {
                            val samples = parseFrameTime(timeStr)
                            when (idxNum) {
                                0 -> currentIndex00 = samples
                                1 -> currentIndex01 = samples
                            }
                        }
                    }
                }
            }
            // flush 最后一条 track
            flushTrack(tracks, currentTrackNum, currentTitle, currentPerformer, currentIndex00, currentIndex01)

        } catch (e: Exception) {
            Log.e(TAG, "parse exception: ${e.message}", e)
            return null
        }

        if (currentFile == null || tracks.isEmpty()) {
            Log.e(TAG, "No FILE or TRACK found in CUE: $cuePath")
            return null
        }

        Log.i(TAG, "CUE parsed: file=$currentFile tracks=${tracks.size}")
        return CueFile(currentFile!!, tracks)
    }

    /**
     * 根据采样点定位当前轨号（0-based index）
     */
    fun findTrackAt(cueFile: CueFile, samplePosition: Long): CueTrack? {
        // 从后往前找：找到最后一个 startSamples <= samplePosition 的轨
        var found: CueTrack? = null
        for (track in cueFile.tracks) {
            if (track.index01Samples <= samplePosition) {
                found = track
            } else break
        }
        return found
    }

    /**
     * 给定轨号，找下一轨的 INDEX 01 采样点（用于 Gapless 切曲 seek）
     */
    fun nextTrackStartSamples(cueFile: CueFile, currentTrack: CueTrack): Long? {
        val idx = cueFile.tracks.indexOf(currentTrack)
        return if (idx >= 0 && idx < cueFile.tracks.size - 1) {
            cueFile.tracks[idx + 1].index01Samples
        } else null
    }

    // ── private ─────────────────────────────────────────────────────────────────

    private fun flushTrack(
        tracks: MutableList<CueTrack>,
        num: Int,
        title: String?,
        performer: String?,
        idx00: Long?,
        idx01: Long?
    ) {
        if (num <= 0 || idx01 == null) return
        tracks.add(CueTrack(
            number = num,
            title = title,
            performer = performer,
            index01Samples = idx01,
            index00Samples = idx00,
            isPregap = false
        ))
    }

    /** 拆分 "COMMAND arg1 arg2 ..." 保留引号内空格 */
    private fun splitCommand(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val n = line.length
        while (i < n) {
            while (i < n && line[i] == ' ') i++
            if (i >= n) break
            if (line[i] == '"') {
                val start = i + 1
                i++
                while (i < n && line[i] != '"') i++
                result.add(line.substring(start, i.coerceAtMost(n)))
                if (i < n && line[i] == '"') i++
            } else {
                val start = i
                while (i < n && line[i] != ' ') i++
                result.add(line.substring(start, i))
            }
        }
        return result
    }

    /** 提取引号内容 */
    private fun extractQuoted(s: String?): String? {
        if (s == null) return null
        val t = s.trim()
        return if (t.startsWith('"') && t.endsWith('"') && t.length >= 2) {
            t.substring(1, t.length - 1)
        } else t
    }

    /**
     * 解析 mm:ss:ff（分钟:秒:帧）→ 采样点
     * CD 帧率 = 75 frames/second
     * CUE INDEX 时间基于 CD 音频时钟：min*60*75 + sec*75 + frames
     */
    private fun parseFrameTime(time: String): Long {
        // 支持 "05:30:00" 或 "00:00:00"
        val parts = time.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val min  = parts[0].toLongOrNull() ?: 0
                    val sec  = parts[1].toLongOrNull() ?: 0
                    val fr   = parts[2].toLongOrNull() ?: 0
                    // CD 标准帧率 75fps，换算成采样点（乘以 sampleRate/75）
                    // 注意：实际采样点由 FLAC 文件真实 sampleRate 决定
                    // 这里存 CD 帧计数，转换时需乘以 FLAC_sampleRate/75
                    (min * 60 * 75 + sec * 75 + fr)
                }
                2 -> {
                    // 某些 CUE 用 mm:ss（无帧），默认 0 帧
                    val min = parts[0].toLongOrNull() ?: 0
                    val sec = parts[1].toLongOrNull() ?: 0
                    min * 60 * 75 + sec * 75
                }
                else -> 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFrameTime fail: $time → 0")
            0L
        }
    }

    /**
     * 将 CD 帧计数转为 FLAC 真实采样点
     * @param cdFrames CD 帧计数（parseFrameTime 返回值）
     * @param flacSampleRate FLAC 文件真实采样率（Hz）
     */
    fun cdFramesToSamples(cdFrames: Long, flacSampleRate: Int): Long {
        // CD 时钟 = 75 frames/second
        return (cdFrames * flacSampleRate / 75.0).roundToLong()
    }
}
