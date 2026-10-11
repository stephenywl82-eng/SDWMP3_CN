package com.sdw.music.player.core.audio

import android.content.Context
import android.util.Log
import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 【v8.13】BPM 批量扫描器
 * - FLAC 走 dr_flac 直解，其他格式走 NDKDecoder（AMediaCodec → float）
 * - 每首取前 30 秒，下采样 8kHz 单声道 → native 自相关检测（bpm_detector.h）
 * - 结果写 BpmKeyCache（SharedPreferences 持久化），下次扫描直接读
 * - 只测 bpm<=0 的歌；tag 已有 BPM 的跳过
 * 【v8.16+】非 FLAC 走 fd 方式打开（scoped storage 下 AMediaExtractor 路径方式 -10002）
 */
object BpmScanner {

    private const val TAG = "BpmScanner"

    init {
        try {
            System.loadLibrary("oboe_bridge")
        } catch (e: Throwable) {
            Log.w(TAG, "loadLibrary failed: ${e.message}")
        }
    }

    // JNI：float[] + sampleRate → BPM（-1 = 检测失败）
    external fun nativeDetectBpm(samples: FloatArray, sampleRate: Int): Int

    // JNI：文件路径 → BPM（-1 = 失败）
    external fun nativeDetectBpmFromFile(path: String): Int

    // JNI：fd → BPM（-1 = 失败；scoped storage 安全方式）
    external fun nativeDetectBpmFromFd(path: String?, fd: Int, offset: Long, length: Long): Int

    // 【v8.157】扩展版：返回 int[]{bpm, firstBeatMs, confPct}（firstBeat=-1 未知，confPct=conf*1000）
    // 【v8.166】网格之后追加 [chorusMs, keyCode]；keyCode：小调 1..12 / 大调 101..112 / -1 未知
    external fun nativeDetectBpmFromFdEx(path: String?, fd: Int, offset: Long, length: Long): IntArray?
    external fun nativeDetectBpmFromFileEx(path: String): IntArray?

    /** 【v8.166】native Camelot 编码 → "8A"/"8B" 字符串 */
    private fun camelotFromCode(code: Int): String =
        if (code >= 100) "${code - 100}B" else "${code}A"

    /** 【v8.166】标签里的传统调性记号("Am"/"F#")→ Camelot。
     *  与 AudioAnalyzer.toCamelot 同一张映射表（AudioAnalyzer 在默认包里，本类无法直接引用）。 */
    private fun toCamelot(raw: String): String {
        val t = raw.trim()
        if (t.matches(Regex("^(1[0-2]|[1-9])[AB]$"))) return t
        val isMinor = t.endsWith("m", ignoreCase = true)
        val root = if (isMinor) t.dropLast(1) else t
        val semitone = when (root) {
            "C" -> 0; "C#" -> 1; "Db" -> 1; "D" -> 2; "D#" -> 3; "Eb" -> 3
            "E" -> 4; "F" -> 5; "F#" -> 6; "Gb" -> 6; "G" -> 7; "G#" -> 8
            "Ab" -> 8; "A" -> 9; "A#" -> 10; "Bb" -> 10; "B" -> 11
            else -> return ""
        }
        val majorCamelot = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        val minorCamelot = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val number = if (isMinor) minorCamelot[semitone] else majorCamelot[semitone]
        return "$number${if (isMinor) "A" else "B"}"
    }

    /** 【v8.157】检测完整信息：[bpm, firstBeatMs, confPct]，失败返回 null */
    suspend fun detectFullForSong(context: Context, path: String): IntArray? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        try {
            val f = File(path)
            if (!f.exists()) return@withContext null
            val arr = if (path.endsWith(".flac", ignoreCase = true)) {
                nativeDetectBpmFromFileEx(f.absolutePath)
            } else {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    nativeDetectBpmFromFdEx(f.absolutePath, pfd.fd, 0, pfd.statSize)
                }
            }
            val bpm = arr?.getOrNull(0) ?: -1
            if (bpm in 40..220) arr else null
        } catch (e: Throwable) {
            Log.w(TAG, "detectFullForSong failed: $path ${e.message}")
            null
        }
    }

    /**
     * 扫描单曲 BPM（不落缓存，供单曲补测）
     * @return 60..220 的 BPM，失败 0
     */
    suspend fun detectForSong(context: Context, path: String): Int = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext 0
        try {
            val f = File(path)
            if (!f.exists()) return@withContext 0
            val bpm = if (path.endsWith(".flac", ignoreCase = true)) {
                nativeDetectBpmFromFile(f.absolutePath)
            } else {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    nativeDetectBpmFromFd(f.absolutePath, pfd.fd, 0, pfd.statSize)
                }
            }
            if (bpm in 40..220) bpm else 0
        } catch (e: Throwable) {
            Log.w(TAG, "detectForSong failed: $path ${e.message}")
            0
        }
    }

    /**
     * 批量扫描曲库中 bpm<=0 的歌（后台）。
     * @param onProgress (done, total, currentTitle)
     * @return 检测到 BPM 并入库的歌曲数
     */
    suspend fun scanLibrary(
        context: Context,
        songs: List<Song>,
        forceRescan: Boolean = false,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        var detected = 0
        var done = 0
        val total = songs.size
        val lock = Any()

        // 预过滤：只扫需要测的歌（非 force 时跳过已缓存/已有 BPM 的）
        val tasks = songs.filter { song ->
            val path = song.filePath.ifEmpty { song.path }
            if (path.isBlank() || path.startsWith("content://")) return@filter false
            if (forceRescan) return@filter true
            // 【v8.157】新引擎优先：无缓存 / 旧引擎缓存 → 重扫；同引擎缓存 → 跳过
            // （不再用 song.bpm>0 跳过，否则旧引擎/标签值永远不会被新引擎覆盖）
            val e = BpmKeyCache.getEntry(path)
            e == null || e.engine < BpmKeyCache.ENGINE
        }
        val tasksTotal = tasks.size

        // 【v8.16】4 线程并行扫描（native 每首独立实例，线程安全；SharedPreferences 线程安全）
        coroutineScope {
            repeat(4) { worker ->
                launch {
                    var i = worker
                    while (i < tasks.size) {
                        val song = tasks[i]
                        val path = song.filePath.ifEmpty { song.path }
                        val full = detectFullForSong(context, path)
                        val bpm = full?.getOrNull(0) ?: -1
                        if (bpm in 40..220) {
                            synchronized(lock) {
                                detected++
                                val firstBeat = full?.getOrNull(1) ?: -1
                                val conf = (full?.getOrNull(2) ?: 0) / 1000f
                                // 【P2-2】解析动态 beatgrid：段数 N，后接 N 组 {bpm, firstBeatMs, startMs}
                                val nseg = full?.getOrNull(3) ?: 0
                                val grid = ArrayList<IntArray>()
                                if (nseg > 0) {
                                    var w = 4
                                    for (s in 0 until nseg) {
                                        val gb = full?.getOrNull(w) ?: break
                                        val gf = full?.getOrNull(w + 1) ?: break
                                        val gs = full?.getOrNull(w + 2) ?: break
                                        grid.add(intArrayOf(gb, gf, gs)); w += 3
                                    }
                                }
                                // 【v8.166】网格之后追加：副歌起点 + 调性（Camelot 编码）
                                val tailIdx = 4 + 3 * grid.size
                                val chorusMs = full?.getOrNull(tailIdx) ?: -1
                                val keyCode = full?.getOrNull(tailIdx + 1) ?: -1
                                val key = when {
                                    song.key.isNotBlank() -> toCamelot(song.key).takeIf { it.isNotBlank() } ?: (if (keyCode > 0) camelotFromCode(keyCode) else "")
                                    keyCode > 0 -> camelotFromCode(keyCode)
                                    else -> ""
                                }
                                val struct = BpmKeyCache.Structure(
                                    chorusMs = if (chorusMs > 0) chorusMs else -1
                                )
                                // 【V8.304】尾部 [x]=bpmMilli（浮点 BPM×1000，0=无）→ 对拍用
                                val bpmMilli = full?.getOrNull(tailIdx + 4) ?: 0
                                val bpmF = if (bpmMilli > 0) bpmMilli / 1000f else 0f
                                BpmKeyCache.putEx(path, bpm, key, firstBeat, conf, BpmKeyCache.ENGINE, grid, struct, bpmF)
                            }
                        }
                        synchronized(lock) {
                            done++
                            onProgress(done, tasksTotal, song.title)
                            // 【v8.21】每 20 首增量落盘：扫一半中断不丢已扫结果
                            if (done % 20 == 0) BpmKeyCache.scheduleFlush()
                        }
                        i += 4
                        if (done % 12 == 0) delay(5)  // 节流
                    }
                }
            }
        }
        BpmKeyCache.scheduleFlush()
        Log.i(TAG, "scanLibrary done: $detected detected / $tasksTotal scanned / $total songs (force=$forceRescan)")
        detected
    }
}
