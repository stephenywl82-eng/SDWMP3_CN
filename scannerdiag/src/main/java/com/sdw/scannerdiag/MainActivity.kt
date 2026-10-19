package com.sdw.scannerdiag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * SDW 扫描诊断器 —— 仅复刻主程序 SongRepository.rescanFromMediaStore 的「逐文件探测」逻辑，
 * 不播放、不写库，唯一目的：定位「扫描永远卡在一处」到底卡在哪一文件、哪一步。
 *
 * 每首歌做两步（与主程序一致）：
 *   STEP1 probeDurationMs -> MediaMetadataRetriever.setDataSource(path)
 *   STEP2 readBpmAndKey   -> FLAC Vorbis / ID3v2 标签解析
 * 每步在独立线程跑 + 10s 硬超时；超时就判定该文件在此步骤卡死，继续下一首，绝不整轮卡死。
 * 结束把全部「卡死文件」列成清单，便于直接回传。
 */
class MainActivity : android.app.Activity() {

    private lateinit var btnScan: Button
    private lateinit var btnCopy: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    private val logLines = StringBuilder()
    private val hungFiles = StringBuilder()
    private lateinit var logFile: File
    private var scanning = false
    private val REQ_PERM = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnScan = findViewById(R.id.btnScan)
        btnCopy = findViewById(R.id.btnCopy)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        logFile = File(getExternalFilesDir(null), "scan_diag.log")
        btnScan.setOnClickListener { checkPermAndScan() }
        btnCopy.setOnClickListener { copyLog() }
    }

    private fun checkPermAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startScan() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    @Deprecated("Deprecated in API 33")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else toast("需要存储权限才能扫描")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun out(line: String) {
        logLines.appendLine(line)
        synchronized(logFile) { logFile.appendText(line + "\n") }
        runOnUiThread {
            tvLog.text = logLines.toString()
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        btnScan.isEnabled = false
        logLines.setLength(0)
        hungFiles.setLength(0)
        logFile.writeText("")
        Thread({ runScan() }, "scan-thread").start()
    }

    private fun runScan() {
        out("=== SDW 扫描诊断  开始 ${java.util.Date()} ===")
        out("复刻主程序 SongRepository.rescanFromMediaStore 的逐文件探测：")
        out("  STEP1 probeDurationMs -> MediaMetadataRetriever.setDataSource(path)")
        out("  STEP2 readBpmAndKey   -> FLAC Vorbis / ID3v2 标签解析")
        out("每步独立线程 + 10s 硬超时；超时即判定该文件在此步骤卡死，继续下一首。")
        out("")

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        var total = 0
        var processed = 0
        var hung = 0
        var slow = 0

        try {
            val c = contentResolver.query(uri, projection, null, null, sortOrder)
            if (c == null) {
                out("query 返回 null（未授权或系统异常）")
            } else {
                c.use {
                    total = it.count
                    out("MediaStore 音频行数: $total")
                    val dataIdx = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (it.moveToNext()) {
                        processed++
                        val path = it.getString(dataIdx) ?: ""
                        if (path.isBlank()) continue
                        out("[$processed/$total] $path")

                        val pr = runWithTimeout("PROBE", 10000L) { probeDurationMs(path) }
                        val pSlow = pr.elapsedMs > 3000
                        if (pr.timedOut) hung++ else if (pSlow) slow++
                        out("   PROBE: ${flag(pr.timedOut, pSlow)}耗时 ${pr.elapsedMs}ms ${pr.note ?: "dur=${pr.value}"}")
                        if (pr.timedOut) hungFiles.appendLine("PROBE HUNG: $path")

                        val tr = runWithTimeout("TAGS", 10000L) { readBpmAndKey(path) }
                        val tSlow = tr.elapsedMs > 3000
                        if (tr.timedOut) hung++ else if (tSlow) slow++
                        out("   TAGS : ${flag(tr.timedOut, tSlow)}耗时 ${tr.elapsedMs}ms ${tr.note ?: "bpm=${tr.value?.first},key=${tr.value?.second}"}")
                        if (tr.timedOut) hungFiles.appendLine("TAGS HUNG: $path")

                        if (processed % 25 == 0) runOnUiThread {
                            tvStatus.text = "进度 $processed/$total  卡死=$hung 慢(>3s)=$slow"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out("SCAN EXCEPTION: ${e.message}")
            Log.e("ScannerDiag", "scan", e)
        }

        out("")
        out("=== 扫描结束 总行数=$total 处理=$processed 卡死(>10s)=$hung 慢(>3s)=$slow ===")
        if (hungFiles.isNotEmpty()) {
            out("")
            out("★★★ 卡死文件清单（这些就是主程序扫描卡住的根因） ★★★")
            out(hungFiles.toString().trimEnd())
        } else {
            out("未检测到卡死（>10s 超时）的文件；如有“慢”文件见上方 △慢△ 标记。")
        }
        runOnUiThread {
            tvStatus.text = "完成 处理=$processed 卡死=$hung 慢=$slow"
            btnScan.isEnabled = true
            btnCopy.isEnabled = true
        }
        scanning = false
    }

    private fun flag(timedOut: Boolean, slow: Boolean): String =
        if (timedOut) "★HUNG★ " else if (slow) "△慢△ " else ""

    private data class TimedResult<T>(
        val value: T?,
        val elapsedMs: Long,
        val timedOut: Boolean,
        val note: String?
    )

    /** 在独立线程执行 block，超过 ms 毫秒未返回即判超时（不取消底层调用，仅放弃等待、继续下一首）。 */
    private fun <T> runWithTimeout(tag: String, ms: Long, block: () -> T): TimedResult<T> {
        val task = FutureTask<T> { block() }
        val th = Thread(task, "diag-$tag")
        th.start()
        val start = System.currentTimeMillis()
        return try {
            val v = task.get(ms, TimeUnit.MILLISECONDS)
            TimedResult(v, System.currentTimeMillis() - start, false, null)
        } catch (_: TimeoutException) {
            TimedResult(null, System.currentTimeMillis() - start, true, "TIMEOUT@${ms}ms")
        } catch (e: Exception) {
            TimedResult(null, System.currentTimeMillis() - start, false, e.message ?: e.javaClass.simpleName)
        }
    }

    // ===== 以下逐字复刻主程序 SongRepository 的逐文件探测逻辑（V9.19） =====
    // 注意：诊断版去掉了内层 8s 超时，完全交给 runWithTimeout 兜底，
    // 这样「原生层 setDataSource 挂死」能被外层 10s 精确捕获并标红。

    private fun probeDurationMs(path: String): Long {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            return mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            return 0L
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readBpmAndKey(filePath: String): Pair<Int, String> {
        return try {
            when {
                filePath.endsWith(".flac", true) -> readFlacVorbisComments(filePath)
                filePath.endsWith(".mp3", true) -> readId3v2Tags(filePath)
                filePath.endsWith(".ogg", true) -> readFlacVorbisComments(filePath)
                else -> Pair(0, "")
            }
        } catch (e: Exception) {
            Pair(0, "")
        }
    }

    private fun readFlacVorbisComments(filePath: String): Pair<Int, String> {
        val raf = RandomAccessFile(filePath, "r")
        try {
            val magic = ByteArray(4)
            raf.read(magic)
            if (String(magic) != "fLaC") return Pair(0, "")
            var bpm = 0
            var key = ""
            var blocksRead = 0
            while (true) {
                if (++blocksRead > 64) break
                val header = ByteArray(4)
                if (raf.read(header) < 4) break
                val blockType = header[0].toInt() and 0x7F
                val isLast = (header[0].toInt() and 0x80) != 0
                val dataLength = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                // 【V9.19】退化护栏：损坏/全零填充的 FLAC 会出现「blockType=0 且 dataLength=0 且非末块」，
                // 导致 skipBytes(0) 后无限读零头 → 解析死循环。真·FLAC 必有 STREAMINFO(34) 且末块置 isLast。
                if (blockType == 0 && dataLength == 0 && !isLast) break
                if (blockType == 4) {
                    val data = ByteArray(dataLength)
                    raf.read(data)
                    val comments = parseVorbisComments(data)
                    comments["BPM"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    if (bpm == 0) comments["bpm"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    if (bpm == 0) comments["TEMPO"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    key = comments["INITIALKEY"] ?: comments["initialkey"] ?:
                            comments["KEY"] ?: comments["key"] ?: ""
                    if (key.length > 10) key = ""
                    if (key.isNotBlank()) key = toCamelot(key)
                    return Pair(bpm, key)
                } else {
                    raf.skipBytes(dataLength)
                }
                if (isLast) break
            }
            return Pair(bpm, key)
        } finally {
            raf.close()
        }
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val vendorLen = buf.int
            buf.position(buf.position() + vendorLen)
            val commentCount = buf.int
            for (i in 0 until commentCount) {
                if (buf.remaining() < 4) break
                val commentLen = buf.int
                if (buf.remaining() < commentLen) break
                val commentBytes = ByteArray(commentLen)
                buf.get(commentBytes)
                val comment = String(commentBytes, Charsets.UTF_8)
                val eqIndex = comment.indexOf('=')
                if (eqIndex > 0) {
                    val k = comment.substring(0, eqIndex)
                    val v = comment.substring(eqIndex + 1)
                    if (!result.containsKey(k)) result[k] = v
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun readId3v2Tags(filePath: String): Pair<Int, String> {
        val raf = RandomAccessFile(filePath, "r")
        try {
            val magic = ByteArray(3)
            raf.read(magic)
            if (String(magic) != "ID3") return Pair(0, "")
            raf.skipBytes(3)
            val sizeBytes = ByteArray(4)
            raf.read(sizeBytes)
            val tagSize = ((sizeBytes[0].toInt() and 0x7F) shl 21) or
                    ((sizeBytes[1].toInt() and 0x7F) shl 14) or
                    ((sizeBytes[2].toInt() and 0x7F) shl 7) or
                    (sizeBytes[3].toInt() and 0x7F)
            val tagData = ByteArray(tagSize)
            raf.read(tagData)
            var bpm = 0
            var key = ""
            var pos = 0
            while (pos + 10 <= tagData.size) {
                val frameId = String(tagData, pos, 4, Charsets.ISO_8859_1)
                if (frameId[0] < 'A' || frameId[0] > 'Z') break
                val frameSize = ((tagData[pos + 4].toInt() and 0xFF) shl 24) or
                        ((tagData[pos + 5].toInt() and 0xFF) shl 16) or
                        ((tagData[pos + 6].toInt() and 0xFF) shl 8) or
                        (tagData[pos + 7].toInt() and 0xFF)
                if (frameSize <= 0 || frameSize > tagData.size) break
                val frameData = tagData.copyOfRange(pos + 10, minOf(pos + 10 + frameSize, tagData.size))
                when (frameId) {
                    "TBPM" -> {
                        val t = decodeId3Text(frameData)
                        t.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    }
                    "TKEY" -> {
                        val t = decodeId3Text(frameData)
                        if (t.length in 1..10) key = toCamelot(t)
                    }
                }
                pos += 10 + frameSize
            }
            return Pair(bpm, key)
        } finally {
            raf.close()
        }
    }

    private fun decodeId3Text(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return try {
            val encoding = data[0].toInt() and 0xFF
            val textBytes = data.copyOfRange(1, data.size)
            when (encoding) {
                0 -> String(textBytes, Charsets.ISO_8859_1).trimEnd('\u0000')
                1 -> String(textBytes, Charsets.UTF_16).trimEnd('\u0000')
                2 -> String(textBytes, Charsets.UTF_16BE).trimEnd('\u0000')
                3 -> String(textBytes, Charsets.UTF_8).trimEnd('\u0000')
                else -> String(textBytes, Charsets.UTF_8).trimEnd('\u0000')
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 诊断无需真实调性转换，仅复刻调用路径以暴露潜在 hang。 */
    private fun toCamelot(k: String): String = k

    private fun copyLog() {
        val text = logLines.toString()
        if (text.isBlank()) {
            toast("还没有日志，先扫描")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("scan_diag", text))
        toast("日志已复制到剪贴板")
    }
}
