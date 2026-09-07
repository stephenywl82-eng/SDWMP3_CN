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
            if (!forceRescan && song.bpm > 0) return@filter false
            val path = song.filePath.ifEmpty { song.path }
            if (path.isNotBlank() && !path.startsWith("content://")) {
                // 【v8.21】已缓存（扫过半途中断的）直接跳过，下次扫描接着扫
                if (!forceRescan && BpmKeyCache.get(path) != null) return@filter false
                true
            } else false
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
                        val bpm = detectForSong(context, path)
                        if (bpm > 0) {
                            synchronized(lock) {
                                detected++
                                BpmKeyCache.put(path, bpm, song.key)
                            }
                        }
                        synchronized(lock) {
                            done++
                            onProgress(done, tasksTotal, song.title)
                            // 【v8.21】每 20 首增量落盘：扫一半中断不丢已扫结果
                            if (done % 20 == 0) BpmKeyCache.flush()
                        }
                        i += 4
                        if (done % 12 == 0) delay(5)  // 节流
                    }
                }
            }
        }
        BpmKeyCache.flush()
        Log.i(TAG, "scanLibrary done: $detected detected / $tasksTotal scanned / $total songs (force=$forceRescan)")
        detected
    }
}
